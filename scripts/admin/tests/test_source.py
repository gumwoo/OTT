import base64
import hashlib
from collections.abc import Iterator
from pathlib import Path
from typing import TYPE_CHECKING

import boto3
import pytest
from moto import mock_aws

from ott_admin.source import upload_source

if TYPE_CHECKING:
    from types_boto3_s3 import S3Client


@pytest.fixture
def s3(monkeypatch: pytest.MonkeyPatch) -> Iterator["S3Client"]:
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "testing")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "testing")
    monkeypatch.setenv("AWS_DEFAULT_REGION", "ap-northeast-2")
    with mock_aws():
        client = boto3.client("s3")
        client.create_bucket(
            Bucket="ott-source",
            CreateBucketConfiguration={"LocationConstraint": "ap-northeast-2"},
        )
        yield client


def test_uploads_once_and_reuses_the_same_content(s3: "S3Client", tmp_path: Path) -> None:
    source = tmp_path / "spring.webm"
    source.write_bytes(b"video")

    assert upload_source(s3, "ott-source", "films/spring.webm", source) is True
    assert upload_source(s3, "ott-source", "films/spring.webm", source) is False

    head = s3.head_object(Bucket="ott-source", Key="films/spring.webm", ChecksumMode="ENABLED")
    assert head["ChecksumSHA256"] == base64.b64encode(hashlib.sha256(b"video").digest()).decode()


def test_never_overwrites_a_different_original(s3: "S3Client", tmp_path: Path) -> None:
    s3.put_object(Bucket="ott-source", Key="spring.webm", Body=b"older cut")
    source = tmp_path / "spring.webm"
    source.write_bytes(b"video")

    with pytest.raises(ValueError, match="other content"):
        upload_source(s3, "ott-source", "spring.webm", source)

    assert s3.get_object(Bucket="ott-source", Key="spring.webm")["Body"].read() == b"older cut"
