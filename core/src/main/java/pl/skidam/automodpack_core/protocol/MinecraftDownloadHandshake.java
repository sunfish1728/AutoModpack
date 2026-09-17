package pl.skidam.automodpack_core.protocol;

import io.netty.buffer.ByteBuf;
import java.io.*;
import java.nio.charset.StandardCharsets;
import pl.skidam.automodpack_core.protocol.netty.detectors.AMMHDetector.DecodeResult;

/** Minecraft-compatible envelope which zstdnet and Forge preserve while routing. */
public final class MinecraftDownloadHandshake {
    private static final String MARKER = "\0AMDL1\0";
    private static final int MAX_PACKET = 2048;

    public static void write(DataOutputStream out, String host, int port) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var packet = new DataOutputStream(bytes);
        writeVarInt(packet, 0);
        writeVarInt(packet, 763); // Minecraft 1.20.1; this envelope is consumed before game login.
        byte[] hostname = (host + "\0FML3" + MARKER).getBytes(StandardCharsets.UTF_8);
        writeVarInt(packet, hostname.length);
        packet.write(hostname);
        packet.writeShort(port);
        writeVarInt(packet, 2);
        writeVarInt(out, bytes.size());
        out.write(bytes.toByteArray());
    }

    /** null means incomplete; a null hostname means this is an ordinary/non-download packet. */
    public static DecodeResult decode(ByteBuf source) {
        ByteBuf in = source.duplicate();
        boolean complete = false;
        try {
            int size = readVarInt(in);
            if (size < 1 || size > MAX_PACKET) return new DecodeResult(null, 0);
            if (in.readableBytes() < size) return null;
            complete = true;
            int consumed = in.readerIndex() - source.readerIndex() + size;
            in = in.readSlice(size);
            if (readVarInt(in) != 0) return new DecodeResult(null, 0);
            readVarInt(in); // protocol version
            int length = readVarInt(in);
            if (length < 1 || length > in.readableBytes() - 3) return new DecodeResult(null, 0);
            String host = in.readCharSequence(length, StandardCharsets.UTF_8).toString();
            in.readUnsignedShort();
            // zstdnet appends a NUL-delimited real-IP field after our marker.
            if (readVarInt(in) != 2 || in.isReadable() || !host.contains(MARKER)) return new DecodeResult(null, 0);
            int suffix = host.indexOf('\0');
            if (suffix <= 0) return new DecodeResult(null, 0);
            return new DecodeResult(host.substring(0, suffix), consumed);
        } catch (IndexOutOfBoundsException e) {
            return complete ? new DecodeResult(null, 0) : null;
        } catch (IllegalArgumentException e) {
            return new DecodeResult(null, 0);
        }
    }

    private static int readVarInt(ByteBuf in) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte(); value |= (b & 127) << shift;
            if ((b & 128) == 0) return value;
        }
        throw new IllegalArgumentException("Invalid VarInt");
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        do {
            int b = value & 127; value >>>= 7;
            out.writeByte(value == 0 ? b : b | 128);
        } while (value != 0);
    }
}
