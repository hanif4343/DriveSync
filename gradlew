#!/usr/bin/env sh
##############################################################################
## Gradle start up script for UN*X
##############################################################################
APP_HOME=`pwd -P`
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
DEFAULT_JVM_OPTS="-Xmx2048m -Xms512m"
MAX_FD="maximum"
warn () { echo "$*"; } >&2
die () { echo; echo "$*"; echo; exit 1; } >&2
cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in CYGWIN*) cygwin=true;; Darwin*) darwin=true;; MINGW*) msys=true;; NONSTOP*) nonstop=true;; esac
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ -n "$JAVA_HOME" ]; then
  if [ -x "$JAVA_HOME/jre/sh/java" ]; then JAVACMD="$JAVA_HOME/jre/sh/java"
  else JAVACMD="$JAVA_HOME/bin/java"
  fi
else JAVACMD="java"; fi
if ! command -v "$JAVACMD" > /dev/null 2>&1; then die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."; fi
GRADLE_OPTS="${GRADLE_OPTS:-"-Dhttps.protocols=TLSv1.2,TLSv1.3"}"
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
