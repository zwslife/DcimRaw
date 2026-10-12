# -*- coding: utf-8 -*-
"""最小 TIFF/IFD 解析器（CR3 CMT 盒专用）——Kotlin 实现的对照原型。"""
import struct
import sys

TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8}
TAG_NAMES = {
    0x010F: "Make", 0x0110: "Model", 0x0132: "DateTime",
    0x829A: "ExposureTime", 0x829D: "FNumber", 0x8827: "ISO",
    0x9003: "DateTimeOriginal", 0x920A: "FocalLength", 0x9209: "Flash",
    0xA403: "WhiteBalance", 0xA434: "LensModel", 0xA405: "FocalLengthIn35mm",
    0x8822: "ExposureProgram", 0x0100: "ImageWidth", 0x0101: "ImageLength",
    0x9204: "ExposureBias", 0x9207: "MeteringMode",
}


def parse_tiff(data: bytes):
    if data[:2] != b"II" or struct.unpack_from("<H", data, 2)[0] != 42:
        return None
    out = {}

    def read_ifd(offset):
        if offset + 2 > len(data):
            return
        count = struct.unpack_from("<H", data, offset)[0]
        p = offset + 2
        for _ in range(count):
            if p + 12 > len(data):
                return
            tag, typ, cnt = struct.unpack_from("<HHI", data, p)
            size = TYPE_SIZE.get(typ, 1) * cnt
            if size <= 4:
                raw = data[p + 8: p + 8 + 4][:size]
            else:
                voff = struct.unpack_from("<I", data, p + 8)[0]
                raw = data[voff: voff + size] if voff + size <= len(data) else b""
            out[tag] = decode(typ, cnt, raw)
            p += 12

    def decode(typ, cnt, raw):
        try:
            if typ == 2:
                return raw.rstrip(b"\x00").decode("ascii", "ignore")
            if typ == 3:
                vals = struct.unpack_from("<%dH" % cnt, raw)
            elif typ == 4:
                vals = struct.unpack_from("<%dI" % cnt, raw)
            elif typ == 5:
                vals = [(struct.unpack_from("<II", raw, i * 8)) for i in range(cnt)]
            else:
                return raw
            return vals if cnt > 1 else vals[0]
        except Exception:
            return None

    read_ifd(struct.unpack_from("<I", data, 4)[0])
    return out


def load_cmt(path, want):
    f = open(path, "rb")
    f.seek(24)
    moov_size, _ = struct.unpack(">I4s", f.read(8))
    pos = 32
    end = 24 + moov_size
    while pos + 8 <= end:
        f.seek(pos)
        s, t = struct.unpack(">I4s", f.read(8))
        t = t.decode("latin1")
        if t == "uuid":
            upos = pos + 8 + 16
            uend = pos + s
            p = upos
            while p + 8 <= uend:
                f.seek(p)
                s2, t2 = struct.unpack(">I4s", f.read(8))
                if t2.decode("latin1") == want:
                    return f.read(s2 - 8)
                p += s2
            return None
        pos += s
    return None


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\Administrator\WorkBuddy\DcimRaw\analysis\r10_sample.CR3"
    for box in ("CMT1", "CMT2"):
        data = load_cmt(path, box)
        tags = parse_tiff(data) if data else None
        print(f"=== {box} ===")
        if not tags:
            print("  (解析失败)")
            continue
        for tid, name in TAG_NAMES.items():
            if tid in tags:
                print(f"  {name} = {tags[tid]}")
