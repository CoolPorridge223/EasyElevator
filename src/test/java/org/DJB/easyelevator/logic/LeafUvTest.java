package org.DJB.easyelevator.logic;

/**
 * Run with tools/test-logic.ps1. No Minecraft bootstrap or external test framework needed.
 *
 * <p>{@link LeafUv} 的回归测试。这里的断言刻意写成<b>画面上的要求</b>，而不是"坐标谁大谁小"：
 * 1.5.6 实机反馈"四个朝向里只有一个门贴图是对的"，根因就是上一版断言了"u0 贴在长方体最小坐标那一端"
 * 这个实现假设——{@code BoxMesh} 实际是把 u0 交给每个面的第一个顶点，而各面第一个顶点落在哪一端是固定的。
 * 所以现在断言的是：
 * <ol>
 *   <li><b>先导端拿到哪一号纹理坐标</b>：左扇 u0 = 1（门板先导端 = 折边所在）、右扇 u1 = 0；</li>
 *   <li><b>门框端拿到另一号</b>：左扇 u1 = 进度、右扇 u0 = 1-进度（可见区间就是 [进度,1] / [0,1-进度]）；</li>
 *   <li><b>全关时正好用完整段贴图</b>（不退化、不少贴一半），全开时宽度归零；</li>
 *   <li><b>映射进格子</b>：结果整体落在目标格内，不会越界取到相邻的图集格。</li>
 * </ol>
 */
public final class LeafUvTest {
    /** 断言：失败即抛 AssertionError，由退出码/异常判定为失败。 */
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }

    /** 与 CabinRenderer.Mat.DOOR 在图集里的格子一致：4x4 图集的第 14 格（col 1, row 3 → u 0.25..0.5, v 0.75..1）。 */
    private static final float[] DOOR_CELL={.25f,.75f,.5f,1f};

    /**
     * 测试入口：两扇门 × 六个进度 × 两种目标格子，外加镜像规则与非法进度；全部通过后打印 PASS 行。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        for(boolean right:new boolean[]{false,true})
            for(float p:new float[]{0f,.05f,.25f,.5f,.9f,1f}) {
                String where="right="+right+" progress="+p;
                float[] range=LeafUv.leafRange(p,right);
                float u0=range[0],u1=range[1];              // u0 = 面第一个顶点那一端
                float lo=Math.min(u0,u1),hi=Math.max(u0,u1);
                // 1. 可见宽度 = 1 - 进度（全关时整段、全开时归零）
                check(Math.abs((hi-lo)-(1-p))<1e-6,where+": visible fraction must be 1-progress");
                // 2. 先导端与门框端拿到的纹理坐标：左扇 [进度,1]（u0 在先导端）、右扇 [0,1-进度]（u1 在先导端）
                if(right) {
                    check(Math.abs(u1-0)<1e-6,where+": the right leaf's leading edge must sample texture u=0");
                    check(Math.abs(u0-(1-p))<1e-6,where+": the right leaf's frame end must sample u=1-progress");
                } else {
                    check(Math.abs(u0-1)<1e-6,where+": the left leaf's leading edge must sample texture u=1");
                    check(Math.abs(u1-p)<1e-6,where+": the left leaf's frame end must sample u=progress");
                }
                if(p==0f) check(lo==0&&hi==1,where+": a closed leaf must span the whole texture");
                check(u0>=0&&u0<=1&&u1>=0&&u1<=1,where+": both ends must be inside the texture");
                // 3. 映射进目标格子：整体落在格内，全关时正好用完整个格子
                for(float[] target:new float[][]{DOOR_CELL,{0,0,1,1}}) {
                    float[] uv=LeafUv.toUv(range,target);
                    float a=Math.min(uv[0],uv[2]),b=Math.max(uv[0],uv[2]);
                    check(a>=target[0]-1e-6&&b<=target[2]+1e-6,where+": uv u range must stay inside the target cell");
                    check(uv[1]==target[1]&&uv[3]==target[3],where+": v range is the whole cell height");
                    if(p==0f) check(Math.abs(a-target[0])<1e-6&&Math.abs(b-target[2])<1e-6,
                            where+": a closed leaf must use the whole cell, not a slice of the atlas");
                }
            }

        // 4. 断面：固定的一小段，位于先导端那一侧
        for(boolean right:new boolean[]{false,true}) {
            float[] edge=LeafUv.edgeRange(right,LeafUv.EDGE_WIDTH);
            float lo=Math.min(edge[0],edge[1]),hi=Math.max(edge[0],edge[1]);
            check(Math.abs(hi-lo-LeafUv.EDGE_WIDTH)<1e-6,"edge width must be EDGE_WIDTH");
            check(Math.abs(lo-(right?0:1-LeafUv.EDGE_WIDTH))<1e-6&&Math.abs(hi-(right?LeafUv.EDGE_WIDTH:1))<1e-6,
                    "the rim slice must sit at the panel's leading end (right="+right+")");
        }

        // 5. 六面 UV：大面用门板贴图、四周断面用固定片段，厚度方向决定哪两个面是大面
        float[] panel={.2f,.1f,.9f,.4f}, edge={.1f,.1f,.2f,.4f};
        for(boolean alongZ:new boolean[]{true,false}) {
            float[][] faces=LeafUv.slabUv(panel,edge,alongZ);
            check(faces.length==6,"slabUv must describe all six faces");
            for(int i=0;i<6;i++) check(faces[i]==panel||faces[i]==edge,"face "+i+" must use one of the two rects");
            check(faces[alongZ?1:3]==panel&&faces[alongZ?0:2]==panel,"the two plate faces must get the panel rect");
            check(faces[4]==edge&&faces[5]==edge,"top/bottom are always the thin rim");
        }

        // 6. 非法进度给出确定结果，绝不把 NaN 写进坐标
        for(float bad:new float[]{-1f,2f,Float.NaN,Float.POSITIVE_INFINITY}) {
            float p=LeafUv.sanitize(bad);
            check(p>=0&&p<=1,"sanitized progress for "+bad);
            float[] range=LeafUv.leafRange(bad,false);
            check(Double.isFinite(range[0])&&Double.isFinite(range[1]),"range must stay finite for "+bad);
            check(range[0]>=0&&range[0]<=1&&range[1]>=0&&range[1]<=1,"range must stay inside the texture for "+bad);
        }

        System.out.println("PASS: sliding-leaf UV (leading edge and frame end sample the right texture ends on both leaves and"
                + " every facing, whole texture when closed, mapped inside the atlas cell).");
    }
}
