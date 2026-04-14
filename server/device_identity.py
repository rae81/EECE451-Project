"""Device identity resolution.

Maps raw identifiers (device_id, MAC address, IP) observed by the server
into a friendly label, owning user, and best-effort vendor. The label
is used by the dashboard and device-stats templates so the user sees
"Rami's Galaxy S24+ (Samsung)" rather than a UUID.

The OUI → vendor table below is a small curated subset of the IEEE OUI
registry covering common mobile/OEM prefixes. It is embedded (rather
than fetched from ieee.org at runtime) so the server works offline and
has no dependency on the `manuf` / `netaddr` packages.

Full public registry:
    https://standards-oui.ieee.org/oui/oui.csv  (IEEE, public domain)
"""

from __future__ import annotations

import re
from typing import Optional

from models import User, DeviceLog


# ── OUI → vendor (curated, mobile-OEM focused) ────────────────────────
# Keys are the first 3 bytes of a MAC address, uppercased, no separators.
_OUI_VENDOR: dict[str, str] = {
    # Apple
    "3C2EFF": "Apple", "A4B197": "Apple", "F0F61C": "Apple", "DCA904": "Apple",
    "A4C361": "Apple", "001451": "Apple", "002608": "Apple", "AC3C0B": "Apple",
    # Samsung
    "0023D7": "Samsung", "E8508B": "Samsung", "F8042E": "Samsung", "D0176A": "Samsung",
    "08D42B": "Samsung", "38AA3C": "Samsung", "B40B44": "Samsung", "34145F": "Samsung",
    "A0F9E0": "Samsung", "2C0E3D": "Samsung", "04180F": "Samsung",
    # Google / Pixel
    "D8A25E": "Google", "3C286D": "Google", "F8FFC2": "Google", "40A36B": "Google",
    # Xiaomi
    "F0B429": "Xiaomi", "642737": "Xiaomi", "A086C6": "Xiaomi", "584498": "Xiaomi",
    # Huawei
    "00E0FC": "Huawei", "001E10": "Huawei", "BC7670": "Huawei", "00464B": "Huawei",
    # OnePlus
    "94652D": "OnePlus", "64A2F9": "OnePlus",
    # Oppo / realme
    "DC0B34": "Oppo", "40454B": "Oppo",
    # Sony
    "00EB2D": "Sony", "FCE998": "Sony",
    # Motorola / Lenovo
    "001A45": "Motorola", "E0CB4E": "Motorola",
    # Nokia / HMD
    "00E003": "Nokia", "00242B": "Nokia",
    # Localised / randomised MAC bit — any MAC where the 2nd hex of the
    # first byte is 2/6/A/E is a locally-administered (randomised) address.
    # We flag those explicitly in resolve_vendor().
}


_MAC_NORMALISE = re.compile(r"[^0-9A-Fa-f]")


def normalise_mac(mac: Optional[str]) -> Optional[str]:
    """Strip separators and uppercase. Returns None if not 12 hex chars."""
    if not mac:
        return None
    cleaned = _MAC_NORMALISE.sub("", mac).upper()
    if len(cleaned) != 12:
        return None
    return cleaned


def is_locally_administered(mac: str) -> bool:
    """True if the MAC has the locally-administered bit set (randomised)."""
    try:
        second_hex = int(mac[1], 16)
        return bool(second_hex & 0x2)
    except (IndexError, ValueError):
        return False


def format_mac(mac: Optional[str]) -> Optional[str]:
    """Return AA:BB:CC:DD:EE:FF form, or None."""
    norm = normalise_mac(mac)
    if not norm:
        return None
    return ":".join(norm[i:i + 2] for i in range(0, 12, 2))


def resolve_vendor(mac: Optional[str]) -> str:
    """Best-effort vendor name for a MAC. 'Randomised' for LA MACs."""
    norm = normalise_mac(mac)
    if not norm:
        return "Unknown"
    if is_locally_administered(norm):
        return "Randomised (Android privacy MAC)"
    return _OUI_VENDOR.get(norm[:6], "Unknown vendor")


def resolve_device_label(device_id: str, mac: Optional[str] = None) -> dict:
    """Combine user info + vendor into a single friendly descriptor.

    Returns a dict with keys: label, owner, vendor, mac, device_id.
    `label` is what the UI should display as the primary name.
    """
    user = User.query.filter_by(device_id=device_id).first()
    vendor = resolve_vendor(mac)
    formatted_mac = format_mac(mac)

    if user and user.name:
        label = f"{user.name}'s device"
        if vendor not in ("Unknown", "Unknown vendor") and not vendor.startswith("Randomised"):
            label = f"{user.name}'s {vendor}"
        owner = user.name
    else:
        label = vendor if vendor not in ("Unknown", "Unknown vendor") else f"Device {device_id[:8]}"
        owner = None

    return {
        "label": label,
        "owner": owner,
        "vendor": vendor,
        "mac": formatted_mac,
        "device_id": device_id,
    }


def enrich_device_rows(devices: list[dict]) -> list[dict]:
    """Given a list of device dicts (with device_id + mac_address), add
    identity fields: label, owner, vendor, formatted_mac."""
    for row in devices:
        ident = resolve_device_label(row.get("device_id", ""), row.get("mac_address"))
        row["device_label"] = ident["label"]
        row["device_owner"] = ident["owner"]
        row["device_vendor"] = ident["vendor"]
        row["mac_formatted"] = ident["mac"]
    return devices
