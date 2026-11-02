#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
##############################################################################
# Gradle start up script for UN*X
##############################################################################
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DEFAULT_JVM_OPTS='-Xmx64m -Xms64m'
MAX_FD="maximum"
warn () { echo "$*"; }
die () { echo; echo "$*"; echo; exit 1; }
cygwin=false; msys=false; darwin=false; nonstop=false
case "$(uname)" in
  CYGWIN* ) cygwin=true ;;
  Darwin*  ) darwin=true ;;
  MSYS* | MINGW* ) msys=true ;;
  NONSTOP* ) nonstop=true ;;
esac
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if ! jar tf "$CLASSPATH" 2>/dev/null | grep -q "org/gradle/wrapper/GradleWrapperMain.class"; then
  echo "Gradle wrapper JAR is incomplete; using a local Gradle installation or bootstrapping Gradle 8.7."
  if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
  fi
  DIST_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/anivault-bootstrap/gradle-8.7"
  GRADLE_BIN="$DIST_ROOT/gradle-8.7/bin/gradle"
  if [ ! -x "$GRADLE_BIN" ]; then
    mkdir -p "$DIST_ROOT"
    ZIP_FILE="$DIST_ROOT/gradle-8.7-bin.zip"
    if command -v curl >/dev/null 2>&1; then
      curl -fL --retry 2 "https://services.gradle.org/distributions/gradle-8.7-bin.zip" -o "$ZIP_FILE" || exit 1
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP_FILE" "https://services.gradle.org/distributions/gradle-8.7-bin.zip" || exit 1
    else
      echo "ERROR: Install Gradle 8.7 or curl/wget to bootstrap it."
      exit 1
    fi
    command -v unzip >/dev/null 2>&1 || { echo "ERROR: unzip is required to bootstrap Gradle."; exit 1; }
    unzip -q -o "$ZIP_FILE" -d "$DIST_ROOT"
    rm -f "$ZIP_FILE"
  fi
  exec "$GRADLE_BIN" "$@"
fi
set_java_home () {
  if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/jre/sh/java" ]; then JAVACMD="$JAVA_HOME/jre/sh/java"
    else JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ]; then die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"; fi
  else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found."
  fi
}
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
set_java_home
exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=$APP_BASE_NAME" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
