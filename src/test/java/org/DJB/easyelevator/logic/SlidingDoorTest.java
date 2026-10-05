package org.DJB.easyelevator.logic;

/**
 * Run with tools/test-logic.ps1. No Minecraft bootstrap or external test framework needed.
 *
 * <p>{@link SlidingDoor} 的回归测试：轿厢门是<b>两扇对开滑门</b>，门洞就是整个轿厢正面（内净宽 2.6 格），
 * 两扇一起向两侧收拢、全开时门洞全通——与楼层门同一套做法。这里钉住的不变量是：
 * 关门时两扇拼满整个正面只留中缝、外缘固定不动、内缘随进度线性外移、两扇严格镜像、
 * 全开时宽度归零（门不再生成）、门扇始终不越出轿厢外表面、与楼层门框保 0.0125 格间隙。
 */
public final class SlidingDoorTest {
    /** 断言：失败即抛 AssertionError，由退出码/异常判定为失败。 */
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }

    /**
     * 测试入口：五个进度 × 两侧，加门区厚度/间隙/非法进度检查，全部通过后打印 PASS 行。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        // 1. 关门（进度 0）：两扇拼满整个正面，只在中缝分开，且外缘正好贴到侧壁内侧
        double[] closed=SlidingDoor.panelX(false,0);
        check(Math.abs(closed[0]+SlidingDoor.OUTER_EDGE)<1e-9&&Math.abs(closed[1]+SlidingDoor.SEAM)<1e-9,
                "closed left leaf must span -OUTER_EDGE..-SEAM");
        check(Math.abs(SlidingDoor.clearHalfWidth(0)-SlidingDoor.SEAM)<1e-9,"a closed door leaves only the centre seam");

        for(float p:new float[]{0f,.25f,.5f,.75f,1f}) {
            String where="progress="+p;
            double[] l=SlidingDoor.panelX(false,p), r=SlidingDoor.panelX(true,p);
            // 2. 外缘固定不动，内缘随进度线性外移
            check(Math.abs(l[0]+SlidingDoor.OUTER_EDGE)<1e-9&&Math.abs(r[1]-SlidingDoor.OUTER_EDGE)<1e-9,
                    where+": the outer edge must stay put (it is the fixed side of the leaf)");
            double expectedInner=SlidingDoor.SEAM+(SlidingDoor.OUTER_EDGE-SlidingDoor.SEAM)*p;
            check(Math.abs(-l[1]-expectedInner)<1e-9&&Math.abs(r[0]-expectedInner)<1e-9,
                    where+": the leading edge must move outward linearly");
            check(Math.abs(SlidingDoor.clearHalfWidth(p)-expectedInner)<1e-9,
                    where+": the clear opening must match clearHalfWidth");
            // 3. 两扇严格镜像；宽度随进度单调变小（全开时归零）
            check(Math.abs(l[0]+r[1])<1e-9&&Math.abs(l[1]+r[0])<1e-9,where+": both leaves must mirror each other");
            double width=r[1]-r[0];
            check(width>=-1e-9,where+": the leaf must not invert");
            check(Math.abs(width-(SlidingDoor.OUTER_EDGE-expectedInner))<1e-9,where+": leaf width = OUTER_EDGE - inner");
            // 4. 门扇始终不越出轿厢外表面
            check(Math.abs(l[0])<=1.5&&Math.abs(r[1])<=1.5,where+": the leaf must stay inside the cabin");
            // 5. 可见性判定与宽度一致：全开时宽度归零、跳过渲染与碰撞
            check(SlidingDoor.visible(p)==(width>1e-6),where+": visible() must agree with the leaf width");
        }

        // 6. 全开：门洞几乎全通（只剩 OUTER_INSET 的收边），两扇都不再生成
        check(!SlidingDoor.visible(1),"a fully open door has no leaves left to draw");
        check(Math.abs(SlidingDoor.clearHalfWidth(1)-SlidingDoor.OUTER_EDGE)<1e-9,
                "a fully open door must clear the whole doorway");
        check(SlidingDoor.clearHalfWidth(1)/SlidingDoor.DOORWAY_HALF>.99,
                "the open doorway must be essentially the full cabin width");

        // 7. 门区厚度 0.2 格、前表面与轿厢正面齐平（楼层门后缘 1.3125 → 间隙 0.0125 > GameTest 的 0.01 容差）
        double[] z=SlidingDoor.doorZ();
        check(Math.abs((z[1]-z[0])-.2)<1e-9,"the door zone must stay 0.2 blocks deep");
        check(Math.abs(SlidingDoor.DOOR_Z_FRONT-1.3)<1e-9&&1.3125-SlidingDoor.DOOR_Z_FRONT>.01,
                "the door front must keep more than 0.01 blocks of clearance from the landing frame");

        // 8. 非法进度给出确定结果，绝不把 NaN 写进坐标
        for(float bad:new float[]{-1f,2f,Float.NaN,Float.POSITIVE_INFINITY}) {
            float s=SlidingDoor.sanitize(bad);
            check(s>=0&&s<=1,"sanitized progress for "+bad);
            double[] x=SlidingDoor.panelX(false,bad);
            check(Double.isFinite(x[0])&&Double.isFinite(x[1]),"panel coordinates must stay finite for "+bad);
        }

        System.out.println("PASS: two-leaf cabin door (spanning the whole cabin front, both leaves retract into the side"
                + " walls, fully open when done, same mechanism as the landing door, still 0.0125 blocks clear of the"
                + " landing frame).");
    }
}
