#include <jni.h>
#include <vector>
#include "shottracer.h"

namespace {
struct Handle { STTracker *tracker; int width, height; };
Handle *get(jlong pointer) { return reinterpret_cast<Handle *>(pointer); }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_ajc_shottracer_NativeCore_create(JNIEnv *, jobject, jint w, jint h, jdouble x, jdouble y) {
    auto *tracker = st_create(w, h, x, y);
    return tracker ? reinterpret_cast<jlong>(new Handle{tracker, w, h}) : 0;
}
extern "C" JNIEXPORT void JNICALL
Java_com_ajc_shottracer_NativeCore_destroy(JNIEnv *, jobject, jlong pointer) {
    auto *handle = get(pointer);
    if (handle) { st_destroy(handle->tracker); delete handle; }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_ajc_shottracer_NativeCore_push(JNIEnv *env, jobject, jlong pointer, jintArray array, jdouble time) {
    auto *handle = get(pointer);
    if (!handle || !array || env->GetArrayLength(array) != handle->width * handle->height) return -1;
    const int n = handle->width * handle->height;
    std::vector<jint> pixels(n);
    env->GetIntArrayRegion(array, 0, n, pixels.data());
    if (env->ExceptionCheck()) return -1;
    std::vector<uint8_t> gray(n);
    for (int i = 0; i < n; ++i) {
        const auto p = uint32_t(pixels[i]);
        gray[i] = uint8_t((77 * ((p >> 16) & 255) + 150 * ((p >> 8) & 255) + 29 * (p & 255)) >> 8);
    }
    return st_push(handle->tracker, gray.data(), handle->width, time);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_ajc_shottracer_NativeCore_count(JNIEnv *, jobject, jlong pointer) {
    auto *handle = get(pointer);
    return handle ? st_count(handle->tracker) : 0;
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_ajc_shottracer_NativeCore_render(JNIEnv *env, jobject, jlong pointer, jdouble time) {
    auto *handle = get(pointer);
    if (!handle) return nullptr;
    const int n = handle->width * handle->height;
    std::vector<uint8_t> rgba(n * 4);
    if (st_render(handle->tracker, time, rgba.data(), handle->width * 4) < 0) return nullptr;
    std::vector<jint> pixels(n);
    for (int i = 0; i < n; ++i)
        pixels[i] = jint((uint32_t(rgba[i * 4 + 3]) << 24) | (uint32_t(rgba[i * 4]) << 16) |
                         (uint32_t(rgba[i * 4 + 1]) << 8) | rgba[i * 4 + 2]);
    auto array = env->NewIntArray(n);
    if (array) env->SetIntArrayRegion(array, 0, n, pixels.data());
    return array;
}
