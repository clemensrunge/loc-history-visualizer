#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_CMD=${JAVA_HOME:+$JAVA_HOME/bin/}java

if ! command -v "$JAVA_CMD" >/dev/null 2>&1; then
    echo "Java was not found. Set JAVA_HOME or put java on PATH." >&2
    exit 1
fi

exec "$JAVA_CMD" -Xmx64m -Xms64m -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
