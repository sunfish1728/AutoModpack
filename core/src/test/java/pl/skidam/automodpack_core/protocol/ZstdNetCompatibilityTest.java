package pl.skidam.automodpack_core.protocol;

import cn.tohsaka.factory.zstdnet.client.ClientProxyPublisher;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;

class ZstdNetCompatibilityTest {
    @Test void capturesOnlyTheMatchingLocalZstdProxy() {
        var handle = new ClientProxyPublisher.ProxyHandle(12345, "entry.example", 23071, "ZSTD");
        ClientProxyPublisher.setProxy(handle);
        try {
            var route = ZstdNetCompatibility.capture(new InetSocketAddress("127.0.0.1", 12345), getClass().getClassLoader());
            assertEquals(InetSocketAddress.createUnresolved("entry.example", 23071), route);
            assertNull(ZstdNetCompatibility.capture(new InetSocketAddress("127.0.0.1", 12346), getClass().getClassLoader()));
            assertNull(ZstdNetCompatibility.capture(new InetSocketAddress("192.0.2.1", 12345), getClass().getClassLoader()));
            ClientProxyPublisher.setProxy(new ClientProxyPublisher.ProxyHandle(12345, "entry.example", 23071, "RAW"));
            assertEquals(InetSocketAddress.createUnresolved("entry.example", 23071), ZstdNetCompatibility.capture(new InetSocketAddress("127.0.0.1", 12345), getClass().getClassLoader()), "RAW proxy also has an ephemeral listener: preserve its public route");
        } finally { ClientProxyPublisher.setProxy(null); }
    }
}
