package org.DJB.easyelevator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.render.Camera;
import org.slf4j.LoggerFactory;

/** Opt-in actual client startup, including transformation of the camera mixin. Never shipped. */
public class ElevatorClientSmoke implements ClientModInitializer {
    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("easyelevator.clientSmoke")) return;
        // CLIENT_STARTED can precede asynchronous texture loading; quitting there races shutdown.
        final int[] settled={0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if(client.getOverlay()!=null || client.currentScreen==null) { settled[0]=0; return; }
            ++settled[0];
            if(settled[0]==20) {
                new Camera().getPos();
                client.setScreen(new org.DJB.easyelevator.client.ElevatorScreen(
                        new org.DJB.easyelevator.network.ElevatorNetworking.OpenPanel(-1,java.util.List.of(
                                new net.minecraft.util.math.BlockPos(0,64,3),new net.minecraft.util.math.BlockPos(0,72,3),new net.minecraft.util.math.BlockPos(0,80,3)))) {
                    @Override public void tick() { } // UI fixture only; production screens still require a real cabin.
                });
            }
            if(settled[0]==30) net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(client.runDirectory,"elevator-panel.png",client.getFramebuffer(),message->{
                LoggerFactory.getLogger("EasyElevatorSmoke").info("PASS: client initialized, resources loaded, Camera mixin transformed, station panel rendered.");
                client.execute(client::scheduleStop);
            });
        });
    }
}
