# repair-071501 · 核价单版本切换两个 BUG 修复报告

> 归属：task-0713（核价管理·核价单的版本选择）交付后返修
> 日期：2026-07-15
> 分支：`worktree-repair-0713-version-switch`（从 master HEAD 288efa1 建）
> 排查方法：systematic-debugging（先根因、后修复，禁猜测式改）

---

## 0. 现象（来自 `/img-temp/5.png`）

核价单 **HJ-20260715-0584**（产品 S-3120014539，状态 待核价/PENDING），核价单视图 →「物料与元素BOM」页签（元素组件，非树），两个问题：

1. **版本列宽度严重超标**：版本列（内含一个小下拉框）占了整张表约一半宽度，大量空白；字段列（销售料号/材质料号/…/损耗率）反被挤到右侧。
2. **切换版本后整组数据全变「—」**：把某个元素料号切到版本 2001 后，该组所有行的所有列（含销售料号本身）全部显示「—」，产品小计 ¥0.00。

---

## 1. 根因调查（Phase 1 证据链）

### Bug 1 — 版本列过宽

- 表格 `.qt-cost-table` 为 `width: 100%` + 默认 `table-layout: auto`（见 `cpq-frontend/src/pages/quotation/quotation.css:151`）。
- `ReadonlyProductCard.tsx` 里**字段列** `<th>` 都带**显式 `width`**（`style={{ width: w, minWidth: w }}`）。
- 唯独**版本系统列**的 `<th>` 只写了 `minWidth: 90`、**没有 `width`**；版本 `<td>`（rowSpan 分组单元格）**完全没有宽度样式**。
- `table-layout:auto` 下，无显式宽度的列会**优先吸收整张 100% 宽表格的全部剩余水平空间**。非树页签中版本列是唯一"无 width"列 → 独吞所有 slack → 被撑到极宽。

> 结论：**纯前端 CSS/布局缺陷**，与后端无关。

### Bug 2 — 切换后整组数据全变「—」（真根因）

排查用真实库数据（核价单 `af4d1c84`，line `70bc1007`，元素组件 `33fee28a`，切换料号 `S-2120011658` → 2001）逐层比对：

| 层 | 证据 | 结论 |
|---|---|---|
| override 是否写入 | `costing_order_version_override` 有 `(33fee28a, S-2120011658, 2001)` | 切换动作本身成功 |
| $view SQL 是否返数据 | 手工按 `:versionFilter` 展开跑 `wl_ys_bom_view`：2001 **返回 3 行**（Cu 20 / 301 70 / Ni 10，material_no/component_no/content 全在） | **后端查询没问题，数据是有的** |
| 缓存里存了什么 | `costing_render` 元素页签 8 行：未切换的 5 行 `basicDataValues` **有值**（`{"{$wl_ys_bom_view.content}":78,…}`）；刚切换的 3 行 `driverRow` 完整但 **`basicDataValues = {}` 空** | 差异被精确定位到 **basicDataValues 空** |
| 字段取数口径 | 元素组件 7 个字段**全是 `BASIC_DATA`**，`basic_data_path = $wl_ys_bom_view.<列>` → 单元格从 `basicDataValues[{$wl_ys_bom_view.列}]` 取值 | basicDataValues 空 ⇒ 全部单元格「—」 |

**根因**：`CostingVersionService`（非树切换路径）**丢弃了 expand 管线已算好的 `basicDataValues`**。

- `ExpandDriverResponse.Row` 同时含 `driverRow` **和** `basicDataValues`（BASIC_DATA 字段取数的唯一来源）。
- `expandRows()`（原第 335 行）遍历时**只取 `row.driverRow`，丢掉 `row.basicDataValues`**。
- `buildMixedBaseRows()`（原第 305 行）把新行 `basicDataValues` 硬写成空对象 `MAPPER.createObjectNode()`。
- 切换后新行的 BASIC_DATA 字段全部取不到值 → 整组「—」（含销售料号，因销售料号也是 `$wl_ys_bom_view.material_no` 的 BASIC_DATA 字段）。

**为何交付时没暴露**：task-0713 交付 live 验证走的是**主树（子配件）**切换（拓扑 13/11 行），主树路径走 `costingTreeRenderService.render(...)` 完整渲染管线、`basicDataValues` 正常填充；**非树页签**走的是另一条 `buildMixedBaseRows` 手工拼装路径，未被 live 验证覆盖 —— 印证记忆 `cpq-unverifiable-feature-masks-gap`（无正向数据/未真跑的分支会掩盖缺陷）。

---

## 2. 修复方案

### Bug 1（前端）`cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`

给版本系统列（树页签 料号+版本、非树页签 版本）的 `<th>` 和 rowSpan `<td>` 补**显式 `width`**，使其不再独吞剩余空间、按内容自适应：

- 非树 版本 `<th>`：`{ width: 100, minWidth: 90 }`；对应 rowSpan `<td>`：加 `width: 100`。
- 树 料号 `<th>`：`{ width: 130, minWidth: 120 }`；树 版本 `<th>`：`{ width: 100, minWidth: 90 }`；树 版本 `<td>`：加 `width: 100`。

> 原理：一旦版本列有了显式宽度，`table-layout:auto` 的剩余空间会在所有列间按比例分摊（与字段列现状一致），不再全灌进版本列。

### Bug 2（后端）`cpq-backend/.../service/CostingVersionService.java`

保留 expand 管线已算好的 `basicDataValues`，与初次渲染同口径：

