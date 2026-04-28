#!/bin/bash
set -e

cd /root/Vídeos/FFAIAssistant

# Configurar git
git config user.name "FFAI Assistant"
git config user.email "assistant@ffai.com"

# Añadir cambios
git add -A

# Verificar si hay cambios para commitear
if git diff --cached --quiet; then
    echo "No hay cambios para commitear"
    exit 0
fi

# Crear commit con los cambios realizados
git commit -m "Fix: Corregir errores de compilación en archivos principales

Archivos modificados:
- AdaptiveInferencePipeline.kt: Agregado onThermalThrottle()
- DecisionEngine.kt: Actualizado a recordStageTime()
- FFAccessibilityService.kt: Corregida inicialización de ROITracker
- DeepRLCore.kt: Cambiado val a var en priority de RLExperience
- gradle-wrapper.properties: Actualizado a Gradle 8.13
- build.sh: Actualizado a Java 25

Los cambios alinean los consumidores con las APIs actuales del sistema."

# Push a la rama build
git push origin build

echo "✅ Cambios subidos exitosamente a la rama build"
