package org.DJB.easyelevator.logic;

/** Local cabin lamp shading. Only changes mesh lightmap coordinates, never world light. */
public final class CabinLighting {
    public static final int LAMP_LEVEL = 15;
    private static final double LAMP_X = 0, LAMP_Y = 2.78, LAMP_Z = -.25;

    private CabinLighting() { }

    /** Sample at a face centre in cabin coordinates, before applying cabin rotation. */
    public static int surface(int worldLight, float x, float y, float z,
                              float nx, float ny, float nz) {
        // Exclude the base underside, roof exterior, outer walls and guide shoes.
        // The observation frame extends to 1.41; the solid outer shell is at 1.5.
        if (Math.abs(x) > 1.42 || y < .199 || y > 2.802 || z < -1.42
                || z > ElevatorParameters.CABIN_DOOR_BACK_Z + .003) return worldLight;
        double dx = LAMP_X - x, dy = LAMP_Y - y, dz = LAMP_Z - z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double facing = (dx * nx + dy * ny + dz * nz) / Math.max(distance, .001);
        // Back faces, including the outside of the doors, receive no cabin light.
        if (facing <= 0) return worldLight;
        int level = (int) Math.floor(LAMP_LEVEL - 1.4 * distance - 2 * (1 - facing));
        return withBlockLight(worldLight, Math.max(0, Math.min(13, level)));
    }

    /** Only the downward lamp diffuser emits; its top and edges keep ambient lighting. */
    public static int lamp(int worldLight, float x, float y, float z,
                           float nx, float ny, float nz) {
        return ny < -.5f ? withBlockLight(worldLight, LAMP_LEVEL) : worldLight;
    }

    private static int withBlockLight(int worldLight, int level) {
        // Preserve sky light and any extended bits; only raise the four block-light bits.
        return (worldLight & ~0xF0) | Math.max((worldLight >> 4) & 15, level) << 4;
    }
}
