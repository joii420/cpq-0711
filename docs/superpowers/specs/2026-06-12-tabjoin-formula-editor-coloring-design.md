# 页签连表公式编辑器:配色统一 + 富文本原子块设计

- 日期:2026-06-12
- 范围:`TabJoinFormulaDrawer` 公式编辑体验(前端,两点同分支交付)
- 状态:设计已与用户确认,待 spec 评审

## 1. 背景与目标

`TabJoinFormulaDrawer`(配置页签连表公式抽屉)当前用一个 `Input.TextArea` 让用户编辑括号串表达式(如 `[投料.金额] * [加工.工时] + [回料(总计)]`),配合下方 `TabFieldMatrix` 字段矩阵点击插入引用。当前体验有两个缺口:

1. **矩阵 chip 配色语义不统一**:小计列与页签总计都是绿色,二者无法区分;普通可比明细是默认灰边,与"更细 source"明细(已蓝边)不一致。
2. **输入框是纯文本**:`[别名.字段]` 引用混在普通文本里,不可视、易写错别名/字段,且看不出某个引用是否有效。

目标:
- **第 1 点**:统一矩阵 chip 配色,让"明细 / 小计列 / 页签总计 / 不可比"四类一眼可辨。
- **第 2 点**:把输入框升级为富文本编辑器,`[...]` 引用渲染为**彩色原子块**,配色与矩阵同一套语义。

非目标(YAGNI):
- 不改 `expression` 字符串契约、不改保存/试算/`expressionToTokens` 任何后端或序列化逻辑。
- 不做语法自动补全/下拉联想。
- 不处理 `{path}` BNF 路径的富展示(罕见,按中性块兜底即可)。

## 2. 配色体系(单一事实源)

两点共用一套配色语义,统一由配色分类器产出:

| 类型 | 判定 | 矩阵 chip | 输入框原子块 |
|---|---|---|---|
| 明细字段(可点) | 字段 ∈ detailFields 且行键可比 | 🔵 蓝边 | 🔵 蓝底块 |
| 明细字段(不可比禁用) | 字段 ∈ detailFields 但行键不可比 | ⬜ 灰边(禁用) | 🔴 红底块 ※ |
| 小计列 | 字段 ∈ subtotalCols | 🟡 黄边 | 🟡 黄底块 |
| 页签总计 | `[别名(总计)]` | 🟢 绿边 | 🟢 绿底块 |
| 无效引用 | 别名/字段对不上 tabDefs | —— | 🔴 红底块 |

※ 设计决策(用户确认):**凡保存会被 `checkMappable` 拦下的引用一律红**。"不可比明细"在矩阵里是禁用灰 chip(无法点击插入),但用户仍可**手敲**进输入框 —— 此时它"能解析、但保存必失败",故输入框里归红,与"查不到"同色。判据统一为:**保存会失败 = 红**。

## 3. 配色分类器(`formulaSerialize.ts` 新增纯函数)

放在已有纯模块 `cpq-frontend/src/pages/component/formulaSerialize.ts`(无副作用、已有 `.test.ts` 覆盖),用 TDD 实现。复用现有 `findTabByRef` / `comparable` / `isSubset`,**与保存期 `expressionToTokens` + `checkMappable` 同源**,杜绝"显示色与保存结果分叉"。

> **同源纪律(评审收紧)**:判"保存会不会失败"的唯一权威是 `checkMappable`(`formulaSerialize.ts` 599-607),它拒绝的精确条件是 **`cross_tab_ref` 且 `match.length === 0`**。`match` 由 `buildMatch`(47-57)产出,**`comparable()` 与 `match 非空` 并不等价**:当 `selfRowKeyFields=[]` 时 `comparable([], X)` 返 `true`(空集⊆任何集),但 `buildMatch` 因 `!selfRowKeyFields?.length` 必返 `[]` → 保存仍失败。**故分类器判红必须镜像 `buildMatch`(match 是否空),不能用 `comparable`。**

### 3.1 类型

```ts
export type SegmentColor = 'blue' | 'yellow' | 'green' | 'red' | null;

export interface FormulaSegment {
  /** 原始片段文本。块:含括号的 "[...]"/"{...}";文本:运算符/数字/FN/括号等原样 */
  raw: string;
  /** true = 原子块([...] 或 {...});false = 普通可编辑文本 */
  isBlock: boolean;
  /** 块的展示文本(去外层方括号、去 (总计) → 用 · 连接);文本段等于 raw */
  display: string;
  /** 块的配色;文本段为 null */
  color: SegmentColor;
}
```

