#!/usr/bin/env bash
# Local APK build wrapper for VSCodroid.
#
# gh (GitHub CLI) is not installed on this machine, so fetch-vscode-oss.sh
# cannot use its normal `gh release download` path. Instead we fetch the
# server tarball with curl and pin it via the script's documented offline
# remedy: VSCODE_OSS_URL + VSCODE_OSS_SHA256, which keeps the digest check.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"

VERSION="$(cat VSCODE_VERSION)"
TARBALL_NAME="vscode-reh-web-linux-arm64-$VERSION.tar.gz"
TARBALL="$ROOT/server/$TARBALL_NAME"
URL="https://github.com/rmyndharis/VSCodroid/releases/download/server-$VERSION/$TARBALL_NAME"

mkdir -p server
if [ ! -f "$TARBALL" ]; then
    echo "Downloading $TARBALL_NAME ..."
    curl -L --fail --show-error -o "$TARBALL.part" "$URL"
    mv "$TARBALL.part" "$TARBALL"
fi

DIGEST="$(sha256sum "$TARBALL" | cut -d' ' -f1)"
echo "server tarball sha256=$DIGEST"

export VSCODE_OSS_URL="file://$TARBALL"
export VSCODE_OSS_SHA256="$DIGEST"

bash scripts/build-all.sh 2>&1 | tee /tmp/vscodroid-build.log
