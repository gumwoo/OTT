# Python transcoder 컨벤션 조사 (2026-10-13)

범위: 프로젝트 레이아웃, uv, Ruff, mypy, 예외·종료 코드, subprocess·SIGTERM, heartbeat 스레드, 로깅, 테스트, boto3 조건부 쓰기.

## 0. 요약

설계는 대체로 공식 권장과 맞다. 코딩 전에 반영할 사항:

1. **`upload_file`로는 `IfNoneMatch`를 쓸 수 없다.** s3transfer 업로드 허용 인자 목록에 없다. `put_object`를 직접 호출한다.
2. **botocore 자동 재시도와 `If-None-Match`가 겹치면 내가 방금 올린 객체에 412가 날 수 있다** (추론, 미실측).
3. **Python이 PID 1이면 SIGTERM 핸들러를 직접 등록해야 한다.** 없으면 SIGTERM이 무시되고 grace period 뒤 SIGKILL, ffmpeg 자식에 신호 미전달.
4. **emptyDir `sizeLimit` 초과 시 ENOSPC 없이 Pod가 evict될 수 있다.** `DISK_FULL` 보고 경로가 실행되지 않을 수 있다.

Ruff 0.16.0(2026-07-23)부터 기본 규칙이 59개 → 413개. 규칙은 `select`로 명시해 고정한다.

## 1. 출처 (실제로 열어본 것만)

