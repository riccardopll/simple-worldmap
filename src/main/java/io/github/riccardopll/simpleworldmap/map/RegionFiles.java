package io.github.riccardopll.simpleworldmap.map;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Region file format: 4-byte magic, 1-byte version, then one deflate stream holding
 * <ol>
 * <li>each {@link RegionLod} level from the smallest up, as alpha, red, green and blue planes, so drawing a
 * zoomed-out map inflates only the start of the file;</li>
 * <li>512*512 color bytes;</li>
 * <li>a byte that is 1 when tints follow, then the red, green and blue planes of the tints, 512*512 bytes each.</li>
 * </ol>
 * The levels are computed from the blocks on every write. All disk access runs on one background thread so
 * reads and writes of a file stay ordered.
 */
public final class RegionFiles {
	private static final Logger LOGGER = LoggerFactory.getLogger("simple-worldmap");
	private static final int MAGIC = 0x53574D50;
	private static final int VERSION = 1;
	private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.swm");

	static final ExecutorService IO = ioThread("Simple World Map IO");

	/** Colors and tints of a region; {@code tints} is null when no block is tinted. */
	public record Data(byte[] colors, int @Nullable [] tints) {
	}

	private RegionFiles() {
	}

	/** A low-priority single background thread, so the tasks it runs stay in order. */
	public static ExecutorService ioThread(String name) {
		return Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	public static Path file(Path dir, int x, int z) {
		return dir.resolve("r." + x + "." + z + ".swm");
	}

	/** Lists region coordinates present in a directory, packed with {@link #key}. */
	public static Set<Long> list(Path dir) {
		Set<Long> keys = new HashSet<>();
		if (!Files.isDirectory(dir)) {
			return keys;
		}
		try (var stream = Files.newDirectoryStream(dir, "r.*.swm")) {
			for (Path path : stream) {
				Matcher matcher = NAME.matcher(path.getFileName().toString());
				if (matcher.matches()) {
					keys.add(key(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))));
				}
			}
		} catch (IOException | NumberFormatException e) {
			LOGGER.warn("Could not list map regions in {}", dir, e);
		}
		return keys;
	}

	public static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	public static int keyX(long key) {
		return (int) (key >> 32);
	}

	public static int keyZ(long key) {
		return (int) key;
	}

	/** Returns {@code top}, with blocks it does not have filled in from {@code base}. */
	static Data overlay(@Nullable Data base, Data top) {
		if (base == null) {
			return top;
		}
		byte[] colors = base.colors().clone();
		int[] tints = base.tints() != null ? base.tints().clone() : top.tints() != null ? new int[MapRegion.AREA] : null;
		for (int i = 0; i < MapRegion.AREA; i++) {
			if (top.colors()[i] != 0) {
				colors[i] = top.colors()[i];
				if (tints != null) {
					tints[i] = top.tints() == null ? 0 : top.tints()[i];
				}
			}
		}
		return new Data(colors, tints);
	}

	/**
	 * Writes a region. A region still loading from disk holds only blocks sampled since, so its stored
	 * blocks are read back and the new samples laid over them.
	 */
	static void save(Path file, Data snapshot, boolean partial) {
		write(file, partial ? overlay(read(file), snapshot) : snapshot);
	}

	/** Returns the stored region, or null if the file is missing or unreadable. */
	static @Nullable Data read(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (DataInputStream body = openBody(file)) {
			if (body == null) {
				return null;
			}
			for (int level = RegionLod.LEVELS; level >= 1; level--) {
				body.skipNBytes(4L * RegionLod.size(level) * RegionLod.size(level));
			}
			byte[] colors = new byte[MapRegion.AREA];
			body.readFully(colors);
			if (body.readUnsignedByte() == 0) {
				return new Data(colors, null);
			}
			int[] tints = new int[MapRegion.AREA];
			readPlanes(body, tints, 16);
			return new Data(colors, tints);
		} catch (IOException e) {
			LOGGER.warn("Could not read map region {}", file, e);
			return null;
		}
	}

	/** Returns one {@link RegionLod} level of a stored region, or null if the file is missing or unreadable. */
	static int @Nullable [] readLod(Path file, int level) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (DataInputStream body = openBody(file)) {
			if (body == null) {
				return null;
			}
			for (int skipped = RegionLod.LEVELS; skipped > level; skipped--) {
				body.skipNBytes(4L * RegionLod.size(skipped) * RegionLod.size(skipped));
			}
			int[] pixels = new int[RegionLod.size(level) * RegionLod.size(level)];
			readPlanes(body, pixels, 24);
			return pixels;
		} catch (IOException e) {
			LOGGER.warn("Could not read map region {}", file, e);
			return null;
		}
	}

	/** Opens the deflate stream of a region file, or returns null if the file has another format. */
	private static @Nullable DataInputStream openBody(Path file) throws IOException {
		InputStream raw = Files.newInputStream(file);
		try {
			DataInputStream header = new DataInputStream(raw);
			if (header.readInt() != MAGIC || header.readUnsignedByte() != VERSION) {
				LOGGER.warn("Ignoring map region with unknown format: {}", file);
				raw.close();
				return null;
			}
			return new DataInputStream(new InflaterInputStream(raw));
		} catch (IOException e) {
			raw.close();
			throw e;
		}
	}

	static void write(Path file, Data data) {
		int[][] levels = RegionLod.downsample(data);
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream raw = Files.newOutputStream(temp)) {
				raw.write(new byte[] {(byte) (MAGIC >>> 24), (byte) (MAGIC >>> 16), (byte) (MAGIC >>> 8), (byte) MAGIC, VERSION});
				Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
				try (DeflaterOutputStream body = new DeflaterOutputStream(raw, deflater, 8192)) {
					for (int level = RegionLod.LEVELS; level >= 1; level--) {
						writePlanes(body, levels[level - 1], 24);
					}
					body.write(data.colors());
					int[] tints = data.tints();
					body.write(tints == null ? 0 : 1);
					if (tints != null) {
						writePlanes(body, tints, 16);
					}
				} finally {
					deflater.end();
				}
			}
			moveIntoPlace(temp, file);
		} catch (IOException e) {
			LOGGER.warn("Could not save map region {}", file, e);
		}
	}

	/** Reads byte planes from bit {@code topShift} down to bit 0, combining them into {@code values}. */
	private static void readPlanes(DataInputStream body, int[] values, int topShift) throws IOException {
		byte[] plane = new byte[values.length];
		for (int shift = topShift; shift >= 0; shift -= 8) {
			body.readFully(plane);
			for (int i = 0; i < values.length; i++) {
				values[i] |= (plane[i] & 0xFF) << shift;
			}
		}
	}

	private static void writePlanes(OutputStream body, int[] values, int topShift) throws IOException {
		byte[] plane = new byte[values.length];
		for (int shift = topShift; shift >= 0; shift -= 8) {
			for (int i = 0; i < values.length; i++) {
				plane[i] = (byte) (values[i] >> shift);
			}
			body.write(plane);
		}
	}

	public static void moveIntoPlace(Path temp, Path target) throws IOException {
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Waits for queued region writes; called when the game closes. */
	public static void shutdown() {
		shutdown(IO);
	}

	/** Stops an executor after its queued tasks finish, waiting up to 15 seconds. */
	public static void shutdown(ExecutorService executor) {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
				LOGGER.warn("Timed out while saving map data");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
