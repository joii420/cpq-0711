# 前端任务文档 · task-0801 页签连表公式配置优化

> 版本：v1.0 / 2026-08-01 · 技术总监下发
> 需求基线：同目录 `需求说明.md`（**必读，尤其 §11 澄清纪要**）+ `原型-连表公式抽屉-v1.html`（视觉定稿，可交互）
> 接口契约：同目录 `api.md`

---

## 0. 阅前须知（30 秒）

- 本任务**纯前端**，后端零改动。你的 PR 里若出现 `cpq-backend/` 下的 diff = 越界。
- 视觉与数值**以原型为准**；原型未覆盖或与本文冲突的技术细节**以本文为准**（原型有 3 处演示级简化，照抄会出 bug，见 §3.2）。
- 本抽屉的**唯一入口是「组件管理」页**（不是模板管理），且服务 **NORMAL / EXCEL / SUBTOTAL 三种组件类型**。
- 改动**不触发 AP-44**（不涉及 `field_type` 变动），改动文件也不在 CLAUDE.md「协议级改动必跑 E2E」清单内 —— 但本任务**自带一个专项 E2E 要求**（Task F6），不是可选项。

---

## 1. 目标（4 点定稿 + 3 项澄清增补）

| # | 内容 | 出处 |
|---|---|---|
| 1 | 抽屉改左右分栏（左 42% 字段面板 / 右 58% 公式配置），两栏各自滚动 | 原型 |
| 2 | 移除试算功能（含 `SampleCardPicker` 一并删除） | 原型 + 澄清 C1 |
| 3 | 公式框 `minHeight 52 → 170`、`maxHeight 340`、超出内部滚动 | 原型 |
| 4 | 括号配对可视化：深度着色 + 光标处配对高亮 + 未闭合标红 | 原型 |
| 5 | 🆕 左栏顶部加搜索框（匹配页签名 + 字段名） | 澄清 C2 |
| 6 | 🆕 抽屉宽度 `min(1520px, 92vw)` 自适应 | 澄清 C3 |
| 7 | 🆕 抽屉文件拆分：左右两栏各抽子组件 | 澄清 C6 |

---

## 2. 现状代码地图（动手前先对照读一遍）

| 文件 | 行数 | 现状职责 | 本次 |
|---|---|---|---|
| `src/pages/template/TabJoinFormulaDrawer.tsx` | 925 | Drawer 主体：状态、试算、公式框、工具条、规则提示、SUMIF 构造器、字段矩阵 | 主改 + 拆分 |
| `src/pages/template/tabjoin/TabFieldMatrix.tsx` | 252 | 页签字段矩阵（**左右结构**：160px 固定页签名列 + chip 区） | 改为上下卡片结构 |
| `src/pages/template/tabjoin/FormulaRichInput.tsx` | 234 | contentEditable 富文本公式框（块渲染 + 光标维护） | 高度 + 括号着色 + 光标修正 |
| `src/pages/template/tabjoin/formulaBracketCheck.ts` | 52 | `checkParenBalance(expr)` 圆括号平衡校验 | 抽 `scanParens` |
| `src/pages/template/tabjoin/SampleCardPicker.tsx` | 56 | 样本卡下拉（仅试算用） | 🗑️ 删除 |
| `src/services/tabJoinFormulaService.ts` | 65 | 4 个方法 | 删 3 个（见 `api.md` §4） |
| `src/pages/component/ComponentManagement.tsx:1789` | — | **唯一调用方**（宿主页） | 不改（除非 props 变） |

**关键既有约定（不得破坏）**：

1. `FormulaRichInput` 的 `onChange` 契约：父组件**不得**对值做 trim/normalize，必须原样回写 —— 否则 `lastEmittedRef` 比对失效，打字中光标会丢（组件内 `:109-115` 有注释）。
2. `readBack()` 递归、`caretOffset()` 用 `cloneContents + readBack` 计偏移 —— 三者（`readBack` / `caretOffset` / `restoreCaret`）**必须口径一致**，这是本次最大风险点。
3. `TabFieldMatrix.tsx` 导出的 `tabComparable()` 被 `tabFieldMatrix.test.ts` 依赖，**保持同路径导出**。
4. `TabJoinFormulaDrawer.tsx` 导出的 `buildSumifToken` / `buildSumifText` 被 `__tests__/sumifTokenBuild.test.ts` 依赖，**保持同路径导出**。
5. 引用块 `[...]` 的 5 色语义（蓝/黄/绿/紫/红）**不动**，红 = 非法引用是最重要的提示。

