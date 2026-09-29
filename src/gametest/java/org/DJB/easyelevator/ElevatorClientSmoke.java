package org.DJB.easyelevator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.render.Camera;
import org.slf4j.LoggerFactory;

/** Opt-in actual client startup, including transformation of the camera mixin. Never shipped. */
public class ElevatorClientSmoke implements ClientModInitializer {
    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("easyelevator.clientSmoke")) return;
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            new Camera().getPos();
            LoggerFactory.getLogger("EasyElevatorSmoke").info("PASS: client initialized, resources loaded, Camera mixin transformed.");
            client.scheduleStop();
        });
    }
}