### 3.2 函数签名

```ts
// 把整串切成有序 Segment[](块 + 文本交替),供 FormulaRichInput 渲染
export function parseFormulaSegments(
  expr: string,
  tabDefs: TabDef[],
  selfRowKeyFields: string[] | undefined,
  /**
   * 是否强制 mappable 约束(评审点3)。
   *  - NORMAL/SUBTOTAL 组件:true —— 保存走 expressionToTokens + checkMappable,
   *    明细引用 match 空(含不可比 / selfRowKeyFields 空)→ 判红。
   *  - EXCEL 组件:false —— 保存走 buildColumn,不过 checkMappable,明细引用怎么写都能存,
   *    只要别名/字段在 tabDefs 解析得到就判蓝,绝不因不可比误标红。
   */
  enforceMappable: boolean,
): FormulaSegment[];

// 单个 [...] 内 body 的判色(parseFormulaSegments 内部调用,导出供单测)
export function classifyRefSegment(
  body: string,                 // 去掉外层方括号后、且已 trim 的内容,如 "投料.金额" / "回料(总计)"
  tabDefs: TabDef[],
  selfRowKeyFields: string[] | undefined,
  enforceMappable: boolean,
): { kind: string; color: SegmentColor };
```

### 3.3 切分规则

复用现有 `lex()` 的切分语义(`[...]`、`{...}`、运算符、数字、FN、括号),但**不抛错**:富文本渲染必须对"半截/非法"输入也能渲染(用户正在打字)。实现上 `parseFormulaSegments` 自行做一次宽容扫描:

- 遇 `[` 找下一个 `]`:成块。找不到 `]`(用户刚敲了 `[` 还没闭合)→ 该 `[` 及其后作为**文本段**,不成块(下次输入闭合后自然收块)。
- 遇 `{` 找 `}`:成中性块(`color: null`,展示去花括号)。未闭合同上作文本。
- 其余字符累积成文本段,直到遇到下一个 `[`/`{`。
- **空白对齐(评审点4)**:取出 `[...]` 的 body 后,**先 `body.trim()` 再传 `classifyRefSegment`**,与 `lex()` 内 `raw.body.trim()`(`formulaSerialize.ts` 369、244)保持一致,避免 `[投料. 金额]` 这类带空格输入造成"显示色 ≠ 保存结果"。

### 3.4 判色表(`classifyRefSegment`)

输入 body(已去外层 `[]` 且已 `trim`)。**按下表自上而下匹配,行序即优先级**(评审点1:`别名(总计)` 这类"无点但带 `(总计)`"必须排在 `self-field` 之前,否则会被误判蓝):

| 序 | body 形态 | 判定 | kind | color |
|---|---|---|---|---|
| 1 | 以 `(总计)` 结尾且**无点**:`别名(总计)` | 别名 ∈ tabDefs → 整页签小计(保存为 component_subtotal,无 match 约束,恒可存);否则查不到 | `tab-total` / `invalid` | 🟢 / 🔴 |
| 2 | 含点 `别名.字段`,字段 ∈ tabDef.subtotalCols(非聚合) | 小计列(保存为 component_subtotal,无 match 约束) | `subtotal` | 🟡 |
| 3 | 含点 `别名.字段` 或 `别名.字段(总计)`,字段 ∈ detailFields 且 **buildMatch 非空**(等价:可比且 selfRowKeyFields 非空) | 明细可用 | `detail` | 🔵 |
| 4 | 含点,字段 ∈ detailFields,但 `enforceMappable` 且 **buildMatch 空**(不可比 / selfRowKeyFields 空) | 保存必失败 | `invalid` | 🔴 |
| 4' | 同上但 `enforceMappable=false`(EXCEL 组件) | 解析得到即有效,不受 match 约束 | `detail` | 🔵 |
| 5 | 含点,但别名查不到 / 字段不在该 tab 任何字段 | 查不到(不论 enforceMappable) | `invalid` | 🔴 |
| 6 | 无点、无 `(总计)`:`字段`(宿主自身列) | tabDefs 无法证伪,视为有效 | `self-field` | 🔵 |

