package pl.skidam.automodpack_core.protocol;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.config.*;
import pl.skidam.automodpack_core.protocol.netty.*;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.GlobalVariables.*;

class DownloadTransportIntegrationTest {
    enum Mode { SHARED_TCP, DEDICATED_TLS, TCP_TUNNEL, PROXY_V1, PROXY_V2 }
    @TempDir Path directory;

    @ParameterizedTest @EnumSource(Mode.class)
    void manifestFilesRefreshAndRestartUseSameRoute(Mode mode) throws Exception {
        byte[] content = new byte[256 * 1024]; new Random(4321).nextBytes(content);
        Path file = directory.resolve("mod.jar"); Files.write(file, content);
        Path manifest = directory.resolve("manifest.json"); Files.writeString(manifest, "{\"modpackName\":\"test\",\"list\":[]}");
        var previousConfig = serverConfig; var previousHost = hostServer;
        var previousManifest = hostModpackContentFile;
        serverConfig = new Jsons.ServerConfigFieldsV2();
        serverConfig.validateSecrets = false; // Isolated temporary server only; no game authorization context.
        serverConfig.bindPort = mode == Mode.DEDICATED_TLS ? 25566 : -1;
        hostModpackContentFile = manifest;
        hostServer = new NettyServer() {
            @Override public Optional<Path> getPath(String hash) { return Optional.of(file); }
            @Override public boolean isRunning() { return true; }
        };
        var group = new NioEventLoopGroup(2);
        Channel backend = null;
        Tunnel tunnel = null;
        new TrafficShaper(null);
        try {
            var keys = NetUtils.generateKeyPair();
            var tls = SslContextBuilder.forServer(keys.getPrivate(), NetUtils.selfSign(keys))
                    .sslProvider(SslProvider.JDK).protocols("TLSv1.3").build();
            backend = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                        @Override protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                            ch.pipeline().addLast(new ProtocolServerHandler(tls));
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            int port = ((InetSocketAddress) backend.localAddress()).getPort();
            if (mode == Mode.TCP_TUNNEL || mode == Mode.PROXY_V1 || mode == Mode.PROXY_V2) {
                tunnel = new Tunnel(port, mode);
                port = tunnel.port();
            }
            var entry = InetSocketAddress.createUnresolved("localhost", port);
            var addresses = DownloadRoutes.resolve("", -1, mode != Mode.DEDICATED_TLS, entry, entry, null);
            AtomicInteger prompts = new AtomicInteger();
            String[] trustedFingerprint = {null};
            for (int run = 0; run < 2; run++) {
                // This is the actual persistence path used before the Minecraft classes load on restart.
                addresses = ConfigTools.GSON.fromJson(ConfigTools.GSON.toJson(addresses), Jsons.ModpackAddresses.class);
                try (var client = new DownloadClient(addresses, new byte[32], 3, certificate -> {
                    try {
                        String fingerprint = NetUtils.getFingerprint(certificate);
                        if (trustedFingerprint[0] == null) { prompts.incrementAndGet(); trustedFingerprint[0] = fingerprint; }
                        return trustedFingerprint[0].equals(fingerprint);
                    } catch (Exception e) { throw new RuntimeException(e); }
                })) {
                    Path list = client.downloadFile(new byte[0], directory.resolve("list-" + run), null).get(10, TimeUnit.SECONDS);
                    assertEquals(Files.readString(manifest), Files.readString(list));
                    var downloads = new ArrayList<CompletableFuture<Path>>();
                    for (int i = 0; i < 3; i++) downloads.add(client.downloadFile(new byte[]{1}, directory.resolve("file-" + run + "-" + i), null));
                    for (var pending : downloads) assertArrayEquals(content, Files.readAllBytes(pending.get(10, TimeUnit.SECONDS)));
                    Path refresh = client.requestRefresh(new byte[0][], directory.resolve("refresh-" + run)).get(10, TimeUnit.SECONDS);
                    assertEquals(Files.readString(manifest), Files.readString(refresh));
                }
            }
            assertEquals(1, prompts.get(), "Saved fingerprint must survive restart");
            assertThrows(IOException.class, () -> new DownloadClient(addressesCopy(entry, mode), new byte[32], 1, cert -> false));
        } finally {
            if (tunnel != null) tunnel.close();
            if (backend != null) backend.close().syncUninterruptibly();
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            TrafficShaper.close();
            serverConfig = previousConfig; hostServer = previousHost; hostModpackContentFile = previousManifest;
        }
    }

    private Jsons.ModpackAddresses addressesCopy(InetSocketAddress entry, Mode mode) {
        return DownloadRoutes.resolve("", -1, mode != Mode.DEDICATED_TLS, entry, entry, null);
    }

    /** A byte-transparent TCP tunnel, optionally with the PROXY headers used by reverse proxies. */
    private static class Tunnel implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0);
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        Tunnel(int backend, Mode mode) throws IOException {
            workers.submit(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket client = listener.accept(); sockets.add(client);
                        Socket upstream = new Socket("127.0.0.1", backend); sockets.add(upstream);
                        if (mode == Mode.PROXY_V1) {
                            upstream.getOutputStream().write("PROXY TCP4 192.0.2.1 127.0.0.1 12345 25565\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        } else if (mode == Mode.PROXY_V2) {
                            DataOutputStream out = new DataOutputStream(upstream.getOutputStream());
                            out.write(new byte[]{13,10,13,10,0,13,10,81,85,73,84,10});
                            out.writeByte(0x21); out.writeByte(0x11); out.writeShort(12);
                            out.write(new byte[]{(byte)192,0,2,1,127,0,0,1}); out.writeShort(12345); out.writeShort(25565);
                        }
                        workers.submit(() -> copy(client, upstream));
                        workers.submit(() -> copy(upstream, client));
                    } catch (IOException e) { if (!listener.isClosed()) throw new UncheckedIOException(e); }
                }
            });
        }
        int port() { return listener.getLocalPort(); }
        private void copy(Socket source, Socket destination) {
            try { source.getInputStream().transferTo(destination.getOutputStream()); }
            catch (IOException ignored) { }
            finally { try { destination.shutdownOutput(); } catch (IOException ignored) { } }
        }
        @Override public void close() throws Exception {
            listener.close();
            for (Socket socket : sockets) socket.close();
            workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
