package pl.skidam.automodpack_core.protocol;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

class ZstdDownloadTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void downloadsAndReconnectsAfterLoginProxyHasClosed(boolean zstd) throws Exception {
        int closedPort;
        try (ServerSocket loginProxy = new ServerSocket(0)) { closedPort = loginProxy.getLocalPort(); }
        try (ServerSocket entry = new ServerSocket(0)) {
            entry.setSoTimeout(5000);
            var keyPair = generateKeyPair();
            var certificate = selfSign(keyPair);
            var keys = KeyStore.getInstance(KeyStore.getDefaultType());
            keys.load(null);
            keys.setKeyEntry("test", keyPair.getPrivate(), new char[0], new java.security.cert.Certificate[]{certificate});
            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, new char[0]);
            var context = SSLContext.getInstance("TLSv1.3");
            context.init(kmf.getKeyManagers(), null, null);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            byte[] payload = "actual mod file after confirmation".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Future<?> server = executor.submit(() -> {
                try {
                    // The first TLS probe is rejected; the second uses the user's accepted certificate.
                    for (int attempt = 0; attempt < 4; attempt++) {
                        try (Socket remote = entry.accept()) {
                            remote.setSoTimeout(5000);
                            var input = new DataInputStream(zstd ? new ZstdInputStream(remote.getInputStream()) : remote.getInputStream());
                            OutputStream output = zstd ? new ZstdOutputStream(remote.getOutputStream(), 3).setCloseFrameOnFlush(false) : remote.getOutputStream();
                            if (zstd) {
                                int size = readVarInt(input);
                                byte[] handshake = input.readNBytes(size);
                                assertTrue(new String(handshake, java.nio.charset.StandardCharsets.UTF_8).contains("\0AMDL1\0"));
                            } else {
                                assertEquals(MAGIC_AMMH, input.readInt());
                                input.readNBytes(input.readUnsignedShort());
                            }
                            var plainOut = new DataOutputStream(output);
                            plainOut.writeInt(MAGIC_AMOK);
                            plainOut.flush();
                            Socket streams = new Socket() {
                                public boolean isConnected() { return true; }
                                public InputStream getInputStream() { return input; }
                                public OutputStream getOutputStream() { return output; }
                                public synchronized void close() throws IOException { remote.close(); }
                                public void setSoTimeout(int timeout) throws SocketException { remote.setSoTimeout(timeout); }
                                public int getSoTimeout() throws SocketException { return remote.getSoTimeout(); }
                            };
                            try (var tls = (SSLSocket) context.getSocketFactory().createSocket(streams, "localhost", entry.getLocalPort(), true)) {
                                tls.setUseClientMode(false);
                                try { tls.startHandshake(); } catch (IOException e) { if (attempt % 2 == 0) continue; throw e; }
                                var in = new DataInputStream(tls.getInputStream());
                                var out = new DataOutputStream(tls.getOutputStream());
                                assertEquals(PROTOCOL_VERSION, in.readByte());
                                assertEquals(CONFIGURATION_COMPRESSION_TYPE, in.readByte());
                                in.readByte();
                                out.write(new byte[]{PROTOCOL_VERSION, CONFIGURATION_COMPRESSION_TYPE, COMPRESSION_NONE}); out.flush();
                                assertEquals(PROTOCOL_VERSION, in.readByte());
                                assertEquals(CONFIGURATION_CHUNK_SIZE_TYPE, in.readByte());
                                int chunk = in.readInt();
                                out.write(new byte[]{PROTOCOL_VERSION, CONFIGURATION_CHUNK_SIZE_TYPE}); out.writeInt(chunk); out.flush();
                                in.readByte(); assertEquals(CONFIGURATION_ECHO_TYPE, in.readByte());
                                int compressed = in.readInt(); in.readInt();
                                byte[] request = in.readNBytes(compressed);
                                assertEquals(FILE_REQUEST_TYPE, request[1]);
                                var header = new ByteArrayOutputStream();
                                var data = new DataOutputStream(header);
                                data.writeByte(PROTOCOL_VERSION); data.writeByte(FILE_RESPONSE_TYPE); data.writeLong(payload.length);
                                frame(out, header.toByteArray()); frame(out, payload);
                                frame(out, new byte[]{PROTOCOL_VERSION, END_OF_TRANSMISSION}); out.flush();
                            }
                        }
                    }
                } catch (Exception e) { throw new CompletionException(e); }
            });
            try {
                var addresses = new Jsons.ModpackAddresses(new InetSocketAddress("127.0.0.1", closedPort), new InetSocketAddress("localhost", entry.getLocalPort()), true);
                var json = ConfigTools.GSON.toJsonTree(addresses).getAsJsonObject();
                if (zstd) json.add("zstdAddress", ConfigTools.GSON.toJsonTree(addresses.serverAddress));
                else json.add("hostAddress", ConfigTools.GSON.toJsonTree(addresses.serverAddress));
                addresses = ConfigTools.GSON.fromJson(json, Jsons.ModpackAddresses.class);
                for (int download = 0; download < 2; download++) {
                try (var client = new DownloadClient(addresses, new byte[32], 1, cert -> true)) {
                    Path destination = directory.resolve("download.bin");
                    client.downloadFile(new byte[]{1}, destination, null).get(5, TimeUnit.SECONDS);
                    assertArrayEquals(payload, Files.readAllBytes(destination));
                }
                }
                server.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (server.isDone()) server.get();
                throw e;
            } finally { entry.close(); executor.shutdownNow(); }
        }
    }

    private static void frame(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length); out.writeInt(bytes.length); out.write(bytes);
    }
    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte(); value |= (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new IOException("Invalid VarInt");
    }
}