1. `expandRows(...)` 返回类型由 `List<Map<String,Object>>`（仅 driverRow）改为 `List<ExpandDriverResponse.Row>`（完整 Row，含 basicDataValues）。
2. `buildMixedBaseRows(...)` 组装新行时，`basicDataValues` 由 `r.basicDataValues` 填充（null 兜底空对象），**不再置空**：
   ```java
   rowNode.set("driverRow", MAPPER.valueToTree(r.driverRow));
   rowNode.set("basicDataValues",
       r.basicDataValues != null ? MAPPER.valueToTree(r.basicDataValues) : MAPPER.createObjectNode());
   ```
3. `listVersionOptions(...)` 两处调用点适配为读 `row.driverRow`（仅取 view_version/partNo，行为不变）。

> 与 `CardSnapshotService` 初次渲染的行序列化（`snapshotRowNode`，`driverRow` + `basicDataValues` 均直接来自 `ExpandDriverResponse.Row`）完全对齐——统一口径，消除非树切换这条旁路的偏差。

**影响面**：仅 `CostingVersionService` 一个文件、只作用于**非树页签版本切换**这条路径；主树切换（`costingTreeRenderService.render`）、报价侧 frozen_dto、其余卡片/页签均不受影响。

---

## 3. 验证

**方法**：systematic-debugging Phase 4 —— 用截图那张真实单（`af4d1c84` / line `70bc1007` / 元素组件 `33fee28a` / 料号 `S-2120011658`）写回归测试 `CostingVersionServiceTest#t4`，@QuarkusTest 直连共享库跑真实数据。

- **前端**：`npx tsc --noEmit` → 0 错误 ✅；临时 vite(5175) 拉 `ReadonlyProductCard.tsx` → HTTP 200 ✅；主入口 200 ✅。
- **后端回归测试**（worktree `cpq-backend` 内亲跑）：`t4` 单次 switch→2001，断言该料号元素行 ①版本纯净（全 2001）②`basicDataValues` 非空 → **PASS** ✅（`t3` 非 PENDING 403、`t1` 版本列表倒序 也 PASS）。
- **live 数据佐证（修复前 vs 后，直查 `costing_render`）**：
  - 修复前（截图态）：元素页签切换组 3 行 `basicDataValues = {}`（空）→ 全「—」。
  - 修复后：同组行 `basicDataValues` 有 **7 个键**（对应 7 个 BASIC_DATA 字段）、`view_version` 纯 2001、总行数回落到正确的 8 → 正常显示销售料号/含量等。
- 测试跑完顺带把该单 live `costing_render` 刷新为正确态（用户重开截图即见修复）。

> **说明：`t2`（主树切换拓扑计数）失败 = 共享库夹具漂移，非本次回归。** t2 硬编码期望"override 2000→13 行"，但直接 SQL 模拟递归树现返 **17**（`S-3120014539` 的孙件被并发会话增补，1 根+8 子+8 孙）。t2 走**树路径** `costingTreeRenderService.render`，**完全不经过本次改动的 `expandRows`/`buildMixedBaseRows`**，同一份数据在 master 上同样得 17。不追这个随共享库漂移的硬编码计数（同 CLAUDE.md「quotation-flow 3 失败=共享 DB 夹具漂移」处置口径）。

---

## 4. 排查中发现的独立遗留问题（不在本次修复范围，已登记 BL-0058）

排查 Bug2 时，为验证"切换后版本纯净"额外写了断言，暴露出一个**与本次两个 bug 无关、也非本次修复引入的既有问题**：

- **现象**：在**同一个请求作用域内**对同一个 `$view` 先后做「LIST 模式 expand（下拉列全版本）」与「RENDER 模式 expand（按 override 渲染）」，第二次会命中第一次缓存 → 返回全版本（混版）。
- **根因**：`DataLoader` 是 `@RequestScoped`，其 `resultCache` 按 `$view` 归一化 path 为 key，**不含 versionFilter 的 mode/override 维度**（`docs/反模式.md` AP-37 / 记忆 `cpq-sqlview-cache-key-needs-component-dim` 同族"缺维度缓存串号"）。
- **生产影响 = 低，且不牵动本次两个 bug**：
  - `switchVersion` 生产是**独立 HTTP 请求**、每请求一个新 `@RequestScoped DataLoader`，单请求内对该 `$view` 只 expand 一次 → **无串号，版本过滤正确**。用户原始 live 数据是干净的单版本 `3×2001`，逐条印证生产切换过滤是对的（问题只是 bdv 空显示「—」，已修）。
  - 仅 `listVersionOptions` 端点自身在一个请求内 LIST→RENDER 连查同一 path，会让"当前版本高亮 `currentVersion`"在**无 override 兜底时**可能取错版本（纯高亮，不影响实际切换；有 override 时 currentVersion 直接读 override 表，不受影响）。
  - `@QuarkusTest` 因两次服务调用共享同一 request scope 会放大成"混版"——**测试假象**，故 `t4` 有意改为单次 switch、不在同请求先调 listVersionOptions。
- **修法方向（未做）**：给 `DataLoader.resultCache` key 增加 versionFilter 维度（override 指纹 + mode），或 `listVersionOptions` 两次 expand 间清 resultCache。因触及核价渲染取数缓存核心（AP-37 高风险区 + `docs/三大核心模块基线.md` 基线），需单独立项评审，不在本次止血范围。

---

## 5. 涉及文件

| 文件 | 改动 |
|---|---|
| `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` | Bug1：版本/料号系统列补显式 width |
| `cpq-backend/src/main/java/com/cpq/quotation/service/CostingVersionService.java` | Bug2：expandRows 保留完整 Row + buildMixedBaseRows 填 basicDataValues + listVersionOptions 适配 |
| `cpq-backend/src/test/java/com/cpq/quotation/service/CostingVersionServiceTest.java` | 新增 t4 非树元素切版本回归测试（断言新行 basicDataValues 非空） |
