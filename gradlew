#!/bin/sh
APP_HOME=$( cd "${APP_HOME:-$(dirname "$0")}" > /dev/null && pwd -P ) || exit
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec java $DEFAULT_JVM_OPTS $JAVA_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
