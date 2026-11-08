#!/usr/bin/env bash
# Build the app in Docker (image openhu-companion:dev, docker/Dockerfile).
#   docker/dev.sh build     debug APK (app/build/outputs/apk/debug/), unit tests, lint
#   docker/dev.sh bundle    release bundle for Play (app/build/outputs/bundle/release/), signed with the upload key
#                           from keystore.properties when it exists
#   docker/dev.sh install   build and install the debug APK on the phone (adb on the host)
#   docker/dev.sh gradle …  any Gradle task
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE=openhu-companion:dev
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -t "$IMAGE" "$ROOT/docker"
gradle() {
  # the Gradle and Android user homes stay in the checkout (.gradle-home, ignored): caches and the debug key survive
  docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e GRADLE_USER_HOME=/src/.gradle-home \
    -e ANDROID_USER_HOME=/src/.gradle-home/android -v "$ROOT:/src" -w /src "$IMAGE" ./gradlew --no-daemon -q "$@"
}
case "${1:-build}" in
  build) gradle assembleDebug testDebugUnitTest lintDebug ;;
  bundle) gradle bundleRelease ;;
  install) gradle assembleDebug && adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" ;;
  gradle) shift; gradle "$@" ;;
  *) echo "usage: $0 build|bundle|install|gradle <task>…" >&2; exit 2 ;;
esac
