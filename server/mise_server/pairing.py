"""LAN address discovery and the pairing QR code (mise://pair?url=…&token=…)."""

from __future__ import annotations

import html
import io
import socket
from urllib.parse import quote

import segno


def lan_ips() -> list[str]:
    """Best-guess IPv4 addresses other devices on the LAN can reach, primary first."""
    ips: list[str] = []
    try:
        # No packets are sent: connecting a UDP socket just picks the outbound interface.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 80))
            ips.append(s.getsockname()[0])
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    real = [ip for ip in ips if not ip.startswith("127.")]
    return real or ["127.0.0.1"]


def pairing_uri(server_url: str, token: str) -> str:
    return f"mise://pair?url={quote(server_url, safe='')}&token={quote(token, safe='')}"


def terminal_qr(data: str) -> str:
    buf = io.StringIO()
    segno.make(data, error="m").terminal(out=buf, compact=True, border=2)
    return buf.getvalue()


def svg_qr(data: str) -> str:
    buf = io.BytesIO()
    segno.make(data, error="m").save(buf, kind="svg", scale=8, border=2, dark="#111", light="#fff")
    return buf.getvalue().decode("utf-8")


def pairing_page(uri: str) -> str:
    """Shown at http://localhost:<port>/ on the PC itself (never to other devices)."""
    svg = svg_qr(uri)
    return f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Pair Mise</title>
<style>body{{font-family:system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem;color:#222}}
code{{word-break:break-all;background:#f3f3f3;padding:.1rem .3rem;border-radius:4px}}svg{{width:100%;height:auto}}</style>
</head><body><h1>Pair your phone</h1>
<p>In the Mise app open <b>Settings → Pair with PC</b> and scan this code
(or point the phone camera at it).</p>
{svg}
<p>Manual entry: <code>{html.escape(uri)}</code></p>
<p>Keep this page private: the code contains your access token.</p></body></html>"""
