# Configuración de Secrets para GitHub Actions (Release Builds)

Este documento explica cómo configurar los secrets necesarios para compilar APKs firmados en GitHub Actions.

## Secrets Requeridos

Para compilar release APKs firmados, necesitas configurar estos 4 secrets en tu repositorio de GitHub:

| Secret | Descripción | Cómo obtenerlo |
|--------|-------------|----------------|
| `ANDROID_KEYSTORE_BASE64` | Keystore codificado en Base64 | `base64 -w 0 tu-keystore.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del keystore | La que usaste al crear el keystore |
| `ANDROID_KEY_ALIAS` | Alias de la clave | El alias que usaste al crear la clave |
| `ANDROID_KEY_PASSWORD` | Contraseña de la clave | Generalmente igual a la del keystore |

## Pasos para Configurar

### 1. Crear el Keystore (si no lo tienes)

```bash
keytool -genkey -v -keystore release.keystore -alias ffai -keyalg RSA -keysize 2048 -validity 10000
```

### 2. Codificar el Keystore en Base64

```bash
base64 -w 0 release.keystore > release.keystore.base64
```

Copia el contenido del archivo `.base64`.

### 3. Configurar en GitHub

1. Ve a tu repositorio en GitHub
2. Navega a **Settings** → **Secrets and variables** → **Actions**
3. Haz clic en **New repository secret**
4. Agrega cada uno de los 4 secrets:
   - `ANDROID_KEYSTORE_BASE64` - Pega el contenido codificado
   - `ANDROID_KEYSTORE_PASSWORD` - Tu contraseña del keystore
   - `ANDROID_KEY_ALIAS` - Tu alias (ej: `ffai`)
   - `ANDROID_KEY_PASSWORD` - Tu contraseña de la clave

### 4. Verificar la Configuración

Una vez configurados, puedes:

1. Ir a **Actions** → **Build Signed Release APK**
2. Hacer clic en **Run workflow**
3. El workflow validará automáticamente que todos los secrets estén presentes

## Importante

- **NUNCA** subas el archivo `.keystore` o `.jks` al repositorio
- **NUNCA** pongas contraseñas en archivos de código
- Los secrets solo están disponibles para workflows en la rama default o tags

## Debug Builds

Los debug builds **NO** requieren secrets y se compilan automáticamente en cada push a `main` o PR.

Ver: [`.github/workflows/build.yml`](.github/workflows/build.yml)
