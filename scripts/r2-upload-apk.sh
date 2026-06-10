#!/usr/bin/env bash
# Upload a local APK to Cloudflare R2 for the v2 account-gated download Worker.
# Requires Wrangler auth. Defaults to the remote R2 bucket currently bound by
# v2.download.800claw.com.
#
# Usage:
#   ./scripts/r2-upload-apk.sh <local-apk-path> <r2-object-key> [bucket]
#
# Example keys:
#   prod/artifacts/apks/800claw-termux-app/800claw-termux-app-latest-universal.apk
#   prod/artifacts/apks/800claw-termux-app/800claw-termux-app-v1.0.0+abc1234-universal.apk
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "Usage: $0 <local-apk> <r2-object-key> [bucket]" >&2
  exit 1
fi

LOCAL_APK="$1"
R2_OBJECT_KEY="$2"
BUCKET="${3:-${R2_BUCKET:-${CLOUDFLARE_R2_BUCKET:-800claw-public}}}"

if [[ ! -f "$LOCAL_APK" ]]; then
  echo "APK not found: $LOCAL_APK" >&2
  exit 1
fi

if command -v wrangler >/dev/null 2>&1; then
  WRANGLER=(wrangler)
elif command -v npx >/dev/null 2>&1; then
  WRANGLER=(npx wrangler)
else
  echo "Neither wrangler nor npx was found on PATH." >&2
  exit 1
fi

REMOTE_FLAG=(--remote)
if [[ "${R2_LOCAL:-0}" == "1" ]]; then
  REMOTE_FLAG=()
fi

"${WRANGLER[@]}" r2 object put "${BUCKET}/${R2_OBJECT_KEY}" --file "$LOCAL_APK" "${REMOTE_FLAG[@]}"
echo "Uploaded r2://${BUCKET}/${R2_OBJECT_KEY}"
