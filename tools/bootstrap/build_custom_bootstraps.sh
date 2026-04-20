#!/usr/bin/env bash
# Build Termux bootstrap zips with extra packages (proot, proot-distro, ...)
# pre-installed so the runtime APK can run proot offline on first launch.
#
# This is the local/developer equivalent of .github/workflows/build-custom-bootstrap.yml.
# It drives Termux's own scripts/generate-bootstraps.sh inside Termux's
# run-docker.sh container, so a working Docker daemon is required.
#
# Usage:
#   tools/bootstrap/build_custom_bootstraps.sh
#
# Environment overrides:
#   EXTRA_PACKAGES   default: proot,proot-distro
#   ARCHITECTURES    default: aarch64,x86_64
#   PACKAGE_VARIANT  default: apt-android-7         (used only for output path)
#   TP_DIR           default: <repo>/tools/bootstrap/build-custom/termux-packages
#   OUT_DIR          default: <repo>/tools/bootstrap/output-custom/<PACKAGE_VARIANT>
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

EXTRA_PACKAGES="${EXTRA_PACKAGES:-proot,proot-distro}"
ARCHITECTURES="${ARCHITECTURES:-aarch64,x86_64}"
PACKAGE_VARIANT="${PACKAGE_VARIANT:-apt-android-7}"
TP_DIR="${TP_DIR:-$ROOT_DIR/tools/bootstrap/build-custom/termux-packages}"
OUT_DIR="${OUT_DIR:-$ROOT_DIR/tools/bootstrap/output-custom/$PACKAGE_VARIANT}"

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker is required on PATH" >&2
  exit 1
fi

mkdir -p "$(dirname "$TP_DIR")" "$OUT_DIR"

if [[ ! -d "$TP_DIR/.git" ]]; then
  echo "Cloning termux/termux-packages into $TP_DIR"
  git clone --depth=1 https://github.com/termux/termux-packages.git "$TP_DIR"
else
  echo "Updating termux-packages checkout in $TP_DIR"
  (cd "$TP_DIR" && git fetch --depth=1 origin master && git reset --hard origin/master)
fi

echo "Building custom bootstrap: arch=${ARCHITECTURES} variant=${PACKAGE_VARIANT} extras=${EXTRA_PACKAGES}"
(
  cd "$TP_DIR"
  ./scripts/run-docker.sh ./scripts/generate-bootstraps.sh \
    --architectures "$ARCHITECTURES" \
    --add "$EXTRA_PACKAGES"
)

shopt -s nullglob
moved=0
for f in "$TP_DIR"/bootstrap-*.zip; do
  cp "$f" "$OUT_DIR/"
  moved=$((moved+1))
done
if [[ $moved -eq 0 ]]; then
  echo "ERROR: no bootstrap-*.zip produced by generate-bootstraps.sh" >&2
  ls -lah "$TP_DIR" || true
  exit 1
fi

{
  echo "# Custom Termux bootstrap zips"
  echo "# package_variant=${PACKAGE_VARIANT}"
  echo "# extra_packages=${EXTRA_PACKAGES}"
  echo "# generated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "# generator=termux/termux-packages@$(cd "$TP_DIR" && git rev-parse HEAD)"
} > "$OUT_DIR/MANIFEST.txt"

(
  cd "$OUT_DIR"
  if command -v sha256sum >/dev/null 2>&1; then
    find . -maxdepth 1 -type f -name "bootstrap-*.zip" -print0 \
      | sort -z | xargs -0 sha256sum > SHA256SUMS.txt
  else
    find . -maxdepth 1 -type f -name "bootstrap-*.zip" -print0 \
      | sort -z | xargs -0 shasum -a 256 > SHA256SUMS.txt
  fi
)

echo ""
echo "Done. Output: $OUT_DIR"
ls -lah "$OUT_DIR"
echo ""
echo "SHA256SUMS.txt:"
cat "$OUT_DIR/SHA256SUMS.txt"
