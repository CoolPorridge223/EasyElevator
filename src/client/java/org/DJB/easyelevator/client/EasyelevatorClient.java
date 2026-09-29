package org.DJB.easyelevator.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import org.DJB.easyelevator.Easyelevator;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;
import org.DJB.easyelevator.network.ElevatorNetworking;
import java.util.HashMap;
import java.util.Map;

public class EasyelevatorClient implements ClientModInitializer {
    private final Map<Integer,CabinRunningSound> sounds=new HashMap<>();

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(Easyelevator.CABIN,CabinRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.MotionFrame.ID,(payload,context)->
                context.client().execute(()->CabinMotion.receive(payload)));
        ClientPlayNetworking.registerGlobalReceiver(ElevatorNetworking.OpenPanel.ID,(payload,context)->
                context.client().execute(()->context.client().setScreen(new ElevatorScreen(payload))));
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            sounds.entrySet().removeIf(entry->{
                var e=client.world==null?null:client.world.getEntityById(entry.getKey());
                if(!(e instanceof CabinEntity cabin) || cabin.isRemoved() || cabin.phase()!=ElevatorController.Phase.MOVING) {
                    client.getSoundManager().stop(entry.getValue());return true;
                }
                return false;
            });
            if(client.world==null) return;
            for(var e:client.world.getEntities()) if(e instanceof CabinEntity cabin && cabin.phase()==ElevatorController.Phase.MOVING)
                sounds.computeIfAbsent(cabin.getId(),id->{var sound=new CabinRunningSound(cabin);client.getSoundManager().play(sound);return sound;});
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{sounds.values().forEach(client.getSoundManager()::stop);sounds.clear();CabinMotion.clear();});
    }
}
