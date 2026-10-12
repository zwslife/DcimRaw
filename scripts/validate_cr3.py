# -*- coding: utf-8 -*-
"""用 rawpy（LibRaw 官方 Python 绑定）对 CR3 做完整 RAW 解码验证。

输出:
- 解码参数与耗时（评估手机端 M3 集成的性能预期）
- decoded_full.jpg 全尺寸解码结果（目视验证画质）
"""
import json
import os
import sys
import time

import rawpy

OUT_DIR = r"C:\Users\Administrator\WorkBuddy\DcimRaw\analysis"


def main(path: str):
    os.makedirs(OUT_DIR, exist_ok=True)
    t0 = time.perf_counter()
    raw = rawpy.imread(path)
    t1 = time.perf_counter()
    sizes = raw.sizes
    info = {
        "file": path,
        "size_mb": round(os.path.getsize(path) / 1048576, 1),
        "camera": getattr(raw, "camera_whitebalance", None) is not None,
        "raw_type": str(raw.raw_type),
        "sizes": {
            "raw_height": sizes.raw_height, "raw_width": sizes.raw_width,
            "height": sizes.height, "width": sizes.width,
            "top_margin": sizes.top_margin, "left_margin": sizes.left_margin,
        },
        "color_desc": raw.color_desc.decode("latin1") if isinstance(raw.color_desc, bytes) else str(raw.color_desc),
        "num_colors": raw.num_colors,
        "open_seconds": round(t1 - t0, 2),
    }
    t2 = time.perf_counter()
    rgb = raw.postprocess(
        use_camera_wb=True,
        no_auto_bright=False,
        output_bps=8,
    )
    t3 = time.perf_counter()
    info["postprocess_seconds"] = round(t3 - t2, 2)
    info["rgb_shape"] = list(rgb.shape)

    from PIL import Image
    out = os.path.join(OUT_DIR, "decoded_full.jpg")
    Image.fromarray(rgb).save(out, quality=92)
    info["decoded_jpg"] = out
    info["decoded_jpg_mb"] = round(os.path.getsize(out) / 1048576, 2)
    print(json.dumps(info, ensure_ascii=False, indent=2))
    raw.close()


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\Administrator\WorkBuddy\DcimRaw\IMG_7412.CR3"
    main(target)
