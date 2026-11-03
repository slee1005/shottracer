#include "shottracer.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <vector>

namespace {
struct Camera { double time, x, y; };
struct Candidate { double x, y, strength; };
double length(double x, double y) { return std::hypot(x, y); }
double travel(double t, double k) { return -std::expm1(-k * t) / k; }
}

struct STTracker {
    int width, height;
    double seed_x, seed_y, last_time = -1, camera_x = 0, camera_y = 0;
    double pending_start_time = 0;
    bool launched = false, ended = false;
    std::vector<uint8_t> previous;
    std::vector<STPoint> points, pending;
    std::vector<Camera> cameras;
};

extern "C" STTracker *st_create(int w, int h, double x, double y) {
    if (w < 16 || h < 16 || w > 960 || h > 960 || !std::isfinite(x) ||
        !std::isfinite(y) || x < 3 || y < 3 || x >= w - 3 || y >= h - 3) return nullptr;
    try {
        auto *tracker = new STTracker{};
        tracker->width = w; tracker->height = h;
        tracker->seed_x = x; tracker->seed_y = y;
        return tracker;
    } catch (...) { return nullptr; }
}
extern "C" void st_destroy(STTracker *t) { delete t; }

namespace {
/* Translation-only prototype. This is deliberately distinct from the desktop
   LK/RANSAC similarity estimator. Deterministic tie breaking avoids flat-field drift. */
std::pair<int, int> translation(const STTracker &t, const uint8_t *gray, int stride) {
    double best = std::numeric_limits<double>::infinity();
    int bx = 0, by = 0;
    for (int dy = -6; dy <= 6; ++dy) for (int dx = -6; dx <= 6; ++dx) {
        double sum = 0;
        int n = 0;
        for (int y = 8; y < t.height - 8; y += 9) for (int x = 8; x < t.width - 8; x += 9) {
            if (length(x - t.seed_x - t.camera_x, y - t.seed_y - t.camera_y) < 24) continue;
            if (gray[y * stride + x] >= 140 || t.previous[(y - dy) * t.width + x - dx] >= 140) continue;
            sum += std::abs(int(gray[y * stride + x]) - int(t.previous[(y - dy) * t.width + x - dx]));
            ++n;
        }
        const double score = sum / std::max(1, n) + 0.002 * (dx * dx + dy * dy);
        if (score < best) { best = score; bx = dx; by = dy; }
    }
    return {bx, by};
}

std::vector<Candidate> candidates(const STTracker &t, const uint8_t *gray, int stride, int dx, int dy) {
    const int w = t.width, h = t.height;
    std::vector<uint8_t> mask(w * h, 0);
    for (int y = 1; y < h - 1; ++y) for (int x = 1; x < w - 1; ++x) {
        const int px = x - dx, py = y - dy;
        if (px < 0 || py < 0 || px >= w || py >= h) continue;
        const int value = gray[y * stride + x];
        if (value >= 150 && value - t.previous[py * w + px] > 24) mask[y * w + x] = 1;
    }
    std::vector<Candidate> result;
    std::vector<int> todo;
    for (int y = 1; y < h - 1; ++y) for (int x = 1; x < w - 1; ++x) {
        const int first = y * w + x;
        if (!mask[first]) continue;
        todo.clear(); todo.push_back(first); mask[first] = 0;
        double sx = 0, sy = 0, strength = 0;
        int minx = x, maxx = x, miny = y, maxy = y;
        for (size_t i = 0; i < todo.size(); ++i) {
            const int p = todo[i], yy = p / w, xx = p % w;
            sx += xx; sy += yy; strength += gray[yy * stride + xx];
            minx = std::min(minx, xx); maxx = std::max(maxx, xx);
            miny = std::min(miny, yy); maxy = std::max(maxy, yy);
            for (const int delta : {-w, w, -1, 1}) {
                const int q = p + delta;
                if (q >= 0 && q < w * h && mask[q]) { mask[q] = 0; todo.push_back(q); }
            }
        }
        const double n = double(todo.size());
        if (n >= 2 && n <= 180 && maxx - minx <= 22 && maxy - miny <= 22)
            result.push_back({sx / n - t.camera_x, sy / n - t.camera_y, strength / n / 255.0});
    }
    return result;
}

bool direction(double ax, double ay, double bx, double by) {
    const double a = length(ax, ay), b = length(bx, by);
    return a > 0 && b > 0 && (ax * bx + ay * by) / (a * b) > 0.866;
}

Camera camera_at(const STTracker &t, double time) {
    if (t.cameras.empty()) return {time, 0, 0};
    auto next = std::lower_bound(t.cameras.begin(), t.cameras.end(), time,
                                [](const Camera &c, double v) { return c.time < v; });
    if (next == t.cameras.begin()) return *next;
    if (next == t.cameras.end()) return t.cameras.back();
    const auto &a = *(next - 1);
    const double f = (time - a.time) / (next->time - a.time);
    return {time, a.x + f * (next->x - a.x), a.y + f * (next->y - a.y)};
}
}

