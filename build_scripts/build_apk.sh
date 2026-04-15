#!/bin/bash
# Script de build automatizado para FF AI Assistant
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

echo "=========================================="
echo "FF AI Assistant - Build Script"
echo "=========================================="
echo ""

# Verificar dependencias
echo "[1/8] Verificando dependencias..."
if ! command -v java &> /dev/null; then
    echo "Error: Java no está instalado"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | grep -oP 'version "[^"]+"' | grep -oP '\d+' | head -1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "Error: Se requiere Java 17+. Versión detectada: $JAVA_VERSION"
    exit 1
fi
echo "  ✓ Java $JAVA_VERSION detectado"

# Verificar modelo inicial
echo ""
echo "[2/8] Verificando modelo inicial..."
if [ ! -f "app/src/main/assets/model_init.tflite" ]; then
    echo "  Modelo no encontrado. Creando..."
    cd model_training
    python3 create_initial_model.py
    cd ..
else
    echo "  ✓ Modelo inicial encontrado"
fi

# Clean
echo ""
echo "[3/8] Limpiando build anterior..."
./gradlew clean --quiet

# Build debug
echo ""
echo "[4/8] Compilando APK Debug..."
./gradlew assembleDebug --quiet

# Verificar keystore
echo ""
echo "[5/8] Verificando keystore..."
KEYSTORE_FILE="keystore/ffai.keystore"
if [ ! -f "$KEYSTORE_FILE" ]; then
    echo "  Creando keystore nuevo..."
    mkdir -p keystore
    keytool -genkey -v \
        -keystore "$KEYSTORE_FILE" \
        -alias ffai \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=FFAI, OU=AI, O=FFAI, L=Mobile, S=Android, C=US" \
        -storepass ffai1234 \
        -keypass ffai1234
    echo "  ✓ Keystore creado"
else
    echo "  ✓ Keystore encontrado"
fi

# Build release
echo ""
echo "[6/8] Compilando APK Release..."
./gradlew assembleRelease --quiet

# Firmar APK
echo ""
echo "[7/8] Firmando APK..."
UNSIGNED_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED_APK="app/build/outputs/apk/release/FFAIAssistant-v2.apk"

if [ -f "$UNSIGNED_APK" ]; then
    jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
        -keystore "$KEYSTORE_FILE" \
        -storepass ffai1234 \
        "$UNSIGNED_APK" \
        ffai
    
    # Optimizar con zipalign
    if command -v zipalign &> /dev/null; then
        zipalign -v 4 "$UNSIGNED_APK" "$SIGNED_APK"
    else
        cp "$UNSIGNED_APK" "$SIGNED_APK"
    fi
    
    echo "  ✓ APK firmado: $SIGNED_APK"
else
    echo "  Error: APK unsigned no encontrado"
    exit 1
fi

# Verificar resultado
echo ""
echo "[8/8] Verificando APK..."
if [ -f "$SIGNED_APK" ]; then
    APK_SIZE=$(du -h "$SIGNED_APK" | cut -f1)
    echo "  ✓ APK generado exitosamente"
    echo "  ✓ Tamaño: $APK_SIZE"
    
    # Mostificar info del APK
    if command -v aapt &> /dev/null; then
        echo ""
        aapt dump badging "$SIGNED_APK" | head -5
    fi
else
    echo "  Error: No se pudo generar el APK"
    exit 1
fi

# Resumen
echo ""
echo "=========================================="
echo "✓ Build completado exitosamente"
echo "=========================================="
echo ""
echo "Archivos generados:"
echo "  Debug:   app/build/outputs/apk/debug/app-debug.apk"
echo "  Release: $SIGNED_APK"
echo ""
echo "Para instalar:"
echo "  adb install -r \"$SIGNED_APK\""
echo ""
