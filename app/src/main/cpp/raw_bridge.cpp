#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <vector>
#include <sys/stat.h>
#include <unistd.h>

#include "libraw/libraw.h"

#define LOG_TAG "RawBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

/** 抛出带阶段信息的 Java 异常，便于定位真机问题。 */
jobject failStage(JNIEnv* env, const char* stage, const char* detail) {
    jclass exClass = env->FindClass("java/lang/RuntimeException");
    if (exClass != nullptr) {
        char msg[256];
        snprintf(msg, sizeof(msg), "[%s] %s", stage, detail);
        env->ThrowNew(exClass, msg);
    }
    return nullptr;
}

}  // namespace

namespace {

jobject createBitmap(JNIEnv* env, int width, int height);
jobject failStage(JNIEnv* env, const char* stage, const char* detail);

jobject createBitmap(JNIEnv* env, int width, int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (bitmapClass == nullptr) return nullptr;
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (configClass == nullptr) return nullptr;
    jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888",
                                               "Landroid/graphics/Bitmap$Config;");
    if (argbField == nullptr) return nullptr;
    jobject config = env->GetStaticObjectField(configClass, argbField);
    jmethodID createMid = env->GetStaticMethodID(
        bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (createMid == nullptr) return nullptr;
    return env->CallStaticObjectMethod(bitmapClass, createMid, width, height, config);
}

}  // namespace

/**
 * 解码 CR3 为 ARGB_8888 Bitmap。
 *
 * fd 约定：调用方传入已 detach 的文件描述符，所有权移交本函数
 * （成功时由 LibRaw 关闭，失败时由本函数关闭）。
 *
 * 解码参数与 PC 端 rawpy 验证一致：相机白平衡、sRGB 输出、自动亮度。
 * halfSize=true 时走半尺寸快速解码（未使用，预留）。
 */
