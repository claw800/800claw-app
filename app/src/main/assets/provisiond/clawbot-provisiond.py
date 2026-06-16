#!/usr/bin/env python3
"""800claw provisiond — outbound WebSocket device client for hub provisioning."""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

try:
    import websocket  # type: ignore
except ImportError:
    websocket = None

PRODUCT_ARMBIAN = "800claw-armbian"
PRODUCT_ANDROID = "800claw-android"

DEFAULT_DEVICE_ID_PATH = Path("/etc/clawbot/device-id")
DEFAULT_DEVICE_SECRET_PATH = Path("/etc/clawbot/device-secret")
DEFAULT_ACTIVATION_PATH = Path("/etc/clawbot/activation.json")
DEFAULT_ENV_PATH = Path("/opt/clawbot/.env")
DEFAULT_COMPOSE_DIR = Path("/opt/clawbot")
GUEST_NANOBOT_CONFIG = Path.home() / ".nanobot" / "config.json"
GUEST_ACTIVATION = Path.home() / ".clawbot" / "activation.json"


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8").strip()


def load_identity(device_id_path: Path, secret_path: Path) -> tuple[str, str]:
    device_id = os.environ.get("CLAWBOT_DEVICE_ID", "").strip() or read_text(device_id_path)
    secret = os.environ.get("CLAWBOT_DEVICE_SECRET", "").strip() or read_text(secret_path)
    if not device_id or not secret:
        raise RuntimeError("device id/secret missing")
    return device_id, secret


