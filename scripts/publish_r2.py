#!/usr/bin/env python3
"""Publish the default 800claw Termux APK to Cloudflare R2.

By default this is a dry run. Pass --execute to upload to remote R2.
"""

import argparse
import datetime as dt
import hashlib
import hmac
import html
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LOCAL_FILE = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "termux-app_apt-android-7-debug_universal.apk"
DEFAULT_PRODUCT = "800claw-termux-app"
DEFAULT_FILENAME = "800claw-termux-app-latest-universal.apk"
WRANGLER_UPLOAD_LIMIT_BYTES = 300 * 1024 * 1024
MULTIPART_PART_SIZE_BYTES = 64 * 1024 * 1024


def resolve_wrangler_command() -> list[str]:
    for candidate in ("wrangler", "wrangler.cmd", "wrangler.exe"):
        resolved = shutil.which(candidate)
        if resolved:
            return [resolved]
    for candidate in ("npx", "npx.cmd", "npx.exe"):
        resolved = shutil.which(candidate)
        if resolved:
            return [resolved, "wrangler"]
    print("Could not find wrangler or npx on PATH.", file=sys.stderr)
    sys.exit(1)


def run_upload(bucket: str, key: str, source: Path, execute: bool, remote: bool) -> None:
    if remote and source.stat().st_size > WRANGLER_UPLOAD_LIMIT_BYTES:
        run_multipart_upload(bucket, key, source, execute)
        return

    command = [*resolve_wrangler_command(), "r2", "object", "put", f"{bucket}/{key}", "--file", str(source)]
    if remote:
        command.append("--remote")
    print(" ".join(command))
    if execute:
        subprocess.run(command, check=True)


def env_value(*names: str) -> str:
    for name in names:
        value = os.environ.get(name, "").strip()
        if value:
            return value
    return ""


def s3_config() -> tuple[str, str, str, str, str]:
    account_id = env_value("CLOUDFLARE_ACCOUNT_ID", "CF_ACCOUNT_ID")
    access_key = env_value("R2_ACCESS_KEY_ID", "AWS_ACCESS_KEY_ID")
    secret_key = env_value("R2_SECRET_ACCESS_KEY", "AWS_SECRET_ACCESS_KEY")
    region = env_value("R2_REGION", "AWS_REGION") or "auto"
    endpoint = env_value("R2_ENDPOINT")
    if not endpoint and account_id:
        endpoint = f"https://{account_id}.r2.cloudflarestorage.com"
    missing = [
        name
        for name, value in (
            ("CLOUDFLARE_ACCOUNT_ID or R2_ENDPOINT", account_id or endpoint),
            ("R2_ACCESS_KEY_ID or AWS_ACCESS_KEY_ID", access_key),
            ("R2_SECRET_ACCESS_KEY or AWS_SECRET_ACCESS_KEY", secret_key),
        )
        if not value
    ]
    if missing:
        print("Large R2 uploads require S3 multipart credentials. Missing: " + ", ".join(missing), file=sys.stderr)
        sys.exit(1)
    return endpoint.rstrip("/"), access_key, secret_key, region, "s3"


def quote_path(value: str) -> str:
    return "/".join(urllib.parse.quote(segment, safe="-_.~") for segment in value.split("/"))


def canonical_query(params: dict[str, str]) -> str:
    items = sorted((urllib.parse.quote(k, safe="-_.~"), urllib.parse.quote(v, safe="-_.~")) for k, v in params.items())
    return "&".join(f"{k}={v}" for k, v in items)


def signing_key(secret_key: str, date_stamp: str, region: str, service: str) -> bytes:
    k_date = hmac.new(("AWS4" + secret_key).encode(), date_stamp.encode(), hashlib.sha256).digest()
    k_region = hmac.new(k_date, region.encode(), hashlib.sha256).digest()
    k_service = hmac.new(k_region, service.encode(), hashlib.sha256).digest()
    return hmac.new(k_service, b"aws4_request", hashlib.sha256).digest()


