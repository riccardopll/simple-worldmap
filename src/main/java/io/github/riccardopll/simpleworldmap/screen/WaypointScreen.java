package io.github.riccardopll.simpleworldmap.screen;

import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.riccardopll.simpleworldmap.map.MapSession;
import io.github.riccardopll.simpleworldmap.waypoint.Waypoint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

/** Creates, renames, moves, recolors or deletes a waypoint. */
public final class WaypointScreen extends Screen {
	private static final int TEXT = 0xFFFFFFFF;
	private static final int LABEL = 0xFFA0A0A0;
	private static final int ERROR = 0xFFFF5555;
	private static final int VALID = 0xFFE0E0E0;
	private static final int MAX_NAME_LENGTH = 32;

	private final @Nullable Screen parent;
	private final MapSession session;
	private final @Nullable Waypoint existing;
	private final Waypoint initial;
	private int colorIndex;
	private String name;
	private String x;
	private String y;
	private String z;
	private EditBox nameBox;
	private EditBox xBox;
	private EditBox yBox;
	private EditBox zBox;

	private WaypointScreen(@Nullable Screen parent, MapSession session, @Nullable Waypoint existing, Waypoint initial) {
		super(Component.translatable(existing == null ? "simple-worldmap.waypoint.add" : "simple-worldmap.waypoint.edit"));
		this.parent = parent;
		this.session = session;
		this.existing = existing;
		this.initial = initial;
		this.name = initial.name();
		this.x = Integer.toString(initial.x());
		this.y = Integer.toString(initial.y());
		this.z = Integer.toString(initial.z());
		this.colorIndex = Math.max(0, indexOfColor(initial.color()));
	}

	public static WaypointScreen create(@Nullable Screen parent, MapSession session, BlockPos pos) {
		int count = session.waypoints.all().size();
		String name = Component.translatable("simple-worldmap.waypoint.default_name", count + 1).getString();
		Waypoint waypoint = new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), Waypoint.COLORS[count % Waypoint.COLORS.length]);
		return new WaypointScreen(parent, session, null, waypoint);
	}

	public static WaypointScreen edit(@Nullable Screen parent, MapSession session, Waypoint waypoint) {
		return new WaypointScreen(parent, session, waypoint, waypoint);
	}

	private static int indexOfColor(int color) {
		for (int i = 0; i < Waypoint.COLORS.length; i++) {
			if (Waypoint.COLORS[i] == color) {
				return i;
			}
		}
		return -1;
	}

	@Override
	protected void init() {
		int left = width / 2 - 100;
		int top = Math.max(30, height / 2 - 70);

		nameBox = addRenderableWidget(new EditBox(font, left, top + 12, 200, 20, Component.translatable("simple-worldmap.waypoint.name")));
		nameBox.setMaxLength(MAX_NAME_LENGTH);
		nameBox.setValue(name);
		nameBox.setResponder(value -> name = value);

		xBox = coordinateBox(left, top + 48, x, "X", value -> x = value);
		yBox = coordinateBox(left + 68, top + 48, y, "Y", value -> y = value);
		zBox = coordinateBox(left + 136, top + 48, z, "Z", value -> z = value);

		addRenderableWidget(Button.builder(colorLabel(), button -> {
			colorIndex = (colorIndex + 1) % Waypoint.COLORS.length;
			button.setMessage(colorLabel());
		}).bounds(left, top + 76, 200, 20).build());

		int buttonTop = top + 106;
		if (existing != null) {
			addRenderableWidget(Button.builder(Component.translatable("simple-worldmap.waypoint.save"), button -> save())
				.bounds(left, buttonTop, 64, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("simple-worldmap.waypoint.delete"), button -> delete())
				.bounds(left + 68, buttonTop, 64, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
				.bounds(left + 136, buttonTop, 64, 20).build());
		} else {
			addRenderableWidget(Button.builder(Component.translatable("simple-worldmap.waypoint.save"), button -> save())
				.bounds(left, buttonTop, 98, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
				.bounds(left + 102, buttonTop, 98, 20).build());
		}
		setInitialFocus(nameBox);
	}

	private EditBox coordinateBox(int left, int top, String value, String label, Consumer<String> responder) {
		EditBox box = addRenderableWidget(new EditBox(font, left, top, 64, 20, Component.literal(label)));
		box.setMaxLength(9);
		box.setValue(value);
		box.setResponder(text -> {
			responder.accept(text);
			box.setTextColor(parse(text) == null ? ERROR : VALID);
		});
		return box;
	}

	private MutableComponent colorLabel() {
		return Component.translatable("simple-worldmap.waypoint.color")
			.append(" ")
			.append(Component.literal("██").withColor(Waypoint.COLORS[colorIndex] & 0xFFFFFF));
	}

	private static @Nullable Integer parse(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void save() {
		Integer px = parse(x);
		Integer py = parse(y);
		Integer pz = parse(z);
		if (px == null || py == null || pz == null) {
			return;
		}
		String trimmed = name.trim();
		Waypoint updated = new Waypoint(trimmed.isEmpty() ? initial.name() : trimmed, px, py, pz, Waypoint.COLORS[colorIndex]);
		if (existing == null) {
			session.waypoints.add(updated);
		} else {
			session.waypoints.replace(existing, updated);
		}
		onClose();
	}

	private void delete() {
		if (existing != null) {
			session.waypoints.remove(existing);
		}
		onClose();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int left = width / 2 - 100;
		int top = Math.max(30, height / 2 - 70);
		graphics.centeredText(font, title, width / 2, top - 18, TEXT);
		graphics.text(font, Component.translatable("simple-worldmap.waypoint.name"), left, top, LABEL);
		graphics.text(font, "X", xBox.getX(), top + 36, LABEL);
		graphics.text(font, "Y", yBox.getX(), top + 36, LABEL);
		graphics.text(font, "Z", zBox.getX(), top + 36, LABEL);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			save();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
