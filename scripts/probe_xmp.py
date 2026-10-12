# -*- coding: utf-8 -*-
"""探查 CR3 的 XMP uuid 盒：确认拍摄参数字段结构，为 Kotlin 实现定稿。"""
import re
import struct
import sys

XMP_UUID = "be7acfcb97a942e89c71999491e3afac"
PRVW_UUID = "eaf42b5e1c984b88b9fbb7dc406e4d16"

path = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\Administrator\WorkBuddy\DcimRaw\analysis\r10_sample.CR3"
f = open(path, "rb")

pos = 0
size_total = 0
import os
size_total = os.path.getsize(path)

while pos + 8 <= size_total:
    f.seek(pos)
    raw = f.read(8)
    size, typ = struct.unpack(">I4s", raw)
    hdr = 8
    if size == 1:
        size = struct.unpack(">Q", f.read(8))[0]
        hdr = 16
    t = typ.decode("latin1")
    if t == "uuid":
        f.seek(pos + hdr)
        uuid = f.read(16).hex()
        if uuid == XMP_UUID:
            payload = f.read(size - hdr - 16)
            text = payload.decode("utf-8", "ignore")
            print("=== XMP 长度:", len(text))
            # 打印 rdf:Description 的属性片段和关键字段行
            for m in re.finditer(r"<rdf:Description[^>]*>", text):
                seg = m.group(0)
                for attr in re.findall(r"(\w+:\w+)=\"([^\"]*)\"", seg):
                    print(f"  {attr[0]} = {attr[1]}")
            for tag in ["FNumber", "ExposureTime", "ISOSpeedRatings", "FocalLength",
                        "DateTimeOriginal", "WhiteBalance", "Flash", "Lens", "Model", "Make",
                        "PixelXDimension", "PixelYDimension", "ExposureProgram"]:
                for m in re.finditer(r"<(?:exif|tiff|aux|xmp|crs):" + tag + r"[^/>]*>([^<]{0,80})", text):
                    print(f"  <{tag}> = {m.group(1).strip()}")
                for m in re.finditer(r"(?:exif|tiff|aux|xmp|crs):" + tag + r"=\"([^\"]*)\"", text):
                    pass  # 已在上面属性段打印
        if uuid == PRVW_UUID:
            payload = f.read(size - hdr - 16)
            soi = payload.find(b"\xff\xd8")
            print(f"=== PRVW 盒 {len(payload)} 字节, JPEG 起始偏移 {soi}")
            if soi >= 0:
                out = r"C:\Users\Administrator\WorkBuddy\DcimRaw\analysis\prvw.jpg"
                open(out, "wb").write(payload[soi:payload.rfind(b"\xff\xd9") + 2])
                print("  已导出 analysis/prvw.jpg")
    if t == "mdat":
        break
    pos += size
print("done")
