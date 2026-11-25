#define _GNU_SOURCE
#include <jni.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <cmath>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <vector>
#include <string>
#include <mutex>
#include <thread>
#include <atomic>
#include <chrono>
#include <algorithm>

namespace {

constexpr int TYPE_DWORD  = 0; // int32
constexpr int TYPE_FLOAT  = 1; // float
constexpr int TYPE_DOUBLE = 2; // double
constexpr int TYPE_QWORD  = 3; // int64
constexpr size_t MAX_RESULTS = 250000;

struct Region {
    uintptr_t start;
    uintptr_t end;
};

struct FrozenItem {
    uintptr_t addr;
    int type;
    int64_t i64;
    double f64;
};

std::mutex g_res_mtx;
std::vector<uintptr_t> g_results;
int g_last_type = TYPE_DWORD;

std::mutex g_freeze_mtx;
std::vector<FrozenItem> g_frozen;
bool g_freeze_running = false;

size_t type_size(int type) {
    return (type == TYPE_DOUBLE || type == TYPE_QWORD) ? 8 : 4;
}

bool parse_value(int type, const char* raw, int64_t& out_i, double& out_f) {
    if (!raw || !*raw) return false;
    std::string s(raw);
    for (char& c : s) {
        if (c == ',') c = '.';
    }
    char* end = nullptr;
    errno = 0;
    if (type == TYPE_DWORD || type == TYPE_QWORD) {
        long long v = strtoll(s.c_str(), &end, 10);
        if (end == s.c_str() || errno == ERANGE) return false;
        out_i = (int64_t)v;
        out_f = (double)v;
        return true;
    } else {
        double v = strtod(s.c_str(), &end);
        if (end == s.c_str() || !std::isfinite(v)) return false;
        out_f = v;
        out_i = (int64_t)v;
        return true;
    }
}

std::vector<Region> get_rw_regions() {
    std::vector<Region> regions;
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return regions;

    char line[512];
    while (fgets(line, sizeof(line), f)) {
        unsigned long long start = 0, end = 0;
        char perms[8] = {0};
        int n = sscanf(line, "%llx-%llx %7s", &start, &end, perms);
        if (n < 3) continue;
        if (perms[0] != 'r' || perms[1] != 'w' || perms[3] != 'p') continue;
        if (end <= start) continue;
        if (end - start > 512ULL * 1024ULL * 1024ULL) continue;
#if UINTPTR_MAX > 0xffffffffULL
        if (end >= 0x7fffffffffffULL) continue;
#endif
        if (strstr(line, "/dev/") ||
            strstr(line, "/system/") ||
            strstr(line, "/vendor/") ||
            strstr(line, "/apex/") ||
            strstr(line, "[stack") ||
            strstr(line, "[vvar]") ||
            strstr(line, "[vdso]") ||
            strstr(line, "kgsl") ||
            strstr(line, "dmabuf") ||
            strstr(line, "dalvik-jit")) {
            continue;
        }
        regions.push_back({(uintptr_t)start, (uintptr_t)end});
    }
    fclose(f);
    return regions;
}

bool safe_read(int mem_fd, uintptr_t addr, void* out, size_t len) {
    if (mem_fd >= 0) {
        ssize_t r = pread64(mem_fd, out, len, (off64_t)(uint64_t)addr);
        if (r == (ssize_t)len) return true;
    }
#ifdef SYS_process_vm_readv
    struct iovec local { out, len };
    struct iovec remote { (void*)addr, len };
    ssize_t r = syscall(SYS_process_vm_readv, getpid(), &local, 1, &remote, 1, 0);
    return r == (ssize_t)len;
#else
    return false;
#endif
}

bool addr_in_rw_maps(uintptr_t addr, size_t len) {
    auto regions = get_rw_regions();
    for (const auto& r : regions) {
        if (addr >= r.start && addr + len <= r.end) return true;
    }
    return false;
}

bool safe_write_bytes(uintptr_t addr, const void* data, size_t len) {
    int fd = open("/proc/self/mem", O_RDWR);
    if (fd >= 0) {
        ssize_t w = pwrite64(fd, data, len, (off64_t)(uint64_t)addr);
        close(fd);
        if (w == (ssize_t)len) return true;
    }
    // Fallback: verify readable & rw-p before direct write
    int rfd = open("/proc/self/mem", O_RDONLY);
    uint8_t tmp[8];
    bool ok = safe_read(rfd, addr, tmp, len);
    if (rfd >= 0) close(rfd);
    if (ok && addr_in_rw_maps(addr, len)) {
        memcpy((void*)addr, data, len);
        return true;
    }
    return false;
}

bool write_typed(uintptr_t addr, int type, int64_t i64, double f64) {
    if (type == TYPE_DWORD) {
        int32_t v = (int32_t)i64;
        return safe_write_bytes(addr, &v, 4);
    } else if (type == TYPE_FLOAT) {
        float v = (float)f64;
        return safe_write_bytes(addr, &v, 4);
    } else if (type == TYPE_DOUBLE) {
        return safe_write_bytes(addr, &f64, 8);
    } else {
        return safe_write_bytes(addr, &i64, 8);
    }
}

bool matches_bytes(const uint8_t* p, int type, int64_t ti, double tf) {
    if (type == TYPE_DWORD) {
        int32_t v;
        memcpy(&v, p, 4);
        return v == (int32_t)ti;
    } else if (type == TYPE_FLOAT) {
        float v;
        memcpy(&v, p, 4);
        return std::isfinite(v) && fabsf(v - (float)tf) <= 0.01f;
    } else if (type == TYPE_DOUBLE) {
        double v;
        memcpy(&v, p, 8);
        return std::isfinite(v) && fabs(v - tf) <= 0.001;
    } else {
        int64_t v;
        memcpy(&v, p, 8);
        return v == ti;
    }
}

std::string format_typed(const uint8_t* p, int type) {
    char buf[64];
    if (type == TYPE_DWORD) {
        int32_t v; memcpy(&v, p, 4);
        snprintf(buf, sizeof(buf), "%d", v);
    } else if (type == TYPE_FLOAT) {
        float v; memcpy(&v, p, 4);
        snprintf(buf, sizeof(buf), "%.2f", v);
    } else if (type == TYPE_DOUBLE) {
        double v; memcpy(&v, p, 8);
        snprintf(buf, sizeof(buf), "%.2f", v);
    } else {
        int64_t v; memcpy(&v, p, 8);
        snprintf(buf, sizeof(buf), "%lld", (long long)v);
    }
    return std::string(buf);
}

void ensure_freeze_thread_locked() {
    if (g_freeze_running) return;
    g_freeze_running = true;
    std::thread([]() {
        while (true) {
            std::vector<FrozenItem> snap;
            {
                std::lock_guard<std::mutex> lk(g_freeze_mtx);
                if (g_frozen.empty()) {
                    g_freeze_running = false;
                    return;
                }
                snap = g_frozen;
            }
            for (const auto& item : snap) {
                write_typed(item.addr, item.type, item.i64, item.f64);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(80));
        }
    }).detach();
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_speedgod_module_MemScanner_nativeNewSearch(JNIEnv* env, jclass, jint type, jstring valStr) {
    if (!valStr) return 0;
    const char* raw = env->GetStringUTFChars(valStr, nullptr);
    int64_t ti = 0; double tf = 0.0;
    bool valid = parse_value(type, raw, ti, tf);
    env->ReleaseStringUTFChars(valStr, raw);
    if (!valid) return -1;

    auto regions = get_rw_regions();
    int mem_fd = open("/proc/self/mem", O_RDONLY);
    const size_t step = type_size(type);
    const size_t CHUNK = 64 * 1024;
    std::vector<uint8_t> buf(CHUNK);
    uintptr_t buf_start = (uintptr_t)buf.data();
    uintptr_t buf_end = buf_start + buf.size();

    std::vector<uintptr_t> found;
    found.reserve(4096);

    for (const auto& reg : regions) {
        if (found.size() >= MAX_RESULTS) break;
        for (uintptr_t cur = reg.start; cur < reg.end && found.size() < MAX_RESULTS; cur += CHUNK) {
            size_t to_read = (size_t)std::min<uintptr_t>(CHUNK, reg.end - cur);
            if (!safe_read(mem_fd, cur, buf.data(), to_read)) {
                // Fallback page-by-page
                for (uintptr_t p = cur; p < cur + to_read && found.size() < MAX_RESULTS; p += 4096) {
                    size_t plen = (size_t)std::min<uintptr_t>(4096, (cur + to_read) - p);
                    if (!safe_read(mem_fd, p, buf.data(), plen)) continue;
                    for (size_t off = 0; off + step <= plen; off += step) {
                        uintptr_t a = p + off;
                        if (a >= buf_start && a < buf_end) continue;
                        if (matches_bytes(buf.data() + off, type, ti, tf)) {
                            found.push_back(a);
                            if (found.size() >= MAX_RESULTS) break;
                        }
                    }
                }
                continue;
            }
            for (size_t off = 0; off + step <= to_read; off += step) {
                uintptr_t a = cur + off;
                if (a >= buf_start && a < buf_end) continue;
                if (matches_bytes(buf.data() + off, type, ti, tf)) {
                    found.push_back(a);
                    if (found.size() >= MAX_RESULTS) break;
                }
            }
        }
    }

    if (mem_fd >= 0) close(mem_fd);

    std::lock_guard<std::mutex> lk(g_res_mtx);
    g_results.swap(found);
    g_last_type = type;
    return (jint)g_results.size();
}

JNIEXPORT jint JNICALL
Java_com_speedgod_module_MemScanner_nativeRefineSearch(JNIEnv* env, jclass, jint type, jstring valStr) {
    if (!valStr) return 0;
    const char* raw = env->GetStringUTFChars(valStr, nullptr);
    int64_t ti = 0; double tf = 0.0;
    bool valid = parse_value(type, raw, ti, tf);
    env->ReleaseStringUTFChars(valStr, raw);
    if (!valid) return -1;

    std::vector<uintptr_t> cur;
    {
        std::lock_guard<std::mutex> lk(g_res_mtx);
        cur = g_results;
    }
    if (cur.empty()) return 0;

    int mem_fd = open("/proc/self/mem", O_RDONLY);
    size_t sz = type_size(type);
    uint8_t tmp[8];
    std::vector<uintptr_t> next;
    next.reserve(std::min<size_t>(cur.size(), 4096));

    for (uintptr_t addr : cur) {
        if (safe_read(mem_fd, addr, tmp, sz) && matches_bytes(tmp, type, ti, tf)) {
            next.push_back(addr);
        }
    }
    if (mem_fd >= 0) close(mem_fd);

    std::lock_guard<std::mutex> lk(g_res_mtx);
    g_results.swap(next);
    g_last_type = type;
    return (jint)g_results.size();
}

JNIEXPORT void JNICALL
Java_com_speedgod_module_MemScanner_nativeClearSearch(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_res_mtx);
    g_results.clear();
}

JNIEXPORT jint JNICALL
Java_com_speedgod_module_MemScanner_nativeGetResultCount(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_res_mtx);
    return (jint)g_results.size();
}

