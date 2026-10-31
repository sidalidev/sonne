#!/usr/bin/env bash
# Publishes an Android version of Sonne (built-in update, see docs/PROTOCOLE.md, "Mise à jour").
#
#   tools/release-android.sh --notes "Short text, in English"   # builds, publishes to the server and GitHub
#   tools/release-android.sh --sans-publication                 # builds and prepares only
#
# 1. Repository checks, JVM tests, signed release APK (keystore in ~/.sonne, see android/README.md),
#    no permission outside docs/permissions.txt.
# 2. SHA-256 of the APK and SHA-256 fingerprint of the signing certificate (apksigner).
# 3. android.json: { version, versionCode, url, sha256, certSha256, notes } (notes = --notes, shown by the app).
# 4. scp of the APK then of android.json to $SONNE_VPS:$SONNE_RELEASES_DIR/
#    (the relay serves them read only: /update/android.json and /download/<name>.apk).
# 5. GitHub release v<version> with the APK, its checksum and the CHANGELOG.md section as notes.
#
# Environment, required to publish:
#   SONNE_VPS            SSH target of the server that hosts the relay, for example user@example.org
#   SONNE_RELEASES_DIR   folder on that server mounted on /releases in the relay (SONNE_RELEASES_HOST_DIR
#                        in deploy/docker-compose.yml)
# Optional:
#   SONNE_BASE_URL       public address of the relay (default https://sonne.sidali.dev)
#   SONNE_GITHUB_REPO    GitHub repository (default sidalidev/sonne)
#   SONNE_NOTES          same as --notes
#   SONNE_JAVA_HOME      JDK 21 (default /opt/homebrew/opt/openjdk@21)
#
# Phones with the built-in updater download from the relay; Obtainium downloads from the GitHub release.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")/.." && pwd)"
# JDK 21 (newer JDKs break the Kotlin Gradle plugin); SONNE_JAVA_HOME to point elsewhere.
export JAVA_HOME="${SONNE_JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BASE_URL="${SONNE_BASE_URL:-https://sonne.sidali.dev}"
DEPOT_GH="${SONNE_GITHUB_REPO:-sidalidev/sonne}"

PUBLIER=1
NOTES="${SONNE_NOTES:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --sans-publication) PUBLIER=0 ;;
    --notes) NOTES="${2:?--notes expects a text}"; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

echec() { echo "Error: $*" >&2; exit 1; }

BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
[ -x "$APKSIGNER" ] && [ -x "$AAPT2" ] || echec "apksigner or aapt2 not found in $BUILD_TOOLS"
[ -f "$HOME/.sonne/keystore.properties" ] || echec "~/.sonne/keystore.properties missing: the APK would not be signed"

if [ "$PUBLIER" = 1 ]; then
  command -v gh > /dev/null || echec "gh not found"
  VPS="${SONNE_VPS:-}"
  DOSSIER_VPS="${SONNE_RELEASES_DIR:-}"
  [ -n "$VPS" ] || echec "SONNE_VPS is not set (SSH target of the relay server, for example user@example.org)"
  [ -n "$DOSSIER_VPS" ] || echec "SONNE_RELEASES_DIR is not set (folder of published versions on that server)"
  [ -z "$(git -C "$RACINE" status --porcelain)" ] || echec "uncommitted changes: commit before publishing"
fi

echo "==> Repository checks (dashes, forbidden words, security review, CHANGELOG)"
"$RACINE/tools/check-repo.sh" || echec "repository checks failed"

echo "==> JVM tests and release APK"
(cd "$RACINE/android" && ./gradlew -q testDebugUnitTest assembleRelease)
APK_BRUT="$RACINE/android/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK_BRUT" ] || echec "release APK not found: $APK_BRUT"
"$RACINE/tools/check-permissions.sh" "$APK_BRUT" || echec "permission missing from docs/permissions.txt"

BADGING="$("$AAPT2" dump badging "$APK_BRUT" | sed -n '1p')"
PAQUET="$(sed -E "s/.*package: name='([^']+)'.*/\1/" <<< "$BADGING")"
VERSION_CODE="$(sed -E "s/.*versionCode='([0-9]+)'.*/\1/" <<< "$BADGING")"
VERSION="$(sed -E "s/.*versionName='([^']+)'.*/\1/" <<< "$BADGING")"
[ "$PAQUET" = "dev.sidali.sonne" ] || echec "unexpected package: $PAQUET"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]] || echec "unexpected versionName: $VERSION"

echo "==> Signature"
CERTS="$("$APKSIGNER" verify --print-certs "$APK_BRUT" 2> /dev/null)" || echec "invalid signature"
NB_SIGNATAIRES="$(grep -c '^Signer #[0-9]* certificate SHA-256 digest:' <<< "$CERTS" || true)"
[ "$NB_SIGNATAIRES" = 1 ] || echec "one signer expected, $NB_SIGNATAIRES found"
grep -q 'CN=Android Debug' <<< "$CERTS" && echec "APK signed with the debug key"
CERT_SHA256="$(sed -nE 's/^Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})$/\1/p' <<< "$CERTS")"
[ -n "$CERT_SHA256" ] || echec "certificate fingerprint not found"

