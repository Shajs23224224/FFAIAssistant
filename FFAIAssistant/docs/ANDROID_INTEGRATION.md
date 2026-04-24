# Integración cliente Android ↔ Tactical AI

## Endpoints principales

- **`POST /v1/decide`** — Contrato con envelope (recomendado para producción).
- **`POST /decide`** — Cuerpo plano igual que [`tactical_ai/sample_snapshot.json`](../tactical_ai/sample_snapshot.json) (incluye `_meta` opcional).

Autenticación (si el servidor tiene `TACTICAL_AI_API_KEYS`):

- Cabecera `Authorization: Bearer <clave>`, o
- Cabecera `X-API-Key: <clave>`.

Use siempre **HTTPS** (TLS); en Android evita cleartext salvo entornos de depuración controlados.

## Contrato `POST /v1/decide`

### Request (schema 1.0)

```json
{
  "schema_version": "1.0",
  "correlation_id": "uuid-o-id-opaco",
  "timestamp_ms": 1712938123456,
  "event_type": "GAME_TICK",
  "session_id": "opcional",
  "device_id": "opcional",
  "source": "apk_client",
  "payload": {
    "tick_ms": 0,
    "player_hp": 55,
    "player_hp_max": 100,
    "ammo": 18,
    "cooldowns_ms": { "heal": 8000 },
    "player_position": [12.5, 8.0],
    "exposure": 0.72,
    "zone_pressure": 0.48,
    "enemies": [
      {
        "id": "a1",
        "position": [22.0, 9.0],
        "visible": true,
        "in_cover_estimate": 0.3
      }
    ],
    "_meta": {
      "personality": "tactico",
      "seed": 7,
      "dt_ms": 33.0
    }
  }
}
```

- **`payload`**: mismo formato que acepta `game_snapshot_from_mapping` (ver docstring en `tactical_ai/external_snapshot.py`).
- **`_meta`**: opcional; `personality`, `seed`, `dt_ms` alineados con el CLI `run_snapshot`.

### Response (éxito)

```json
{
  "schema_version": "1.0",
  "response_version": "1.0",
  "correlation_id": "uuid-o-id-opaco",
  "status": "ok",
  "error_code": null,
  "error_message": null,
  "payload": {
    "action": "HOLD",
    "aim_delta": { "x": 0.0, "y": 0.0 },
    "move_intent": { "x": 0.0, "y": 0.0 },
    "reaction_delay_applied_ms": 100
  }
}
```

### Errores de validación / versión

`status: "error"` con `error_code` estable (p. ej. `SCHEMA_VERSION_UNSUPPORTED`, `VALIDATION_SNAPSHOT`). El cliente debe hacer **fallback local** (p. ej. acción `HOLD`) sin crashear.

## Cliente HTTP en Android (Kotlin)

- **No bloquear el hilo principal**: usar **Coroutines** (`Dispatchers.IO`) o `Executor` + callback al hilo principal.
- **OkHttp** o **Retrofit** con:
  - Timeout de conexión y lectura explícitos (p. ej. 5–15 s según red).
  - Reintentos con backoff **solo** para errores idempotentes y códigos 5xx / red; no reintentar 401/400 sin cambiar credenciales o payload.
- **Archivos JSON** (opcional): puedes escribir `tyro.request.<correlation_id>.json` y la respuesta en `tyro.response.<correlation_id>.json` para depuración; el transporte de producción sigue siendo HTTP.

### Unity

Si el juego es Unity, coloca el cliente HTTP en un **plugin Android (Kotlin/Java)** o usa `UnityWebRequest` en un hilo de fondo / `async` C# y serializa el mismo JSON; no ejecutes llamadas bloqueantes en el hilo principal de Unity.

## WebSocket (opcional)

Si activas `ENABLE_MOBILE_WS_ROUTES` en el servidor:

- URL ejemplo: `wss://<host>/mobile/ws/<device_id>?api_key=<clave>` (la clave debe coincidir con `TACTICAL_AI_API_KEYS` en producción).
- Mensaje `{"type":"game_state","state":{...}}` con el mismo shape que `MobileGameState` en el servidor.

**Nota:** el estado de sesión es **en memoria** en una sola instancia; para multi-instancia prefiere `POST /v1/decide` stateless.

## Compatibilidad de versiones

- Enviar siempre `schema_version` coherente con la app.
- Ignorar campos desconocidos en la respuesta para permitir evolución del backend.
