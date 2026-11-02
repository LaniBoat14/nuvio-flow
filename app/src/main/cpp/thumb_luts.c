/* thumbcolor's tables for the hardware thumbnail path, which applies the same tone map on the GPU. */
#include <jni.h>

#include "thumbcolor.h"

JNIEXPORT jfloatArray JNICALL
Java_com_nuvio_tv_core_player_thumbnail_ThumbNative_nativeToneLuts(JNIEnv *env, jclass clazz, jint transfer,
                                                                     jfloat peakNits, jboolean bt2020) {
    (void) clazz;
    tc_params p = {transfer == 2 ? TC_TRANSFER_HLG : TC_TRANSFER_PQ, peakNits, 0.0f, bt2020 ? 1 : 0};
    tc_ctx *c = tc_create(&p);
    if (!c) return NULL;
    float out[3 * TC_LUT_SIZE + 1];
    tc_tables(c, out, out + TC_LUT_SIZE, out + 2 * TC_LUT_SIZE, out + 3 * TC_LUT_SIZE);
    tc_free(c);
    jfloatArray arr = (*env)->NewFloatArray(env, 3 * TC_LUT_SIZE + 1);
    if (arr) (*env)->SetFloatArrayRegion(env, arr, 0, 3 * TC_LUT_SIZE + 1, out);
    return arr;
}
