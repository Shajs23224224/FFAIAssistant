#!/bin/bash
set -e

cd /root/Vídeos/FFAIAssistant

# Limpiar posibles locks
rm -f .git/index.lock

# Configurar git
git config user.name "FFAI Assistant"
git config user.email "assistant@ffai.com"

echo "=== Git Status ==="
git status

echo "=== Git Add ==="
git add -A

echo "=== Git Commit ==="
git commit -m "Fix: Corregir errores de compilación Kotlin - API contracts, Float/Double, enums, async scope

Archivos modificados:
- AdaptiveInferencePipeline.kt: scope.async, ThermalManager.ThermalState
- AdvancedAICore.kt: recordFrame(), totalDetections
- EnsembleRLCoordinator.kt: .discreteAction, .action
- TransformerAgent.kt: DoubleArray->FloatArray conversion
- SituationAnalyzer.kt: ReasoningModeCore enum

Los cambios alinean los consumidores con las APIs actuales del sistema."

echo "=== Git Push ==="
git push origin build

echo "=== Verificación ==="
git log --oneline -3
