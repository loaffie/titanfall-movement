package com.loaffie.titanfall.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.loaffie.titanfall.TitanfallMovement;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class TitanfallConfig {
	public double strafeTurnFactor = 0.4;
	public double maxHorizontalSpeed = 1.0;
	public double airPreserveSpeed = 0.15;
	public int wallRunMaxTicks = 40;
	public double wallRunFallSpeed = -0.015;
	public double wallJumpPush = 0.5;
	public int wallCooldownTicks = 6;
	public double slideFriction = 0.99;
	public double slideMinSpeed = 0.15;
	public double doubleJumpVelocity = 0.42;
	public boolean soundsEnabled = true;
	public double movementVolume = 1.0;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("titanfall_movement.json");
	private static TitanfallConfig instance;

	public static TitanfallConfig get() {
		if (instance == null) {
			instance = load();
		}

		return instance;
	}

	private static TitanfallConfig load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				TitanfallConfig config = GSON.fromJson(reader, TitanfallConfig.class);
				if (config != null) {
					return config;
				}
			} catch (IOException exception) {
				TitanfallMovement.LOGGER.error("Failed to read config", exception);
			}
		}

		TitanfallConfig config = new TitanfallConfig();
		config.save();
		return config;
	}

	public void save() {
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(this, writer);
		} catch (IOException exception) {
			TitanfallMovement.LOGGER.error("Failed to write config", exception);
		}
	}
}
