package org.DJB.easyelevator.logic;

/**
 * 轿厢内灯的<b>局部光照换算</b>：纯函数地改写网格顶点的打包光照值（lightmap 坐标），
 * 让舱内出现"顶灯照亮四壁"的效果，而<b>不改世界光照</b>、不写存档、不发包。
 *
 * <p>为什么需要它：轿厢是实体，它的内饰亮度只能来自所在方块位置的环境光——灯罩贴图再亮也不会照亮舱壁；
 * 而真的放一个光源方块会在井道里漏光、还会影响别的模组与红石判定。折中办法是渲染时按"面中心到灯的距离 +
 * 面法线朝向"把方块光那 4 位抬高，纯视觉、零副作用。
 *
 * <p>在整体架构中的位置：logic 包中最纯粹的几何/表现计算之一（与 {@code ElevatorParameters} 一样不引用
 * 任何 Minecraft 类）；唯一调用方是 {@link org.DJB.easyelevator.client.CabinRenderer}，它把 {@link #surface}
 * 与 {@link #lamp} 作为 {@code BoxMesh.FaceLighting} 逐面传下去（见 {@code BoxMesh.cuboid} 的 lighting 参数）。
 *
 * <p>关键不变量/约束：
 * <ul>
 *   <li>传入坐标是<b>轿厢本地坐标</b>（单位：格），在施加轿厢旋转/平移<b>之前</b>采样——灯位因此是一个局部常量，
 *       轿厢朝哪个方向都不影响结果。</li>
 *   <li>只抬不降：返回值中的方块光分量取"世界值"与"补光值"的较大者，天光与更高位原样保留，
 *       因此夜间的轿厢仍是"世界暗 + 舱内亮"，不会把白天压暗。</li>
 *   <li>无状态、可任意并发调用；不缓存任何中间量。</li>
 * </ul>
 */
public final class CabinLighting {
    /** 灯罩朝下面使用的方块光等级（0..15）：满级，因为它是轿厢内唯一的主动光源。 */
    public static final int LAMP_LEVEL = 15;
    /** 灯具在轿厢本地坐标中的位置（格）：舱顶正中偏后一点（顶板内表面在 Y≈2.8，故灯位取 2.78）。 */
    private static final double LAMP_X = 0, LAMP_Y = 2.78, LAMP_Z = -.25;

    private CabinLighting() { }

    /**
     * 普通舱内表面（舱壁、地板、顶板内面等）的补光采样：面中心离灯越远越暗，背向灯的面不补光。
     *
     * <p>采样点在轿厢本地坐标、施加轿厢旋转之前；超出"舱内"范围的面一律返回世界光照（见下）。
     * 只抬高方块光分量，天光与更高位保留（见 {@link #withBlockLight}）。
     *
     * @param worldLight 该顶点原本的打包光照值（世界采样值）
     * @param x 面中心本地 X，单位：格
     * @param y 面中心本地 Y，单位：格
     * @param z 面中心本地 Z，单位：格
     * @param nx 该面法线 X（单位向量，已随轿厢旋转）
     * @param ny 该面法线 Y
     * @param nz 该面法线 Z
     * @return 补光后的打包光照值；不在舱内补光范围时原样返回 {@code worldLight}
     */
    public static int surface(int worldLight, float x, float y, float z,
                              float nx, float ny, float nz) {
        // 排除基座底面、顶板外面、外壳侧壁与背面导靴：这些面看不见或朝外，补光会从壳缝里透出去。
        // 观光舱的玻璃框延伸到 1.41 格，实心外壳在 1.5 格，故取 1.42 作分界（留出浮点余量）。
        // 正面以门背面 + 3 毫米为界：滑动门与门区不在舱内补光范围内，门扇靠世界光照亮。
        if (Math.abs(x) > 1.42 || y < .199 || y > 2.802 || z < -1.42
                || z > ElevatorParameters.CABIN_DOOR_BACK_Z + .003) return worldLight;
        double dx = LAMP_X - x, dy = LAMP_Y - y, dz = LAMP_Z - z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double facing = (dx * nx + dy * ny + dz * nz) / Math.max(distance, .001);
        // facing = 面法线与"指向灯具的方向"的夹角余弦：背面（含门扇朝外的一面）不受舱内灯照。
        if (facing <= 0) return worldLight;
        // 衰减：每远离灯 1 格降 1.4 级，斜射面再按 (1-facing) 最多降 2 级；
        // 两个系数与上限 13 都是观感调参值（与 CabinRenderer 的贴图/色板配套），改前请实机看舱内层次。
        int level = (int) Math.floor(LAMP_LEVEL - 1.4 * distance - 2 * (1 - facing));
        return withBlockLight(worldLight, Math.max(0, Math.min(13, level)));
    }

    /**
     * 灯罩自身的光照：只有朝下的漫射面发满级方块光，顶面与侧边保持环境光。
     *
     * <p>这样灯罩看起来是"从下面透光"的，而不是整块自发光塑料。
     *
     * @param worldLight 该顶点原本的打包光照值
     * @param x 面中心本地 X，单位：格（本方法不使用，保留以匹配 {@code BoxMesh.FaceLighting} 的签名）
     * @param y 面中心本地 Y，单位：格（同上）
     * @param z 面中心本地 Z，单位：格（同上）
     * @param nx 面法线 X（同上）
     * @param ny 面法线 Y：&lt; -0.5 视为"朝下"
     * @param nz 面法线 Z（同上）
     * @return 朝下的面返回方块光 {@link #LAMP_LEVEL}，其余面原样返回 {@code worldLight}
     */
    public static int lamp(int worldLight, float x, float y, float z,
                           float nx, float ny, float nz) {
        return ny < -.5f ? withBlockLight(worldLight, LAMP_LEVEL) : worldLight;
    }

    /**
     * 把方块光分量提升到给定等级，且只抬不降。
     *
     * @param worldLight 原打包光照值
     * @param level 期望的方块光等级（0..15）
     * @return 天光与更高位原样保留、方块光取两者较大者后的打包光照值
     */
    private static int withBlockLight(int worldLight, int level) {
        // 打包格式里方块光占 bit 4..7，天光在更高位：先清掉这 4 位再写入较大者，天光与其余位不受影响。
        return (worldLight & ~0xF0) | Math.max((worldLight >> 4) & 15, level) << 4;
    }
}
