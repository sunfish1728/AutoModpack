package pl.skidam.automodpack_core.protocol;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolMessageDecoder;
import pl.skidam.automodpack_core.protocol.netty.message.request.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolFragmentationTest {
    @Test void fileRequestCanArriveOneByteAtATime() {
        var wire = Unpooled.buffer().writeByte(PROTOCOL_VERSION).writeByte(FILE_REQUEST_TYPE)
                .writeZero(32).writeInt(40).writeZero(40);
        var channel = new EmbeddedChannel(new ProtocolMessageDecoder());
        try {
            while (wire.readableBytes() > 1) {
                assertFalse(channel.writeInbound(wire.readRetainedSlice(1)));
            }
            assertTrue(channel.writeInbound(wire.readRetainedSlice(1)));
            FileRequestMessage message = channel.readInbound();
            assertEquals(40, message.getFileHash().length);
            assertNull(channel.readInbound());
        } finally { wire.release(); channel.finishAndReleaseAll(); }
    }

    @Test void largeRefreshSpansSeveralCompressionFrames() {
        var wire = Unpooled.buffer().writeByte(PROTOCOL_VERSION).writeByte(REFRESH_REQUEST_TYPE)
                .writeZero(32).writeInt(2000).writeInt(40).writeZero(80000);
        var channel = new EmbeddedChannel(new ProtocolMessageDecoder());
        try {
            while (wire.readableBytes() > 8192) assertFalse(channel.writeInbound(wire.readRetainedSlice(8192)));
            assertTrue(channel.writeInbound(wire.readRetainedSlice(wire.readableBytes())));
            RefreshRequestMessage message = channel.readInbound();
            assertEquals(2000, message.getFileHashesList().length);
        } finally { wire.release(); channel.finishAndReleaseAll(); }
    }

    @Test void emptyRefreshDoesNotConsumeNextRequest() {
        var wire = Unpooled.buffer().writeByte(PROTOCOL_VERSION).writeByte(REFRESH_REQUEST_TYPE)
                .writeZero(32).writeInt(0).writeByte(PROTOCOL_VERSION).writeByte(FILE_REQUEST_TYPE)
                .writeZero(32).writeInt(0);
        var channel = new EmbeddedChannel(new ProtocolMessageDecoder());
        try {
            assertTrue(channel.writeInbound(wire));
            assertInstanceOf(RefreshRequestMessage.class, channel.readInbound());
            assertInstanceOf(FileRequestMessage.class, channel.readInbound());
            assertNull(channel.readInbound());
        } finally { channel.finishAndReleaseAll(); }
    }
}
