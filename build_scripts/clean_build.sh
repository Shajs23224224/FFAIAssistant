#!/bin/bash
# Script de limpieza de archivos temporales

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

echo "Limpiando archivos temporales..."

# Limpiar build de Gradle
if [ -d "app/build" ]; then
    rm -rf app/build
    echo "  ✓ Eliminado app/build/"
fi

# Limpiar archivos .gradle
if [ -d ".gradle" ]; then
    rm -rf .gradle
    echo "  ✓ Eliminado .gradle/"
fi

# Limpiar archivos temporales
find . -name "*.tmp" -delete 2>/dev/null
find . -name "*.temp" -delete 2>/dev/null
find . -name ".DS_Store" -delete 2>/dev/null

# Limpiar logs antiguos
if [ -d "app/src/main/logs" ]; then
    rm -rf app/src/main/logs/*.log
    echo "  ✓ Limpiados logs antiguos"
fi

# Mantener solo últimos 5 modelos backup
MODEL_DIR="app/src/main/assets"
if [ -d "$MODEL_DIR" ]; then
    ls -t "$MODEL_DIR"/model_backup_*.tflite 2>/dev/null | tail -n +6 | xargs -r rm
    echo "  ✓ Limpiados modelos backup antiguos"
fi

echo ""
echo "✓ Limpieza completada"
echo ""
echo "Espacio liberado:"
du -sh . 2>/dev/null || echo "  (No disponible)"
