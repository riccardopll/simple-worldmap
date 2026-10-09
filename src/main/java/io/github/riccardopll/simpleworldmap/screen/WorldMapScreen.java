package io.github.riccardopll.simpleworldmap.screen;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.riccardopll.simpleworldmap.SimpleWorldMap;
import io.github.riccardopll.simpleworldmap.map.MapRegion;
import io.github.riccardopll.simpleworldmap.map.MapSession;
import io.github.riccardopll.simpleworldmap.waypoint.Waypoint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Matrix3x2fStack;
import org.lwjgl.sdl.SDLKeycode;
import org.jspecify.annotations.Nullable;

public final class WorldMapScreen extends Screen {
	private static final float MIN_SCALE = 0.25F;
	private static final float MAX_SCALE = 16.0F;
	private static final int MAX_UPLOADS_PER_FRAME = 2;
	private static final int BACKGROUND = 0xFF15171A;
	private static final int PANEL = 0xA0000000;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int LABEL_BACKGROUND = 0x99000000;
	private static final int HOVER_RADIUS = 6;
	private static final int HEAD_SIZE = 8;
	private static final float ZOOM_STEP = 1.2F;

	private static float lastScale = 2.0F;
	private static long frame;

	private final MapSession session;
	private double centerX;
	private double centerZ;
	private float scale = lastScale;
	private @Nullable Waypoint hovered;
	private boolean panning;

	public WorldMapScreen(MapSession session) {
		super(Component.translatable("simple-worldmap.map.title"));
		this.session = session;
		centerOnPlayer();
	}

	private void centerOnPlayer() {
		LocalPlayer player = minecraft.player;
		if (player != null) {
			centerX = player.getX();
			centerZ = player.getZ();
		}
	}

	private double worldX(double screenX) {
		return centerX + (screenX - width / 2.0) / scale;
	}

	private double worldZ(double screenY) {
		return centerZ + (screenY - height / 2.0) / scale;
	}

	private float screenX(double worldX) {
		return (float) ((worldX - centerX) * scale + width / 2.0);
	}

