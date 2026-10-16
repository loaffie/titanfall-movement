package com.loaffie.titanfall.client;

import com.loaffie.titanfall.config.TitanfallConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Config screen for the movement model. The layout adapts to the window: it prefers two columns and
 * adds a third, and squeezes the row height, when the window is too short or too narrow for that, so
 * the screen still fits at high GUI scale or in a small window instead of running off the bottom.
 */
public class TitanfallConfigScreen extends Screen {
	private static final int MAX_ROW_HEIGHT = 20;
	private static final int MIN_ROW_HEIGHT = 12;
	private static final int TOP = 22;
	private static final int GAP = 8;

	private final Screen parent;
	private final TitanfallConfig config = TitanfallConfig.get();
	private final List<Row> rows = new ArrayList<>();

	public TitanfallConfigScreen(Screen parent) {
		super(Text.literal("Titanfall Movement"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rows.clear();
		slider("Max Speed (b/s)", 1.0, 30.0, config.maxHorizontalSpeed, value -> config.maxHorizontalSpeed = value);
		slider("Air Accel", 0.0, 40.0, config.airAccelerate, value -> config.airAccelerate = value);
		slider("Air Speed Cap", 0.0, 10.0, config.airWishSpeedCap, value -> config.airWishSpeedCap = value);
		slider("Air Gravity", 0.0, 1.5, config.airGravityScale, value -> config.airGravityScale = value);
		whole("Wall Run Ticks", 5.0, 100.0, config.wallRunMaxTicks, value -> config.wallRunMaxTicks = (int) value);
		slider("Wall Vertical", -0.2, 0.2, config.wallRunVerticalVelocity, value -> config.wallRunVerticalVelocity = value);
		slider("Wall Jump Push", 0.0, 15.0, config.wallJumpPush, value -> config.wallJumpPush = value);
		slider("Wall Jump Up", 0.0, 0.8, config.wallJumpUp, value -> config.wallJumpUp = value);
		slider("Wall Momentum", 0.5, 2.0, config.wallJumpMomentum, value -> config.wallJumpMomentum = value);
		whole("Wall Cooldown", 0.0, 20.0, config.wallCooldownTicks, value -> config.wallCooldownTicks = (int) value);
		slider("Slide Boost", 0.0, 1.0, config.slideEntryBoost, value -> config.slideEntryBoost = value);
		slider("Slide Friction", 0.0, 2.0, config.slideFriction, value -> config.slideFriction = value);
		slider("Slide Min Speed", 0.0, 15.0, config.slideMinSpeed, value -> config.slideMinSpeed = value);
		slider("Slide Hop Mom.", 0.5, 1.5, config.slideHopMomentum, value -> config.slideHopMomentum = value);
		whole("Slide Cooldown", 0.0, 100.0, config.slideBoostCooldownTicks, value -> config.slideBoostCooldownTicks = (int) value);
		slider("Double Jump", 0.0, 0.8, config.doubleJumpVelocity, value -> config.doubleJumpVelocity = value);
		slider("Volume", 0.0, 1.0, config.movementVolume, value -> config.movementVolume = value);

		int gridBottom = this.height - 27 - 26;
		int columns = 2;
		while (columns < 4 && TOP + perColumn(columns) * MAX_ROW_HEIGHT > gridBottom) {
			columns++;
		}

		int perColumn = perColumn(columns);
		int rowHeight = Math.max(MIN_ROW_HEIGHT, Math.min(MAX_ROW_HEIGHT, Math.max(1, (gridBottom - TOP) / perColumn)));
		int widgetHeight = Math.max(MIN_ROW_HEIGHT, rowHeight - 2);
		int columnWidth = Math.min(170, Math.max(70, (this.width - 20) / columns - GAP));
		int gridWidth = columnWidth * columns + GAP * (columns - 1);
		int startX = Math.max(5, (this.width - gridWidth) / 2);

		for (int index = 0; index < rows.size(); index++) {
			int x = startX + (index / perColumn) * (columnWidth + GAP);
			int y = TOP + (index % perColumn) * rowHeight;
			addDrawableChild(new ConfigSlider(x, y, columnWidth, widgetHeight, rows.get(index)));
		}

		int buttonY = Math.min(this.height - 27 - 24, TOP + perColumn * rowHeight + 2);
		addDrawableChild(ButtonWidget.builder(soundsLabel(), button -> {
			config.soundsEnabled = !config.soundsEnabled;
			button.setMessage(soundsLabel());
		}).dimensions(startX, buttonY, columnWidth, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(this.width / 2 - 50, this.height - 27, 100, 20).build());
	}

	@Override
	public void close() {
		config.save();
		MinecraftClient.getInstance().setScreen(parent);
	}

	private int perColumn(int columns) {
		return (rows.size() + columns - 1) / columns;
	}

	private Text soundsLabel() {
		return Text.literal("Sounds: " + (config.soundsEnabled ? "ON" : "OFF"));
	}

	private void slider(String label, double min, double max, double value, DoubleConsumer setter) {
		rows.add(new Row(label, min, max, value, false, setter));
	}

	private void whole(String label, double min, double max, double value, DoubleConsumer setter) {
		rows.add(new Row(label, min, max, value, true, setter));
	}

	private record Row(String label, double min, double max, double value, boolean whole, DoubleConsumer setter) {
	}

	private static class ConfigSlider extends SliderWidget {
		private final Row row;

		ConfigSlider(int x, int y, int width, int height, Row row) {
			super(x, y, width, height, Text.literal(row.label()), (row.value() - row.min()) / (row.max() - row.min()));
			this.row = row;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			double current = current();
			String text = row.whole() ? Integer.toString((int) Math.round(current)) : String.format(Locale.ROOT, "%.2f", current);
			setMessage(Text.literal(row.label() + ": " + text));
		}

		@Override
		protected void applyValue() {
			double current = current();
			row.setter().accept(row.whole() ? Math.round(current) : current);
		}

		private double current() {
			return row.min() + value * (row.max() - row.min());
		}
	}
}
