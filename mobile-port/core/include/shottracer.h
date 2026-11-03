#ifndef SHOTTRACER_MOBILE_H
#define SHOTTRACER_MOBILE_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* All tracker coordinates are upright analysis pixels. Time is source seconds,
   never frame number: mobile footage may have variable frame rate. */
typedef struct STTracker STTracker;
typedef struct { double time, x, y, confidence; } STPoint;
typedef struct {
    double u0, v0, vh, t0, a, c, b, g, w, k, flight_time;
    double height, lift, dv, dv_from, du;
} STModel;
STTracker *st_create(int width, int height, double x, double y);
void st_destroy(STTracker *tracker);
/* Strictly increasing timestamps; first frame establishes the address spot.
   Returns -1 invalid input, 0 waiting/missing, 1 tracking, 2 track ended.
   Only one frame is retained. Caller serializes mutations and rendering. */
int st_push(STTracker *tracker, const uint8_t *gray, int stride, double time);
int st_count(const STTracker *tracker);
int st_point(const STTracker *tracker, int index, STPoint *point);
/* Transparent, premultiplied RGBA. Same function for preview and export.
   Output is at analysis resolution, for proportional scaling by native adapters.
   The native adapter adds the always-on product watermark to this same bitmap. */
int st_render(const STTracker *tracker, double time, uint8_t *rgba, int stride);
/* Desktop Model.at/apex_time semantics, without mutating the automatic model. */
int st_model_at(const STModel *model, double time, double *x, double *y);
double st_model_apex(const STModel *model);
#ifdef __cplusplus
}
#endif
#endif
