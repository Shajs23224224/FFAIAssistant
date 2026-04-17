# Testing de FFAIAssistant en Docker

## Resumen de opciones

Tu entorno **soporta Docker con KVM**, lo cual permite varias opciones de testing:

| Método | Tiempo | Recursos | Uso recomendado |
|--------|--------|----------|-----------------|
| **Build simple** | ~5 min | 500MB RAM | Verificar compilación |
| **Build + Lint** | ~8 min | 1GB RAM | Validar código |
| **Emulador Android** | ~15 min | 4GB+ RAM | Testing funcional completo |

---

## Opción 1: Build Rápido (Sin Emulador)

Verifica que el código compila correctamente después de los cambios:

```bash
cd /root/Imágenes/Hacksito/FFAIAssistant

# Build rápido usando script incluido
./scripts/docker-build.sh

# O manualmente con imagen Eclipse Temurin:
docker run --rm \
  -v "$(pwd)":/project \
  -w /project \
  -e GRADLE_OPTS="-Xmx2g -XX:MaxMetaspaceSize=512m" \
  eclipse-temurin:17-jdk \
  bash -c "
    apt-get update -qq && apt-get install -y -qq git wget unzip > /dev/null 2>&1 && \
    export ANDROID_HOME=/tmp/android-sdk && \
    mkdir -p \$ANDROID_HOME && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/sdk.zip && \
    unzip -q /tmp/sdk.zip -d \$ANDROID_HOME/cmdline-tools && \
    mv \$ANDROID_HOME/cmdline-tools/cmdline-tools \$ANDROID_HOME/cmdline-tools/latest && \
    yes | \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1 && \
    \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'platforms;android-34' 'build-tools;34.0.0' > /dev/null 2>&1 && \
    chmod +x gradlew && \
    ./gradlew assembleRelease --no-daemon -x test 2>&1 | tail -30
  "
```

---

## Opción 2: Build + Análisis Estático

```bash
docker run --rm \
  -v "$(pwd)":/project \
  -w /project \
  mingc/android-build-box:master \
  bash -c "
    ./gradlew lint assembleRelease --no-daemon 2>&1 | tail -50
  "
```

**Nota:** Esta imagen es grande (~2GB) pero tiene todo pre-instalado.

---

## Opción 3: Emulador Android Completo (Testing Funcional)

### Requisitos
- KVM habilitado (`/dev/kvm` accesible) ✅
- 4GB+ RAM libre
- 10GB+ espacio en disco

### Iniciar emulador con tu APK

```bash
# 1. Primero, compilar el APK
./gradlew assembleRelease

# 2. Iniciar emulador en Docker
docker run -d \
  --name android-emulator \
  --privileged \
  -p 6080:6080 \
  -p 5554:5554 \
  -p 5555:5555 \
  -v /dev/kvm:/dev/kvm \
  -v "$(pwd)/app/build/outputs/apk/release":/app \
  -e DEVICE="Samsung Galaxy S10" \
  -e WEB_VNC=true \
  budtmo/docker-android:emulator_11.0

# 3. Esperar a que arranque (60-90 segundos)
sleep 90

# 4. Instalar APK
docker exec android-emulator adb install /app/app-release.apk

# 5. Ver emulador en navegador
# Abrir: http://localhost:6080
```

### Acceder al emulador

- **Web VNC**: http://localhost:6080
- **ADB**: `adb connect localhost:5555`

---

## Opción 4: Docker Compose (Todo Automatizado)

```bash
# Requiere docker-compose instalado
cd /root/Imágenes/Hacksito/FFAIAssistant

# Verificar que KVM esté disponible
ls -la /dev/kvm

# Iniciar stack completo
docker-compose -f docker-android-test.yml up

# Acceder a VNC
# URL: http://localhost:6080
```

---

## Troubleshooting

### Error: `/dev/kvm not found`
```bash
# Solución: usar emulador sin aceleración (muy lento)
docker run ... -e EMULATOR_ARGS="-no-accel -gpu swiftshader_indirect"
```

### Error: `Out of memory`
```bash
# Reducir memoria de Gradle
export GRADLE_OPTS="-Xmx1g -XX:MaxMetaspaceSize=256m"
```

### Error: `Permission denied on /dev/kvm`
```bash
# Agregar usuario al grupo KVM
sudo usermod -aG kvm $USER
# Relogear o: newgrp kvm
```

---

## Verificación Rápida

Script incluido para verificar el entorno:

```bash
./scripts/docker-check.sh
```

Salida esperada:
```
✅ Docker instalado: Docker version XX.XX.X
✅ KVM disponible
✅ RAM: XGB
✅ Espacio en disco: XXGB libres
```

---

## Resumen

Para **verificar tus correcciones rápidamente**:

```bash
# 1. Verificar entorno
./scripts/docker-check.sh

# 2. Build simple
./scripts/docker-build.sh

# 3. APK resultante en:
ls -la app/build/outputs/apk/release/
```
