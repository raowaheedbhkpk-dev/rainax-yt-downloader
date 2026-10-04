/* Small bridge between the app (Kotlin) and the LAME MP3 encoder. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "lame/lame.h"

typedef struct {
    lame_global_flags *gf;
    int channels;          /* channels written to the MP3 (1 or 2) */
    short *tmp;            /* scratch for downmixing more than 2 input channels */
    int tmpFrames;
} Enc;

JNIEXPORT jlong JNICALL
Java_com_rainax_ytdownloader_Mp3Encoder_nativeInit(JNIEnv *env, jclass cls, jint channels, jint sampleRate, jint bitrate, jint quality) {
    Enc *e = (Enc *) calloc(1, sizeof(Enc));
    if (!e) return 0;
    e->channels = channels >= 2 ? 2 : 1;
    e->gf = lame_init();
    if (!e->gf) { free(e); return 0; }
    lame_set_num_channels(e->gf, e->channels);
    lame_set_in_samplerate(e->gf, sampleRate);
    lame_set_brate(e->gf, bitrate);
    lame_set_mode(e->gf, e->channels == 2 ? JOINT_STEREO : MONO);
    lame_set_quality(e->gf, quality);
    lame_set_VBR(e->gf, vbr_off);
    lame_set_bWriteVbrTag(e->gf, 0);
    lame_set_findReplayGain(e->gf, 0);
    if (lame_init_params(e->gf) < 0) {
        lame_close(e->gf);
        free(e);
        return 0;
    }
    return (jlong) (intptr_t) e;
}

/* pcm: interleaved 16-bit samples with inChannels channels. Returns MP3 bytes written, or < 0 on error. */
JNIEXPORT jint JNICALL
Java_com_rainax_ytdownloader_Mp3Encoder_nativeEncode(JNIEnv *env, jclass cls, jlong handle, jshortArray pcm, jint frames, jint inChannels, jbyteArray out) {
    Enc *e = (Enc *) (intptr_t) handle;
    if (!e || frames <= 0) return 0;
    jsize outSize = (*env)->GetArrayLength(env, out);
    jshort *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (!in) return -100;
    jbyte *mp3 = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!mp3) { (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT); return -100; }
    int n;
    if (inChannels == e->channels) {
        n = e->channels == 2
            ? lame_encode_buffer_interleaved(e->gf, in, frames, (unsigned char *) mp3, outSize)
            : lame_encode_buffer(e->gf, in, NULL, frames, (unsigned char *) mp3, outSize);
    } else {
        /* channel count differs (e.g. 5.1 -> stereo, stereo -> mono): keep the first channels */
        if (e->tmpFrames < frames) {
            free(e->tmp);
            e->tmp = (short *) malloc(sizeof(short) * frames * 2);
            e->tmpFrames = e->tmp ? frames : 0;
        }
        if (!e->tmp) {
            n = -101;
        } else {
            for (int i = 0; i < frames; i++) {
                short l = in[i * inChannels];
                short r = inChannels > 1 ? in[i * inChannels + 1] : l;
                if (e->channels == 2) { e->tmp[i * 2] = l; e->tmp[i * 2 + 1] = r; }
                else e->tmp[i] = (short) (((int) l + (int) r) / 2);
            }
            n = e->channels == 2
                ? lame_encode_buffer_interleaved(e->gf, e->tmp, frames, (unsigned char *) mp3, outSize)
                : lame_encode_buffer(e->gf, e->tmp, NULL, frames, (unsigned char *) mp3, outSize);
        }
    }
    (*env)->ReleasePrimitiveArrayCritical(env, out, mp3, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    return n;
}

JNIEXPORT jint JNICALL
Java_com_rainax_ytdownloader_Mp3Encoder_nativeFlush(JNIEnv *env, jclass cls, jlong handle, jbyteArray out) {
    Enc *e = (Enc *) (intptr_t) handle;
    if (!e) return 0;
    jsize outSize = (*env)->GetArrayLength(env, out);
    jbyte *mp3 = (*env)->GetByteArrayElements(env, out, NULL);
    if (!mp3) return -100;
    int n = lame_encode_flush(e->gf, (unsigned char *) mp3, outSize);
    (*env)->ReleaseByteArrayElements(env, out, mp3, 0);
    return n;
}

JNIEXPORT void JNICALL
Java_com_rainax_ytdownloader_Mp3Encoder_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    Enc *e = (Enc *) (intptr_t) handle;
    if (!e) return;
    lame_close(e->gf);
    free(e->tmp);
    free(e);
}
