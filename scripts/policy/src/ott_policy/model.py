import json
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import pycountry
import yaml

# api AdminContentController의 contentId 규칙과 같다
CONTENT_ID = re.compile(r"[a-z0-9]([a-z0-9-]*[a-z0-9])?")
CONTENT_ID_MAX = 40
KEY_PREFIX = "policy:"
# KVS 값 한도 (design.md §6). rev가 가장 길어질 때로 잰다
VALUE_MAX_BYTES = 1024
REV_MAX = 2**31 - 1
COUNTRIES = frozenset(country.alpha_2 for country in pycountry.countries)


@dataclass(frozen=True)
class Policy:
    content_id: str
    # 국가 → (notBefore, notAfter) epoch 초. notAfter는 배타적
    rights: dict[str, tuple[int, int]]

    @property
    def key(self) -> str:
        return KEY_PREFIX + self.content_id

    def value(self, rev: int) -> str:
        rights = {country: list(self.rights[country]) for country in sorted(self.rights)}
        return json.dumps({"rev": rev, "rights": rights}, separators=(",", ":"))


class PolicyError(Exception):
    pass


def load_dir(directory: Path) -> dict[str, Policy]:
    """policies/*.yaml을 읽는다. 문제를 모두 모아 PolicyError 하나로 던진다."""
    policies: dict[str, Policy] = {}
    problems: list[str] = []
    if not directory.is_dir():
        return policies
    for path in sorted(directory.glob("*.y*ml")):
        try:
            policy = parse(path.stem, yaml.safe_load(path.read_text(encoding="utf-8")))
        except (PolicyError, yaml.YAMLError) as e:
            problems.append(f"{path.name}: {e}")
            continue
        policies[policy.content_id] = policy
    if problems:
        raise PolicyError("\n".join(problems))
    return policies


def parse(stem: str, document: Any) -> Policy:
    if not isinstance(document, dict) or set(document) != {"contentId", "rights"}:
        raise PolicyError("needs exactly contentId and rights")
    content_id = document["contentId"]
    if not isinstance(content_id, str) or not _valid_id(content_id):
        raise PolicyError(f"invalid contentId {content_id!r}")
    if content_id != stem:
        raise PolicyError(f"file name must be {content_id}.yaml")
    raw_rights = document["rights"]
    if not isinstance(raw_rights, dict):
        raise PolicyError("rights must be a mapping of country codes")
    rights: dict[str, tuple[int, int]] = {}
    for country, period in raw_rights.items():
        if country not in COUNTRIES:
            raise PolicyError(f"unknown ISO 3166-1 alpha-2 code {country!r}")
        rights[country] = _period(country, period)
    policy = Policy(content_id, rights)
    size = len(policy.value(REV_MAX).encode("utf-8"))
    if size > VALUE_MAX_BYTES:
        raise PolicyError(f"KVS value is {size} bytes (max {VALUE_MAX_BYTES})")
    return policy


def parse_value(value: str) -> tuple[int, dict[str, tuple[int, int]]]:
    """KVS에 있는 값을 (rev, rights)로. 배포 계획에서 내용 비교에 쓴다."""
    document = json.loads(value)
    rights = {country: (period[0], period[1]) for country, period in document["rights"].items()}
    return int(document["rev"]), rights


def _valid_id(content_id: str) -> bool:
    return len(content_id) <= CONTENT_ID_MAX and CONTENT_ID.fullmatch(content_id) is not None


def _period(country: str, period: Any) -> tuple[int, int]:
    if not isinstance(period, dict) or set(period) != {"notBefore", "notAfter"}:
        raise PolicyError(f"{country}: needs exactly notBefore and notAfter")
    not_before = _epoch(country, "notBefore", period["notBefore"])
    not_after = _epoch(country, "notAfter", period["notAfter"])
    if not_before >= not_after:
        raise PolicyError(f"{country}: notBefore must be earlier than notAfter")
    return not_before, not_after


def _epoch(country: str, name: str, value: Any) -> int:
    # 따옴표 없는 시각은 YAML이 datetime으로 바꾸면서 시간대를 버릴 수 있다. 문자열만 받는다
    if not isinstance(value, str):
        raise PolicyError(f"{country}.{name}: quote the timestamp")
    try:
        moment = datetime.fromisoformat(value)
    except ValueError as e:
        raise PolicyError(f"{country}.{name}: not an ISO 8601 timestamp: {value}") from e
    if moment.tzinfo is None:
        raise PolicyError(f"{country}.{name}: needs a UTC offset (e.g. +09:00): {value}")
    return int(moment.timestamp())