---

## 3. 全局红线

### 3.1 绝对不做

- ❌ 不改公式语义、不改 token 序列化/反序列化（`formulaSerialize.ts` **只读复用**）
- ❌ 不改保存按钮的禁用口径（仍由 `checkParenBalance` 决定）
- ❌ 不改 SUMIF 构造器**内部表单结构**（只调整它在版面中的位置）
- ❌ 不引入第三方编辑器库（CodeMirror / Monaco 等）
- ❌ 不把 SUMIF 构造器抽成独立文件（澄清 C6 明确否决：与主体十几个状态耦合，回归风险 > 收益）
- ❌ 不改后端、不加 Flyway 迁移

### 3.2 原型的 3 处演示级简化（照抄必出 bug）

| 风险 | 原型的做法 | 你必须的做法 |
|---|---|---|
| **R1 光标错位** | 沿用现状 `restoreCaret`，把元素节点一律当"带 `data-raw` 的块"，新括号 span 长度按 0 计 | **改递归**，规则见 Task F2 §2 |
| **R2 扫描口径** | 着色扫描只跳过 `[...]`，**没跳 `{...}`** | 与 `checkParenBalance` **同源**：抽 `scanParens` 一处维护两处消费（Task F1） |
| **R3 括号可编辑性** | — | 括号 span **绝不能**设 `contenteditable="false"`（否则删不掉），也**绝不能**加 `data-raw`（会被退格删块逻辑误判为块） |

---

## 4. 任务拆分

> 建议顺序 **F1 → F2 → F3 → F4 → F5 → F6**。F1/F3 互不依赖可并行；F5 依赖 F3+F4。
> 每完成一个任务跑一次 §5 自检，不要攒到最后。

---

### Task F1 · 抽出 `scanParens` 共享扫描（纯函数层，零 UI 风险）

**文件**：`tabjoin/formulaBracketCheck.ts`、`tabjoin/formulaBracketCheck.test.ts`

**新增导出**（签名固定，F2 会消费）：

```ts
export interface ParenInfo {
  /** 该括号字符在原始表达式串中的下标 */
  index: number;
  /** '(' 或 ')' */
  ch: '(' | ')';
  /** 嵌套深度，最外层 = 0；着色用 depth % 4 取色 */
  depth: number;
  /** 配对括号的 index；未配对时为 null */
  matchIndex: number | null;
  /** true = 未闭合的 '(' 或无匹配的 ')' */
  error: boolean;
}

/**
 * 扫描表达式中的**分组圆括号**（跳过 [...] 与 {...} 块内的圆括号）。
 * 返回按 index 升序的信息数组。纯函数，无副作用。
 */
export function scanParens(expr: string): ParenInfo[];
```

**实现要点**：

- 跳过规则与现有 `checkParenBalance:27-37` **逐字一致**：遇 `[` → `indexOf(']')`，遇 `{` → `indexOf('}')`，未闭合则跳到串尾。
- 用栈配对：遇 `(` 压栈（depth = 栈深）；遇 `)` 弹栈配对，栈空则该 `)` 标 `error: true`（depth 记 0）；扫完后栈中残留的 `(` 全标 `error: true`。
- **`checkParenBalance` 改为消费 `scanParens`**，但**对外行为逐字不变**：错误文案必须仍是现有的两句原文（`'括号不匹配：多了 1 个右括号 ")"（出现无匹配的右括号）'` / `` `括号不匹配：缺少 ${n} 个右括号 ")"` ``）—— 保存按钮 Tooltip 直接显示它，改文案 = 改行为。

**单测**（`formulaBracketCheck.test.ts` 补充，AC-21）：

