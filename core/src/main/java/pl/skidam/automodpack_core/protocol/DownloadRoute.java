package pl.skidam.automodpack_core.protocol;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Persistent connection recipe. Never contains a live socket or a login proxy handle. */
public record DownloadRoute(InetSocketAddress endpoint, Transport transport, Handshake handshake) {
    public enum Transport { TCP, ZSTD }
    public enum Handshake { TLS, AMMH, MINECRAFT }

    public DownloadRoute {
        Objects.requireNonNull(endpoint, "Download endpoint");
        Objects.requireNonNull(transport, "Download transport");
        Objects.requireNonNull(handshake, "Download handshake");
        if (endpoint.getHostString().isBlank() || endpoint.getPort() < 1 || endpoint.getPort() > 65535)
            throw new IllegalArgumentException("Invalid download endpoint: " + endpoint);
        if (transport == Transport.ZSTD && handshake != Handshake.MINECRAFT)
            throw new IllegalArgumentException("zstdnet proxy requires a Minecraft routing handshake");
    }

    public static DownloadRoute tcp(InetSocketAddress endpoint, boolean magic) {
        return new DownloadRoute(endpoint, Transport.TCP, magic ? Handshake.AMMH : Handshake.TLS);
    }

    public static DownloadRoute zstd(InetSocketAddress endpoint) {
        return new DownloadRoute(endpoint, Transport.ZSTD, Handshake.MINECRAFT);
    }
}
