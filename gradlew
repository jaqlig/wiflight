#!/bin/sh
# Lightweight launcher for the official Gradle wrapper. The JAR is checksum-pinned by bootstrap.
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$APP_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    python3 "$APP_DIR/scripts/bootstrap-wrapper.py"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_COMMAND="$JAVA_HOME/bin/java"
else
    JAVA_COMMAND=java
fi
exec "$JAVA_COMMAND" -Dorg.gradle.appname=gradlew -classpath "$APP_DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
