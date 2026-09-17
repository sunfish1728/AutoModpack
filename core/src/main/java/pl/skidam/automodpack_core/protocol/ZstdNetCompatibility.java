package pl.skidam.automodpack_core.protocol;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

/** Optional adapter for zstdnet 1.4.8. Reads local routing state without taking ownership of its proxy. */
public final class ZstdNetCompatibility {
    private ZstdNetCompatibility() {}

    public static InetSocketAddress capture(InetSocketAddress connected, ClassLoader gameLoader) {
        DownloadRoute route = captureRoute(connected, gameLoader);
        return route == null ? null : route.endpoint();
    }

    public static DownloadRoute captureRoute(InetSocketAddress connected, ClassLoader gameLoader) {
        if (connected.getAddress() == null || !connected.getAddress().isLoopbackAddress()) return null;
        try {
            Class<?> publisher = Class.forName("cn.tohsaka.factory.zstdnet.client.ClientProxyPublisher", false, gameLoader);
            Object instance = field(publisher, "INSTANCE", null);
            synchronized (field(publisher, "stateLock", instance)) {
                DownloadRoute route = route(field(publisher, "activeProxy", instance), connected.getPort());
                if (route != null) return route;
                route = route(field(publisher, "activeSession", instance), connected.getPort());
                if (route != null) return route;
            }
            Class<?> hooks = Class.forName("cn.tohsaka.factory.zstdnet.coremod.ConnectScreenHooks", false, gameLoader);
            synchronized (field(hooks, "LOCK", null)) {
                return route(field(hooks, "currentProxy", null), connected.getPort());
            }
        } catch (ClassNotFoundException e) {
            return null; // zstdnet is not installed; preserve the ordinary download path.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not read zstdnet routing state; installed zstdnet may be incompatible", e);
            return null;
        }
    }

    private static Object field(Class<?> type, String name, Object owner) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static DownloadRoute route(Object proxy, int localPort) throws ReflectiveOperationException {
        if (proxy == null) return null;
        Class<?> type = proxy.getClass();
        if ((int) type.getMethod("localPort").invoke(proxy) != localPort) return null;
        String mode = type.getMethod("mode").invoke(proxy).toString();
        if (!mode.equals("ZSTD") && !mode.equals("RAW")) return null;
        String host = (String) type.getMethod("remoteHost").invoke(proxy);
        int port = (int) type.getMethod("remotePort").invoke(proxy);
        if (host == null || host.isBlank() || port < 1 || port > 65535) return null;
        LOGGER.info("Captured zstdnet public entry {}:{} for login proxy port {}", host, port, localPort);
        InetSocketAddress entry = AddressHelpers.format(host, port);
        return mode.equals("ZSTD") ? DownloadRoute.zstd(entry) : DownloadRoute.tcp(entry, true);
    }
}
