#!/usr/bin/env bash
# Dev helper: waits until Minecraft (javaw.exe) is closed, then copies the freshly built jar into
# the game's mods folder, replacing any older Wynn Labs (wynnlabs-<version>.jar from before the
# loader split, or pre-rename wynn-inventory-api) jar. Run it in the background after a build.
# Does nothing if the installed jar is already the latest build.
#
#   ./install-when-closed.sh            the Fabric jar (fabric/build/libs/wynnlabs-fabric-<v>.jar)
#   ./install-when-closed.sh neoforge   the NeoForge jar (neoforge/build/libs/wynnlabs-neoforge-<v>.jar)
#
# Fabric mods folder: $WYNNLABS_MODS, or the first line of .local-mods-path next to this script.
# NeoForge mods folder: $WYNNLABS_NEOFORGE_MODS, or the first line of .local-mods-path-neoforge.
# Both files are git-ignored, e.g. /c/Users/me/curseforge/minecraft/Instances/Wynncraft/mods
# The Fabric folder never gets the NeoForge jar, and the other way round.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
LOADER="${1:-fabric}"
case "$LOADER" in
  fabric)   MODS="${WYNNLABS_MODS:-}";          PATH_FILE=".local-mods-path";          VAR="WYNNLABS_MODS" ;;
  neoforge) MODS="${WYNNLABS_NEOFORGE_MODS:-}"; PATH_FILE=".local-mods-path-neoforge"; VAR="WYNNLABS_NEOFORGE_MODS" ;;
  *) echo "usage: $0 [neoforge]   (no argument installs the Fabric jar)" >&2; exit 2 ;;
esac
if [ -z "$MODS" ] && [ -f "$HERE/$PATH_FILE" ]; then
  MODS="$(head -n 1 "$HERE/$PATH_FILE" | tr -d '\r')"
fi
if [ -z "$MODS" ] || [ ! -d "$MODS" ]; then
  echo "set $VAR or write the $LOADER mods folder into $PATH_FILE" >&2
  exit 1
fi
SRC="$(ls -t "$HERE/$LOADER"/build/libs/wynnlabs-"$LOADER"-*.jar 2>/dev/null | grep -v -e '-sources' -e '-dev' | head -n 1)"
if [ -z "$SRC" ]; then
  echo "no $LOADER/build/libs/wynnlabs-$LOADER-*.jar: build first" >&2
  exit 1
fi
DST="$MODS/$(basename "$SRC")"

if [ -f "$DST" ] && cmp -s "$SRC" "$DST"; then
  echo "already installed"
  exit 0
fi
while tasklist //FI "IMAGENAME eq javaw.exe" | grep -qi javaw; do
  sleep 1
done
sleep 1   # let the game release its files
for old in "$MODS"/wynnlabs-*.jar "$MODS"/wynn-inventory-api-*.jar; do
  [ -f "$old" ] && [ "$old" != "$DST" ] && rm -f "$old"
done
cp "$SRC" "$DST" && echo "installed $(basename "$SRC") $(date '+%H:%M')"
