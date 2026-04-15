#!/bin/bash
# Arreglar errores de git

echo "Arreglando configuración de Git..."

# 1. Configurar identidad
git config --global user.email "ffai@example.com"
git config --global user.name "FF AI Assistant"

# 2. Verificar estado
echo ""
echo "Estado actual:"
git status

# 3. Agregar todos los archivos
echo ""
echo "Agregando archivos..."
git add .

# 4. Crear commit
echo ""
echo "Creando commit..."
git commit -m "FF AI Assistant v2.0 - Online Learning" || echo "Commit ya existe"

# 5. Forzar push
echo ""
echo "Subiendo a GitHub..."
git push -f origin main

echo ""
echo "Listo! Verifica en: https://github.com/Shajs23224224/FFAIAssistant"