JNIEXPORT jobjectArray JNICALL
Java_com_speedgod_module_MemScanner_nativeGetResults(JNIEnv* env, jclass, jint maxCount) {
    std::vector<uintptr_t> snap;
    int type = TYPE_DWORD;
    {
        std::lock_guard<std::mutex> lk(g_res_mtx);
        type = g_last_type;
        size_t n = std::min<size_t>(g_results.size(), (size_t)std::max(0, (int)maxCount));
        snap.assign(g_results.begin(), g_results.begin() + n);
    }

    std::vector<uintptr_t> frozen_addrs;
    {
        std::lock_guard<std::mutex> lk(g_freeze_mtx);
        for (const auto& f : g_frozen) frozen_addrs.push_back(f.addr);
    }

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)snap.size(), strCls, nullptr);
    int mem_fd = open("/proc/self/mem", O_RDONLY);
    size_t sz = type_size(type);
    uint8_t tmp[8];

    for (size_t i = 0; i < snap.size(); ++i) {
        uintptr_t addr = snap[i];
        std::string val = "?";
        if (safe_read(mem_fd, addr, tmp, sz)) {
            val = format_typed(tmp, type);
        }
        bool is_f = std::find(frozen_addrs.begin(), frozen_addrs.end(), addr) != frozen_addrs.end();
        char row[128];
        snprintf(row, sizeof(row), "%llx|%s|%d", (unsigned long long)addr, val.c_str(), is_f ? 1 : 0);
        jstring js = env->NewStringUTF(row);
        env->SetObjectArrayElement(arr, (jsize)i, js);
        env->DeleteLocalRef(js);
    }

    if (mem_fd >= 0) close(mem_fd);
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_speedgod_module_MemScanner_nativeEditAll(JNIEnv* env, jclass, jint type, jstring valStr) {
    if (!valStr) return 0;
    const char* raw = env->GetStringUTFChars(valStr, nullptr);
    int64_t ti = 0; double tf = 0.0;
    bool valid = parse_value(type, raw, ti, tf);
    env->ReleaseStringUTFChars(valStr, raw);
    if (!valid) return -1;

    std::vector<uintptr_t> snap;
    {
        std::lock_guard<std::mutex> lk(g_res_mtx);
        snap = g_results;
    }

    int edited = 0;
    size_t limit = std::min<size_t>(snap.size(), 500);
    for (size_t i = 0; i < limit; ++i) {
        if (write_typed(snap[i], type, ti, tf)) edited++;
    }
    return edited;
}

