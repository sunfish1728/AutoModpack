package pl.skidam.automodpack_core.protocol;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class ZstdSocketLifecycleTest {
    @Test void streamCloseAndSocketCloseDoNotRecursivelyFreeNativeState() throws Exception {
        // Repeat in one JVM: a native double-free may crash a later allocation, not the original close.
        try (ServerSocket listener = new ServerSocket(0)) {
            for (int i = 0; i < 100; i++) {
                try (var socket = new ZstdSocket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
                    try (Socket peer = listener.accept()) {
                        var output = socket.getOutputStream();
                        output.write(new byte[32]); output.flush();
                        output.close();
                        assertFalse(socket.isClosed(), "Compression stream must not own the underlying TCP socket");
                        socket.close(); socket.close();
                        assertTrue(socket.isClosed());
                        assertThrows(SocketException.class, socket::getOutputStream);
                    }
                }
            }
        }
    }
}
