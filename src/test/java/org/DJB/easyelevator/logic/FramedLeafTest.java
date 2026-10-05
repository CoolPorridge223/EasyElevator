package org.DJB.easyelevator.logic;

/**
 * Run with tools/test-logic.ps1. No Minecraft bootstrap or external test framework needed.
 *
 * <p>{@link FramedLeaf} 的回归测试：铁框玻璃门扇的边框与玻璃矩形。这段算术出错的表现是
 * "门快开完时闪一下"或"玻璃消失/变黑"，所以这里逐个宽度断言：边框不超过门扇、玻璃永远是非负且在门扇内、
 * 门扇收到边框以内时玻璃优雅退化成一个点（而不是反向矩形）、全开（宽度 0）时玻璃不可见。
 */
public final class FramedLeafTest {
    /** 断言：失败即抛 AssertionError，由退出码/异常判定为失败。 */
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }

    /**
     * 测试入口：从整扇门宽一路收到 0，全部通过后打印 PASS 行。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        // 楼层门叶关闭时宽 1.25 格（20/16）、高 2.8125 格（45/16）
        for(double width:new double[]{1.25,1.0,.6,.4,.3,.2,.1,.05,.01,0}) {
            String where="width="+width;
            double w0=0,w1=width,y0=0,y1=2.8125;
            double[] l=FramedLeaf.layout(w0,w1,y0,y1);
            double f=FramedLeaf.frameWidth(width);
            check(f>=0&&f<=FramedLeaf.FRAME+1e-9,where+": frame width must stay within 0..FRAME");
            // 竖框：不越出门扇、不互相穿插
            check(l[4]>=w0-1e-9&&l[5]<=w1+1e-9&&l[6]>=w0-1e-9&&l[7]<=w1+1e-9,where+": stiles must stay inside the leaf");
            check(l[5]<=l[6]+1e-9,where+": the two stiles must not cross");
            // 横框同理
            check(l[0]>=y0-1e-9&&l[1]<=y1+1e-9&&l[2]>=y0-1e-9&&l[3]<=y1+1e-9,where+": rails must stay inside the leaf");
            check(l[1]<=l[2]+1e-9,where+": the two rails must not cross");
            // 玻璃：非负、在门扇内、且不超出两侧竖框之间
            check(l[9]>=l[8]-1e-9&&l[11]>=l[10]-1e-9,where+": glass must not be an inverted rectangle");
            check(l[8]>=w0-1e-9&&l[9]<=w1+1e-9&&l[10]>=y0-1e-9&&l[11]<=y1+1e-9,where+": glass must stay inside the leaf");
            if(width>3*FramedLeaf.FRAME) check(l[9]-l[8]>0,where+": a wide leaf must still have glass");
            check(FramedLeaf.glassVisible(l)==(l[9]-l[8]>1e-6&&l[11]-l[10]>1e-6),
                    where+": glassVisible must agree with the glass rectangle");
        }

        // 边框随门扇变窄而变窄：整扇门时用满 2/16，收到一半时按比例缩
        check(Math.abs(FramedLeaf.frameWidth(1.25)-FramedLeaf.FRAME)<1e-9,"a wide leaf uses the full 2/16 frame");
        check(FramedLeaf.frameWidth(.3)<FramedLeaf.FRAME,"a narrow leaf must shrink its frame");
        check(FramedLeaf.frameWidth(-1)==0&&FramedLeaf.frameWidth(0)==0,"negative/zero width must give a zero frame");

        // 全开：门扇宽度为 0，玻璃不可见
        double[] closedUp=FramedLeaf.layout(.5,.5,0,2.8125);
        check(!FramedLeaf.glassVisible(closedUp),"a fully open leaf has no glass to draw");

        // 8. 贴图不能拉伸：每块边框的 UV 取用范围必须跟着它自己的尺寸走
        //    （照旧把整张贴图铺到 2/16 格宽的竖框上，整块门板贴图会被压成一条"条形码"）
        float[] window={.1f,.0f,.9f,1f}; // 门扇大面的 UV 窗口
        for(double w:new double[]{1.25,.8,.4,.2,.05}) {
            String where="window width="+w;
            double[] l=FramedLeaf.layout(0,w,0,2.8125);
            double stileFrac=(l[5]-l[4])/w, railFrac=(l[1]-l[0])/2.8125;
            float[] stile=FramedLeaf.stileUv(window,stileFrac), rail=FramedLeaf.railUv(window,railFrac);
            // 竖框：横向只取"边框占比"那一段，纵向取整段
            check(Math.abs((stile[2]-stile[0])-(window[2]-window[0])*stileFrac)<1e-6,
                    where+": the stile must take only its own share of the window width");
            check(Math.abs((stile[3]-stile[1])-(window[3]-window[1]))<1e-6,where+": the stile must span the full window height");
            check(stile[0]>=window[0]-1e-6&&stile[2]<=window[2]+1e-6,where+": the stile UV must stay inside the window");
            // 横框：横向取整段，纵向只取"边框占比"那一段
            check(Math.abs((rail[2]-rail[0])-(window[2]-window[0]))<1e-6,where+": the rail must span the full window width");
            check(Math.abs((rail[3]-rail[1])-(window[3]-window[1])*railFrac)<1e-6,
                    where+": the rail must take only its own share of the window height");
            check(rail[1]>=window[1]-1e-6&&rail[3]<=window[3]+1e-6,where+": the rail UV must stay inside the window");
        }

        // 9. 玻璃面：UV 的宽高比必须与玻璃面的宽高比一致（否则花纹会被纵向拉长约 2 倍）
        float[] cell={.5f,.5f,.75f,.75f};
        for(double[] pane:new double[][]{{1.2,2.6},{.6,2.6},{.2,2.6},{2.6,2.6}}) {
            float[] uv=FramedLeaf.paneUv(cell,pane[0],pane[1]);
            double uvAspect=(uv[2]-uv[0])/(uv[3]-uv[1]);
            double paneAspect=pane[0]/pane[1];
            check(Math.abs(uvAspect-Math.min(1,paneAspect))<1e-6,
                    "the glass UV aspect ("+uvAspect+") must match the pane's ("+paneAspect+")");
            check(uv[0]>=cell[0]-1e-6&&uv[2]<=cell[2]+1e-6,"the glass UV must stay inside its cell");
        }

        // 10. 断面小片：始终是基准矩形内部的一小块，且比例被夹在 0..1
        for(double f:new double[]{-1,0,.06,.5,1,2}) {
            float[] slice=FramedLeaf.centredSlice(window,(float)f,(float)f);
            check(slice[0]>=window[0]-1e-6&&slice[2]<=window[2]+1e-6&&slice[1]>=window[1]-1e-6&&slice[3]<=window[3]+1e-6,
                    "a centred slice must stay inside the rect (fraction "+f+")");
            check(slice[2]>=slice[0]&&slice[3]>=slice[1],"a centred slice must not invert (fraction "+f+")");
        }

        System.out.println("PASS: framed glass leaf (frame shrinks with the leaf, glass never inverts, stays inside the"
                + " leaf, disappears cleanly when fully open, and every part takes a UV range scaled to its own size so"
                + " nothing is stretched).");
    }
}
