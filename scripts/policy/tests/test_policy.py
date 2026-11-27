import json
from pathlib import Path

import pytest

from ott_policy import cli
from ott_policy.deploy import DeployError, deploy
from ott_policy.model import PolicyError, load_dir, parse
from ott_policy.plan import DEPLOY_KEY, impact, plan

KR_2026 = {"notBefore": "2026-01-01T00:00:00+09:00", "notAfter": "2028-01-01T00:00:00+09:00"}


def write(directory: Path, content_id: str, rights: dict[str, object], name: str = "") -> None:
    directory.mkdir(parents=True, exist_ok=True)
    body = json.dumps({"contentId": content_id, "rights": rights})
    (directory / f"{name or content_id}.yaml").write_text(body, encoding="utf-8")


def test_policy_value_is_compact_utc_epoch_seconds(tmp_path: Path) -> None:
    write(
        tmp_path,
        "movie-100",
        {
            "KR": KR_2026,
            "ID": {
                "notBefore": "2026-10-01T00:00:00+07:00",
                "notAfter": "2027-01-01T00:00:00+07:00",
            },
        },
    )

    policy = load_dir(tmp_path)["movie-100"]

    assert policy.key == "policy:movie-100"
    assert policy.value(14) == (
        '{"rev":14,"rights":{"ID":[1790787600,1798736400],"KR":[1767193200,1830265200]}}'
    )


@pytest.mark.parametrize(
    ("document", "message"),
    [
        ({"contentId": "movie-100"}, "exactly contentId and rights"),
        ({"contentId": "Movie", "rights": {}}, "invalid contentId"),
        ({"contentId": "other", "rights": {}}, "file name must be other.yaml"),
        ({"contentId": "movie-100", "rights": {"XX": KR_2026}}, "unknown ISO 3166-1"),
        (
            {
                "contentId": "movie-100",
                "rights": {"KR": {"notBefore": "2026-01-01T00:00:00+09:00"}},
            },
            "exactly notBefore and notAfter",
        ),
        (
            {
                "contentId": "movie-100",
                "rights": {"KR": {**KR_2026, "notBefore": "2026-01-01T00:00:00"}},
            },
            "needs a UTC offset",
        ),
        (
            {
                "contentId": "movie-100",
                "rights": {"KR": {**KR_2026, "notAfter": "2025-01-01T00:00:00Z"}},
            },
            "earlier than notAfter",
        ),
        (
            {"contentId": "movie-100", "rights": {"KR": {**KR_2026, "notBefore": 1767193200}}},
            "quote the timestamp",
        ),
    ],
)
def test_invalid_policies_are_rejected(document: object, message: str) -> None:
    with pytest.raises(PolicyError, match=message):
        parse("movie-100", document)


def test_unquoted_yaml_timestamp_is_rejected(tmp_path: Path) -> None:
    # YAML이 따옴표 없는 시각을 datetime으로 바꾸면 시간대를 잃을 수 있다
    (tmp_path / "movie-100.yaml").write_text(
        "contentId: movie-100\nrights:\n  KR:\n    notBefore: 2026-01-01T00:00:00+09:00\n"
        '    notAfter: "2028-01-01T00:00:00+09:00"\n',
        encoding="utf-8",
    )

    with pytest.raises(PolicyError, match="quote the timestamp"):
        load_dir(tmp_path)


def test_value_over_one_kilobyte_is_rejected() -> None:
    countries = [
        "KR",
        "US",
        "JP",
        "ID",
        "DE",
        "FR",
        "GB",
        "CA",
        "AU",
        "BR",
        "IN",
        "MX",
        "ES",
        "IT",
        "NL",
        "SE",
        "NO",
        "FI",
        "DK",
        "PL",
        "TR",
        "TH",
        "VN",
        "PH",
        "MY",
        "SG",
        "TW",
        "HK",
        "NZ",
        "AR",
        "CL",
        "CO",
        "PE",
        "ZA",
        "EG",
        "SA",
        "AE",
        "IL",
    ]
    rights = dict.fromkeys(countries, KR_2026)

    with pytest.raises(PolicyError, match="max 1024"):
        parse("movie-100", {"contentId": "movie-100", "rights": rights})


def test_plan_writes_only_changed_content_and_deletes_removed(tmp_path: Path) -> None:
    write(tmp_path, "same", {"KR": KR_2026})
    write(tmp_path, "changed", {"KR": KR_2026, "US": KR_2026})
    write(tmp_path, "new", {"KR": KR_2026})
    desired = load_dir(tmp_path)
    current = {
        "policy:same": desired["same"].value(3),
        "policy:changed": parse(
            "changed", {"contentId": "changed", "rights": {"KR": KR_2026}}
        ).value(3),
        "policy:gone": desired["same"].value(3),
    }

    result = plan(current, desired, rev=4)

    assert sorted(result.puts) == ["policy:changed", "policy:new"]
    assert json.loads(result.puts["policy:changed"])["rev"] == 4
    assert result.deletes == ["policy:gone"]


