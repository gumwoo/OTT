from datetime import UTC, datetime

import boto3
import pytest
from botocore.stub import Stubber

from ott_policy.deploy import DeployError, KvsStore

ARN = "arn:aws:cloudfront::123456789012:key-value-store/0123abcd-0123-0123-0123-0123456789ab"


@pytest.fixture
def client() -> object:
    return boto3.client(
        "cloudfront-keyvaluestore",
        region_name="us-east-1",
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )


# Stubber가 요청 인자를 서비스 모델로 검사하므로 호출 모양이 실제 API와 맞는지 본다
def test_calls_match_the_kvs_api(client: object) -> None:
    store = KvsStore(ARN, client)  # type: ignore[arg-type]
    with Stubber(client) as stub:  # type: ignore[arg-type]
        stub.add_response(
            "describe_key_value_store",
            {
                "ETag": "KV1",
                "KvsARN": ARN,
                "ItemCount": 0,
                "TotalSizeInBytes": 0,
                "Created": datetime(2026, 11, 27, tzinfo=UTC),
            },
            {"KvsARN": ARN},
        )
        stub.add_response(
            "get_key",
            {"Key": "_deploy", "Value": '{"rev":1}', "ItemCount": 1, "TotalSizeInBytes": 9},
            {"KvsARN": ARN, "Key": "_deploy"},
        )
        stub.add_client_error(
            "get_key",
            "ResourceNotFoundException",
            http_status_code=404,
            expected_params={"KvsARN": ARN, "Key": "policy:none"},
        )
        stub.add_response(
            "list_keys",
            {
                "Items": [{"Key": "policy:a", "Value": "1"}, {"Key": "_deploy", "Value": "2"}],
                "NextToken": "t",
            },
            {"KvsARN": ARN},
        )
        stub.add_response(
            "list_keys",
            {"Items": [{"Key": "policy:b", "Value": "3"}]},
            {"KvsARN": ARN, "NextToken": "t"},
        )
        stub.add_response(
            "update_keys",
            {"ETag": "KV2", "ItemCount": 2, "TotalSizeInBytes": 10},
            {
                "KvsARN": ARN,
                "IfMatch": "KV1",
                "Puts": [{"Key": "policy:a", "Value": "x"}],
                "Deletes": [{"Key": "policy:b"}],
            },
        )

        assert store.etag() == "KV1"
        assert store.get("_deploy") == '{"rev":1}'
        assert store.get("policy:none") is None
        assert store.items("policy:") == {"policy:a": "1", "policy:b": "3"}
        store.update("KV1", {"policy:a": "x"}, ["policy:b"])
        stub.assert_no_pending_responses()


def test_stale_etag_asks_for_a_rerun(client: object) -> None:
    store = KvsStore(ARN, client)  # type: ignore[arg-type]
    with Stubber(client) as stub:  # type: ignore[arg-type]
        stub.add_client_error("update_keys", "ConflictException", http_status_code=409)

        with pytest.raises(DeployError, match="run the deploy again"):
            store.update("KV0", {"policy:a": "x"}, [])
