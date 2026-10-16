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
import java.nio.file.StandardCopyOption;

/**
 * Tunables for the Source-style movement model.
 *
 * <p>Every speed is in blocks per second (1 block = 1 metre), not blocks per tick, because that is
 * the unit the Source formulas work in. For scale: vanilla walking is 4.3 blocks/s, vanilla
 * sprinting 5.6, and 13.4 blocks/s is the 30 mph that Respawn quote for chained acrobatic movement.
 */
public class TitanfallConfig {
	/** Bumped whenever field meanings change, so an old file is backed up instead of misread. */
	public static final int CURRENT_VERSION = 2;

	public int configVersion = CURRENT_VERSION;

	// --- air movement (Source wish-velocity accelerator) ---
	/** Wish speed and traversal cap, blocks/s. */
	public double maxHorizontalSpeed = 13.4;
	/** Source sv_airaccelerate. The accelerator step is accel * wishSpeed * dt, clamped to the speed still addable. */
	public double airAccelerate = 12.0;
	/**
	 * The speed cap Source calls the air wish-speed limit. Strict stock Source parity is 30 units/s
	 * (0.5715 blocks/s); this default is deliberately more generous so strafing visibly builds speed.
	 * This is the main "how much does air strafing gain" knob.
	 */
	public double airWishSpeedCap = 1.2;
	/** 1.0 leaves vanilla gravity alone; lower values make the air arc floatier. */
	public double airGravityScale = 1.0;

	// --- wall running ---
	/** 35 ticks is the ~1.75 s wall run documented for Titanfall 2. */
	public int wallRunMaxTicks = 35;
	/** Net vertical speed per tick during a run, blocks/tick. 0 is a flat glide with zero gravity penalty. */
	public double wallRunVerticalVelocity = 0.0;
	/** Wall jump push away from the wall, blocks/s. */
	public double wallJumpPush = 3.0;
	/** Wall jump upward impulse, blocks/tick (0.42 is the vanilla jump). */
	public double wallJumpUp = 0.42;
	/** 1.0 preserves 100% of the wall-run speed through the kick, above 1.0 adds a boost. */
	public double wallJumpMomentum = 1.0;
	public int wallCooldownTicks = 6;

	// --- sliding ---
	/** Small start boost, as a fraction of the entry speed. */
	public double slideEntryBoost = 0.1;
	/** Source-style friction coefficient; higher scrubs speed faster. */
	public double slideFriction = 0.35;
	/** Minimum speed to start a slide, blocks/s. */
	public double slideMinSpeed = 3.0;
	/** 1.0 carries 100% of the slide's velocity into the air on a slide hop. */
	public double slideHopMomentum = 1.0;
	/** Ticks before another slide boost is allowed; 40 ticks is the 2 s timer Titanfall 2 uses. */
	public int slideBoostCooldownTicks = 40;

	// --- jumping ---
	/**
	 * The air jump's own vertical impulse, blocks/tick. It resets the vertical speed rather than adding
	 * to it, exactly like a ground jump, so a second jump at the apex of an arc gives the full amount.
	 */
	public double doubleJumpVelocity = 0.42;
	/** Total push a lurch can add, blocks/s. It follows the held direction instead of being fixed at the
	 *  moment of the jump, which is what allows a jump to be used to redirect momentum. */
	public double lurchSpeed = 2.0;
	/** Length of the lurch window in ticks; 7 ticks is the ~0.35 s window Titanfall 2 uses. */
	public int lurchTicks = 7;
	/** Whether attaching to a wall gives the air jump back. */
	public boolean airJumpOnWallRun = true;
	/** Whether crouching against a wall mid-air (a wall bump) gives the air jump back. */
	public boolean airJumpOnWallBump = true;
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
				if (config != null && config.configVersion == CURRENT_VERSION) {
					return config;
				}

				String version = config == null ? "unknown" : Integer.toString(config.configVersion);
				TitanfallMovement.LOGGER.warn("Movement config version {} does not match {}, backing it up and writing defaults", version, CURRENT_VERSION);
				Files.move(PATH, PATH.resolveSibling(PATH.getFileName() + ".v" + version + ".bak"), StandardCopyOption.REPLACE_EXISTING);
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
