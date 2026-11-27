import json
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING, Protocol

import boto3
from botocore.exceptions import ClientError

from ott_policy.model import KEY_PREFIX, Policy
from ott_policy.plan import DEPLOY_KEY, MAX_POLICY_CHANGES, Plan, deploy_record, plan

if TYPE_CHECKING:
    from types_boto3_cloudfront_keyvaluestore import CloudFrontKeyValueStoreClient


class DeployError(Exception):
    pass


class Store(Protocol):
    def etag(self) -> str: ...

    def get(self, key: str) -> str | None: ...

    def items(self, prefix: str) -> dict[str, str]: ...

    def update(self, etag: str, puts: dict[str, str], deletes: list[str]) -> None: ...


@dataclass(frozen=True)
class Result:
    rev: int
    plan: Plan


def deploy(
    store: Store,
    desired: dict[str, Policy],
    commit: str,
    is_ancestor: Callable[[str, str], bool],
) -> Result | None:
    """변경이 없으면 None. rev는 배포 순번이고 정책 내용과 따로 간다 (design.md §8)."""
    # ETag를 먼저 읽는다. 그 뒤에 끼어든 쓰기는 UpdateKeys의 If-Match가 막는다
    etag = store.etag()
    last = store.get(DEPLOY_KEY)
    last_rev = 0
    if last is not None:
        try:
            record = json.loads(last)
            last_rev = int(record["rev"])
        except (ValueError, KeyError, TypeError) as e:
            raise DeployError(f"{DEPLOY_KEY} in KVS is not readable: {last!r}") from e
        if not is_ancestor(str(record["commit"]), commit):
            raise DeployError(
                f"{commit} does not contain the last deployed commit {record['commit']}; "
                "an older commit must not overwrite newer policies"
            )
    rev = last_rev + 1
    try:
        changes = plan(store.items(KEY_PREFIX), desired, rev)
    except (ValueError, KeyError, TypeError, IndexError) as e:
        raise DeployError(f"a policy value in KVS is not readable: {e}") from e
    if changes.change_count == 0:
        return None
    if changes.change_count > MAX_POLICY_CHANGES:
        raise DeployError(f"{changes.change_count} policy keys changed (max {MAX_POLICY_CHANGES})")
    puts = {**changes.puts, DEPLOY_KEY: deploy_record(rev, commit)}
    store.update(etag, puts, changes.deletes)
    return Result(rev, changes)


def git_is_ancestor(ancestor: str, commit: str) -> bool:
    try:
        completed = subprocess.run(  # noqa: S603 - 인자는 커밋 SHA뿐이다
            ["git", "merge-base", "--is-ancestor", ancestor, commit],  # noqa: S607
            check=False,
            capture_output=True,
        )
    except FileNotFoundError as e:
        raise DeployError("git is required to check the last deployed commit") from e
    if completed.returncode not in {0, 1}:
        raise DeployError(f"git merge-base failed: {completed.stderr.decode().strip()}")
    return completed.returncode == 0


class KvsStore:
    """CloudFront KeyValueStore API. SigV4A라 boto3에 awscrt가 있어야 한다."""

    def __init__(self, arn: str, client: "CloudFrontKeyValueStoreClient | None" = None) -> None:
        self._arn = arn
        # KVS API는 전역 서비스다. 리전은 서명용으로만 쓴다
        self._kvs = client or boto3.client("cloudfront-keyvaluestore", region_name="us-east-1")

    def etag(self) -> str:
        return self._kvs.describe_key_value_store(KvsARN=self._arn)["ETag"]

    def get(self, key: str) -> str | None:
        try:
            return self._kvs.get_key(KvsARN=self._arn, Key=key)["Value"]
        except ClientError as e:
            if e.response.get("Error", {}).get("Code") == "ResourceNotFoundException":
                return None
            raise

    def items(self, prefix: str) -> dict[str, str]:
        found: dict[str, str] = {}
        for page in self._kvs.get_paginator("list_keys").paginate(KvsARN=self._arn):
            for item in page.get("Items", []):
                if item["Key"].startswith(prefix):
                    found[item["Key"]] = item["Value"]
        return found

    def update(self, etag: str, puts: dict[str, str], deletes: list[str]) -> None:
        try:
            self._kvs.update_keys(
                KvsARN=self._arn,
                IfMatch=etag,
                Puts=[{"Key": key, "Value": value} for key, value in puts.items()],
                Deletes=[{"Key": key} for key in deletes],
            )
        except ClientError as e:
            if e.response.get("ResponseMetadata", {}).get("HTTPStatusCode") in {409, 412}:
                raise DeployError("KVS changed after it was read; run the deploy again") from e
            raise
