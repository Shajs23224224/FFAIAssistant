#!/usr/bin/env python3
"""
Crea un modelo TensorFlow Lite inicial para la IA.
Arquitectura: 8 -> 32 -> 16 -> 15 (features -> hidden1 -> hidden2 -> actions)
"""

import tensorflow as tf
import numpy as np
from pathlib import Path


def create_model():
    """Crea modelo de red neuronal para decisión de acciones."""
    
    model = tf.keras.Sequential([
        # Input: 8 features (health, ammo, enemy_present, enemy_x, enemy_y, enemy_distance, shoot_cooldown, heal_cooldown)
        tf.keras.layers.Dense(32, activation='relu', input_shape=(8,)),
        tf.keras.layers.Dropout(0.2),
        tf.keras.layers.Dense(16, activation='relu'),
        tf.keras.layers.Dropout(0.1),
        # Output: 15 acciones con softmax para probabilidades
        tf.keras.layers.Dense(15, activation='softmax')
    ])
    
    # Compilar con configuración para RL
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss='categorical_crossentropy',
        metrics=['accuracy']
    )
    
    return model


def convert_to_tflite(model, output_path):
    """Convierte modelo a TensorFlow Lite optimizado."""
    
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    
    # Optimizaciones para móvil
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    
    # Quantization a float16 (buen balance precisión/velocidad)
    converter.target_spec.supported_types = [tf.float16]
    
    # Permitir operaciones selectivas
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS
    ]
    
    tflite_model = converter.convert()
    
    # Guardar
    output_file = Path(output_path)
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_bytes(tflite_model)
    
    size_kb = len(tflite_model) / 1024
    print(f"✓ Modelo TFLite guardado: {output_path}")
    print(f"  Tamaño: {size_kb:.2f} KB")
    print(f"  Input: 8 features")
    print(f"  Output: 15 acciones")
    
    return output_path


def test_model(model_path):
    """Prueba el modelo con input de ejemplo."""
    
    # Cargar modelo TFLite
    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()
    
    # Obtener detalles de input/output
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    
    print(f"\n✓ Modelo cargado exitosamente")
    print(f"  Input shape: {input_details[0]['shape']}")
    print(f"  Output shape: {output_details[0]['shape']}")
    
    # Test con estado de ejemplo (vida alta, enemigo presente)
    test_input = np.array([[0.9, 0.8, 1.0, 0.3, -0.2, 0.5, 0.0, 0.0]], dtype=np.float32)
    
    interpreter.set_tensor(input_details[0]['index'], test_input)
    interpreter.invoke()
    output = interpreter.get_tensor(output_details[0]['index'])
    
    action_names = [
        "AIM", "SHOOT", "MOVE_FORWARD", "MOVE_BACKWARD", "MOVE_LEFT", 
        "MOVE_RIGHT", "HEAL", "RELOAD", "CROUCH", "JUMP", 
        "LOOT", "REVIVE", "ROTATE_LEFT", "ROTATE_RIGHT", "HOLD"
    ]
    
    print(f"\n✓ Test con estado: vida=0.9, ammo=0.8, enemy_present=1.0")
    print(f"  Probabilidades por acción:")
    for i, (name, prob) in enumerate(zip(action_names, output[0])):
        print(f"    {name:15}: {prob:.4f}")
    
    best_action = action_names[np.argmax(output[0])]
    print(f"\n  Acción seleccionada: {best_action}")


def main():
    print("=" * 50)
    print("FF AI Assistant - Modelo Inicial")
    print("=" * 50)
    
    # Crear modelo
    print("\n[1/3] Creando modelo Keras...")
    model = create_model()
    model.summary()
    
    # Convertir a TFLite
    output_path = "../app/src/main/assets/model_init.tflite"
    print(f"\n[2/3] Convirtiendo a TFLite...")
    convert_to_tflite(model, output_path)
    
    # Test
    print(f"\n[3/3] Probando modelo...")
    test_model(output_path)
    
    print("\n" + "=" * 50)
    print("✓ Modelo inicial creado exitosamente")
    print(f"✓ Ubicación: {output_path}")
    print("=" * 50)


if __name__ == "__main__":
    main()
