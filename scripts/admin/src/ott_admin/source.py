import base64
import hashlib
from pathlib import Path
from typing import TYPE_CHECKING

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

if TYPE_CHECKING:
    from types_boto3_s3 import S3Client

CHUNK_BYTES = 1024 * 1024
HTTP_PRECONDITION_FAILED = 412


def s3_client(endpoint_url: str | None, region: str) -> "S3Client":
    config = Config(retries={"mode": "standard"})
    if endpoint_url:
        # 로컬 에뮬레이터는 버킷 이름을 호스트 이름으로 풀지 못한다
        config = config.merge(Config(s3={"addressing_style": "path"}))
    return boto3.client("s3", region_name=region, endpoint_url=endpoint_url, config=config)


def sha256_hex(path: Path) -> str:
    return _sha256(path).hexdigest()


def upload_source(s3: "S3Client", bucket: str, key: str, path: Path) -> bool:
    """올렸으면 True, 같은 내용이 이미 있으면 False. 다른 내용이면 ValueError."""
    checksum = base64.b64encode(_sha256(path).digest()).decode()
    try:
        with path.open("rb") as body:
            s3.put_object(
                Bucket=bucket,
                Key=key,
                Body=body,
                IfNoneMatch="*",
                ChecksumAlgorithm="SHA256",
                ChecksumSHA256=checksum,
            )
    except ClientError as e:
        if e.response.get("ResponseMetadata", {}).get("HTTPStatusCode") != HTTP_PRECONDITION_FAILED:
            raise
        head = s3.head_object(Bucket=bucket, Key=key, ChecksumMode="ENABLED")
        if head.get("ChecksumSHA256") != checksum:
            raise ValueError(f"s3://{bucket}/{key} already exists with other content") from e
        return False
    return True


def _sha256(path: Path) -> "hashlib._Hash":
    digest = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(CHUNK_BYTES):
            digest.update(chunk)
    return digest