extern "C" int st_push(STTracker *t, const uint8_t *gray, int stride, double time) {
    if (!t || !gray || stride < t->width || !std::isfinite(time) || time < 0 ||
        time <= t->last_time || t->cameras.size() >= 2000) return -1;
    try {
        const bool first = t->previous.empty();
        if (!first) {
            const auto [dx, dy] = translation(*t, gray, stride);
            t->camera_x += dx; t->camera_y += dy;
            if (!t->ended) {
                auto options = candidates(*t, gray, stride, dx, dy);
                if (!t->launched) {
                    /* Require the address ball to disappear before accepting a launch. */
                    const int sx = int(std::round(t->seed_x + t->camera_x));
                    const int sy = int(std::round(t->seed_y + t->camera_y));
                    const bool gone = sx < 0 || sy < 0 || sx >= t->width || sy >= t->height ||
                                      gray[sy * stride + sx] < 140;
                    const STPoint origin = t->pending.empty() ?
                        STPoint{t->last_time, t->seed_x, t->seed_y, 1} : t->pending.back();
                    double best = 1e30;
                    Candidate chosen{};
                    bool found = false;
                    for (const auto &c : options) {
                        const double vx = c.x - origin.x, vy = c.y - origin.y;
                        const double d = length(vx, vy);
                        if (!gone || d < 1.5 || d > 100 || vy > -0.3 * d) continue;
                        if (!t->pending.empty()) {
                            const auto &a = t->pending.size() == 1 ?
                                STPoint{t->pending_start_time, t->seed_x, t->seed_y, 1} :
                                t->pending[t->pending.size() - 2];
                            const double ax = origin.x - a.x, ay = origin.y - a.y;
                            const double ratio = (d / (time - origin.time)) /
                                std::max(1e-6, length(ax, ay) / (origin.time - a.time));
                            if (!direction(ax, ay, vx, vy) || ratio < 0.3 || ratio > (t->pending.size() == 1 ? 2.5 : 1.1)) continue;
                        }
                        if (d < best) { best = d; chosen = c; found = true; }
                    }
                    if (found) {
                        if (t->pending.empty()) t->pending_start_time = t->last_time;
                        t->pending.push_back({time, chosen.x, chosen.y, chosen.strength});
                        if (t->pending.size() == 3) {
                            t->points.push_back({t->pending_start_time, t->seed_x, t->seed_y, 1});
                            t->points.insert(t->points.end(), t->pending.begin(), t->pending.end());
                            t->launched = true;
                        }
                    } else { t->pending.clear(); }
                } else {
                    const auto &p = t->points.back(), &a = t->points[t->points.size() - 2];
                    const double dt = p.time - a.time;
                    const double factor = (time - p.time) / dt;
                    const double px = p.x + (p.x - a.x) * factor, py = p.y + (p.y - a.y) * factor;
                    const double gate = std::max(7.0, 0.7 * length(p.x - a.x, p.y - a.y) * factor);
                    double best = gate;
                    const Candidate *chosen = nullptr;
                    for (const auto &c : options) {
                        const double d = length(c.x - px, c.y - py);
                        if (d < best) { best = d; chosen = &c; }
                    }
                    if (chosen) t->points.push_back({time, chosen->x, chosen->y, chosen->strength});
                    else if (time - p.time >= 0.2) t->ended = true;
                }
            }
        }
        t->cameras.push_back({time, t->camera_x, t->camera_y});
        t->previous.resize(t->width * t->height);
        for (int y = 0; y < t->height; ++y)
            std::memcpy(t->previous.data() + y * t->width, gray + y * stride, t->width);
        t->last_time = time;
        return t->ended ? 2 : t->launched ? 1 : 0;
    } catch (...) { return -1; }
}
extern "C" int st_count(const STTracker *t) { return t ? int(t->points.size()) : 0; }
extern "C" int st_camera_count(const STTracker *t) { return t ? int(t->cameras.size()) : 0; }
extern "C" int st_camera_point(const STTracker *t, int i, STPoint *p) {
    if (!t || !p || i < 0 || i >= int(t->cameras.size())) return -1;
    const auto &c = t->cameras[size_t(i)];
    *p = {c.time, c.x, c.y, 1}; return 0;
}
extern "C" int st_curve_at(const double *p, double flight, double apex, double speed,
                            double time, double *x, double *y) {
    if (!p || !x || !y || !std::isfinite(flight) || flight <= 0 ||
        !std::isfinite(speed) || speed < .5 || speed > 4 || !std::isfinite(time) || !std::isfinite(apex)) return -1;
    for (int i = 0; i < 6; ++i) if (!std::isfinite(p[i])) return -1;
    const double u = std::clamp(time * speed / flight, 0.0, 1.0);
    const double a = std::clamp(apex, .35, .62);
    if (u <= a) {
        const double q = u / a, ease = 1 - (1 - q) * (1 - q);
        *x = p[0] + (p[2] - p[0]) * ease;
        *y = p[1] + (p[3] - p[1]) * ease;
    } else {
        const double q = (u - a) / (1 - a);
        *x = p[2] + (p[4] - p[2]) * q;
        *y = p[3] + (p[5] - p[3]) * q * q;
    }
    return 0;
}
extern "C" int st_point(const STTracker *t, int i, STPoint *p) {
    if (!t || !p || i < 0 || i >= int(t->points.size())) return -1;
    *p = t->points[size_t(i)]; return 0;
}
extern "C" int st_render(const STTracker *t, double time, uint8_t *rgba, int stride) {
    if (!t || !rgba || stride < t->width * 4 || !std::isfinite(time)) return -1;
    for (int y = 0; y < t->height; ++y) std::memset(rgba + y * stride, 0, t->width * 4);
    if (t->points.size() < 4 || time < t->points[1].time || time > t->points.back().time + 1) return 0;
    const auto camera = camera_at(*t, time);
    const int radius = std::max(1, t->width / 240);
    auto dot = [&](double x, double y) {
        if (x < -radius || y < -radius || x > t->width + radius || y > t->height + radius) return;
        const int cx = int(std::round(x)), cy = int(std::round(y));
        for (int yy = std::max(0, cy - radius); yy <= std::min(t->height - 1, cy + radius); ++yy)
            for (int xx = std::max(0, cx - radius); xx <= std::min(t->width - 1, cx + radius); ++xx) {
                if ((xx - cx) * (xx - cx) + (yy - cy) * (yy - cy) > radius * radius) continue;
                uint8_t *pixel = rgba + yy * stride + xx * 4;
                pixel[0] = 255; pixel[1] = 48; pixel[2] = 60; pixel[3] = 255;
            }
    };
    for (size_t i = 1; i < t->points.size(); ++i) {
        const auto &a = t->points[i - 1], &b = t->points[i];
        if (time <= a.time) break;
        const double f = std::clamp((time - a.time) / (b.time - a.time), 0.0, 1.0);
        const double dx = (b.x - a.x) * f, dy = (b.y - a.y) * f;
        const int steps = std::max(1, int(std::ceil(length(dx, dy) * 2)));
        for (int j = 0; j <= steps; ++j)
            dot(a.x + dx * j / steps + camera.x, a.y + dy * j / steps + camera.y);
    }
    return 0;
}

