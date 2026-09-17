package pl.skidam.automodpack_core.protocol;

import pl.skidam.automodpack_core.protocol.compression.ZstdCompression;
import java.io.*;
import java.net.*;

/** A normal TCP socket whose streams use zstdnet framing, including the TLS traffic. */
final class ZstdSocket extends Socket {
    private InputStream input;
    private OutputStream output;
    private boolean closing;

    @Override public synchronized InputStream getInputStream() throws IOException {
        if (closing) throw new SocketException("Socket is closed");
        if (input == null) input = ZstdCompression.inputStream(new FilterInputStream(super.getInputStream()) {
            @Override public void close() { /* TCP lifetime belongs to ZstdSocket. */ }
        });
        return input;
    }

    @Override public synchronized OutputStream getOutputStream() throws IOException {
        if (closing) throw new SocketException("Socket is closed");
        if (output == null) {
            OutputStream wire = super.getOutputStream();
            // zstdnet 1.4.8 first interprets the compressed bytes as a Minecraft packet.
            // A short Zstd frame starts with 0x28 (a claimed 40-byte payload) but may
            // contain fewer than 41 bytes, so the sniffer discards it on timeout.
            // A standard Zstd skippable frame supplies a complete sniffable prefix:
            // LE magic 0x184D2A50, LE payload length 80, then 80 ignored bytes.
            // Decoders discard this metadata; no padding reaches TLS or Minecraft.
            byte[] prefix = new byte[88];
            prefix[0] = 0x50; prefix[1] = 0x2a; prefix[2] = 0x4d; prefix[3] = 0x18;
            prefix[4] = 80;
            wire.write(prefix);
            output = ZstdCompression.outputStream(new FilterOutputStream(wire) {
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    out.write(bytes, offset, length);
                }
                @Override public void close() { /* Never recursively close the owning socket. */ }
            });
        }
        return output;
    }

    @Override public synchronized void close() throws IOException {
        if (closing) return;
        closing = true;
        // Close TCP first so a stalled peer cannot block resource cleanup.
        try { super.close(); }
        finally {
            try { if (input != null) input.close(); } catch (IOException ignored) {}
            // Finishing the compression frame may write to the TCP socket we just closed.
            try { if (output != null) output.close(); } catch (IOException ignored) {}
        }
    }
}
