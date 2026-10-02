package org.DJB.easyelevator.block;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

import java.util.List;

/**
 * Run with {@code ./gradlew.bat geometryTest} (or from the IDE). Needs the Minecraft classes, no game launch.
 *
 * <p>楼层门门面几何（{@link LandingDoorGeometry}）的回归测试：门框连成整圈且常驻、门扇随进度向两侧收拢、
 * 关门时门洞被封住且正中只留一条细门缝、几何不越出自己那一格、门面厚度与朝轿厢一侧的间隙符合设计值。
 * 这些不变量同时决定碰撞、轮廓与渲染，任何一条退化都会表现为穿模、闪烁、门框断开或门扇凭空消失，
 * 因此改门模型或改尺寸后应先跑一遍。
 *
 * <p>期望值全部由 {@link LandingDoorGeometry} 的常量推出（不写死 23 / 25 这类数字），
 * 这样调整 {@code SEAM}、{@code FRAME} 或门尺寸后本测试仍然有效。
 *
 * <p>与 {@code logic/ElevatorControllerTest} 同样的思路：几何是纯算术，不依赖世界、方块状态或方块实体，
 * 所以可以直接用普通 {@code java} 跑 {@link #main}，用 AssertionError 判定成败；差别是本类需要
 * Minecraft 的 {@code Box}/{@code VoxelShape}/{@code Direction}，因此必须带上 Minecraft 类路径。
 * 长度单位是格（方块），门内坐标用 1/16 格。
 */
public final class LandingDoorGeometryTest {
    /** 四种水平朝向：NORTH/EAST 是直接映射，SOUTH/WEST 需要镜像，必须都覆盖。 */
    private static final Direction[] FACINGS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** 每扇门扇可能停留的位置：全关、半开、全开。 */
    private static final float[] LEAF_STEPS = {0f, .5f, 1f};

    /** 断言：失败即抛 AssertionError，由退出码/异常判定为失败。 */
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    /** 该盒在门宽轴上的跨度（格）。门宽轴 = FACING.rotateYClockwise()。 */
    private static double width(Box box, Direction facing) {
        return facing.getAxis() == Direction.Axis.Z ? box.maxX - box.minX : box.maxZ - box.minZ;
    }

    /** 该盒沿朝向方向、自门面那一侧算起的厚度（格）：恒为门面厚度 3/16。 */
    private static double facingThickness(Box box, Direction facing) {
        return switch (facing) {
            case SOUTH -> 1 - box.minZ;
            case NORTH -> box.maxZ;
            case EAST -> 1 - box.minX;
            default -> box.maxX;
        };
    }

