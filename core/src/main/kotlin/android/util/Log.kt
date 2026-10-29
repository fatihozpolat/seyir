package android.util

import seyir.core.SeyirLog

/**
 * android.util.Log taklidi. Eklentiler aynı paket adıyla derlendiği için kodlarına dokunmadan
 * masaüstünde çalışırlar. Çıktı [SeyirLog] üzerinden geçer.
 */
@Suppress("unused")
object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    @JvmStatic fun v(tag: String?, msg: String?): Int = write(VERBOSE, tag, msg, null)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = write(VERBOSE, tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String?): Int = write(DEBUG, tag, msg, null)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = write(DEBUG, tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String?): Int = write(INFO, tag, msg, null)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = write(INFO, tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String?): Int = write(WARN, tag, msg, null)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = write(WARN, tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = write(WARN, tag, null, tr)
    @JvmStatic fun e(tag: String?, msg: String?): Int = write(ERROR, tag, msg, null)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = write(ERROR, tag, msg, tr)
    @JvmStatic fun wtf(tag: String?, msg: String?): Int = write(ASSERT, tag, msg, null)
    @JvmStatic fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = write(ASSERT, tag, msg, tr)

    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = SeyirLog.isEnabled(level)

    @JvmStatic fun getStackTraceString(tr: Throwable?): String = tr?.stackTraceToString() ?: ""

    private fun write(level: Int, tag: String?, msg: String?, tr: Throwable?): Int {
        SeyirLog.log(level, tag ?: "", msg, tr)
        return 0
    }
}