| 출처 | 등급 | URL | 확인한 내용 |
|---|---|---|---|
| PEP 8 | 공식 문서 | https://peps.python.org/pep-0008/ | `Exception` 상속, bare except 지양, try 범위 최소화, `raise X from Y`, `Error` 접미사 |
| src vs flat layout | 공식 문서(PyPA) | https://packaging.python.org/en/latest/discussions/src-layout-vs-flat-layout/ | src layout은 개발 사본 실수 import 방지 |
| Writing pyproject.toml | 공식 문서(PyPA) | https://packaging.python.org/en/latest/guides/writing-pyproject-toml/ | `[build-system]` 필수, `[project.scripts]` |
| subprocess | 공식 문서 | https://docs.python.org/3/library/subprocess.html | shell 주입 경고, `run()` 타임아웃 시 kill, `communicate()` 타임아웃은 직접 kill, PIPE+`wait()` 교착, `process_group`(3.11), `preexec_fn` 스레드 위험 |
| signal | 공식 문서 | https://docs.python.org/3/library/signal.html | 핸들러는 메인 스레드에서만, 핸들러에서 예외 금지 |
| threading | 공식 문서 | https://docs.python.org/3/library/threading.html | daemon 스레드는 종료 시 갑자기 멈춤, `Event.wait()` 반환값, `threading.excepthook` |
| logging HOWTO / Cookbook | 공식 문서 | https://docs.python.org/3/howto/logging.html , https://docs.python.org/3/howto/logging-cookbook.html | `getLogger(__name__)`, contextvars 컨텍스트 주입 |
| exceptions / sys | 공식 문서 | https://docs.python.org/3/library/exceptions.html , https://docs.python.org/3/library/sys.html | ENOSPC 전용 하위 클래스 없음, exit 코드 0–127 |
| Typing spec: Protocol | 공식 문서 | https://typing.python.org/en/latest/spec/protocol.html | 구조적 서브타이핑, `runtime_checkable` 제약 |
| Ruff linter/formatter/settings/default-rules | 도구 메인테이너 문서 | https://docs.astral.sh/ruff/linter/ , https://docs.astral.sh/ruff/formatter/ , https://docs.astral.sh/ruff/settings/ , https://docs.astral.sh/ruff/default-rules/ | `select` 권장, `ALL` 주의, formatter 충돌 규칙 |
| Ruff CHANGELOG, v0.16.0 블로그 | 도구 메인테이너 문서 | https://github.com/astral-sh/ruff/blob/main/CHANGELOG.md , https://astral.sh/blog/ruff-v0.16.0 | 0.16.0 기본 규칙 59→413, 0.17.0 기본 target py311 |
| Ruff S603 / BLE001 | 도구 메인테이너 문서 | https://docs.astral.sh/ruff/rules/subprocess-without-shell-equals-true/ , https://docs.astral.sh/ruff/rules/blind-except/ | S603 오탐 많음, BLE001 예외 조건 |
| uv Docker 가이드 | 도구 메인테이너 문서 | https://docs.astral.sh/uv/guides/integration/docker/ | uv 버전 고정, `UV_COMPILE_BYTECODE`, `--no-install-project`, 멀티스테이지 |
| uv init / build backend / sync | 도구 메인테이너 문서 | https://docs.astral.sh/uv/concepts/build-backend/ , https://docs.astral.sh/uv/concepts/projects/sync/ | 앱 템플릿 기본 src layout, `uv_build`, `--locked`, uv.lock 커밋 |
| mypy existing_code | 도구 메인테이너 문서 | https://mypy.readthedocs.io/en/stable/existing_code.html | `--strict` 플래그 목록 |
| Pyright configuration | 도구 메인테이너 문서 | https://github.com/microsoft/pyright/blob/main/docs/configuration.md | 기본 `standard` |
| pytest good practices / changelog | 도구 메인테이너 문서 | https://docs.pytest.org/en/stable/explanation/goodpractices.html | tests 밖 배치 + src layout, `--import-mode=importlib`, 9.0 `[tool.pytest]`·`strict` |
| structlog | 도구 메인테이너 문서 | https://www.structlog.org/en/stable/logging-best-practices.html | stdout, 운영은 JSON |
| boto3 put_object | 공식 문서(AWS) | https://docs.aws.amazon.com/boto3/latest/reference/services/s3/client/put_object.html | `IfNoneMatch`, 412 / 409 `ConditionalRequestConflict` |
| S3 conditional writes | 공식 문서(AWS) | https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html | 동시 쓰기 시 먼저 끝난 쪽 성공, 409면 PutObject 재시도 가능 |
| boto3 error handling / retries / download | 공식 문서(AWS) | https://docs.aws.amazon.com/boto3/latest/guide/error-handling.html , https://docs.aws.amazon.com/boto3/latest/guide/retries.html | `ClientError`, legacy(총 5회)/standard(총 3회) |
| botocore·boto3 CHANGELOG | 메인테이너 저장소 | https://github.com/boto/botocore/blob/develop/CHANGELOG.rst | 1.35.2 조건부 쓰기 추가, 1.35.69 `If-Match` |
| botocore `_retry.json`, `response.py` | 원본 소스 | https://github.com/boto/botocore/blob/develop/botocore/data/_retry.json | S3 전용 재시도는 RequestTimeout·BadDigest뿐 |
| s3transfer `manager.py` | 원본 소스 | https://github.com/boto/s3transfer/blob/develop/s3transfer/manager.py | `ALLOWED_UPLOAD_ARGS`에 `IfNoneMatch` 없음 |
| Kubernetes Pod lifecycle / Job / Ephemeral storage | 공식 문서 | https://github.com/kubernetes/website | PID 1에 TERM, grace 기본 30초, "같은 프로그램이 두 번 시작될 수 있음", 저장소 한도 초과 시 evict |
| Linux pid_namespaces(7) | 공식 문서(man) | https://man7.org/linux/man-pages/man7/pid_namespaces.7.html | init은 핸들러를 등록한 신호만 받음 |
| hynek "Testing & Packaging" | 기여자 글 | https://hynek.me/articles/testing-packaging/ | src layout 근거 |
| hynek/doc2dash | 기여자 공개 저장소 | https://github.com/hynek/doc2dash | src layout, Ruff ALL+ignore, mypy strict, pytest importlib |
| hynek/structlog | 기여자 공개 저장소 | https://github.com/hynek/structlog | src/tests/docs 구조 |
| simonw/shot-scraper, simonw/click-app | 기여자 공개 저장소 | https://github.com/simonw/shot-scraper , https://github.com/simonw/click-app | flat layout, `uv_build`, `[dependency-groups]` |
| astral-sh/uv-docker-example | 도구 메인테이너 저장소 | https://github.com/astral-sh/uv-docker-example | uid 999 nonroot, `PYTHONUNBUFFERED=1`, venv 바이너리 직접 실행 |
| fastapi/full-stack-fastapi-template | 기여자 공개 저장소 | https://github.com/fastapi/full-stack-fastapi-template | mypy strict, Ruff select, pre-commit에서 `uv run` |

