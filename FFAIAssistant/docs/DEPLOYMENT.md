# Despliegue (Render.com, Docker, variables de entorno)

## Comando de arranque

```bash
uvicorn tactical_ai.api.main:app --host 0.0.0.0 --port "$PORT" --workers 1
```

En Docker/Render, `PORT` lo inyecta la plataforma.

## Variables de entorno

| Variable | Obligatorio | Descripción |
|----------|-------------|-------------|
| `ENVIRONMENT` | Recomendado | `development` \| `production` \| `test`. En `production` exige `TACTICAL_AI_API_KEYS`. |
| `TACTICAL_AI_API_KEYS` | **Sí en producción** | Lista separada por comas. Cliente: cabecera `Authorization: Bearer <clave>` o `X-API-Key`. |
| `CORS_ORIGINS` | No | Orígenes permitidos separados por coma. Vacío + `development` → `*`. |
| `ENABLE_MOBILE_WS_ROUTES` | No | `true` para exponer `/mobile/*` y WebSocket (estado solo en RAM). |
| `ENABLE_GAME_INTELLIGENCE` | No | `true` para activar `POST /api/game-intelligence`. |
| `RATE_LIMIT_DEFAULT` | No | Por defecto el código usa `120/minute` en rutas anotadas con SlowAPI. |
| `MAX_JSON_BODY_BYTES` | No | Reservado para futuro middleware de tamaño (por defecto 1 MiB en settings). |

## Health checks

- **Liveness**: `GET /health` → 200 si el proceso responde.
- **Readiness**: `GET /ready` → 200 si el warmup del agente (pipeline base sin capas avanzadas) tuvo éxito; 503 si falló.

## Render.com

1. Crea un **Web Service** desde este repo (Dockerfile en la raíz) o usa [render.yaml](../render.yaml) como blueprint.
2. Configura `TACTICAL_AI_API_KEYS` en el panel (Environment).
3. Health check path: `/health` (o `/ready` si quieres no recibir tráfico hasta que el agente esté listo).

## GitHub Actions

El workflow [`.github/workflows/ci.yml`](../.github/workflows/ci.yml) instala el paquete, ejecuta Ruff en los módulos del API y pytest. No despliega automáticamente a Render; puedes añadir un paso con [Deploy Hook](https://render.com/docs/deploy-hooks) del servicio si lo necesitas.

## Notas de escalado

- Con más de un worker o varias instancias, el estado en memoria de rutas móviles **no** está compartido; usa diseño stateless (`POST /v1/decide` con snapshot completo) o un almacén externo (Redis).
