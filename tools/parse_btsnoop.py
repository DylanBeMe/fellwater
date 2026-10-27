#!/usr/bin/env python3
"""Decode a btsnoop_hci.log into an ATT timeline.

Prints, in order:
  * attribute discovery (services / characteristics and their handles)
  * every ATT write and notification, as handle + hex

Filter to one connection with --handle-prefix or grep the output for the
console's characteristic handles.

usage: parse_btsnoop.py btsnoop_hci.log [--all] [--skip-acks]
"""

import struct
import sys
from collections import OrderedDict

ATT_OPS = {
    0x01: "ERROR_RSP",
    0x02: "MTU_REQ",
    0x03: "MTU_RSP",
    0x04: "FIND_INFO_REQ",
    0x05: "FIND_INFO_RSP",
    0x08: "READ_BY_TYPE_REQ",
    0x09: "READ_BY_TYPE_RSP",
    0x0A: "READ_REQ",
    0x0B: "READ_RSP",
    0x10: "READ_BY_GROUP_REQ",
    0x11: "READ_BY_GROUP_RSP",
    0x12: "WRITE_REQ",
    0x13: "WRITE_RSP",
    0x16: "PREPARE_WRITE_REQ",
    0x18: "EXEC_WRITE_REQ",
    0x1B: "NOTIFY",
    0x1D: "INDICATE",
    0x1E: "CONFIRM",
    0x52: "WRITE_CMD",
}

# GATT declarations
DECL_PRIMARY = 0x2800
DECL_CHAR = 0x2803


def records(path):
    data = open(path, "rb").read()
    if data[:8] != b"btsnoop\x00":
        raise SystemExit("not a btsnoop file")
    version, datalink = struct.unpack_from(">II", data, 8)
    off = 16
    while off + 24 <= len(data):
        orig, incl, flags, drops, ts = struct.unpack_from(">IIIIQ", data, off)
        off += 24
        payload = data[off:off + incl]
        off += incl
        yield flags & 0x01, ts, payload


def att_from_acl(pkt):
    # pkt[0] is the H4 packet type; the HCI ACL header starts at offset 1.
    if len(pkt) < 6:
        return None
    _, dlen = struct.unpack_from("<HH", pkt, 1)
    body = pkt[5:5 + dlen]
    if len(body) < 4:
        return None
    l2len, cid = struct.unpack_from("<HH", body, 0)
    if cid != 0x0004:  # ATT
        return None
    return body[4:4 + l2len]


def u16(b, i):
    return struct.unpack_from("<H", b, i)[0]


def main():
    path = sys.argv[1]
    show_all = "--all" in sys.argv
    skip_acks = "--skip-acks" in sys.argv

    handles = OrderedDict()   # handle -> label
    pending_uuid = {}
    events = []

    for direction, ts, pkt in records(path):
        if not pkt or pkt[0] != 0x02:      # ACL only
            continue
        att = att_from_acl(pkt)
        if not att or len(att) < 1:
            continue
        op = att[0]
        arrow = "host>" if direction == 0 else "dev<"

        if op == 0x11 and len(att) >= 6:   # READ_BY_GROUP_RSP: services
            i = 2
            while i + 4 <= len(att):
                h = u16(att, i)
                uuid = att[i + 2:i + 6]
                us = uuid[::-1].hex() if len(uuid) == 4 else uuid.hex()
                gid = DECL_PRIMARY if len(att) and att[1] == 6 else DECL_CHAR
                handles[h] = f"SERVICE 0x{us}"
                i += 6 if att[1] == 6 else 4

        elif op == 0x09 and len(att) >= 2:  # READ_BY_TYPE_RSP: characteristics
            i = 2
            while i + 2 <= len(att):
                h = u16(att, i)
                val = att[i + 2:i + att[1] + 2]
                if len(val) >= 5 and val[0] == 0x08:      # properties=READ, uuid16
                    uuid = val[3:7][::-1].hex()
                    handles[h] = f"CHAR    0x{uuid}"
                i += att[1]

        elif op in (0x12, 0x52) and len(att) >= 3:
            h = u16(att, 1)
            events.append((arrow, h, att[3:], "write" if op == 0x12 else "write-cmd"))

        elif op in (0x1B, 0x1D) and len(att) >= 3:
            h = u16(att, 1)
            events.append((arrow, h, att[3:], "notify" if op == 0x1B else "indicate"))

        elif show_all:
            events.append((arrow, None, att, ATT_OPS.get(op, f"op{op:02x}")))

    if handles:
        print("=== attributes discovered ===")
        for h, label in handles.items():
            print(f"  0x{h:04x}  {label}")
        print()

    print("=== ATT traffic (writes and notifications) ===")
    for arrow, h, value, kind in events:
        hexval = " ".join(f"{b:02x}" for b in value)
        # Pure ATT-layer acks (03 f0 from the console) are noise when filtered out.
        if skip_acks and value[:2] == b"\x03\xf0" and len(value) == 2:
            continue
        hs = f"0x{h:04x}" if h is not None else "  -   "
        label = handles.get(h, "") if h is not None else ""
        print(f"  {arrow} {hs} {kind:9} {hexval}   {label}")


if __name__ == "__main__":
    main()