def compute_secret(device_id: str, production_secret: str) -> str:
    return hmac.new(
        production_secret.encode("utf-8"),
        device_id.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def request_ticket(hub_base: str, device_id: str, secret: str) -> str:
    payload = json.dumps({"deviceId": device_id, "secret": secret}).encode("utf-8")
    req = urllib.request.Request(
        f"{hub_base.rstrip('/')}/api/device/v1/ticket",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if not body.get("success") or not body.get("ticket"):
        raise RuntimeError(body.get("error") or "ticket request failed")
    return str(body["ticket"])


def ws_url(hub_base: str, device_id: str, ticket: str) -> str:
    base = hub_base.rstrip("/")
    if base.startswith("https://"):
        base = "wss://" + base[len("https://") :]
    elif base.startswith("http://"):
        base = "ws://" + base[len("http://") :]
    return f"{base}/ws/v1?device_id={urllib.parse.quote(device_id)}&ticket={urllib.parse.quote(ticket)}"


def send_status(ws: Any, device_id: str, step: str, state: str, message: str = "", url: str = "") -> None:
    payload: dict[str, str] = {"step": step, "state": state}
    if message:
        payload["message"] = message
    if url:
        payload["url"] = url
    ws.send(
        json.dumps(
            {
                "type": "status",
                "deviceId": device_id,
                "payload": payload,
            }
        )
    )


def send_response(ws: Any, device_id: str, message_id: str, ok: bool, error: str = "") -> None:
    payload: dict[str, Any] = {"messageId": message_id, "ok": ok}
    if error:
        payload["error"] = error
    ws.send(
        json.dumps(
            {
                "type": "response",
                "deviceId": device_id,
                "payload": payload,
            }
        )
    )


def save_activation(path: Path, device_id: str, device_license: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps({"device_id": device_id, "device_license": device_license}, indent=2),
        encoding="utf-8",
    )


def patch_env_file(env_path: Path, config_json: dict[str, Any]) -> None:
    lines: list[str] = []
    if env_path.exists():
        lines = env_path.read_text(encoding="utf-8").splitlines()

    def set_kv(key: str, value: str) -> None:
        nonlocal lines
        replaced = False
        out: list[str] = []
        for line in lines:
            if line.startswith(f"{key}="):
                out.append(f"{key}={value}")
                replaced = True
            else:
                out.append(line)
        if not replaced:
            out.append(f"{key}={value}")
        lines = out

    providers = config_json.get("providers") or {}
    if isinstance(providers, dict) and providers:
        first = next(iter(providers.values()))
        if isinstance(first, dict):
            if first.get("apiKey"):
                set_kv("NANOBOT_API_KEY", str(first["apiKey"]))
            if first.get("apiBase"):
                set_kv("NANOBOT_API_BASE", str(first["apiBase"]))

    channels = config_json.get("channels") or {}
    feishu = channels.get("feishu") if isinstance(channels, dict) else None
    if isinstance(feishu, dict):
        set_kv("NANOBOT_FEISHU_ENABLED", "true" if feishu.get("enabled") else "false")
        if feishu.get("appId"):
            set_kv("NANOBOT_FEISHU_APP_ID", str(feishu["appId"]))
        if feishu.get("appSecret"):
            set_kv("NANOBOT_FEISHU_APP_SECRET", str(feishu["appSecret"]))

    weixin = channels.get("weixin") if isinstance(channels, dict) else None
    if isinstance(weixin, dict):
        set_kv("NANOBOT_WEIXIN_ENABLED", "true" if weixin.get("enabled") else "false")

    env_path.parent.mkdir(parents=True, exist_ok=True)
    env_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def save_nanobot_config(config_path: Path, config_json: dict[str, Any]) -> None:
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(json.dumps(config_json, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def restart_compose(compose_dir: Path) -> None:
    compose = shutil.which("docker")
    cmd = [compose or "docker", "compose", "up", "-d", "--force-recreate"]
    subprocess.run(cmd, cwd=str(compose_dir), check=False)


def extract_weixin_login_url(log_text: str) -> str:
    match = re.search(r"Login URL:\s*(\S+)", log_text)
    return match.group(1) if match else ""


def run_weixin_login_probe() -> str:
    try:
        proc = subprocess.run(
            ["nanobot", "channels", "login", "weixin"],
            capture_output=True,
            text=True,
            timeout=90,
            check=False,
        )
        return extract_weixin_login_url((proc.stdout or "") + (proc.stderr or ""))
    except Exception:
        return ""


def handle_configure(
    ws: Any,
    device_id: str,
    message_id: str,
    payload: dict[str, Any],
    product: str,
) -> None:
    playbook = str(payload.get("playbook") or "full_init")
    try:
        if playbook in {"identity_and_license", "full_init"}:
            license_token = str(payload.get("device_license") or "")
            if license_token:
                target = DEFAULT_ACTIVATION_PATH if product == PRODUCT_ARMBIAN else GUEST_ACTIVATION
                save_activation(target, device_id, license_token)
                send_status(ws, device_id, "identity_and_license", "completed")

        config_json = payload.get("config_json")
        if playbook in {"apply_server_config", "feishu_channel", "weixin_channel", "full_init"} and isinstance(
            config_json, dict
        ):
            if product == PRODUCT_ARMBIAN:
                patch_env_file(DEFAULT_ENV_PATH, config_json)
                send_status(ws, device_id, "apply_server_config", "completed")
                if playbook in {"feishu_channel", "full_init"}:
                    restart_compose(DEFAULT_COMPOSE_DIR)
                    send_status(ws, device_id, "feishu_channel", "started")
                if playbook in {"weixin_channel", "full_init"}:
                    login_url = run_weixin_login_probe()
                    if login_url:
                        send_status(
                            ws,
                            device_id,
                            "weixin_channel",
                            "needs_user_action",
                            url=login_url,
                        )
                    else:
                        send_status(ws, device_id, "weixin_channel", "pending_token")
            else:
                save_nanobot_config(GUEST_NANOBOT_CONFIG, config_json)
                send_status(ws, device_id, "apply_server_config", "completed")
                if playbook in {"weixin_channel", "full_init"}:
                    login_url = run_weixin_login_probe()
                    if login_url:
                        send_status(
                            ws,
                            device_id,
                            "weixin_channel",
                            "needs_user_action",
                            url=login_url,
                        )

        send_response(ws, device_id, message_id, True)
    except Exception as exc:
        send_response(ws, device_id, message_id, False, str(exc))


def connect_loop(hub_base: str, device_id: str, secret: str, product: str) -> None:
    if websocket is None:
        raise RuntimeError("websocket-client package required: pip install websocket-client")

    backoff = 1
    while True:
        try:
            ticket = request_ticket(hub_base, device_id, secret)
            url = ws_url(hub_base, device_id, ticket)
            ws = websocket.create_connection(url, timeout=30)
            ws.send(
                json.dumps(
                    {
                        "type": "hello",
                        "deviceId": device_id,
                        "payload": {
                            "product": product,
                            "phase": "waiting_for_config",
                            "version": "provisiond-1.0.0",
                        },
                    }
                )
            )
            backoff = 1
            while True:
                ws.settimeout(5)
                try:
                    raw = ws.recv()
                except Exception:
                    continue
                if not raw:
                    break
                msg = json.loads(raw)
                msg_type = msg.get("type")
                if msg_type == "request_hello":
                    ws.send(
                        json.dumps(
                            {
                                "type": "hello",
                                "deviceId": device_id,
                                "payload": {
                                    "product": product,
                                    "phase": "waiting_for_config",
                                    "version": "provisiond-1.0.0",
                                },
                            }
                        )
                    )
                elif msg_type == "configure":
                    payload = msg.get("payload") or {}
                    if isinstance(payload, str):
                        payload = json.loads(payload)
                    handle_configure(ws, device_id, str(msg.get("messageId") or ""), payload, product)
        except Exception as exc:
            print(f"provisiond reconnect in {backoff}s: {exc}", file=sys.stderr)
            time.sleep(backoff)
            backoff = min(backoff * 2, 60)


def main() -> None:
    parser = argparse.ArgumentParser(description="800claw provisiond")
    parser.add_argument("--hub", default=os.environ.get("HUB_BASE_URL", "http://127.0.0.1:8060"))
    parser.add_argument("--product", default=os.environ.get("CLAWBOT_PRODUCT", PRODUCT_ARMBIAN))
    parser.add_argument("--device-id-path", type=Path, default=DEFAULT_DEVICE_ID_PATH)
    parser.add_argument("--device-secret-path", type=Path, default=DEFAULT_DEVICE_SECRET_PATH)
    args = parser.parse_args()

    device_id, secret = load_identity(args.device_id_path, args.device_secret_path)
    connect_loop(args.hub, device_id, secret, args.product)


if __name__ == "__main__":
    main()
