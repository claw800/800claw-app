#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCKERFILE="$ROOT_DIR/tools/bootstrap/bake-ubuntu.Dockerfile"

ARCH="all"
OUTPUT_DIR="$ROOT_DIR/tools/bootstrap/output/rootfs"
ENABLE_BROWSER_STACK="${ENABLE_BROWSER_STACK:-0}"

# Optional buildx cache wiring. Set these in CI (e.g. from the GitHub
# Actions workflow) to enable layer-level cache reuse across runs. Leave
# unset locally to build without any cache backend.
#   BUILDX_CACHE_FROM, e.g. "type=gha,scope=ubuntu-rootfs-arm64-v8a"
#   BUILDX_CACHE_TO,   e.g. "type=gha,mode=max,scope=ubuntu-rootfs-arm64-v8a"
BUILDX_CACHE_FROM="${BUILDX_CACHE_FROM:-}"
BUILDX_CACHE_TO="${BUILDX_CACHE_TO:-}"

usage() {
  cat <<'EOF'
Usage: tools/bootstrap/bake-ubuntu.sh [--arch <arm64-v8a|x86_64|all>] [--output-dir <path>]

Examples:
  tools/bootstrap/bake-ubuntu.sh --arch arm64-v8a
  tools/bootstrap/bake-ubuntu.sh --arch x86_64 --output-dir tools/bootstrap/output/rootfs
  ENABLE_BROWSER_STACK=1 tools/bootstrap/bake-ubuntu.sh --arch arm64-v8a
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch)
      ARCH="$2"
      shift 2
      ;;
    --output-dir)
      OUTPUT_DIR="$2"
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

build_one() {
  local arch_label="$1"
  local platform="$2"
  local dest="$OUTPUT_DIR/$arch_label"

  mkdir -p "$dest"
  echo "Building rootfs for $arch_label ($platform)"

  local -a cache_args=()
  if [[ -n "$BUILDX_CACHE_FROM" ]]; then
    cache_args+=(--cache-from "$BUILDX_CACHE_FROM")
  fi
  if [[ -n "$BUILDX_CACHE_TO" ]]; then
    cache_args+=(--cache-to "$BUILDX_CACHE_TO")
  fi

  docker buildx build \
    --platform "$platform" \
    --file "$DOCKERFILE" \
    --build-arg ROOTFS_ARCH_LABEL="$arch_label" \
    --build-arg ENABLE_BROWSER_STACK="$ENABLE_BROWSER_STACK" \
    "${cache_args[@]}" \
    --output "type=local,dest=$dest" \
    "$ROOT_DIR"
}

case "$ARCH" in
  arm64-v8a)
    build_one "arm64-v8a" "linux/arm64"
    ;;
  x86_64)
    build_one "x86_64" "linux/amd64"
    ;;
  all)
    build_one "arm64-v8a" "linux/arm64"
    build_one "x86_64" "linux/amd64"
    ;;
  *)
    echo "Unsupported arch: $ARCH" >&2
    usage
    exit 1
    ;;
esac

(
  cd "$OUTPUT_DIR"
  find . -type f \
    \( -name "*.tar.xz" -o -name "dpkg-manifest.txt" -o -name "python-version.txt" -o -name "node-version.txt" \) \
    -print0 \
    | sort -z \
    | xargs -0 sha256sum > SHA256SUMS.txt
)

echo "Rootfs artifacts written to: $OUTPUT_DIR"