| 用例 | 输入 | 期望 |
|---|---|---|
| 正常嵌套 | `((1+2)*3)` | 4 个括号，depth 依次 0/1/1/0，两两配对，`error` 全 false |
| 4 层以上循环 | `((((1))))` | depth 0/1/2/3/3/2/1/0（着色侧 `%4` 后循环） |
| 未闭合 | `SUM([投料.金额]` | 该 `(` `error: true`，`checkParenBalance().ok === false` |
| 多余右括号 | `1+2)` | 该 `)` `error: true` |
| 🔑 `(总计)` 不计数 | `[回料(总计)] + (1)` | **只返 2 个**括号（`(1)` 的），`ok === true` |
| 🔑 `{}` 内不计数 | `{a(b)} + (1)` | 只返 2 个，`ok === true` |
| 空串 / 无括号 | `''` / `[投料.金额]` | 返 `[]`，`ok === true` |

**完成判据**：`npx vitest run src/pages/template/tabjoin/formulaBracketCheck.test.ts` 全绿，且**现有用例一个不改**（原有 7 个断言必须原样通过）。

---

### Task F2 · `FormulaRichInput`：高度 + 括号着色 + 光标修正

**文件**：`tabjoin/FormulaRichInput.tsx`

#### 2.1 容器高度

```
minHeight: 52 → 170
maxHeight: 340（新增）
overflowY: 'auto'（新增）
```
其余样式不动（contentEditable 本身随内容增高，**不要**加 JS 测高）。

#### 2.2 🔑 `restoreCaret` 改递归（AC-12 的唯一正确解）

**现状缺陷**（已实证）：`:83-94` 对元素节点一律 `getAttribute('data-raw') ?? ''` 计长度、且**不下降**。新增的括号 span 没有 `data-raw` → 长度按 0 计 → 偏移全错。
实证：公式 `SUM([投料.金额])`，DOM = 文本`"SUM"` + span`"("` + 块`[投料.金额]` + span`")"`，`offset=4`（应落在 `(` 之后）→ 现状算法把光标放到**整个块之后**。用户表现：在 `(` 后打字，字符跑到块后面去。

**改法**（必须与 `readBack` 完全对齐）：

```
遍历节点：
  TEXT_NODE                    → 按 textContent.length 计；offset 落在区间内则 setStart(node, offset-acc) 返回
  ELEMENT 且 有 data-raw        → 【原子块】按 data-raw.length 整体跳过，不进入内部；
                                  offset <= acc 时 setStartBefore(node) 返回
  ELEMENT 且 无 data-raw        → 【括号 span / 其他 wrapper】递归下降处理其子节点
  BR                           → 忽略（与 readBack 一致）
```

建议实现为返回 `boolean`（是否已定位）的递归函数 + 外层游标对象携带 `acc`，避免闭包写错。全部遍历完仍未命中 → 落到末尾（保持现状兜底逻辑）。

> ⚠️ **禁止**采用「给括号 span 也加 `data-raw`」这条捷径 —— 它会让 `handleKeyDown:175` 的退格删块判定（`prev.getAttribute('data-raw') != null`）把括号当成块整体删掉。已在澄清纪要中否决。

#### 2.3 括号渲染（内容驱动通道）

在 `renderInto` 里，对**非块的文本段**（`!s.isBlock`）逐字符处理：

- 先 `const parens = scanParens(str)` 得到全串的括号信息（注意：`scanParens` 吃的是**完整表达式串**，不是单个 segment —— 需要维护 segment 在全串中的起始偏移，用「全串下标」去匹配 `ParenInfo.index`）。
- 该字符是括号且命中 `ParenInfo` → 包一个 `<span>`：
  ```
  class = 'par p{depth % 4}' + (error ? ' parErr' : '')
  data-paren-idx = String(info.index)
  textContent = 该括号字符
  ```
  **不设** `contenteditable`，**不设** `data-raw`。
- 非括号字符 → 仍走文本节点（可把连续非括号字符合并成一个文本节点，减少节点数）。

**样式**（取自原型，可内联或用 `<style>` 注入，二选一自行决定，但要保证不污染全局）：