## 2. 주제별 규칙

### 2.1 레이아웃·의존성·Docker

| 규칙 | 근거 | 강제 | 확신도 |
|---|---|---|---|
| `transcoder/src/transcoder/` src layout, tests는 패키지 밖. `validate_hls.py`는 모듈로 흡수 | PyPA, pytest, Hynek, uv 기본값 | `uv_build`, pytest importlib | 높음 |
| `[project.scripts] transcoder = "transcoder.cli:main"` + `__main__.py` | PyPA, uv, doc2dash | — | 높음 |
| `uv.lock` 커밋, CI·Docker는 `uv sync --locked` | uv 문서 | `uv lock --check` | 높음 |
| 개발 도구는 `[dependency-groups] dev`, 이미지는 `--no-dev` | uv sync | Dockerfile | 높음 |
| Dockerfile은 uv-docker-example 멀티스테이지 패턴 + nonroot + `PYTHONUNBUFFERED=1` + exec 형식 CMD, 최종 이미지에 ffmpeg 설치 | Astral 예제 | 리뷰 | 높음 |
| uv 바이너리 버전 고정 | uv Docker 가이드 | — | 높음 |

### 2.2 타입

| 규칙 | 근거 | 강제 | 확신도 |
|---|---|---|---|
| mypy `strict = true` | mypy 문서, doc2dash, fastapi 템플릿 | CI | 높음 |
| boto3 타입 스텁(`types-boto3[s3]` 또는 `mypy-boto3-s3`) | PyPI | dev 의존성 | 중간 |
| 스토리지 인터페이스는 `typing.Protocol` | Typing spec | mypy | 높음 |

### 2.3 예외와 종료 코드

| 규칙 | 근거 | 강제 | 확신도 |
|---|---|---|---|
| `TranscodeError(Exception)` 루트 + `VersionDirtyError`, `DiskFullError`, `SourceHashMismatchError`, `ValidationFailedError`, `UploadConflictError`, `LeaseLostError`. 각 클래스에 `failure_code` | PEP 8 | Ruff `N818` | 높음 |
| `raise X from e` | PEP 8 | Ruff `B904` | 높음 |
| bare except 금지. `except Exception`은 `main()` 한 곳에서 `logger.exception` 후 `INTERNAL` 보고 | PEP 8, BLE001 | Ruff `E722`, `BLE001` | 높음 |
| 예상된 실패와 버그의 종료 코드 분리 (0–127) | sys 문서 | 테스트 | 중간 |
| 실패 보고 API 호출 실패가 원래 예외를 가리지 않게 | PEP 8 | 리뷰 | 높음 |

### 2.4 ffmpeg·SIGTERM·디스크

| 규칙 | 근거 | 강제 | 확신도 |
|---|---|---|---|
| 인자 리스트, `shell=True` 금지, `shutil.which("ffmpeg")` | subprocess 보안 | Ruff `S602`/`S604` | 높음 |
| `Popen` + 메인 스레드 폴링(`wait(timeout=1)`)으로 stop_event 확인, 중단은 `terminate()` → `wait()` → `kill()` | subprocess 문서 | 테스트 | 높음 |
| stderr를 PIPE로 받고 `wait()`하면 교착. 파일로 리다이렉트하거나 별도 스레드에서 읽기 | subprocess 경고 | 리뷰 | 높음 |
| `preexec_fn` 금지. 필요하면 `start_new_session` 또는 `process_group=0` | subprocess 문서 | Ruff (`PLW1509`, 미검증) | 높음 |
| SIGTERM 핸들러는 메인 스레드에서 등록, `stop_event.set()`만 | signal 문서 | 리뷰 | 높음 |
| PID 1 Python은 핸들러 필수, ffmpeg에 직접 `terminate()`, CMD는 exec 형식(셸 래퍼 금지) | pid_namespaces(7), K8s | Dockerfile | 높음 |
| `terminationGracePeriodSeconds` 안에 종료·보고·heartbeat 정지가 끝나게 예산 설계 | K8s pod lifecycle | Job spec | 높음 |
| ENOSPC는 `errno.ENOSPC`로 판별, ffmpeg는 종료 코드·stderr로만. `shutil.disk_usage` 사전·주기 점검 병행 | exceptions 문서 | 테스트 | 높음 / 낮음(ffmpeg 메시지) |

