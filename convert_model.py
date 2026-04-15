#!/usr/bin/env python3
"""
Convierte el modelo entrenado de NumPy (.npz) a TensorFlow Lite (.tflite)
"""

import numpy as np
import tensorflow as tf
from pathlib import Path
import argparse


def load_numpy_model(npz_path: str) -> dict:
    """Carga los pesos desde archivo .npz"""
    data = np.load(npz_path)
    weights = {}
    for key in data.files:
        weights[key] = data[key]
        print(f"Loaded: {key} - shape: {weights[key].shape}")
    return weights


def create_ff_net_model(weights: dict) -> tf.keras.Model:
    """
    Recrea la arquitectura FFNetLite en Keras.
    
    Input: 8 features (health, ammo, enemy_present, enemy_x, enemy_y, 
                      enemy_distance, shoot_cooldown, heal_cooldown)
    Output: 6 acciones (SHOOT, MOVE_TO_COVER, HEAL, ROTATE, RELOAD, HOLD)
    """
    # Asumimos arquitectura: 8 -> 16 -> 8 -> 6 (como en el código original)
    model = tf.keras.Sequential([
        tf.keras.layers.Dense(16, activation='relu', input_shape=(8,)),
        tf.keras.layers.Dense(8, activation='relu'),
        tf.keras.layers.Dense(6, activation='softmax')
    ])
    
    # Asignar pesos si están disponibles
    try:
        # Mapear nombres de pesos del formato .npz a capas de Keras
        layer_idx = 0
        for key in sorted(weights.keys()):
            if 'W' in key or 'weight' in key.lower():
                w = weights[key]
                if layer_idx < len(model.layers):
                    layer = model.layers[layer_idx]
                    # Buscar bias correspondiente
                    bias_key = key.replace('W', 'b').replace('weight', 'bias')
                    if bias_key in weights:
                        b = weights[bias_key]
                        layer.set_weights([w, b])
                        print(f"Set weights for layer {layer_idx}: {key}, {bias_key}")
                        layer_idx += 1
    except Exception as e:
        print(f"Warning: Could not load all weights: {e}")
        print("Using random initialization")
    
    model.compile(optimizer='adam', loss='categorical_crossentropy')
    return model


def convert_to_tflite(model: tf.keras.Model, output_path: str) -> str:
    """Convierte modelo Keras a TFLite con optimizaciones"""
    
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    
    # Optimizaciones para móvil
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    
    # Quantization a INT8 (más rápido en CPU móvil)
    def representative_dataset():
        for _ in range(100):
            data = np.random.rand(1, 8).astype(np.float32)
            yield [data]
    
    converter.representative_dataset = representative_dataset
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.int8
    converter.inference_output_type = tf.int8
    
    tflite_model = converter.convert()
    
    # Guardar
    output_file = Path(output_path)
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_bytes(tflite_model)
    
    size_kb = len(tflite_model) / 1024
    print(f"TFLite model saved: {output_path}")
    print(f"Size: {size_kb:.2f} KB")
    
    return output_path


def main():
    parser = argparse.ArgumentParser(description="Convert NPZ model to TFLite")
    parser.add_argument("--input", "-i", required=True, help="Path to .npz model")
    parser.add_argument("--output", "-o", default="app/src/main/assets/model.tflite",
                        help="Output path for .tflite model")
    
    args = parser.parse_args()
    
    print(f"Loading model from: {args.input}")
    weights = load_numpy_model(args.input)
    
    print("\nCreating Keras model...")
    model = create_ff_net_model(weights)
    model.summary()
    
    print("\nConverting to TFLite...")
    convert_to_tflite(model, args.output)
    
    print("\nDone! Copy the .tflite file to app/src/main/assets/")


if __name__ == "__main__":
    main()
