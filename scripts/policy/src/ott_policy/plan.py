import json
from dataclasses import dataclass, field
from datetime import UTC, datetime

from ott_policy.model import KEY_PREFIX, Policy, parse_value

# UpdateKeys 한 요청에 50개(put+delete). 하나는 _deploy 메타 키 (design.md §8)
MAX_POLICY_CHANGES = 49
DEPLOY_KEY = "_deploy"


@dataclass
class Plan:
    puts: dict[str, str] = field(default_factory=dict)
    deletes: list[str] = field(default_factory=list)

    @property
    def change_count(self) -> int:
        return len(self.puts) + len(self.deletes)


def plan(current: dict[str, str], desired: dict[str, Policy], rev: int) -> Plan:
    """current는 KVS의 policy:* 키와 값. 내용이 같으면 rev가 달라도 다시 쓰지 않는다."""
    result = Plan()
    for policy in desired.values():
        existing = current.get(policy.key)
        if existing is None or parse_value(existing)[1] != policy.rights:
            result.puts[policy.key] = policy.value(rev)
    result.deletes = sorted(key for key in current if key.removeprefix(KEY_PREFIX) not in desired)
    return result


def deploy_record(rev: int, commit: str, now: datetime | None = None) -> str:
    at = int((now or datetime.now(UTC)).timestamp())
    return json.dumps({"rev": rev, "commit": commit, "at": at}, separators=(",", ":"))


def impact(base: dict[str, Policy], head: dict[str, Policy]) -> list[str]:
    """PR 코멘트용 영향 미리보기. 국가 단위로 무엇이 열리고 막히는지 적는다."""
    lines: list[str] = []
    for content_id in sorted(set(base) | set(head)):
        before = base[content_id].rights if content_id in base else {}
        after = head[content_id].rights if content_id in head else {}
        if content_id not in head:
            lines.append(f"- `{content_id}`: 정책 삭제 → 모든 국가 차단 (POLICY_UNAVAILABLE)")
            continue
        if content_id not in base:
            lines.append(f"- `{content_id}`: 새 정책")
        for country in sorted(set(before) | set(after)):
            if country not in after:
                lines.append(f"- `{content_id}` {country}: 허용 → 차단 (GEO_BLOCKED)")
            elif country not in before:
                lines.append(f"- `{content_id}` {country}: 차단 → 허용 {_period(after[country])}")
            elif before[country] != after[country]:
                was, now = _period(before[country]), _period(after[country])
                lines.append(f"- `{content_id}` {country}: 기간 {was} → {now}")
    return lines


def _period(period: tuple[int, int]) -> str:
    def iso(epoch: int) -> str:
        return datetime.fromtimestamp(epoch, UTC).strftime("%Y-%m-%dT%H:%MZ")

    return f"[{iso(period[0])}, {iso(period[1])})"
