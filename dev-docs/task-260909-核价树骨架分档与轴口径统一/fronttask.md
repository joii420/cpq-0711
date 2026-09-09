# fronttask · 前端任务分解

> 只按本文件做。AC 原文在 `需求文档.md §③`，**这里只标编号，不复制原文**。
> 🎨 **视觉基准是 `原型图/`，不是你的审美**：布局结构、控件类型与顺序、文案、空态与禁用态文案逐项对齐。
> 允许的偏差只有一类：**组件库能力所限的等价实现**，且必须在回报里列出来。
> 🚫 原型没画到的地方**不许自由发挥** —— 停下来问主线，那是需求缺口。

**改动面**：`cpq-frontend/src/pages/component/` 下 2 个文件 + `services/costingBomTreeConfigService.ts` 类型。**无新增页面、无新增接口。**

---

## F-1 · `usage` 类型与标签扩到三值

**服务的 AC**：AC-1, AC-2

- 位置：`src/services/costingBomTreeConfigService.ts` 的 `BomTreeConfigUsage`
- 由 `'QUOTE' | 'COSTING'` 改为 `'QUOTE' | 'COST_BASIC' | 'COST_DETAIL'`
- ⚠️ **前端不再产出 `'COSTING'`**：它在后端是只读兼容别名（`backtask.md B-2`），新建/编辑一律只发三值之一
- `CostingBomTreeConfigTab.tsx` 的 `USAGE_LABEL` 同步：`QUOTE→报价`、`COST_BASIC→基础核价`、`COST_DETAIL→详细核价`
- 🔑 **用词必须与全站一致**：`src/pages/master-data/dataset/datasetConfig.ts` 里已有「基础核价」「详细核价」两个 label，**逐字沿用**，🚫 不要自创「明细核价」等变体

## F-2 · 切换控件由 2 项改 3 项，默认落「基础核价」

**服务的 AC**：AC-1, AC-19

- 位置：`CostingBomTreeConfigTab.tsx` 的 `Segmented`（`:161` 一带）与 `useState` 初值（`:20`）
- 三个选项按 `原型图/01-核价树配置-基础核价.html` 的**顺序**：`报价` / `基础核价` / `详细核价`
- 默认选中由 `'COSTING'` 改为 **`'COST_BASIC'`**
- 顶部 `Alert` 文案改为三套口径（原型里有定稿文案，逐字照抄）：
  > 报价、基础核价、详细核价三套各自独立维护、独立生效（active），互不影响：切换上方开关只改变本页面查看/操作的范围，激活某一套的配置不会下线另外两套的现役配置。
- 标题 `{USAGE_LABEL[usage]}树配置` 与抽屉标题已参数化，**改标签即自动跟随** —— 但要**实测**三种切换下标题都对（这类"自动跟随"最容易在改完标签后才发现某处写死）

## F-3 · 空态

**服务的 AC**：AC-2

- 「详细核价」当前**必然是空的**（库里 `COST_DETAIL` 方言组件实测 0 个）⇒ 空态是本期最常见的首屏，不是边角
- 文案：**「暂无详细核价树配置」**（按 usage 动态：`暂无{USAGE_LABEL[usage]}树配置`）
- 🚫 空态下「新增」按钮**保持可点，不得禁用、不得隐藏**（`frontend.md §1.2`：禁用要可见且说明原因；这里根本不该禁用）
- 视觉照 `原型图/02-核价树配置-详细核价-空态.html`

## F-4 · 「核价树配置」页签仅 `SYSTEM_ADMIN` 可见

**服务的 AC**：AC-17

- 位置：`src/pages/component/ComponentManagementHub.tsx`
- 取角色：`useAuthStore()` 的 `user?.role`，判据 `=== 'SYSTEM_ADMIN'`
  —— 与 `src/layouts/MainLayout.tsx:157` 的既有写法**同款**（`const userRole = (user?.role || 'SALES_REP') as Role`），🚫 不要新造一套角色判定
- 非管理员：该 tab **整项不进 `items` 数组**（不是渲染出来再隐藏）
- ⚠️ **前端隐藏不构成权限**：后端 `B-8` 同步收紧，两边都要改。回报里必须写明你确认过后端那半也在本次范围内
- 视觉照 `原型图/03-组件管理-非管理员视角.html`（3 个页签）

## F-5 · 回归确认（不改代码，但必须验）

**服务的 AC**：AC-3, AC-19

- 新增/编辑抽屉里的 `usage` 字段来自 `form.setFieldsValue({ usage })`（`:48`），随 F-2 的默认值改变而改变 —— **实测三种切换下新增出来的记录 usage 正确**
- `SelectableTable` 的工具栏动作（新增 / 设为生效 / 编辑 / 删除）与 `enabledWhen` **一律不动**（`frontend.md §1.2` 既有实现已合规）
- 删除确认文案里「将导致对应侧（报价/核价）BOM 树无法渲染」需同步为三套口径

---

## 为什么没有别的前端改动（逐条排除，不是没看）

| 可能以为要改的地方 | 判定 |
|---|---|
| 报价单编辑页 / 核价卡片要不要加「数据集」选择器 | **不加**。用哪套数据集由「该核价模板的 BOM 页签建在哪个数据集上」决定，渲染期后端自动解析（`backtask.md B-3`），**用户不选** |
| `QuotationStep2.tsx` / `ReadonlyProductCard.tsx` 的版本列 | **不动**。本期不碰版本切换链路（`需求文档.md §② 明确不做`） |
| 取数配置器的数据集选择器 | **不动**。`BuilderDataset` 早已是三值（`sqlViewBuilderService.ts:77`），本期不涉及 |
| 树渲染组件（缩进 / 折叠箭头 / BOM 列） | **不动**。行数与层级的变化全部来自后端 baseRows，前端数据驱动（`activeComponentBomTree` 按 `__sys.nodeId` 判定），**零前端改动即可呈现 AC-7 的 7 行 4 层** |

⚠️ 上表第 4 行是本任务前端改动面小的**根本原因**，也是最需要在开发期证伪的假设：
**F-x 全部做完之前，先在 dev 环境确认「后端修好后 BOM 页签能自然渲出 7 行」** —— 若渲不出，说明前端也有分支要改，**立即报主线**，不要自行加代码。
