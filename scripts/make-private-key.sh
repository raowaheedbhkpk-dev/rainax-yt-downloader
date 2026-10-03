#!/usr/bin/env bash
# Creates your own private signing key and stores it in GitHub Secrets.
# Termux:  pkg install openjdk-17 gh -y   (then: gh auth login)
# Run from the repository folder:  bash scripts/make-private-key.sh
set -euo pipefail

command -v keytool >/dev/null || { echo "keytool not found (install openjdk-17)"; exit 1; }
command -v gh >/dev/null || { echo "gh not found (install GitHub CLI and run: gh auth login)"; exit 1; }

PASS="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
OUT="$HOME/rainax-private.keystore"

keytool -genkeypair -keystore "$OUT" -storetype PKCS12 -alias rainax -keyalg RSA -keysize 4096 \
  -validity 36500 -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=RAINAX YT DOWNLOADER, OU=RAINAX, O=RAINAX, C=PK"

base64 -w0 "$OUT" | gh secret set RAINAX_KEYSTORE_BASE64
printf '%s' "$PASS" | gh secret set RAINAX_STORE_PASSWORD
printf '%s' "$PASS" | gh secret set RAINAX_KEY_PASSWORD
printf '%s' "rainax" | gh secret set RAINAX_KEY_ALIAS

echo
echo "Done. Private key saved at: $OUT"
echo "Store password: $PASS"
echo "BACK UP BOTH. If they are lost, new versions cannot update the installed app."
echo "Your next tagged build is signed with this key."
