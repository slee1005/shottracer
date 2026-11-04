#include <jni.h>
#include <vector>
#include <cstring>
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
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_ajc_shottracer_NativeCore_samples(JNIEnv *env, jobject, jlong pointer, jboolean camera) {
    auto *h = get(pointer); if (!h) return nullptr;
    const int count = camera ? st_camera_count(h->tracker) : st_count(h->tracker);
    std::vector<jdouble> result(count * 3);
    for (int i = 0; i < count; ++i) {
        STPoint p{};
        if (camera) st_camera_point(h->tracker, i, &p); else st_point(h->tracker, i, &p);
        result[i * 3] = p.time; result[i * 3 + 1] = p.x / h->width; result[i * 3 + 2] = p.y / h->height;
    }
    auto output = env->NewDoubleArray(count * 3);
    if (output) env->SetDoubleArrayRegion(output, 0, count * 3, result.data());
    return output;
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_ajc_shottracer_NativeCore_curve(JNIEnv *env, jobject, jdoubleArray controls,
                                      jdouble flight, jdouble apex, jdouble speed, jdouble time) {
    if (env->GetArrayLength(controls) != 6) return nullptr;
    double p[6], result[2]; env->GetDoubleArrayRegion(controls, 0, 6, p);
    if (st_curve_at(p, flight, apex, speed, time, &result[0], &result[1]) != 0) return nullptr;
    auto output = env->NewDoubleArray(2);
    if (output) env->SetDoubleArrayRegion(output, 0, 2, result);
    return output;
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

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_ajc_shottracer_NativeCore_fit(JNIEnv *env, jobject, jdoubleArray points, jdouble x, jdouble y,
                                    jdouble lo, jdouble hi) {
    const int size = env->GetArrayLength(points);
    if (size % 3 != 0) return nullptr;
    std::vector<double> p(size); env->GetDoubleArrayRegion(points, 0, size, p.data());
    STModel model{};
    if (st_fit_model(p.data(), size / 3, x, y, .15, lo, hi, 3.5, 7.5, 5.5, &model) != 0) return nullptr;
    static_assert(sizeof(STModel) == sizeof(double) * 16);
    double values[16]; std::memcpy(values, &model, sizeof(model));
    auto out = env->NewDoubleArray(16); if (out) env->SetDoubleArrayRegion(out, 0, 16, values); return out;
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_ajc_shottracer_NativeCore_refit(JNIEnv *env, jobject, jdoubleArray base, jdoubleArray points,
                                      jdoubleArray landing, jdouble flight) {
    if (env->GetArrayLength(base) != 16) return nullptr;
    const int size = env->GetArrayLength(points); if (size % 3 != 0) return nullptr;
    double values[16]; env->GetDoubleArrayRegion(base, 0, 16, values); STModel model{}, result{};
    std::memcpy(&model, values, sizeof(model));
    std::vector<double> p(size); env->GetDoubleArrayRegion(points, 0, size, p.data());
    double target[2]; if (landing) { if (env->GetArrayLength(landing) != 2) return nullptr; env->GetDoubleArrayRegion(landing, 0, 2, target); }
    if (st_refit_model(&model, p.data(), size / 3, landing ? target : nullptr, flight, &result) != 0) return nullptr;
    std::memcpy(values, &result, sizeof(result)); auto out = env->NewDoubleArray(16);
    if (out) env->SetDoubleArrayRegion(out, 0, 16, values); return out;
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_ajc_shottracer_NativeCore_modelPoint(JNIEnv *env, jobject, jdoubleArray values, jdouble time) {
    if (env->GetArrayLength(values) != 16) return nullptr;
    double p[16], result[2]; env->GetDoubleArrayRegion(values, 0, 16, p); STModel model{}; std::memcpy(&model, p, sizeof(model));
    if (st_model_at(&model, time, &result[0], &result[1]) != 0) return nullptr;
    auto out = env->NewDoubleArray(2); if (out) env->SetDoubleArrayRegion(out, 0, 2, result); return out;
}
extern "C" JNIEXPORT jdouble JNICALL
Java_com_ajc_shottracer_NativeCore_modelApex(JNIEnv *env, jobject, jdoubleArray values) {
    if (env->GetArrayLength(values) != 16) return -1;
    double p[16]; env->GetDoubleArrayRegion(values, 0, 16, p); STModel m{}; std::memcpy(&m, p, sizeof(m)); return st_model_apex(&m);
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_ajc_shottracer_NativeCore_reshape(JNIEnv *env, jobject, jdoubleArray values, jdoubleArray points, jdouble apex) {
    if (env->GetArrayLength(values) != 16) return nullptr;
    const int size = env->GetArrayLength(points); if (size % 3) return nullptr;
    double p[16]; env->GetDoubleArrayRegion(values, 0, 16, p); STModel base{}, result{}; std::memcpy(&base, p, sizeof(base));
    std::vector<double> rows(size); env->GetDoubleArrayRegion(points, 0, size, rows.data());
    if (st_with_apex(&base, rows.data(), size / 3, apex, &result)) return nullptr;
    std::memcpy(p, &result, sizeof(result)); auto out = env->NewDoubleArray(16); if (out) env->SetDoubleArrayRegion(out, 0, 16, p); return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ajc_shottracer_NativeCore_mode(JNIEnv *, jobject, jlong pointer, jint mode) {
    auto *h = get(pointer); return h ? st_set_mode(h->tracker, mode) : -1;
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_ajc_shottracer_NativeCore_club(JNIEnv *env, jobject, jbyteArray previous, jbyteArray current,
    jbyteArray next, jbyteArray body, jint w, jint h, jdoubleArray pose, jdoubleArray motion) {
    const int n = w*h;
    if (w < 16 || h < 16 || w > 960 || h > 960 || env->GetArrayLength(previous) != n || env->GetArrayLength(current) != n ||
        env->GetArrayLength(next) != n || env->GetArrayLength(body) != n || env->GetArrayLength(pose) != 3 || env->GetArrayLength(motion) != 4) return nullptr;
    std::vector<uint8_t> p(n),c(n),q(n),m(n); double a[3],d[4];
    env->GetByteArrayRegion(previous,0,n,reinterpret_cast<jbyte*>(p.data())); env->GetByteArrayRegion(current,0,n,reinterpret_cast<jbyte*>(c.data()));
    env->GetByteArrayRegion(next,0,n,reinterpret_cast<jbyte*>(q.data())); env->GetByteArrayRegion(body,0,n,reinterpret_cast<jbyte*>(m.data()));
    env->GetDoubleArrayRegion(pose,0,3,a); env->GetDoubleArrayRegion(motion,0,4,d); STPoint point{};
    if (st_club_point(p.data(),c.data(),q.data(),m.data(),w,h,a[0],a[1],a[2],d[0],d[1],d[2],d[3],&point) != 1) return nullptr;
    const double xy[2] = {point.x/w, point.y/h}; auto out=env->NewDoubleArray(2); if(out) env->SetDoubleArrayRegion(out,0,2,xy); return out;
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_ajc_shottracer_NativeCore_motion(JNIEnv *env, jobject, jdoubleArray points) {
    if (!points) return nullptr;
    const int n=env->GetArrayLength(points);
    if (n%3 || n<9 || n>6000) return nullptr;
    std::vector<double> samples(n); double result[5];
    env->GetDoubleArrayRegion(points,0,n,samples.data());
    if (env->ExceptionCheck() || st_motion_summary(samples.data(),n/3,result)) return nullptr;
    auto output=env->NewDoubleArray(5);
    if(output) env->SetDoubleArrayRegion(output,0,5,result);
    return output;
}
