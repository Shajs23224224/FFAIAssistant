#!/bin/sh
# Gradle wrapper para el proyecto

# Descargar gradle si no existe
GRADLE_VERSION=8.4
GRADLE_DIR="$HOME/.gradle/wrapper/dists/gradle-$GRADLE_VERSION-bin"
GRADLE_ZIP="$GRADLE_DIR/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -f "$GRADLE_ZIP" ]; then
    echo "Descargando Gradle $GRADLE_VERSION..."
    mkdir -p "$GRADLE_DIR"
    wget -q "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -O "$GRADLE_ZIP" 2>/dev/null || \
    curl -sL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$GRADLE_ZIP"
    
    cd "$GRADLE_DIR" && unzip -q gradle-$GRADLE_VERSION-bin.zip
fi

GRADLE_HOME="$GRADLE_DIR/gradle-$GRADLE_VERSION"
export GRADLE_HOME

# Ejecutar gradle
exec "$GRADLE_HOME/bin/gradle" "$@"
