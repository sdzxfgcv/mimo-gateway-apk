package com.mimogateway

import android.content.Context
import java.io.File

/** 把崩溃/启动异常写到应用私有目录，便于真机排障（用户可读）。 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "crash.log")

    fun write(ctx: Context, where: String, e: Throwable) {
        try {
            val sb = StringBuilder()
            sb.append("=== $where @ ${java.util.Date()} ===\n")
            sb.append(e.toString()).append("\n")
            e.stackTrace.take(12).forEach { sb.append("  at $it\n") }
            sb.append("\n")
            file(ctx).appendText(sb.toString())
        } catch (_: Exception) {}
    }

    fun read(ctx: Context): String = try {
        val f = file(ctx)
        if (f.exists()) f.readText().takeLast(1500) else ""
    } catch (_: Exception) { "" }

    fun clear(ctx: Context) { try { file(ctx).writeText("") } catch (_: Exception) {} }
}
