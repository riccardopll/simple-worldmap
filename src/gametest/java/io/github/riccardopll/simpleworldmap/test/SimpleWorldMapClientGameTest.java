package io.github.riccardopll.simpleworldmap.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.riccardopll.simpleworldmap.SimpleWorldMap;
import io.github.riccardopll.simpleworldmap.screen.WaypointScreen;
import io.github.riccardopll.simpleworldmap.screen.WorldMapScreen;
import io.github.riccardopll.simpleworldmap.waypoint.Waypoint;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.RemotePlayer;
import org.lwjgl.sdl.SDLScancode;

public final class SimpleWorldMapClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		TestInput input = context.getInput();
		Path mapRoot = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("simple-worldmap"));
		deleteRecursively(mapRoot);

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
		}

		context.waitTicks(20);
		Path overworld = singleDir(mapRoot.resolve("singleplayer")).resolve("minecraft").resolve("overworld");
		check(countRegions(overworld) > 0, "overworld region files written in " + overworld);
		check(Files.isRegularFile(overworld.resolve("waypoints.json")), "waypoints saved");
		check(countRegions(overworld.resolveSibling("the_nether")) > 0, "nether region files written");

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
			for (int i = 0; i < 4; i++) {
				input.pressKey(SDLScancode.SDL_SCANCODE_KP_MINUS);
			}
			context.waitTicks(5);
			context.takeScreenshot("swm-terrain-map-key-zoomed-out");
			for (int i = 0; i < 6; i++) {
				input.pressKey(SDLScancode.SDL_SCANCODE_KP_PLUS);
			}

			context.runOnClient(mc -> {
				RemotePlayer other = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), "Steve"));
				other.setId(Integer.MAX_VALUE - 1);
				other.snapTo(mc.player.getX() + 12, mc.player.getY(), mc.player.getZ() + 6, 0, 0);
				mc.level.addEntity(other);
			});
			context.waitTicks(5);
			context.takeScreenshot("swm-terrain-map-other-player");
			check(context.computeOnClient(mc -> mc.level.players().size()) == 2, "remote player present in client level");
			input.pressKey(SimpleWorldMap.openMapKey);
			context.waitForScreen(null);
		}

		context.waitTicks(20);
		Path server = singleDir(mapRoot.resolve("multiplayer")).resolve("minecraft").resolve("overworld");
		check(countRegions(server) > 0, "server region files written in " + server);
	}

	private static List<Waypoint> waypoints(ClientGameTestContext context) {
		return context.computeOnClient(mc -> List.copyOf(SimpleWorldMap.session(mc.level).waypoints.all()));
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
