package com.dcimgallery.raw.core.raw

import android.graphics.Bitmap

/**
 * LibRaw JNI 解码入口。
 * native 库名 dcimraw，实现见 app/src/main/cpp/raw_bridge.cpp。
 */
object RawDecoder {

    init {
        System.loadLibrary("dcimraw")
    }

    /**
     * 完整解码 CR3 为 ARGB_8888 Bitmap（相机白平衡、sRGB、自动亮度）。
     *
     * @param fd 已 detach 的文件描述符，所有权移交 native 层
     *           （成功由 LibRaw 关闭，失败由 native 关闭），调用方不得再使用
     * @param halfSize 半尺寸快速解码（预留）
     * @return 解码结果；失败返回 null
     */
    external fun decodeFd(fd: Int, halfSize: Boolean): Bitmap?

    // ---- 调色渲染引擎 ----

    /** 从解码 Bitmap 创建调色会话（内置 ~maxDim 小尺寸预览源），返回句柄（0 = 失败）。 */
    external fun createToneSession(bitmap: Bitmap, maxDim: Int): Long

    external fun destroyToneSession(handle: Long)

    /** 会话预览源尺寸 [w, h]。 */
    external fun tonePreviewSize(handle: Long): IntArray

    /** 用预览源快速渲染（滑杆实时反馈），写入 out Bitmap。 */
    external fun renderTonePreview(
        handle: Long,
        matrix: FloatArray,
        lut: FloatArray,
        hsl: FloatArray,
        out: Bitmap,
    ): Boolean

    /** 全尺寸渲染（定稿展示与导出），src 为解码原图，dst 为同尺寸目标。 */
    external fun renderToneFull(
        src: Bitmap,
        matrix: FloatArray,
        lut: FloatArray,
        hsl: FloatArray,
        dst: Bitmap,
    ): Boolean

    /** 对尚未发布的调色结果应用蒙版，覆盖率会按像素中心双线性采样。 */
    external fun applyMasks(
        bitmap: Bitmap, matrices: FloatArray, luts: FloatArray, hsls: FloatArray,
        coverage: ByteArray, maskWidth: Int, maskHeight: Int,
    ): Boolean
}
