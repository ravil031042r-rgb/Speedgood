#include <jni.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>
#include <stdint.h>
#include <math.h>
#include <atomic>
#include <string>
#include <shadowhook.h>

namespace {
constexpr int MAX_CLOCK_ID = 16;
struct ClockAnchor { std::atomic<uint64_t> seq{0}; bool initialized{false}; int64_t real_base_ns{0}; int64_t virt_base_ns{0}; double speed{1.0}; };
struct TodAnchor { std::atomic<uint64_t> seq{0}; bool initialized{false}; int64_t real_base_us{0}; int64_t virt_base_us{0}; double speed{1.0}; };
ClockAnchor g_clocks[MAX_CLOCK_ID];
TodAnchor g_tod;
std::atomic_flag g_write_lock = ATOMIC_FLAG_INIT;
std::atomic<bool> g_initialized{false};
std::atomic<double> g_speed{1.0};
std::atomic<bool> g_hook_realtime{true};
std::atomic<bool> g_hook_sleep{false};
std::string g_status_msg = "Not initialized";

using fn_clock_gettime_t = int (*)(clockid_t, struct timespec*);
using fn_gettimeofday_t = int (*)(struct timeval*, struct timezone*);
using fn_nanosleep_t = int (*)(const struct timespec*, struct timespec*);
using fn_usleep_t = int (*)(useconds_t);
fn_clock_gettime_t orig_clock_gettime = nullptr;
fn_gettimeofday_t orig_gettimeofday = nullptr;
fn_nanosleep_t orig_nanosleep = nullptr;
fn_usleep_t orig_usleep = nullptr;

inline void lock_w() { while (g_write_lock.test_and_set(std::memory_order_acquire)) {} }
inline void unlock_w() { g_write_lock.clear(std::memory_order_release); }
inline int64_t to_ns(const struct timespec& ts) { return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec; }
inline void from_ns(int64_t ns, struct timespec* ts) { if (ns < 0) ns = 0; ts->tv_sec = ns / 1000000000LL; ts->tv_nsec = ns % 1000000000LL; }
inline int64_t to_us(const struct timeval& tv) { return (int64_t)tv.tv_sec * 1000000LL + (int64_t)tv.tv_usec; }
inline void from_us(int64_t us, struct timeval* tv) { if (us < 0) us = 0; tv->tv_sec = us / 1000000LL; tv->tv_usec = us % 1000000LL; }
inline bool is_mono(clockid_t id) { return id == CLOCK_MONOTONIC || id == CLOCK_MONOTONIC_RAW || id == CLOCK_MONOTONIC_COARSE || id == CLOCK_BOOTTIME; }
inline bool is_rt(clockid_t id) { return id == CLOCK_REALTIME || id == CLOCK_REALTIME_COARSE; }

void update_anchors(double new_spd, bool new_rt) {
    lock_w();
    const clockid_t ids[] = {CLOCK_REALTIME, CLOCK_MONOTONIC, CLOCK_MONOTONIC_RAW, CLOCK_REALTIME_COARSE, CLOCK_MONOTONIC_COARSE, CLOCK_BOOTTIME};
    for (clockid_t id : ids) {
        if (id < 0 || id >= MAX_CLOCK_ID || !orig_clock_gettime) continue;
        struct timespec ts{};
        if (orig_clock_gettime(id, &ts) != 0) continue;
        int64_t r = to_ns(ts);
        double eff = is_rt(id) ? (new_rt ? new_spd : 1.0) : new_spd;
        ClockAnchor& c = g_clocks[id];
        int64_t v = c.initialized ? c.virt_base_ns + (int64_t)((double)((r - c.real_base_ns) > 0 ? (r - c.real_base_ns) : 0) * c.speed) : r;
        uint64_t s = c.seq.load(std::memory_order_relaxed);
        c.seq.store(s + 1, std::memory_order_release);
        c.real_base_ns = r; c.virt_base_ns = v; c.speed = eff; c.initialized = true;
        c.seq.store(s + 2, std::memory_order_release);
    }
    if (orig_gettimeofday) {
        struct timeval tv{};
        if (orig_gettimeofday(&tv, nullptr) == 0) {
            int64_t r = to_us(tv);
            double eff = new_rt ? new_spd : 1.0;
            int64_t v = g_tod.initialized ? g_tod.virt_base_us + (int64_t)((double)((r - g_tod.real_base_us) > 0 ? (r - g_tod.real_base_us) : 0) * g_tod.speed) : r;
            uint64_t s = g_tod.seq.load(std::memory_order_relaxed);
            g_tod.seq.store(s + 1, std::memory_order_release);
            g_tod.real_base_us = r; g_tod.virt_base_us = v; g_tod.speed = eff; g_tod.initialized = true;
            g_tod.seq.store(s + 2, std::memory_order_release);
        }
    }
    g_speed.store(new_spd, std::memory_order_release);
    g_hook_realtime.store(new_rt, std::memory_order_release);
    unlock_w();
}

int my_clock_gettime(clockid_t id, struct timespec* ts) {
    int ret = orig_clock_gettime(id, ts);
    if (ret != 0 || !ts || id < 0 || id >= MAX_CLOCK_ID) return ret;
    if (!is_mono(id) && !(is_rt(id) && g_hook_realtime.load(std::memory_order_relaxed))) return ret;
    ClockAnchor& c = g_clocks[id];
    int64_t r = to_ns(*ts), rb = 0, vb = 0; double spd = 1.0; bool init = false;
    for (int i = 0; i < 8; ++i) {
        uint64_t s1 = c.seq.load(std::memory_order_acquire);
        if (s1 & 1ULL) continue;
        init = c.initialized; rb = c.real_base_ns; vb = c.virt_base_ns; spd = c.speed;
        if (s1 == c.seq.load(std::memory_order_acquire)) break;
    }
    if (!init) return ret;
    int64_t d = r - rb; if (d < 0) d = 0;
    from_ns(vb + (int64_t)((double)d * spd), ts);
    return ret;
}

int my_gettimeofday(struct timeval* tv, struct timezone* tz) {
    int ret = orig_gettimeofday(tv, tz);
    if (ret != 0 || !tv || !g_hook_realtime.load(std::memory_order_relaxed)) return ret;
    int64_t r = to_us(*tv), rb = 0, vb = 0; double spd = 1.0; bool init = false;
    for (int i = 0; i < 8; ++i) {
        uint64_t s1 = g_tod.seq.load(std::memory_order_acquire);
        if (s1 & 1ULL) continue;
        init = g_tod.initialized; rb = g_tod.real_base_us; vb = g_tod.virt_base_us; spd = g_tod.speed;
        if (s1 == g_tod.seq.load(std::memory_order_acquire)) break;
    }
    if (!init) return ret;
    int64_t d = r - rb; if (d < 0) d = 0;
    from_us(vb + (int64_t)((double)d * spd), tv);
    return ret;
}

int my_nanosleep(const struct timespec* req, struct timespec* rem) {
    if (!req || !g_hook_sleep.load(std::memory_order_relaxed)) return orig_nanosleep(req, rem);
    double spd = g_speed.load(std::memory_order_relaxed);
    if (spd <= 0.05 || fabs(spd - 1.0) < 0.01) return orig_nanosleep(req, rem);
    int64_t ns = (int64_t)((double)to_ns(*req) / spd);
    if (spd > 1.0 && ns < 100000LL) ns = 100000LL;
    struct timespec m{}; from_ns(ns, &m);
    return orig_nanosleep(&m, rem);
}

int my_usleep(useconds_t usec) {
    if (!g_hook_sleep.load(std::memory_order_relaxed) || usec == 0) return orig_usleep(usec);
    double spd = g_speed.load(std::memory_order_relaxed);
    if (spd <= 0.05 || fabs(spd - 1.0) < 0.01) return orig_usleep(usec);
    double sc = (double)usec / spd;
    if (spd > 1.0 && sc < 100.0) sc = 100.0;
    return orig_usleep((useconds_t)sc);
}
}

