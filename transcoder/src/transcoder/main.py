import json
import logging
import sys

from transcoder.errors import TranscodeError

log = logging.getLogger(__name__)

EXIT_FAILED = 1
EXIT_UNEXPECTED = 2


def run(argv: list[str]) -> None:
    log.info(json.dumps({"event": "start", "args": argv}))


def main(argv: list[str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(message)s")
    try:
        run(sys.argv[1:] if argv is None else argv)
    except TranscodeError as e:
        # 예상한 실패라 traceback은 남기지 않는다
        log.error(json.dumps({"event": "failed", "failure_code": e.failure_code, "error": str(e)}))  # noqa: TRY400
        return EXIT_FAILED
    except Exception:  # PY-04: 여기 한 곳만
        log.exception(json.dumps({"event": "unexpected_error"}))
        return EXIT_UNEXPECTED
    return 0


if __name__ == "__main__":
    sys.exit(main())
