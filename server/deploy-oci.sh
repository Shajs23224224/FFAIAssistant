#!/bin/bash
#
# Deploy script para Oracle Cloud Infrastructure (OCI)
# Configura la VM Always Free con Docker, SSL y el servidor AI
#

set -e

echo "=========================================="
echo "Free Fire AI - Oracle Cloud Deploy"
echo "=========================================="

# Colores
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Variables (modificar según tu setup)
DOMAIN=${DOMAIN:-""}  # Dejar vacío para usar IP
EMAIL=${EMAIL:-""}   # Para Let's Encrypt

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# ============================================
# 1. ACTUALIZAR SISTEMA
# ============================================
log_info "Actualizando sistema..."
sudo apt-get update -y
sudo apt-get upgrade -y

# ============================================
# 2. INSTALAR DOCKER
# ============================================
log_info "Instalando Docker..."
if ! command -v docker &> /dev/null; then
    curl -fsSL https://get.docker.com -o get-docker.sh
    sh get-docker.sh
    sudo usermod -aG docker $USER
    rm get-docker.sh
    log_info "Docker instalado"
else
    log_info "Docker ya está instalado"
fi

# Instalar Docker Compose
if ! command -v docker-compose &> /dev/null; then
    sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
    sudo chmod +x /usr/local/bin/docker-compose
    log_info "Docker Compose instalado"
fi

# ============================================
# 3. CONFIGURAR FIREWALL (UFW)
# ============================================
log_info "Configurando firewall..."
sudo apt-get install -y ufw
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow ssh
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable

# ============================================
# 4. CREAR ESTRUCTURA DE DIRECTORIOS
# ============================================
log_info "Creando estructura de directorios..."
mkdir -p ~/ffai-server/{nginx/ssl,models,logs}
cd ~/ffai-server

# ============================================
# 5. COPIAR ARCHIVOS DEL PROYECTO
# ============================================
log_info "Copiando archivos..."
# Estos archivos deben estar scp-eados previamente o usar git
if [ ! -f "docker-compose.yml" ]; then
    log_warn "docker-compose.yml no encontrado. Copia los archivos manualmente:"
    log_warn "  scp -r server/* opc@YOUR_OCI_IP:~/ffai-server/"
    exit 1
fi

# ============================================
# 6. CONFIGURAR SSL (Let's Encrypt o Self-signed)
# ============================================
if [ -n "$DOMAIN" ]; then
    log_info "Configurando Let's Encrypt para $DOMAIN..."
    sudo apt-get install -y certbot
    sudo certbot certonly --standalone -d $DOMAIN --agree-tos -n -m $EMAIL
    sudo cp /etc/letsencrypt/live/$DOMAIN/fullchain.pem nginx/ssl/
    sudo cp /etc/letsencrypt/live/$DOMAIN/privkey.pem nginx/ssl/
    sudo chown -R $USER:$USER nginx/ssl/
else
    log_warn "Sin dominio, creando certificado self-signed..."
    sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout nginx/ssl/privkey.pem \
        -out nginx/ssl/fullchain.pem \
        -subj "/C=US/ST=State/L=City/O=FFAI/CN=localhost"
fi

# ============================================
# 7. INICIAR SERVICIOS
# ============================================
log_info "Iniciando servicios Docker..."
docker-compose down 2>/dev/null || true
docker-compose pull
docker-compose up -d --build

# ============================================
# 8. VERIFICAR SERVICIOS
# ============================================
log_info "Verificando servicios..."
sleep 5

if curl -s http://localhost:8000/health > /dev/null; then
    log_info "✓ API está respondiendo"
else
    log_error "✗ API no responde"
    docker-compose logs api
fi

if curl -s -o /dev/null -w "%{http_code}" http://localhost > /dev/null; then
    log_info "✓ Nginx está respondiendo"
else
    log_error "✗ Nginx no responde"
    docker-compose logs nginx
fi

# ============================================
# 9. CONFIGURAR AUTO-START
# ============================================
log_info "Configurando auto-start..."
sudo tee /etc/systemd/system/ffai.service > /dev/null <<EOF
[Unit]
Description=Free Fire AI Server
Requires=docker.service
After=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/home/opc/ffai-server
User=opc
ExecStart=/usr/local/bin/docker-compose up -d
ExecStop=/usr/local/bin/docker-compose down
TimeoutStartSec=0

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable ffai.service

# ============================================
# 10. INFO FINAL
# ============================================
echo ""
echo "=========================================="
echo -e "${GREEN}¡DEPLOY COMPLETADO!${NC}"
echo "=========================================="
echo ""
echo "URLs de acceso:"
if [ -n "$DOMAIN" ]; then
    echo "  WebSocket: wss://$DOMAIN/ws"
    echo "  HTTP:      https://$DOMAIN"
else
    IP=$(curl -s ifconfig.me)
    echo "  WebSocket: ws://$IP/ws"
    echo "  HTTP:      http://$IP"
fi
echo ""
echo "Comandos útiles:"
echo "  Ver logs:  docker-compose logs -f"
echo "  Reiniciar: docker-compose restart"
echo "  Detener:   docker-compose down"
echo ""
echo "Para actualizar:"
echo "  git pull && docker-compose up -d --build"
echo ""