JNIEXPORT jboolean JNICALL
Java_com_speedgod_module_MemScanner_nativeEditOne(JNIEnv* env, jclass, jstring addrHex, jint type, jstring valStr) {
    if (!addrHex || !valStr) return JNI_FALSE;
    const char* aStr = env->GetStringUTFChars(addrHex, nullptr);
    uintptr_t addr = (uintptr_t)strtoull(aStr, nullptr, 16);
    env->ReleaseStringUTFChars(addrHex, aStr);

    const char* raw = env->GetStringUTFChars(valStr, nullptr);
    int64_t ti = 0; double tf = 0.0;
    bool valid = parse_value(type, raw, ti, tf);
    env->ReleaseStringUTFChars(valStr, raw);
    if (!valid || addr == 0) return JNI_FALSE;

    bool ok = write_typed(addr, type, ti, tf);
    if (ok) {
        std::lock_guard<std::mutex> lk(g_freeze_mtx);
        for (auto& item : g_frozen) {
            if (item.addr == addr) {
                item.type = type;
                item.i64 = ti;
                item.f64 = tf;
            }
        }
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_speedgod_module_MemScanner_nativeToggleFreeze(JNIEnv* env, jclass, jstring addrHex, jint type, jstring valStr) {
    if (!addrHex || !valStr) return JNI_FALSE;
    const char* aStr = env->GetStringUTFChars(addrHex, nullptr);
    uintptr_t addr = (uintptr_t)strtoull(aStr, nullptr, 16);
    env->ReleaseStringUTFChars(addrHex, aStr);

    const char* raw = env->GetStringUTFChars(valStr, nullptr);
    int64_t ti = 0; double tf = 0.0;
    bool valid = parse_value(type, raw, ti, tf);
    env->ReleaseStringUTFChars(valStr, raw);
    if (addr == 0) return JNI_FALSE;

    std::lock_guard<std::mutex> lk(g_freeze_mtx);
    for (auto it = g_frozen.begin(); it != g_frozen.end(); ++it) {
        if (it->addr == addr) {
            g_frozen.erase(it);
            return JNI_FALSE;
        }
    }
    if (!valid) return JNI_FALSE;
    g_frozen.push_back({addr, type, ti, tf});
    ensure_freeze_thread_locked();
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_speedgod_module_MemScanner_nativeFreezeAll(JNIEnv* env, jclass, jint type, jstring valStr) {
    if (!valStr) return 0;
    const char* raw = env->GetStringUTFChars(valStr, nullptr);
    int64_t ti = 0; double tf = 0.0;
    bool valid = parse_value(type, raw, ti, tf);
    env->ReleaseStringUTFChars(valStr, raw);
    if (!valid) return -1;

    std::vector<uintptr_t> snap;
    {
        std::lock_guard<std::mutex> lk(g_res_mtx);
        size_t n = std::min<size_t>(g_results.size(), 50);
        snap.assign(g_results.begin(), g_results.begin() + n);
    }

    std::lock_guard<std::mutex> lk(g_freeze_mtx);
    int added = 0;
    for (uintptr_t addr : snap) {
        bool exists = false;
        for (auto& item : g_frozen) {
            if (item.addr == addr) {
                item.type = type; item.i64 = ti; item.f64 = tf;
                exists = true; break;
            }
        }
        if (!exists) {
            g_frozen.push_back({addr, type, ti, tf});
            added++;
        }
    }
    if (!g_frozen.empty()) ensure_freeze_thread_locked();
    return (jint)g_frozen.size();
}

JNIEXPORT void JNICALL
Java_com_speedgod_module_MemScanner_nativeUnfreezeAll(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_freeze_mtx);
    g_frozen.clear();
}

JNIEXPORT jint JNICALL
Java_com_speedgod_module_MemScanner_nativeGetFrozenCount(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_freeze_mtx);
    return (jint)g_frozen.size();
}

} // extern "C"
