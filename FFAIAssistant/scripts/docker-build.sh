#!/bin/bash
# Build rápido de FFAIAssistant usando Docker con imagen ligera

set -e

echo "=== FFAIAssistant Docker Build ==="
echo ""

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

# Verificar que Docker esté disponible
if ! command -v docker &> /dev/null; then
    echo "❌ Docker no está instalado"
    exit 1
fi

echo "📦 Usando imagen ligera: eclipse-temurin:17-jdk"
echo "⏳ Este proceso toma ~5-10 minutos la primera vez..."
echo ""

# Build usando imagen ligera con Gradle wrapper del proyecto
docker run --rm \
    -v "$PROJECT_DIR":/project \
    -w /project \
    -e GRADLE_OPTS="-Xmx2g -XX:MaxMetaspaceSize=512m -Dorg.gradle.daemon=false" \
    eclipse-temurin:17-jdk \
    bash -c "
        echo '🔧 Instalando dependencias...' && \
        apt-get update -qq && apt-get install -y -qq git wget unzip > /dev/null 2>&1 && \
        
        echo '📥 Descargando Android SDK...' && \
        export ANDROID_HOME=/tmp/android-sdk && \
        mkdir -p \$ANDROID_HOME && \
        wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/sdk.zip && \
        unzip -q /tmp/sdk.zip -d \$ANDROID_HOME/cmdline-tools && \
        mv \$ANDROID_HOME/cmdline-tools/cmdline-tools \$ANDROID_HOME/cmdline-tools/latest && \
        
        echo '✅ Aceptando licencias...' && \
        yes | \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 || true && \
        
        echo '📦 Instalando platform y build-tools...' && \
        \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'platforms;android-34' 'build-tools;34.0.0' > /dev/null 2>&1 && \
        
        echo '🔨 Compilando APK...' && \
        chmod +x gradlew && \
        ./gradlew assembleRelease --no-daemon -x test 2>&1 && \
        echo '' && \
        echo '✅ Build completado!' && \
        ls -lh app/build/outputs/apk/release/*.apk 2>/dev/null || echo '⚠️ APK no encontrada'
    "

BUILD_STATUS=$?

echo ""
if [ $BUILD_STATUS -eq 0 ]; then
    echo "=== ✅ BUILD EXITOSO ==="
    echo ""
    echo "APK generada:"
    ls -lh "$PROJECT_DIR/app/build/outputs/apk/release/"*.apk 2>/dev/null || echo "Verificar directorio de salida"
    echo ""
    echo "Instalación manual:"
    echo "  adb install -r app/build/outputs/apk/release/app-release.apk"
else
    echo "=== ❌ BUILD FALLÓ ==="
    echo "Código de error: $BUILD_STATUS"
    exit 1
fi
