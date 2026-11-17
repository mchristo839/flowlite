#!/usr/bin/env bash
# Copies a desktop installer to out/ and writes flowlite-<os>.json beside it.
# The signature covers "flowlite-<os>:<build>:<sha256>" (update.rs in the desktop app), so a
# tampered file fails the checksum and an old release cannot be relabelled as new.
# Usage: sign-desktop.sh <linux|windows|macos> <installer glob>
set -euo pipefail
OS=$1; shift
if [ -z "${RELEASE_KEY:-}" ]; then
  echo "::error::FLOWLITE_RELEASE_KEY secret is missing. The desktop apps refuse unsigned releases."
  exit 1
fi
IN=$(ls $@ | head -1)
[ -f "$IN" ] || { echo "::error::No installer found for $OS"; exit 1; }
mkdir -p out
NAME=$(basename "$IN")
cp "$IN" "out/$NAME"
SHA=$(sha256sum "out/$NAME" | cut -d' ' -f1)
KEY=$(mktemp); printf '%s' "$RELEASE_KEY" > "$KEY"
MANIFEST=$(mktemp); printf 'flowlite-%s:%s:%s' "$OS" "$FLOWLITE_BUILD" "$SHA" > "$MANIFEST"
(cd Handy && bunx tauri signer sign -f "$KEY" -p "" "$MANIFEST" >/dev/null)
SIG=$(cat "$MANIFEST.sig"); rm -f "$KEY" "$MANIFEST" "$MANIFEST.sig"
# "deb" keeps Linux apps from 0.2.x updating. Newer apps read "file".
PY=$(command -v python3 || command -v python)
"$PY" - "$OS" "$FLOWLITE_BUILD" "$SHA" "$SIG" "$GITHUB_REF_NAME" "$NAME" > "out/flowlite-$OS.json" <<'EOF2'
import json, sys
os_, code, sha, sig, tag, name = sys.argv[1:]
m = {"version_code": int(code), "sha256": sha, "signature": sig, "tag": tag, "file": name}
if os_ == "linux":
    m["deb"] = name
print(json.dumps(m))
EOF2
cat "out/flowlite-$OS.json"
