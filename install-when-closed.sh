#!/usr/bin/env bash
# Dev helper: waits until Minecraft (javaw.exe) is closed, then copies the freshly built mod jar
# into the game's mods folder, replacing any older Wynn Labs (or pre-rename wynn-inventory-api)
# jar. Run it in the background after a build. Does nothing if the installed jar is already the
# latest build.
#
# The mods folder comes from $WYNNLABS_MODS, or from the first line of .local-mods-path next to
# this script (git-ignored), e.g. /c/Users/me/curseforge/minecraft/Instances/Wynncraft/mods
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
MODS="${WYNNLABS_MODS:-}"
if [ -z "$MODS" ] && [ -f "$HERE/.local-mods-path" ]; then
  MODS="$(head -n 1 "$HERE/.local-mods-path" | tr -d '\r')"
fi
if [ -z "$MODS" ] || [ ! -d "$MODS" ]; then
  echo "set WYNNLABS_MODS or write the mods folder into .local-mods-path" >&2
  exit 1
fi
SRC="$(ls -t "$HERE"/build/libs/wynnlabs-*.jar 2>/dev/null | grep -v -- '-sources' | head -n 1)"
if [ -z "$SRC" ]; then
  echo "no build/libs/wynnlabs-*.jar: build first" >&2
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
