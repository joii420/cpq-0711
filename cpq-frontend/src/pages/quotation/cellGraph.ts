/**
 * task-0803：单元格 (行 × 列) 级依赖图 + Kahn 拓扑排序 + 环检测。
 * 前端镜像后端 `com.cpq.quotation.service.formula.CellGraph`（逐位对齐，含环处置口径）。
 *
 * 为什么求值单元要从「行」下沉到「单元格」：`PGET` 需要父行先算（自上而下），`CSUM` 族
 * 需要子行先算（自下而上），两个方向相反 —— 原来「逐行 × 行内列拓扑」的单遍求值不可能
 * 同时满足。把 (行, 列) 当节点后，两种方向只是图上不同朝向的边，一次拓扑排序即可统一处理。
 *
 * 环的处置：环上 cell 不进 `order`，由调用方置 0 + 标错；环外 cell 照常求值（不是整页签炸掉）。
 * 列引用自己的自环同样算环。
 */
export interface Cell {
  row: number;
  col: number;
}

export interface CellGraphResult {
  order: Cell[];
  cycles: Cell[];
}

export class CellGraph {
  private readonly rows: number;
  private readonly cols: number;
  private readonly out: number[][];
  private readonly indeg: number[];

  constructor(rows: number, cols: number) {
    this.rows = Math.max(0, rows);
    this.cols = Math.max(0, cols);
    const n = this.rows * this.cols;
    this.out = Array.from({ length: n }, () => []);
    this.indeg = new Array(n).fill(0);
  }

  private idx(row: number, col: number): number {
    return row * this.cols + col;
  }

  private valid(r: number, c: number): boolean {
    return r >= 0 && r < this.rows && c >= 0 && c < this.cols;
  }

  /**
   * 加一条依赖边：(fromRow,fromCol) 必须先于 (toRow,toCol) 求值。
   * 任一端越界 → 静默忽略（调用方按业务语义收集边，越界表示该边不成立，如根行的 PGET）。
   */
  addEdge(fromRow: number, fromCol: number, toRow: number, toCol: number): void {
    if (!this.valid(fromRow, fromCol) || !this.valid(toRow, toCol)) return;
    const f = this.idx(fromRow, fromCol);
    const t = this.idx(toRow, toCol);
    this.out[f].push(t);
    this.indeg[t]++; // f === t（自环）时入度恒 >0，永远出不了队 → 被判为环
  }

  topoOrder(): CellGraphResult {
    const n = this.rows * this.cols;
    if (n === 0) return { order: [], cycles: [] };

    const deg = this.indeg.slice();
    const queue: number[] = [];
    for (let i = 0; i < n; i++) if (deg[i] === 0) queue.push(i);

    const order: Cell[] = [];
    const emitted = new Array(n).fill(false);
    let qi = 0;
    while (qi < queue.length) {
      const cur = queue[qi++];
      emitted[cur] = true;
      order.push({ row: Math.floor(cur / this.cols), col: cur % this.cols });
      for (const nxt of this.out[cur]) {
        if (--deg[nxt] === 0) queue.push(nxt);
      }
    }

    const cycles: Cell[] = [];
    for (let i = 0; i < n; i++) {
      if (!emitted[i]) cycles.push({ row: Math.floor(i / this.cols), col: i % this.cols });
    }
    return { order, cycles };
  }
}
