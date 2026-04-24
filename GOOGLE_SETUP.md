# Configuración de Google Sign-In y Drive API

Este documento explica cómo configurar la autenticación de Google para el backup en Drive.

## Prerrequisitos

1. Cuenta de Google
2. Acceso a [Google Cloud Console](https://console.cloud.google.com/)
3. Android Studio instalado (para generar SHA-1/SHA-256 del certificado)

---

## Paso 1: Crear Proyecto en Google Cloud Console

1. Ve a [Google Cloud Console](https://console.cloud.google.com/)
2. Clic en "Seleccionar un proyecto" → "Proyecto nuevo"
3. Nombre: `FFAI-Assistant` (o el que prefieras)
4. Clic en "Crear"

---

## Paso 2: Habilitar APIs

1. En el menú lateral, ve a **"APIs y servicios"** → **"Biblioteca"**
2. Busca y habilita:
   - **Google Drive API** (para almacenamiento en la nube)
   - **Google People API** (para obtener foto de perfil del usuario)

> **Nota:** El "Google Sign-In" no es una API separada. Se configura automáticamente cuando creas las credenciales OAuth en el Paso 4.

---

## Paso 3: Configurar Pantalla de Consentimiento OAuth

1. Ve a "APIs y servicios" → "Pantalla de consentimiento de OAuth"
2. Selecciona tipo: **Externo** (para distribución pública)
3. Completa la información:
   - Nombre de la app: `FFAI Assistant`
   - Email de soporte: tu email
   - Logo (opcional)
   - Dominios: `ffai-assistant.com` (puedes dejar vacío para desarrollo)
4. En "Ámbitos" (Scopes), agrega estos:
   
   Para **Google Sign-In** (obligatorios):
   - `openid` (identificación del usuario)
   - `email` (acceso al email del usuario)
   - `profile` (nombre y foto del usuario)
   
   Para **Google Drive**:
   - `.../auth/drive.file` (acceso a archivos creados por la app - recomendado)
   - `.../auth/drive` (acceso completo - solo si es necesario)
   
   Para **People API** (opcional, para foto de perfil):
   - `.../auth/userinfo.profile`

5. Agrega usuarios de prueba (tu email de Google)
6. Clic en "Guardar y continuar"

---

## Paso 4: Crear Credenciales OAuth 2.0

### Para Android (APK):

1. Ve a "APIs y servicios" → "Credenciales"
2. Clic en "Crear credenciales" → "ID de cliente de OAuth"
3. Selecciona "Android"
4. Completa:
   - Nombre: `FFAI Android Client`
   - Huella digital del certificado SHA-1:
     
     **Obtener SHA-1/SHA-256:**
     ```bash
     # Para keystore de debug
     keytool -list -v -alias androiddebugkey -keystore ~/.android/debug.keystore
     
     # Password por defecto: android
     
     # Para keystore de release (tu archivo .jks)
     keytool -list -v -keystore tu-keystore.jks
     ```
   - Nombre de paquete: `com.ffai.assistant`
5. Clic en "Crear"

### Para Web/Backend (opcional, para Drive REST API):

1. "Crear credenciales" → "ID de cliente de OAuth"
2. Selecciona "Aplicación web"
3. Nombre: `FFAI Web Client`
4. Orígenes autorizados de JavaScript: `http://localhost` (para pruebas)
5. Guarda el "Client ID" generado

---

## Paso 5: Descargar google-services.json

1. Ve a [Firebase Console](https://console.firebase.google.com/) (usa la misma cuenta de Google Cloud)
2. Clic en "Agregar proyecto" → selecciona tu proyecto de Google Cloud
3. Continuar con la configuración
4. Agrega una app Android:
   - Nombre del paquete: `com.ffai.assistant`
   - Nickname: `FFAI`
   - SHA-1 (el mismo del paso anterior)
5. Descarga el archivo `google-services.json`
6. **Coloca el archivo en**: `app/google-services.json` (sobrescribe la plantilla)
7. Clic en "Siguiente" y completa la verificación

---

## Paso 6: Configurar Build Gradle

El archivo `build.gradle.kts` ya tiene las dependencias. Solo asegúrate de que el plugin de Google Services esté aplicado:

Agrega en `build.gradle.kts` (nivel proyecto root):
```kotlin
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("com.google.gms.google-services") version "4.4.0" apply false
}
```

Y en `app/build.gradle.kts` ya está:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services") // AGREGA ESTA LÍNEA
}
```

---

## Paso 7: Probar Autenticación

1. Sincroniza proyecto en Android Studio: `Tools` → `AGP Upgrade Assistant` (si aplica)
2. Build → Clean Project
3. Build → Rebuild Project
4. Ejecuta en dispositivo/emulador
5. En la app, ve a configuración y presiona "Iniciar sesión con Google"
6. Selecciona tu cuenta
7. Deberías ver tu foto de perfil y email en la UI

---

## Solución de Problemas

### "Error 10: Developer error"
- Verifica que el SHA-1 sea correcto
- Asegúrate que el package name coincida exactamente: `com.ffai.assistant`
- Revisa que `google-services.json` esté en la carpeta correcta

### "Access Denied" en Drive
- La app solo puede acceder a archivos que ella misma creó (scope drive.file)
- Para archivos existentes, usa el scope completo `drive` (requiere verificación de Google)

### "API Key invalid"
- Regenera el archivo `google-services.json` desde Firebase Console
- Limpia y reconstruye el proyecto

### "No encuentro Google Sign-In API en la Biblioteca"
**Normal** - El Sign-In no requiere una API separada. Solo necesitas:
1. Habilitar **People API** (para datos de perfil)
2. Configurar correctamente la **Pantalla de Consentimiento OAuth** (Paso 3)
3. Crear credenciales **OAuth 2.0 para Android** (Paso 4)

El Sign-In funciona a través de los **scopes OAuth** (`openid`, `email`, `profile`), no de una API REST.

---

## Estructura en Google Drive

La app creará automáticamente esta estructura en el Drive del usuario:

```
/MyDrive/FFAI/
├── models/
│   ├── model_current.tflite
│   ├── model_backup_YYYYMMDD.tflite
│   └── manifest.json
├── checkpoints/
│   ├── checkpoint_latest.pth
│   └── checkpoint_EPOCH.pth
├── data/
│   ├── experiences_backup.db
│   └── episodes.json
└── sessions/
    └── session_USERID.json
```

---

## Notas de Seguridad

1. **Nunca subas `google-services.json` a repositorios públicos**
2. Agrega al `.gitignore`:
   ```
   app/google-services.json
   *.jks
   keystore.properties
   ```
3. Usa variables de entorno en CI/CD para los secretos

---

## Contacto

Para soporte con la configuración de Google Cloud, consulta:
- [Documentación Google Sign-In](https://developers.google.com/identity/sign-in/android/start)
- [Documentación Drive API](https://developers.google.com/drive/api/v3/about-sdk)
