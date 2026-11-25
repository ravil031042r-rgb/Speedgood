package com.speedgod.module
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : IXposedHookZygoteInit, IXposedHookLoadPackage {
    private class Slot { var init = false; var rb = 0L; var vb = 0L; var spd = 1.0 }
    companion object {
        @Volatile private var modPath: String? = null
        private val hooked = AtomicBoolean(false)
        private val reg = AtomicBoolean(false)
        private val lock = Any()
        private val slots = Array(3) { Slot() }
    }
    override fun initZygote(sp: IXposedHookZygoteInit.StartupParam) { modPath = sp.modulePath }
    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        val pkg = lp.packageName ?: return
        if (pkg == "com.speedgod.module" || pkg == "android" || pkg == "com.android.systemui") return
        if (!hooked.compareAndSet(false, true)) return
        if (modPath.isNullOrBlank()) {
            val s = MainHook::class.java.classLoader?.toString() ?: ""
            modPath = null

        }
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    NativeSpeedBridge.ensureInitialized(modPath, app)
                    if (reg.compareAndSet(false, true)) {
                        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                            override fun onActivityCreated(a: Activity, b: Bundle?) {}
                            override fun onActivityStarted(a: Activity) {}
                            override fun onActivityResumed(a: Activity) { OverlayManager.attachToActivity(a) }
                            override fun onActivityPaused(a: Activity) { OverlayManager.detachFromActivity(a) }
                            override fun onActivityStopped(a: Activity) {}
                            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
                            override fun onActivityDestroyed(a: Activity) { OverlayManager.detachFromActivity(a) }
                        })
                    }
                }
            })
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as? Activity ?: return
                    NativeSpeedBridge.ensureInitialized(modPath, a.applicationContext)
                    OverlayManager.attachToActivity(a)
                }
            })
            XposedHelpers.findAndHookMethod(Activity::class.java, "onPause", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { (param.thisObject as? Activity)?.let { OverlayManager.detachFromActivity(it) } }
            })
        } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(SystemClock::class.java, "uptimeMillis", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) { if (!NativeSpeedBridge.isNativeLoaded) (p.result as? Long)?.let { p.result = calc(it, 0, NativeSpeedBridge.currentSpeed) } }
            })
            XposedHelpers.findAndHookMethod(SystemClock::class.java, "elapsedRealtime", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) { if (!NativeSpeedBridge.isNativeLoaded) (p.result as? Long)?.let { p.result = calc(it, 1, NativeSpeedBridge.currentSpeed) } }
            })
            XposedHelpers.findAndHookMethod(System::class.java, "currentTimeMillis", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) { if (!NativeSpeedBridge.isNativeLoaded) (p.result as? Long)?.let { p.result = calc(it, 2, if (NativeSpeedBridge.hookRealtime) NativeSpeedBridge.currentSpeed else 1.0) } }
            })
        } catch (_: Throwable) {}
    }
    private fun calc(raw: Long, idx: Int, target: Double): Long = synchronized(lock) {
        val s = slots[idx]
        if (!s.init) { s.rb = raw; s.vb = raw; s.spd = target; s.init = true; return raw }
        val v = s.vb + ((raw - s.rb).coerceAtLeast(0L) * s.spd).toLong()
        if (target != s.spd) { s.rb = raw; s.vb = v; s.spd = target }
        v
    }
}
