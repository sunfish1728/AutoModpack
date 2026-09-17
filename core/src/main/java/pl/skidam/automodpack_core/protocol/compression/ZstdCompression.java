package pl.skidam.automodpack_core.protocol.compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.COMPRESSION_ZSTD;

/**
 * Zstandard compression codec implementation.
 * Loads the embedded /META-INF/jarjar/zstd-jni.jar from inside the automodpack jar
 */
public class ZstdCompression implements CompressionCodec {

    private static MethodHandle compressMethodHandle;
    private static MethodHandle decompressMethodHandle;
    private static boolean initialized = true;
    private static ClassLoader nativeLoader;

    static {
        try {
            Path tempJar = Files.createTempFile("zstd-jni-", ".jar");
            try (InputStream in = ZstdCompression.class.getResourceAsStream("/META-INF/jarjar/zstd-jni.jar")) {
                if (in == null) {
                    throw new IOException("Failed to open stream to embedded /META-INF/jarjar/zstd-jni.jar");
                }
                Files.copy(in, tempJar, StandardCopyOption.REPLACE_EXISTING);
            }
            tempJar.toFile().deleteOnExit();

            URLClassLoader loader = new URLClassLoader(new URL[]{tempJar.toUri().toURL()}, ZstdCompression.class.getClassLoader());
            nativeLoader = loader;

            Class<?> zstdClass = Class.forName("com.github.luben.zstd.Zstd", true, loader);
            Method compressMethod = zstdClass.getMethod("compress", byte[].class);
            Method decompressMethod = zstdClass.getMethod("decompress", byte[].class, int.class);

            compressMethodHandle = MethodHandles.lookup().unreflect(compressMethod);
            decompressMethodHandle = MethodHandles.lookup().unreflect(decompressMethod);
        } catch (Throwable e) {
            initialized = false;
            LOGGER.debug("Failed to initialize embedded zstd-jni", e);
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    public static InputStream inputStream(InputStream input) throws IOException {
        try {
            if (!initialized) throw new IOException("Embedded zstd-jni is unavailable");
            return (InputStream) Class.forName("com.github.luben.zstd.ZstdInputStream", true, nativeLoader)
                    .getConstructor(InputStream.class).newInstance(input);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Cannot open zstdnet input stream", e);
        }
    }

    public static OutputStream outputStream(OutputStream output) throws IOException {
        try {
            if (!initialized) throw new IOException("Embedded zstd-jni is unavailable");
            Class<?> type = Class.forName("com.github.luben.zstd.ZstdOutputStream", true, nativeLoader);
            Object stream = type.getConstructor(OutputStream.class, int.class).newInstance(output, 3);
            type.getMethod("setCloseFrameOnFlush", boolean.class).invoke(stream, false);
            return (OutputStream) stream;
        } catch (ReflectiveOperationException e) {
            throw new IOException("Cannot open zstdnet output stream", e);
        }
    }

    @Override
    public byte[] compress(byte[] input) throws IOException {
        try {
            return (byte[]) compressMethodHandle.invokeExact(input);
        } catch (Throwable e) {
            throw new IOException("Zstd compression failed", e);
        }
    }

    @Override
    public byte[] decompress(byte[] compressed, int originalLength) throws IOException {
        try {
            byte[] decompressed = (byte[]) decompressMethodHandle.invokeExact(compressed, originalLength);
            if (decompressed.length != originalLength) {
                throw new IOException("Unexpected decompressed length: " + decompressed.length + " (expected " + originalLength + ")");
            }
            return decompressed;
        } catch (Throwable e) {
            throw new IOException("Zstd decompression failed", e);
        }
    }

    @Override
    public byte getCompressionType() {
        return COMPRESSION_ZSTD;
    }
}
