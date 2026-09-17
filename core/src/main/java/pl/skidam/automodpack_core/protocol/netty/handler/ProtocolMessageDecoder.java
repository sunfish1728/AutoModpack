package pl.skidam.automodpack_core.protocol.netty.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import pl.skidam.automodpack_core.protocol.netty.message.request.*;
import java.util.List;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

/** Messages may span any number of compression frames or TCP reads. */
public class ProtocolMessageDecoder extends ByteToMessageDecoder {
    private static final int MAX_MESSAGE_BYTES = 64 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 38) return; // version, type, secret, length/count
        int start = in.readerIndex();
        byte version = in.readByte();
        byte type = in.readByte();
        byte[] secret = new byte[32];
        in.readBytes(secret);
        int length = in.readInt();
        if (length < 0 || length > MAX_MESSAGE_BYTES) throw new CorruptedFrameException("Invalid message length/count");
        if (type == REFRESH_REQUEST_TYPE) {
            if (length == 0) {
                out.add(new RefreshRequestMessage(version, secret, new byte[0][]));
                return;
            }
            if (length > 1_000_000) throw new CorruptedFrameException("Too many refresh hashes");
            if (in.readableBytes() < 4) { in.readerIndex(start); return; }
            int hashLength = in.readInt();
            if (hashLength < 1 || hashLength > 128 || (long) length * hashLength > MAX_MESSAGE_BYTES)
                throw new CorruptedFrameException("Invalid refresh hash length");
            if (in.readableBytes() < (long) length * hashLength) { in.readerIndex(start); return; }
            byte[][] hashes = new byte[length][];
            for (int i = 0; i < length; i++) { hashes[i] = new byte[hashLength]; in.readBytes(hashes[i]); }
            out.add(new RefreshRequestMessage(version, secret, hashes));
            return;
        }
        if (type != ECHO_TYPE && type != FILE_REQUEST_TYPE && type != FILE_RESPONSE_TYPE)
            throw new CorruptedFrameException("Unknown message type: " + type);
        if (in.readableBytes() < length) { in.readerIndex(start); return; }
        byte[] data = new byte[length]; in.readBytes(data);
        switch (type) {
            case ECHO_TYPE -> out.add(new EchoMessage(version, secret, data));
            case FILE_REQUEST_TYPE -> out.add(new FileRequestMessage(version, secret, data));
            case FILE_RESPONSE_TYPE -> out.add(new FileResponseMessage(version, secret, data));
        }
    }
}
