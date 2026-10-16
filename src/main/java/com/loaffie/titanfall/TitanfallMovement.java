package com.loaffie.titanfall;

import com.loaffie.titanfall.movement.TitanfallSounds;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TitanfallMovement implements ModInitializer {
	public static final String MOD_ID = "titanfall_movement";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		TitanfallSounds.register();
		LOGGER.info("Initializing Titanfall Movement");
	}
}
