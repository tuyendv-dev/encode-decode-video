#include <jni.h>
#include <opus/opus.h>
#include <android/log.h>
#include <cstring>

#define TAG "OpusJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

// Package: com.example.demoplayvideo.decoder
// Class: OpusDecoder
JNIEXPORT jlong JNICALL
Java_com_example_demoplayvideo_decoder_OpusDecoder_nativeCreateDecoder(
        JNIEnv *env,
        jobject thiz,
        jint sample_rate,
        jint channels) {

    LOGI("Creating Opus decoder: %dHz, %d channel(s)", sample_rate, channels);

    int error;
    OpusDecoder *decoder = opus_decoder_create(sample_rate, channels, &error);

    if (error != OPUS_OK || decoder == nullptr) {
        LOGE("Failed to create decoder: %s", opus_strerror(error));
        return 0;
    }

    LOGI("✓ Decoder created successfully at %p", decoder);
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT jint JNICALL
Java_com_example_demoplayvideo_decoder_OpusDecoder_nativeDecode(
        JNIEnv *env,
        jobject thiz,
        jlong decoder_handle,
        jbyteArray opus_data,
        jint opus_length,
        jshortArray pcm_data,
        jint frame_size) {

    if (decoder_handle == 0) {
        LOGE("Invalid decoder handle");
        return OPUS_INVALID_STATE;
    }

    OpusDecoder *decoder = reinterpret_cast<OpusDecoder *>(decoder_handle);

    // Get Opus data
    jbyte *opus_bytes = env->GetByteArrayElements(opus_data, nullptr);
    if (opus_bytes == nullptr) {
        LOGE("Failed to get Opus data");
        return OPUS_ALLOC_FAIL;
    }

    // Get PCM buffer
    jshort *pcm_shorts = env->GetShortArrayElements(pcm_data, nullptr);
    if (pcm_shorts == nullptr) {
        env->ReleaseByteArrayElements(opus_data, opus_bytes, JNI_ABORT);
        LOGE("Failed to get PCM buffer");
        return OPUS_ALLOC_FAIL;
    }

    // Decode
    int samples = opus_decode(
            decoder,
            reinterpret_cast<const unsigned char *>(opus_bytes),
            opus_length,
            pcm_shorts,
            frame_size,
            0  // decode_fec = 0 (no forward error correction)
    );

    // Release arrays
    env->ReleaseByteArrayElements(opus_data, opus_bytes, JNI_ABORT);
    env->ReleaseShortArrayElements(pcm_data, pcm_shorts, 0);

    if (samples < 0) {
        LOGE("Decode error: %s (code: %d)", opus_strerror(samples), samples);
    } else {
        LOGD("Decoded %d samples from %d bytes", samples, opus_length);
    }

    return samples;
}

JNIEXPORT void JNICALL
Java_com_example_demoplayvideo_decoder_OpusDecoder_nativeResetDecoder(
        JNIEnv *env,
        jobject thiz,
        jlong decoder_handle) {

    if (decoder_handle == 0) {
        LOGE("Invalid decoder handle for reset");
        return;
    }

    OpusDecoder *decoder = reinterpret_cast<OpusDecoder *>(decoder_handle);
    int result = opus_decoder_ctl(decoder, OPUS_RESET_STATE);

    if (result != OPUS_OK) {
        LOGE("Failed to reset decoder: %s", opus_strerror(result));
    } else {
        LOGI("Decoder reset successfully");
    }
}

JNIEXPORT void JNICALL
Java_com_example_demoplayvideo_decoder_OpusDecoder_nativeDestroyDecoder(
        JNIEnv *env,
        jobject thiz,
        jlong decoder_handle) {

    if (decoder_handle == 0) {
        LOGE("Invalid decoder handle for destroy");
        return;
    }

    OpusDecoder *decoder = reinterpret_cast<OpusDecoder *>(decoder_handle);
    opus_decoder_destroy(decoder);

    LOGI("✓ Decoder destroyed at %p", decoder);
}

// JNI_OnLoad - called when library is loaded
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("OpusJNI library loaded - Opus version: %s", opus_get_version_string());
    return JNI_VERSION_1_6;
}

} // extern "C"