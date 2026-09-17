package cn.tohsaka.factory.zstdnet.client;

/** Fixture matching the fields and public handle methods of the installed zstdnet 1.4.8. */
public final class ClientProxyPublisher {
    private static final ClientProxyPublisher INSTANCE = new ClientProxyPublisher();
    private final Object stateLock = new Object();
    private Object activeProxy;
    private Object activeSession;

    public static void setProxy(Object proxy) { INSTANCE.activeProxy = proxy; }
    public record ProxyHandle(int localPort, String remoteHost, int remotePort, String mode) {}
}
