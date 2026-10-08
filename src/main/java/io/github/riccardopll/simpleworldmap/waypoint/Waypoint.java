package io.github.riccardopll.simpleworldmap.waypoint;

/** A named position in one dimension. {@code color} is opaque ARGB. */
public record Waypoint(String name, int x, int y, int z, int color) {
	public static final int[] COLORS = {
		0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFFFFFFF
	};
}
