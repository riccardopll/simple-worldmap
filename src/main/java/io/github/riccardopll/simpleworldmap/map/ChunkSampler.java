package io.github.riccardopll.simpleworldmap.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

/**
 * Turns a loaded chunk into 16x16 vanilla map color ids (MapColor id << 2 | brightness),
 * using the same height and water-depth shading as vanilla map items at scale 1:1.
 */
public final class ChunkSampler {
	private static final int NO_HEIGHT = Integer.MIN_VALUE;

	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private final BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
	private final int[] northHeights = new int[16];
	private BlockState state;
	private int waterDepth;

	/** Writes 256 packed colors into {@code out}, indexed by {@code localZ * 16 + localX}. */
	public void sample(ClientLevel level, LevelChunk chunk, byte[] out) {
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		boolean ceiling = level.dimensionType().hasCeiling();

		LevelChunk north = level.getChunkSource().getChunk(chunk.getPos().x(), chunk.getPos().z() - 1, ChunkStatus.FULL, false);
		for (int x = 0; x < 16; x++) {
			northHeights[x] = north == null ? NO_HEIGHT : surface(level, north, minX + x, minZ - 1, ceiling);
		}

		for (int x = 0; x < 16; x++) {
			int previous = northHeights[x];
			for (int z = 0; z < 16; z++) {
				int bx = minX + x;
				int bz = minZ + z;
				int height = surface(level, chunk, bx, bz, ceiling);
				out[z * 16 + x] = height == NO_HEIGHT ? 0 : color(level, bx, bz, height, previous);
				previous = height;
			}
		}
	}

	private byte color(ClientLevel level, int bx, int bz, int height, int northHeight) {
		MapColor color = state.getMapColor(level, pos.set(bx, height, bz));
		if (color == MapColor.NONE) {
			return 0;
		}
		int parity = (bx + bz) & 1;
		MapColor.Brightness brightness;
		if (color == MapColor.WATER) {
			double diff = waterDepth * 0.1 + parity * 0.2;
			brightness = diff < 0.5 ? MapColor.Brightness.HIGH : diff > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
		} else {
			int reference = northHeight == NO_HEIGHT ? height : northHeight;
			double diff = (height - reference) * 0.8 + (parity - 0.5) * 0.4;
			brightness = diff > 0.6 ? MapColor.Brightness.HIGH : diff < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
		}
		return color.getPackedId(brightness);
	}

	/**
	 * Finds the topmost block with a map color in the column, storing it in {@link #state} and the
	 * water depth above the floor in {@link #waterDepth}. Returns its Y, or {@link #NO_HEIGHT} for empty columns.
	 */
	private int surface(ClientLevel level, LevelChunk chunk, int bx, int bz, boolean ceiling) {
		int minY = level.getMinY();
		int y = ceiling ? belowCeiling(level, chunk, bx, bz) : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
		waterDepth = 0;
		if (y < minY) {
			return NO_HEIGHT;
		}

		pos.set(bx, y, bz);
		state = chunk.getBlockState(pos);
		while (state.getMapColor(level, pos) == MapColor.NONE) {
			if (--y < minY) {
				return NO_HEIGHT;
			}
			state = chunk.getBlockState(pos.setY(y));
		}

		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty()) {
			waterDepth = 1;
			below.set(bx, y - 1, bz);
			while (below.getY() >= minY && !chunk.getBlockState(below).getFluidState().isEmpty()) {
				waterDepth++;
				below.move(Direction.DOWN);
			}
			if (!state.isFaceSturdy(level, pos, Direction.UP)) {
				state = fluid.createLegacyBlock();
			}
		}
		return y;
	}

	/** For dimensions with a roof, such as the Nether: the first non-air block below the first air gap under the roof. */
	private int belowCeiling(ClientLevel level, LevelChunk chunk, int bx, int bz) {
		int minY = level.getMinY();
		int y = Math.min(level.getMaxY(), minY + level.dimensionType().logicalHeight() - 1);
		pos.set(bx, y, bz);
		while (y >= minY && !chunk.getBlockState(pos.setY(y)).isAir()) {
			y--;
		}
		while (y >= minY && chunk.getBlockState(pos.setY(y)).isAir()) {
			y--;
		}
		return y;
	}
}
