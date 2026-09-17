package pl.skidam.automodpack_core.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import io.netty.buffer.Unpooled;
import com.github.luben.zstd.ZstdInputStream;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.protocol.netty.TrafficShaper;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the actual installed zstdnet JAR, without starting Minecraft or changing its config. */
class ZstdRealProxyTest {
    @TempDir Path directory;
    private Class<?> runtime() throws Exception {
        try { return Class.forName("cn.tohsaka.factory.zstdnet.server.ServerProxyRuntime"); }
        catch (ClassNotFoundException e) {
            Assumptions.abort("Supply -PzstdnetJar and -PzstdnetLoggingJar to test the real proxy");
            throw e;
        }
    }

    @Test void shortHandshakeSurvivesRealProxySniffer() throws Exception {
        Class<?> type = runtime();
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object proxy = constructor.newInstance();
        var sniff = type.getDeclaredMethod("tryReadPacketWire", PushbackInputStream.class, Duration.class, int.class);
        sniff.setAccessible(true);
        try (ServerSocket listener = new ServerSocket(0)) {
            var executor = Executors.newSingleThreadExecutor();
            var received = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(300);
                    var input = new PushbackInputStream(socket.getInputStream(), 4096);
                    byte[] peek = (byte[]) sniff.invoke(proxy, input, Duration.ofMillis(300), 2048);
                    assertTrue(peek != null && peek.length > 0, "zstdnet discarded short download handshake as NO_DATA");
                    input.unread(peek);
                    var decoded = new ZstdInputStream(input);
                    var bytes = new ByteArrayOutputStream();
                    MinecraftDownloadHandshake.write(new DataOutputStream(bytes), "destop", 23071);
                    assertArrayEquals(bytes.toByteArray(), decoded.readNBytes(bytes.size()));
                }
                return null;
            });
            try (var socket = new ZstdSocket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", listener.getLocalPort()));
                var out = new DataOutputStream(socket.getOutputStream());
                MinecraftDownloadHandshake.write(out, "destop", 23071); out.flush();
                received.get(3, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void recognizesDownloadAfterRealProxyAddsForwardedIp() throws Exception {
        Class<?> type = runtime();
        var append = type.getDeclaredMethod("appendForwardedIpMarker", String.class, String.class);
        append.setAccessible(true);
        String forwarded = (String) append.invoke(null, "destop\0FML3\0AMDL1\0", "fe80::3970:afb8:17c5:c49d%9");
        byte[] host = forwarded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var buf = Unpooled.buffer();
        // This fixture remains shorter than 128 bytes, so these VarInts fit in one byte.
        buf.writeByte(1 + 2 + 1 + host.length + 2 + 1);
        buf.writeByte(0).writeByte(0xfb).writeByte(5).writeByte(host.length).writeBytes(host).writeShort(23071).writeByte(2);
        try { assertEquals("destop", MinecraftDownloadHandshake.decode(buf).hostname()); }
        finally { buf.release(); }
    }

    @Test void downloadsThroughActualZstdProxyAndAutoModpackServer() throws Exception {
        Class<?> type = runtime();
        byte[] payload = new byte[512 * 1024];
        new java.util.Random(1234).nextBytes(payload);
        Path source = directory.resolve("served.bin"); Files.write(source, payload);
        var previousManifest = GlobalVariables.hostModpackContentFile;
        GlobalVariables.hostModpackContentFile = directory.resolve("manifest.json");
        Files.writeString(GlobalVariables.hostModpackContentFile, "{\"modpackName\":\"zstd-test\",\"list\":[]}");
        var previousConfig = GlobalVariables.serverConfig;
        var previousServer = GlobalVariables.hostServer;
        GlobalVariables.serverConfig = new Jsons.ServerConfigFieldsV2();
        GlobalVariables.serverConfig.validateSecrets = false; // Only this isolated test server.
        GlobalVariables.hostServer = new NettyServer() {
            @Override public java.util.Optional<Path> getPath(String hash) { return java.util.Optional.of(source); }
        };
        var group = new io.netty.channel.nio.NioEventLoopGroup(2);
        var workers = Executors.newCachedThreadPool();
        io.netty.channel.Channel backend = null;
        new TrafficShaper(null);
        try (ServerSocket entry = new ServerSocket(0)) {
            var keys = NetUtils.generateKeyPair();
            var tls = io.netty.handler.ssl.SslContextBuilder.forServer(keys.getPrivate(), NetUtils.selfSign(keys))
                    .sslProvider(io.netty.handler.ssl.SslProvider.JDK).protocols("TLSv1.3").build();
            backend = new io.netty.bootstrap.ServerBootstrap().group(group)
                    .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                    .childHandler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                        @Override protected void initChannel(io.netty.channel.socket.SocketChannel channel) {
                            channel.pipeline().addLast(new ProtocolServerHandler(tls));
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            int backendPort = ((InetSocketAddress) backend.localAddress()).getPort();
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            Object proxy = constructor.newInstance();
            Class<?> mode = Class.forName(type.getName() + "$RuntimeMode");
            var load = type.getDeclaredMethod("loadOrCreateConfig", Path.class, int.class, mode); load.setAccessible(true);
            Path config = directory.resolve("proxy.properties");
            Files.writeString(config, "enabled=true\nauto_takeover=false\ntarget=127.0.0.1:" + backendPort + "\n");
            Object settings = load.invoke(proxy, config, backendPort, mode.getEnumConstants()[0]);
            set(type, proxy, "cfg", settings);
            set(type, proxy, "workers", workers);
            var stats = Class.forName("cn.tohsaka.factory.zstdnet.core.stats.TrafficStats").getConstructor().newInstance();
            set(type, proxy, "stats", stats);
            var guardConstructor = Class.forName(type.getName() + "$FloodGuard").getDeclaredConstructor(settings.getClass());
            guardConstructor.setAccessible(true);
            set(type, proxy, "guard", guardConstructor.newInstance(settings));
            var handle = type.getDeclaredMethod("handleClient", Socket.class); handle.setAccessible(true);
            Future<?> accept = workers.submit(() -> {
                try {
                    while (!entry.isClosed()) {
                        Socket socket = entry.accept();
                        workers.submit(() -> { try { handle.invoke(proxy, socket); } catch (Exception e) { throw new CompletionException(e); } });
                    }
                } catch (IOException e) { if (!entry.isClosed()) throw new CompletionException(e); }
            });
            int deadPort;
            try (ServerSocket oldLogin = new ServerSocket(0)) { deadPort = oldLogin.getLocalPort(); }
            var login = new InetSocketAddress("127.0.0.1", deadPort);
            var addresses = DownloadRoutes.resolve("", -1, true, login, login,
                    DownloadRoute.zstd(new InetSocketAddress("localhost", entry.getLocalPort())));
            addresses = pl.skidam.automodpack_core.config.ConfigTools.GSON.fromJson(
                    pl.skidam.automodpack_core.config.ConfigTools.GSON.toJson(addresses), Jsons.ModpackAddresses.class);
            for (int retry = 0; retry < 2; retry++) {
                try (var client = new DownloadClient(addresses, new byte[32], 3, certificate -> true)) {
                    Path manifest = client.downloadFile(new byte[0], directory.resolve("manifest-" + retry), null).get(15, TimeUnit.SECONDS);
                    assertEquals(Files.readString(GlobalVariables.hostModpackContentFile), Files.readString(manifest));
                    var pending = new java.util.ArrayList<CompletableFuture<Path>>();
                    for (int file = 0; file < 3; file++) {
                        pending.add(client.downloadFile(new byte[]{'x'}, directory.resolve("received-" + retry + "-" + file), null));
                    }
                    for (var download : pending) assertArrayEquals(payload, Files.readAllBytes(download.get(15, TimeUnit.SECONDS)));
                    Path refresh = client.requestRefresh(new byte[0][], directory.resolve("refresh-" + retry)).get(15, TimeUnit.SECONDS);
                    assertEquals(Files.readString(manifest), Files.readString(refresh));
                }
            }
            entry.close(); accept.get(3, TimeUnit.SECONDS);
        } finally {
            if (backend != null) backend.close().syncUninterruptibly();
            workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS);
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            TrafficShaper.close();
            GlobalVariables.hostModpackContentFile = previousManifest;
            GlobalVariables.serverConfig = previousConfig; GlobalVariables.hostServer = previousServer;
        }
    }

    private static void set(Class<?> type, Object instance, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
}
