import argparse
import io
import sys
from collections.abc import Sequence
from pathlib import Path

from botocore.exceptions import BotoCoreError, ClientError

from ott_policy.deploy import DeployError, KvsStore, deploy, git_is_ancestor
from ott_policy.model import PolicyError, load_dir
from ott_policy.plan import MAX_POLICY_CHANGES, impact, plan

EXIT_FAILED = 1


def main(argv: Sequence[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if isinstance(stream, io.TextIOWrapper):
            stream.reconfigure(encoding="utf-8")
    args = _parser().parse_args(argv)
    try:
        result: int = args.handler(args)
    except (PolicyError, DeployError) as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_FAILED
    except (ClientError, BotoCoreError) as e:
        print(f"aws error: {e}", file=sys.stderr)
        return EXIT_FAILED
    return result


def _check(args: argparse.Namespace) -> int:
    head = load_dir(args.policies)
    lines = [f"정책 {len(head)}개 검증 통과"]
    if args.base is not None:
        base = load_dir(args.base)
        # 바뀐 키 수는 내용 기준이다. rev는 배포할 때 정해지므로 아무 값이나 넣어 비교한다
        current = {policy.key: policy.value(0) for policy in base.values()}
        changes = plan(current, head, rev=0).change_count
        if changes > MAX_POLICY_CHANGES:
            raise PolicyError(f"{changes} policy keys change in one PR (max {MAX_POLICY_CHANGES})")
        preview = impact(base, head)
        lines += ["", f"바뀌는 정책 키 {changes}개", *(preview or ["- 영향 없음"])]
    print("\n".join(lines))
    return 0


def _deploy(args: argparse.Namespace) -> int:
    desired = load_dir(args.policies)
    result = deploy(KvsStore(args.kvs_arn), desired, args.commit, git_is_ancestor)
    if result is None:
        print("no policy changes; KVS left as is")
        return 0
    print(
        f"deployed rev {result.rev}: put {len(result.plan.puts)}, delete {len(result.plan.deletes)}"
    )
    return 0


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ott-policy", description="국가별 정책 검증·배포")
    commands = parser.add_subparsers(required=True)

    check = commands.add_parser("check", help="policies/*.yaml 검증과 영향 미리보기")
    check.add_argument("--policies", type=Path, default=Path("policies"))
    check.add_argument("--base", type=Path, help="비교할 이전 policies 디렉터리 (PR의 base)")
    check.set_defaults(handler=_check)

    run = commands.add_parser("deploy", help="KVS에 반영 (UpdateKeys 한 번)")
    run.add_argument("--policies", type=Path, default=Path("policies"))
    run.add_argument("--kvs-arn", required=True)
    run.add_argument("--commit", required=True, help="배포하는 커밋 SHA")
    run.set_defaults(handler=_deploy)
    return parser


if __name__ == "__main__":
    sys.exit(main())