> **回显形态补充(评审点2/5)**:打开已存公式时 `tokensToDrawerExpression` 会把聚合明细回显为 **`SUM([别名.字段])`** 而非 `[别名.字段(总计)]`(`formulaSerialize.ts` 555-557);行级聚合回显为 **`SUM([宿主名.列] * [细名.列])`**(530-551)。这些 `SUM(`/`)`/` * ` 都在 `[...]` **之外**,被切为**普通文本段**,内层每个 `[...]` 块**独立**按上表判色(评审点8)。即判色器无需识别 FN 包裹,FN 名天然落文本段。

> **`display` vs `raw` 纪律(评审点7)**:判色(`classifyRefSegment` 的 `body.indexOf('.')` 分割)**始终用 raw body 里的英文 `.`**;`display` 字段仅供展示,用 `·` 替换 `.`。实现者**不得**拿 `display` 去 split 判色。
>
> 展示文本(`display`)约定:
> - `[别名(总计)]` → `别名(总计)`
> - `[别名.字段]` → `别名·字段`
> - `[别名.字段(总计)]` → `别名·字段(总计)`
> - `[字段]` → `字段`
> - 红色无效块也照常去括号展示原 body,便于用户看清自己敲错了什么。

### 3.5 单测要点(`formulaSerialize.test.ts`)

- 每条判色表行至少一例(蓝/黄/绿/红/self/中性各覆盖),含行序优先级用例(`[别名(总计)]` 不被误判 self-field)。
- 不可比明细(`enforceMappable=true`)→ 红(与 `checkMappable` 拒绝一致)。
- **`selfRowKeyFields=[]` + `enforceMappable=true` + `[别名.明细字段]` → 红**(镜像 buildMatch 空,评审点3)。
- **同一不可比明细,`enforceMappable=false`(EXCEL)→ 蓝**(不误报,评审点3)。
- 回显形态 `SUM([别名.明细])`:`SUM(`/`)` 为文本段、内层块判蓝(评审点2)。
- 未闭合 `[` / `{` → 不抛错,降级为文本段。
- 带空格 `[投料. 金额]`:body trim 后按 `投料.金额` 判色(评审点4)。
- 空串 → `[]`。
- 混合串切分顺序正确(`SUM([宿主.列] * [细.列])` → 文本`SUM(` + 块 + 文本` * ` + 块 + 文本`)`)。
- round-trip:`segments.map(s => s.raw).join('')` === 原 expr(无损还原,保证读回不丢字符)。

## 4. 第 1 点:`TabFieldMatrix.tsx` chip 重配色

纯样式改动,无逻辑变化,单文件。

| chip 类型 | 现状 | 改为 |
|---|---|---|
| 明细·普通可比 | 默认灰边(`background:#fff`,无 borderColor) | 蓝边 `#91caff`(与"更细 source"统一) |
| 明细·更细 source | 蓝边 `#91caff` | 不变 |
| 明细·不可比 | 灰(`#bfbfbf`/`#fafafa`/`#f0f0f0`) | 不变 |
| 小计列 | 绿(字 `#389e0d`、边 `#b7eb8f`、虚线) | 黄(字 `#fa8c16`、边 `#ffd591`、底 `#fff`、保留虚线) |
| 页签总计 | 绿(字 `#389e0d`、边 `#b7eb8f`、虚线) | 不变 |

改完后:小计列=黄、页签总计=绿,二者区分开;所有可点明细=蓝、不可点明细=灰。`onInsert` 行为完全不动。

## 5. 第 2 点:`FormulaRichInput`(受控 contentEditable)

### 5.1 组件契约

新文件 `cpq-frontend/src/pages/template/tabjoin/FormulaRichInput.tsx`。

```ts
interface FormulaRichInputProps {
  value: string;                       // 受控:expression 字符串(契约不变)
  onChange: (next: string) => void;    // DOM 读回的字符串
  tabDefs: TabDef[];
  selfRowKeyFields?: string[];
  enforceMappable: boolean;            // 由 componentType 推导:EXCEL→false,NORMAL/SUBTOTAL→true
  placeholder?: string;
}
export interface FormulaRichInputHandle {
  insertAtCursor: (text: string, caretOffsetFromEnd?: number) => void;
}
```

在 `TabJoinFormulaDrawer` 中替换第 294 行的 `Input.TextArea`;现有 `insertAtCursor`(371 行,基于 textarea selection)删除,改调组件暴露的 `insertAtCursor`。矩阵 `onInsert` 与 OPS/FUNCS 工具条按钮全部转走新 `insertAtCursor`。

