"""Picking the address for the pairing QR code — virtual adapters must not win."""

from mise_server.__main__ import choose_address
from mise_server.pairing import lan_candidates

WINDOWS_WITH_WSL = [
    ("127.0.0.1", "Loopback Pseudo-Interface 1"),
    ("172.27.48.1", "vEthernet (WSL (Hyper-V firewall))"),
    ("172.17.0.1", "vEthernet (Default Switch)"),
    ("169.254.12.3", "Bluetooth Network Connection"),
    ("192.168.1.42", "Wi-Fi"),
]


def test_wifi_beats_wsl_even_when_the_route_points_at_wsl():
    # The old logic used the default-route interface, which on some PCs is the WSL switch.
    cands = lan_candidates(WINDOWS_WITH_WSL, route_ip="172.27.48.1")
    assert cands[0].ip == "192.168.1.42" and cands[0].adapter == "Wi-Fi"
    assert {c.ip for c in cands if c.likely_virtual} == {"172.27.48.1", "172.17.0.1"}
    assert all(c.ip not in ("127.0.0.1", "169.254.12.3") for c in cands)


def test_docker_bridge_on_linux_loses_to_ethernet():
    cands = lan_candidates([("172.17.0.1", "docker0"), ("10.0.0.7", "enp3s0")], route_ip=None)
    assert [c.ip for c in cands] == ["10.0.0.7", "172.17.0.1"]


def test_home_network_in_172_range_still_works():
    # Some routers do hand out 172.16-31 addresses; with a real adapter name it should win.
    cands = lan_candidates([("172.20.10.4", "Wi-Fi"), ("172.27.48.1", "vEthernet (WSL)")], route_ip="172.27.48.1")
    assert cands[0].ip == "172.20.10.4"


def test_route_breaks_ties_between_real_adapters():
    cands = lan_candidates([("192.168.1.42", "Wi-Fi"), ("192.168.0.9", "Ethernet")], route_ip="192.168.1.42")
    assert cands[0].ip == "192.168.1.42"


def test_override_wins():
    cands = lan_candidates(WINDOWS_WITH_WSL, route_ip="172.27.48.1")
    host, others = choose_address("192.168.1.99", cands)
    assert host == "192.168.1.99" and len(others) == len(cands)
    host, others = choose_address("", cands)
    assert host == "192.168.1.42" and host not in [c.ip for c in others]
    assert choose_address("", []) == ("127.0.0.1", [])


def test_real_machine_does_not_crash():
    assert isinstance(lan_candidates(), list)
