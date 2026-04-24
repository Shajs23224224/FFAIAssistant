#!/usr/bin/env python3
"""
Script para crear modelos TFLite dummy para desarrollo.
Los modelos reales se entrenan en Colab y se descargan.
"""
import os
import sys

def create_dummy_tflite(path, size_bytes=1024):
    """Crea un archivo TFLite dummy con el tamaño especificado."""
    # Header mínimo de FlatBuffer TFLite
    header = bytes([
        0x18, 0x00, 0x00, 0x00,  # Offset a tabla root
        0x54, 0x46, 0x4C, 0x33,  # Magic "TFL3"
        0x00, 0x00, 0x0E, 0x00,  # Version
        0x14, 0x00,              # Byte length of offsets
        0x00, 0x00,              # Padding
    ])

    # Rellenar con zeros hasta el tamaño deseado
    data = header + bytes(size_bytes - len(header))

    with open(path, 'wb') as f:
        f.write(data)

    print(f"Created: {path} ({size_bytes} bytes)")

if __name__ == "__main__":
    # Determinar directorio de modelos
    if len(sys.argv) > 1:
        model_dir = sys.argv[1]
    else:
        # Default: app/src/main/assets/models
        script_dir = os.path.dirname(os.path.abspath(__file__))
        model_dir = os.path.join(script_dir, '..', 'app', 'src', 'main', 'assets', 'models')

    os.makedirs(model_dir, exist_ok=True)

    print(f"Creating dummy TFLite models in: {model_dir}")

    # Crear modelos dummy con tamaños representativos
    models = [
        ("perception.tflite", 2 * 1024 * 1024),  # 2MB - CNN
        ("policy.tflite", 1 * 1024 * 1024),      # 1MB - MLP
        ("economy.tflite", 512 * 1024),          # 512KB - MLP pequeño
    ]

    for name, size in models:
        path = os.path.join(model_dir, name)
        create_dummy_tflite(path, size)

    print("\nDone! These are DUMMY models for development.")
    print("Real models must be trained in Colab and placed here.")
    print("\nTotal dummy models:", len(models))
