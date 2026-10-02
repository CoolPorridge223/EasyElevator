package org.DJB.easyelevator.logic;

/**
 * Run with tools/test-logic.ps1. No Minecraft bootstrap or external test framework needed.
 *
 * <p>选站面板网格排布（{@link PanelLayout}）的回归测试：编号顺序（最底层 = 1 层、右下角起步、
 * 从右往左、从下往上）、长方形列数选择、分页不漏站也不多出空页。面板本身是客户端 GUI，
 * 但排布规则是纯算术，因此可以脱离游戏直接跑 {@link #main}，用 AssertionError 判定成败。
 */
public final class PanelLayoutTest {
    /** 断言：失败即抛 AssertionError，由退出码/异常判定为失败。 */
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }

    /**
     * 测试入口：验证列数选择、编号顺序与分页，全部通过后打印 PASS 行。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        // 1. 列数选择：把站点尽量排成长方形（上限 8 列时的期望值）。
        int[][] expected={{1,1},{2,2},{3,3},{4,2},{5,3},{6,3},{7,4},{8,4},{9,3},{10,5},{11,4},{12,4},{16,4},{20,5}};
        for(int[] e:expected) {
            int got=PanelLayout.chooseColumns(e[0],8);
            check(got==e[1],"columns for "+e[0]+" stations: "+got+" != "+e[1]);
        }
        // 列数上限较小时必须被压住（窗口很窄的情况）。
        for(int maxColumns=1;maxColumns<=8;maxColumns++)
            for(int count=1;count<=64;count++) {
                int got=PanelLayout.chooseColumns(count,maxColumns);
                check(got>=1&&got<=maxColumns,"columns "+got+" out of 1.."+maxColumns+" for "+count+" stations");
            }

        // 2. 编号顺序：9 个站点 → 3×3，序号 0 在右下角，先向左、再向上（"从右往左、从下往上"）。
        int columns=PanelLayout.chooseColumns(9,8);
        check(columns==3,"9 stations use 3 columns");
        for(int row=0;row<3;row++) for(int fromRight=0;fromRight<3;fromRight++) {
            int index=row*columns+fromRight; // 第 row 行（自下而上）、从右数第 fromRight 列
            check(PanelLayout.rowFromBottom(index,columns)==row,
                    "index "+index+" should sit on row "+row+" from the bottom");
            check(PanelLayout.columnFromRight(index,columns)==fromRight,
                    "index "+index+" should sit "+fromRight+" columns from the right");
        }
        // 同一列的数字自下而上递增：右列 1/4/7、中列 2/5/8、左列 3/6/9（真实面板的观感）。
        for(int fromRight=0;fromRight<columns;fromRight++) {
            int previous=-1;
            for(int row=0;row<3;row++) {
                int index=row*columns+fromRight;
                check(index>previous,"column "+fromRight+" numbers must grow upward (index "+index+")");
                previous=index;
            }
        }
        // 最底层就是编号 1：整份列表按高度升序，下标 0 落在右下角（从下数第 0 行、从右数第 0 列）。
        check(PanelLayout.rowFromBottom(0,columns)==0&&PanelLayout.columnFromRight(0,columns)==0,
                "floor 1 (lowest station) must be the bottom-right button");

        // 3. 分页：所有站点恰好出现一次，且最后一页不为空。
        for(int count=1;count<=64;count++) {
            for(int maxColumns:new int[]{1,3,5,8}) for(int maxRows:new int[]{1,3,8}) {
                var grid=PanelLayout.grid(count,maxColumns,maxRows);
                check(grid.columns()>=1&&grid.columns()<=maxColumns,"columns in range for "+count);
                check(grid.rowsPerPage()>=1&&grid.rowsPerPage()<=maxRows,"rows in range for "+count);
                check(grid.columns()*grid.totalRows()>=count,"all stations fit for "+count);
                check(grid.pageCount()==Math.max(1,(grid.totalRows()+grid.rowsPerPage()-1)/grid.rowsPerPage()),
                        "page count for "+count);
                check(PanelLayout.pageStart(grid,grid.pageCount()-1)<count,"last page must not be empty for "+count);

                boolean[] seen=new boolean[count];
                for(int page=0;page<grid.pageCount();page++) {
                    int start=PanelLayout.pageStart(grid,page);
                    int end=Math.min(count,start+PanelLayout.capacity(grid));
                    check(start<end,"page "+page+" must have at least one station for "+count);
                    for(int i=start;i<end;i++) {
                        check(!seen[i],"station "+i+" appears on more than one page for "+count);
                        seen[i]=true;
                    }
                }
                for(boolean shown:seen) check(shown,"every station must be reachable for "+count);
            }
        }
        // 空列表也要给出一个可绘制的 1×1 网格，面板才不会除以 0 或画出负数尺寸。
        var empty=PanelLayout.grid(0,8,8);
        check(empty.columns()==1&&empty.rowsPerPage()==1&&empty.pageCount()==1,"empty list uses a 1x1 grid");

        System.out.println("PASS: station panel layout (rectangle column choice, floor 1 at the bottom-right,"
                + " right-to-left then bottom-to-top numbering, paging covers every station exactly once).");
    }
}
