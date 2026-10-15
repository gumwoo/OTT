FROM python:3.13-slim AS build
RUN pip install --no-cache-dir --root-user-action=ignore uv==0.13.0
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=0
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY src ./src
RUN uv sync --locked --no-dev --no-editable

FROM python:3.13-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 999 --no-create-home --shell /usr/sbin/nologin transcoder
COPY --from=build /app/.venv /app/.venv
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
USER 999
# PY-07: 셸 래퍼 없이 Python이 PID 1로 SIGTERM을 받는다
ENTRYPOINT ["transcoder"]
