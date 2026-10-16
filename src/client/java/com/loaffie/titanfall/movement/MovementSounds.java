package com.loaffie.titanfall.movement;

import com.loaffie.titanfall.config.TitanfallConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

public final class MovementSounds {
	private static LoopSound wallRun;
	private static LoopSound slide;

	private MovementSounds() {
	}

	public static void startWallRun() {
		stopWallRun();
		play(TitanfallSounds.WALLRUN_START);
		wallRun = playLoop(TitanfallSounds.WALLRUN_LOOP);
	}

	public static void stopWallRun() {
		stop(wallRun);
		wallRun = null;
	}

	public static void startSlide() {
		stopSlide();
		play(TitanfallSounds.SLIDE_START);
		slide = playLoop(TitanfallSounds.SLIDE_LOOP);
	}

	public static void stopSlide() {
		stop(slide);
		slide = null;
	}

	public static void wallJump() {
		play(TitanfallSounds.WALLJUMP);
	}

	public static void jump() {
		play(TitanfallSounds.JUMP);
	}

	public static void doubleJump() {
		play(TitanfallSounds.DOUBLE_JUMP);
	}

	public static void land() {
		play(TitanfallSounds.LAND);
	}

	public static void clear() {
		stopWallRun();
		stopSlide();
	}

	private static void play(SoundEvent event) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null && TitanfallConfig.get().soundsEnabled) {
			client.player.playSound(event, (float) TitanfallConfig.get().movementVolume, 1.0F);
		}
	}

	private static LoopSound playLoop(SoundEvent event) {
		if (!TitanfallConfig.get().soundsEnabled) {
			return null;
		}

		LoopSound sound = new LoopSound(event, (float) TitanfallConfig.get().movementVolume);
		MinecraftClient.getInstance().getSoundManager().play(sound);
		return sound;
	}

	private static void stop(LoopSound sound) {
		if (sound != null) {
			MinecraftClient.getInstance().getSoundManager().stop(sound);
		}
	}

	private static class LoopSound extends AbstractSoundInstance {
		LoopSound(SoundEvent event, float volume) {
			super(event, SoundCategory.PLAYERS, SoundInstance.createRandom());
			this.volume = volume;
			this.relative = true;
			this.repeat = true;
		}
	}
}
