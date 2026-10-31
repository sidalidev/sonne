#!/usr/bin/env bash
# Repository checks, run on every push (.github/workflows/ci.yml) and before a release.
#
#   1. No long dash or en dash, no forbidden words, no personal home path in tracked files.
#   2. The latest internal security review (docs/security/YYYY-MM-DD.md) is less than 6 months old.
#   3. CHANGELOG.md has an entry for the app's versionName.
set -euo pipefail

RACINE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$RACINE"
ECHEC=0
echec() { echo "FAIL: $*" >&2; ECHEC=1; }

# 1. Text hygiene. Patterns are assembled so that this file does not match itself.
TIRETS=("$(printf '\342\200\224')" "$(printf '\342\200\223')")
MOTS=("Clau""de" "generated"" by AI" "Co-Authored"-"By")
CHEMIN="/Use""rs/"
if git grep -nIF -e "${TIRETS[0]}" -e "${TIRETS[1]}" > /dev/null; then
  echec "long dash or en dash found:"; git grep -nIF -e "${TIRETS[0]}" -e "${TIRETS[1]}" | head -20 >&2
fi
if git grep -nIiF -e "${MOTS[0]}" -e "${MOTS[1]}" -e "${MOTS[2]}" > /dev/null; then
  echec "forbidden word found:"; git grep -nIiF -e "${MOTS[0]}" -e "${MOTS[1]}" -e "${MOTS[2]}" | head -20 >&2
fi
if git grep -nIF -e "$CHEMIN" > /dev/null; then
  echec "personal path found:"; git grep -nIF -e "$CHEMIN" | head -20 >&2
fi

# 2. Security review age.
DERNIERE="$(ls docs/security 2> /dev/null | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}\.md$' | sort | tail -1 | sed 's/\.md$//')"
if [ -z "$DERNIERE" ]; then
  echec "no dated review in docs/security/"
else
  AGE="$(python3 -c "import datetime,sys; print((datetime.date.today() - datetime.date.fromisoformat(sys.argv[1])).days)" "$DERNIERE")"
  if [ "$AGE" -gt 183 ]; then echec "last security review is $AGE days old ($DERNIERE), more than 6 months"; fi
  echo "Security review: $DERNIERE ($AGE days ago)"
  # The web About page shows the same date as the app.
  LISIBLE="$(python3 -c "import datetime,sys; d=datetime.date.fromisoformat(sys.argv[1]); print(f'{d.day} {d.strftime(\"%b\")} {d.year}')" "$DERNIERE")"
  grep -qF "Last security review: $LISIBLE" web/about.html || echec "web/about.html does not show the last security review ($LISIBLE)"
fi

# 3. Changelog entry for the current version.
VERSION="$(sed -nE 's/.*versionName = .*\?: "([^"]+)".*/\1/p' android/app/build.gradle.kts | head -1)"
if [ -z "$VERSION" ]; then
  echec "versionName not found in android/app/build.gradle.kts"
elif ! grep -qE "^## ${VERSION//./\\.}( |$)" CHANGELOG.md; then
  echec "CHANGELOG.md has no entry for $VERSION"
else
  echo "Changelog: entry for $VERSION"
fi

[ "$ECHEC" = 0 ] && echo "Repository checks passed."
exit "$ECHEC"