extern "C" int st_model_at(const STModel *m, double t, double *x, double *y) {
    if (!m || !x || !y || !std::isfinite(t) || m->k <= 0 || m->flight_time <= 0) return -1;
    const double s = travel(t, m->k), den = 1 + m->w * s;
    if (den <= 0) return -1;
    *x = m->u0 + (m->a * s + m->c * t * t) / den;
    const double rise = m->b * s - m->g * t * t + m->lift * t * t * t;
    *y = m->vh + (m->v0 - m->vh - m->height * rise) / den;
    const double f = std::clamp((t - m->dv_from) / std::max(m->flight_time - m->dv_from, 1e-6), 0.0, 1.0);
    *x += m->du * f * f; *y += m->dv * f * f;
    return std::isfinite(*x) && std::isfinite(*y) ? 0 : -1;
}
extern "C" double st_model_apex(const STModel *m) {
    if (!m || m->k <= 0 || m->flight_time <= 0) return -1;
    double best = -std::numeric_limits<double>::infinity(), time = 0;
    for (int i = 0; i <= 2000; ++i) {
        const double t = m->flight_time * i / 2000;
        const double rise = m->b * travel(t, m->k) - m->g * t * t + m->lift * t * t * t;
        if (rise > best) { best = rise; time = t; }
    }
    return time;
}

