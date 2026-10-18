"""LAN address discovery and the pairing QR code (mise://pair?url=…&token=…)."""

from __future__ import annotations

import html
import io
import ipaddress
import re
import socket
from dataclasses import dataclass
from urllib.parse import quote

import segno


# Adapters that are almost never how a phone reaches this PC: WSL / Hyper-V, Docker, VMs, VPNs.
_VIRTUAL = re.compile(
    r"vethernet|hyper-v|wsl|docker|veth|br-|virbr|vmware|vmnet|virtualbox|vbox|host-only|"
    r"tailscale|zerotier|wireguard|wg\d|tun|tap|utun|npcap|loopback|bluetooth",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Candidate:
    ip: str
    adapter: str
    score: int  # lower is better

    @property
    def likely_virtual(self) -> bool:
        return self.score >= 10


def _score(ip: str, adapter: str, route_ip: str | None) -> int:
    a = ipaddress.ip_address(ip)
    if ip.startswith("192.168."):
        score = 0  # typical home router range
    elif ip.startswith("10."):
        score = 1
    elif a in ipaddress.ip_network("172.16.0.0/12"):
        score = 3  # used by home routers too, but also by WSL, Hyper-V and Docker
    else:
        score = 5
    if _VIRTUAL.search(adapter):
        score += 10
    if ip == route_ip:
        score -= 1  # tie-break towards the interface the default route uses
    return score


def _route_ip() -> str | None:
    try:
        # No packets are sent: connecting a UDP socket just picks the outbound interface.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 80))
            return s.getsockname()[0]
    except OSError:
        return None


def _adapters() -> list[tuple[str, str]]:
    """(ip, adapter name) for every IPv4 address on this machine."""
    out: list[tuple[str, str]] = []
    try:
        import ifaddr

        for adapter in ifaddr.get_adapters():
            for ip in adapter.ips:
                if isinstance(ip.ip, str):  # IPv4 (IPv6 entries are tuples)
                    out.append((ip.ip, adapter.nice_name or adapter.name))
    except Exception:  # pragma: no cover - fall back to what the socket module knows
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
                out.append((info[4][0], ""))
        except OSError:
            pass
    return out


def lan_candidates(adapters: list[tuple[str, str]] | None = None, route_ip: str | None = None) -> list[Candidate]:
    """Every address a phone might use to reach this PC, best guess first."""
    if adapters is None:
        adapters, route_ip = _adapters(), _route_ip()
        if route_ip and route_ip not in {ip for ip, _ in adapters}:
            adapters.append((route_ip, ""))
    seen: dict[str, Candidate] = {}
    for ip, name in adapters:
        a = ipaddress.ip_address(ip)
        if a.is_loopback or a.is_link_local or a.is_unspecified:
            continue
        c = Candidate(ip, name, _score(ip, name, route_ip))
        if ip not in seen or c.score < seen[ip].score:
            seen[ip] = c
    return sorted(seen.values(), key=lambda c: (c.score, c.ip))


def lan_ips() -> list[str]:
    """Best-guess IPv4 addresses other devices on the LAN can reach, primary first."""
    return [c.ip for c in lan_candidates()] or ["127.0.0.1"]


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


def pairing_page(options: list[tuple[str, str]]) -> str:
    """Shown at http://localhost:<port>/ on the PC itself (never to other devices).

    `options` is [(label, pairing uri)], best guess first. Every candidate address gets its own QR
    code, so if the first one doesn't work from the phone you can scan the next.
    """
    blocks = []
    for i, (label, uri) in enumerate(options):
        heading = "Scan this" if i == 0 else "Or try this one"
        blocks.append(
            f"<section><h2>{heading}: {html.escape(label)}</h2>{svg_qr(uri)}"
            f"<p>Manual entry: <code>{html.escape(uri)}</code></p></section>"
        )
    return f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Pair Mise</title>
<style>body{{font-family:system-ui,sans-serif;max-width:32rem;margin:2rem auto;padding:0 1rem;color:#222}}
code{{word-break:break-all;background:#f3f3f3;padding:.1rem .3rem;border-radius:4px}}svg{{width:100%;height:auto}}
section{{border-top:1px solid #ddd;margin-top:1.5rem}}h2{{font-size:1.1rem}}</style>
</head><body><h1>Pair your phone</h1>
<p>In the Mise app open <b>Settings → Pair with PC</b> and scan a code (or point the phone camera at it).
Use the address on the same network as your phone — usually your Wi-Fi or Ethernet adapter, often
<code>192.168.x.x</code>. Addresses on "vEthernet", WSL, Docker or VPN adapters won't work from the phone.</p>
{"".join(blocks)}
<p>Keep this page private: the codes contain your access token.</p></body></html>"""
