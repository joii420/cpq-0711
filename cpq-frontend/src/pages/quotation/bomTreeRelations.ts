/**
 * task-0803：BOM 树页签的父子关系（按行下标表达，纯计算，前端镜像后端
 * `com.cpq.quotation.service.formula.TreeRelations`）。
 *
 * 🚨 父子边一律按 `nodeId` / `parentId` 认，禁止按料号：BOM 是 DAG，同一料号会挂在
 * 多个父件下（现网实例 3110520789 同挂两个父件），按料号认边必然串号。
 *
 * 与后端的一处不对称（刻意）：后端 `TreeRelations.of` 接收「完整 baseRows + 墓碑 id 集合」，
 * 自行按墓碑过滤存活行。前端调用方（QuotationStep2 / ReadonlyProductCard）在到达这里之前，
 * 墓碑行已经被 `buildSnapshotExpansions`/`keepRow` 从 `activeDriverExpansion.rows` 中物理剔除
 * （报价侧 AP-54 头号不变量），核价侧则从不产生墓碑（spec §3.7 隔离）。
 * 因此这里的输入天然只含"存活"行，无需再传墓碑集合——效果与后端逐位一致：
 *   - 某节点被物理移除 → 其子行 parentId 查 byNodeId 找不到 → 视为无父（同后端 `p==null`）
 *   - 后端"父存在但已墓碑"分支（`!alive[p]`）在前端天然不会出现（该行根本不在数组里）
 * 两条路径殊途同归：都是"parentId 指向的节点不在当前存活集合"→ 统一按"无父"处理（§11.5 G5）。
 */
export interface TreeRowRef {
  nodeId?: string | null;
  parentId?: string | null;
  lvl?: number | null;
}

export interface TreeRelations {
  size(): number;
  /** 直接父行下标；无父（根行 / 父不在行集中）→ -1。 */
  parentOf(i: number): number;
  /** 直接子行下标（不含孙辈）。 */
  childrenOf(i: number): number[];
  lvl(i: number): number;
  isRoot(i: number): boolean;
  isLeaf(i: number): boolean;
}

/** rows 里任意一行带非空 nodeId → 视为树页签行集（镜像后端 `TreeRelations.isTreeRows`）。 */
export function isTreeRows(rows: Array<TreeRowRef | undefined | null>): boolean {
  return rows.some((r) => r?.nodeId != null && r.nodeId !== '');
}

export function buildTreeRelations(rows: Array<TreeRowRef | undefined | null>): TreeRelations {
  const n = rows.length;
  const byNodeId = new Map<string, number>();
  for (let i = 0; i < n; i++) {
    const nid = rows[i]?.nodeId;
    if (nid != null && nid !== '') byNodeId.set(nid, i);
  }

  const parent: number[] = new Array(n).fill(-1);
  const children: number[][] = Array.from({ length: n }, () => []);
  for (let i = 0; i < n; i++) {
    const pid = rows[i]?.parentId;
    if (pid == null || pid === '') continue;
    const p = byNodeId.get(pid);
    if (p === undefined) continue; // 父不在存活行集中 → 视为无父（§11.5 G5）
    parent[i] = p;
    children[p].push(i);
  }

  return {
    size: () => n,
    parentOf: (i) => (i >= 0 && i < n ? parent[i] : -1),
    childrenOf: (i) => (i >= 0 && i < n ? children[i] : []),
    lvl: (i) => (i >= 0 && i < n ? (rows[i]?.lvl ?? 0) : 0),
    isRoot: (i) => (i >= 0 && i < n ? parent[i] < 0 : true),
    isLeaf: (i) => (i >= 0 && i < n ? children[i].length === 0 : true),
  };
}
