package hev.htproxy

/**
 * Binding for hev-socks5-tunnel. The package and class name MUST stay exactly
 * hev.htproxy.TProxyService because the native library registers its JNI methods on it.
 */
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray?
}
