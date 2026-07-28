/**
 * 前端分页页签（材质 / 元素）的「列排序」共用实现（task-0728 · F3 / F4）。
 *
 * 为什么不用 antd 的默认（非受控）排序：
 *   非受控时组件拿不到「排序刚变了」这个事件（`SelectableTable` 没有透传 Table 的 onChange），
 *   而规范要求「排序变化后页码重置到第 1 页」（需求说明 §4.3）。
 *   因此这里改为 **受控排序**：列上显式给 `sortOrder`，点击表头由本模块的三态机推进。
 *   受控判定见 antd `table/hooks/useSorter.js#collectSortStates`：列里只要 `'sortOrder' in column`
 *   就完全以列上的值为准，内部 state 被忽略——所以三态由我们自己算，antd 只负责画箭头 + 排数据。
 *
 * 三态循环与 antd 原生一致（`nextSortDirection(['ascend','descend'], cur)`）：
 *   未排序 → 升序 → 降序 → 取消（回 dataSource 原序 = 后端默认序）。
 *   点另一列时该列直接进入升序（单列排序，旧列复位）。
 *
 * 空值（null / undefined / ''）**升降序都排在最后**：
 *   antd 在降序时会把比较结果取反（`getSortData`: `sortOrder === 'ascend' ? r : -r`），
 *   所以空值分支要按当前 order 预先取反才能"恒在后"。order 由 antd 作为比较函数第 3 个参数传入。
 */
import type { ColumnType } from 'antd/es/table';
import type { SortOrder } from 'antd/es/table/interface';

/** 当前排序状态：key=null 表示未排序（回后端默认序） */
export interface ClientSortState {
  key: string | null;
  order: SortOrder;
}

/** 初始 / 取消态 */
export const NO_SORT: ClientSortState = { key: null, order: null };

/** 值类型：文本 localeCompare / 数值相减 / 时间按原始 ISO 串解析（**不要**用格式化后的展示串） */
export type ClientSortKind = 'text' | 'number' | 'time';

const isEmptyValue = (v: unknown): boolean => v === null || v === undefined || v === '';

/**
 * 三态推进：同列 升→降→取消；换列直接升序。
 */
export function nextClientSort(prev: ClientSortState, key: string): ClientSortState {
  if (prev.key !== key) return { key, order: 'ascend' };
  if (prev.order === 'ascend') return { key, order: 'descend' };
  return { key: null, order: null };
}

/**
 * 生成 antd 列比较函数。第 3 个参数 order 由 antd 传入（见 useSorter#getSortData）。
 */
export function makeClientComparator<T>(get: (r: T) => unknown, kind: ClientSortKind) {
  return (a: T, b: T, order?: SortOrder): number => {
    const va = get(a);
    const vb = get(b);
    const ea = isEmptyValue(va);
    const eb = isEmptyValue(vb);
    if (ea && eb) return 0;
    // 抵消 antd 降序时的取反 → 空值升降序都落在最后
    const emptyRank = order === 'descend' ? -1 : 1;
    if (ea) return emptyRank;
    if (eb) return -emptyRank;

    if (kind === 'number') {
      const na = Number(va);
      const nb = Number(vb);
      if (Number.isNaN(na) || Number.isNaN(nb)) return String(va).localeCompare(String(vb), 'zh-Hans-CN');
      return na - nb;
    }
    if (kind === 'time') {
      const ta = Date.parse(String(va));
      const tb = Date.parse(String(vb));
      // 非法时间串退化为字面比较（ISO 串字面序即时间序）
      if (Number.isNaN(ta) || Number.isNaN(tb)) return String(va).localeCompare(String(vb));
      return ta - tb;
    }
    return String(va).localeCompare(String(vb), 'zh-Hans-CN');
  };
}

/**
 * 一列所需的排序三件套：比较函数 + 受控 sortOrder + 表头点击/回车（键盘可达）。
 *
 * 用法：
 *   const [sort, setSort] = useState<ClientSortState>(NO_SORT);
 *   const cycle = (k: string) => { setSort(p => nextClientSort(p, k)); setPage(1); };
 *   { title:'名称', dataIndex:'name', key:'name',
 *     ...clientSortProps<Row>('name', sort, cycle, r => r.name, 'text') }
 *
 * 注：antd 会把本函数返回的 onClick / onKeyDown 包一层（自己的 triggerSorter 先跑、我们的后跑，
 * onKeyDown 仅在 Enter 时透传）。受控模式下 antd 的内部 state 不生效，实际以本模块的 state 为准。
 */
export function clientSortProps<T>(
  key: string,
  state: ClientSortState,
  onCycle: (key: string) => void,
  get: (r: T) => unknown,
  kind: ClientSortKind,
): Pick<ColumnType<T>, 'sorter' | 'sortOrder' | 'onHeaderCell'> {
  return {
    sorter: makeClientComparator<T>(get, kind),
    sortOrder: state.key === key ? state.order : null,
    onHeaderCell: () => ({
      onClick: () => onCycle(key),
      onKeyDown: () => onCycle(key), // antd 只在 Enter 时调用
    }),
  };
}
