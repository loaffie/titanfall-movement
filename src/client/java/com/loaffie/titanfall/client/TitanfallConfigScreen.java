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
		int y = 26;

		slider(leftX, y, "Max Speed (b/s)", 1.0, 30.0, config.maxHorizontalSpeed, value -> config.maxHorizontalSpeed = value);
		slider(rightX, y, "Air Accel", 0.0, 40.0, config.airAccelerate, value -> config.airAccelerate = value);
		y += 20;
		slider(leftX, y, "Air Speed Cap", 0.0, 10.0, config.airWishSpeedCap, value -> config.airWishSpeedCap = value);
		slider(rightX, y, "Air Gravity", 0.0, 1.5, config.airGravityScale, value -> config.airGravityScale = value);
		y += 20;
		wholeSlider(leftX, y, "Wall Run Ticks", 5.0, 100.0, config.wallRunMaxTicks, value -> config.wallRunMaxTicks = (int) value);
		slider(rightX, y, "Wall Vertical", -0.2, 0.2, config.wallRunVerticalVelocity, value -> config.wallRunVerticalVelocity = value);
		y += 20;
		slider(leftX, y, "Wall Jump Push", 0.0, 15.0, config.wallJumpPush, value -> config.wallJumpPush = value);
		slider(rightX, y, "Wall Jump Up", 0.0, 0.8, config.wallJumpUp, value -> config.wallJumpUp = value);
		y += 20;
		slider(leftX, y, "Wall Momentum", 0.5, 2.0, config.wallJumpMomentum, value -> config.wallJumpMomentum = value);
		wholeSlider(rightX, y, "Wall Cooldown", 0.0, 20.0, config.wallCooldownTicks, value -> config.wallCooldownTicks = (int) value);
		y += 20;
		slider(leftX, y, "Slide Boost", 0.0, 1.0, config.slideEntryBoost, value -> config.slideEntryBoost = value);
		slider(rightX, y, "Slide Friction", 0.0, 2.0, config.slideFriction, value -> config.slideFriction = value);
		y += 20;
		slider(leftX, y, "Slide Min Speed", 0.0, 15.0, config.slideMinSpeed, value -> config.slideMinSpeed = value);
		slider(rightX, y, "Slide Hop Mom.", 0.5, 1.5, config.slideHopMomentum, value -> config.slideHopMomentum = value);
		y += 20;
		wholeSlider(leftX, y, "Slide Cooldown", 0.0, 100.0, config.slideBoostCooldownTicks, value -> config.slideBoostCooldownTicks = (int) value);
		slider(rightX, y, "Double Jump", 0.0, 0.8, config.doubleJumpVelocity, value -> config.doubleJumpVelocity = value);
		y += 20;
		slider(leftX, y, "Volume", 0.0, 1.0, config.movementVolume, value -> config.movementVolume = value);
		addDrawableChild(ButtonWidget.builder(soundsLabel(), button -> {
			config.soundsEnabled = !config.soundsEnabled;
			button.setMessage(soundsLabel());
		}).dimensions(rightX, y, 150, 20).build());

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
