#!/bin/bash
# Script para subir proyecto a GitHub

echo "=========================================="
echo "FF AI Assistant - Subir a GitHub"
echo "=========================================="
echo ""

# Verificar argumento
if [ -z "$1" ]; then
    echo "Uso: ./upload_to_github.sh TU_USUARIO_GITHUB"
    echo "Ejemplo: ./upload_to_github.sh miusuario"
    exit 1
fi

GITHUB_USER=$1
REPO_NAME="FFAIAssistant"

echo "Usuario GitHub: $GITHUB_USER"
echo "Repo: $REPO_NAME"
echo ""

# Verificar git
if ! command -v git &> /dev/null; then
    echo "Instalando git..."
    apt-get update && apt-get install -y git
fi

# Configurar git si no está configurado
if [ -z "$(git config --global user.email 2>/dev/null)" ]; then
    echo "Configurando git..."
    git config --global user.email "ffai@example.com"
    git config --global user.name "FF AI"
fi

# Inicializar repo
echo "[1/5] Inicializando repositorio..."
git init

# Agregar archivos
echo "[2/5] Agregando archivos..."
git add .

# Commit
echo "[3/5] Creando commit..."
git commit -m "FF AI Assistant v2.0 - Online Learning for Free Fire

Features:
- 15 AI actions (shoot, heal, move, etc.)
- Online learning with SQLite
- TensorFlow Lite inference
- Android 12+ compatible
- 60 FPS screen capture
- Accessibility gesture injection"

# Agregar remote
echo "[4/5] Conectando a GitHub..."
git remote add origin "https://github.com/$GITHUB_USER/$REPO_NAME.git" 2>/dev/null || \
git remote set-url origin "https://github.com/$GITHUB_USER/$REPO_NAME.git"

# Push
echo "[5/5] Subiendo a GitHub..."
echo ""
echo "Se te pedirá tu token de GitHub."
echo "Si no tienes uno, crea en: https://github.com/settings/tokens"
echo ""
git branch -M main
git push -u origin main

echo ""
echo "=========================================="
if [ $? -eq 0 ]; then
    echo "✅ Subido exitosamente!"
    echo ""
    echo "Próximos pasos:"
    echo "1. Ve a: https://github.com/$GITHUB_USER/$REPO_NAME"
    echo "2. Click en 'Actions'"
    echo "3. Click en 'Enable workflows'"
    echo "4. Click en 'Build APK' → 'Run workflow'"
    echo "5. Espera 5 minutos y descarga el APK"
else
    echo "❌ Error al subir"
    echo "Verifica que el repo exista en GitHub"
fi
echo "=========================================="
