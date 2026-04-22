# Hacksito — Tactical AI (Tyro)

Paquete Python `tactical_ai`: motor de decisión táctica y API HTTP para clientes (Android, Unity, CLI).

## Instalación

```bash
pip install -e ".[dev]"
```

## API (desarrollo)

```bash
export ENVIRONMENT=development
uvicorn tactical_ai.api.main:app --reload --host 0.0.0.0 --port 8000
```

## Documentación

- [Despliegue y variables de entorno](docs/DEPLOYMENT.md)
- [Integración cliente Android](docs/ANDROID_INTEGRATION.md)

## Tests

```bash
pytest
ruff check tactical_ai tests
```
