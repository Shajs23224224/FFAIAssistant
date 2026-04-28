# Google Colab - Generación de Modelos TFLite

Esta carpeta contiene notebooks y scripts para entrenar y exportar modelos TFLite para FFAI Assistant.

## Estructura

```
colab/
├── README.md                      (Este archivo)
├── 01_yolov8_training.ipynb       (Detección de objetos)
├── 02_dqn_training.ipynb          (Deep Q-Network)
├── 03_ppo_training.ipynb          (Proximal Policy Optimization)
├── 04_sac_training.ipynb          (Soft Actor-Critic)
├── 05_world_model.ipynb           (World Model / Dreamer)
├── 06_transformer.ipynb           (Transformer Policy)
├── 07_icm_curiosity.ipynb         (Intrinsic Curiosity Module)
├── 08_meta_learning.ipynb         (MAML + Meta Controller)
├── utils/
│   ├── export_tflite.py           (Utilidades de exportación)
│   └── game_environment.py        (Simulador del juego para RL)
└── data/
    └── sample_game_frames.zip     (Frames de ejemplo para entrenamiento)
```

## Flujo de Trabajo

1. **Abrir notebook en Google Colab**
   - Subir archivo `.ipynb` a Colab
   - O abrir desde GitHub: `File > Open notebook > GitHub`

2. **Entrenar modelo**
   - Ejecutar celdas en orden
   - Ajustar hiperparámetros según necesidad

3. **Exportar a TFLite**
   - La última celda exporta automáticamente
   - Descargar archivo `.tflite`

4. **Copiar a Android Studio**
   ```bash
   cp modelo.tflite /ruta/proyecto/app/src/main/assets/
   ```

## Recursos de GPU en Colab

```python
# Verificar GPU disponible
import tensorflow as tf
print("GPU disponible:", tf.config.list_physical_devices('GPU'))
print("CUDA disponible:", tf.test.is_built_with_cuda())
```

## Notas

- Usar **runtime GPU** para entrenamiento rápido
- Guardar checkpoints en Google Drive: `/content/drive/MyDrive/FFAI/`
- Los modelos FP16 reducen tamaño 50% con mínima pérdida de precisión
