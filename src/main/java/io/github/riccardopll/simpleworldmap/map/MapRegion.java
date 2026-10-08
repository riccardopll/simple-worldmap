package io.github.riccardopll.simpleworldmap.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.MapColor;

import com.mojang.blaze3d.platform.NativeImage;

/** A 512x512 block area (32x32 chunks) stored as one byte per block. Main thread only. */
public final class MapRegion {
	public static final int SHIFT = 9;
	public static final int SIZE = 1 << SHIFT;
	public static final int AREA = SIZE * SIZE;

	private static final int[] PALETTE = new int[256];

	static {
		for (int i = 0; i < 256; i++) {
			PALETTE[i] = (i >> 2) == 0 ? 0 : MapColor.getColorFromPackedId(i);
		}
	}

	public final int x;
	public final int z;
	private final byte[] colors = new byte[AREA];
	private boolean loaded;
	private boolean dirty;
	private boolean textureDirty = true;
	private DynamicTexture texture;
	private Identifier textureId;
	private long lastUsed;

	MapRegion(int x, int z) {
		this.x = x;
		this.z = z;
	}

	/** Copies a 16x16 chunk sample into the region. */
	void putChunk(int chunkX, int chunkZ, byte[] sample) {
		int baseX = (chunkX & 31) << 4;
		int baseZ = (chunkZ & 31) << 4;
		boolean changed = false;
		for (int dz = 0; dz < 16; dz++) {
			int row = (baseZ + dz) * SIZE + baseX;
			for (int dx = 0; dx < 16; dx++) {
				byte value = sample[dz * 16 + dx];
				if (value != 0 && colors[row + dx] != value) {
					colors[row + dx] = value;
					changed = true;
				}
			}
		}
		if (changed) {
			dirty = true;
			textureDirty = true;
		}
	}

	/** Fills in blocks that were not sampled this session with data read from disk. */
	void merge(byte[] stored) {
		if (stored != null) {
			for (int i = 0; i < AREA; i++) {
				if (colors[i] == 0 && stored[i] != 0) {
					colors[i] = stored[i];
					textureDirty = true;
				}
			}
		}
		loaded = true;
	}

	public boolean isLoaded() {
		return loaded;
	}

	boolean isDirty() {
		return dirty;
	}

	byte[] snapshotForSave() {
		dirty = false;
		return colors.clone();
	}

	public int colorAt(int localX, int localZ) {
		return colors[localZ * SIZE + localX] & 0xFF;
	}

	boolean hasTexture() {
		return texture != null;
	}

	long lastUsed() {
		return lastUsed;
	}

	public boolean needsUpload() {
		return texture == null || textureDirty;
	}

	/**
	 * Returns the texture id for drawing, creating or refreshing the GPU texture when {@code upload} is set.
	 * Returns null if the texture has never been uploaded.
	 */
	public Identifier texture(boolean upload, long frame) {
		lastUsed = frame;
		if (!upload) {
			return textureId;
		}
		if (texture == null) {
			texture = new DynamicTexture(() -> "Simple World Map region " + x + "," + z, SIZE, SIZE, true);
			textureId = Identifier.fromNamespaceAndPath("simple-worldmap", "region/" + regionKeyPath());
			Minecraft.getInstance().getTextureManager().register(textureId, texture);
		}
		NativeImage pixels = texture.getPixels();
		for (int pz = 0; pz < SIZE; pz++) {
			int row = pz * SIZE;
			for (int px = 0; px < SIZE; px++) {
				pixels.setPixel(px, pz, PALETTE[colors[row + px] & 0xFF]);
			}
		}
		texture.upload();
		textureDirty = false;
		return textureId;
	}

	private String regionKeyPath() {
		return (x < 0 ? "n" + -x : String.valueOf(x)) + "_" + (z < 0 ? "n" + -z : String.valueOf(z));
	}

	void releaseTexture() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(textureId);
			texture = null;
			textureId = null;
			textureDirty = true;
		}
	}
}
