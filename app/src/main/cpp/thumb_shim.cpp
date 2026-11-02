/*
 * JNI side of the thumbnail keyframe decoder. Picks one FFmpeg backend per process (thumb_backend.h); when none
 * loads, the next nativeInit tries again. `setprop debug.nuvio.thumb.backend ff60` (or `mpv`) forces the choice.
 *
 * One handle = one decoder context, used from one thread at a time (the Kotlin side serialises).
 */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <sys/system_properties.h>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <sched.h>
#include <thread>

#include "hevc_strips.h"
#include "thumb_backend.h"

#define TAG "ThumbDecode"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

const thumb::Backend *g_backend = nullptr;
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
char g_error[512] = "";
pthread_mutex_t g_error_lock = PTHREAD_MUTEX_INITIALIZER;

void clear_failures() {
    pthread_mutex_lock(&g_error_lock);
    g_error[0] = '\0';
    pthread_mutex_unlock(&g_error_lock);
}

const thumb::Backend *backend() {
    pthread_mutex_lock(&g_lock);
    if (!g_backend) {
        clear_failures();
        char forced[PROP_VALUE_MAX] = "";
        __system_property_get("debug.nuvio.thumb.backend", forced);
        if (strcmp(forced, "ff60") != 0) g_backend = thumb::backend_mpv();
        if (!g_backend && strcmp(forced, "mpv") != 0) g_backend = thumb::backend_ff60();
        if (g_backend) LOGI("backend=%s %s%s", g_backend->name, g_backend->version, forced[0] ? " (forced)" : "");
        else thumb::note_failure("no FFmpeg backend available%s", forced[0] ? " (forced)" : "");
    }
    const thumb::Backend *b = g_backend;
    pthread_mutex_unlock(&g_lock);
    return b;
}

/**
 * Runs a decode or render on a short-lived thread in the idle scheduling class, below every playback thread; the strip
 * decoders it starts inherit the class. The caller's pool thread keeps its own scheduling.
 */
int run_idle(int (*work)(void *), void *arg) {
    int r = 0;
    try {
        std::thread t([&] {
            sched_param none{};
            static bool warned = false;
            if (sched_setscheduler(0, SCHED_IDLE, &none) != 0 && !warned) {
                warned = true;
                LOGW("idle scheduling refused (errno %d), decoding at the caller's priority", errno);
            }
            r = work(arg);
        });
        t.join();
    } catch (...) {
        r = work(arg);
    }
    return r;
}

struct JHandle {
    const thumb::Backend *b;
    void *h;
};

}  // namespace

