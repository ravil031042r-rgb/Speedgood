package com.speedgod.module
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Process
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.zip.ZipFile

object NativeSpeedBridge {
    @Volatile var isNativeLoaded = false; private set
    @Volatile var currentSpeed = 1.0; private set
    @Volatile var hookRealtime = true; private set
    @Volatile var hookSleep = false; private set
    @Volatile var statusText = "Java Fallback"; private set
    @Volatile private var initAttempted = false

    @JvmStatic external fun nativeInit(): Int
    @JvmStatic external fun nativeSetSpeed(speed: Double)
    @JvmStatic external fun nativeSetHookRealtime(enabled: Boolean)
    @JvmStatic external fun nativeSetHookSleep(enabled: Boolean)
    @JvmStatic external fun nativeGetStatus(): String

    @Synchronized fun ensureInitialized(modulePath: String?, context: Context?) {
        if (initAttempted) return
        initAttempted = true
        val abi = if (Process.is64Bit()) (Build.SUPPORTED_64_BIT_ABIS.firstOrNull { it == "arm64-v8a" } ?: "arm64-v8a") else "armeabi-v7a"
        val loaded = tryLoadDirect(modulePath, abi) || tryLoadSys() || tryLoadExtract(modulePath, abi, context)
        if (loaded) {
            try {
                if (nativeInit() > 0) {
                    isNativeLoaded = true
                    statusText = nativeGetStatus()
                    return
                }
            } catch (_: Throwable) {}
        }
        isNativeLoaded = false
        statusText = "Java Fallback"
    }

    fun setSpeed(speed: Double) {
        val c = speed.coerceIn(0.0, 100.0)
        currentSpeed = c
        if (isNativeLoaded) try { nativeSetSpeed(c) } catch (_: Throwable) {}
        try {
            val sc = if (c <= 0.0) 0f else (1.0 / c).toFloat().coerceIn(0.01f, 20f)
            ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, sc)
        } catch (_: Throwable) {}
    }
    fun setHookRealtime(e: Boolean) { hookRealtime = e; if (isNativeLoaded) try { nativeSetHookRealtime(e) } catch (_: Throwable) {} }
    fun setHookSleep(e: Boolean) { hookSleep = e; if (isNativeLoaded) try { nativeSetHookSleep(e) } catch (_: Throwable) {} }

    @Suppress("UnsafeDynamicallyLoadedCode")
    private fun tryLoadDirect(p: String?, abi: String): Boolean {
        if (p.isNullOrBlank()) return false
        return try { System.load("$p!/lib/$abi/libshadowhook.so"); System.load("$p!/lib/$abi/libspeedhack.so"); true } catch (_: Throwable) { false }
    }
    private fun tryLoadSys(): Boolean = try { System.loadLibrary("shadowhook"); System.loadLibrary("speedhack"); true } catch (_: Throwable) { false }
    @Suppress("UnsafeDynamicallyLoadedCode")
    private fun tryLoadExtract(p: String?, abi: String, ctx: Context?): Boolean {
        if (p.isNullOrBlank() || ctx == null) return false
        return try {
            val dir = File(ctx.codeCacheDir, "sg_libs/$abi").apply { mkdirs() }
            val out = listOf("libshadowhook.so", "libspeedhack.so").map { name ->
                val f = File(dir, name)
                ZipFile(p).use { z ->
                    val e = z.getEntry("lib/$abi/$name") ?: return false
                    if (!f.exists() || f.length() != e.size) { z.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }; f.setExecutable(true, false) }
                }
                f
            }
            out.forEach { System.load(it.absolutePath) }
            true
        } catch (t: Throwable) { XposedBridge.log("[SpeedGod] $t"); false }
    }
}
