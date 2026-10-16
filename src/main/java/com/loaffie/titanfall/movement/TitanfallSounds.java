package com.loaffie.titanfall.movement;

import com.loaffie.titanfall.TitanfallMovement;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class TitanfallSounds {
	public static final SoundEvent WALLRUN_START = register("wallrun_start");
	public static final SoundEvent WALLRUN_LOOP = register("wallrun_loop");
	public static final SoundEvent WALLJUMP = register("walljump");
	public static final SoundEvent SLIDE_START = register("slide_start");
	public static final SoundEvent SLIDE_LOOP = register("slide_loop");
	public static final SoundEvent JUMP = register("jump");
	public static final SoundEvent DOUBLE_JUMP = register("double_jump");
	public static final SoundEvent LAND = register("land");

	private TitanfallSounds() {
	}

	private static SoundEvent register(String name) {
		Identifier id = Identifier.of(TitanfallMovement.MOD_ID, name);
		return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
	}

	public static void register() {
	}
}
