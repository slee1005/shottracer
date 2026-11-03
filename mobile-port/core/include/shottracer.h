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
int st_camera_count(const STTracker *tracker);
int st_camera_point(const STTracker *tracker, int index, STPoint *point);
/* Start/apex/landing in normalized reference coordinates, actual flight seconds.
   Speed changes the drawing clock only. The apex and landing are exact. */
int st_curve_at(const double *controls, double flight, double apex_fraction,
                double speed, double time, double *x, double *y);
/* Transparent, premultiplied RGBA. Same function for preview and export.
   Output is at analysis resolution, for proportional scaling by native adapters.
   The native adapter adds the always-on product watermark to this same bitmap. */
int st_render(const STTracker *tracker, double time, uint8_t *rgba, int stride);
/* Desktop Model.at/apex_time semantics, without mutating the automatic model. */
int st_model_at(const STModel *model, double time, double *x, double *y);
double st_model_apex(const STModel *model);
/* Samples are (source seconds, reference x, reference y); t0 is in source seconds.
   The caller excludes the address seed. No observation/model is modified. */
int st_fit_model(const double *samples, int count, double x, double y, double horizon,
                 double t0_min, double t0_max, double min_flight, double max_flight,
                 double typical, STModel *model);
int st_refit_model(const STModel *base, const double *samples, int count,
                   const double *landing, double flight, STModel *model);
int st_with_apex(const STModel *base, const double *samples, int count, double apex_time, STModel *model);
#ifdef __cplusplus
}
#endif
#endif
