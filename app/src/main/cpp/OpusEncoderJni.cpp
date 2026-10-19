#include <jni.h>
#include <opus.h>

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_ddagunts_screencast_nativecast_NativeOpusEncoder_create(JNIEnv *, jobject) {
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(48000, 2, OPUS_APPLICATION_AUDIO, &error);
    if (error != OPUS_OK || !encoder) return 0;
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(128000)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    return reinterpret_cast<jlong>(encoder);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_ddagunts_screencast_nativecast_NativeOpusEncoder_encode(
        JNIEnv *env, jobject, jlong handle, jbyteArray pcm) {
    if (!handle || !pcm || env->GetArrayLength(pcm) != 1920) return nullptr;
    jbyte *input = env->GetByteArrayElements(pcm, nullptr);
    if (!input) return nullptr;
    unsigned char encoded[4000];
    int size = opus_encode(reinterpret_cast<OpusEncoder *>(handle),
                           reinterpret_cast<const opus_int16 *>(input), 480,
                           encoded, sizeof(encoded));
    env->ReleaseByteArrayElements(pcm, input, JNI_ABORT);
    if (size < 0) return nullptr;
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size,
                                        reinterpret_cast<const jbyte *>(encoded));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ddagunts_screencast_nativecast_NativeOpusEncoder_destroy(
        JNIEnv *, jobject, jlong handle) {
    if (handle) opus_encoder_destroy(reinterpret_cast<OpusEncoder *>(handle));
}