| class | 样式 |
|---|---|
| `.par` | `font-weight: 800` |
| `.p0` / `.p1` / `.p2` / `.p3` | `color: #d4820a` / `#7d3ac1` / `#0a9396` / `#c2185b` |
| `.parErr` | `color: #cf1322; text-decoration: wavy underline #cf1322; text-underline-offset: 3px` |
| `.parHit`（配对高亮） | `background: #fff3cd; border-radius: 3px` |

#### 2.4 🔑 配对高亮（光标驱动通道）—— 绝不重建 DOM

- 监听 `selectionchange`（document 级，需在 effect 里注册并在卸载时移除）+ 组件的 `keyup` / `click` / `focus`。
- 处理逻辑：
  1. 若正在 composition（`composingRef.current === true`）→ **直接 return**（AC-13）。
  2. 取当前 selection，判断光标是否**紧邻**某个 `[data-paren-idx]` span（该 span 在光标左侧或右侧，含光标落在 span 内部文本的首/尾）。
  3. 先 `querySelectorAll('[data-paren-idx]')` 清掉所有 `parHit`，再给命中的 span 及其 `matchIndex` 对应的 span 加上 `parHit`。
- **只操作 class/style，不碰 `innerHTML`、不调 `renderInto`、不 `onChange`。** 违反此条 → 中文输入法被打断 + 光标丢失，AC-12/AC-13 必挂。
- 为避免每次高亮都重扫，`scanParens` 结果可随 `renderInto` 缓存到 ref（key = 当前串），高亮时直接查。

**完成判据**：手动在浏览器里验证 —— ① 输入 `((((1))))` 见 4 色循环；② 方向键把光标停在括号旁，配对两半同时黄底；③ 输入 `SUM([投料.金额]` 见红色波浪线且保存禁用；④ **在 `(` 后连打 10 个字符，字符按序落在正确位置**；⑤ 切中文输入法连续打字不被打断。

---

### Task F3 · 移除试算 + 删除死代码

**文件**：`TabJoinFormulaDrawer.tsx`、`tabjoin/SampleCardPicker.tsx`（删）、`services/tabJoinFormulaService.ts`

**删除清单**（逐项勾掉）：

- [ ] 顶部试算条容器（`:514-573` 那整块 `background:#f0f5ff`）
- [ ] `<SampleCardPicker>` 使用与 `import`（`:10`、`:518`）
- [ ] 「试算」按钮 + EXCEL 单值结果 + 错误显示 + 逐行结果 `<Table>` + 「试算无行」提示
- [ ] `runDryRun()` 函数（`:442-490`）
- [ ] 状态：`sampleLi` / `dryRunValue` / `dryRunRows` / `dryRunErrors` / `dryRunLoading`（`:201-205`）
- [ ] 因上述删除而不再使用的 `import`（`Table` 等 —— 以 `tsc` 报错为准，不要凭感觉删）
- [ ] 文件 `tabjoin/SampleCardPicker.tsx`（`git rm`）
- [ ] `services/tabJoinFormulaService.ts` 中：`interface SampleCard`、`sampleCardsByComponent`、`dryRunByComponent`、`dryRunToken`（详见 `api.md` §4；**保留** `TabDef` 与 `tabDefsByComponent`）

**完成判据**（AC-5）：

```bash
/usr/bin/grep -n "试算\|dryRun\|SampleCard" cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx   # 期望零命中
/usr/bin/grep -rn "sampleCardsByComponent\|dryRunByComponent\|dryRunToken\|SampleCardPicker" cpq-frontend/src  # 期望零命中
```

> 后端端点**保留不动**，不要顺手去删 java。恢复试算的路径 = 从 git 历史取回本次删除的前端文件。

---

### Task F4 · 左栏：`TabFieldPanel` + 搜索 + 卡片改上下结构

**文件**：🆕 `tabjoin/TabFieldPanel.tsx`、`tabjoin/TabFieldMatrix.tsx`

#### 4.1 `TabFieldPanel.tsx`（新建）

