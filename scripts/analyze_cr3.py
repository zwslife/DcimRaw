# -*- coding: utf-8 -*-
"""CR3 (ISO BMFF) 结构分析 + 内嵌 JPEG 预览提取原型。

验证目标:
1. 确认 ftyp 品牌为 crx (CR3)
2. 枚举 moov 下的 trak, 找到全尺寸 JPEG 预览轨 (stsd=jpeg)
3. 按 stco/co64 + stsz 提取预览样本, 落盘验证可正常打开
4. 提取 PRVW / THMB 盒内预览 (兜底路径)
5. 估算 RAW/C-RAW 压缩类型 (按文件大小与轨道信息启发式判断)
"""
import json
import os
import struct
import sys

SRC = r"C:\Users\Administrator\WorkBuddy\DcimRaw\IMG_7412.CR3"
OUT_DIR = r"C:\Users\Administrator\WorkBuddy\DcimRaw\analysis"

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"edts", b"udta", b"dinf"}


def parse_boxes(f, end, depth=0):
    nodes = []
    while f.tell() < end - 7:
        start = f.tell()
        raw = f.read(8)
        if len(raw) < 8:
            break
        size, typ = struct.unpack(">I4s", raw)
        hdr = 8
        if size == 1:
            size = struct.unpack(">Q", f.read(8))[0]
            hdr = 16
        elif size == 0:
            size = end - start
        if size < hdr:
            break
        node = {"type": typ.decode("latin1"), "start": start, "size": size, "hdr": hdr, "children": []}
        if typ in CONTAINERS:
            f.seek(start + hdr)
            node["children"] = parse_boxes(f, start + size, depth + 1)
        nodes.append(node)
        f.seek(start + size)
    return nodes


def find_all(nodes, typ):
    hit = []
    for n in nodes:
        if n["type"] == typ:
            hit.append(n)
        hit.extend(find_all(n["children"], typ))
    return hit


def child(node, typ):
    for c in node["children"]:
        if c["type"] == typ:
            return c
    return None


def payload(f, node, limit=None):
    f.seek(node["start"] + node["hdr"])
    n = node["size"] - node["hdr"]
    return f.read(n if limit is None else min(n, limit))


def parse_trak(f, trak):
    info = {}
    tkhd = child(trak, "tkhd")
    if tkhd:
        data = payload(f, tkhd)
        w, h = struct.unpack(">II", data[-8:])
        info["width"], info["height"] = w >> 16, h >> 16
    mdia = child(trak, "mdia")
    if mdia:
        hdlr = child(mdia, "hdlr")
        if hdlr:
            d = payload(f, hdlr, 16)
            info["handler"] = d[8:12].decode("latin1")
        minf = child(mdia, "minf")
        stbl = child(minf, "stbl") if minf else None
        if stbl:
            stsd = child(stbl, "stsd")
            if stsd:
                d = payload(f, stsd, 20)
                info["stsd_format"] = d[12:16].decode("latin1")
                info["stsd_entries"] = struct.unpack(">I", d[4:8])[0]
            stsz = child(stbl, "stsz")
            if stsz:
                d = payload(f, stsz, 12 + 4 * 8)
                ss, cnt = struct.unpack(">II", d[4:12])
                info["sample_count"] = cnt
                if ss:
                    info["sample_sizes"] = [ss] * cnt
                else:
                    full = payload(f, stsz)
                    info["sample_sizes"] = list(struct.unpack(">%dI" % cnt, full[12:12 + 4 * cnt]))
            for t in ("stco", "co64"):
                stc = child(stbl, t)
                if stc:
                    d = payload(f, stc)
                    cnt = struct.unpack(">I", d[4:8])[0]
                    fmt, w = ((">%dI" % cnt), 4) if t == "stco" else ((">%dQ" % cnt), 8)
                    info["chunk_offsets"] = list(struct.unpack(fmt, d[8:8 + w * cnt]))
                    break
    return info


def extract_jpeg_region(f, offset, size, out_path):
    f.seek(offset)
    data = f.read(size)
    soi = data.find(b"\xff\xd8")
    if soi < 0:
        return None
    eoi = data.rfind(b"\xff\xd9")
    data = data[soi:eoi + 2 if eoi >= 0 else len(data)]
    with open(out_path, "wb") as o:
        o.write(data)
    return len(data)


def jpeg_dims(path):
    with open(path, "rb") as f:
        data = f.read(2)
        if data != b"\xff\xd8":
            return None
        while True:
            hdr = f.read(2)
            if len(hdr) < 2 or hdr[0] != 0xFF:
                return None
            marker = hdr[1]
            if marker in (0xC0, 0xC1, 0xC2, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                f.read(3)
                h, w = struct.unpack(">HH", f.read(4))
                return w, h
            if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
                continue
            seg = f.read(2)
            if len(seg) < 2:
                return None
            ln = struct.unpack(">H", seg)[0]
            f.seek(ln - 2, 1)


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else SRC
    os.makedirs(OUT_DIR, exist_ok=True)
    report = {"file": src, "size_mb": round(os.path.getsize(src) / 1048576, 1)}
    f = open(src, "rb")
    top = parse_boxes(f, os.path.getsize(src))
    report["top_boxes"] = [(n["type"], n["size"]) for n in top]

    ftyp = find_all(top, "ftyp")
    if ftyp:
        d = payload(f, ftyp[0], 16)
        report["major_brand"] = d[:4].decode("latin1")
        report["compatible"] = d[8:16].decode("latin1")

    moov = find_all(top, "moov")[0]
    traks = find_all(moov["children"], "trak")
    trak_infos = [parse_trak(f, t) for t in traks]
    report["traks"] = trak_infos

    # 选全尺寸预览轨：stsd 类型在新固件上统一为 'CRAW'，不可靠，
    # 必须读取每个轨道样本头两个字节，以 JPEG 魔数 FF D8 判定
    def is_jpeg_trak(t):
        offs = t.get("chunk_offsets")
        if not offs:
            return False
        f.seek(offs[0])
        return f.read(2) == b"\xff\xd8"

    cands = [(i, t) for i, t in enumerate(trak_infos) if is_jpeg_trak(t)]
    report["jpeg_trak_count"] = len(cands)
    if cands:
        i, best = max(cands, key=lambda x: x[1].get("width", 0) * x[1].get("height", 0))
        out = os.path.join(OUT_DIR, "preview_full.jpg")
        n = extract_jpeg_region(f, best["chunk_offsets"][0], best["sample_sizes"][0], out)
        report["preview_full"] = {"trak": i, "dims": best.get("width"), "x": best.get("height"),
                                   "bytes": n, "actual_dims": jpeg_dims(out) if n else None}

    # PRVW / THMB 兜底
    for name in ("PRVW", "THMB"):
        for box in find_all(top, name):
            d = payload(f, box)
            soi, eoi = d.find(b"\xff\xd8"), d.rfind(b"\xff\xd9")
            if soi >= 0:
                out = os.path.join(OUT_DIR, name.lower() + ".jpg")
                with open(out, "wb") as o:
                    o.write(d[soi:eoi + 2])
                report[name.lower()] = {"bytes": eoi + 2 - soi, "actual_dims": jpeg_dims(out)}

    raw_traks = [t for t in trak_infos if t.get("stsd_format", "").upper().startswith("CRX") or
                 t.get("stsd_format", "") == "crx "]
    if raw_traks:
        report["raw_trak_format"] = raw_traks[0].get("stsd_format")
    sz = report["size_mb"]
    report["compression_guess"] = "C-RAW(有损压缩)" if sz < 20 else "RAW(无损压缩)"

    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
