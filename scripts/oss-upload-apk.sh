#!/usr/bin/env bash
# Upload a local APK to Aliyun OSS (private bucket). Requires ossutil 2.x (recommended) or 1.x in PATH.
# Windows (PowerShell): use scripts/oss-upload-apk.ps1 with the same env vars.
# Usage: ALIYUN_OSS_ENDPOINT=oss-cn-xxx.aliyuncs.com ALIYUN_OSS_BUCKET=bucket \
#   ALIYUN_OSS_ACCESS_KEY_ID=... ALIYUN_OSS_ACCESS_KEY_SECRET=... \
#   ./scripts/oss-upload-apk.sh <local-apk-path> <oss-object-key>
# Optional: ALIYUN_OSS_REGION (or OSS_REGION) — ossutil 2.x needs a region for SigV4; otherwise
#   derived from the endpoint host (oss-cn-hangzhou.aliyuncs.com -> cn-hangzhou).
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <local-apk> <oss-object-key>" >&2
  exit 1
fi
LOCAL="$1"
KEY="$2"
: "${ALIYUN_OSS_ENDPOINT:?set ALIYUN_OSS_ENDPOINT}"
: "${ALIYUN_OSS_BUCKET:?set ALIYUN_OSS_BUCKET}"
: "${ALIYUN_OSS_ACCESS_KEY_ID:?set ALIYUN_OSS_ACCESS_KEY_ID}"
: "${ALIYUN_OSS_ACCESS_KEY_SECRET:?set ALIYUN_OSS_ACCESS_KEY_SECRET}"

ep="${ALIYUN_OSS_ENDPOINT#https://}"
ep="${ep#http://}"
ep="https://${ep}"

host="${ep#https://}"
host="${host%%/*}"

OSS_REGION="${ALIYUN_OSS_REGION:-${OSS_REGION:-}}"
if [[ -z "${OSS_REGION}" ]]; then
  if [[ "$host" =~ ^oss-(.+)-internal\.aliyuncs\.com$ ]]; then
    OSS_REGION="${BASH_REMATCH[1]}"
  elif [[ "$host" =~ ^oss-(.+)\.aliyuncs\.com$ ]]; then
    OSS_REGION="${BASH_REMATCH[1]}"
  else
    echo "Could not derive OSS region from endpoint host '${host}'. Set ALIYUN_OSS_REGION (e.g. cn-hangzhou)." >&2
    exit 1
  fi
fi

if command -v ossutil &>/dev/null; then
  ossutil cp -f "$LOCAL" "oss://${ALIYUN_OSS_BUCKET}/${KEY}" \
    -e "$ep" \
    --region "${OSS_REGION}" \
    -i "${ALIYUN_OSS_ACCESS_KEY_ID}" \
    -k "${ALIYUN_OSS_ACCESS_KEY_SECRET}"
  echo "Uploaded oss://${ALIYUN_OSS_BUCKET}/${KEY}"
  exit 0
fi

echo "ossutil not found; install ossutil 2.0 from https://www.alibabacloud.com/help/en/oss/install-ossutil2" >&2
exit 1