```tsx
interface Props {
  tabDefs: TabDef[];
  selfRowKeyFields?: string[];
  onInsert: (token: string) => void;
}
```

组成：**标题行**（「页签组件与可选字段」+ 副标题「点击插入到右侧公式 · 行键不可比的明细置灰」）→ **搜索框** → **宿主行键状态条** → `<TabFieldMatrix tabDefs={filtered} … />`。

**搜索规格（AC-16 / AC-17）**：

| 项 | 规格 |
|---|---|
| 控件 | antd `Input`，`allowClear`，`placeholder="搜索页签或字段名"`，`size="small"` |
| 匹配 | 关键词 `trim().toLowerCase()`；与 `componentName` / `alias` / 各 chip 名做 `includes` |
| 规则① 命中页签名 | 整卡保留，**所有 chip 都在** |
| 规则② 仅命中字段名 | 该卡保留，`detailFields` / `subtotalCols` **只留命中项** |
| 规则③ 都不命中 | 整卡隐藏 |
| 「页签总计」chip | 规则②下**始终保留**（整页签级引用，与字段名无关） |
| 空结果 | 渲染「无匹配的页签或字段」占位（12px 灰字），不要空白 |
| 置灰态 | **不受搜索影响**：命中的不可比明细 chip 仍置灰、仍带原 Tooltip 文案 |
| 性能 | `useMemo` 过滤；**纯前端，不发任何请求**；不得修改传入的 `tabDefs` 数组（返回新副本） |

> 插入 token 的引用名口径**不得因搜索改变**：仍是 `def.componentName || def.alias`（AC-17）。

#### 4.2 `TabFieldMatrix.tsx`（改排布）

- **左右结构 → 上下卡片结构**（原型 `.tab` / `.thead` / `.tbody`）：
  - 卡片：`border:1px solid #eef0f2; border-radius:8px; margin-bottom:10px; overflow:hidden`
  - 头部 `.thead`：`display:flex; justify-content:space-between; padding:8px 11px; background:#fafbfc; border-bottom:1px solid #eef0f2` —— 左放页签名（`font-weight:600; font-size:13px`，别名不同时补 `[alias]` 小字），右放行键徽标（`font-size:11px; color:#5b6b7c; background:#eef4ff; border:1px solid #d6e4ff; border-radius:10px; padding:1px 8px`）
  - 体部 `.tbody`：`padding:9px 11px; display:flex; flex-wrap:wrap; gap:7px` —— 明细 / 小计列 / 页签总计三组，组间 `margin-left:8px; padding-left:10px; border-left:1px dashed #e5e7eb`
  - 不可比页签整卡底色 `#fafafa`（沿用现状语义）
- **chip 的点击行为、置灰判定、Tooltip 文案、插入 token 格式全部不变**（这是行为不变式，只改排布）。
- 「清空表达式」按钮**移出**本组件 → 到右栏（Task F5）。相应地 `onClearExpression` prop 从 `TabFieldMatrix` 移除。
- `tabComparable()` **保持同路径导出**（`tabFieldMatrix.test.ts` 依赖）。

**完成判据**：`npx vitest run src/pages/template/tabjoin/tabFieldMatrix.test.ts` 全绿；用**组件目录「核价模板」**（同目录 18 个 ACTIVE 组件，本库最大目录）目测卡片不再挤压长字段名。

> ⚠️ **测试数据口径（2026-08-01 更正，见需求说明 §11.1 F3）**：本抽屉左栏页签集 = `ComponentTabDefService.tabDefsForComponent` 按 **`directoryId` 取同目录 ACTIVE 组件**，**与模板无关**。任务书初稿引用的「核价模板1 有 35 页签」是模板级口径，**作废**。验证一律用**组件目录**维度：核价模板 18 / 施耐德-1-BUG 11 / 施耐德-1 8 / 罗克韦尔 7。

---

### Task F5 · 右栏 `FormulaEditorPanel` + Drawer 两栏布局

**文件**：🆕 `tabjoin/FormulaEditorPanel.tsx`、`TabJoinFormulaDrawer.tsx`

#### 5.1 `FormulaEditorPanel.tsx`（新建）