extern "C" {
JNIEXPORT jint JNICALL Java_com_speedgod_module_NativeSpeedBridge_nativeInit(JNIEnv*, jclass) {
    if (g_initialized.load(std::memory_order_acquire)) return 0;
    if (shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false) != 0) return -1;
    int cnt = 0;
    if (shadowhook_hook_sym_name("libc.so", "clock_gettime", (void*)my_clock_gettime, (void**)&orig_clock_gettime)) cnt++;
    if (shadowhook_hook_sym_name("libc.so", "gettimeofday", (void*)my_gettimeofday, (void**)&orig_gettimeofday)) cnt++;
    if (shadowhook_hook_sym_name("libc.so", "nanosleep", (void*)my_nanosleep, (void**)&orig_nanosleep)) cnt++;
    if (shadowhook_hook_sym_name("libc.so", "usleep", (void*)my_usleep, (void**)&orig_usleep)) cnt++;
    if (cnt == 0) return -2;
    update_anchors(1.0, true);
    g_initialized.store(true, std::memory_order_release);
    g_status_msg = "Native OK (" + std::to_string(cnt) + " hooks)";
    return cnt;
}
JNIEXPORT void JNICALL Java_com_speedgod_module_NativeSpeedBridge_nativeSetSpeed(JNIEnv*, jclass, jdouble spd) {
    if (g_initialized.load(std::memory_order_acquire)) update_anchors(spd < 0 ? 0 : (spd > 100 ? 100 : spd), g_hook_realtime.load(std::memory_order_relaxed));
}
JNIEXPORT void JNICALL Java_com_speedgod_module_NativeSpeedBridge_nativeSetHookRealtime(JNIEnv*, jclass, jboolean en) {
    if (g_initialized.load(std::memory_order_acquire)) update_anchors(g_speed.load(std::memory_order_relaxed), en == JNI_TRUE);
}
JNIEXPORT void JNICALL Java_com_speedgod_module_NativeSpeedBridge_nativeSetHookSleep(JNIEnv*, jclass, jboolean en) {
    g_hook_sleep.store(en == JNI_TRUE, std::memory_order_release);
}
JNIEXPORT jstring JNICALL Java_com_speedgod_module_NativeSpeedBridge_nativeGetStatus(JNIEnv* env, jclass) {
    return env->NewStringUTF(g_status_msg.c_str());
}
}
