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
  # Emptied first, so what is in there afterwards is exactly what the gallery still defines. Left
  # to accumulate, a scene deleted months ago keeps its picture and keeps being read as current.
  rm -f "$gallery"/*.png
  "$here/gradlew" :composeApp:renderGallery -q --console=plain
fi

mkdir -p "$model/card"
count=0
for source in "$gallery"/*-phone-*.png "$gallery"/*-wide-*.png "$gallery"/*-card-*.png; do
  [ -e "$source" ] || continue
  name="$(basename "$source")"
  case "$name" in
    # The index wants the top of a screen at a readable size; the renderer draws
    # that itself, so nothing outside this toolchain is needed to make one.
    *-card-*.png) cp "$source" "$model/card/$name" ;;
    *) cp "$source" "$model/$name"; count=$((count + 1)) ;;
  esac
done

# A whole run draws every scene there is, so anything in the model that this run did not draw is a
# picture of a screen the app no longer has. Left behind, those keep being read as current: three
# findings in one review came from renders of states that had been deleted from the gallery. A run
# for a single view says nothing about the others, so it prunes nothing.
if [ $# -eq 0 ]; then
  removed=0
  for stale in "$model"/*.png "$model"/card/*.png; do
    [ -e "$stale" ] || continue
    if [ ! -e "$gallery/$(basename "$stale")" ]; then
      rm "$stale"
      removed=$((removed + 1))
    fi
  done
  [ "$removed" -gt 0 ] && echo "removed $removed render(s) of scenes that no longer exist"
fi

echo "$count renders in docs/model/img, cards in docs/model/img/card"
