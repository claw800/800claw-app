#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSIONS_FILE="$ROOT_DIR/tools/bootstrap/BOOTSTRAP_VERSIONS.txt"
OUTPUT_DIR="${1:-$ROOT_DIR/tools/bootstrap/output}"

mkdir -p "$OUTPUT_DIR"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

download_one() {
  local package_variant="$1"
  local version="$2"
  local arch="$3"
  local expected_sha="$4"

  local base_url="https://github.com/termux/termux-packages/releases/download/bootstrap-${version}"
  local file_name="bootstrap-${arch}.zip"
  local target_dir="$OUTPUT_DIR/$package_variant/$version"
  local target_file="$target_dir/$file_name"
  local url="$base_url/$file_name"

  mkdir -p "$target_dir"
  echo "Downloading $url"
  curl -fL --retry 3 --retry-delay 2 "$url" -o "$target_file"

  local actual_sha
  actual_sha="$(sha256_file "$target_file")"
  if [[ "$actual_sha" != "$expected_sha" ]]; then
    echo "Checksum mismatch for $target_file" >&2
    echo "  expected: $expected_sha" >&2
    echo "  actual:   $actual_sha" >&2
    exit 1
  fi
}

while IFS='|' read -r package_variant version arch sha; do
  [[ -z "${package_variant// }" ]] && continue
  [[ "${package_variant:0:1}" == "#" ]] && continue
  download_one "$package_variant" "$version" "$arch" "$sha"
done < "$VERSIONS_FILE"

echo "All bootstrap files downloaded and verified into: $OUTPUT_DIR"
