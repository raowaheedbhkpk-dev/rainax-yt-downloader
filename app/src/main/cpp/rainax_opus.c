/* Opus decoding inside the app (libopus, BSD licence): many times faster than Android's decoders for
   converting, because nothing has to travel to the system's media process and back for every 20 ms of sound. */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "opus.h"

typedef struct {
    OpusDecoder *dec;
    int channels;
} RxOpus;

/* --- plain C core (also used by the tests) --- */

void *rx_opus_open(int channels) {
    if (channels < 1 || channels > 2) return NULL;
    int err = 0;
    RxOpus *o = (RxOpus *) calloc(1, sizeof(RxOpus));
    if (!o) return NULL;
    o->dec = opus_decoder_create(48000, channels, &err);
    if (err != OPUS_OK || !o->dec) { free(o); return NULL; }
    o->channels = channels;
    return o;
}

/* One Opus packet -> interleaved 16-bit samples at 48 kHz. Returns sample frames (per channel), or < 0 on error. */
int rx_opus_decode(void *h, const unsigned char *packet, int len, short *out, int maxFrames) {
    RxOpus *o = (RxOpus *) h;
    if (!o) return -1;
    return opus_decode(o->dec, packet, len, out, maxFrames, 0);
}

void rx_opus_close(void *h) {
    RxOpus *o = (RxOpus *) h;
    if (!o) return;
    opus_decoder_destroy(o->dec);
    free(o);
}

/* --- JNI --- */

JNIEXPORT jlong JNICALL
Java_com_rainax_ytdownloader_OpusDecoder_nativeOpen(JNIEnv *env, jclass cls, jint channels) {
    return (jlong) (intptr_t) rx_opus_open(channels);
}

/* packet: a direct ByteBuffer holding [len] bytes from position 0. out: room for maxFrames * channels samples. */
JNIEXPORT jint JNICALL
Java_com_rainax_ytdownloader_OpusDecoder_nativeDecode(JNIEnv *env, jclass cls, jlong handle, jobject packet, jint len, jshortArray out) {
    RxOpus *o = (RxOpus *) (intptr_t) handle;
    if (!o) return -1;
    unsigned char *pkt = (unsigned char *) (*env)->GetDirectBufferAddress(env, packet);
    if (!pkt) return -2;
    jsize outLen = (*env)->GetArrayLength(env, out);
    jshort *pcm = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!pcm) return -3;
    int frames = rx_opus_decode(o, pkt, len, pcm, outLen / o->channels);
    (*env)->ReleasePrimitiveArrayCritical(env, out, pcm, 0);
    return frames;
}

JNIEXPORT void JNICALL
Java_com_rainax_ytdownloader_OpusDecoder_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    rx_opus_close((void *) (intptr_t) handle);
}
