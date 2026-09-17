package pl.skidam.automodpack_core.protocol;

import io.netty.buffer.Unpooled;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.protocol.netty.TrafficShaper;
import pl.skidam.automodpack_core.protocol.netty.handler.ProtocolServerHandler;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
import static pl.skidam.automodpack_core.protocol.NetUtils.*;

class MinecraftDownloadHandshakeTest {
    private byte[] handshake() throws IOException {
        var bytes = new ByteArrayOutputStream();
        MinecraftDownloadHandshake.write(new DataOutputStream(bytes), "entry.example", 23071);
        return bytes.toByteArray();
    }

    @Test void handlesEveryFragmentBoundaryWithoutConsumingInput() throws Exception {
        byte[] bytes = handshake();
        for (int length = 0; length < bytes.length; length++) {
            var part = Unpooled.wrappedBuffer(bytes, 0, length);
            try { assertNull(MinecraftDownloadHandshake.decode(part), "length=" + length); assertEquals(0, part.readerIndex()); }
            finally { part.release(); }
        }
        var complete = Unpooled.buffer().writeByte(42).writeBytes(bytes).writeByte(99);
        complete.readByte();
        try {
            var decoded = MinecraftDownloadHandshake.decode(complete);
            assertEquals("entry.example", decoded.hostname());
            assertEquals(bytes.length, decoded.consumedBytes());
            assertEquals(1, complete.readerIndex());
        } finally { complete.release(); }
    }

    @Test void rejectsOrdinaryLoginAndMalformedEnvelopes() throws Exception {
        byte[] bytes = handshake();
        for (int i = 0; i < bytes.length - 4; i++) {
            if (bytes[i] == 'A' && bytes[i + 1] == 'M') { bytes[i] = 'X'; break; }
        }
        for (byte[] candidate : new byte[][]{bytes, {1, 0}, {(byte)255, (byte)255, (byte)255, (byte)255, (byte)255}}) {
            var buf = Unpooled.wrappedBuffer(candidate);
            try { assertNull(MinecraftDownloadHandshake.decode(buf).hostname()); }
            finally { buf.release(); }
        }
    }

    @Test void serverAcceptsBothLegacyAndMinecraftDownloadHandshake() throws Exception {
        var legacy = new ByteArrayOutputStream();
        var out = new DataOutputStream(legacy);
        out.writeInt(MAGIC_AMMH); out.writeShort(9); out.writeBytes("localhost");
        var previousConfig = GlobalVariables.serverConfig;
        var previousServer = GlobalVariables.hostServer;
        GlobalVariables.hostServer = new pl.skidam.automodpack_core.protocol.netty.NettyServer();
        GlobalVariables.serverConfig = new pl.skidam.automodpack_core.config.Jsons.ServerConfigFieldsV2();
        new TrafficShaper(null);
        try {
            for (byte[] bytes : new byte[][]{legacy.toByteArray(), handshake()}) {
                var channel = new EmbeddedChannel(new ProtocolServerHandler(null));
                try {
                    for (byte b : bytes) channel.writeInbound(Unpooled.buffer(1).writeByte(b));
                    ByteBuf response = channel.readOutbound();
                    assertNotNull(response);
                    try { assertEquals(MAGIC_AMOK, response.readInt()); } finally { response.release(); }
                    assertNotNull(channel.pipeline().get("configuration-handler"));
                } finally { channel.finishAndReleaseAll(); }
            }
        } finally { TrafficShaper.close(); GlobalVariables.serverConfig = previousConfig; GlobalVariables.hostServer = previousServer; }
    }

    @Test void ordinaryLoginStillReachesMinecraftUnchanged() throws Exception {
        var previousConfig = GlobalVariables.serverConfig;
        var previousServer = GlobalVariables.hostServer;
        GlobalVariables.serverConfig = new pl.skidam.automodpack_core.config.Jsons.ServerConfigFieldsV2();
        GlobalVariables.serverConfig.bindPort = -1;
        GlobalVariables.hostServer = new pl.skidam.automodpack_core.protocol.netty.NettyServer() {
            @Override public boolean isRunning() { return true; }
        };
        byte[] bytes = handshake();
        bytes[bytes.length - 5] = 'X'; // Remove the AMDL1 marker while keeping a valid game handshake.
        var channel = new EmbeddedChannel(new ProtocolServerHandler(null));
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(bytes));
            ByteBuf forwarded = channel.readInbound();
            assertNotNull(forwarded);
            try {
                byte[] actual = new byte[forwarded.readableBytes()];
                forwarded.readBytes(actual);
                assertArrayEquals(bytes, actual);
            } finally { forwarded.release(); }
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
            GlobalVariables.serverConfig = previousConfig;
            GlobalVariables.hostServer = previousServer;
        }
    }
}