	private float screenY(double worldZ) {
		return (float) ((worldZ - centerZ) * scale + height / 2.0);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, BACKGROUND);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		frame++;
		drawRegions(graphics);
		drawWaypoints(graphics, mouseX, mouseY);
		drawPlayers(graphics, partialTick);
		drawOverlay(graphics, mouseX, mouseY);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		session.releaseIdleTextures(frame);
	}

	private void drawRegions(GuiGraphicsExtractor graphics) {
		int minRegionX = Mth.floor(worldX(0)) >> MapRegion.SHIFT;
		int maxRegionX = Mth.floor(worldX(width)) >> MapRegion.SHIFT;
		int minRegionZ = Mth.floor(worldZ(0)) >> MapRegion.SHIFT;
		int maxRegionZ = Mth.floor(worldZ(height)) >> MapRegion.SHIFT;
		int uploads = 0;
		Matrix3x2fStack pose = graphics.pose();
		for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
			for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
				MapRegion region = session.region(regionX, regionZ, false);
				if (region == null) {
					continue;
				}
				boolean upload = region.isLoaded() && region.needsUpload() && uploads < MAX_UPLOADS_PER_FRAME;
				if (upload) {
					uploads++;
				}
				Identifier texture = region.texture(upload, frame);
				if (texture == null) {
					continue;
				}
				pose.pushMatrix();
				pose.translate(screenX((double) regionX * MapRegion.SIZE), screenY((double) regionZ * MapRegion.SIZE));
				pose.scale(scale);
				graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0, 0, MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE);
				pose.popMatrix();
			}
		}
	}

	private void drawWaypoints(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		hovered = null;
		double closest = HOVER_RADIUS * HOVER_RADIUS;
		Matrix3x2fStack pose = graphics.pose();
		for (Waypoint waypoint : session.waypoints.all()) {
			float x = screenX(waypoint.x() + 0.5);
			float y = screenY(waypoint.z() + 0.5);
			if (x < -50 || y < -20 || x > width + 50 || y > height + 20) {
				continue;
			}
			pose.pushMatrix();
			pose.translate(x, y);
			pose.rotate(Mth.PI / 4);
			graphics.fill(-4, -4, 4, 4, 0xFF000000);
			graphics.fill(-3, -3, 3, 3, waypoint.color());
			pose.popMatrix();
			graphics.centeredText(font, waypoint.name(), Math.round(x), Math.round(y) - 15, TEXT);

			double distance = (x - mouseX) * (x - mouseX) + (y - mouseY) * (y - mouseY);
			if (distance <= closest) {
				closest = distance;
				hovered = waypoint;
			}
		}
		if (hovered != null) {
			graphics.setTooltipForNextFrame(font, Component.translatable("simple-worldmap.map.waypoint_tooltip",
				hovered.name(), hovered.x(), hovered.y(), hovered.z()), mouseX, mouseY);
		}
	}

	private void drawPlayers(GuiGraphicsExtractor graphics, float partialTick) {
		LocalPlayer self = minecraft.player;
		if (self == null || self.level() != session.level) {
			return;
		}
		for (AbstractClientPlayer other : session.level.players()) {
			if (other != self && !other.isInvisibleTo(self)) {
				drawPlayer(graphics, other, partialTick, 0xFF000000);
			}
		}
		drawPlayer(graphics, self, partialTick, 0xFFFFFFFF);
	}

	/** Draws the player's head with their name in a label below it. */
	private void drawPlayer(GuiGraphicsExtractor graphics, AbstractClientPlayer player, float partialTick, int border) {
		float x = screenX(Mth.lerp(partialTick, player.xo, player.getX()));
		float y = screenY(Mth.lerp(partialTick, player.zo, player.getZ()));
		if (x < -50 || y < -20 || x > width + 50 || y > height + 20) {
			return;
		}
		drawHead(graphics, player, x, y, border);

		Component name = player.getName();
		int left = Math.round(x) - font.width(name) / 2 - 2;
		int right = left + font.width(name) + 4;
		int top = Math.round(y) + HEAD_SIZE / 2 + 3;
		int bottom = top + 12;
		graphics.fill(left + 1, top, right - 1, bottom, LABEL_BACKGROUND);
		graphics.fill(left, top + 1, left + 1, bottom - 1, LABEL_BACKGROUND);
		graphics.fill(right - 1, top + 1, right, bottom - 1, LABEL_BACKGROUND);
		graphics.text(font, name, left + 2, top + 2, TEXT);
	}

	private static void drawHead(GuiGraphicsExtractor graphics, AbstractClientPlayer player, float x, float y, int border) {
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		graphics.fill(-HEAD_SIZE / 2 - 1, -HEAD_SIZE / 2 - 1, HEAD_SIZE / 2 + 1, HEAD_SIZE / 2 + 1, border);
		PlayerFaceExtractor.extractRenderState(graphics, player.getSkin().body().texturePath(), -HEAD_SIZE / 2, -HEAD_SIZE / 2, HEAD_SIZE,
			player.isModelPartShown(PlayerModelPart.HAT), false, -1);
		pose.popMatrix();
	}

	private void drawOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		String cursor = Component.translatable("simple-worldmap.map.cursor", Mth.floor(worldX(mouseX)), Mth.floor(worldZ(mouseY))).getString();
		graphics.fill(0, height - 14, width, height, PANEL);
		graphics.text(font, cursor, 4, height - 11, TEXT);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			if (hovered != null) {
				minecraft.gui.setScreen(WaypointScreen.edit(this, session, hovered));
			} else {
				int x = Mth.floor(worldX(event.x()));
				int z = Mth.floor(worldZ(event.y()));
				minecraft.gui.setScreen(WaypointScreen.create(this, session, new BlockPos(x, estimateY(x, z), z)));
			}
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			panning = true;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			panning = false;
		}
		return super.mouseReleased(event);
	}

	private int estimateY(int x, int z) {
		LocalPlayer player = minecraft.player;
		int fallback = player == null ? session.level.getSeaLevel() : player.blockPosition().getY();
		if (session.level.dimensionType().hasCeiling() || !session.level.hasChunk(x >> 4, z >> 4)) {
			return fallback;
		}
		return session.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		// The game can report a button as held when its release was dropped during world loading.
		if (panning && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			centerX -= dx / scale;
			centerZ -= dy / scale;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (scrollY == 0) {
			return false;
		}
		zoom(scrollY, x, y);
		return true;
	}

	/** Zooms by {@code steps} while keeping the world point under the given screen position fixed. */
	private void zoom(double steps, double x, double y) {
		double anchorX = worldX(x);
		double anchorZ = worldZ(y);
		scale = Mth.clamp(scale * (float) Math.pow(ZOOM_STEP, steps), MIN_SCALE, MAX_SCALE);
		centerX = anchorX - (x - width / 2.0) / scale;
		centerZ = anchorZ - (y - height / 2.0) / scale;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (SimpleWorldMap.openMapKey.matches(event)) {
			onClose();
			return true;
		}
		int keycode = event.keycode();
		if (keycode == SDLKeycode.SDLK_PLUS || keycode == SDLKeycode.SDLK_EQUALS || keycode == SDLKeycode.SDLK_KP_PLUS) {
			zoom(1, width / 2.0, height / 2.0);
			return true;
		}
		if (keycode == SDLKeycode.SDLK_MINUS || keycode == SDLKeycode.SDLK_KP_MINUS) {
			zoom(-1, width / 2.0, height / 2.0);
			return true;
		}
		if (event.key() == InputConstants.KEY_SPACE) {
			centerOnPlayer();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void tick() {
		if (minecraft.level != session.level) {
			onClose();
		}
	}

	@Override
	public void removed() {
		lastScale = scale;
		session.releaseTextures();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