NOM="sonne-$VERSION.apk"
SORTIE="$RACINE/android/app/build/release"
rm -rf "$SORTIE" && mkdir -p "$SORTIE"
cp "$APK_BRUT" "$SORTIE/$NOM"
SHA256="$(shasum -a 256 "$SORTIE/$NOM" | cut -d' ' -f1)"
(cd "$SORTIE" && shasum -a 256 "$NOM" > "$NOM.sha256")
[ -n "$NOTES" ] || NOTES="Sonne $VERSION"
# GitHub release notes: the "## <version>" section of CHANGELOG.md, without its heading.
awk -v v="$VERSION" '/^## /{dedans = (index($0, "## " v " ") == 1 || $0 == "## " v)} dedans' "$RACINE/CHANGELOG.md" \
  | tail -n +2 > "$SORTIE/notes.md"
grep -q '[^[:space:]]' "$SORTIE/notes.md" || echec "CHANGELOG.md has no section for $VERSION"

VERSION="$VERSION" VERSION_CODE="$VERSION_CODE" URL="$BASE_URL/download/$NOM" SHA256="$SHA256" \
  CERT_SHA256="$CERT_SHA256" NOTES="$NOTES" node -e '
    const e = process.env;
    const annonce = {
      version: e.VERSION,
      versionCode: Number(e.VERSION_CODE),
      url: e.URL,
      sha256: e.SHA256,
      certSha256: e.CERT_SHA256,
      notes: e.NOTES,
    };
    process.stdout.write(JSON.stringify(annonce, null, 2) + "\n");
  ' > "$SORTIE/android.json"

echo "    APK          : $SORTIE/$NOM"
echo "    version      : $VERSION ($VERSION_CODE)"
echo "    sha256       : $SHA256"
echo "    certificate  : $CERT_SHA256"
cat "$SORTIE/android.json"

if [ "$PUBLIER" = 0 ]; then
  echo "==> --sans-publication: nothing sent."
  exit 0
fi

echo "==> Server: $VPS:$DOSSIER_VPS"
# An APK already published under this name must never change (phones check its checksum).
DISTANT="$(ssh "$VPS" "mkdir -p '$DOSSIER_VPS' && if [ -f '$DOSSIER_VPS/$NOM' ]; then sha256sum '$DOSSIER_VPS/$NOM' | cut -d' ' -f1; fi")"
if [ -n "$DISTANT" ] && [ "$DISTANT" != "$SHA256" ]; then
  echec "$NOM already exists on the server with another checksum ($DISTANT): bump the version"
fi
# APK first, announcement second, put in place by a rename: it never points to a missing file.
scp -q "$SORTIE/$NOM" "$VPS:$DOSSIER_VPS/$NOM.tmp"
scp -q "$SORTIE/android.json" "$VPS:$DOSSIER_VPS/android.json.tmp"
ssh "$VPS" "cd '$DOSSIER_VPS' && chmod 644 '$NOM.tmp' android.json.tmp && mv -f '$NOM.tmp' '$NOM' && mv -f android.json.tmp android.json"

echo "==> Online check"
sleep 1
EN_LIGNE="$(curl -fsS -H 'Cache-Control: no-cache' "$BASE_URL/update/android.json")" || echec "announcement unreachable"
grep -q "\"versionCode\": *$VERSION_CODE" <<< "$EN_LIGNE" || grep -q "\"versionCode\":$VERSION_CODE" <<< "$EN_LIGNE" \
  || echec "the online announcement does not have versionCode $VERSION_CODE: $EN_LIGNE"
TELECHARGE="$(curl -fsS "$BASE_URL/download/$NOM" | shasum -a 256 | cut -d' ' -f1)"
[ "$TELECHARGE" = "$SHA256" ] || echec "the downloaded APK has the wrong checksum ($TELECHARGE)"
echo "    $BASE_URL/update/android.json and $BASE_URL/download/$NOM: OK"

echo "==> Release GitHub v$VERSION"
if gh release view "v$VERSION" -R "$DEPOT_GH" > /dev/null 2>&1; then
  gh release upload "v$VERSION" -R "$DEPOT_GH" --clobber "$SORTIE/$NOM" "$SORTIE/$NOM.sha256"
else
  gh release create "v$VERSION" -R "$DEPOT_GH" --target main --title "Sonne $VERSION" --notes-file "$SORTIE/notes.md" \
    "$SORTIE/$NOM" "$SORTIE/$NOM.sha256"
fi
echo "==> Published: Sonne $VERSION ($VERSION_CODE), sha256 $SHA256"
gh release view "v$VERSION" -R "$DEPOT_GH" --json url -q .url
