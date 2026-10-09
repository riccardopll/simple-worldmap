package io.github.riccardopll.simpleworldmap.map;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

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
import org.jspecify.annotations.Nullable;

/** Map data and waypoints for the dimension the player is currently in. Main thread only. */
public final class MapSession {
	private static final long SAMPLE_BUDGET_NANOS = 2_000_000L;
	private static final int TEXTURE_IDLE_FRAMES = 120;

	public final ClientLevel level;
	public final WaypointStore waypoints;
	private final Path dir;
	private final Map<Long, MapRegion> regions = new HashMap<>();
	/** Low-detail images by level, starting at level 1. */
	private final List<Map<Long, RegionLod>> lods = IntStream.range(0, RegionLod.LEVELS)
		.<Map<Long, RegionLod>>mapToObj(level -> new HashMap<>())
		.toList();
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

	/** Returns the region if it is in memory, without loading it. */
	public @Nullable MapRegion cachedRegion(int regionX, int regionZ) {
		return regions.get(RegionFiles.key(regionX, regionZ));
	}

	/**
	 * Returns a low-detail level of the region, reading it from disk if needed but never loading the full
	 * region. Returns null for unexplored areas.
	 */
	public @Nullable RegionLod lod(int regionX, int regionZ, int level) {
		long key = RegionFiles.key(regionX, regionZ);
		MapRegion region = regions.get(key);
		MapRegion source = region != null && region.isLoaded() ? region : null;
		Map<Long, RegionLod> cache = lods.get(level - 1);
		RegionLod lod = cache.get(key);
		if (lod == null) {
			if (source == null && !onDisk.contains(key)) {
				return null;
			}
			RegionLod created = new RegionLod(regionX, regionZ, level);
			cache.put(key, created);
			if (source == null) {
				RegionFiles.IO.execute(() -> {
					int[] pixels = RegionFiles.readLod(dir, regionX, regionZ, level);
					Minecraft.getInstance().execute(() -> {
						if (!closed && pixels != null && cache.get(key) == created) {
							created.fill(pixels);
						}
					});
				});
			}
			lod = created;
		}
		lod.setSource(source);
		return lod;
	}

	/** Returns a low-detail level of the region if it is in memory, without reading it. */
	public @Nullable RegionLod cachedLod(int regionX, int regionZ, int level) {
		return lods.get(level - 1).get(RegionFiles.key(regionX, regionZ));
	}

	public void saveDirty() {
		for (MapRegion region : regions.values()) {
			save(region);
		}
	}

	private void save(MapRegion region) {
		if (!region.isDirty()) {
			return;
		}
		RegionFiles.Data snapshot = region.snapshotForSave();
		boolean partial = !region.isLoaded();
		onDisk.add(RegionFiles.key(region.x, region.z));
		RegionFiles.IO.execute(() -> RegionFiles.save(dir, region.x, region.z, snapshot, partial));
	}

	/** Drops regions more than one region away from the player once they are saved. */
	public void trim(int playerRegionX, int playerRegionZ) {
		Iterator<MapRegion> iterator = regions.values().iterator();
		while (iterator.hasNext()) {
			MapRegion region = iterator.next();
			if (region.isLoaded() && isFar(region, playerRegionX, playerRegionZ)) {
				drop(region);
				iterator.remove();
			}
		}
	}

	private static boolean isFar(MapRegion region, int playerRegionX, int playerRegionZ) {
		return Math.abs(region.x - playerRegionX) > 1 || Math.abs(region.z - playerRegionZ) > 1;
	}

	/** Saves a region and frees its texture before it is removed, keeping its low-detail images only if they match what is saved. */
	private void drop(MapRegion region) {
		save(region);
		region.releaseTexture();
		long key = RegionFiles.key(region.x, region.z);
		for (Map<Long, RegionLod> cache : lods) {
			RegionLod lod = cache.get(key);
			if (lod != null && lod.isBehind(region)) {
				lod.release();
				cache.remove(key);
			} else if (lod != null) {
				lod.setSource(null);
			}
		}
	}

	/**
	 * Frees what the map has not drawn recently: textures, low-detail images, and full regions more than
	 * one region away from the player.
	 */
	public void releaseIdle(long frame, int playerRegionX, int playerRegionZ) {
		Iterator<MapRegion> iterator = regions.values().iterator();
		while (iterator.hasNext()) {
			MapRegion region = iterator.next();
			if (frame - region.lastUsed() <= TEXTURE_IDLE_FRAMES) {
				continue;
			}
			if (region.isLoaded() && isFar(region, playerRegionX, playerRegionZ)) {
				drop(region);
				iterator.remove();
			} else if (region.hasTexture()) {
				region.releaseTexture();
			}
		}
		for (Map<Long, RegionLod> cache : lods) {
			cache.values().removeIf(lod -> {
				if (frame - lod.lastUsed() <= TEXTURE_IDLE_FRAMES) {
					return false;
				}
				lod.release();
				return true;
			});
		}
	}

	/** Frees all textures and low-detail images, for when the map closes. */
	public void releaseTextures() {
		for (MapRegion region : regions.values()) {
			region.releaseTexture();
		}
		for (Map<Long, RegionLod> cache : lods) {
			cache.values().forEach(RegionLod::release);
			cache.clear();
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
