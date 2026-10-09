package io.github.riccardopll.simpleworldmap.waypoint;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import io.github.riccardopll.simpleworldmap.map.RegionFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The waypoints of one dimension of one world, stored as {@code waypoints.json}. Main thread only.
 * Reads and writes share one background thread, so a load always sees the latest save.
 */
public final class WaypointStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("simple-worldmap");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final ExecutorService IO = RegionFiles.ioThread("Simple World Map waypoints");

	private final Path file;
	private final List<Waypoint> waypoints = new ArrayList<>();

	private WaypointStore(Path file) {
		this.file = file;
	}

	public static WaypointStore load(Path dir) {
		WaypointStore store = new WaypointStore(dir.resolve("waypoints.json"));
		store.waypoints.addAll(CompletableFuture.supplyAsync(() -> read(store.file), IO).join());
		return store;
	}

	private static List<Waypoint> read(Path file) {
		List<Waypoint> result = new ArrayList<>();
		if (!Files.isRegularFile(file)) {
			return result;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Waypoint[] stored = GSON.fromJson(reader, Waypoint[].class);
			if (stored != null) {
				for (Waypoint waypoint : stored) {
					if (waypoint != null && waypoint.name() != null) {
						result.add(waypoint);
					}
				}
			}
		} catch (IOException | JsonParseException e) {
			LOGGER.warn("Could not read waypoints from {}", file, e);
		}
		return result;
	}

	/** Waits for queued waypoint writes; called when the game closes. */
	public static void shutdown() {
		RegionFiles.shutdown(IO);
	}

	public List<Waypoint> all() {
		return Collections.unmodifiableList(waypoints);
	}

	public void add(Waypoint waypoint) {
		waypoints.add(waypoint);
		save();
	}

	public void replace(Waypoint old, Waypoint updated) {
		int index = waypoints.indexOf(old);
		if (index >= 0) {
			waypoints.set(index, updated);
		} else {
			waypoints.add(updated);
		}
		save();
	}

	public void remove(Waypoint waypoint) {
		if (waypoints.remove(waypoint)) {
			save();
		}
	}

	private void save() {
		String json = GSON.toJson(waypoints.toArray(Waypoint[]::new));
		Path target = file;
		IO.execute(() -> {
			Path temp = target.resolveSibling(target.getFileName() + ".tmp");
			try {
				Files.createDirectories(target.getParent());
				Files.writeString(temp, json, StandardCharsets.UTF_8);
				RegionFiles.moveIntoPlace(temp, target);
			} catch (IOException e) {
				LOGGER.warn("Could not save waypoints to {}", target, e);
			}
		});
	}
}