def format_mib(size_bytes: int) -> str:
    return f"{size_bytes / (1024 * 1024):.1f} MiB"


def format_speed(size_bytes: int, elapsed_seconds: float) -> str:
    if elapsed_seconds <= 0:
        return "-- MiB/s"
    return f"{size_bytes / (1024 * 1024) / elapsed_seconds:.2f} MiB/s"


def format_elapsed(elapsed_seconds: float) -> str:
    minutes, seconds = divmod(int(elapsed_seconds), 60)
    hours, minutes = divmod(minutes, 60)
    if hours:
        return f"{hours}h{minutes:02d}m{seconds:02d}s"
    if minutes:
        return f"{minutes}m{seconds:02d}s"
    return f"{seconds}s"


def s3_request(
    method: str,
    bucket: str,
    key: str,
    query: dict[str, str],
    body: bytes,
    content_type: str = "application/octet-stream",
) -> bytes:
    endpoint, access_key, secret_key, region, service = s3_config()
    parsed = urllib.parse.urlparse(endpoint)
    host = parsed.netloc
    path = f"/{bucket}/{quote_path(key)}"
    payload_hash = hashlib.sha256(body).hexdigest()
    now = dt.datetime.now(dt.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    scope = f"{date_stamp}/{region}/{service}/aws4_request"
    headers = {
        "content-type": content_type,
        "host": host,
        "x-amz-content-sha256": payload_hash,
        "x-amz-date": amz_date,
    }
    signed_headers = ";".join(sorted(headers))
    canonical_headers = "".join(f"{name}:{headers[name]}\n" for name in sorted(headers))
    canonical_request = "\n".join([method, path, canonical_query(query), canonical_headers, signed_headers, payload_hash])
    string_to_sign = "\n".join(
        ["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical_request.encode()).hexdigest()]
    )
    signature = hmac.new(signing_key(secret_key, date_stamp, region, service), string_to_sign.encode(), hashlib.sha256).hexdigest()
    headers["authorization"] = (
        f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, SignedHeaders={signed_headers}, Signature={signature}"
    )
    url = f"{endpoint}{path}"
    query_string = canonical_query(query)
    if query_string:
        url += f"?{query_string}"
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"R2 multipart request failed: HTTP {error.code} {detail}") from error


def upload_part_with_etag(bucket: str, key: str, upload_id: str, part_number: int, body: bytes) -> str:
    endpoint, access_key, secret_key, region, service = s3_config()
    parsed = urllib.parse.urlparse(endpoint)
    host = parsed.netloc
    path = f"/{bucket}/{quote_path(key)}"
    query = {"partNumber": str(part_number), "uploadId": upload_id}
    payload_hash = hashlib.sha256(body).hexdigest()
    now = dt.datetime.now(dt.timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    scope = f"{date_stamp}/{region}/{service}/aws4_request"
    headers = {
        "content-type": "application/octet-stream",
        "host": host,
        "x-amz-content-sha256": payload_hash,
        "x-amz-date": amz_date,
    }
    signed_headers = ";".join(sorted(headers))
    canonical_headers = "".join(f"{name}:{headers[name]}\n" for name in sorted(headers))
    canonical_request = "\n".join(["PUT", path, canonical_query(query), canonical_headers, signed_headers, payload_hash])
    string_to_sign = "\n".join(
        ["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical_request.encode()).hexdigest()]
    )
    signature = hmac.new(signing_key(secret_key, date_stamp, region, service), string_to_sign.encode(), hashlib.sha256).hexdigest()
    headers["authorization"] = (
        f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, SignedHeaders={signed_headers}, Signature={signature}"
    )
    request = urllib.request.Request(f"{endpoint}{path}?{canonical_query(query)}", data=body, method="PUT", headers=headers)
    with urllib.request.urlopen(request) as response:
        etag = response.headers.get("ETag", "").strip()
    if not etag:
        raise RuntimeError(f"R2 did not return ETag for part {part_number}")
    return etag


def run_multipart_upload(bucket: str, key: str, source: Path, execute: bool) -> None:
    total_bytes = source.stat().st_size
    print(f"{source} is {format_mib(total_bytes)}; using R2 S3 multipart upload.")
    if not execute:
        print(f"multipart put r2://{bucket}/{key} --file {source}")
        print("Dry run only. Re-run with --execute to upload.")
        return

    create_xml = s3_request("POST", bucket, key, {"uploads": ""}, b"", "application/octet-stream")
    upload_id = ET.fromstring(create_xml).findtext(".//{*}UploadId")
    if not upload_id:
        raise RuntimeError("R2 did not return an UploadId")

    parts: list[tuple[int, str]] = []
    try:
        upload_started = time.monotonic()
        uploaded_bytes = 0
        with source.open("rb") as handle:
            part_number = 1
            while True:
                chunk = handle.read(MULTIPART_PART_SIZE_BYTES)
                if not chunk:
                    break
                part_started = time.monotonic()
                etag = upload_part_with_etag(bucket, key, upload_id, part_number, chunk)
                part_elapsed = time.monotonic() - part_started
                uploaded_bytes += len(chunk)
                total_elapsed = time.monotonic() - upload_started
                percent = uploaded_bytes / total_bytes * 100
                parts.append((part_number, etag))
                print(
                    f"Uploaded part {part_number}: {format_mib(uploaded_bytes)} / {format_mib(total_bytes)} ({percent:.1f}%) "
                    f"| part {format_speed(len(chunk), part_elapsed)} | avg {format_speed(uploaded_bytes, total_elapsed)} "
                    f"| elapsed {format_elapsed(total_elapsed)}",
                    flush=True,
                )
                part_number += 1
        complete_body = (
            "<CompleteMultipartUpload>"
            + "".join(f"<Part><PartNumber>{part}</PartNumber><ETag>{html.escape(etag)}</ETag></Part>" for part, etag in parts)
            + "</CompleteMultipartUpload>"
        ).encode()
        s3_request("POST", bucket, key, {"uploadId": upload_id}, complete_body, "application/xml")
        total_elapsed = time.monotonic() - upload_started
        print(f"Uploaded r2://{bucket}/{key} in {format_elapsed(total_elapsed)} at {format_speed(total_bytes, total_elapsed)} average")
    except Exception:
        try:
            s3_request("DELETE", bucket, key, {"uploadId": upload_id}, b"", "application/octet-stream")
        finally:
            raise


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish 800claw Termux APK to Cloudflare R2")
    parser.add_argument("--bucket", default="800claw-public", help="R2 bucket name")
    parser.add_argument("--env", default="prod", choices=["dev", "uat", "prod"], help="Object-key environment prefix")
    parser.add_argument("--file", default=str(DEFAULT_LOCAL_FILE), help="Local APK path")
    parser.add_argument("--key", default="", help="Override full R2 object key")
    parser.add_argument("--execute", action="store_true", help="Run wrangler upload instead of printing command")
    parser.add_argument("--local", action="store_true", help="Upload to Wrangler local R2 instead of remote R2")
    args = parser.parse_args()

    source = Path(args.file).resolve()
    if not source.is_file():
        print(f"Missing local APK: {source}", file=sys.stderr)
        sys.exit(1)

    key = args.key or f"{args.env}/artifacts/apks/{DEFAULT_PRODUCT}/{DEFAULT_FILENAME}"
    run_upload(args.bucket, key, source, args.execute, not args.local)
    if not args.execute and source.stat().st_size <= WRANGLER_UPLOAD_LIMIT_BYTES:
        print("Dry run only. Re-run with --execute to upload.")


if __name__ == "__main__":
    main()
