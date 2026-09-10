# fronttask · 前端任务分解

> 只按本文件做。AC 原文在 `需求文档.md §③`。
> 🎨 视觉基准是 `原型图/`，**逐项对齐**；允许的偏差只有「组件库能力所限的等价实现」，且要在回报里列出来。

**改动面**：`cpq-frontend/src/pages/component/SqlViewBuilderTab.tsx` + `services/sqlViewBuilderService.ts`。**无新增页面、无新增接口。**

🔑 **起点比想象的近**：`SelColumn` **已经有 `fieldType: string`**（`SqlViewBuilderTab.tsx:164`），它从后端回填（供刷新回填契约）。缺的只有两件事 —— **界面上不可改** + **保存时不发回去**。

---

## F-1 · 列配置区新增「字段类型」选择器

**服务的 AC**：AC-1, AC-11

- 位置：`SqlViewBuilderTab.tsx` 的列配置区（与 `isAmount` / `inSubtotal` / 角色标记同一行区域）
- 绑定到已有的 `col.fieldType`
- 值域**恰好 3 个**，标签按原型逐字：`基础数据` / `文本输入` / `数字输入`
- 🚫 **不出现** `FORMULA` / `DATA_SOURCE` / `FIXED_VALUE` —— 它们分别需要 `formula_id` / `binding` / `content`，配置器不收集，放进去就是让人配出必然坏的字段
- 刷新页面重新打开时**回填当前值**（AC-11；`fieldType` 本就是为回填而存在的，确认这条链路仍通）

## F-2 · 整列批量设置

**服务的 AC**：AC-2

- 列配置区顶部提供「**整列批量设为…**」入口，选中值后应用到当前**全部**列，逐列选择器同步变更
- 视觉与交互照 `原型图/01-列配置-字段类型选择器.html`
- 🚫 按 `frontend.md §1.2`：若某状态下不可用，**保持可见 + hover 说明原因**，不许 `return null` 隐藏

## F-3 · 保存时发 `fieldType`

**服务的 AC**：AC-3, AC-4, AC-5, AC-8, AC-9

- 位置：保存 payload 拼装处（`SqlViewBuilderTab.tsx:229~238` 一带）+ `sqlViewBuilderService.ts`
- 把 `fieldType` 加入每列的请求体
- ⚠️ `sqlViewBuilderService.ts:17` 的注释「**不传 viewColumn/fieldType**」**已过期，必须同步更新**，否则下一个人照它写会再踩一次
- ⚠️ **`viewColumn` 仍然不传**（那是后端纯函数生成的，只读展示）—— 本次只解禁 `fieldType` 一个

## F-4 · 新列的默认值

**服务的 AC**：AC-3, AC-4, AC-5

- 新拖入的列，`fieldType` 初值按**当前数据集方言**：`COST_BASIC` / `COST_DETAIL` → `BASIC_DATA`；`QUOTE` → 按 `dataType` 推 `INPUT_TEXT` / `INPUT_NUMBER`
- 🔑 **前后端各自算一次默认值是有意的**：前端算是为了**界面上一眼可见**（用户看到的就是将要保存的），后端算是为了**旧客户端不传时仍正确**（AC-9）。两边规则必须一致 —— 这是本任务唯一的"双写"，请在注释里写明理由，避免后人"顺手收敛成一处"

## F-5 · 回归确认（不改代码，但必须验）

**服务的 AC**：AC-14

- 报价侧配置流程**逐位不变**：新建 `QUOTE` 页签 → 默认仍是 `INPUT_TEXT`/`INPUT_NUMBER` → 保存后写的仍是 `default_source.path`
- 既有列配置项（`isAmount` / `inSubtotal` / 角色标记 / 拖拽排序 / 改名）**一律不动**

## F-6 · 渲染等价与只读形态的开发期证伪

**服务的 AC**：AC-6, AC-7, AC-12

这三条**不需要你写新代码**（`ComponentCell` 的两条分支都已存在），但**必须由你在开发期实测**——
因为它们决定本任务是否成立，而失败点在一个你改不到的地方。

- **AC-6 渲染等价**：用配置器建**同源双组件**（同一 `$view`、同样的列、同样的字段名），
  一个全配 `BASIC_DATA`、一个全配 `INPUT_*`，放进同一张 DRAFT 核价模板渲染。
  **两个页签逐行逐列的可见值必须逐字相同。**
  🔑 风险在键格式：`basic_data_path` 存 `$view.col`，而 `basicDataValues` 的键是 `{$view.col}`（**带花括号**），
  中间隔着 `bnfDriverLookupKey()`。数据面已实测就位，**转换对不对得上必须你实测**。
- **AC-7 只读形态**：`BASIC_DATA` 页签的字段渲染为纯文本、**无 `<input>`**；
  同卡片的 `INPUT_*` 页签**仍有 `<input>`**（阳性对照 —— 否则"没有 input"在整页没渲染时也成立）。
- **AC-12 序列**：改类型 → 保存 → 渲染 → 再改回 → 渲染，值与控件形态都要跟着变回去。

🚨 **渲染不出来就立刻停下报主线** —— 那意味着 `basic_data_path` → `basicDataValues` 的链路有缺口，
属需求缺口，🚫 **不许自行加代码绕**（比如在渲染层补一个 fallback 去读 `default_source`）。

---

## 为什么没有别的前端改动（逐条排除）

| 可能以为要改的 | 判定 |
|---|---|
| `ComponentCell` 的渲染分支 | **不动**。`BASIC_DATA`(:394) 与 `INPUT_*` 两条分支都已存在，本次只是让配置能选到前者 |
| `QuotationStep2.tsx` 的 `readonly` 护栏 | **不动**。用户 `D-1` 明确只要配置器这一条 |
| 存量组件的字段类型 | **不动**。AC-13 是零回归门禁 |
| 组件管理里的字段配置页 | **不动**。那条老路径本来就能设 `field_type` |

⚠️ 最后一行是本任务改动面小的原因，也是**最需要在开发期证伪的假设**：
先确认「把某列配成 `BASIC_DATA` 后，卡片上该列的值仍然显示且与改配前逐字相同」（AC-6）。
**渲染不出来就立刻报主线** —— 那意味着 `basic_data_path` → `basicDataValues` 的键格式转换有问题，属需求缺口，不要自行加代码绕。
