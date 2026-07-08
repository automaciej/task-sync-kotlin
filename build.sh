#!/usr/bin/env bash
# Wrapper around Gradle that sets up the Android Studio JDK.
set -euo pipefail

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

exec ./gradlew "$@"
