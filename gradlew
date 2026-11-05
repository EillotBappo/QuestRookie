#!/bin/sh

# This is a dummy wrapper since we need to check if gradle is available
GRADLE_HOME=""
JAVA_HOME=""

if [ -f "./gradle/wrapper/gradle-wrapper.jar" ]; then
    GRADLE_WRAPPER_JAR="./gradle/wrapper/gradle-wrapper.jar"
else
    GRADLE_WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
fi

# Try to find java and gradle
if command -v java &> /dev/null; then
    JAVA_CMD=$(command -v java)
else
    echo "Error: Java not found in PATH"
    exit 1
fi

if [ ! -f "$GRADLE_WRAPPER_JAR" ]; then
    # Try from current dir first, then try to find it
    if [ ! -f "./gradle/wrapper/gradle-wrapper.jar" ] && [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
        echo "Error: Gradle wrapper JAR not found at $GRADLE_WRAPPER_JAR"
        exit 1
    fi
fi

exec "$JAVA_CMD" -Dorg.gradle.appname=gradlew -classpath "$GRADLE_WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
