#!/bin/bash
# Script de build para FFAIAssistant
# Configura automáticamente el entorno y compila

set -e

# Colores
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo "🔧 Configurando entorno..."

# Configurar Java 17
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH

# Configurar Android SDK
export ANDROID_HOME=/root/Vídeos/FFAIAssistant/android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

echo "✅ Java: $(java -version 2>&1 | head -1)"
echo "✅ ANDROID_HOME: $ANDROID_HOME"
echo "✅ SDK Manager: $(which sdkmanager)"

# Verificar SDK
echo ""
echo "📦 Verificando componentes del SDK..."

if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
    echo "⬇️  Instalando Android API 34..."
    yes | sdkmanager "platforms;android-34" 2>/dev/null || true
fi

if [ ! -d "$ANDROID_HOME/build-tools/34.0.0" ]; then
    echo "⬇️  Instalando Build Tools 34.0.0..."
    yes | sdkmanager "build-tools;34.0.0" 2>/dev/null || true
fi

# Aceptar licencias
echo "📝 Aceptando licencias..."
yes | sdkmanager --licenses 2>/dev/null || true

echo ""
echo "🔨 Iniciando build..."
./gradlew clean compileDebugKotlin --no-daemon

echo ""
echo -e "${GREEN}✅ Build completado exitosamente${NC}"
