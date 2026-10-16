package com.loaffie.titanfall.client;

import com.loaffie.titanfall.config.TitanfallConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.DoubleConsumer;

public class TitanfallConfigScreen extends Screen {
	private final Screen parent;
	private final TitanfallConfig config = TitanfallConfig.get();

	public TitanfallConfigScreen(Screen parent) {
		super(Text.literal("Titanfall Movement"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int leftX = this.width / 2 - 160;
		int rightX = this.width / 2 + 10;
		int y = 40;

		slider(leftX, y, "Strafe Turn", 0.0, 1.0, config.strafeTurnFactor, value -> config.strafeTurnFactor = value);
		slider(rightX, y, "Slide Friction", 0.9, 1.0, config.slideFriction, value -> config.slideFriction = value);
		y += 24;
		slider(leftX, y, "Max Speed", 0.2, 2.0, config.maxHorizontalSpeed, value -> config.maxHorizontalSpeed = value);
		slider(rightX, y, "Slide Min Speed", 0.0, 0.5, config.slideMinSpeed, value -> config.slideMinSpeed = value);
		y += 24;
		slider(leftX, y, "Air Preserve Speed", 0.0, 0.5, config.airPreserveSpeed, value -> config.airPreserveSpeed = value);
		slider(rightX, y, "Double Jump Velocity", 0.1, 0.8, config.doubleJumpVelocity, value -> config.doubleJumpVelocity = value);
		y += 24;
		wholeSlider(leftX, y, "Wall Run Ticks", 5.0, 100.0, config.wallRunMaxTicks, value -> config.wallRunMaxTicks = (int) value);
		slider(rightX, y, "Volume", 0.0, 1.0, config.movementVolume, value -> config.movementVolume = value);
		y += 24;
		slider(leftX, y, "Wall Fall Speed", -0.1, 0.0, config.wallRunFallSpeed, value -> config.wallRunFallSpeed = value);
		addDrawableChild(ButtonWidget.builder(soundsLabel(), button -> {
			config.soundsEnabled = !config.soundsEnabled;
			button.setMessage(soundsLabel());
		}).dimensions(rightX, y, 150, 20).build());
		y += 24;
		slider(leftX, y, "Wall Jump Push", 0.0, 1.0, config.wallJumpPush, value -> config.wallJumpPush = value);
		y += 24;
		wholeSlider(leftX, y, "Wall Cooldown", 0.0, 20.0, config.wallCooldownTicks, value -> config.wallCooldownTicks = (int) value);

		addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close()).dimensions(this.width / 2 - 50, this.height - 27, 100, 20).build());
	}

	@Override
	public void close() {
		config.save();
		MinecraftClient.getInstance().setScreen(parent);
	}

	private Text soundsLabel() {
		return Text.literal("Sounds: " + (config.soundsEnabled ? "ON" : "OFF"));
	}

	private void slider(int x, int y, String label, double min, double max, double value, DoubleConsumer setter) {
		addDrawableChild(new ConfigSlider(x, y, 150, label, min, max, value, false, setter));
	}

	private void wholeSlider(int x, int y, String label, double min, double max, double value, DoubleConsumer setter) {
		addDrawableChild(new ConfigSlider(x, y, 150, label, min, max, value, true, setter));
	}

	private static class ConfigSlider extends SliderWidget {
		private final String label;
		private final double min;
		private final double max;
		private final boolean whole;
		private final DoubleConsumer setter;

		ConfigSlider(int x, int y, int width, String label, double min, double max, double value, boolean whole, DoubleConsumer setter) {
			super(x, y, width, 20, Text.literal(label), (value - min) / (max - min));
			this.label = label;
			this.min = min;
			this.max = max;
			this.whole = whole;
			this.setter = setter;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			double current = current();
			String text = whole ? Integer.toString((int) Math.round(current)) : String.format(Locale.ROOT, "%.2f", current);
			setMessage(Text.literal(label + ": " + text));
		}

		@Override
		protected void applyValue() {
			double current = current();
			setter.accept(whole ? Math.round(current) : current);
		}

		private double current() {
			return min + value * (max - min);
		}
	}
}
