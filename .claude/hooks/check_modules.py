"""Stop: 바뀐 모듈만 컴파일·타입·아키텍처 검사를 돌린다 (CONVENTIONS §10 S)."""

import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(os.environ.get("CLAUDE_PROJECT_DIR", Path(__file__).resolve().parents[2]))
CACHE = ROOT / ".claude" / ".cache"


def tool(name: str) -> str | None:
    return shutil.which(name + ".cmd") or shutil.which(name)


def changed_files() -> list[str]:
    out = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        cwd=ROOT, capture_output=True, text=True, encoding="utf-8", check=True,
    ).stdout
    return [line[3:].split(" -> ")[-1].strip('"') for line in out.splitlines()]


def fingerprint(files: list[str]) -> str:
    h = hashlib.sha256()
    for f in sorted(files):
        h.update(f.encode())
        p = ROOT / f
        if p.is_file():
            h.update(p.read_bytes())
    return h.hexdigest()


def commands(module: str) -> list[list[str]] | str:
    if module == "api":
        gradlew = ROOT / "api" / ("gradlew.bat" if os.name == "nt" else "gradlew")
        return [[str(gradlew), "--console=plain", "checkFormat", "checkstyleMain", "test", "--tests", "*ArchitectureTests"]]
    if module == "web":
        npm = tool("npm")
        return "npm이 없다" if not npm else [[npm, "run", "-s", "typecheck"], [npm, "run", "-s", "lint"]]
    if module == "transcoder":
        uv = tool("uv")
        return "uv가 없다" if not uv else [[uv, "run", "mypy", "src", "tests"], [uv, "run", "pytest", "-q"]]
    return []


def main() -> int:
    sys.stderr.reconfigure(encoding="utf-8")  # Windows 파이프 기본값은 cp949
    if os.environ.get("CI"):
        return 0
    if json.load(sys.stdin).get("stop_hook_active"):
        return 0

    by_module: dict[str, list[str]] = {}
    for f in changed_files():
        by_module.setdefault(f.split("/", 1)[0], []).append(f)

    errors = []
    for module, files in by_module.items():
        plan = commands(module)
        if isinstance(plan, str):
            errors.append(f"[{module}] 검사 도구 없음: {plan}")
            continue
        if not plan:
            continue
        stamp = CACHE / f"{module}.sha256"
        fp = fingerprint(files)
        if stamp.is_file() and stamp.read_text() == fp:
            continue
        for cmd in plan:
            r = subprocess.run(cmd, cwd=ROOT / module, capture_output=True, text=True, encoding="utf-8")
            if r.returncode != 0:
                errors.append(f"[{module}] {' '.join(cmd[1:])} 실패:\n{(r.stdout + r.stderr).strip()[-3000:]}")
                break
        else:
            CACHE.mkdir(parents=True, exist_ok=True)
            stamp.write_text(fp)

    if errors:
        print("\n\n".join(errors), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