extern "C" JNIEXPORT jobject JNICALL
Java_com_dcimgallery_raw_core_raw_RawDecoder_decodeFd(
    JNIEnv* env, jclass /* clazz */, jint fd, jboolean halfSize) {
    if (fd < 0) {
        LOGE("invalid fd");
        return nullptr;
    }

    LibRaw raw;
    raw.imgdata.params.use_camera_wb = 1;
    raw.imgdata.params.output_color = 1;   // sRGB
    raw.imgdata.params.no_auto_bright = 0; // 自动亮度（接近机内观感）
    raw.imgdata.params.half_size = halfSize ? 1 : 0;

    // 首选 /proc/self/fd 零拷贝打开；失败则整文件读入内存走 open_buffer
    //（部分机型 FUSE/SELinux 对 fd 重开有限制，兜底保证可用性）。
    void* buf = nullptr;
    int ret;
    {
        char path[64];
        snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
        ret = raw.open_file(path);
        if (ret != LIBRAW_SUCCESS) {
            struct stat st;
            if (fstat(fd, &st) == 0 && st.st_size > 0) {
                size_t size = static_cast<size_t>(st.st_size);
                buf = malloc(size);
                if (buf != nullptr) {
                    lseek(fd, 0, SEEK_SET);
                    size_t off = 0;
                    while (off < size) {
                        ssize_t n = read(fd, static_cast<char*>(buf) + off, size - off);
                        if (n <= 0) break;
                        off += static_cast<size_t>(n);
                    }
                    if (off == size) {
                        ret = raw.open_buffer(buf, size);
                    } else {
                        free(buf);
                        buf = nullptr;
                    }
                }
            }
        }
        close(fd);
    }
    if (ret != LIBRAW_SUCCESS) {
        free(buf);
        LOGE("open failed: %s", libraw_strerror(ret));
        return failStage(env, "打开文件", libraw_strerror(ret));
    }

    ret = raw.unpack();
    if (ret != LIBRAW_SUCCESS) {
        free(buf);
        LOGE("unpack failed: %s", libraw_strerror(ret));
        return failStage(env, "读取 RAW 数据", libraw_strerror(ret));
    }

    ret = raw.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        free(buf);
        LOGE("dcraw_process failed: %s", libraw_strerror(ret));
        return failStage(env, "RAW 解码", libraw_strerror(ret));
    }

    int err = 0;
    libraw_processed_image_t* img = raw.dcraw_make_mem_image(&err);
    if (img == nullptr || err != LIBRAW_SUCCESS) {
        free(buf);
        LOGE("make_mem_image failed: %d", err);
        return failStage(env, "生成图像", "make_mem_image failed");
    }

    const int width = img->width;
    const int height = img->height;
    if (img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 8) {
        free(buf);
        LOGE("unexpected image format: type=%d colors=%d bits=%d",
             img->type, img->colors, img->bits);
        libraw_dcraw_clear_mem(img);
        return failStage(env, "图像格式", "unexpected pixel format");
    }

    jobject bitmap = createBitmap(env, width, height);
    if (bitmap == nullptr) {
        free(buf);
        LOGE("createBitmap failed (%dx%d)", width, height);
        libraw_dcraw_clear_mem(img);
        return failStage(env, "内存不足", "createBitmap failed");
    }

    // 像素写入走 Java setPixels 语义（int = 0xAARRGGBB，公开文档定义），
    // 彻底绕开 NDK 内存布局歧义；逐行写入控制内存峰值。
    jclass bitmapClass = env->GetObjectClass(bitmap);
    jmethodID setPixels =
        env->GetMethodID(bitmapClass, "setPixels", "([IIIIIII)V");
    if (setPixels == nullptr) {
        free(buf);
        libraw_dcraw_clear_mem(img);
        return failStage(env, "内部错误", "setPixels not found");
    }
    jintArray rowArray = env->NewIntArray(width);
    if (rowArray == nullptr) {
        free(buf);
        libraw_dcraw_clear_mem(img);
        return failStage(env, "内存不足", "rowArray alloc failed");
    }
    std::vector<jint> rowBuf(static_cast<size_t>(width));

    const uint8_t* src = img->data;
    for (int y = 0; y < height; ++y) {
        const uint8_t* s = src + static_cast<size_t>(y) * width * 3;
        for (int x = 0; x < width; ++x) {
            const size_t p = static_cast<size_t>(x) * 3;
            rowBuf[static_cast<size_t>(x)] = static_cast<jint>(
                0xFF000000u |
                (static_cast<uint32_t>(s[p]) << 16) |
                (static_cast<uint32_t>(s[p + 1]) << 8) |
                static_cast<uint32_t>(s[p + 2]));
        }
        env->SetIntArrayRegion(rowArray, 0, width, rowBuf.data());
        env->CallVoidMethod(bitmap, setPixels, rowArray, 0, width, 0, y, width, 1);
    }
    env->DeleteLocalRef(rowArray);

    libraw_dcraw_clear_mem(img);
    free(buf);
    LOGI("decoded %dx%d (half=%d)", width, height, halfSize ? 1 : 0);
    return bitmap;
}

// ===================== Lr 风格调色渲染引擎 =====================
// 管线：矩阵（曝光/对比/色温/色调/饱和）→ 亮度曲线 LUT（高光/阴影/白色/黑色）→ HSL 八分区。
// 预览走小尺寸会话（~1280px，毫秒级）；定稿/导出走全尺寸带渲染。

#include <cmath>
#include <cstring>

