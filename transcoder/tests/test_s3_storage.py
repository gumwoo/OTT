from collections.abc import Iterator
from pathlib import Path
from typing import TYPE_CHECKING, Any

import boto3
import pytest
from botocore.exceptions import ClientError
from moto import mock_aws

from tests.fakes import write_hls
from transcoder import hls
from transcoder.errors import ConfigError, UploadFailedError, VersionDirtyError
from transcoder.storage import S3Storage

if TYPE_CHECKING:
    from types_boto3_s3 import S3Client

SOURCE = "ott-source"
CONTENT = "ott-content"


@pytest.fixture
def s3(monkeypatch: pytest.MonkeyPatch) -> Iterator["S3Client"]:
    for name, value in {
        "AWS_ACCESS_KEY_ID": "testing",
        "AWS_SECRET_ACCESS_KEY": "testing",
        "AWS_DEFAULT_REGION": "ap-northeast-2",
    }.items():
        monkeypatch.setenv(name, value)
    with mock_aws():
        client = boto3.client("s3")
        for bucket in (SOURCE, CONTENT):
            client.create_bucket(
                Bucket=bucket,
                CreateBucketConfiguration={"LocationConstraint": "ap-northeast-2"},
            )
        yield client


def storage(s3: "S3Client") -> S3Storage:
    return S3Storage(SOURCE, CONTENT, client=s3)


def client_error(code: str, status: int) -> ClientError:
    return ClientError(
        {
            "Error": {"Code": code},
            "ResponseMetadata": {
                "RequestId": "",
                "HostId": "",
                "HTTPStatusCode": status,
                "HTTPHeaders": {},
                "RetryAttempts": 0,
            },
        },
        "PutObject",
    )


def test_uploads_under_the_version_prefix_with_playback_headers(
    s3: "S3Client", tmp_path: Path
) -> None:
    write_hls(tmp_path)
    store = storage(s3)
    assert store.is_version_empty("movie", 3)

    for name in hls.upload_order(tmp_path):
        store.put("movie", 3, name, tmp_path / name)

    keys = [o["Key"] for o in s3.list_objects_v2(Bucket=CONTENT)["Contents"]]
    assert sorted(keys) == [
        "content/movie/v3/360p.m3u8",
        "content/movie/v3/360p_00000.ts",
        "content/movie/v3/master.m3u8",
    ]
    head = s3.head_object(Bucket=CONTENT, Key="content/movie/v3/master.m3u8")
    assert head["ContentType"] == "application/vnd.apple.mpegurl"
    assert head["CacheControl"] == "public, max-age=31536000, immutable"
    assert s3.head_object(Bucket=CONTENT, Key="content/movie/v3/360p_00000.ts")["ContentType"] == (
        "video/mp2t"
    )
    assert not store.is_version_empty("movie", 3)
    assert store.is_version_empty("movie", 4)
    # prefix가 겹치는 다른 버전·콘텐츠는 이 버전의 것으로 치지 않는다
    assert store.is_version_empty("movie", 30)
    assert store.is_version_empty("movi", 3)


def test_existing_object_with_other_content_is_version_dirty(
    s3: "S3Client", tmp_path: Path
) -> None:
    s3.put_object(Bucket=CONTENT, Key="content/movie/v3/master.m3u8", Body=b"other run")
    write_hls(tmp_path)

    with pytest.raises(VersionDirtyError, match=r"v3/master.m3u8"):
        storage(s3).put("movie", 3, "master.m3u8", tmp_path / "master.m3u8")

    body = s3.get_object(Bucket=CONTENT, Key="content/movie/v3/master.m3u8")["Body"].read()
    assert body == b"other run"


def test_412_for_the_same_bytes_counts_as_uploaded(s3: "S3Client", tmp_path: Path) -> None:
    # 응답 유실 뒤 botocore가 다시 보낸 경우와 같다: 이미 내 내용이 올라가 있다
    write_hls(tmp_path)
    store = storage(s3)
    store.put("movie", 3, "master.m3u8", tmp_path / "master.m3u8")

    store.put("movie", 3, "master.m3u8", tmp_path / "master.m3u8")


def test_conflict_is_retried_then_succeeds(
    s3: "S3Client", tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    write_hls(tmp_path)
    real_put = s3.put_object
    calls = 0

    def conflict_once(**kwargs: Any) -> Any:
        nonlocal calls
        calls += 1
        if calls == 1:
            raise client_error("ConditionalRequestConflict", 409)
        return real_put(**kwargs)

    monkeypatch.setattr(s3, "put_object", conflict_once)

    storage(s3).put("movie", 3, "master.m3u8", tmp_path / "master.m3u8")

    assert calls == 2


def test_conflict_that_keeps_happening_is_upload_failed(
    s3: "S3Client", tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    write_hls(tmp_path)
    calls = 0

    def conflict(**kwargs: Any) -> Any:
        nonlocal calls
        calls += 1
        raise client_error("ConditionalRequestConflict", 409)

    monkeypatch.setattr(s3, "put_object", conflict)

    with pytest.raises(UploadFailedError, match="ConditionalRequestConflict"):
        storage(s3).put("movie", 3, "master.m3u8", tmp_path / "master.m3u8")
    assert calls == 3


def test_unreadable_existing_object_is_upload_failed(
    s3: "S3Client", tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    write_hls(tmp_path)
    s3.put_object(Bucket=CONTENT, Key="content/movie/v3/master.m3u8", Body=b"other run")

    def denied(**kwargs: Any) -> Any:
        raise client_error("AccessDenied", 403)

    monkeypatch.setattr(s3, "head_object", denied)

    with pytest.raises(UploadFailedError, match="head failed"):
        storage(s3).put("movie", 3, "master.m3u8", tmp_path / "master.m3u8")


def test_other_upload_errors_are_upload_failed(
    s3: "S3Client", tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    write_hls(tmp_path)

    def denied(**kwargs: Any) -> Any:
        raise client_error("AccessDenied", 403)

    monkeypatch.setattr(s3, "put_object", denied)

    with pytest.raises(UploadFailedError, match="AccessDenied"):
        storage(s3).put("movie", 3, "master.m3u8", tmp_path / "master.m3u8")


def test_fetches_the_source_object(s3: "S3Client", tmp_path: Path) -> None:
    s3.put_object(Bucket=SOURCE, Key="movie/original.mp4", Body=b"x" * 3_000_000)

    dest = storage(s3).fetch_source("movie/original.mp4", tmp_path / "work" / "source")

    assert dest.read_bytes() == b"x" * 3_000_000


def test_missing_source_is_a_config_error(s3: "S3Client", tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="source not found"):
        storage(s3).fetch_source("movie/none.mp4", tmp_path / "source")
