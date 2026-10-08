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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Region file format: 4-byte magic, 1-byte version, then 512*512 color bytes compressed with deflate.
 * All disk access runs on one background thread so reads and writes of a file stay ordered.
 */
public final class RegionFiles {
	private static final Logger LOGGER = LoggerFactory.getLogger("simple-worldmap");
	private static final int MAGIC = 0x53574D50;
	private static final int VERSION = 1;
	private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.swm");

	public static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Simple World Map IO");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	private RegionFiles() {
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

	/** Returns the stored colors, or null if the file is missing or unreadable. */
	static byte[] read(Path file) {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (InputStream raw = Files.newInputStream(file);
			DataInputStream header = new DataInputStream(raw)) {
			if (header.readInt() != MAGIC || header.readUnsignedByte() != VERSION) {
				LOGGER.warn("Ignoring map region with unknown format: {}", file);
				return null;
			}
			try (DataInputStream body = new DataInputStream(new InflaterInputStream(raw))) {
				byte[] colors = new byte[MapRegion.AREA];
				body.readFully(colors);
				return colors;
			}
		} catch (IOException e) {
			LOGGER.warn("Could not read map region {}", file, e);
			return null;
		}
	}

	static void write(Path file, byte[] colors) {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			try (OutputStream raw = Files.newOutputStream(temp)) {
				raw.write(new byte[] {(byte) (MAGIC >>> 24), (byte) (MAGIC >>> 16), (byte) (MAGIC >>> 8), (byte) MAGIC, VERSION});
				Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
				try (DeflaterOutputStream body = new DeflaterOutputStream(raw, deflater, 8192)) {
					body.write(colors);
				} finally {
					deflater.end();
				}
			}
			moveIntoPlace(temp, file);
		} catch (IOException e) {
			LOGGER.warn("Could not save map region {}", file, e);
		}
	}

	public static void moveIntoPlace(Path temp, Path target) throws IOException {
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Waits for queued writes; called when the game closes. */
	public static void shutdown() {
		IO.shutdown();
		try {
			if (!IO.awaitTermination(15, TimeUnit.SECONDS)) {
				LOGGER.warn("Timed out while saving map regions");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
