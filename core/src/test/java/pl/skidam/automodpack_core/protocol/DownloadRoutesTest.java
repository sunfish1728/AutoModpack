package pl.skidam.automodpack_core.protocol;

import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;

class DownloadRoutesTest {
    private final InetSocketAddress local = new InetSocketAddress("127.0.0.1", 12345);
    private final InetSocketAddress entry = InetSocketAddress.createUnresolved("entry.example", 23071);

    @Test void sharedCompressedEntrySurvivesPersistenceWithoutLoginProxy() {
        var addresses = DownloadRoutes.resolve("", -1, true, local, local, DownloadRoute.zstd(entry));
        var restored = ConfigTools.GSON.fromJson(ConfigTools.GSON.toJson(addresses), Jsons.ModpackAddresses.class);
        assertEquals(DownloadRoute.zstd(entry), DownloadRoutes.from(restored));
        assertEquals(entry, restored.serverAddress);
        assertFalse(ConfigTools.GSON.toJson(restored).contains("12345"));
    }

    @Test void unresolvedOriginalLoopbackIsAlsoReplaced() {
        var original = InetSocketAddress.createUnresolved("127.0.0.1", 12345);
        var addresses = DownloadRoutes.resolve("", -1, true, original, local, DownloadRoute.zstd(entry));
        assertEquals(entry, addresses.serverAddress);
    }

    @Test void explicitDownloadPortUsesPublicHostAndPlainTcp() {
        var addresses = DownloadRoutes.resolve("", 24444, false, entry, local, DownloadRoute.zstd(entry));
        assertEquals(DownloadRoute.tcp(InetSocketAddress.createUnresolved("entry.example", 24444), false), addresses.route);
    }

    @Test void explicitHostIsNotOverriddenByGameCompression() {
        var addresses = DownloadRoutes.resolve("files.example", 443, false, entry, local, DownloadRoute.zstd(entry));
        assertEquals(DownloadRoute.tcp(InetSocketAddress.createUnresolved("files.example", 443), false), addresses.route);
    }

    @Test void rawProxyAlsoSurvivesClosure() {
        var addresses = DownloadRoutes.resolve("", -1, true, local, local, DownloadRoute.tcp(entry, true));
        assertEquals(DownloadRoute.tcp(entry, true), addresses.route);
        assertEquals(entry, addresses.serverAddress);
    }

    @Test void ordinaryDirectAndTcpTunnelUseActualConnectedPort() {
        var connected = new InetSocketAddress("192.0.2.5", 34567);
        var addresses = DownloadRoutes.resolve("", -1, true, entry, connected, null);
        assertEquals(DownloadRoute.tcp(InetSocketAddress.createUnresolved("192.0.2.5", 34567), true), addresses.route);
        assertEquals(entry, addresses.serverAddress);
    }

    @Test void legacyConfigsRemainReadable() {
        var addresses = new Jsons.ModpackAddresses(entry, entry, true);
        assertEquals(DownloadRoute.tcp(entry, true), DownloadRoutes.from(addresses));
        addresses.zstdAddress = entry;
        assertEquals(DownloadRoute.zstd(entry), DownloadRoutes.from(addresses));
    }

    @Test void ipv6ScopeAndPortSurvivePersistence() {
        var ipv6 = InetSocketAddress.createUnresolved("fe80::1234%9", 23071);
        var addresses = DownloadRoutes.resolve("", -1, true, ipv6, local, DownloadRoute.zstd(ipv6));
        var restored = ConfigTools.GSON.fromJson(ConfigTools.GSON.toJson(addresses), Jsons.ModpackAddresses.class);
        assertEquals(ipv6, restored.downloadAddress());
    }

    @Test void invalidRecipesFailBeforeOpeningSocket() {
        assertThrows(IllegalArgumentException.class, () -> DownloadRoutes.resolve("", 0, true, entry, local, null));
        assertThrows(IllegalArgumentException.class, () -> new DownloadRoute(entry, DownloadRoute.Transport.ZSTD, DownloadRoute.Handshake.TLS));
    }
}
