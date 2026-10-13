"""PostToolUse: 방금 고친 파일 하나만 빠르게 검사한다 (CONVENTIONS §10 F)."""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(os.environ.get("CLAUDE_PROJECT_DIR", Path(__file__).resolve().parents[2]))
WEB_EXT = {".ts", ".tsx", ".js", ".css", ".json", ".html", ".md"}


def tool(name: str) -> str | None:
    return shutil.which(name + ".cmd") or shutil.which(name)


def run(cmd: list[str], cwd: Path) -> str | None:
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8")
    if result.returncode == 0:
        return None
    return (result.stdout + result.stderr).strip() or f"{cmd[0]} exited {result.returncode}"


def checks(rel: Path) -> list[tuple[list[str], Path]] | str:
    parts, suffix = rel.parts, rel.suffix
    if parts[0] == "web" and suffix in WEB_EXT and "node_modules" not in parts:
        npx = tool("npx")
        if not npx:
            return "npx가 없어 web 파일을 검사하지 못했다"
        target = str(Path(*parts[1:]))
        cmds = [([npx, "prettier", "--check", target], ROOT / "web")]
        if suffix in {".ts", ".tsx"}:
            cmds.append(([npx, "eslint", target], ROOT / "web"))
        return cmds
    if parts[0] == "transcoder" and suffix == ".py":
        uv = tool("uv")
        if not uv:
            return "uv가 없어 Python 파일을 검사하지 못했다. uv를 설치해야 한다"
        target = str(Path(*parts[1:]))
        return [
            ([uv, "run", "ruff", "check", target], ROOT / "transcoder"),
            ([uv, "run", "ruff", "format", "--check", target], ROOT / "transcoder"),
        ]
    if parts[0] == "infra" and suffix == ".tf":
        tf = tool("terraform")
        if not tf:
            return "terraform이 없어 .tf 파일을 검사하지 못했다"
        return [([tf, "fmt", "-check", "-diff", str(rel)], ROOT)]
    return []


def main() -> int:
    sys.stderr.reconfigure(encoding="utf-8")  # Windows 파이프 기본값은 cp949
    if os.environ.get("CI"):
        return 0
    payload = json.load(sys.stdin)
    path = payload.get("tool_input", {}).get("file_path")
    if not path:
        return 0
    try:
        rel = Path(path).resolve().relative_to(ROOT.resolve())
    except ValueError:
        return 0

    plan = checks(rel)
    if isinstance(plan, str):
        print(plan, file=sys.stderr)
        return 2
    failures = [out for cmd, cwd in plan if (out := run(cmd, cwd))]
    if failures:
        print(f"{rel} 검사 실패:\n" + "\n\n".join(failures), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
