# api · repair-0814 详情页报价树多出「版本」列

## 结论：**接口零改动**（无新增 / 无字段变更 / 无契约调整）

按 `dev-docs/任务平台规则.md` §3.4，此处写清判定依据与回归确认清单，不留空槽。

## A0 为什么不需要改接口

「BOM 树系统固定列出几列」**从来不是接口语义，而是前端按 `side` 的渲染决策**。后端只负责下发行数据，前端决定渲染成几列：

| 载荷 | 产出方 | 报价侧（QUOTE） | 核价侧（COSTING） | 本次是否变化 |
|---|---|---|---|---|
| `__sys.nodeId` / `parentId` | 树展开（spine 系统列） | 建树 + 行身份（repair-0727） | 同左 | 否 |
| `__sys.hfPartNo` | 同上 | 渲染「料号」列 | 同左 | 否 |
| `__sys.bomVersion` | 同上 | **前端不再渲染**（本次修复；数据仍下发，只是不用） | 渲染「版本」列 | **否**（后端载荷不变） |
| `__sys.isCycle` / `nodeType` | 同上 | 环提示 / 加叶子置灰 | 同左 | 否 |
| `$view` 输出列 `view_version` | 非树页签 driver 展开 | 前端 `activeComponentVersionable` 恒 false（自带 `isCosting`），不渲染 | 渲染非树版本列（task-0713 F2） | 否 |

**关键点**：本次只是「前端不再消费 `__sys.bomVersion`（报价侧）」，**没有要求后端停止下发**。理由见 `backtask.md` B0-2 —— `bomVersion` 与 `nodeId`/`parentId` 同属一组 spine 系统列，为藏一列而裁剪后端载荷会动 task-0721 / repair-0727 的核心契约。

## A1 版本切换端点（task-0713）—— 确认不受影响

- `VersionSelectDropdown` 的调用前提是 `canSwitchTreeVersion = isCosting && !!coid && !!bomSys?.hfPartNo` **且** `editable`。
- 报价单详情页（`QuotationDetail` → `ProductDetailViews`）**不传 `coid`**，`isCosting` 亦为 false → 报价侧本就不会发起任何版本切换请求。
- 本次把版本 `<td>` 整块加 `isCosting` 闸门，**只是把一个恒不触发的分支从 DOM 中移除**，不改变任何请求行为。

## A2 回归确认清单

- [ ] 报价单详情页（`/quotations/{id}`）F12 Network：切到 BOM 树页签**不应**出现 `/part-version` 或版本切换相关请求（改动前后一致，都不应有）。
- [ ] 核价工作台 / 核价详情页版本下拉仍能正常拉取版本列表并切换（task-0713 F3/F4 链路不变）。
- [ ] 报价单详情页请求集合与改动前逐条一致（本次为纯渲染改动，不应新增/减少任何请求）。

## A3 二期触发条件

仅当出现以下任一情况，本文件才需要升级为真的接口变更文档：
1. 裁决改为「报价侧也展示 BOM 版本」→ 需明确报价侧 `bomVersion` 的业务取值口径（当前未经业务确认）。
2. 做 `BL-0174` 时决定把「每个页签出哪些系统列」下沉为后端声明式契约 → 属新增接口字段，需重写本文件。
