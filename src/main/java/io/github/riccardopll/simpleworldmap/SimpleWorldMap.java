package io.github.riccardopll.simpleworldmap;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.riccardopll.simpleworldmap.map.MapRegion;
import io.github.riccardopll.simpleworldmap.map.MapSession;
import io.github.riccardopll.simpleworldmap.map.RegionFiles;
import io.github.riccardopll.simpleworldmap.screen.WorldMapScreen;
import io.github.riccardopll.simpleworldmap.waypoint.Waypoint;
import io.github.riccardopll.simpleworldmap.waypoint.WaypointStore;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class SimpleWorldMap implements ClientModInitializer {
	public static final String MOD_ID = "simple-worldmap";

	private static final int REFRESH_RADIUS = 2;
	private static final int REFRESH_INTERVAL_TICKS = 40;
	private static final int SAVE_INTERVAL_TICKS = 600;

	public static KeyMapping openMapKey;
	public static KeyMapping zoomInKey;
	public static KeyMapping zoomOutKey;

	private static @Nullable MapSession session;
	private static long ticks;
	private static boolean deathKnown;
	private static @Nullable GlobalPos lastDeath;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "general"));
		openMapKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.simple-worldmap.open_map", InputConstants.KEY_M, category));
		// Bindings store physical keys: these are the US + and - positions, which other layouts may label differently.
		zoomInKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.simple-worldmap.zoom_in", InputConstants.KEY_EQUALS, category));
		zoomOutKey = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.simple-worldmap.zoom_out", InputConstants.KEY_MINUS, category));

		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			MapSession current = session(level);
			int x = chunk.getPos().x();
			int z = chunk.getPos().z();
			current.enqueueChunk(x, z);
			// The chunk to the south shades its top row against this chunk's heights.
			current.enqueueChunk(x, z + 1);
		});
		ClientTickEvents.END_CLIENT_TICK.register(SimpleWorldMap::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			closeSession();
			RegionFiles.shutdown();
			WaypointStore.shutdown();
		});
	}

	/** Zoom keys act only inside the map screen, so presses made in game are discarded. */
	private static void drainZoomClicks() {
		while (zoomInKey.consumeClick()) {
		}
		while (zoomOutKey.consumeClick()) {
		}
	}

	/** The session for the given level, starting a new one after joining a world or changing dimension. */
	public static MapSession session(ClientLevel level) {
		if (session == null || session.level != level) {
			closeSession();
			session = new MapSession(Minecraft.getInstance(), level);
		}
		return session;
	}

	private static void closeSession() {
		if (session != null) {
			session.close();
			session = null;
		}
	}

	/**
	 * The server sends the last death location with every respawn, so a new value means the player died.
	 * The value present when joining is only remembered.
	 */
	private static void checkDeath(Minecraft client, MapSession current, @Nullable GlobalPos death) {
		if (!deathKnown) {
			deathKnown = true;
			lastDeath = death;
			return;
		}
		if (death == null || death.equals(lastDeath)) {
			return;
		}
		lastDeath = death;
		WaypointStore store = death.dimension() == current.level.dimension()
			? current.waypoints
			: WaypointStore.load(MapSession.directory(client, death.dimension()));
		BlockPos pos = death.pos();
		String name = Component.translatable("simple-worldmap.waypoint.death").getString();
		store.add(new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), Waypoint.COLORS[0]));
	}

	private static void tick(Minecraft client) {
		ClientLevel level = client.level;
		LocalPlayer player = client.player;
		if (level == null || player == null) {
			closeSession();
			deathKnown = false;
			while (openMapKey.consumeClick()) {
			}
			drainZoomClicks();
			return;
		}

		MapSession current = session(level);
		ticks++;
		checkDeath(client, current, player.getLastDeathLocation().orElse(null));

		while (openMapKey.consumeClick()) {
			if (client.gui.screen() == null) {
				client.gui.setScreen(new WorldMapScreen(current));
			}
		}

		drainZoomClicks();

		int playerChunkX = player.blockPosition().getX() >> 4;
		int playerChunkZ = player.blockPosition().getZ() >> 4;
		if (ticks % REFRESH_INTERVAL_TICKS == 0) {
			for (int dx = -REFRESH_RADIUS; dx <= REFRESH_RADIUS; dx++) {
				for (int dz = -REFRESH_RADIUS; dz <= REFRESH_RADIUS; dz++) {
					current.enqueueChunk(playerChunkX + dx, playerChunkZ + dz);
				}
			}
		}
		current.processQueue();

		if (ticks % SAVE_INTERVAL_TICKS == 0) {
			current.saveDirty();
			if (!(client.gui.screen() instanceof WorldMapScreen)) {
				current.trim(playerChunkX >> (MapRegion.SHIFT - 4), playerChunkZ >> (MapRegion.SHIFT - 4));
			}
		}
	}
}
