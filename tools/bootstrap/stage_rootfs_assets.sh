#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE_ROOT="$ROOT_DIR/tools/bootstrap/output/rootfs"
DEST_ROOT="$ROOT_DIR/app/src/main/assets/rootfs"
ARCH="all"

usage() {
  cat <<'EOF'
Usage: tools/bootstrap/stage_rootfs_assets.sh [--arch <arm64-v8a|x86_64|all>] [--source <path>] [--dest <path>]

Copies generated rootfs artifacts from tools/bootstrap/output/rootfs into app assets.

Defaults:
  --source tools/bootstrap/output/rootfs
  --dest   app/src/main/assets/rootfs
  --arch   all

Examples:
  tools/bootstrap/stage_rootfs_assets.sh
  tools/bootstrap/stage_rootfs_assets.sh --arch arm64-v8a
  tools/bootstrap/stage_rootfs_assets.sh --source /tmp/rootfs-output --dest app/src/main/assets/rootfs
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch)
      ARCH="$2"
      shift 2
      ;;
    --source)
      SOURCE_ROOT="$2"
      shift 2
      ;;
    --dest)
      DEST_ROOT="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

mkdir -p "$DEST_ROOT/metadata"

copy_arch() {
  local arch="$1"
  local src="$SOURCE_ROOT/$arch"
  local src_mode="nested"

  if [[ ! -d "$src" ]]; then
    # GitHub artifact downloads are often unzipped per-arch into a flat folder.
    # In that case SOURCE_ROOT itself contains ubuntu-noble-<arch>.tar.xz.
    src="$SOURCE_ROOT"
    src_mode="flat"
  fi

  local tarball
  tarball="$(ls "$src"/ubuntu-noble-"$arch".tar.xz 2>/dev/null || true)"
  if [[ -z "$tarball" ]]; then
    echo "Missing tarball for $arch in $src (mode: $src_mode)" >&2
    exit 1
  fi

  echo "Staging $(basename "$tarball")"
  cp -f "$tarball" "$DEST_ROOT/"

  mkdir -p "$DEST_ROOT/metadata/$arch"
  for f in dpkg-manifest.txt python-version.txt node-version.txt; do
    if [[ -f "$src/$f" ]]; then
      cp -f "$src/$f" "$DEST_ROOT/metadata/$arch/$f"
    fi
  done
}

case "$ARCH" in
  arm64-v8a)
    copy_arch "arm64-v8a"
    ;;
  x86_64)
    copy_arch "x86_64"
    ;;
  all)
    copy_arch "arm64-v8a"
    copy_arch "x86_64"
    ;;
  *)
    echo "Unsupported arch: $ARCH" >&2
    usage
    exit 1
    ;;
esac

echo "Staged rootfs assets to: $DEST_ROOT"
echo "Current payloads:"
ls -lah "$DEST_ROOT"
