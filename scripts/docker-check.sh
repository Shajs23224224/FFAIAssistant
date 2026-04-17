#!/bin/bash
# Script para verificar si el entorno soporta Android en Docker

echo "=== Verificación de entorno Docker para Android ==="
echo ""

# Verificar Docker
if ! command -v docker &> /dev/null; then
    echo "❌ Docker no está instalado"
    exit 1
fi
echo "✅ Docker instalado: $(docker --version)"

# Verificar Docker Compose
if command -v docker-compose &> /dev/null; then
    echo "✅ Docker Compose instalado: $(docker-compose --version)"
elif docker compose version &> /dev/null; then
    echo "✅ Docker Compose (plugin) disponible"
else
    echo "⚠️  Docker Compose no encontrado (se usará 'docker compose')"
fi

# Verificar KVM (necesario para emulador)
if [ -e /dev/kvm ]; then
    echo "✅ KVM disponible: $(ls -la /dev/kvm)"
    if groups | grep -qE 'kvm|libvirt'; then
        echo "✅ Usuario en grupo KVM"
    else
        echo "⚠️  Usuario no en grupo KVM (puede necesitar sudo)"
    fi
else
    echo "❌ KVM no disponible - No se puede usar emulador acelerado"
    echo "   Opciones alternativas:"
    echo "   1. Usar build-only (sin emulador)"
    echo "   2. Instalar qemu-kvm y configurar /dev/kvm"
fi

# Verificar recursos
MEM_GB=$(free -g | awk '/^Mem:/{print $2}')
if [ "$MEM_GB" -lt 4 ]; then
    echo "⚠️  RAM insuficiente: ${MEM_GB}GB (mínimo 4GB recomendado)"
else
    echo "✅ RAM: ${MEM_GB}GB"
fi

# Verificar espacio en disco
DISK_GB=$(df -BG . | awk 'NR==2{print $4}' | tr -d 'G')
if [ "$DISK_GB" -lt 10 ]; then
    echo "⚠️  Espacio en disco bajo: ${DISK_GB}GB libres (10GB+ recomendado)"
else
    echo "✅ Espacio en disco: ${DISK_GB}GB libres"
fi

echo ""
echo "=== Opciones disponibles ==="
echo ""
echo "1. BUILD ONLY (sin emulador):"
echo "   docker run --rm -v '\$(pwd)':/project mingc/android-build-box bash -c 'cd /project && ./gradlew assembleRelease'"
echo ""
echo "2. BUILD + EMULADOR (requiere KVM):"
echo "   docker-compose -f docker-android-test.yml up"
echo ""
echo "3. VERIFICAR CÓDIGO SÓLO:"
echo "   ./gradlew lint check"
echo ""
