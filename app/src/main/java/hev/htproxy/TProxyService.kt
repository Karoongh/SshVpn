package hev.htproxy

/**
 * Binding for hev-socks5-tunnel. The package and class name MUST stay exactly
 * hev.htproxy.TProxyService because the native library registers its JNI methods on it.
 */
object TProxyService {
    @JvmField
    val nativeReady: Boolean

    init {
        var ok = false
        try {
            System.loadLibrary("hev-socks5-tunnel")
            ok = true
        } catch (t: Throwable) {
            // Never crash the process during class init; callers check nativeReady.
            android.util.Log.e("TProxyService", "Failed to load hev-socks5-tunnel", t)
        }
        nativeReady = ok
    }

    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray?
}