    /**
     * 测试入口：逐条验证几何不变量，全部通过后打印 PASS 行。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        int boxes = 0;

        // 1. 门扇内缘行程：关门时两扇各占门洞一半、正中只留 SEAM 宽门缝；全开时收到门框内缘（宽度归零）。
        double leftInner = LandingDoorGeometry.FRAME + LandingDoorGeometry.LEAF_TRAVEL;
        double rightInner = LandingDoorGeometry.DOOR_WIDTH - LandingDoorGeometry.FRAME - LandingDoorGeometry.LEAF_TRAVEL;
        check(Math.abs(LandingDoorGeometry.leafEdge(0f, false) - leftInner) < 1e-9, "closed left leaf inner edge");
        check(Math.abs(LandingDoorGeometry.leafEdge(0f, true) - rightInner) < 1e-9, "closed right leaf inner edge");
        check(Math.abs(rightInner - leftInner - LandingDoorGeometry.SEAM) < 1e-9, "closed seam width = SEAM");
        check(Math.abs(LandingDoorGeometry.leafEdge(1f, false) - LandingDoorGeometry.FRAME) < 1e-9,
                "open left leaf retracts into the frame");
        check(Math.abs(LandingDoorGeometry.leafEdge(1f, true) - (LandingDoorGeometry.DOOR_WIDTH - LandingDoorGeometry.FRAME)) < 1e-9,
                "open right leaf retracts into the frame");
        double previous = Double.MAX_VALUE;
        for (float p : new float[]{0f, .25f, .5f, .75f, 1f}) {
            double edge = LandingDoorGeometry.leafEdge(p, false);
            // 单调收拢：进度增加时门扇只能往门框里走，回弹在画面上就是抖动。
            check(edge <= previous + 1e-9, "left leaf retracts monotonically at p=" + p);
            check(edge >= LandingDoorGeometry.FRAME - 1e-9, "left leaf never crosses the frame at p=" + p);
            check(LandingDoorGeometry.leafEdge(p, true) <= LandingDoorGeometry.DOOR_WIDTH - LandingDoorGeometry.FRAME + 1e-9,
                    "right leaf never crosses the frame at p=" + p);
            previous = edge;
        }
        // 全开时宽度归零，渲染器据此直接跳过绘制。
        check(LandingDoorGeometry.leafBox(Direction.SOUTH, 1f, false) == null, "fully open leaf has no geometry");
        check(LandingDoorGeometry.leafBox(Direction.SOUTH, 0f, false) != null, "closed leaf has geometry");

        for (Direction facing : FACINGS) {
            // 2. 门框常驻且连成整圈：全开时左右立柱与门楣都还在，门洞让空。
            check(!LandingDoorGeometry.shape(facing, 0, 0, 1f).isEmpty(), facing + ": left pillar stays when open");
            check(!LandingDoorGeometry.shape(facing, 2, 0, 1f).isEmpty(), facing + ": right pillar stays when open");
            check(!LandingDoorGeometry.shape(facing, 1, 2, 1f).isEmpty(), facing + ": top frame stays when open");
            check(LandingDoorGeometry.shape(facing, 1, 0, 1f).isEmpty(), facing + ": open door clears the doorway");
            check(LandingDoorGeometry.shape(facing, 1, 1, 1f).isEmpty(), facing + ": open door clears the upper doorway");
            check(!LandingDoorGeometry.shape(facing, 1, 0, 0f).isEmpty(), facing + ": closed door blocks the doorway");
            // 顶行左右两列必须是"立柱 + 门楣"：立柱通到本格底部（minY≈0）且门楣在本格顶部（maxY≈1），
            // 否则立柱在离门楣 13/16 格处断开，整圈门框看起来像三段。
            for (int column : new int[]{0, 2}) {
                boolean reachesDown = false, hasBeam = false;
                for (Box part : LandingDoorGeometry.shape(facing, column, 2, 0f).getBoundingBoxes()) {
                    if (part.minY < .05) reachesDown = true;
                    if (part.maxY > .95) hasBeam = true;
                }
                check(reachesDown && hasBeam, facing + ": top row keeps pillar connected to the beam, column=" + column);
            }

            // 3. 关门时两扇门扇只在中缝处分开：没有任何碰撞盒跨越门洞正中。
            for (Box part : LandingDoorGeometry.shape(facing, 1, 0, 0f).getBoundingBoxes()) {
                boolean crossesCentre = switch (facing) {
                    case NORTH, SOUTH -> part.minX < .5 && part.maxX > .5;
                    default -> part.minZ < .5 && part.maxZ > .5;
                };
                check(!crossesCentre, facing + ": closed leaves leave the centre seam open");
            }

            for (int level = 0; level < 3; level++) {
                for (int column = 0; column < 3; column++) {
                    for (float p : LEAF_STEPS) {
                        List<Box> parts = LandingDoorGeometry.shape(facing, column, level, p).getBoundingBoxes();
                        for (Box part : parts) {
                            boxes++;
                            // 4. 形状必须是"本格内部"的几何，否则碰撞会溢出到相邻方块。
                            check(part.minX >= -1e-9 && part.maxX <= 1 + 1e-9
                                            && part.minY >= -1e-9 && part.maxY <= 1 + 1e-9
                                            && part.minZ >= -1e-9 && part.maxZ <= 1 + 1e-9,
                                    facing + ": part " + column + "/" + level + " stays in its own block at p=" + p + " -> " + part);
                            // 5. 门面厚度恒为 3/16 格且贴在朝 FACING 的一侧：这是"轿厢前端与楼层门之间留
                            //    0.0125 格"的前提（轿厢中心在轨道中心前 2 格、前端 1.3，门面在 3.8125）。
                            check(Math.abs(facingThickness(part, facing) - LandingDoorGeometry.FRAME / 16) < 1e-9,
                                    facing + ": part " + column + "/" + level + " depth along facing != 3/16 at p=" + p
                                            + " -> " + facingThickness(part, facing));
                        }
                    }
                    if (level < 2) {
                        // 6. 关门时的横向覆盖：左右两列被门框立柱 + 门扇铺满，中列只留 SEAM 宽门缝。
                        double covered = 0;
                        for (Box part : LandingDoorGeometry.shape(facing, column, level, 0f).getBoundingBoxes())
                            covered += width(part, facing);
                        double expected = column == 1 ? 1 - LandingDoorGeometry.SEAM / 16 : 1;
                        check(Math.abs(covered - expected) < 1e-9,
                                facing + ": column " + column + " closed width coverage " + covered + " != " + expected);
                    }
                }
            }

            // 7. 由几何推出的轿厢前端间隙：门方块中心在轨道中心前 3 格，门面再减去厚度；轿厢前端在 2 + 1.3 格。
            double doorNearFace = 3.0 - 0.5 + (1 - LandingDoorGeometry.FRAME / 16);
            double cabinFront = 2.0 + 1.3;
            check(Math.abs((doorNearFace - cabinFront) - 0.0125) < 1e-9,
                    facing + ": derived cabin/landing gap is 0.0125 -> " + (doorNearFace - cabinFront));
        }

        System.out.println("PASS: landing door geometry (" + boxes + " collision boxes over "
                + FACINGS.length + " facings x 3 columns x 3 levels x " + LEAF_STEPS.length + " leaf positions):"
                + " connected permanent frame, retracting leaves, centred seam=" + LandingDoorGeometry.SEAM
                + "/16, in-block shapes, 3/16 depth, 0.0125 cabin gap.");
    }
}
