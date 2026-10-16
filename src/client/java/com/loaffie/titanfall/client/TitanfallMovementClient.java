package com.loaffie.titanfall.client;

import com.loaffie.titanfall.movement.TitanfallController;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class TitanfallMovementClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(TitanfallController::tick);
	}
}
