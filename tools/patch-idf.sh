#!/bin/sh
# QindaDeck: apply (or revert) the local ESP-IDF patches this firmware needs.
#
# The IDF ships exFAT disabled in FatFS and exposes no Kconfig switch for it,
# so we carry the one-liner as a proper patch in firmware/patches/. Run this
# once per IDF checkout; it is idempotent. Revert with:
#   tools/patch-idf.sh --revert
set -eu

IDF_PATH="${IDF_PATH:-$HOME/esp/esp-idf}"
PATCH_DIR="$(cd "$(dirname "$0")/../firmware/patches" && pwd)"

apply_one() {
    p="$1"
    if git -C "$IDF_PATH" apply --check --reverse "$p" 2>/dev/null; then
        echo "already applied: $(basename "$p")"
    elif git -C "$IDF_PATH" apply --check "$p" 2>/dev/null; then
        git -C "$IDF_PATH" apply "$p"
        echo "applied:         $(basename "$p")"
    else
        echo "CONFLICT:        $(basename "$p") does not apply cleanly" >&2
        exit 1
    fi
}

revert_one() {
    p="$1"
    if git -C "$IDF_PATH" apply --check --reverse "$p" 2>/dev/null; then
        git -C "$IDF_PATH" apply --reverse "$p"
        echo "reverted:        $(basename "$p")"
    else
        echo "not applied:     $(basename "$p")"
    fi
}

for p in "$PATCH_DIR"/*.patch; do
    if [ "${1:-}" = "--revert" ]; then
        revert_one "$p"
    else
        apply_one "$p"
    fi
done
