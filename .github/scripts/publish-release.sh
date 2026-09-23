#!/usr/bin/env bash
# Publish an existing GitHub release of Jarvis to Modrinth and CurseForge
# from your own machine, with the tokens in your shell and nowhere else.
#
#   export MODRINTH_TOKEN=...   MODRINTH_ID=...
#   export CURSEFORGE_TOKEN=... CURSEFORGE_PLUGIN_ID=...  CURSEFORGE_MOD_ID=...
#   .github/scripts/publish-release.sh 0.17.0
#
# Any platform whose variables are unset is skipped. Needs curl and
# python3. The jars are fetched from the GitHub release for that version,
# so run it after the release exists; the notes are the CHANGELOG section.
set -euo pipefail

VERSION="${1:?usage: publish-release.sh <version>   (e.g. 0.17.0)}"
REPO="iamgadgetman/jarvis"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Fetching the v$VERSION jars from github.com/$REPO"
for name in jarvis-paper jarvis-fabric jarvis-neoforge; do
  curl -fsSL -o "$WORK/$name-$VERSION.jar" \
    "https://github.com/$REPO/releases/download/v$VERSION/$name-$VERSION.jar"
done

# Player-facing notes in docs/release-notes/ win over the CHANGELOG section,
# which is written for whoever reads the code.
if [ -s "$ROOT/docs/release-notes/$VERSION.md" ]; then
  cp "$ROOT/docs/release-notes/$VERSION.md" "$WORK/notes.md"
else
  awk -v v="$VERSION" '
    /^## v/ { if (found) exit; if (index($0, "## v" v " ") == 1 || $0 == "## v" v) { found = 1; next } }
    found { print }
  ' "$ROOT/CHANGELOG.md" > "$WORK/notes.md"
fi
[ -s "$WORK/notes.md" ] || echo "Jarvis $VERSION" > "$WORK/notes.md"

PUB="python3 $HERE/publish.py"

if [ -n "${MODRINTH_TOKEN:-}" ] && [ -n "${MODRINTH_ID:-}" ]; then
  $PUB modrinth --project "$MODRINTH_ID" --file "$WORK/jarvis-paper-$VERSION.jar" \
    --version "$VERSION+paper" --name "Jarvis $VERSION for Paper" --loaders paper purpur \
    --game-versions 1.21.11..26.2 --deps simple-voice-chat:optional --changelog "$WORK/notes.md"
  $PUB modrinth --project "$MODRINTH_ID" --file "$WORK/jarvis-fabric-$VERSION.jar" \
    --version "$VERSION+fabric" --name "Jarvis $VERSION for Fabric" --loaders fabric \
    --game-versions 26.3 --deps fabric-api:required simple-voice-chat:optional --changelog "$WORK/notes.md"
  $PUB modrinth --project "$MODRINTH_ID" --file "$WORK/jarvis-neoforge-$VERSION.jar" \
    --version "$VERSION+neoforge" --name "Jarvis $VERSION for NeoForge" --loaders neoforge \
    --game-versions 26.3 --deps simple-voice-chat:optional --changelog "$WORK/notes.md"
else
  echo "Modrinth: skipped (MODRINTH_TOKEN and MODRINTH_ID not both set)"
fi

if [ -n "${CURSEFORGE_TOKEN:-}" ] && [ -n "${CURSEFORGE_PLUGIN_ID:-}" ]; then
  $PUB curseforge --host dev.bukkit.org --project "$CURSEFORGE_PLUGIN_ID" \
    --file "$WORK/jarvis-paper-$VERSION.jar" --name "Jarvis $VERSION for Paper" \
    --game-versions 1.21.11..26.2 \
    --deps citizens:requiredDependency simple-voice-chat:optionalDependency --changelog "$WORK/notes.md"
else
  echo "CurseForge plugin: skipped (CURSEFORGE_TOKEN and CURSEFORGE_PLUGIN_ID not both set)"
fi

if [ -n "${CURSEFORGE_TOKEN:-}" ] && [ -n "${CURSEFORGE_MOD_ID:-}" ]; then
  $PUB curseforge --host minecraft.curseforge.com --project "$CURSEFORGE_MOD_ID" \
    --file "$WORK/jarvis-fabric-$VERSION.jar" --name "Jarvis $VERSION for Fabric" \
    --game-versions 26.3 --extra Fabric "Java 25" Server \
    --deps fabric-api:requiredDependency simple-voice-chat:optionalDependency --changelog "$WORK/notes.md"
  $PUB curseforge --host minecraft.curseforge.com --project "$CURSEFORGE_MOD_ID" \
    --file "$WORK/jarvis-neoforge-$VERSION.jar" --name "Jarvis $VERSION for NeoForge" \
    --game-versions 26.3 --extra NeoForge "Java 25" Server \
    --deps simple-voice-chat:optionalDependency --changelog "$WORK/notes.md"
else
  echo "CurseForge mods: skipped (CURSEFORGE_TOKEN and CURSEFORGE_MOD_ID not both set)"
fi