接线要点(评审点5/3):
- **`exprRef` 类型迁移**:现 `useRef<any>(null)`(57 行)+ `exprRef.current?.resizableTextArea?.textArea`(88-89 行)取底层 textarea —— 改为 `useRef<FormulaRichInputHandle | null>(null)`,调用点统一 `exprRef.current?.insertAtCursor(...)`,删掉 textarea DOM 探取逻辑。
- **`enforceMappable` 推导**:在 Drawer 内由 `componentType === 'EXCEL' ? false : true` 算出,传入 `FormulaRichInput`(与 save 路径的 EXCEL/token 分叉一致,123-158 行)。

### 5.2 渲染(字符串 → DOM)

- `parseFormulaSegments(value, tabDefs, selfRowKeyFields, enforceMappable)` 得 `Segment[]`。
- 文本段 → 文本节点(可逐字编辑)。
- 块段 → `<span contentEditable={false} data-raw="[...]" class="fri-block fri-{color}">{display}</span>`,块两侧各放一个零宽不可见文本节点作光标锚点,保证能在块前后落光标。
- 配色 class:`fri-blue/yellow/green/red` + 中性块 `fri-neutral`。样式用 CSS-in-JS 或局部 `<style>`。

### 5.3 读回(DOM → 字符串)

`onInput` 时遍历编辑器子节点:文本节点取 `textContent`,块 span 取 `data-raw`,顺序拼接 → `onChange(joined)`。

### 5.4 插入(替换旧 `insertAtCursor`)

用 Selection/Range API:
- 光标在编辑器内 → 在当前 Range 处插入 text,折叠光标到插入末尾减 `caretOffsetFromEnd`(用于 `SUM()` 落在括号内)。
- 光标不在编辑器内(如刚点矩阵 chip)→ 追加到末尾。
- 插入后触发一次读回(等价 onChange)+ 重渲染收块。

### 5.5 块原子性与光标

- 块内不可逐字编(`contentEditable={false}`)。
- 退格:光标贴在块右边界时,删整块(拦截 `beforeinput`/`keydown` Backspace,移除该 span,读回)。
- 光标在块间移动靠零宽锚点 + 浏览器默认行为。

### 5.6 IME 防护(中文输入)

- `compositionstart` → 置 `composingRef=true`,**挂起**重解析/重渲染。
- `compositionend` → 置 false,读回字符串并重渲染收块。
- composing 期间 `onInput` 不触发 `onChange` 的重渲染(只更新底层字符串,DOM 不重建,避免打断输入法候选)。

### 5.7 已知风险(诚实告知 + 验收策略)

contentEditable 的 IME / 光标维护 / 粘贴(需强制纯文本)/ 原子块删除是业界已知坑,headless E2E 难以稳定驱动 contentEditable。缓解:
- `parseFormulaSegments` / `classifyRefSegment` 用 TDD 单测**最大化锁定可测逻辑**。
- `paste` 事件强制取 `text/plain` 注入,避免带样式 HTML 污染。
- contentEditable 交互(打字 / 中文 / 退格删块 / 点击矩阵插入 / 工具条插入 / 打开已存公式回显)**由用户真机验收**。

## 6. 交付流程与自检

- 按 CLAUDE.md:新功能走**隔离 worktree 分支**;两点同分支一起交付。
- 顺序:先 Point 1(快、低风险)→ 再分类器(TDD)→ 再 Point 2(组件 + 接线)。
- 自检清单:
  1. `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错。
  2. `formulaSerialize.test.ts` 全绿(含新增分类器用例)。
  3. 改动的 `.tsx`(`TabJoinFormulaDrawer` / `TabFieldMatrix` / 新 `FormulaRichInput`)逐个 `curl http://localhost:5174/src/...` → 200。
  4. `TabJoinFormulaDrawer` 属公式协议入口,按 CLAUDE.md 双 spec 兜底:`quotation-flow.spec.ts`(SIMPLE)+ `composite-product-flow.spec.ts`(COMPOSITE),确认未回归(评审点6)。
  5. contentEditable 交互真机验收(用户)。
- "完成"宣告附"已自检"声明行。

## 7. 影响面

- 改:`formulaSerialize.ts`(+纯函数)、`formulaSerialize.test.ts`(+用例)、`TabFieldMatrix.tsx`(配色)、`TabJoinFormulaDrawer.tsx`(替换输入框 + 接线)。
- 增:`FormulaRichInput.tsx`。
- 不改:任何后端、序列化主逻辑、`expression` 契约、保存/试算端点。
