#!/usr/bin/env python3
"""Generate the 6 facings x 16 power values blockstate for the TCP interface block."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/network-socket/blockstates/tcp_interface.json"

ROTATIONS = {
    "down": {"x": 90},
    "east": {"y": 90},
    "north": {},
    "south": {"y": 180},
    "up": {"x": 270},
    "west": {"y": 270},
}

variants = {}
for facing in ("down", "east", "north", "south", "up", "west"):
    for power in range(16):
        model = ("network-socket:block/tcp_interface_on"
                 if power > 0 else "network-socket:block/tcp_interface")
        entry = {"model": model}
        entry.update(ROTATIONS[facing])
        variants[f"facing={facing},power={power}"] = entry

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps({"variants": variants}, indent=2) + "\n", encoding="utf-8")
print(f"wrote {OUT} ({len(variants)} variants)")