namespace {

struct ToneSession {
    int pw = 0;
    int ph = 0;
    std::vector<uint8_t> src;  // 预览源 RGB888
};

struct RenderData {
    float m[20];
    float lut[256];
    float hsl[24];  // 8 区 × [色相°, 饱和度Δ, 亮度Δ]
    bool hasHsl = false;
};

const float HSL_CENTERS[8] = {0.f, 30.f, 60.f, 120.f, 180.f, 240.f, 270.f, 315.f};

inline int clamp255(float v) {
    return v < 0.f ? 0 : (v > 255.f ? 255 : static_cast<int>(v + 0.5f));
}

inline float clamp01(float v) {
    return v < 0.f ? 0.f : (v > 1.f ? 1.f : v);
}

inline float circDist(float a, float b) {
    float d = std::fabs(a - b);
    return d > 180.f ? 360.f - d : d;
}

void rgbToHsl(float r, float g, float b, float& h, float& s, float& l) {
    float mx = std::max(r, std::max(g, b));
    float mn = std::min(r, std::min(g, b));
    l = (mx + mn) * 0.5f;
    if (mx == mn) {
        h = 0.f;
        s = 0.f;
        return;
    }
    float d = mx - mn;
    s = l > 0.5f ? d / (2.f - mx - mn) : d / (mx + mn);
    if (mx == r) {
        h = 60.f * std::fmod((g - b) / d + 6.f, 6.f);
    } else if (mx == g) {
        h = 60.f * ((b - r) / d + 2.f);
    } else {
        h = 60.f * ((r - g) / d + 4.f);
    }
}

inline float hueToRgb(float p, float q, float t) {
    if (t < 0.f) t += 1.f;
    if (t > 1.f) t -= 1.f;
    if (t < 1.f / 6.f) return p + (q - p) * 6.f * t;
    if (t < 1.f / 2.f) return q;
    if (t < 2.f / 3.f) return p + (q - p) * (2.f / 3.f - t) * 6.f;
    return p;
}

void hslToRgb(float h, float s, float l, float& r, float& g, float& b) {
    if (s <= 1e-6f) {
        r = g = b = l;
        return;
    }
    float q = l < 0.5f ? l * (1.f + s) : l + s - l * s;
    float p = 2.f * l - q;
    float hn = std::fmod(h, 360.f) / 360.f;
    if (hn < 0.f) hn += 1.f;
    r = hueToRgb(p, q, hn + 1.f / 3.f);
    g = hueToRgb(p, q, hn);
    b = hueToRgb(p, q, hn - 1.f / 3.f);
}

inline bool isNeutralData(const RenderData& d) {
    for (float v : d.hsl) {
        if (v != 0.f) return false;
    }
    for (int i = 0; i < 256; i++) {
        if (std::fabs(d.lut[i] - i) > 0.5f) return false;
    }
    // 矩阵是否单位阵（仅检查非对角偏移与增益）
    static const float ID[20] = {1,0,0,0,0, 0,1,0,0,0, 0,0,1,0,0, 0,0,0,1,0};
    for (int i = 0; i < 20; i++) {
        if (std::fabs(d.m[i] - ID[i]) > 1e-5f) return false;
    }
    return true;
}

inline void processPixel(const RenderData& d, uint8_t& R, uint8_t& G, uint8_t& B) {
    float r = R, g = G, b = B;
    float nr = d.m[0] * r + d.m[1] * g + d.m[2] * b + d.m[4];
    float ng = d.m[5] * r + d.m[6] * g + d.m[7] * b + d.m[9];
    float nb = d.m[10] * r + d.m[11] * g + d.m[12] * b + d.m[14];
    nr = d.lut[clamp255(nr)];
    ng = d.lut[clamp255(ng)];
    nb = d.lut[clamp255(nb)];

    // 基础/色调调整无需经过 RGB↔HSL，尤其避免每帧数百万次取模与除法。
    if (!d.hasHsl) {
        R = static_cast<uint8_t>(clamp255(nr));
        G = static_cast<uint8_t>(clamp255(ng));
        B = static_cast<uint8_t>(clamp255(nb));
        return;
    }

    float fr = nr / 255.f, fg = ng / 255.f, fb = nb / 255.f;
    float h, s, l;
    rgbToHsl(fr, fg, fb, h, s, l);
    if (s > 1e-4f) {
        float hueShift = 0.f, satD = 0.f, lumD = 0.f;
        for (int i = 0; i < 8; i++) {
            float w = 1.f - circDist(h, HSL_CENTERS[i]) / 45.f;
            if (w > 0.f) {
                hueShift += w * d.hsl[i * 3];
                satD += w * d.hsl[i * 3 + 1];
                lumD += w * d.hsl[i * 3 + 2];
            }
        }
        h += hueShift;
        s = clamp01(s * (1.f + satD));
        l = clamp01(l + lumD);
        hslToRgb(h, s, l, fr, fg, fb);
    }
    R = static_cast<uint8_t>(clamp255(fr * 255.f));
    G = static_cast<uint8_t>(clamp255(fg * 255.f));
    B = static_cast<uint8_t>(clamp255(fb * 255.f));
}

bool loadRenderData(JNIEnv* env, jfloatArray matrix, jfloatArray lut, jfloatArray hsl, RenderData& d) {
    if (env->GetArrayLength(matrix) != 20 || env->GetArrayLength(lut) != 256 ||
        env->GetArrayLength(hsl) != 24) {
        return false;
    }
    env->GetFloatArrayRegion(matrix, 0, 20, d.m);
    env->GetFloatArrayRegion(lut, 0, 256, d.lut);
    env->GetFloatArrayRegion(hsl, 0, 24, d.hsl);
    for (float v : d.hsl) {
        if (v != 0.f) {
            d.hasHsl = true;
            break;
        }
    }
    return true;
}

void writeRgbToBitmap(JNIEnv* env, jobject bitmap, int w, int h, const uint8_t* rgb) {
    jclass cls = env->GetObjectClass(bitmap);
    jmethodID setPixels = env->GetMethodID(cls, "setPixels", "([IIIIIII)V");
    jintArray rowArray = env->NewIntArray(w);
    std::vector<jint> row(static_cast<size_t>(w));
    for (int y = 0; y < h; ++y) {
        const uint8_t* s = rgb + static_cast<size_t>(y) * w * 3;
        for (int x = 0; x < w; ++x) {
            const size_t p = static_cast<size_t>(x) * 3;
            row[static_cast<size_t>(x)] = static_cast<jint>(
                0xFF000000u |
                (static_cast<uint32_t>(s[p]) << 16) |
                (static_cast<uint32_t>(s[p + 1]) << 8) |
                static_cast<uint32_t>(s[p + 2]));
        }
        env->SetIntArrayRegion(rowArray, 0, w, row.data());
        env->CallVoidMethod(bitmap, setPixels, rowArray, 0, w, 0, y, w, 1);
    }
    env->DeleteLocalRef(rowArray);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_dcimgallery_raw_core_raw_RawDecoder_createToneSession(
    JNIEnv* env, jclass /* clazz */, jobject bitmap, jint maxDim) {
    jclass cls = env->GetObjectClass(bitmap);
    jmethodID getWidth = env->GetMethodID(cls, "getWidth", "()I");
    jmethodID getHeight = env->GetMethodID(cls, "getHeight", "()I");
    jmethodID getPixels = env->GetMethodID(cls, "getPixels", "([IIIIIII)V");
    const int w = env->CallIntMethod(bitmap, getWidth);
    const int h = env->CallIntMethod(bitmap, getHeight);
    if (w <= 0 || h <= 0) return 0;

    if (maxDim <= 0) return 0;
    // 向上取整，确保预览不会超过指定尺寸。
    int factor = (std::max(w, h) + maxDim - 1) / maxDim;
    if (factor < 1) factor = 1;
    const int pw = w / factor;
    const int ph = h / factor;
    if (pw <= 0 || ph <= 0) return 0;

    auto* session = new ToneSession();
    session->pw = pw;
    session->ph = ph;
    session->src.resize(static_cast<size_t>(pw) * ph * 3);

    const int band = std::min(128, h);
    jintArray arr = env->NewIntArray(static_cast<jsize>(w) * band);
    if (arr == nullptr) {
        delete session;
        return 0;
    }
    std::vector<uint32_t> accR(pw), accG(pw), accB(pw), accN(pw);
    int curPY = -1;

    auto flush = [&](int py) {
        uint8_t* dst = session->src.data() + static_cast<size_t>(py) * pw * 3;
        for (int x = 0; x < pw; ++x) {
            uint32_t n = accN[x] > 0 ? accN[x] : 1;
            dst[x * 3] = static_cast<uint8_t>(accR[x] / n);
            dst[x * 3 + 1] = static_cast<uint8_t>(accG[x] / n);
            dst[x * 3 + 2] = static_cast<uint8_t>(accB[x] / n);
        }
    };

    int bandStart = 0;
    jint* px = nullptr;
    int bandH = 0;
    for (int sy = 0; sy < h && curPY < ph; ++sy) {
        if (sy >= bandStart + bandH) {
            if (px != nullptr) env->ReleaseIntArrayElements(arr, px, JNI_ABORT);
            bandStart = sy;
            bandH = std::min(band, h - sy);
            env->CallVoidMethod(bitmap, getPixels, arr, 0, w, 0, sy, w, bandH);
            px = env->GetIntArrayElements(arr, nullptr);
            if (px == nullptr) break;
        }
        const int py = sy / factor;
        if (py >= ph) break;
        if (py != curPY) {
            if (curPY >= 0) flush(curPY);
            std::fill(accR.begin(), accR.end(), 0);
            std::fill(accG.begin(), accG.end(), 0);
            std::fill(accB.begin(), accB.end(), 0);
            std::fill(accN.begin(), accN.end(), 0);
            curPY = py;
        }
        const jint* row = px + static_cast<size_t>(sy - bandStart) * w;
        for (int x = 0; x < pw; ++x) {
            const int sxEnd = std::min((x + 1) * factor, w);
            for (int sx = x * factor; sx < sxEnd; ++sx) {
                const uint32_t v = static_cast<uint32_t>(row[sx]);
                accR[x] += (v >> 16) & 0xFF;
                accG[x] += (v >> 8) & 0xFF;
                accB[x] += v & 0xFF;
                accN[x]++;
            }
        }
    }
    if (px != nullptr) env->ReleaseIntArrayElements(arr, px, JNI_ABORT);
    env->DeleteLocalRef(arr);
    if (curPY >= 0) flush(curPY);

    LOGI("tone session %dx%d -> %dx%d (factor %d)", w, h, pw, ph, factor);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_dcimgallery_raw_core_raw_RawDecoder_destroyToneSession(
    JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    delete reinterpret_cast<ToneSession*>(handle);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_dcimgallery_raw_core_raw_RawDecoder_tonePreviewSize(
    JNIEnv* env, jclass /* clazz */, jlong handle) {
    auto* s = reinterpret_cast<ToneSession*>(handle);
    jintArray out = env->NewIntArray(2);
    if (s != nullptr && out != nullptr) {
        jint dims[2] = {s->pw, s->ph};
        env->SetIntArrayRegion(out, 0, 2, dims);
    }
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dcimgallery_raw_core_raw_RawDecoder_renderTonePreview(
    JNIEnv* env, jclass /* clazz */, jlong handle,
    jfloatArray matrix, jfloatArray lut, jfloatArray hsl, jobject outBitmap) {
    auto* s = reinterpret_cast<ToneSession*>(handle);
    if (s == nullptr) return JNI_FALSE;
    RenderData d;
    if (!loadRenderData(env, matrix, lut, hsl, d)) return JNI_FALSE;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, outBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width != static_cast<uint32_t>(s->pw) ||
        info.height != static_cast<uint32_t>(s->ph)) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return JNI_FALSE;
    }
    const bool neutral = isNeutralData(d);
    // 直接写 Bitmap 的 RGBA 缓冲区，无中间输出数组和逐行 JNI 往返。
    for (int y = 0; y < s->ph; ++y) {
        const uint8_t* in = s->src.data() + static_cast<size_t>(y) * s->pw * 3;
        auto* out = static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
        for (int x = 0; x < s->pw; ++x) {
            uint8_t R = in[x * 3];
            uint8_t G = in[x * 3 + 1];
            uint8_t B = in[x * 3 + 2];
            if (!neutral) processPixel(d, R, G, B);
            out[x * 4] = R;
            out[x * 4 + 1] = G;
            out[x * 4 + 2] = B;
            out[x * 4 + 3] = 255;
        }
    }
    return AndroidBitmap_unlockPixels(env, outBitmap) == ANDROID_BITMAP_RESULT_SUCCESS
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dcimgallery_raw_core_raw_RawDecoder_renderToneFull(
    JNIEnv* env, jclass /* clazz */, jobject srcBitmap,
    jfloatArray matrix, jfloatArray lut, jfloatArray hsl, jobject dstBitmap) {
    RenderData d;
    if (!loadRenderData(env, matrix, lut, hsl, d)) return JNI_FALSE;

    jclass cls = env->GetObjectClass(srcBitmap);
    jmethodID getWidth = env->GetMethodID(cls, "getWidth", "()I");
    jmethodID getHeight = env->GetMethodID(cls, "getHeight", "()I");
    jmethodID getPixels = env->GetMethodID(cls, "getPixels", "([IIIIIII)V");
    jmethodID setPixels = env->GetMethodID(cls, "setPixels", "([IIIIIII)V");
    const int w = env->CallIntMethod(srcBitmap, getWidth);
    const int h = env->CallIntMethod(srcBitmap, getHeight);
    if (w <= 0 || h <= 0) return JNI_FALSE;

    if (isNeutralData(d)) {
        // 恒等：整图直拷
        jclass dcls = env->GetObjectClass(dstBitmap);
        jmethodID dSet = env->GetMethodID(dcls, "setPixels", "([IIIIIII)V");
        const int band = 64;
        jintArray arr = env->NewIntArray(static_cast<jsize>(w) * band);
        for (int y = 0; y < h; y += band) {
            const int bh = std::min(band, h - y);
            env->CallVoidMethod(srcBitmap, getPixels, arr, 0, w, 0, y, w, bh);
            env->CallVoidMethod(dstBitmap, dSet, arr, 0, w, 0, y, w, bh);
        }
        env->DeleteLocalRef(arr);
        return JNI_TRUE;
    }

    const int band = 64;
    jintArray arr = env->NewIntArray(static_cast<jsize>(w) * band);
    if (arr == nullptr) return JNI_FALSE;
    for (int y = 0; y < h; y += band) {
        const int bh = std::min(band, h - y);
        env->CallVoidMethod(srcBitmap, getPixels, arr, 0, w, 0, y, w, bh);
        jint* px = env->GetIntArrayElements(arr, nullptr);
        if (px == nullptr) {
            env->DeleteLocalRef(arr);
            return JNI_FALSE;
        }
        const size_t n = static_cast<size_t>(w) * bh;
        for (size_t i = 0; i < n; ++i) {
            const uint32_t v = static_cast<uint32_t>(px[i]);
            uint8_t R = static_cast<uint8_t>((v >> 16) & 0xFF);
            uint8_t G = static_cast<uint8_t>((v >> 8) & 0xFF);
            uint8_t B = static_cast<uint8_t>(v & 0xFF);
            processPixel(d, R, G, B);
            px[i] = static_cast<jint>(
                0xFF000000u |
                (static_cast<uint32_t>(R) << 16) |
                (static_cast<uint32_t>(G) << 8) |
                static_cast<uint32_t>(B));
        }
        env->ReleaseIntArrayElements(arr, px, 0);
        env->CallVoidMethod(dstBitmap, setPixels, arr, 0, w, 0, y, w, bh);
    }
    env->DeleteLocalRef(arr);
    return JNI_TRUE;
}

/** 蒙版只修改新建的输出 Bitmap，发布之后的帧永不原地重写。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_dcimgallery_raw_core_raw_RawDecoder_applyMasks(
    JNIEnv* env, jclass, jobject bitmap, jfloatArray matrices, jfloatArray luts,
    jfloatArray hsls, jbyteArray coverage, jint mw, jint mh) {
    const int count = env->GetArrayLength(matrices) / 20;
    if (count == 0) return JNI_TRUE;
    if (mw <= 0 || mh <= 0 || env->GetArrayLength(matrices) != count * 20 ||
        env->GetArrayLength(luts) != count * 256 || env->GetArrayLength(hsls) != count * 24 ||
        static_cast<int64_t>(mw) * mh * count != env->GetArrayLength(coverage)) return JNI_FALSE;
    std::vector<float> m(count * 20), l(count * 256), hs(count * 24);
    env->GetFloatArrayRegion(matrices, 0, m.size(), m.data());
    env->GetFloatArrayRegion(luts, 0, l.size(), l.data());
    env->GetFloatArrayRegion(hsls, 0, hs.size(), hs.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    std::vector<RenderData> layers(count);
    for (int i = 0; i < count; ++i) {
        std::copy_n(m.data() + i * 20, 20, layers[i].m);
        std::copy_n(l.data() + i * 256, 256, layers[i].lut);
        std::copy_n(hs.data() + i * 24, 24, layers[i].hsl);
        for (float v : layers[i].hsl) if (v != 0.f) { layers[i].hasHsl = true; break; }
    }
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) return JNI_FALSE;
    auto* maps = env->GetByteArrayElements(coverage, nullptr);
    if (!maps) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        env->ReleaseByteArrayElements(coverage, maps, JNI_ABORT);
        return JNI_FALSE;
    }
    const auto* alpha = reinterpret_cast<const uint8_t*>(maps);
    const size_t mapSize = static_cast<size_t>(mw) * mh;
    for (uint32_t y = 0; y < info.height; ++y) {
        auto* row = static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
        const float my = std::max(0.f, std::min(static_cast<float>(mh - 1),
            (y + 0.5f) * mh / info.height - 0.5f));
        const int y0 = static_cast<int>(my), y1 = std::min(y0 + 1, mh - 1);
        const float fy = my - y0;
        for (uint32_t x = 0; x < info.width; ++x) {
            const float mx = std::max(0.f, std::min(static_cast<float>(mw - 1),
                (x + 0.5f) * mw / info.width - 0.5f));
            const int x0 = static_cast<int>(mx), x1 = std::min(x0 + 1, mw - 1);
            const float fx = mx - x0;
            auto* p = row + x * 4;
            for (int i = 0; i < count; ++i) {
                const uint8_t* mask = alpha + i * mapSize;
                const float a0 = mask[y0 * mw + x0] * (1.f - fx) + mask[y0 * mw + x1] * fx;
                const float a1 = mask[y1 * mw + x0] * (1.f - fx) + mask[y1 * mw + x1] * fx;
                const float a = (a0 * (1.f - fy) + a1 * fy) / 255.f;
                if (a <= 0.f) continue;
                uint8_t r = p[0], g = p[1], b = p[2];
                processPixel(layers[i], r, g, b);
                p[0] = static_cast<uint8_t>(clamp255(p[0] + (r - p[0]) * a));
                p[1] = static_cast<uint8_t>(clamp255(p[1] + (g - p[1]) * a));
                p[2] = static_cast<uint8_t>(clamp255(p[2] + (b - p[2]) * a));
            }
        }
    }
    const int status = AndroidBitmap_unlockPixels(env, bitmap);
    env->ReleaseByteArrayElements(coverage, maps, JNI_ABORT);
    return status == ANDROID_BITMAP_RESULT_SUCCESS ? JNI_TRUE : JNI_FALSE;
}
