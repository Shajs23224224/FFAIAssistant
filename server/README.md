# Free Fire AI Server - Oracle Cloud Edition

Servidor profesional para procesar gameplay con IA avanzada (YOLOv8 + PPO) optimizado para Oracle Cloud Infrastructure (OCI) Always Free Tier.

## Arquitectura OCI

```
┌─────────────────┐
│   APK Android   │
│  (Kotlin/Ktor)  │
└────────┬────────┘
         │ WebSocket + Gzip
         ↓
┌─────────────────────────────┐
│     Oracle Cloud VM         │
│  (ARM64 Always Free 4 OCPU) │
│  ┌─────────────────────┐  │
│  │   Nginx (SSL/WS)    │  │
│  └──────────┬──────────┘  │
│  ┌──────────┴──────────┐  │
│  │   FastAPI Server    │  │
│  │  ┌───────────────┐  │  │
│  │  │ YOLOv8 Vision │  │  │
│  │  │ PPO RL Agent  │  │  │
│  │  └───────────────┘  │  │
│  └─────────────────────┘  │
└─────────────────────────────┘
```

## Quick Start - Oracle Cloud

### 1. Crear VM en OCI (Always Free)

```bash
# Shape: VM.Standard.A1.Flex (ARM64)
# OCPUs: 4
# RAM: 24 GB
# Storage: 200 GB
```

### 2. Deploy Automático

```bash
# En tu máquina local, copiar archivos:
scp -r server/* opc@YOUR_OCI_IP:~/ffai-server/

# Conectar por SSH y ejecutar deploy:
ssh opc@YOUR_OCI_IP
cd ~/ffai-server
chmod +x deploy-oci.sh
./deploy-oci.sh
```

### 3. Con Dominio (SSL)

```bash
DOMAIN=ai.tudominio.com EMAIL=tu@email.com ./deploy-oci.sh
```

## Estructura del Proyecto

```
server/
├── main_advanced.py      # Servidor FastAPI con IA avanzada
├── requirements.txt      # Dependencias Python
├── Dockerfile           # Container multi-stage
├── docker-compose.yml   # Stack completo (API + Nginx + Redis)
├── nginx/
│   └── nginx.conf       # Reverse proxy con SSL
├── deploy-oci.sh        # Script de deploy automático
└── README.md
```

## Features

### IA Avanzada
- **Vision**: YOLOv8 para detección de UI (health bar, ammo, enemigos)
- **RL Agent**: PPO (Proximal Policy Optimization) para decisiones tácticas
- **Red Neuronal**: CNN + LSTM para memoria temporal

### Infraestructura Profesional
- **Docker**: Multi-stage build optimizado
- **Nginx**: Reverse proxy, SSL/TLS, rate limiting
- **Redis**: Cache de sesiones y frames
- **Auto-restart**: systemd service
- **Health checks**: Endpoints de monitoreo

### Optimizaciones
- WebSocket con compresión gzip
- Backoff exponencial para reconexión
- Heartbeat/ping cada 30s
- Frame rate limitado (10 FPS)
- JPEG quality optimizado (50%)

## API Endpoints

| Endpoint | Método | Descripción |
|----------|--------|-------------|
| `/` | GET | Health check + info |
| `/health` | GET | Health check simple |
| `/stats` | GET | Estadísticas de frames |
| `/ws` | WebSocket | Comunicación con APK |

## WebSocket Protocol

### Cliente → Servidor
```json
{
  "type": "frame",
  "timestamp": 1234567890,
  "health": 0.75,
  "ammo": 0.30,
  "imageBase64": "...",
  "fps": 15,
  "ping": 120
}
```

### Servidor → Cliente
```json
{
  "type": "action",
  "action": "SHOOT",
  "confidence": 0.92,
  "coordinates": {"x": 1200, "y": 800},
  "duration": 100,
  "game_state": {
    "health_ratio": 0.75,
    "ammo_ratio": 0.30,
    "enemy_present": true
  }
}
```

## Monitoreo

```bash
# Ver logs en tiempo real
docker-compose logs -f

# Ver uso de recursos
docker stats

# Health check
curl http://localhost/health
```

## Troubleshooting

### Conexión WebSocket falla
1. Verificar firewall: `sudo ufw status`
2. Verificar Nginx: `docker-compose logs nginx`
3. Verificar IP en `ServerConfig.kt`

### IA lenta
- Reducir `MAX_FRAME_WIDTH/HEIGHT` en ServerConfig.kt
- Aumentar `FRAME_INTERVAL` (menos FPS)
- Verificar recursos VM: `htop`

### Memoria insuficiente
- Ajustar límites en `docker-compose.yml`
- Considerar usar swap: `sudo fallocate -l 4G /swapfile`

## Costo

**$0/mes** - Todo dentro del Always Free tier de OCI:
- 2 VMs (ARM64 o AMD)
- 4 OCPUs + 24GB RAM por VM
- 200GB storage
- 10TB bandwidth/mes

## Actualizar

```bash
cd ~/ffai-server
git pull origin main  # Si usas git
docker-compose down
docker-compose up -d --build
```

## License

MIT - Free Fire AI Assistant Project
