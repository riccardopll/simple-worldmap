package io.github.riccardopll.simpleworldmap.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.material.MapColor;

import com.mojang.blaze3d.platform.NativeImage;
import org.jspecify.annotations.Nullable;

/** A 512x512 block area (32x32 chunks) stored as one color byte per block, plus tints once any block has one. Main thread only. */
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
	private int @Nullable [] tints;
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
	void putChunk(int chunkX, int chunkZ, byte[] sample, int[] tintSample) {
		int baseX = (chunkX & 31) << 4;
		int baseZ = (chunkZ & 31) << 4;
		boolean changed = false;
		for (int dz = 0; dz < 16; dz++) {
			int row = (baseZ + dz) * SIZE + baseX;
			for (int dx = 0; dx < 16; dx++) {
				byte value = sample[dz * 16 + dx];
				int tint = tintSample[dz * 16 + dx];
				int index = row + dx;
				if (value != 0 && (colors[index] != value || tintAt(index) != tint)) {
					colors[index] = value;
					setTint(index, tint);
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
	void merge(RegionFiles.@Nullable Data stored) {
		if (stored != null) {
			for (int i = 0; i < AREA; i++) {
				if (colors[i] == 0 && stored.colors()[i] != 0) {
					colors[i] = stored.colors()[i];
					setTint(i, stored.tints() == null ? 0 : stored.tints()[i]);
					textureDirty = true;
				}
			}
		}
		loaded = true;
	}

	private int tintAt(int index) {
		return tints == null ? 0 : tints[index];
	}

	private void setTint(int index, int tint) {
		if (tints == null) {
			if (tint == 0) {
				return;
			}
			tints = new int[AREA];
		}
		tints[index] = tint;
	}

	public boolean isLoaded() {
		return loaded;
	}

	boolean isDirty() {
		return dirty;
	}

	RegionFiles.Data snapshotForSave() {
		dirty = false;
		return new RegionFiles.Data(colors.clone(), tints == null ? null : tints.clone());
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
				int tint = tintAt(row + px);
				int packed = colors[row + px] & 0xFF;
				pixels.setPixel(px, pz, tint == 0 ? PALETTE[packed] : tinted(packed, tint));
			}
		}
		texture.upload();
		textureDirty = false;
		return textureId;
	}

	/**
	 * Shades a tint with the map brightness of {@code packed}. Water keeps its tint; grass and
	 * foliage tints are darkened so plains biomes stay close to the untinted vanilla palette.
	 */
	private static int tinted(int packed, int tint) {
		int id = packed >> 2;
		int percent = id == MapColor.WATER.id ? 100 : id == MapColor.GRASS.id ? 90 : 70;
		return ARGB.scaleRGB(ARGB.opaque(tint), MapColor.Brightness.byId(packed & 3).modifier * percent / 100);
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
