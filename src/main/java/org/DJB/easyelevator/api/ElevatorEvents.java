package org.DJB.easyelevator.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import org.DJB.easyelevator.entity.CabinEntity;
import org.DJB.easyelevator.logic.ElevatorController;

/** Server hooks for integrations. Client animations should read tracked phase and door progress. */
public final class ElevatorEvents {
    private ElevatorEvents() { }
    public interface PhaseChanged { void onChange(CabinEntity cabin, ElevatorController.Phase before, ElevatorController.Phase after); }
    public interface Arrived { void onArrival(CabinEntity cabin, int floorY); }
    public static final Event<PhaseChanged> PHASE_CHANGED = EventFactory.createArrayBacked(PhaseChanged.class,
            callbacks -> (cabin, before, after) -> { for (var c : callbacks) c.onChange(cabin, before, after); });
    public static final Event<Arrived> ARRIVED = EventFactory.createArrayBacked(Arrived.class,
            callbacks -> (cabin, floorY) -> { for (var c : callbacks) c.onArrival(cabin, floorY); });
}
