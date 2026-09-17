package pl.skidam.automodpack_core.protocol;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;
import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

/** Owns TCP, optional stream compression, routing handshake and TLS as one connection lifetime. */
public final class DownloadTransport {
    private DownloadTransport() {}

    public static SSLSocket connect(DownloadRoute route, SSLContext context) throws IOException {
        Socket stream = route.transport() == DownloadRoute.Transport.ZSTD ? new ZstdSocket() : new Socket();
        Socket owned = stream;
        try {
            InetSocketAddress endpoint = route.endpoint();
            LOGGER.info("Download route: {} via {} / {}", endpoint, route.transport(), route.handshake());
            stream.connect(new InetSocketAddress(endpoint.getHostString(), endpoint.getPort()), 10000);
            stream.setSoTimeout(10000);
            stream.setTcpNoDelay(true);
            if (route.handshake() != DownloadRoute.Handshake.TLS) {
                // Do not prefetch bytes here: TLS must own every byte after the acknowledgement.
                DataOutputStream out = new DataOutputStream(stream.getOutputStream());
                if (route.handshake() == DownloadRoute.Handshake.MINECRAFT) {
                    MinecraftDownloadHandshake.write(out, endpoint.getHostString(), endpoint.getPort());
                } else {
                    byte[] host = endpoint.getHostString().getBytes(StandardCharsets.UTF_8);
                    if (host.length > 65535) throw new IOException("Download hostname too long");
                    out.writeInt(MAGIC_AMMH);
                    out.writeShort(host.length);
                    out.write(host);
                }
                out.flush();
                if (new DataInputStream(stream.getInputStream()).readInt() != MAGIC_AMOK)
                    throw new IOException("Endpoint did not acknowledge the AutoModpack routing handshake: " + endpoint);
            }
            SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(stream,
                    endpoint.getHostString(), endpoint.getPort(), true);
            owned = tls;
            tls.setEnabledProtocols(new String[]{"TLSv1.3"});
            tls.setEnabledCipherSuites(new String[]{"TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256"});
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            return tls;
        } catch (IOException | RuntimeException e) {
            try { owned.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }
}