### 2.5 heartbeat 스레드

| 규칙 | 근거 | 강제 | 확신도 |
|---|---|---|---|
| `while not stop.wait(interval): post()`, 종료는 `stop.set()` → `join(timeout)` → `is_alive()` 확인 | threading 문서 | 테스트 | 높음 |
| non-daemon + `finally`에서 stop/join | threading 문서 | 리뷰 | 높음 |
| 409를 받으면 lease_lost Event 설정, 메인이 ffmpeg 종료·업로드 중단·`LeaseLostError` | signal·스레드 제약 | 테스트 | 높음 |
| 스레드 본문 전체 try/except. 일시 오류는 로그, 예상 밖 예외는 Event로 메인에 알림 | `threading.excepthook` | 리뷰 | 높음 |
| 업로드 루프도 객체마다 stop_event 확인 | 설계 추론 | 테스트 | 중간 |

### 2.6 로깅

| 규칙 | 근거 | 강제 | 확신도 |
|---|---|---|---|
| stdout 한 줄 JSON, 설정은 `cli.main()` 한 번, `getLogger(__name__)` | logging HOWTO, structlog | 리뷰 | 높음 |
| stdlib logging + 작은 JSON Formatter + contextvars, 또는 structlog `ProcessorFormatter` | Cookbook, structlog | — | 중간 |
| 로그 메시지는 `%` 인자 (또는 G004 끄는 정책 명시) | logging HOWTO | Ruff `G` | 중간 |
| `PYTHONUNBUFFERED=1` | uv-docker-example | Dockerfile | 높음 |

### 2.7 테스트

| 규칙 | 근거 | 강제 | 확신도 |
|---|---|---|---|
| `[tool.pytest]` testpaths, `--import-mode=importlib`, 버전 고정 시 `strict = true` | pytest, doc2dash | CI | 높음 |
| 스토리지는 Protocol의 in-memory FakeStorage, 412는 "키가 있으면 예외"로 재현 | Protocol spec | 테스트 | 높음 |
| subprocess 래퍼는 `[sys.executable, "-c", ...]`로 테스트 | subprocess 문서 | 테스트 | 중간 |
| 실제 ffmpeg 통합 테스트는 짧은 합성 입력 + 별도 마커 | 미검증 | 마커 | 낮음 |

### 2.8 Lint·format·pre-commit

| 규칙 | 근거 | 강제 | 확신도 |
|---|---|---|---|
| Ruff `select` 명시 (예: E, W, F, I, B, UP, SIM, RUF, BLE, S, PTH, TRY, C4, PL에서 시작해 조정). `ALL` 피함 | Ruff 문서, 0.16.0 변경 | pyproject.toml | 높음 / 중간(세트) |
| `ruff format`, 충돌 규칙(`COM812`, `ISC002` 등) 끔, `E501` 끔 | formatter 문서 | 설정 | 높음 |
| pre-commit: `ruff-check --fix --exit-non-zero-on-fix`, `ruff-format`, `uv run mypy src`. CI에서도 동일 | doc2dash, fastapi 템플릿 | pre-commit + CI | 높음 |
| 테스트에서 `S101` 허용 | doc2dash | 설정 | 높음 |

## 3. 폴더 구조 예시

