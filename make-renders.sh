#!/usr/bin/env bash
# Render every view of the app and put the pictures where the model reads them.
#
#   ./make-renders.sh
#
# The renderer draws the real composables off-screen with sample data (no device,
# no emulator), so a picture comes from the same code the app runs and cannot
# quietly stop matching it. Each view is drawn twice: a phone and a window wide
# enough to show what the layout does with the room.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
gallery="$here/composeApp/build/gallery"
model="$here/docs/model/img"

# Named a view, only that one is drawn, which is the difference between waiting
# for one screen and waiting for nine.
if [ $# -gt 0 ]; then
  echo "Rendering $1…"
  "$here/gradlew" :composeApp:renderGallery -q --console=plain "-Ponly=$1"
else
  echo "Rendering every view…"
  "$here/gradlew" :composeApp:renderGallery -q --console=plain
fi

mkdir -p "$model/card"
count=0
for source in "$gallery"/*-phone.png "$gallery"/*-wide.png "$gallery"/*-card.png; do
  [ -e "$source" ] || continue
  name="$(basename "$source")"
  case "$name" in
    # The index wants the top of a screen at a readable size; the renderer draws
    # that itself, so nothing outside this toolchain is needed to make one.
    *-card.png) cp "$source" "$model/card/${name%-card.png}-phone.png" ;;
    *) cp "$source" "$model/$name"; count=$((count + 1)) ;;
  esac
done

echo "$count renders in docs/model/img, cards in docs/model/img/card"
