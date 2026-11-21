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
/* Reference-normalized coordinates -> frame-normalized coordinates. Old projects
   containing translation samples alone remain valid in the native adapters. */
typedef struct { double time, m00, m01, m02, m10, m11, m12; } STCameraTransform;
typedef struct {
    double u0, v0, vh, t0, a, c, b, g, w, k, flight_time;
    double height, lift, dv, dv_from, du;
} STModel;
STTracker *st_create(int width, int height, double x, double y);
/* Set before the first frame: 0 flight, 1 rolling template tracker. */
int st_set_mode(STTracker *tracker, int mode);
/* Before the first frame, enable the desktop analysis pipeline. flight=0 uses
   the full-shot prior; .2..15 uses the user's confirmed start/landing duration.
   Uses bounded frame buffers, source timestamps, LK/RANSAC camera similarity,
   three-frame motion, address departure, and the unchanged desktop flight fit. */
int st_use_desktop_tracking(STTracker *tracker, double flight);
/* Optional first upright RGBA image provides the grass-line horizon. No image is
   retained here. Without color, the desktop grayscale/fallback horizon is used. */
int st_reference_rgba(STTracker *tracker, const uint8_t *rgba, int stride);
/* Flush the short lookahead after imported-video decoding. Last-frame motion is
   not invented; only frames with both neighbouring images become observations. */
int st_finish(STTracker *tracker);
/* Three camera-aligned gray frames plus the current person probability mask.
   Returns 1 observed club tip, 0 missing, -1 invalid. Never invents detections. */
int st_club_point(const uint8_t *previous, const uint8_t *current, const uint8_t *next,
                  const uint8_t *body, int width, int height, double hx, double hy,
                  double person_height, double previous_dx, double previous_dy,
                  double next_dx, double next_dy, STPoint *point);
void st_destroy(STTracker *tracker);
/* Strictly increasing timestamps; first frame establishes the address spot.
   Returns -1 invalid input, 0 waiting/missing, 1 tracking, 2 track ended.
   Desktop analysis retains two previous images, with a short launch lookahead;
   the legacy path retains one. Caller serializes mutations and rendering. */
int st_push(STTracker *tracker, const uint8_t *gray, int stride, double time);
int st_count(const STTracker *tracker);
/* Validated automatic flight, in normalized reference coordinates. Returns -1
   until launch is validated, or for a rolling putt. The desktop pipeline uses
   three detections plus address departure; the legacy path requires five. */
int st_tracker_model(const STTracker *tracker, STModel *model);
int st_point(const STTracker *tracker, int index, STPoint *point);
int st_camera_count(const STTracker *tracker);
int st_camera_point(const STTracker *tracker, int index, STPoint *point);
int st_camera_transform(const STTracker *tracker, int index, STCameraTransform *transform);
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
/* Contiguous observed (seconds, image pixel x/y), at most two seconds.
   Returns [span, dx, dy, median projected speed px/s, count], or -1 missing/invalid.
   This is NOT a physical launch angle, club speed or carry measurement. */
int st_motion_summary(const double *samples, int count, double *summary);
/* Approximate display timing and shape from optional distances in metres.
   Nonpositive inputs are unset. Roll ratio is relative to projected carry. */
void st_shot_info_profile(double carry, double total, double height, double source_flight,
                          double *flight, double *rise_scale, double *roll_ratio);
#ifdef __cplusplus
}
#endif
#endif