收纳（自上而下，顺序同原型）：

1. **标题行**：左「公式表达式」+ 列来源副标题；右**括号状态**（`● 括号匹配` 绿 `#389e0d` / `● 括号不匹配` 红 `#cf1322`，空表达式时不显示）+ 「清空表达式」按钮（从左栏移来）
2. `<FormulaRichInput>`（`ref` 由 Drawer 持有并透传）
3. `!parenCheck.ok` 时的红字错误（保留现状）
4. **图例两行**：第一行「块底色 = 引用语义」（蓝 普通引用 / 黄 小计 / 绿 总计 / 紫 本页签 / 红 非法）；第二行「括号字色 = 配对深度」（4 个色块）+ 「光标停在括号上 → 同一对加黄底」
5. **工具条**：运算符 / 函数 / 条件聚合（现状逻辑原样搬，含 EXCEL 与组件线的分支行为）
6. **规则提示**（黄块，原样搬）

props 建议：`{ expression, onChange, tabDefs, selfRowKeyFields, enforceMappable, componentType, parenCheck, inputRef, onInsert, onClearExpression, onOpenSumif }`。**不在此组件里持有 expression 状态**（状态仍归 Drawer）。

#### 5.2 `TabJoinFormulaDrawer.tsx`（布局 + 瘦身）

- `width={'min(1520px, 92vw)'}`（AC-18；antd `Drawer.width` 接受字符串 CSS 值）
- body 内两栏容器：
  ```
  display: grid
  gridTemplateColumns: minmax(430px, 42fr) minmax(520px, 58fr)
  ```
  左栏 `borderRight: 1px solid #f0f0f0; padding: 14px 16px; overflow: auto; maxHeight: 78vh`
  右栏 `padding: 14px 16px; overflow: auto; maxHeight: 78vh`
- **窄屏降级**：视口 `< 1100px` 时改单栏（`gridTemplateColumns: '1fr'`）。用 `window.matchMedia('(max-width: 1100px)')` + `useState` 实现（记得在卸载时 `removeEventListener`）；不要依赖全局 CSS 文件。
- SUMIF 折叠区留在**右栏底部**（`componentType !== 'EXCEL'` 时才渲染），内部表单结构**一行不改**。
- 保留在 Drawer 的：全部状态、`buildColumn` / `save` / SUMIF 相关逻辑与导出、`exprRef`。
- 目标行数：Drawer ≈ 400 行；若明显超出，说明该搬的没搬干净。

**完成判据**：三种组件类型各打开一次抽屉无报错（AC-19）；1920 / 1366 / 1000 三档窗口宽度目测符合 AC-18。

---

### Task F6 · E2E spec + 全量自检

**文件**：🆕 `cpq-frontend/e2e/tabjoin-formula-drawer.spec.ts`

**覆盖**（AC-22，三条断言即可，不求全）：

1. **AC-3 光标处插入**：打开抽屉 → 在公式框输入 `1+2` → 把光标移到中间（如 `1+` 之后）→ 点左栏某个可点 chip → 断言表达式串为 `1+[XX.YY]2` 形态（**token 落在光标处，不是末尾**）。
2. **AC-8 配对高亮**：输入 `((1))` → 光标停在某个括号旁 → 断言恰有 **2 个** 元素带 `parHit`（或对应背景色）。
3. **AC-12 连打不错位**：输入 `SUM(` → 点 chip 插入引用 → 把光标移到 `(` 之后 → 连打 10 个字符 → 断言这 10 个字符**连续且有序**出现在 `(` 之后、引用块之前。

**环境要点（务必照做，否则测的不是你的代码）**：

- 登录：`admin` / `Admin@2026`。**已知坑**：E2E 反复跑可能把 admin 置为 `INACTIVE`，登录失败时用 SQL 改回 `ACTIVE` 再跑。
- 入口路径：组件管理页 → 选一个 **NORMAL** 组件 → 公式列表 → 点「编辑/添加」开抽屉。选择器约定见 `docs/E2E测试方法.md`。
- 本 spec **不依赖报价单夹具**，因此不受 BL-0078「E2E 夹具集体失效」影响 —— 若你发现它依赖了某张具体报价单，说明入口走错了。
- 运行：`npx playwright test --config=e2e/playwright.config.ts e2e/tabjoin-formula-drawer.spec.ts --reporter=list`

