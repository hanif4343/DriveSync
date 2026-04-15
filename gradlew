#!/usr/bin/env sh
APP_HOME=`pwd -P`
APP_BASE_NAME=`basename "$0"`
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi
exec "$JAVACMD" \
  -Xmx64m \
  -Xms64m \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
