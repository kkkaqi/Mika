package com.wangzan101.mika.cap.phone.a11y

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 自有注入手势期间隔离浮层触摸;计数和 [isolate] 回调只在主线程使用。 */
internal class InjectedGestures(private val isolate: (Boolean) -> Unit) {
    private var depth = 0
    val isolated: Boolean get() = depth > 0

    suspend fun <T> passThrough(block: suspend () -> T): T {
        var entered = false
        try {
            withContext(Dispatchers.Main.immediate) {
                depth++
                // 返回调用线程时可能立即因取消抛错;从修改计数起就必须由 finally 成对恢复。
                entered = true
                if (depth == 1) isolate(true)
            }
            return withContext(NonCancellable) { block() }
        } finally {
            // 排队等主线程时就被取消的没有进入隔离,不能减掉其他手势的计数。
            if (entered) withContext(NonCancellable + Dispatchers.Main.immediate) {
                depth = (depth - 1).coerceAtLeast(0)
                if (depth == 0) isolate(false)
            }
        }
    }
}