class FakeStore:
    def __init__(self, items: dict[str, str]) -> None:
        self.items_ = dict(items)
        self.updates: list[tuple[str, dict[str, str], list[str]]] = []

    def etag(self) -> str:
        return "KV1"

    def get(self, key: str) -> str | None:
        return self.items_.get(key)

    def items(self, prefix: str) -> dict[str, str]:
        return {k: v for k, v in self.items_.items() if k.startswith(prefix)}

    def update(self, etag: str, puts: dict[str, str], deletes: list[str]) -> None:
        self.updates.append((etag, puts, deletes))


def test_deploy_issues_the_next_rev_with_the_deploy_record(tmp_path: Path) -> None:
    write(tmp_path, "movie-100", {"KR": KR_2026})
    store = FakeStore({DEPLOY_KEY: json.dumps({"rev": 13, "commit": "aaa", "at": 1})})

    result = deploy(store, load_dir(tmp_path), "bbb", lambda old, new: (old, new) == ("aaa", "bbb"))

    assert result is not None
    assert result.rev == 14
    etag, puts, deletes = store.updates[0]
    assert etag == "KV1"
    assert json.loads(puts["policy:movie-100"])["rev"] == 14
    assert json.loads(puts[DEPLOY_KEY])["rev"] == 14
    assert json.loads(puts[DEPLOY_KEY])["commit"] == "bbb"
    assert deletes == []


def test_rollback_is_a_new_rev(tmp_path: Path) -> None:
    # rev 13에 잘못된 정책이 나갔고, 이전 내용(KR만)을 되살리는 revert가 rev 14로 나간다
    write(tmp_path, "movie-100", {"KR": KR_2026})
    bad = parse("movie-100", {"contentId": "movie-100", "rights": {}}).value(13)
    store = FakeStore(
        {"policy:movie-100": bad, DEPLOY_KEY: json.dumps({"rev": 13, "commit": "bad", "at": 1})}
    )

    result = deploy(store, load_dir(tmp_path), "revert", lambda old, new: True)

    assert result is not None
    assert result.rev == 14
    assert json.loads(store.updates[0][1]["policy:movie-100"])["rights"]["KR"]


def test_older_commit_cannot_overwrite_newer_policies(tmp_path: Path) -> None:
    write(tmp_path, "movie-100", {"KR": KR_2026})
    store = FakeStore({DEPLOY_KEY: json.dumps({"rev": 20, "commit": "newer", "at": 1})})

    with pytest.raises(DeployError, match="older commit"):
        deploy(store, load_dir(tmp_path), "older", lambda old, new: False)
    assert store.updates == []


def test_nothing_to_deploy_leaves_the_store_alone(tmp_path: Path) -> None:
    write(tmp_path, "movie-100", {"KR": KR_2026})
    desired = load_dir(tmp_path)
    store = FakeStore(
        {
            "policy:movie-100": desired["movie-100"].value(7),
            DEPLOY_KEY: json.dumps({"rev": 7, "commit": "a", "at": 1}),
        }
    )

    assert deploy(store, desired, "b", lambda old, new: True) is None
    assert store.updates == []


def test_impact_preview_names_what_opens_and_closes(tmp_path: Path) -> None:
    base, head = tmp_path / "base", tmp_path / "head"
    write(base, "movie-100", {"KR": KR_2026, "ID": KR_2026})
    write(base, "gone", {"KR": KR_2026})
    write(
        head,
        "movie-100",
        {"KR": {**KR_2026, "notAfter": "2027-01-01T00:00:00+09:00"}, "US": KR_2026},
    )

    lines = impact(load_dir(base), load_dir(head))

    assert lines == [
        "- `gone`: 정책 삭제 → 모든 국가 차단 (POLICY_UNAVAILABLE)",
        "- `movie-100` ID: 허용 → 차단 (GEO_BLOCKED)",
        "- `movie-100` KR: 기간 [2025-12-31T15:00Z, 2027-12-31T15:00Z)"
        " → [2025-12-31T15:00Z, 2026-12-31T15:00Z)",
        "- `movie-100` US: 차단 → 허용 [2025-12-31T15:00Z, 2027-12-31T15:00Z)",
    ]


def test_check_command_fails_when_one_pr_changes_too_many_keys(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    head = tmp_path / "head"
    for i in range(50):
        write(head, f"movie-{i}", {"KR": KR_2026})

    code = cli.main(["check", "--policies", str(head), "--base", str(tmp_path / "empty")])

    assert code == cli.EXIT_FAILED
    assert "50 policy keys" in capsys.readouterr().err


def test_check_command_prints_the_preview(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    write(tmp_path / "head", "movie-100", {"KR": KR_2026})

    code = cli.main(
        ["check", "--policies", str(tmp_path / "head"), "--base", str(tmp_path / "none")]
    )

    assert code == 0
    out = capsys.readouterr().out
    assert "바뀌는 정책 키 1개" in out
    assert "`movie-100`: 새 정책" in out
