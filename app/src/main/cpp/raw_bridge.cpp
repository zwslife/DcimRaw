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

// Shared native rendering for preview, full output and local adjustments.
#include "tone_engine.h"
#include <cstring>
#include <new>

namespace {
using namespace dcim;
struct ToneSession {
    int pw=0, ph=0, originalMax=0;
    std::vector<uint8_t> src;
    Analysis analysis, maskAnalysis;
    RenderData lastData;
    bool hasLastData=false;
};

bool sameData(const RenderData& a,const RenderData& b) {
    return std::memcmp(a.m,b.m,sizeof(a.m))==0 && std::memcmp(a.lut,b.lut,sizeof(a.lut))==0 &&
        std::memcmp(a.hsl,b.hsl,sizeof(a.hsl))==0 && std::memcmp(a.effects,b.effects,sizeof(a.effects))==0;
}

bool loadRenderData(JNIEnv* env,jfloatArray matrix,jfloatArray lut,jfloatArray hsl,jfloatArray effects,RenderData& d) {
    if(!matrix || !lut || !hsl || !effects || env->GetArrayLength(matrix)!=20 ||
        env->GetArrayLength(lut)!=256 || env->GetArrayLength(hsl)!=24 || env->GetArrayLength(effects)!=EffectCount)return false;
    env->GetFloatArrayRegion(matrix,0,20,d.m);
    env->GetFloatArrayRegion(lut,0,256,d.lut);
    env->GetFloatArrayRegion(hsl,0,24,d.hsl);
    env->GetFloatArrayRegion(effects,0,EffectCount,d.effects);
    if(env->ExceptionCheck())return false;
    prepare(d); return true;
}

// Always release bitmap locks on failure, including allocation failure in spatial analysis.
struct LockedBitmap {
    JNIEnv* env; jobject bitmap; AndroidBitmapInfo info{}; void* pixels=nullptr;
    LockedBitmap(JNIEnv* e,jobject b):env(e),bitmap(b) {
        if(AndroidBitmap_getInfo(env,bitmap,&info)==ANDROID_BITMAP_RESULT_SUCCESS &&
            info.format==ANDROID_BITMAP_FORMAT_RGBA_8888 && info.width>0 && info.height>0) {
            if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS)pixels=nullptr;
        }
    }
    ~LockedBitmap(){if(pixels)AndroidBitmap_unlockPixels(env,bitmap);}
    ImageView view()const{return {static_cast<uint8_t*>(pixels),static_cast<int>(info.width),static_cast<int>(info.height),static_cast<int>(info.stride),4};}
};
} // namespace

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
    session->originalMax = std::max(w,h);
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
    // Prepare neighborhood maps before the first dial gesture; amount changes reuse them.
    try {
        session->analysis.ensure({session->src.data(),pw,ph,pw*3,3},true,true);
    } catch(const std::exception&) {
        delete session;
        return 0;
    }
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
    JNIEnv* env,jclass,jlong handle,jfloatArray matrix,jfloatArray lut,jfloatArray hsl,jfloatArray effects,jobject outBitmap) {
    auto* s=reinterpret_cast<ToneSession*>(handle);
    if(!s)return JNI_FALSE;
    RenderData d;
    if(!loadRenderData(env,matrix,lut,hsl,effects,d))return JNI_FALSE;
    LockedBitmap out(env,outBitmap);
    if(!out.pixels || out.info.width!=static_cast<uint32_t>(s->pw) || out.info.height!=static_cast<uint32_t>(s->ph))return JNI_FALSE;
    try {
        if(!s->hasLastData || !sameData(s->lastData,d))s->maskAnalysis.clear();
        ImageView source{s->src.data(),s->pw,s->ph,s->pw*3,3};
        renderImage(source,out.view(),d,s->analysis,static_cast<float>(std::max(s->pw,s->ph))/s->originalMax);
        s->lastData=d;s->hasLastData=true;
        return JNI_TRUE;
    } catch(const std::exception&){return JNI_FALSE;}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dcimgallery_raw_core_raw_RawDecoder_renderToneFull(
    JNIEnv* env,jclass,jobject srcBitmap,jfloatArray matrix,jfloatArray lut,jfloatArray hsl,jfloatArray effects,jobject dstBitmap) {
    RenderData d;
    if(!loadRenderData(env,matrix,lut,hsl,effects,d))return JNI_FALSE;
    LockedBitmap src(env,srcBitmap),dst(env,dstBitmap);
    if(!src.pixels || !dst.pixels || src.info.width!=dst.info.width || src.info.height!=dst.info.height)return JNI_FALSE;
    try {
        Analysis analysis;
        renderImage(src.view(),dst.view(),d,analysis);
        return JNI_TRUE;
    }catch(const std::exception&){return JNI_FALSE;}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dcimgallery_raw_core_raw_RawDecoder_applyMasks(
    JNIEnv* env,jclass,jobject bitmap,jfloatArray matrices,jfloatArray luts,jfloatArray hsls,jfloatArray effects,
    jbyteArray coverage,jint mw,jint mh,jlong handle) {
    if(!matrices || !luts || !hsls || !effects || !coverage)return JNI_FALSE;
    const int count=env->GetArrayLength(matrices)/20;
    if(count==0)return JNI_TRUE;
    if(mw<=0 || mh<=0 || env->GetArrayLength(matrices)!=count*20 || env->GetArrayLength(luts)!=count*256 ||
        env->GetArrayLength(hsls)!=count*24 || env->GetArrayLength(effects)!=count*EffectCount ||
        static_cast<int64_t>(mw)*mh*count!=env->GetArrayLength(coverage))return JNI_FALSE;
    try {
        std::vector<RenderData> layers(count);
        for(int i=0;i<count;++i) {
            env->GetFloatArrayRegion(matrices,i*20,20,layers[i].m);
            env->GetFloatArrayRegion(luts,i*256,256,layers[i].lut);
            env->GetFloatArrayRegion(hsls,i*24,24,layers[i].hsl);
            env->GetFloatArrayRegion(effects,i*EffectCount,EffectCount,layers[i].effects);
            prepare(layers[i]);
        }
        if(env->ExceptionCheck())return JNI_FALSE;
        LockedBitmap image(env,bitmap);
        if(!image.pixels)return JNI_FALSE;
        auto* maps=env->GetByteArrayElements(coverage,nullptr);
        if(!maps)return JNI_FALSE;
        Analysis temporary;
        auto* session=reinterpret_cast<ToneSession*>(handle);
        Analysis& analysis=session?session->maskAnalysis:temporary;
        const float scale=session?static_cast<float>(std::max(session->pw,session->ph))/session->originalMax:1.f;
        bool success=true;
        try {renderMasks(image.view(),layers,reinterpret_cast<const uint8_t*>(maps),mw,mh,analysis,scale);}
        catch(const std::exception&){success=false;}
        env->ReleaseByteArrayElements(coverage,maps,JNI_ABORT);
        return success?JNI_TRUE:JNI_FALSE;
    } catch(const std::exception&){return JNI_FALSE;}
}
