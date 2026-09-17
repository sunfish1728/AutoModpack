package pl.skidam.automodpack_core.protocol;

import java.net.InetSocketAddress;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/** Resolves server advertisements against locally observed routing, once at login. */
public final class DownloadRoutes {
    private DownloadRoutes() {}

    public static Jsons.ModpackAddresses resolve(String advertisedHost, int advertisedPort,
            boolean magic, InetSocketAddress original, InetSocketAddress connected, DownloadRoute proxy) {
        if (advertisedHost == null || advertisedPort < -1 || advertisedPort == 0 || advertisedPort > 65535)
            throw new IllegalArgumentException("Invalid advertised download address");
        InetSocketAddress publicEntry = proxy == null ? connected : proxy.endpoint();
        InetSocketAddress endpoint = AddressHelpers.format(
                advertisedHost.isBlank() ? publicEntry.getHostString() : advertisedHost,
                advertisedPort == -1 ? publicEntry.getPort() : advertisedPort);
        // A separate download host/port is a separate service, not the game's compressed tunnel.
        boolean sharedEntry = advertisedHost.isBlank() && advertisedPort == -1 && magic;
        DownloadRoute route = sharedEntry && proxy != null ? proxy : DownloadRoute.tcp(endpoint, magic);
        InetSocketAddress reconnect = original;
        if (proxy != null && original.getPort() == connected.getPort()
                && (original.equals(connected) || original.getHostString().equalsIgnoreCase(connected.getHostString()))) reconnect = proxy.endpoint();
        Jsons.ModpackAddresses result = new Jsons.ModpackAddresses(route.endpoint(), reconnect, magic);
        result.route = route;
        return result;
    }

    /** Migration is local: existing config files do not need to be rewritten by the user. */
    public static DownloadRoute from(Jsons.ModpackAddresses addresses) {
        if (addresses.route != null) return addresses.route;
        if (addresses.zstdAddress != null) return DownloadRoute.zstd(addresses.zstdAddress);
        return DownloadRoute.tcp(addresses.hostAddress, addresses.requiresMagic);
    }
}