namespace {
bool least2(double aa, double ab, double bb, double ar, double br, double &x, double &y) {
    const double det = aa * bb - ab * ab;
    if (std::abs(det) < 1e-30) return false;
    x = (ar * bb - br * ab) / det; y = (br * aa - ar * ab) / det;
    return std::isfinite(x) && std::isfinite(y);
}
double landing_time(const STModel &m) {
    if (m.g <= 1e-9 || m.b <= 0 || m.b * travel(30, m.k) - m.g * 900 > 0)
        return std::numeric_limits<double>::infinity();
    double lo = .001, hi = 30;
    for (int i = 0; i < 60; ++i) { const double t = (lo + hi) / 2; if (m.b * travel(t, m.k) > m.g * t * t) lo = t; else hi = t; }
    return (lo + hi) / 2;
}
STModel tie_gravity(STModel m, const double *samples, int count, double duration) {
    const double sT = travel(duration, m.k);
    double aa = 0, ar = 0;
    for (int i = 0; i < count; ++i) {
        const double t = samples[i * 3] - m.t0, s = travel(t, m.k), den = 1 + m.w * s;
        const double basis = (s - sT * t * t / (duration * duration)) / den;
        const double rhs = (m.v0 - m.vh) / den + m.vh - samples[i * 3 + 2];
        aa += basis * basis; ar += basis * rhs;
    }
    m.b = ar / std::max(aa, 1e-12); m.g = m.b * sT / (duration * duration); m.flight_time = duration;
    m.height = 1; m.lift = 0; m.du = m.dv = m.dv_from = 0;
    return m;
}
bool solve_model(const double *samples, int count, double x, double y, double horizon,
                  double t0, double w, double minT, double maxT, double typical,
                  double explicitT, STModel &m, double &error) {
    double aa = 0, ab = 0, bb = 0, ax = 0, bx = 0, ay = 0, by = 0;
    for (int i = 0; i < count; ++i) {
        const double t = samples[i * 3] - t0;
        if (t <= 0) return false;
        const double s = travel(t, .35), den = 1 + w * s, a = s / den, b = t * t / den;
        const double rhs = (y - horizon) / den + horizon - samples[i * 3 + 2];
        aa += a * a; ab += a * b; bb += b * b;
        ax += a * (samples[i * 3 + 1] - x); bx += b * (samples[i * 3 + 1] - x);
        ay += a * rhs; by += -b * rhs;
    }
    m = {}; m.u0 = x; m.v0 = y; m.vh = horizon; m.t0 = t0; m.w = w; m.k = .35; m.height = 1;
    if (!least2(aa, ab, bb * 2, ax, bx, m.a, m.c) || !least2(aa, -ab, bb, ay, by, m.b, m.g)) return false;
    m.g = std::max(0.0, m.g);
    const double duration = landing_time(m), observed = samples[(count - 1) * 3] - t0;
    if (explicitT > 0) m = tie_gravity(m, samples, count, explicitT);
    else if (observed < .35 * std::min(duration, maxT)) m = tie_gravity(m, samples, count, typical);
    else if (duration >= minT && duration <= maxT) m.flight_time = duration;
    else m = tie_gravity(m, samples, count, std::clamp(duration, minT, maxT));
    error = 0;
    for (int i = 0; i < count; ++i) {
        double u, v;
        if (st_model_at(&m, samples[i * 3] - t0, &u, &v) != 0) return false;
        error += std::pow(u - samples[i * 3 + 1], 2) + std::pow(v - samples[i * 3 + 2], 2);
    }
    return std::isfinite(error);
}
bool valid_samples(const double *p, int count) {
    if (!p || count < 3 || count > 2000) return false;
    for (int i = 0; i < count * 3; ++i) if (!std::isfinite(p[i])) return false;
    for (int i = 1; i < count; ++i) if (p[i * 3] <= p[(i - 1) * 3]) return false;
    return true;
}
}
extern "C" int st_fit_model(const double *p, int count, double x, double y, double horizon,
                             double lo, double hi, double minT, double maxT, double typical, STModel *out) {
    if (!out || !valid_samples(p, count) || !(lo <= hi && hi < p[0]) || minT <= 0 || maxT < minT || typical < minT || typical > maxT) return -1;
    double best = std::numeric_limits<double>::infinity(); bool found = false;
    for (int i = 0; i <= 10; ++i) for (int j = 0; j <= 60; ++j) {
        const double t0 = lo + (hi - lo) * i / 10, w = j == 0 ? 0 : .05 * std::pow(800.0, (j - 1) / 59.0);
        STModel m{}; double error;
        if (solve_model(p, count, x, y, horizon, t0, w, minT, maxT, typical, 0, m, error) && error < best) { best = error; *out = m; found = true; }
    }
    return found ? 0 : -1;
}
extern "C" int st_refit_model(const STModel *base, const double *p, int count, const double *landing,
                               double flight, STModel *out) {
    if (!base || !out || !valid_samples(p, count) || !std::isfinite(flight) || flight < .2 || flight > 15) return -1;
    double error;
    if (!solve_model(p, count, base->u0, base->v0, base->vh, base->t0, base->w, flight, flight, flight, flight, *out, error)) return -1;
    if (landing) {
        if (!std::isfinite(landing[0]) || !std::isfinite(landing[1])) return -1;
        double x, y; st_model_at(out, flight, &x, &y);
        out->du = landing[0] - x; out->dv = landing[1] - y;
        out->dv_from = std::clamp(p[(count - 1) * 3] - base->t0, 0.0, .6 * flight);
    }
    return 0;
}
extern "C" int st_with_apex(const STModel *base, const double *p, int count, double apex, STModel *out) {
    if (!base || !out || !valid_samples(p, count) || !std::isfinite(apex) || base->flight_time <= 0) return -1;
    const double T = base->flight_time, ta = std::clamp(apex, .35 * T, .62 * T);
    const double a = -T * T, b = T * T * T, c = -2 * ta, d = 3 * ta * ta;
    const double r1 = -travel(T, base->k), r2 = -std::exp(-base->k * ta), det = a * d - b * c;
    if (std::abs(det) < 1e-12) return -1;
    const double gu = (r1 * d - b * r2) / det, lu = (a * r2 - r1 * c) / det;
    double aa = 0, ar = 0;
    for (int i = 0; i < count; ++i) {
        const double t = p[i * 3] - base->t0; if (t <= 0) continue;
        const double s = travel(t, base->k), den = 1 + base->w * s;
        const double basis = (s - gu * t * t + lu * t * t * t) / den;
        aa += basis * basis; ar += basis * ((base->v0 - base->vh) / den + base->vh - p[i * 3 + 2]);
    }
    const double launch = ar / std::max(aa, 1e-12);
    *out = *base; out->b = launch > 0 ? launch : base->b; out->g = gu * out->b; out->lift = lu * out->b;
    return 0;
}
