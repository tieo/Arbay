#!/usr/bin/env bash
# Moves the model between this checkout and the machine serving it.
#
#   ARBAY_MODEL_HOST=user@host docs/model/sync.sh pull    boards edited in the browser come back
#   ARBAY_MODEL_HOST=user@host docs/model/sync.sh push    boards and a freshly built editor go up
#
# The served boards are a copy, so pull before reading them and before committing.
set -euo pipefail

host="${ARBAY_MODEL_HOST:?set ARBAY_MODEL_HOST to the machine serving the model, as user@host}"
remote=/var/lib/arbay-model
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "${1:-}" in
pull)
  rsync -a --delete "$host:$remote/wireframes/" "$here/wireframes/"
  rsync -a "$host:$remote/model.json" "$here/model.json"
  git -C "$here" status --short -- "$here"
  ;;
push)
  (cd "$here/web" && pnpm install --silent && pnpm build >/dev/null)
  rsync -a --delete "$here/web/dist/" "$host:$remote/site/"
  rsync -a --delete "$here/img/" "$host:$remote/img/"
  mkdir -p "$here/wireframes"
  rsync -a --delete "$here/wireframes/" "$host:$remote/wireframes/"
  rsync -a "$here/server.py" "$here/model.json" "$here/markets.json" "$host:$remote/"
  ssh "$host" "chown -R arbay:arbay $remote && systemctl restart arbay-model"
  ;;
*)
  echo "usage: ${BASH_SOURCE[0]} pull|push" >&2
  exit 2
  ;;
esac