**完成判据**：`passed`，并在交付时贴出输出。

---

## 5. 强制自检（每个任务结束都跑，最终交付必须全绿）

```bash
# ① TypeScript：必须 0 错误
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json

# ② Vite transform：每个改动/新建的 .tsx 都要 200
#    ⚠️ worktree 陷阱：5174 上跑的是【主工作区】的代码，用它验证 worktree 的改动会得到假绿！
#    在 worktree 里必须：软链 node_modules → 用另一个端口起临时 vite → 对临时端口 curl
#    （见记忆教训 cpq-worktree-frontend-selfcheck；验完记得关掉临时 server）
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' \
  http://localhost:<临时端口>/src/pages/template/TabJoinFormulaDrawer.tsx
# 对 TabFieldPanel.tsx / FormulaEditorPanel.tsx / TabFieldMatrix.tsx / FormulaRichInput.tsx 重复

# ③ 单测全绿（含既有用例，一个都不能挂）
#    ⚠️ 必须带上 formulaSerialize.test.ts —— 它在 src/pages/component/ 下，不在 template 目录里，
#       但它是本次改动的强相关回归面（公式串解析）。只跑 src/pages/template/ 会漏掉 182 条断言。
npx vitest run src/pages/template/ src/pages/component/formulaSerialize.test.ts
#    基线（技术总监实测）：批 1（F1+F3）完成后 = 6 files / 248 tests 全绿。只能升不能降。

# ④ 死代码清零
/usr/bin/grep -rn "sampleCardsByComponent\|dryRunByComponent\|dryRunToken\|SampleCardPicker\|试算" cpq-frontend/src/pages/template cpq-frontend/src/services

# ⑤ E2E
npx playwright test --config=e2e/playwright.config.ts e2e/tabjoin-formula-drawer.spec.ts --reporter=list
```

> ⚠️ 本环境 `grep` = `ugrep -I`，对中文注释多的大文件会**静默返空**。凡据 grep 空结果下「无残留」结论，必须用 `/usr/bin/grep -a` 复核。
> ⚠️ **不要**在 worktree 里另起后端 dev server 或重装 `node_modules` —— server/DB/依赖是全会话共享的。

**「完成」宣告必须附带一行自检声明**，例如：
> "TS 0 错误 ✅；5 个 .tsx → Vite 200 ✅；vitest 全绿（含新增 7 个 scanParens 用例）✅；死代码 grep 零命中 ✅；E2E 1 passed ✅"

没有这行 = 未完成。

---

## 6. 提交纪律

- 分支：技术总监已建的 worktree 特性分支（见交接说明），**不要**在主工作区或 master 上改。
- 提交只 `git add` 本次明确改动的文件，**严禁 `git add -A`**（多会话并发会夹带他人改动）。提交后 `git show --stat` 自查。
- 提交信息前缀 `feat(task-0801):` / `refactor(task-0801):`。

---

## 7. 交付清单（验收依据）

| # | 交付物 |
|---|---|
| 1 | 代码：4 改 + 2 新建 + 1 删（见 `需求说明.md` §9.3 表） |
| 2 | 自检声明五连（§5 的 ①~⑤ 输出） |
| 3 | 截图：抽屉全貌（1920 宽）、左栏搜索命中态、括号 4 色循环、配对黄底高亮、未闭合红波浪线 |
| 4 | 三类组件（NORMAL / EXCEL / SUBTOTAL）各一张打开截图（AC-19） |
| 5 | AC-14 存量兼容证据：取一条**含 SUMIF token** 的既有公式，改版前后各保存一次，比对 DB 里公式串**逐字节一致** |

**验收由技术总监执行**：会独立重跑 §5 全部命令 + 亲自在浏览器里走 AC-1~AC-22。凡贴出的输出与复核不符，直接退回返修。
