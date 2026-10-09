package io.github.riccardopll.simpleworldmap.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import com.mojang.blaze3d.platform.NativeImage;
import org.jspecify.annotations.Nullable;

/**
 * One low-detail level of a region, where each pixel averages a square of blocks: 4x4 at level 1, 16x16 at
 * level 2. Its pixels come from the full region while that is loaded, otherwise from the start of the
 * region file. Main thread only, except the static helpers.
 */
public final class RegionLod {
	public static final int LEVELS = 2;

	public final int level;
	public final int size;
	private final int x;
	private final int z;
	private @Nullable DynamicTexture texture;
	private @Nullable Identifier textureId;
	private int @Nullable [] pending;
	private @Nullable MapRegion source;
	/** The {@link MapRegion#changes()} of {@link #source} shown by the texture, or -1 before it is synced. */
	private int syncedChanges = -1;
	private long lastUsed;
	private boolean read;
	/** Set once the texture is freed and the level dropped; also read by the IO thread to skip its read. */
	private volatile boolean released;

	RegionLod(int x, int z, int level) {
		this.x = x;
		this.z = z;
		this.level = level;
		this.size = size(level);
	}

	/** Width of a level in pixels; level 0 is full detail. */
	public static int size(int level) {
		return MapRegion.SIZE >> (2 * level);
	}

	/** Builds every level of a region, indexed by {@code level - 1}. Safe on any thread. */
	static int[][] downsample(RegionFiles.Data data) {
		int[][] levels = new int[LEVELS][];
		for (int level = 1; level <= LEVELS; level++) {
			int size = size(level);
			int blocks = MapRegion.SIZE / size;
			int[] pixels = new int[size * size];
			for (int pz = 0; pz < size; pz++) {
				for (int px = 0; px < size; px++) {
					pixels[pz * size + px] = average(data.colors(), data.tints(), px * blocks, pz * blocks, blocks);
				}
			}
			levels[level - 1] = pixels;
		}
		return levels;
	}

	/** Averages the explored blocks of a square, using the explored share as alpha. */
	private static int average(byte[] colors, int @Nullable [] tints, int minX, int minZ, int blocks) {
		int red = 0;
		int green = 0;
		int blue = 0;
		int count = 0;
		for (int bz = minZ; bz < minZ + blocks; bz++) {
			for (int bx = minX; bx < minX + blocks; bx++) {
				int index = bz * MapRegion.SIZE + bx;
				int packed = colors[index] & 0xFF;
				if ((packed >> 2) != 0) {
					int color = MapRegion.color(packed, tints == null ? 0 : tints[index]);
					red += ARGB.red(color);
					green += ARGB.green(color);
					blue += ARGB.blue(color);
					count++;
				}
			}
		}
		return count == 0 ? 0 : ARGB.color(count * 255 / (blocks * blocks), red / count, green / count, blue / count);
	}

	/** Takes pixels from {@code region} while it is non-null; otherwise keeps what was read from disk. */
	void setSource(@Nullable MapRegion region) {
		if (region != source) {
			source = region;
			if (region != null) {
				syncedChanges = -1;
			}
		}
	}

	/** Whether the pixels have to come from disk and no read has been queued yet. */
	boolean needsRead() {
		return source == null && !read && syncedChanges < 0;
	}

	void markRead() {
		read = true;
	}

	boolean isReleased() {
		return released;
	}

	/** Pixels read from disk; ignored once the texture has been synced with a loaded region. */
	void fill(int[] pixels) {
		if (syncedChanges < 0) {
			pending = pixels;
		}
	}

	/** Whether the texture misses changes of {@code region}, so it no longer matches what is saved. */
	boolean isBehind(MapRegion region) {
		return source != region || syncedChanges != region.changes();
	}

	long lastUsed() {
		return lastUsed;
	}

	public boolean needsUpload() {
		return source != null ? syncedChanges != source.changes() : pending != null;
	}

	/** Work of the next upload in pixels, so uploads can be spread over frames. Every texture upload has a fixed cost too. */
	public int uploadCost() {
		return source != null ? MapRegion.AREA : Math.max(size * size, MapRegion.AREA / 64);
	}

	/**
	 * Returns the texture id for drawing, creating or refreshing the GPU texture when {@code upload} is set.
	 * Returns null if the texture has never been uploaded.
	 */
	public @Nullable Identifier texture(boolean upload, long frame) {
		lastUsed = frame;
		if (!upload) {
			return textureId;
		}
		if (texture == null) {
			texture = new DynamicTexture(() -> "Simple World Map region " + x + "," + z + " level " + level, size, size, true);
			textureId = Identifier.fromNamespaceAndPath("simple-worldmap", "region_lod" + level + "/" + MapRegion.keyPath(x, z));
			Minecraft.getInstance().getTextureManager().register(textureId, texture);
		}
		NativeImage image = texture.getPixels();
		if (source != null) {
			sync(source, image);
		} else if (pending != null) {
			for (int i = 0; i < pending.length; i++) {
				image.setPixel(i % size, i / size, pending[i]);
			}
			pending = null;
		}
		texture.upload();
		return textureId;
	}

	/** Recomputes the pixels of chunks changed since the last sync. */
	private void sync(MapRegion region, NativeImage image) {
		int blocks = MapRegion.SIZE / size;
		int perChunk = 16 / blocks;
		int[] chunkChanges = region.chunkChanges();
		for (int chunk = 0; chunk < MapRegion.CHUNKS; chunk++) {
			if (chunkChanges[chunk] <= syncedChanges) {
				continue;
			}
			int minX = (chunk & 31) * perChunk;
			int minZ = (chunk >> 5) * perChunk;
			for (int pz = minZ; pz < minZ + perChunk; pz++) {
				for (int px = minX; px < minX + perChunk; px++) {
					image.setPixel(px, pz, average(region.colors(), region.tints(), px * blocks, pz * blocks, blocks));
				}
			}
		}
		syncedChanges = region.changes();
		pending = null;
	}

	void release() {
		released = true;
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(textureId);
			texture = null;
			textureId = null;
		}
	}
}
