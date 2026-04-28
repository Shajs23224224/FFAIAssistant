# Configuración de Modelos TFLite

## Estructura de Assets

Los modelos deben colocarse en:
```
app/src/main/assets/
├── yolov8n_fp16.tflite          (Detección de objetos)
├── dqn_dueling.tflite           (Agente DQN)
├── dqn_target.tflite            (DQN Target Network)
├── ppo_actor.tflite             (Actor PPO)
├── ppo_critic.tflite            (Crítico PPO)
├── sac_actor.tflite             (Actor SAC)
├── sac_q1.tflite                (Q-Network 1 SAC)
├── sac_q2.tflite                (Q-Network 2 SAC)
├── world_model_encoder.tflite   (World Model - Encoder)
├── world_model_transition.tflite (World Model - Transition)
├── world_model_reward.tflite    (World Model - Reward)
├── transformer_policy.tflite    (Política Transformer)
├── icm_forward.tflite          (ICM Forward Model)
├── icm_inverse.tflite          (ICM Inverse Model)
├── icm_feature.tflite          (ICM Feature Extractor)
├── meta_controller.tflite      (Meta Controller)
├── sub_policies.tflite         (Sub-policies)
└── maml_meta.tflite            (Meta-parámetros MAML)
```

## Entrenamiento en Google Colab

### 1. YOLOv8 (Visión)
```python
# En Google Colab
from ultralytics import YOLO

# Descargar modelo pre-entrenado
model = YOLO('yolov8n.pt')

# Exportar a TFLite FP16
model.export(format='tflite', int8=False, half=True)

# Descargar
from google.colab import files
files.download('yolov8n_fp16.tflite')
```

### 2. DQN (Deep Q-Network)
```python
import tensorflow as tf
import numpy as np

# Definir arquitectura Dueling DQN
class DuelingDQN(tf.keras.Model):
    def __init__(self, state_size, action_size):
        super().__init__()
        self.fc1 = tf.keras.layers.Dense(256, activation='relu')
        self.fc2 = tf.keras.layers.Dense(128, activation='relu')
        # Value stream
        self.value_fc = tf.keras.layers.Dense(64, activation='relu')
        self.value_out = tf.keras.layers.Dense(1)
        # Advantage stream
        self.adv_fc = tf.keras.layers.Dense(64, activation='relu')
        self.adv_out = tf.keras.layers.Dense(action_size)
    
    def call(self, x):
        x = self.fc1(x)
        x = self.fc2(x)
        value = self.value_out(self.value_fc(x))
        advantage = self.adv_out(self.adv_fc(x))
        return value + (advantage - tf.reduce_mean(advantage, axis=1, keepdims=True))

# Crear y convertir
model = DuelingDQN(256, 15)  # state_size=256, action_size=15
model.build(input_shape=(1, 256))

converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
tflite_model = converter.convert()

with open('dqn_dueling.tflite', 'wb') as f:
    f.write(tflite_model)

files.download('dqn_dueling.tflite')
```

### 3. PPO (Actor-Critic)
```python
# Actor (policy network)
actor = tf.keras.Sequential([
    tf.keras.layers.Dense(256, activation='relu', input_shape=(256,)),
    tf.keras.layers.Dense(128, activation='relu'),
    tf.keras.layers.Dense(15, activation='softmax')  # 15 acciones
])

# Critic (value network)
critic = tf.keras.Sequential([
    tf.keras.layers.Dense(256, activation='relu', input_shape=(256,)),
    tf.keras.layers.Dense(128, activation='relu'),
    tf.keras.layers.Dense(1)  # Valor estado
])

# Convertir
for name, model in [('ppo_actor', actor), ('ppo_critic', critic)]:
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    with open(f'{name}.tflite', 'wb') as f:
        f.write(tflite_model)
    files.download(f'{name}.tflite')
```

### 4. SAC (Soft Actor-Critic)
```python
# Actor (policy)
actor = tf.keras.Sequential([
    tf.keras.layers.Dense(256, activation='relu', input_shape=(256,)),
    tf.keras.layers.Dense(128, activation='relu'),
    tf.keras.layers.Dense(15, activation='softmax')
])

# Q-Networks (twin)
for i in [1, 2]:
    qnet = tf.keras.Sequential([
        tf.keras.layers.Dense(256, activation='relu', input_shape=(257,)),  # state + action
        tf.keras.layers.Dense(128, activation='relu'),
        tf.keras.layers.Dense(1)
    ])
    converter = tf.lite.TFLiteConverter.from_keras_model(qnet)
    tflite_model = converter.convert()
    with open(f'sac_q{i}.tflite', 'wb') as f:
        f.write(tflite_model)
    files.download(f'sac_q{i}.tflite')
```

## Integración en Android Studio

1. Copiar modelos descargados:
```bash
# Desde terminal
cp ~/Downloads/*.tflite /ruta/al/proyecto/app/src/main/assets/
```

2. Verificar en `build.gradle`:
```gradle
android {
    // ...
    aaptOptions {
        noCompress 'tflite'
    }
}
```

3. El código cargará automáticamente desde assets:
```kotlin
// YOLODetector.kt
val fileDescriptor = context.assets.openFd(MODEL_NAME)
// Carga el modelo...
```

## Notas Importantes

- **Tamaño APK**: ~150MB con todos los modelos (sin Play Store, no hay límite estricto)
- **Optimización**: Usar FP16 (half precision) para reducir tamaño 50%
- **Placeholders**: Si un modelo falta, la app creará un placeholder y el componente no funcionará
- **Carga perezosa**: Cada componente carga su modelo en `initialize()`

## Troubleshooting

**Error: "Model not found"**
- Verificar nombre exacto del archivo (sensible a mayúsculas)
- Confirmar archivo está en `app/src/main/assets/`
- Recompilar: `Build > Clean Project > Rebuild Project`

**Error: "Cannot open file"**
- Verificar `aaptOptions { noCompress 'tflite' }` en build.gradle
- El archivo puede estar comprimido por Android si no se especifica

**Modelo corrupto**
- Verificar MD5/SHA256 si es posible
- Re-descargar desde Colab
- Confirmar conversión TFLite fue exitosa
