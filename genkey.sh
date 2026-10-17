#!/usr/bin/env bash
# Generates the signing key (debug.keystore, password "android") that sign.sh uses. Needs keytool,
# which `nix develop` provides. Android only installs an update over an existing app when both APKs
# are signed with the same key, so keep this file; a new key means uninstalling the app first.
set -euo pipefail
cd "$(dirname "$0")"

KEYSTORE=${KEYSTORE:-debug.keystore}
if [ -e "$KEYSTORE" ]; then
  echo "$KEYSTORE already exists; delete it first if you really want a new key" >&2
  exit 1
fi
keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
  -alias p3view -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=p3view"
echo "created $KEYSTORE"