**hynek/doc2dash**
```
src/doc2dash/  __init__.py __main__.py convert.py docsets.py output.py parsers/
tests/         __init__.py conftest.py test_docsets.py test_main.py test_output.py parsers/
.pre-commit-config.yaml  noxfile.py  pyproject.toml
```

**simonw/shot-scraper** (flat layout)
```
shot_scraper/  __init__.py cli.py utils.py video.py
tests/         conftest.py test_shot_scraper.py test_utils.py ...
pyproject.toml   # uv_build, module-root = ""
```

**fastapi/full-stack-fastapi-template backend**
```
backend/ Dockerfile .dockerignore pyproject.toml alembic.ini
  app/ (main.py models.py crud.py api/ core/ alembic/ ...)
  tests/ (conftest.py api/ crud/ ...)
```

## 4. boto3 조건부 쓰기

- 파라미터: `IfNoneMatch`(string, 값은 `"*"`). PutObject, CompleteMultipartUpload, CopyObject 지원.
- 지원 버전: botocore·boto3 **1.35.2** changelog에 조건부 쓰기 추가. `IfMatch`는 1.35.69. → `boto3>=1.35.2`.
- 412 처리: AWS 문서는 HTTP 412와 409 `ConditionalRequestConflict`를 명시. 412의 `Error.Code` 문자열(`PreconditionFailed`)은 AWS 원문에서 확인하지 못함 → **HTTP 상태 코드 우선 판별**.

```python
except botocore.exceptions.ClientError as e:
    status = e.response["ResponseMetadata"]["HTTPStatusCode"]
    code = e.response.get("Error", {}).get("Code")
    if status == 412 or code == "PreconditionFailed":
        raise UploadConflictError(key) from e
    if status == 409:  # ConditionalRequestConflict: AWS 문서상 PutObject는 재시도 가능
        ...
    raise
```

- 재시도: 기본 legacy 총 5회, standard 총 3회. 권장 `Config(retries={"mode": "standard", "total_max_attempts": N})`.
- `upload_file`/`upload_fileobj`는 `IfNoneMatch` 불가 → `client.put_object(Body=f, IfNoneMatch="*")`.
- 다운로드 + sha256 동시 검증은 `get_object()["Body"].iter_chunks()`로.

## 5. 우리 설계와 충돌·주의점

1. **재시도 + If-None-Match** (추론, 미실측): 응답 유실 후 재시도하면 내가 올린 객체로 412. 대응안 (a) 412 시 `head_object`로 비교 (b) `ChecksumSHA256` 비교.
2. **409의 두 의미**: heartbeat API 409는 "즉시 중단", S3 409는 "재시도". 코드에서 구분.
3. **backoffLimit 0이어도 1회 실행 보장 아님**: VERSION_DIRTY 사전 점검만으로는 확인과 쓰기 사이 경쟁을 못 막는다. If-None-Match·heartbeat 조합을 테스트로 고정.
4. **PID 1과 SIGTERM**: bash 엔트리포인트를 남기면 신호 전달이 끊길 수 있다. Python으로 흡수.
5. **DISK_FULL이 보고되지 않을 수 있음**: eviction이 먼저 올 수 있다. `sizeLimit`보다 작은 자체 예산으로 점검.
6. `upload_file` 사용 불가.
7. Ruff 버전 고정: `requires-python`, `select`, pre-commit `rev`.
8. `validate_hls.py`의 stdlib 전용 경계 유지.
9. pytest `strict`는 버전 고정 시에만.

## 6. 확인하지 못한 것

- S3 412 `Error.Code` 문자열 (AWS 원문)
- `upload_file(ExtraArgs={"IfNoneMatch": "*"})` 실제 거부 여부, CRT 전송 경로
- 응답 유실 후 재시도 412 시나리오 실측
- ffmpeg의 SIGTERM 처리, 디스크 부족 메시지·종료 코드, `-progress`
- `uv run`의 신호 전달
- ephemeral storage eviction 시 grace period 적용 여부
- logging cookbook structured logging 절
- Ruff `PLW1509`
- Brett Cannon, Charlie Marsh, pytest-dev 메인테이너의 소형 앱
- `types-boto3[s3]` extras 이름
