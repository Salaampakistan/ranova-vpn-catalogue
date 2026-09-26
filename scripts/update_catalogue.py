#!/usr/bin/env python3
import base64
import csv
import hashlib
import io
import ipaddress
import json
import re
import sys
import urllib.request
from datetime import datetime, timezone

SOURCE = "https://www.vpngate.net/api/iphone/"
OUTPUT = "servers.json"
MAX_SERVERS = 60
MAX_CONFIG_BYTES = 200_000

DENY_DIRECTIVES = {
    "up", "down", "script-security", "plugin", "management",
    "http-proxy", "socks-proxy", "auth-user-pass", "config",
    "route-up", "ipchange", "client-connect", "client-disconnect"
}

def fetch():
    req = urllib.request.Request(
        SOURCE,
        headers={
            "User-Agent": "RANOVA-VPN-Catalogue/1.0",
            "Accept": "text/csv,text/plain,*/*",
        },
    )
    with urllib.request.urlopen(req, timeout=35) as r:
        data = r.read(8_000_000)
    return data

def parse_int(value, default=0):
    try:
        return int(str(value).strip())
    except Exception:
        return default

def decode_config(b64):
    raw = base64.b64decode(b64, validate=True)
    if not raw or len(raw) > MAX_CONFIG_BYTES or b"\x00" in raw:
        raise ValueError("bad config size/content")
    return raw.decode("utf-8", errors="strict")

def validate_config(config, expected_ip):
    lines = []
    for raw in config.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith(";"):
            continue
        lines.append(line)

    low = [x.lower() for x in lines]
    if "client" not in low:
        raise ValueError("missing client")
    if not any(x == "dev tun" or x.startswith("dev tun") for x in low):
        raise ValueError("not TUN")

    for line in low:
        first = line.split(None, 1)[0]
        if first in DENY_DIRECTIVES:
            raise ValueError(f"unsafe directive: {first}")

    proto = None
    for line in low:
        if line.startswith("proto "):
            p = line.split(None, 1)[1].strip()
            if p in {"udp", "udp4"}:
                proto = "udp"
            elif p in {"tcp", "tcp-client", "tcp4", "tcp4-client"}:
                proto = "tcp"
            else:
                raise ValueError("unsupported proto")
            break
    if not proto:
        raise ValueError("missing proto")

    remote_ip = None
    remote_port = None
    for line in lines:
        m = re.match(r"(?i)^remote\s+(\S+)\s+(\d+)(?:\s+\S+)?$", line)
        if m:
            remote_ip = m.group(1)
            remote_port = int(m.group(2))
            break
    if not remote_ip or not (1 <= remote_port <= 65535):
        raise ValueError("missing/invalid remote")
    if remote_ip != expected_ip:
        raise ValueError("remote mismatch")

    return proto, remote_port

def parse_feed(data):
    text = data.decode("utf-8-sig", errors="strict")
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")

    header_idx = None
    for i, line in enumerate(lines):
        if line.startswith("#HostName,"):
            header_idx = i
            break
    if header_idx is None:
        raise RuntimeError("VPN Gate CSV header not found")

    csv_text = "\n".join(
        line for line in lines[header_idx:]
        if line and not line.startswith("*")
    )
    reader = csv.DictReader(io.StringIO(csv_text))
    if not reader.fieldnames:
        raise RuntimeError("CSV has no header")
    reader.fieldnames = [h.lstrip("#").strip() for h in reader.fieldnames]

    out = []
    seen = set()

    for row in reader:
        try:
            ip = (row.get("IP") or "").strip()
            ip_obj = ipaddress.ip_address(ip)
            if ip_obj.version != 4 or not ip_obj.is_global:
                continue

            b64 = (row.get("OpenVPN_ConfigData_Base64") or "").strip()
            if not b64:
                continue

            config = decode_config(b64)
            proto, port = validate_config(config, ip)

            key = (ip, port, proto)
            if key in seen:
                continue
            seen.add(key)

            item = {
                "hostname": (row.get("HostName") or "").strip(),
                "ip": ip,
                "port": port,
                "protocol": proto,
                "country": (row.get("CountryLong") or "").strip(),
                "country_code": (row.get("CountryShort") or "").strip().upper(),
                "score": parse_int(row.get("Score")),
                "ping_ms": parse_int(row.get("Ping"), -1),
                "speed_bps": parse_int(row.get("Speed")),
                "sessions": parse_int(row.get("NumVpnSessions")),
                "uptime_ms": parse_int(row.get("Uptime")),
                "log_type": (row.get("LogType") or "").strip(),
                "operator": (row.get("Operator") or "").strip(),
                "openvpn_config_base64": b64,
            }
            out.append(item)
        except Exception:
            continue

    out.sort(key=lambda x: (x["score"], x["speed_bps"]), reverse=True)
    return out[:MAX_SERVERS]

def main():
    data = fetch()
    servers = parse_feed(data)
    if not servers:
        raise RuntimeError("No valid OpenVPN profiles were produced")

    payload_core = {
        "schema": 1,
        "source": "VPN Gate Public VPN Relay Servers",
        "source_url": SOURCE,
        "count": len(servers),
        "servers": servers,
    }
    canonical = json.dumps(payload_core, sort_keys=True, separators=(",", ":")).encode()
    source_hash = hashlib.sha256(canonical).hexdigest()

    payload = {
        "schema": 1,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "catalogue_sha256": source_hash,
        "source": payload_core["source"],
        "source_url": SOURCE,
        "count": len(servers),
        "servers": servers,
    }

    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")

    print(f"Wrote {len(servers)} validated servers to {OUTPUT}")
    print(f"Catalogue SHA-256: {source_hash}")

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"ERROR: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)
