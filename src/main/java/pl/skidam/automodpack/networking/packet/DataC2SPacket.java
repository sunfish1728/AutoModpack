package pl.skidam.automodpack.networking.packet;

import io.netty.buffer.Unpooled;
import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.content.DataPacket;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.ZstdNetCompatibility;
import pl.skidam.automodpack_core.protocol.DownloadRoutes;
import pl.skidam.automodpack_loader_core.ReLauncher;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.config.ConfigTools.GSON;

public class DataC2SPacket {
    public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
        try {
            // Consume the network buffer and capture the local proxy while this login still owns it.
            DataPacket packet = DataPacket.fromJson(buf.readUtf(Short.MAX_VALUE));
            InetSocketAddress connected = (InetSocketAddress) ((ClientLoginNetworkHandlerAccessor) handler)
                    .getConnection().getRemoteAddress();
            InetSocketAddress original = ModPackets.getOriginalServerAddress();
            ModPackets.setOriginalServerAddress(null);
            if (original == null) original = connected;
            var proxy = ZstdNetCompatibility.captureRoute(connected, handler.getClass().getClassLoader());
            Jsons.ModpackAddresses addresses = DownloadRoutes.resolve(packet.address, packet.port,
                    packet.requiresMagic, original, connected, proxy);
            LOGGER.info("Resolved modpack route: {}", addresses.route);
            // Certificate confirmation and file I/O must not block Minecraft's network event loop.
            return CompletableFuture.supplyAsync(() -> process(handler, packet, addresses));
        } catch (Exception e) {
            LOGGER.error("Unable to resolve modpack download route", e);
            return CompletableFuture.completedFuture(response(null));
        }
    }

    private static FriendlyByteBuf process(ClientHandshakePacketListenerImpl handler, DataPacket packet,
            Jsons.ModpackAddresses addresses) {
        try {
            Path modpackDir = ModpackUtils.getModpackPath(addresses.downloadAddress(), packet.modpackName);
            var content = ModpackUtils.requestServerModpackContent(addresses, packet.secret, true);
            if (content.isEmpty()) {
                LOGGER.error("Could not retrieve modpack content using {}", addresses.route);
                return response(null);
            }
            boolean disconnect = false;
            var update = ModpackUtils.isUpdate(content.get(), modpackDir);
            if (update.requiresUpdate()) {
                disconnectImmediately(handler);
                new ModpackUpdater(content.get(), addresses, packet.secret, modpackDir).processModpackUpdate(update);
                disconnect = true;
            } else {
                boolean selected = ModpackUtils.selectModpack(modpackDir, addresses, Set.of());
                Path contentFile = modpackDir.resolve(hostModpackContentFile.getFileName());
                if (Files.exists(contentFile)) Files.writeString(contentFile, GSON.toJson(content.get()));
                if (selected) {
                    SecretsStore.saveClientSecret(clientConfig.selectedModpack, packet.secret);
                    disconnectImmediately(handler);
                    new ReLauncher(modpackDir, UpdateType.SELECT, null).restart(false);
                    disconnect = true;
                }
            }
            if (clientConfig.selectedModpack != null && !clientConfig.selectedModpack.isBlank())
                SecretsStore.saveClientSecret(clientConfig.selectedModpack, packet.secret);
            return response(disconnect);
        } catch (Exception e) {
            LOGGER.error("Error while processing modpack download", e);
            return response(null);
        }
    }

    private static FriendlyByteBuf response(Boolean disconnect) {
        FriendlyByteBuf response = new FriendlyByteBuf(Unpooled.buffer());
        response.writeUtf(String.valueOf(disconnect), Short.MAX_VALUE);
        return response;
    }

    private static void disconnectImmediately(ClientHandshakePacketListenerImpl clientLoginNetworkHandler) {
        ((ClientConnectionAccessor) ((ClientLoginNetworkHandlerAccessor) clientLoginNetworkHandler).getConnection()).getChannel().disconnect();
    }
}
