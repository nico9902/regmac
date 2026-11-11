FROM python:3.12-slim
WORKDIR /app
ARG AI_EXTRAS=""
COPY pyproject.toml .
COPY app ./app
RUN if [ -n "$AI_EXTRAS" ]; then pip install --no-cache-dir ".[${AI_EXTRAS}]"; else pip install --no-cache-dir .; fi
RUN useradd -r -u 10001 regmac
USER regmac
ENV HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 HF_HUB_DISABLE_TELEMETRY=1
EXPOSE 8000
CMD ["uvicorn","app.main:app","--host","0.0.0.0","--port","8000"]
