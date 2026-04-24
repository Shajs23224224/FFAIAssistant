# Imagen de producción para Render u otro host Docker.
FROM python:3.12-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

COPY pyproject.toml README.md ./
COPY tactical_ai ./tactical_ai

RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir .

EXPOSE 8000

CMD uvicorn tactical_ai.api.main:app --host 0.0.0.0 --port ${PORT:-8000} --workers 1
