package io.github.riccardopll.simpleworldmap.screen;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.riccardopll.simpleworldmap.SimpleWorldMap;
import io.github.riccardopll.simpleworldmap.map.MapRegion;
import io.github.riccardopll.simpleworldmap.map.MapSession;
import io.github.riccardopll.simpleworldmap.waypoint.Waypoint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

public final class WorldMapScreen extends Screen {
	private static final float MIN_SCALE = 0.25F;
	private static final float MAX_SCALE = 16.0F;
	private static final int MAX_UPLOADS_PER_FRAME = 2;
	private static final int BACKGROUND = 0xFF15171A;
	private static final int PANEL = 0xA0000000;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFB0B0B0;
	private static final int HOVER_RADIUS = 6;

	private static float lastScale = 2.0F;
	private static long frame;

	private final MapSession session;
	private double centerX;
	private double centerZ;
	private float scale = lastScale;
	private @Nullable Waypoint hovered;

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
		drawPlayer(graphics, partialTick);
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

	private void drawPlayer(GuiGraphicsExtractor graphics, float partialTick) {
		LocalPlayer player = minecraft.player;
		if (player == null || player.level() != session.level) {
			return;
		}
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(screenX(Mth.lerp(partialTick, player.xo, player.getX())), screenY(Mth.lerp(partialTick, player.zo, player.getZ())));
		pose.rotate((player.getViewYRot(partialTick) + 180.0F) * Mth.DEG_TO_RAD);
		drawArrow(graphics, 1, 0xFF000000);
		drawArrow(graphics, 0, 0xFFFFFFFF);
		pose.popMatrix();
	}

	/** An upward-pointing triangle centered on the origin, grown by {@code outline} pixels on each side. */
	private static void drawArrow(GuiGraphicsExtractor graphics, int outline, int color) {
		for (int row = -outline; row < 9 + outline; row++) {
			int half = Math.max(0, row) / 2 + outline;
			graphics.fill(-half - 1, row - 5, half + 1, row - 4, color);
		}
	}

	private void drawOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		LocalPlayer player = minecraft.player;
		String dimension = session.level.dimension().identifier().toString();
		graphics.fill(0, 0, width, 14, PANEL);
		graphics.text(font, title, 4, 3, TEXT);
		graphics.text(font, dimension, 8 + font.width(title), 3, MUTED);
		if (player != null) {
			BlockPos pos = player.blockPosition();
			String text = Component.translatable("simple-worldmap.map.player", pos.getX(), pos.getY(), pos.getZ()).getString();
			graphics.text(font, text, width - font.width(text) - 4, 3, TEXT);
		}

		Component hint = Component.translatable("simple-worldmap.map.hint");
		String cursor = Component.translatable("simple-worldmap.map.cursor", Mth.floor(worldX(mouseX)), Mth.floor(worldZ(mouseY))).getString();
		graphics.fill(0, height - 14, width, height, PANEL);
		graphics.text(font, cursor, 4, height - 11, TEXT);
		if (font.width(cursor) + font.width(hint) + 16 <= width) {
			graphics.text(font, hint, width - font.width(hint) - 4, height - 11, MUTED);
		}
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
		return event.button() == InputConstants.MOUSE_BUTTON_LEFT;
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
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
		double anchorX = worldX(x);
		double anchorZ = worldZ(y);
		scale = Mth.clamp(scale * (float) Math.pow(1.2, scrollY), MIN_SCALE, MAX_SCALE);
		centerX = anchorX - (x - width / 2.0) / scale;
		centerZ = anchorZ - (y - height / 2.0) / scale;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (SimpleWorldMap.openMapKey.matches(event)) {
			onClose();
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
