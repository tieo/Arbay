# Shared by the hooks: finds what identifies the machine, the network or the person in text that is
# about to be committed or pushed.
#
# Two kinds of pattern. The generic ones below name no one and are safe to publish. The private
# ones (the domain, the host names, the accounts, the places) would give away exactly what they
# protect, so they live outside the repository, one extended regex per line, in
#   ${ARBAY_PRIVATE_PATTERNS:-$HOME/.config/git/private-patterns}
# which the dotfiles declare. Without that file only the generic checks run, and the hooks say so.

private_patterns_file="${ARBAY_PRIVATE_PATTERNS:-$HOME/.config/git/private-patterns}"

generic_patterns=(
  '/home/[a-z][a-z0-9_-]+/'                                  # a home directory, from a path or a stack trace
  '\b(192\.168|10\.[0-9]{1,3})\.[0-9]{1,3}\.[0-9]{1,3}\b'    # a private network address
  '\b172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}\b'   # the other private range
  'BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY'               # a private key
  '\bgh[pousr]_[A-Za-z0-9]{30,}'                             # a GitHub token
  '\bsk-ant-[A-Za-z0-9_-]{20,}'                              # an Anthropic key
  '\bAKIA[0-9A-Z]{16}\b'                                     # an AWS key
  '\bAS[0-9]{3,6}\b.*(GmbH|Ltd|Inc)'                         # an ISP named by its autonomous system
)
# 10.0.2.2 is the Android emulator's alias for the host loopback: a documented constant, not a network.
allowed_text='10\.0\.2\.2'

# Saved pages from the sites themselves carry the sites' own data, not ours.
is_fixture() { case "$1" in */fixtures/*|*/testdata/*) return 0 ;; esac; return 1; }

load_private_patterns() {
  private_patterns=()
  [ -r "$private_patterns_file" ] || return 1
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    private_patterns+=("$line")
  done < "$private_patterns_file"
  return 0
}

warn_if_no_private_patterns() {
  load_private_patterns && return 0
  echo "leakcheck: $private_patterns_file is missing, so only the generic checks ran." >&2
  echo "           Names, hosts and places particular to this project were not checked." >&2
}

# scan_text LABEL < text: prints one line per hit and returns 1 if there was any.
scan_text() {
  local label="$1" text hits=0 p hit
  text="$(grep -vE "$allowed_text" || true)"
  [ -z "$text" ] && return 0
  for p in "${generic_patterns[@]}" "${private_patterns[@]}"; do
    [ -z "$p" ] && continue
    hit="$(printf '%s\n' "$text" | grep -inE -- "$p" | head -1)"
    if [ -n "$hit" ]; then
      echo "  $label: $(printf '%s' "$hit" | cut -c1-120)" >&2
      hits=1
    fi
  done
  return $hits
}

# The identities this repository publishes under, one email per line.
allowed_identities_file="$(git rev-parse --show-toplevel)/.githooks/allowed-identities"

identity_allowed() {
  local email="$1"
  grep -vE '^\s*(#|$)' "$allowed_identities_file" | grep -qxF -- "$email"
}
