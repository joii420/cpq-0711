// task-260904 F-8（AC-24）· 前端「页签是不是 BOM 树」的**唯一**判据实现。
//
// 背景：需求文档 §1.35「双判据并存」——后端 B-4 把判据收敛到 BomTreeRenderService 的一个方法里，
// 前端同理，5 处语义闸门（FieldConfigTable / FormulaBuilder / ComponentManagement /
// TabJoinFormulaDrawer / tabjoin/FormulaEditorPanel）一律调用本文件，🚫 不许再各写一份
// `tabType === 'BOM'`。
//
// 判据（与 §1.35 逐条对齐）：
//   ① 该组件**有数据源绑定**（component_sql_view.builder_config 非空）→ 按绑定数据源的
//      `semantic === 'TREE'` 判；此时 `component.tab_type` 一律不参与（AC-27③：
//      同时有 builder_config 与 tab_type='BOM' 时以 semantic 为准，不被历史值污染）。
//   ② 否则（存量组件，无 builder_config）→ 回退 `component.tab_type === 'BOM'`，
//      **行为与改动前逐字一致**（AC-24② / AC-25）。
//
// 🔑 `undefined` 与 `null` 在本文件里是**两个不同的语义**，不要互相替换：
//      undefined = 没有数据源绑定（走分支②回退）
//      null      = 有绑定，但该数据源没有特殊语义（普通平铺数据源，走分支①判为非树）
//    这正是 api.md §1.2 `semantic` 字段的三态（'TREE' / 'MATERIAL_ELEMENT' / null）加上
//    「未绑定」这一态。传参时若把「未绑定」写成 null，存量组件会被判成非树 —— 静默弄坏存量。

/** api.md §1.2：数据源语义三态。null = 普通平铺数据源（既不是树也不是材质元素）。 */
export type TabSemantic = 'TREE' | 'MATERIAL_ELEMENT' | null;

/**
 * 「该组件绑定的数据源语义」。`undefined` = 未绑定数据源（存量组件），与 `null` 严格区分，见文件头。
 * 前端 props 一律用这个别名，读到别名就该想起三态 + 未绑定态。
 */
export type BoundTabSemantic = TabSemantic | undefined;

/**
 * ⚠️ **临时映射，B-1 落地后应当删掉**。
 *
 * 正规来源是 `GET /config/semantic-graph/field-tree` 响应里 `availableSources[].semantic`
 * （api.md §1.2，后端 B-1 提供，属**第二批**）。第一批期间前端拿不到那份清单，只能从
 * `component_sql_view.builder_config.tabType`（= semantic_tab_view 的定位坐标之一）按
 * B-1 声明的同一条映射规则本地推导：`BOM 树 → TREE`、`材质元素 → MATERIAL_ELEMENT`、其余 → null。
 *
 * 🚨 两套词表并存（本次没有统一它，只是两个都认，避免误判）：
 *    · `semantic_tab_view.tab_type` / `FieldTreeBuilder:72` 用「BOM 树」
 *    · `component.tab_type` / `ComponentService.VALID_TAB_TYPES` / `SqlViewBuilderTab.TAB_TYPES` 用「BOM」
 *    详见回报里的「发现但没动的问题」。
 */
export function semanticFromTabType(tabType?: string | null): TabSemantic {
  if (!tabType) return null;
  if (tabType === 'BOM' || tabType === 'BOM 树') return 'TREE';
  if (tabType === '材质元素') return 'MATERIAL_ELEMENT';
  return null;
}

/**
 * §1.35 双判据：该页签是不是 BOM 树。
 *
 * @param boundSemantic 组件绑定数据源的语义；`undefined` = 未绑定（走分支②回退）
 * @param tabType       `component.tab_type`（存量口径，仅分支②读）
 */
export function isTreeTab(boundSemantic: BoundTabSemantic, tabType?: string | null): boolean {
  if (boundSemantic !== undefined) return boundSemantic === 'TREE';   // 分支①
  return tabType === 'BOM';                                          // 分支②（存量，逐字不变）
}

/** 该组件是否已绑定数据源（= 走分支①）。用于「未配页签类型也未绑数据源就放行」这类兜底判断。 */
export function hasSourceBinding(boundSemantic: BoundTabSemantic): boolean {
  return boundSemantic !== undefined;
}

/**
 * 闸门置灰/报错文案里那句「当前页签类型：X」的取值。
 * 存量组件仍显示 tab_type；已绑数据源但 tab_type 为空的新组件显示数据源语义，避免出现
 * 「未配置」这种对新组件不成立的说法。
 * 🚫 不返回数据源 label —— 那要等 B-1 的 `availableSources[].label`。
 */
export function tabSemanticLabel(boundSemantic: BoundTabSemantic, tabType?: string | null): string {
  // ⚠️ 有绑定时**一律看语义**，不看 tab_type —— 与 isTreeTab 分支①同口径。
  // 否则会出现「已按数据源判为非树、文案却写着『当前页签类型：BOM』」这种自相矛盾的提示
  // （AC-27③ 那种「有 builder_config 又有历史 tab_type='BOM'」的组件正好落在这一档）。
  if (boundSemantic === 'TREE') return 'BOM 树';
  if (boundSemantic === 'MATERIAL_ELEMENT') return '材质元素';
  if (boundSemantic === null) return '已绑数据源（非树）';
  return tabType || '未配置';
}
