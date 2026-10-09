package io.github.riccardopll.simpleworldmap.map;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.riccardopll.simpleworldmap.waypoint.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/** Map data and waypoints for the dimension the player is currently in. Main thread only. */
public final class MapSession {
	private static final long SAMPLE_BUDGET_NANOS = 2_000_000L;
	private static final int TEXTURE_IDLE_FRAMES = 120;

	public final ClientLevel level;
	public final WaypointStore waypoints;
	private final Path dir;
	private final Map<Long, MapRegion> regions = new HashMap<>();
	private final Set<Long> onDisk;
	private final LinkedHashSet<Long> queue = new LinkedHashSet<>();
	private final ChunkSampler sampler = new ChunkSampler();
	private final byte[] sample = new byte[256];
	private final int[] tintSample = new int[256];
	private boolean closed;

	public MapSession(Minecraft minecraft, ClientLevel level) {
		this.level = level;
		this.dir = directory(minecraft, level.dimension());
		this.onDisk = RegionFiles.list(dir);
		this.waypoints = WaypointStore.load(dir);
	}

	/** The folder holding map data and waypoints for a dimension of the current world, server or realm. */
	public static Path directory(Minecraft minecraft, ResourceKey<Level> dimension) {
		Identifier id = dimension.identifier();
		return minecraft.gameDirectory.toPath()
			.resolve("simple-worldmap")
			.resolve(worldId(minecraft))
			.resolve(sanitize(id.getNamespace()))
			.resolve(sanitize(id.getPath()));
	}

	/** A stable folder name for the current single-player world, server or realm. */
	private static String worldId(Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			return "singleplayer/" + sanitize(String.valueOf(root.getFileName()));
		}
		ServerData data = minecraft.getCurrentServer();
		if (data != null) {
			return data.isRealm() ? "realms/" + sanitize(data.name) : "multiplayer/" + sanitize(data.ip.toLowerCase(Locale.ROOT));
		}
		return "unknown";
	}

	private static String sanitize(String name) {
		String clean = name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^\\.+", "_");
		return clean.isEmpty() ? "_" : clean;
	}

	public void enqueueChunk(int chunkX, int chunkZ) {
		queue.add(RegionFiles.key(chunkX, chunkZ));
	}

	/** Samples queued chunks until the time budget for this tick runs out. */
	public void processQueue() {
		long deadline = System.nanoTime() + SAMPLE_BUDGET_NANOS;
		Iterator<Long> iterator = queue.iterator();
		while (iterator.hasNext() && System.nanoTime() < deadline) {
			long key = iterator.next();
			iterator.remove();
			int chunkX = RegionFiles.keyX(key);
			int chunkZ = RegionFiles.keyZ(key);
			LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
			if (chunk != null) {
				sampler.sample(level, chunk, sample, tintSample);
				region(chunkX >> 5, chunkZ >> 5, true).putChunk(chunkX, chunkZ, sample, tintSample);
			}
		}
	}

	/** Returns the region, loading it from disk if needed. Without {@code create}, returns null for unexplored areas. */
	public MapRegion region(int regionX, int regionZ, boolean create) {
		long key = RegionFiles.key(regionX, regionZ);
		MapRegion region = regions.get(key);
		if (region != null) {
			return region;
		}
		boolean stored = onDisk.contains(key);
		if (!create && !stored) {
			return null;
		}
		MapRegion created = new MapRegion(regionX, regionZ);
		regions.put(key, created);
		if (stored) {
			Path file = RegionFiles.file(dir, regionX, regionZ);
			RegionFiles.IO.execute(() -> {
				RegionFiles.Data data = RegionFiles.read(file);
				Minecraft.getInstance().execute(() -> {
					if (!closed && regions.get(key) == created) {
						created.merge(data);
					}
				});
			});
		} else {
			created.merge(null);
		}
		return created;
	}

	public void saveDirty() {
		for (MapRegion region : regions.values()) {
			save(region);
		}
	}

	/**
	 * Writes a dirty region. A region still loading from disk holds only blocks sampled since, so its
	 * stored data is read back on the IO thread and the new samples are laid over it.
	 */
	private void save(MapRegion region) {
		if (!region.isDirty()) {
			return;
		}
		RegionFiles.Data snapshot = region.snapshotForSave();
		boolean partial = !region.isLoaded();
		Path file = RegionFiles.file(dir, region.x, region.z);
		onDisk.add(RegionFiles.key(region.x, region.z));
		RegionFiles.IO.execute(() -> RegionFiles.write(file, partial ? RegionFiles.overlay(RegionFiles.read(file), snapshot) : snapshot));
	}

	/** Drops regions more than one region away from the player once they are saved. */
	public void trim(int playerRegionX, int playerRegionZ) {
		Iterator<MapRegion> iterator = regions.values().iterator();
		while (iterator.hasNext()) {
			MapRegion region = iterator.next();
			if (region.isLoaded() && (Math.abs(region.x - playerRegionX) > 1 || Math.abs(region.z - playerRegionZ) > 1)) {
				save(region);
				region.releaseTexture();
				iterator.remove();
			}
		}
	}

	/** Frees GPU textures of regions that have not been drawn recently. */
	public void releaseIdleTextures(long frame) {
		for (MapRegion region : regions.values()) {
			if (region.hasTexture() && frame - region.lastUsed() > TEXTURE_IDLE_FRAMES) {
				region.releaseTexture();
			}
		}
	}

	public void releaseTextures() {
		for (MapRegion region : regions.values()) {
			region.releaseTexture();
		}
	}

	public void close() {
		saveDirty();
		releaseTextures();
		regions.clear();
		queue.clear();
		closed = true;
	}
}
