#!/usr/bin/env bash
# Upload a local APK to Aliyun OSS (private bucket). Requires ossutil in PATH.
# Usage: ALIYUN_OSS_ENDPOINT=oss-cn-xxx.aliyuncs.com ALIYUN_OSS_BUCKET=bucket \
#   ALIYUN_OSS_ACCESS_KEY_ID=... ALIYUN_OSS_ACCESS_KEY_SECRET=... \
#   ./scripts/oss-upload-apk.sh <local-apk-path> <oss-object-key>
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

if command -v ossutil &>/dev/null; then
  ossutil cp -f "$LOCAL" "oss://${ALIYUN_OSS_BUCKET}/${KEY}" \
    -e "https://${ALIYUN_OSS_ENDPOINT}" \
    -i "${ALIYUN_OSS_ACCESS_KEY_ID}" \
    -k "${ALIYUN_OSS_ACCESS_KEY_SECRET}"
  echo "Uploaded oss://${ALIYUN_OSS_BUCKET}/${KEY}"
  exit 0
fi

echo "ossutil not found; install from https://help.aliyun.com/document_detail/120075.html" >&2
exit 1
