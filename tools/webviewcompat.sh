#!/bin/sh
# webviewcompat.sh — silent-fail guard for the head unit's ancient WebView.
#
# The MG4 (SWI68) WebView predates Chrome 71: modern JS/CSS does NOT throw, it is
# silently ignored, so a feature just dies with no error (e.g. `inset:0` collapsed
# the map vignette; `globalThis` / a stray `q()` crashed the whole page). This script
# scans the HAND-WRITTEN map assets for that family of features and fails the build.
#
# It deliberately skips vendored/transpiled libraries (leaflet.js, protomaps-leaflet.js,
# polyfills.js) — those are either es5/es2015 already or provide their own shims.
#
# Usage: sh tools/webviewcompat.sh   (run from repo root; build.sh calls it)

set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Hand-written assets only. Add new ones here as they appear.
FILES="app/assets/map/map-vector.html"

# Pattern | human explanation. Pipe-separated; one check per line.
# Patterns are extended-regex (grep -E).
fail=0
check() {
  pat="$1"; why="$2"
  for f in $FILES; do
    [ -f "$f" ] || continue
    hits=$(grep -nE "$pat" "$f" || true)
    if [ -n "$hits" ]; then
      echo "  ✗ $f: $why"
      echo "$hits" | sed 's/^/      /'
      fail=1
    fi
  done
}

echo "==> WebView compatibility check (pre-Chrome 71)"
check '[^a-zA-Z_.]inset[[:space:]]*:'      "CSS 'inset' shorthand unsupported — use top/left/right/bottom"
check '[^.a-zA-Z0-9_]\?\.'                 "optional chaining ?. unsupported — use explicit && guards"
check '[^?]\?\?[^?]'                       "nullish coalescing ?? unsupported — use ( x != null ? x : y )"
check 'globalThis'                         "globalThis undefined — use window"
check '\.flat(Map)?\('                     "Array.flat/flatMap unsupported — use concat/reduce"
check 'Object\.fromEntries'               "Object.fromEntries unsupported"
check '\.(padStart|padEnd|matchAll|replaceAll)\(' "String padStart/padEnd/matchAll/replaceAll unsupported"
check '[^a-zA-Z0-9_]\.at\(-?[0-9]'         ".at() index unsupported — use [length-1]"
check 'structuredClone'                    "structuredClone unsupported"
check 'BigInt'                             "BigInt unsupported"
check 'gap[[:space:]]*:'                   "flex/grid 'gap' is unreliable here — use margins"

if [ "$fail" -ne 0 ]; then
  echo "==> WebView compatibility check FAILED — fix the above before shipping."
  exit 1
fi
echo "==> WebView compatibility: clean ✅"
