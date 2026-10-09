package io.github.riccardopll.simpleworldmap.test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.riccardopll.simpleworldmap.SimpleWorldMap;
import io.github.riccardopll.simpleworldmap.map.MapSession;
import io.github.riccardopll.simpleworldmap.map.RegionFiles;
import io.github.riccardopll.simpleworldmap.map.RegionLod;
import io.github.riccardopll.simpleworldmap.screen.WaypointScreen;
import io.github.riccardopll.simpleworldmap.screen.WorldMapScreen;
import io.github.riccardopll.simpleworldmap.waypoint.Waypoint;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

public final class SimpleWorldMapClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		TestInput input = context.getInput();
		Path mapRoot = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("simple-worldmap"));
		deleteRecursively(mapRoot);
		context.runOnClient(mc -> checkLowDetailFiles(mapRoot.resolve("lod-test")));

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			world.getConnection().waitForChunksRender();
			context.waitTicks(40);

			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(WorldMapScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("swm-overworld-map");

			input.scroll(-4);
			context.waitTicks(5);
			context.takeScreenshot("swm-overworld-zoomed-out");
			input.scroll(8);
			context.waitTicks(5);
			context.takeScreenshot("swm-overworld-zoomed-in");
			input.scroll(-4);

			double[] center = context.computeOnClient(mc -> new double[] {
				mc.getWindow().getScreenWidth() / 2.0 + 40, mc.getWindow().getScreenHeight() / 2.0 + 30});
			input.setCursorPos(center[0], center[1]);
			context.waitTicks(2);
			input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitForScreen(WaypointScreen.class);
			input.typeChars(" home");
			context.takeScreenshot("swm-add-waypoint");
			context.clickScreenButton("simple-worldmap.waypoint.save");
			context.waitForScreen(WorldMapScreen.class);
			List<Waypoint> waypoints = waypoints(context);
			check(waypoints.size() == 1 && waypoints.getFirst().name().equals("Waypoint 1 home"), "waypoint added from map: " + waypoints);

			input.setCursorPos(center[0], center[1]);
			context.waitTicks(2);
			context.takeScreenshot("swm-waypoint-on-map");
			input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitForScreen(WaypointScreen.class);
			context.takeScreenshot("swm-edit-waypoint");
			int[] size = context.computeOnClient(mc -> new int[] {mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight()});
			input.resizeWindow(size[0] + 200, size[1] + 100);
			context.waitTicks(2);
			context.takeScreenshot("swm-edit-waypoint-resized");
			check(context.computeOnClient(mc -> mc.gui.screen().width == mc.getWindow().getGuiScaledWidth()), "dialog resized");
			input.resizeWindow(size[0], size[1]);
			context.waitTicks(2);
			context.clickScreenButton("simple-worldmap.waypoint.delete");
			context.waitForScreen(WorldMapScreen.class);
			check(waypoints(context).isEmpty(), "waypoint deleted");

			input.setCursorPos(center[0], center[1]);
			context.waitTicks(2);
			input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitForScreen(WaypointScreen.class);
			input.pressKey(InputConstants.KEY_RETURN);
			context.waitForScreen(WorldMapScreen.class);
			check(waypoints(context).size() == 1, "waypoint added with Enter");
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(null);

			world.getServer().runCommand("execute as @p at @s run fillbiome ~-32 -64 ~-32 ~31 -57 ~31 minecraft:savanna");
			context.waitTicks(60);
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(WorldMapScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("swm-savanna-patch");
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(null);

			respawnAfterKill(context, world);
			List<Waypoint> afterDeath = waypoints(context);
			check(afterDeath.size() == 2 && afterDeath.getLast().name().equals("Death"), "death waypoint added: " + afterDeath);

			world.getServer().runCommand("gamemode creative @a");
			world.getServer().runCommand("execute in minecraft:the_nether run tp @a 0 64 0");
			context.waitFor(mc -> mc.level != null && mc.level.dimension().identifier().getPath().equals("the_nether"));
			world.getConnection().waitForChunksRender();
			context.waitTicks(40);
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(WorldMapScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("swm-nether-map");
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(null);

			respawnAfterKill(context, world);
			check(context.computeOnClient(mc -> mc.level.dimension().identifier().getPath()).equals("overworld"), "respawned in the overworld");
			check(waypoints(context).size() == 2, "nether death not added to overworld waypoints");
		}

		context.waitTicks(20);
		Path overworld = singleDir(mapRoot.resolve("singleplayer")).resolve("minecraft").resolve("overworld");
		check(countRegions(overworld) > 0, "overworld region files written in " + overworld);
		checkLowDetailFilesWritten(overworld);
		check(Files.isRegularFile(overworld.resolve("waypoints.json")), "waypoints saved");
		check(countRegions(overworld.resolveSibling("the_nether")) > 0, "nether region files written");
		check(readString(overworld.resolveSibling("the_nether").resolve("waypoints.json")).contains("\"Death\""), "nether death waypoint saved");

		try (TestDedicatedServerContext server = context.worldBuilder().createServer();
			TestDedicatedServerConnection connection = server.connect()) {
			connection.waitForChunksRender();
			context.waitTicks(40);
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(WorldMapScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("swm-dedicated-server-map");
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(null);
		}

		Path[] terrain = new Path[1];
		try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false).create()) {
			world.getServer().runCommand("gamemode creative @a");
			world.getConnection().waitForChunksRender();
			context.waitTicks(60);
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(WorldMapScreen.class);
			context.waitTicks(5);
			context.takeScreenshot("swm-terrain-map");
			input.scroll(3);
			context.waitTicks(5);
			context.takeScreenshot("swm-terrain-map-zoomed");
			input.setCursorPos(200, 200);
			input.holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTick();
			for (int i = 0; i < 10; i++) {
				input.moveCursor(20, 10);
				context.waitTick();
			}
			input.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
			context.waitTicks(5);
			context.takeScreenshot("swm-terrain-map-dragged");

			input.pressKey(InputConstants.KEY_SPACE);
			float before = mapScale(context);
			for (int i = 0; i < 4; i++) {
				input.pressKey(SimpleWorldMap.zoomOutKey);
			}
			context.waitTicks(5);
			float after = mapScale(context);
			check(after < before * 0.5F, "zoom out key: " + before + " -> " + after);
			context.takeScreenshot("swm-terrain-map-key-zoomed-out");
			for (int i = 0; i < 6; i++) {
				input.pressKey(SimpleWorldMap.zoomInKey);
			}
			check(mapScale(context) > after * 2, "zoom in key");

			context.runOnClient(mc -> {
				RemotePlayer other = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), "Steve"));
				other.setId(Integer.MAX_VALUE - 1);
				other.snapTo(mc.player.getX() + 12, mc.player.getY(), mc.player.getZ() + 6, 0, 0);
				mc.level.addEntity(other);
			});
			context.waitTicks(5);
			context.takeScreenshot("swm-terrain-map-other-player");
			check(context.computeOnClient(mc -> mc.level.players().size()) == 2, "remote player present in client level");

			for (int i = 0; i < 30; i++) {
				input.pressKey(SimpleWorldMap.zoomOutKey);
			}
			context.waitTicks(10);
			float minScale = staticFloat(WorldMapScreen.class, "MIN_SCALE");
			check(mapScale(context) == minScale, "zoomed out to the minimum scale " + minScale);
			context.takeScreenshot("swm-terrain-map-min-zoom");
			for (int i = 0; i < 10; i++) {
				input.pressKey(SimpleWorldMap.zoomInKey);
			}
			context.waitTicks(10);
			check(detailLevel(context) == 1, "first low-detail level at scale " + mapScale(context));
			context.takeScreenshot("swm-terrain-map-low-detail");
			for (int i = 0; i < 10; i++) {
				input.pressKey(SimpleWorldMap.zoomOutKey);
			}

			terrain[0] = context.computeOnClient(mc -> MapSession.directory(mc, mc.level.dimension()));
			int[] home = context.computeOnClient(mc -> new int[] {mc.player.getBlockX() >> 9, mc.player.getBlockZ() >> 9});
			List<Long> explored = exploreSynthetic(context, terrain[0], home);
			context.waitTicks(40);
			context.takeScreenshot("swm-terrain-map-min-zoom-explored");
			check(context.computeOnClient(mc -> {
				MapSession session = SimpleWorldMap.session(mc.level);
				int drawn = 0;
				for (long key : explored) {
					int x = RegionFiles.keyX(key);
					int z = RegionFiles.keyZ(key);
					RegionLod lod = session.cachedLod(x, z, RegionLod.LEVELS);
					drawn += session.cachedRegion(x, z) == null && lod != null && field(lod, "textureId") != null ? 1 : 0;
				}
				return drawn;
			}) == explored.size(), explored.size() + " stored regions drawn from low-detail files without loading full detail");
			System.out.println("[simple-worldmap test] textures at minimum zoom: " + textureReport(context));
			world.getServer().runCommand("execute as @p at @s run tp @s ~3000 200 ~");
			context.waitFor(mc -> (mc.player.getBlockX() >> 9) - home[0] > 4);
			world.getConnection().waitForChunksRender();
			context.waitTicks(60);
			input.pressKey(InputConstants.KEY_SPACE);
			context.waitTicks(150);
			context.takeScreenshot("swm-terrain-map-min-zoom-far");
			check(context.computeOnClient(mc -> SimpleWorldMap.session(mc.level).cachedRegion(home[0], home[1])) == null,
				"full detail of the first area released at low-detail zoom");

			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(null);
			context.waitTicks(20);
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(WorldMapScreen.class);
			context.waitTicks(20);
			context.takeScreenshot("swm-terrain-map-min-zoom-reopened");
			check(context.computeOnClient(mc -> {
				MapSession session = SimpleWorldMap.session(mc.level);
				RegionLod lod = session.cachedLod(home[0], home[1], RegionLod.LEVELS);
				return session.cachedRegion(home[0], home[1]) == null && lod != null && field(lod, "textureId") != null;
			}), "first area drawn from its low-detail file without loading full detail");
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(null);
			world.getServer().runCommand("execute as @p at @s run tp @s ~-3000 200 ~");
			context.waitFor(mc -> mc.player.getBlockX() >> 9 == home[0]);
			world.getConnection().waitForChunksRender();
			context.waitTicks(20);
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(WorldMapScreen.class);
			context.waitTicks(20);
			context.takeScreenshot("swm-terrain-map-min-zoom-back");
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(null);
		}

		context.waitTicks(20);
		checkLowDetailFilesWritten(terrain[0]);

		context.waitTicks(20);
		Path server = singleDir(mapRoot.resolve("multiplayer")).resolve("minecraft").resolve("overworld");
		check(countRegions(server) > 0, "server region files written in " + server);

		context.runOnClient(mc -> mc.gui.setScreen(new KeyBindsScreen(null, mc.options)));
		context.waitForScreen(KeyBindsScreen.class);
		input.setCursorPos(100, 100);
		input.scroll(-1000);
		context.waitTicks(5);
		context.takeScreenshot("swm-key-binds");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
	}

	private static List<Waypoint> waypoints(ClientGameTestContext context) {
		return context.computeOnClient(mc -> List.copyOf(SimpleWorldMap.session(mc.level).waypoints.all()));
	}

	private static void respawnAfterKill(ClientGameTestContext context, TestSingleplayerContext world) {
		world.getServer().runCommand("kill @a");
		context.waitForScreen(DeathScreen.class);
		context.waitTicks(30);
		context.clickScreenButton("deathScreen.respawn");
		context.waitForScreen(null);
		world.getConnection().waitForChunksRender();
		context.waitTicks(5);
	}

	private static String readString(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new AssertionError("cannot read " + file, e);
		}
	}

	private static float mapScale(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			try {
				Field scale = WorldMapScreen.class.getDeclaredField("scale");
				scale.setAccessible(true);
				return scale.getFloat(mc.gui.screen());
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	/**
	 * Saves a partly loaded region over a stored one and checks that its low-detail image has the blocks of both,
	 * then checks that a missing or outdated low-detail file is rebuilt from the region.
	 */
	private static void checkLowDetailFiles(Path dir) {
		Class<?>[] save = {Path.class, int.class, int.class, RegionFiles.Data.class, boolean.class};
		Class<?>[] readLod = {Path.class, int.class, int.class, int.class};
		try {
			byte[] west = new byte[512 * 512];
			byte[] east = new byte[512 * 512];
			for (int i = 0; i < west.length; i++) {
				((i & 511) < 256 ? west : east)[i] = MapColor.GRASS.getPackedId(MapColor.Brightness.NORMAL);
			}
			invoke("save", save, dir, 0, 0, new RegionFiles.Data(west, null), false);
			invoke("save", save, dir, 0, 0, new RegionFiles.Data(east, null), true);
			int[] level1 = (int[]) invoke("readLod", readLod, dir, 0, 0, 1);
			check(ARGB.alpha(level1[0]) == 255 && ARGB.alpha(level1[127]) == 255 && level1[0] == level1[127],
				"low-detail file of a partly loaded region has stored and new blocks");
			Files.delete(RegionFiles.lodFile(dir, 0, 0));
			int[] level2 = (int[]) invoke("readLod", readLod, dir, 0, 0, 2);
			check(level2.length == 32 * 32 && ARGB.alpha(level2[0]) == 255 && ARGB.alpha(level2[31]) == 255
				&& Files.isRegularFile(RegionFiles.lodFile(dir, 0, 0)), "missing low-detail file rebuilt");
			invoke("write", new Class<?>[] {Path.class, RegionFiles.Data.class}, RegionFiles.file(dir, 0, 0), new RegionFiles.Data(east, null));
			Files.setLastModifiedTime(RegionFiles.lodFile(dir, 0, 0), FileTime.fromMillis(0));
			level1 = (int[]) invoke("readLod", readLod, dir, 0, 0, 1);
			check(level1[0] == 0 && ARGB.alpha(level1[127]) == 255, "low-detail file older than its region rebuilt");
			deleteRecursively(dir);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void checkLowDetailFilesWritten(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			List<String> missing = files.map(path -> path.getFileName().toString())
				.filter(name -> name.endsWith(".swm") && !Files.isRegularFile(dir.resolve(name.replace(".swm", ".swl"))))
				.toList();
			check(countRegions(dir) > 0 && missing.isEmpty(), "low-detail files written in " + dir + ", missing for " + missing);
		} catch (IOException e) {
			throw new AssertionError("cannot list " + dir, e);
		}
	}

	/**
	 * Stores generated regions west of {@code home}, as if explored in an earlier session, and returns their keys.
	 * Each region leaves some chunks unexplored.
	 */
	private static List<Long> exploreSynthetic(ClientGameTestContext context, Path dir, int[] home) {
		MapColor[] palette = {MapColor.WATER, MapColor.SAND, MapColor.GRASS, MapColor.PLANT, MapColor.STONE, MapColor.SNOW};
		List<Long> keys = new ArrayList<>();
		for (int regionZ = home[1] - 6; regionZ <= home[1] + 5; regionZ++) {
			for (int regionX = home[0] - 12; regionX <= home[0] - 3; regionX++) {
				byte[] colors = new byte[512 * 512];
				for (int i = 0; i < colors.length; i++) {
					double x = regionX * 512 + (i & 511);
					double z = regionZ * 512 + (i >> 9);
					if (((int) Math.floor(x / 16) * 31 + (int) Math.floor(z / 16) * 17) % 23 == 0) {
						continue;
					}
					double height = Math.sin(x / 700) + Math.cos(z / 500) + 0.5 * Math.sin((x + z) / 230);
					int band = Math.clamp((int) Math.floor((height + 1.3) * 1.6), 0, palette.length - 1);
					colors[i] = palette[band].getPackedId(MapColor.Brightness.byId(Math.floorMod((int) (height * 40), 3)));
				}
				invoke("save", new Class<?>[] {Path.class, int.class, int.class, RegionFiles.Data.class, boolean.class},
					dir, regionX, regionZ, new RegionFiles.Data(colors, null), false);
				keys.add(RegionFiles.key(regionX, regionZ));
			}
		}
		context.runOnClient(mc -> {
			@SuppressWarnings("unchecked")
			Set<Long> onDisk = (Set<Long>) field(SimpleWorldMap.session(mc.level), "onDisk");
			onDisk.addAll(keys);
		});
		return keys;
	}

	/** Counts uploaded textures by detail level and their size in pixel memory. */
	private static String textureReport(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			MapSession session = SimpleWorldMap.session(mc.level);
			StringBuilder report = new StringBuilder();
			long bytes = 0;
			@SuppressWarnings("unchecked")
			Map<Long, Object> regions = (Map<Long, Object>) field(session, "regions");
			long full = regions.values().stream().filter(region -> field(region, "texture") != null).count();
			report.append(regions.size()).append(" full regions in memory, ").append(full).append(" full textures");
			bytes += full * 512 * 512 * 4;
			@SuppressWarnings("unchecked")
			List<Map<Long, RegionLod>> lods = (List<Map<Long, RegionLod>>) field(session, "lods");
			for (int level = 1; level <= lods.size(); level++) {
				long count = lods.get(level - 1).values().stream().filter(lod -> field(lod, "textureId") != null).count();
				report.append(", ").append(count).append(" level ").append(level).append(" textures");
				bytes += count * RegionLod.size(level) * RegionLod.size(level) * 4;
			}
			return report.append(", ").append(bytes / 1024).append(" KiB of texture pixels").toString();
		});
	}

	private static int detailLevel(ClientGameTestContext context) {
		return context.computeOnClient(mc -> {
			try {
				Method level = WorldMapScreen.class.getDeclaredMethod("detailLevel");
				level.setAccessible(true);
				return (int) level.invoke(mc.gui.screen());
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
	}

	private static Object invoke(String name, Class<?>[] types, Object... args) {
		try {
			Method method = RegionFiles.class.getDeclaredMethod(name, types);
			method.setAccessible(true);
			return method.invoke(null, args);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static @Nullable Object field(Object owner, String name) {
		try {
			Field field = owner.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(owner);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static float staticFloat(Class<?> owner, String name) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field.getFloat(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static Path singleDir(Path parent) {
		try (Stream<Path> children = Files.list(parent)) {
			List<Path> dirs = children.filter(Files::isDirectory).toList();
			check(dirs.size() == 1, "expected one world folder in " + parent + ": " + dirs);
			return dirs.getFirst();
		} catch (IOException e) {
			throw new AssertionError("cannot list " + parent, e);
		}
	}

	private static void deleteRecursively(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		} catch (IOException e) {
			throw new AssertionError("cannot clear " + root, e);
		}
	}

	private static long countRegions(Path dir) {
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".swm")).count();
		} catch (IOException e) {
			return 0;
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
		System.out.println("[simple-worldmap test] ok: " + message);
	}
}
