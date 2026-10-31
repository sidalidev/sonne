#!/usr/bin/env bash
# Fails if a release APK asks for a permission that is not listed in docs/permissions.txt.
#
#   tools/check-permissions.sh android/app/build/outputs/apk/release/app-release.apk
#
# The list is the merged manifest as Android sees it (aapt2 dump permissions), libraries included.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:?usage: tools/check-permissions.sh <release.apk>}"
LISTE="$RACINE/docs/permissions.txt"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2> /dev/null | sort -V | tail -1)"
[ -x "$AAPT2" ] || { echo "aapt2 not found in $SDK/build-tools" >&2; exit 2; }
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }

DEMANDEES="$("$AAPT2" dump permissions "$APK" | sed -nE "s/^uses-permission(-sdk-23)?: name='([^']+)'.*/\2/p" | sort -u)"
ATTENDUES="$(grep -vE '^\s*(#|$)' "$LISTE" | sed 's/[[:space:]]*$//' | sort -u)"

EN_TROP="$(comm -23 <(echo "$DEMANDEES") <(echo "$ATTENDUES"))"
ABSENTES="$(comm -13 <(echo "$DEMANDEES") <(echo "$ATTENDUES"))"

if [ -n "$ABSENTES" ]; then
  echo "Listed in docs/permissions.txt but no longer requested (update the list):"
  echo "$ABSENTES" | sed 's/^/  /'
fi
if [ -n "$EN_TROP" ]; then
  echo "Permissions requested by the APK but missing from docs/permissions.txt:" >&2
  echo "$EN_TROP" | sed 's/^/  /' >&2
  exit 1
fi
echo "Permissions: $(echo "$DEMANDEES" | wc -l | tr -d ' ') requested, all expected."
