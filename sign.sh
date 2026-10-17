#!/usr/bin/env bash
# Signs an aligned APK: ./sign.sh build/p3view-unsigned.apk build/p3view.apk
#   BUILD_TOOLS  build-tools/X.Y.Z directory (apksigner); set by `nix develop`
#   KEYSTORE     signing keystore (default: debug.keystore in the current directory, password
#                "android"); create it with ./genkey.sh
set -euo pipefail

: "${BUILD_TOOLS:?}"
KEYSTORE=${KEYSTORE:-debug.keystore}
[ -f "$KEYSTORE" ] || { echo "$KEYSTORE not found; run ./genkey.sh in a nix develop shell first" >&2; exit 1; }
[ $# -eq 2 ] || { echo "usage: $0 unsigned.apk signed.apk" >&2; exit 1; }

"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android --out "$2" "$1"
"$BUILD_TOOLS/apksigner" verify "$2"
