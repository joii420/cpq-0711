package com.cpq.quotation.service.formula;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * task-0803：单元格 (行 × 列) 级依赖图 + Kahn 拓扑排序 + 环检测。
 *
 * <p><b>为什么求值单元要从「行」下沉到「单元格」</b>：{@code PGET} 需要父行先算（自上而下），
 * {@code CSUM} 族需要子行先算（自下而上），两个方向相反 —— 原来「逐行 × 行内列拓扑」的
 * 单遍求值不可能同时满足。把 (行, 列) 当节点后，两种方向只是图上不同朝向的边，
 * 一次拓扑排序即可统一处理。
 *
 * <p><b>环的处置</b>：环上 cell 不进 {@link Result#order()}，由调用方置 0 + 标错；
 * <b>环外 cell 照常求值</b>（不是整个页签炸掉）。列引用自己的自环同样算环。
 *
 * <p>边的方向约定：{@code addEdge(from..., to...)} 表示 <b>from 必须先于 to 求值</b>。
 */
public final class CellGraph {

    /** 一个求值单元：第 {@code row} 行的第 {@code col} 列。 */
    public record Cell(int row, int col) {}

    /** 拓扑结果：{@code order} 为可求值序列；{@code cycles} 为环上（排不出来的）cell。 */
    public record Result(List<Cell> order, Set<Cell> cycles) {}

    private final int rows;
    private final int cols;
    private final List<List<Integer>> out;   // 邻接表，元素是 cell 序号
    private final int[] indeg;

    public CellGraph(int rows, int cols) {
        this.rows = Math.max(0, rows);
        this.cols = Math.max(0, cols);
        int n = this.rows * this.cols;
        this.out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new ArrayList<>());
        }
        this.indeg = new int[n];
    }

    private int idx(int row, int col) {
        return row * cols + col;
    }

    private boolean valid(int r, int c) {
        return r >= 0 && r < rows && c >= 0 && c < cols;
    }

    /**
     * 加一条依赖边：{@code (fromRow,fromCol)} 必须先于 {@code (toRow,toCol)} 求值。
     * 任一端越界 → 静默忽略（调用方按业务语义收集边，越界表示该边不成立，如根行的 PGET）。
     */
    public void addEdge(int fromRow, int fromCol, int toRow, int toCol) {
        if (!valid(fromRow, fromCol) || !valid(toRow, toCol)) return;
        int f = idx(fromRow, fromCol);
        int t = idx(toRow, toCol);
        out.get(f).add(t);
        indeg[t]++;      // f == t（自环）时入度恒 >0，永远出不了队 → 被判为环
    }

    public Result topoOrder() {
        int n = rows * cols;
        if (n == 0) return new Result(List.of(), Set.of());

        int[] deg = indeg.clone();
        Deque<Integer> q = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            if (deg[i] == 0) q.add(i);
        }

        List<Cell> order = new ArrayList<>(n);
        boolean[] emitted = new boolean[n];
        while (!q.isEmpty()) {
            int cur = q.poll();
            emitted[cur] = true;
            order.add(new Cell(cur / cols, cur % cols));
            for (int nxt : out.get(cur)) {
                if (--deg[nxt] == 0) q.add(nxt);
            }
        }

        Set<Cell> cycles = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            if (!emitted[i]) cycles.add(new Cell(i / cols, i % cols));
        }
        return new Result(order, cycles);
    }
}
