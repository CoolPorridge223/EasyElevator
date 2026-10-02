package org.DJB.easyelevator.logic;

/**
 * 选站面板的网格布局计算：纯算术，不引用任何 Minecraft 类，因此可以脱离游戏直接跑测试
 * （见 {@code src/test/.../PanelLayoutTest}）。
 *
 * <p>规则（模拟真实电梯按键面板）：
 * <ul>
 *   <li>编号：站点按高度升序，<b>最底层 = 1 层</b>，编号即按钮上的数字；</li>
 *   <li>顺序：从<b>右下角</b>开始，先<b>从右往左</b>排满一行，再换到<b>上一行</b>继续（从下往上），
 *       因此每一列的数字自下而上连续递增；</li>
 *   <li>形状：列数尽量让整块按键排成"长方形"——优先不留下凑不满的行，其次尽量接近正方形，
 *       同分取更宽的排法（见 {@link #chooseColumns}）；站点放不下时按整行翻页。</li>
 * </ul>
 */
public final class PanelLayout {
    /** 一页的排布参数：列数、单页行数、总行数、总页数。 */
    public record Grid(int columns,int rowsPerPage,int totalRows,int pageCount) { }

    /** 工具类，禁止实例化。 */
    private PanelLayout() { }

    /**
     * 选一个"尽量长方形"的列数。
     *
     * <p>把 count 个站点排成 columns × rows，评分 = 空格数 × 2 + |columns - rows|：
     * 先看有没有凑不满的行，再看整体是否接近正方形；同分取列数更大者（面板宽一些更像按键板）。
     * 例（上限 8 列）：9→3×3、12→4×3、10→5×2、8→4×2、7→4×2（顶行空一格）、5→3×2。
     *
     * @param count 站点总数（0 或 1 时固定 1 列）
     * @param maxColumns 列数上限（由面板可用宽度决定）
     * @return 选中的列数，范围 1..maxColumns
     */
    public static int chooseColumns(int count,int maxColumns) {
        if(count<=1) return 1;
        int best=1,bestScore=Integer.MAX_VALUE;
        for(int c=1;c<=Math.max(1,maxColumns);c++) {
            int r=(count+c-1)/c;
            int score=(c*r-count)*2+Math.abs(c-r);
            // <= 让同分时取更大的列数：面板宽一些更像真实按键板
            if(score<=bestScore) { bestScore=score; best=c; }
        }
        return best;
    }

    /**
     * 计算整块按键的排布参数。
     *
     * @param count 站点总数（可为 0，此时仍给出 1×1 的空网格，方便面板照常绘制）
     * @param maxColumns 列数上限（可用宽度）
     * @param maxRows 单页行数上限（可用高度）
     * @return 列数、单页行数、总行数与总页数
     */
    public static Grid grid(int count,int maxColumns,int maxRows) {
        int columns=chooseColumns(count,Math.max(1,maxColumns));
        int totalRows=Math.max(1,(Math.max(0,count)+columns-1)/columns);
        int rowsPerPage=Math.min(totalRows,Math.max(1,maxRows));
        int pageCount=Math.max(1,(totalRows+rowsPerPage-1)/rowsPerPage);
        return new Grid(columns,rowsPerPage,totalRows,pageCount);
    }

    /** @param grid 排布参数 @return 单页能放下的按钮格数（含可能空着的格子） */
    public static int capacity(Grid grid) { return grid.rowsPerPage()*grid.columns(); }

    /** @param grid 排布参数 @param page 页号（0 基） @return 该页第一个站点在整份列表里的下标（0 基） */
    public static int pageStart(Grid grid,int page) { return page*capacity(grid); }

    /**
     * 页内序号 → 从右数第几列（0 = 最右列）。
     *
     * @param indexInPage 页内序号：0 就是右下角那一个
     * @param columns 列数
     * @return 0..columns-1
     */
    public static int columnFromRight(int indexInPage,int columns) { return indexInPage%Math.max(1,columns); }

    /**
     * 页内序号 → 从下数第几行（0 = 最底行）。
     *
     * @param indexInPage 页内序号：0 就是右下角那一个
     * @param columns 列数
     * @return 0..rows-1
     */
    public static int rowFromBottom(int indexInPage,int columns) { return indexInPage/Math.max(1,columns); }
}