void thumb::note_failure(const char *fmt, ...) {
    char line[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    LOGW("%s", line);
    pthread_mutex_lock(&g_error_lock);
    size_t used = strlen(g_error);
    if (used > 0 && used + 2 < sizeof g_error) {
        strcat(g_error, "; ");
        used += 2;
    }
    if (used < sizeof g_error) snprintf(g_error + used, sizeof g_error - used, "%s", line);
    pthread_mutex_unlock(&g_error_lock);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeInit(JNIEnv *, jclass) {
    return backend() ? JNI_TRUE : JNI_FALSE;
}

/** What failed while loading the backends at the last attempt; "" when nothing did. */
JNIEXPORT jstring JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeLastError(JNIEnv *env, jclass) {
    char s[sizeof g_error];
    pthread_mutex_lock(&g_error_lock);
    snprintf(s, sizeof s, "%s", g_error);
    pthread_mutex_unlock(&g_error_lock);
    return env->NewStringUTF(s);
}

/** "<name> <version>", "" when none. */
JNIEXPORT jstring JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeBackend(JNIEnv *env, jclass) {
    const thumb::Backend *b = backend();
    char s[160] = "";
    if (b) snprintf(s, sizeof s, "%s %s", b->name, b->version);
    return env->NewStringUTF(s);
}

/** threads > 1 enables slice threading. Returns 0 when the decoder is missing. */
JNIEXPORT jlong JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeOpen(JNIEnv *env, jclass, jstring decoder, jbyteArray extradata,
                                                                jint width, jint height, jint threads) {
    const thumb::Backend *b = backend();
    if (!b || !decoder) return 0;
    const char *name = env->GetStringUTFChars(decoder, nullptr);
    if (!name) return 0;
    jbyte *extra = nullptr;
    jsize extra_len = 0;
    if (extradata) {
        extra_len = env->GetArrayLength(extradata);
        extra = env->GetByteArrayElements(extradata, nullptr);
    }
    void *h = b->open(name, reinterpret_cast<const uint8_t *>(extra), extra_len, width, height, threads);
    if (extra) env->ReleaseByteArrayElements(extradata, extra, JNI_ABORT);
    env->ReleaseStringUTFChars(decoder, name);
    if (!h) return 0;
    return reinterpret_cast<jlong>(new JHandle{b, h});
}

/** Decodes one keyframe, fills info[] and keeps the frame in the handle for nativeRender. 0 or a negative AVERROR. */
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeDecode(JNIEnv *env, jclass, jlong handle,
                                                                  jbyteArray data, jint len, jintArray info) {
    auto *j = reinterpret_cast<JHandle *>(handle);
    if (!j || len <= 0) return -EINVAL;
    uint8_t *buf = j->b->packet(j->h, len);
    if (!buf) return -ENOMEM;
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(buf));
    int32_t out[thumb::INFO_COUNT];
    struct Call { JHandle *j; int32_t *out; } call{j, out};
    int r = run_idle([](void *a) { auto *c = static_cast<Call *>(a); return c->j->b->decode(c->j->h, c->out); }, &call);
    if (r == 0) env->SetIntArrayRegion(info, 0, thumb::INFO_COUNT, reinterpret_cast<jint *>(out));
    return r;
}

/** As nativeDecode, from a direct ByteBuffer: copied from its address into the packet, no Java array. */
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeDecodeDirect(JNIEnv *env, jclass, jlong handle,
                                                                        jobject data, jint len, jintArray info) {
    auto *j = reinterpret_cast<JHandle *>(handle);
    if (!j || len <= 0) return -EINVAL;
    const auto *src = static_cast<const uint8_t *>(env->GetDirectBufferAddress(data));
    if (!src || env->GetDirectBufferCapacity(data) < len) return -EINVAL;
    uint8_t *buf = j->b->packet(j->h, len);
    if (!buf) return -ENOMEM;
    memcpy(buf, src, static_cast<size_t>(len));
    int32_t out[thumb::INFO_COUNT];
    struct Call { JHandle *j; int32_t *out; } call{j, out};
    int r = run_idle([](void *a) { auto *c = static_cast<Call *>(a); return c->j->b->decode(c->j->h, c->out); }, &call);
    if (r == 0) env->SetIntArrayRegion(info, 0, thumb::INFO_COUNT, reinterpret_cast<jint *>(out));
    return r;
}

/**
 * Scales and tone-maps the decoded frame into bitmap (RGBA_8888 or RGB_565). The bitmap's size is the output size.
 * rowSkip: pre-decimate rows by 2, for >= 4x vertical downscales. 0 or a negative error.
 */
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeRender(JNIEnv *env, jclass, jlong handle, jobject bitmap,
                                                                  jint transfer, jboolean bt2020, jboolean fullRange,
                                                                  jfloat peakNits, jboolean rowSkip) {
    auto *j = reinterpret_cast<JHandle *>(handle);
    if (!j) return -EINVAL;
    AndroidBitmapInfo bi;
    if (AndroidBitmap_getInfo(env, bitmap, &bi) != ANDROID_BITMAP_RESULT_SUCCESS) return -EINVAL;
    const bool rgb565 = bi.format == ANDROID_BITMAP_FORMAT_RGB_565;
    if (!rgb565 && bi.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -EINVAL;
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -EINVAL;
    struct Call {
        JHandle *j; uint8_t *pixels; int stride, w, h; bool rgb565; int transfer; bool bt2020, fullRange; float peak;
        bool rowSkip;
    } call{j, static_cast<uint8_t *>(pixels), static_cast<int>(bi.stride), static_cast<int>(bi.width),
           static_cast<int>(bi.height), rgb565, transfer, bt2020 == JNI_TRUE, fullRange == JNI_TRUE, peakNits,
           rowSkip == JNI_TRUE};
    int r = run_idle([](void *a) {
        auto *c = static_cast<Call *>(a);
        return c->j->b->render(c->j->h, c->pixels, c->stride, c->w, c->h, c->rgb565, c->transfer, c->bt2020,
                               c->fullRange, c->peak, c->rowSkip);
    }, &call);
    AndroidBitmap_unlockPixels(env, bitmap);
    return r;
}

/** Strips an HEVC keyframe access unit (Annex B) would be decoded in, 0 when it decodes whole. No FFmpeg needed. */
JNIEXPORT jint JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeStripCount(JNIEnv *env, jclass, jbyteArray data, jint len) {
    if (!data || len <= 0 || env->GetArrayLength(data) < len) return 0;
    jbyte *b = env->GetByteArrayElements(data, nullptr);
    if (!b) return 0;
    thumb::Strips s;
    const bool ok = thumb::split_hevc(reinterpret_cast<const uint8_t *>(b), static_cast<size_t>(len), &s);
    env->ReleaseByteArrayElements(data, b, JNI_ABORT);
    return ok ? static_cast<jint>(s.pieces.size()) : 0;
}

JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeTrim(JNIEnv *, jclass, jlong handle) {
    auto *j = reinterpret_cast<JHandle *>(handle);
    if (j) j->b->trim(j->h);
}

/** Asks the allocator to return freed pages to the system (mallopt M_PURGE, Android 9+). No-op where absent. */
JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativePurge(JNIEnv *, jclass) {
    using mallopt_fn = int (*)(int, int);
    static const auto fn = reinterpret_cast<mallopt_fn>(dlsym(RTLD_DEFAULT, "mallopt"));
    constexpr int kMPurge = -101;   // bionic <malloc.h> M_PURGE
    if (fn) fn(kMPurge, 0);
}

JNIEXPORT void JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeRelease(JNIEnv *, jclass, jlong handle) {
    auto *j = reinterpret_cast<JHandle *>(handle);
    if (!j) return;
    j->b->release(j->h);
    delete j;
}

}  // extern "C"
