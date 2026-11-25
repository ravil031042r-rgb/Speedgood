package com.speedgod.module

object MemScanner {
    const val TYPE_DWORD = 0
    const val TYPE_FLOAT = 1
    const val TYPE_DOUBLE = 2
    const val TYPE_QWORD = 3

    @JvmStatic external fun nativeNewSearch(type: Int, valueStr: String): Int
    @JvmStatic external fun nativeRefineSearch(type: Int, valueStr: String): Int
    @JvmStatic external fun nativeClearSearch()
    @JvmStatic external fun nativeGetResultCount(): Int
    @JvmStatic external fun nativeGetResults(maxCount: Int): Array<String>
    @JvmStatic external fun nativeEditAll(type: Int, valueStr: String): Int
    @JvmStatic external fun nativeEditOne(addrHex: String, type: Int, valueStr: String): Boolean
    @JvmStatic external fun nativeToggleFreeze(addrHex: String, type: Int, valueStr: String): Boolean
    @JvmStatic external fun nativeFreezeAll(type: Int, valueStr: String): Int
    @JvmStatic external fun nativeUnfreezeAll()
    @JvmStatic external fun nativeGetFrozenCount(): Int

    fun newSearch(type: Int, value: String): Int =
        try { nativeNewSearch(type, value.trim()) } catch (_: Throwable) { -2 }

    fun refineSearch(type: Int, value: String): Int =
        try { nativeRefineSearch(type, value.trim()) } catch (_: Throwable) { -2 }

    fun clearSearch() {
        try { nativeClearSearch() } catch (_: Throwable) {}
    }

    fun getResultCount(): Int =
        try { nativeGetResultCount() } catch (_: Throwable) { 0 }

    fun getResults(maxCount: Int = 35): Array<String> =
        try { nativeGetResults(maxCount) } catch (_: Throwable) { emptyArray() }

    fun editAll(type: Int, value: String): Int =
        try { nativeEditAll(type, value.trim()) } catch (_: Throwable) { -2 }

    fun editOne(addrHex: String, type: Int, value: String): Boolean =
        try { nativeEditOne(addrHex, type, value.trim()) } catch (_: Throwable) { false }

    fun toggleFreeze(addrHex: String, type: Int, value: String): Boolean =
        try { nativeToggleFreeze(addrHex, type, value.trim()) } catch (_: Throwable) { false }

    fun freezeAll(type: Int, value: String): Int =
        try { nativeFreezeAll(type, value.trim()) } catch (_: Throwable) { -2 }

    fun unfreezeAll() {
        try { nativeUnfreezeAll() } catch (_: Throwable) {}
    }

    fun getFrozenCount(): Int =
        try { nativeGetFrozenCount() } catch (_: Throwable) { 0 }
}
