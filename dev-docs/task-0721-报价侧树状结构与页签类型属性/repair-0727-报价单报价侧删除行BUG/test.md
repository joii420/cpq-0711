# 测试用例 —— repair-0727 报价侧树页签删除行 BUG

> 上游：`需求说明.md`（§8 验收标准 / §11 根因勘察与实机复现 / §12 裁决 / §13 方案定稿）、`api.md`（契约）、
> `backtask.md`（B0~B6）、`fronttask.md`（F0~F4）。
> **本文件只设计用例，不执行**。前后端开发完成、开工前必读文档均已核对后，由测试工程师按本文件逐条执行。
> 分支：`fix/repair-0727-tree-delete-row`；工作区：`.claude/worktrees/repair-0727-tree-delete`。
> 编写时间：2026-07-27。编写时已用管理员会话直连 `cpq_db_0724` 核对全部"现成数据"的真实字面值（见 §2），
> 避免用例里出现凭空编造的行号/字段值。

---

## 0. 测试方案概述

### 0.1 覆盖范围

| 维度 | 覆盖 |
|---|---|
| 三条现象闭环 | ①不刷新即消失 ②只删一条 ③提交不再阻断 —— 各自独立可判定用例 + 组合闭环用例 |
| `api.md §3.3` 提交行为对照表 | 5 行逐行一条用例（含 2 条反向：真撞键仍拦 / 非树页签仍拦） |
| 零回归红线（`api.md §4`） | 核价侧、非树页签删除、`add-leaf`、`delete-preview`、PRUNE 剪枝+级联+`retainedParts` |
| 存量兼容 | 旧墓碑（无 `nodeId`）单键退化生效 |
| ①-b 专项 | `snapshot_rows` / `row_data` / `deleted_row_keys` 三者行数一致性（§11.1 实测表格的"改动后"版本） |
| AP-51 行数纪律 | 连续刷新 3 次行数稳定 |
| F-1（§12.3） | `effKey` 三处口径对齐后一致；FORMULA 列取值链路闭环（**详见 §10 风险点，现无可直接复用的正向 UI 数据**） |
| §12.1 已知残留边界 | 同节点字节级完全相同两行仍连删 —— 标注"预期如此，不算失败" |

### 0.2 风险点摘要（先说结论，详细证据见 §10）

在动手写用例前，已用管理员会话核对 `cpq_db_0724` 实际数据，发现三个会直接影响"零回归"验证可行性的环境事实，**技术总监拍板前必须知道**：

1. **`cpq_db_0724` 当前只有 8 张报价单**（`QT-20260726-0001~0008`，均 2026-07-27 建，DRAFT），2026-07-24 之前的历史夹具（含 task-0721 自己的 E2E 专属夹具 `QT-20260721-2067` / `costing-bom-tree.spec.ts` 用的 `QT-20260604-1577`）**在这个库里都不存在**。已有的树相关 E2E spec（`quotation-bom-tree.spec.ts`、`costing-bom-tree.spec.ts`）若不改指向，跑起来会在 `enterQuoteTreeTab` 直接 0 行失败/假阴性，**不能作为本次零回归的证据来源**。
2. **核价侧目前无任何一张"核价单据"**（`costing_order` 表 0 行）；但报价单编辑页内置的"核价视图"（`cardSide==='COSTING'`，靠 `quotation.costing_card_template_id` 是否绑定核价模板决定能不能切）里，`QT-20260726-0006` **未绑核价模板**（`costing_card_template_id IS NULL`），**无法在这张单据上做 AC-7 的 UI 级核价视图回归**；`QT-20260726-0007` / `0008` 绑了核价模板且树组件干净（6 行、0 墓碑），可作为 AC-7 的替代夹具，但这属于我在写用例时临时探明的补充数据，**不是需求说明/任务拆分文档里指定的**，需要技术总监确认能不能用。
3. **全库 3 个 BOM 树组件都没有配置任何 `FORMULA` 字段**（`912fa00c` / `422fd880` / `656c9b87`）。F-1（§12.3）要求的"树页签配 FORMULA 列时公式值能取到"这条**正向 UI/接口验证目前无米下锅**，只能靠后端/前端单测的合成数据闭环，UI 级证据需要额外造一个测试字段（是否允许、造哪张单，见 §10 风险点 1）。

### 0.3 用例类型与执行方式图例

| 前缀 | 类型 | 执行方式 |
|---|---|---|
| `UT-B*` | 后端单元测试 | `cd cpq-backend && ./mvnw -q test -Dtest=<ClassName>`（**必须在 worktree 的 `cpq-backend` 下跑**，见 `backtask.md` B6） |
| `UT-F*` | 前端单元测试 | `cd cpq-frontend && npx vitest run <file>` |
| `IT-*` | 接口测试（curl，用真实共享库数据） | 见 §1.3 curl 约定 |
| `UI-*` | UI 手工测试 | 浏览器 `http://localhost:5174`，admin / Admin@2026 |
| `E2E-*` | Playwright 自动化 | `npx playwright test --config=e2e/playwright.config.ts <spec>` |

---

## 1. 测试环境

| 项 | 值 |
|---|---|
| 数据库 | `cpq_db_0724`（`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724`） |
| 后端 | 共享 dev server `http://localhost:8081`（**所有 curl 加 `--noproxy '*'`**，本机 `http_proxy` 会劫持 localhost） |
| 前端 | 共享 dev server `http://localhost:5174` |
| 登录 | admin / Admin@2026（cookie 会话，`SYSTEM_ADMIN`，覆盖树上删除 + 提交审批所需权限） |
| 健康自检 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` → 期望 `401`；`http://localhost:5174/` → 期望 `200` |

### 1.3 curl 登录 + 会话约定

```bash
COOKIE_JAR=/tmp/repair0727-cookies.txt
curl -s --noproxy '*' -c "$COOKIE_JAR" -X POST http://localhost:8081/api/cpq/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@2026"}' | head -c 300
# 后续所有请求都带 -b "$COOKIE_JAR"
```

---

## 2. 现成验收数据（不新造、不删除；每条会改动数据的用例都配套写复位步骤）

### 2.1 主夹具 —— `QT-20260726-0006`

| 项 | 值 |
|---|---|
| `quotationId` | `69aab7ec-9140-427b-b717-ed0a806485d1` |
| `lineItemId` | `dfee1e78-94c7-4af1-899b-caa9b60fd29a` |
| 树组件（`656c9b87`） | `componentId=656c9b87-cda5-4c32-8d72-45d94714f77a`，`quotation_line_component_data.id=dda39b0c-3690-4a2c-8426-b3a4edfcee17`，`tab_type='BOM'`，`rowKeyFields=["料件"]` |
| 树 6 行（1 基 `idx`，来自 `snapshot_rows` 顺序，与 `submit` 422 报的 `rowIndices` 同口径） | 见下表 |
| 现存墓碑 | `deleted_row_keys = [{"fp":"∅∅∅AgNi11#-Ⅰ∅∅∅∅992S-3120014539","effKey":"S-3120014539/992::4"}]`（**旧格式，无 `nodeId` 键**——正是 AC-9 存量兼容要验证的对象） |
| `row_data`（5 行，已物化） | `[{"料件":"主料1","row_index":0},{"料件":"投入零件1","row_index":1,"组成单位":"PCS","组成数量":1},{"料件":"组成件1","row_index":2,"组成单位":"PCS","组成数量":2},{"料件":"H65","row_index":3},{"料件":"AgNi11#-Ⅰ","row_index":4}]` |
| `quotation_line_item.deleted_tree_nodes` | `NULL`（空，无剪枝墓碑） |
| `quotation.status` | `DRAFT` |
| `costing_card_template_id` | `NULL`（**该单无法切核价视图**，见 §0.2 风险点 2） |

树 6 行明细（`idx` 1 基，`__nodeId` / `__parentNo` / `料件` / `material_no`）：

| idx | `__nodeId` | `__parentNo` | 料件 | `material_no` |
|---|---|---|---|---|
| 1 | `S-3120014539` | （根） | 主料1 | `S-3120014539` |
| 2 | `S-3120014539/S-80011` | `S-3120014539` | 投入零件1 | `S-80011` |
| 3 | `S-3120014539/W-1001` | `S-3120014539` | 组成件1 | `W-1001` |
| 4 | `S-3120014539/00137` | `S-3120014539` | H65 | `00137` |
| 5 | `S-3120014539/992` | `S-3120014539` | **AgNi11#-Ⅰ** | `992` |
| 6 | `S-3120014539/S-80011/992` | `S-80011` | **AgNi11#-Ⅰ**（DAG 重复子件，与 idx5 同料号不同父） | `992` |

现存墓碑精确匹配 idx5（`fp` 尾段 `992S-3120014539`），**不**匹配 idx6（`fp` 尾段应为 `992S-80011`）——这与 `row_data` 现存 5 行（6 − 1）一致。

跨页签联动数据（同一 `lineItemId` 下，供 §5.11 级联测试用）：

| 页签 | `componentId` | `quotation_line_component_data.id` | 与 992/AgNi11#-Ⅰ 的关联 |
|---|---|---|---|
| 材料成本（材质元素） | `de3c660d-c617-4a9d-b2a2-8a943f5cc9ac` | `1a754e1f-484e-4b78-8ffe-e67607a27cc9` | `row_data` 现存 1 行（`料号=992, hf_part_no` 隐含 `S-3120014539`），另有 1 条旧格式墓碑（对应 H65/00137 行，`effKey="1"`，与本次改动无关，**保持原样不要动**） |
| 外购件成本 | `09a146db-bac5-4cde-be4e-38b7cdf21565` | — | 1 行，料号 `W-1001`，`hf_part_no=S-3120014539` |
| 加工费 | `894a75c1-c853-47d2-84cd-98f6f2356afe` | — | 1 行，料号 `S-80011`，`hf_part_no=S-3120014539` |

### 2.2 备用夹具（补充探明，非文档指定，用前需技术总监确认可用性）

| 单据 | `quotationId` | `lineItemId` | 树组件状态 | 用途 |
|---|---|---|---|---|
| `QT-20260726-0007` | `62c3e1bd-5be0-4da4-87a3-9b7ab95e63ed` | `79ec4029-9fa4-4183-8533-cb073f044bd9` | 同一 `656c9b87` 树组件，6 行、**0 墓碑**（干净），`costing_card_template_id` **已绑** | AC-7 核价侧零回归备选（QT-20260726-0006 无法切核价视图） |
| `QT-20260726-0008` | `2168c574-746b-491a-8ba5-7a03851574b3` | `3941020f-e87e-4eab-9c33-1373af0f80d2` | 同上，6 行、0 墓碑，`costing_card_template_id` 已绑 | F-1 `effKey` 格式只读核对（§11.2/§12.3 原文实测用的就是这张单） |

### 2.3 通用复位纪律

1. **任何会写墓碑 / 改 `row_data` / 触发提交状态流转的用例，执行前先跑一遍确认当前状态与 §2.1 表格一致**（防止上一条用例复位失败留下脏状态污染下一条）。
2. 复位优先用**业务端点**（`restore-driver-rows` 清空树组件墓碑 + `refresh-card-snapshot` 重算；`withdraw` 把 `SUBMITTED` 打回 `DRAFT`），只有端点做不到时才退回直接 `UPDATE`。
3. 直接 `UPDATE` 复位模板（树组件 `dda39b0c`）：
   ```sql
   UPDATE quotation_line_component_data
   SET row_data = '[{"料件":"主料1","row_index":0},{"料件":"投入零件1","row_index":1,"组成单位":"PCS","组成数量":1},{"料件":"组成件1","row_index":2,"组成单位":"PCS","组成数量":2},{"料件":"H65","row_index":3},{"料件":"AgNi11#-Ⅰ","row_index":4}]'::jsonb,
       deleted_row_keys = '[{"fp":"∅∅∅AgNi11#-Ⅰ∅∅∅∅992S-3120014539","effKey":"S-3120014539/992::4"}]'::jsonb
   WHERE id = 'dda39b0c-3690-4a2c-8426-b3a4edfcee17';
   UPDATE quotation_line_item SET deleted_tree_nodes = NULL
   WHERE id = 'dfee1e78-94c7-4af1-899b-caa9b60fd29a';
   UPDATE quotation SET status = 'DRAFT'
   WHERE id = '69aab7ec-9140-427b-b717-ed0a806485d1';
   ```
   复位后务必用 `refresh-card-snapshot`（或等价的 `restore-driver-rows` 再删）重新物化一次，不要只改 DB 就收工——否则内存态/`quoteCardValues` 快照与 DB 不同步，下一条用例会读到旧缓存。
4. 材料成本页签（`1a754e1f`）**本次任何用例都不应主动改它**；如因级联测试（§5.11）被动改动，同样先 `SELECT` 现值备份再复位：当前 `row_data = [{"元素":"Ag","料号":"992","材质":"AgNi11#-Ⅰ","项次":1,"row_index":0,"元素单价":118478,"毛用量单位":"g/KPCS"}]`，`deleted_row_keys` 保持含那条 H65 旧墓碑不变（`[{"fp":"∅∅∅d47027f8-99d2-4657-b61b-2e320b5052c4Cu1.0500137H65g/PCS∅∅1S-31200145391850","effKey":"1"}]`）。

---

## 3. 单元测试 —— 后端（Java / JUnit 5）

> 执行：`cd .claude/worktrees/repair-0727-tree-delete/cpq-backend && ./mvnw -q test -Dtest=<ClassName>`

### 3.1 B0（F-1 `effKey` 口径对齐）

| # | 关联 | 类 | 前置 | 步骤 | 预期结果 |
|---|---|---|---|---|---|
| **UT-B0-01** | §12.3 F-1 | `FormulaCalculatorTest` 或临时验证脚本 | 合成一行：`__nodeId="S-x/992"`，`deleted != null`（非空墓碑列表），配一个 `FORMULA` 字段引用该行某列 | 分别调用改动前 `CardSnapshotService.buildResolvedRows` 内部 effKey 算法、`RowDataMaterializer` 内部算法，与 `FormulaCalculator.computeRows` 产出的 `rowKey` 比较 | **核查用例**：断言修复前两者不等（`"S-x/992::<base>"` vs `"<base>"` 不带前缀）——用于把 §12.3 的"待核查"钉成"已证实"的书面证据，需写入 PR 说明，若断言失败（即证伪）立即停手不改代码 |
| **UT-B0-02** | §12.3 F-1 正向闭环 | 同上 | 同上，且该字段确实是 `FORMULA` 类型、公式引用该行值 | 修复后重跑：三处（`FormulaCalculator.computeRows` / `CardSnapshotService.buildResolvedRows` / `RowDataMaterializer`）对同一行产出的 `rowKey`/查表键 | **逐字节相等**（`buildRawRowKeys` 抽取的同一份代码产出）；`editByKey`/`frByKey` 用该键能查到值（非 miss） |
| **UT-B0-03** | backtask B0 验收 | 同上，模拟"旧 `editRows` 用不带前缀的旧键存的历史数据" | 用新键（带前缀）查 `editByKey`/`frByKey` 故意不命中的场景 | 断言回退查询：新键 miss 后用旧键（不带前缀）再查一次 | 能读到历史编辑值（不因升级口径丢失存量编辑） |
| **UT-B0-04** | backtask B0 验收 + 零回归 | 同上，`deleted == null`（模拟核价侧路径） | 不带 `__nodeId` 前缀逻辑分支 | effKey 计算逐位不变，不进新分支 | 核价侧行为零回归 |

### 3.2 B1 — `DeletedRowKeys` 支持 `nodeId` 维度

文件：`cpq-backend/src/main/java/com/cpq/quotation/rowkey/DeletedRowKeys.java`，测试类：`DeletedRowKeysTest`

| # | 前置数据 | 步骤 | 预期结果 |
|---|---|---|---|
| **UT-B1-01** | 两行 `fp` 相同、`nodeId` 不同（模拟"字段视图不输出父件列"极端场景，§11.2 提到的"若某树 driver 视图不输出父件列，两个 occurrence 的 driverRow 即完全同内容"）；墓碑含 `nodeId`，只对应其中一行 | 调新签名 `keepMask(effKeys, fps, nodeIds, deleted)` | `keepMask` 只标记匹配的那一行为删除（`false`），另一行 `true`（保留）——这是症状②的**根治**证据 |
| **UT-B1-02** | 同上两行；墓碑**不含** `nodeId`（旧格式） | 同上调用 | `keepMask` **两行都标记删除**（`false`, `false`）——证明"退化行为保持"（AC-9 存量兼容的单测锚点，同时也是 §12.1 已知残留边界在单测层的成因证据） |
| **UT-B1-03** | 非树行（`nodeIds[i]=null`），墓碑含 `nodeId` | 同上调用 | 按 `fp` 单键匹配（不因墓碑带 `nodeId` 而拒绝匹配非树行）——对应 `api.md §2.2` 表格"任意墓碑 × 非树行"行 |
| **UT-B1-04** | 现有 `keepMask(effKeys, fps, deleted)` 三参重载的既有测试用例 | 原样重跑，不改断言 | **全部不改、全部通过**（新重载是加法，不是替换） |
| **UT-B1-05** | 含 `nodeId` 的 JSON 墓碑字符串 + 不含 `nodeId` 的 JSON 墓碑字符串 | `parse(json)` | 含 → `Tombstone.nodeId()` 非空；不含 → `null`（不抛异常，`Tombstone` 双参构造兼容） |

### 3.3 B2 — 三处 `keepMask` 调用点

| # | 位置 | 步骤 | 预期结果 |
|---|---|---|---|
| **UT-B2-01** | `FormulaCalculator` / `CardSnapshotService.buildResolvedRows` / `RowDataMaterializer` 三处，同一份合成 `baseRows`（含两行同 `fp` 不同 `nodeId`） | 三处各自调用新 `keepMask`，比较 `keep[]` 输出 | 三处结果**逐位一致**（同一取值方式：从 row 顶层 `__nodeId` 取，不从 `driverRow` 内取） |
| **UT-B2-02** | 核价侧路径（`deleted == null`） | 三处分别调用 | 均不进入新分支，行为逐字节不变（回归） |

### 3.4 B3 — 树删除写墓碑带 `nodeId` + 补物化 + 补响应投影

文件：`QuotationTreeService.java`，测试类：`QuotationTreeServiceTest`（单测部分）+ §5 接口测试（真实数据部分）

| # | 步骤 | 预期结果 |
|---|---|---|
| **UT-B3-01** | ROW 模式删除，调 `appendRowTombstone` | 写入的墓碑 JSON 含 `nodeId` 字段，值 = 被删节点的 `__nodeId` |
| **UT-B3-02** | 级联分支（非树页签行）调 `appendRowTombstone` | 写入的墓碑 `nodeId=null`（保持 fp 单键语义，不影响非树页签既有匹配） |
| **UT-B3-03** | 幂等：连续两次对同一 `(nodeId, fp)` 调用删除 | 墓碑判重条件是 **"`fp` 相同 且 `nodeId` 相同"**，第二次不重复追加（对应 `api.md §1.2` "重复删同一 `(nodeId, rowKey)` → 200 且墓碑不重复追加"） |
| **UT-B3-04** | `refreshQuoteProjection` 返回 `null`（模拟非 `DRAFT` 场景） | `executeDelete` 必须回落 `snapshotQuoteSideOnly(li, q)` 兜底，且响应体**不带** `componentData`；不得静默不刷新（抛异常或空响应都算失败） |

### 3.5 B4 — `computeRowFpForNode` 精确定位

| # | 前置数据 | 步骤 | 预期结果 |
|---|---|---|---|
| **UT-B4-01** | 合成同 `nodeId` 下两条内容不同的业务行（模拟"同一节点下两条明细"，即 §12.1 已知边界之外的正常场景） | 请求删除第 2 行（带该行的 `rowKey`/`effKey`） | 算出的 `fp` 是**第 2 行**的 `fp`（不是第 1 行）——这是"删错行"回归的核心断言 |
| **UT-B4-02** | 同 `nodeId` 下只有 1 行（当前 QT-20260726-0006 树的真实情形——6 个 `nodeId` 各自唯一） | 请求删除 | 行为与改动前一致（回归） |
| **UT-B4-03** | 构造"按 `rowKey` 算不出 / 不匹配任何候选行"的场景 | 请求删除 | 退回"第一条"策略，且产生一条 `LOG.warn` 记录（可通过日志断言或 mock Logger 验证） |

### 3.6 B5 — 提交期行键校验（P0）

文件：`RowKeyUniquenessService.java` + `QuotationService.submitForApproval`，测试类：`RowKeyUniquenessServiceTest`

| # | 前置数据 | 步骤 | 预期结果 |
|---|---|---|---|
| **UT-B5-01** | `CompRows` 含 `deletedRowKeysJson`，`snapshotRows` 全集 6 行中 2 行已被墓碑覆盖 | `collectConflicts` | 参与判重的只剩过滤后的行；两条已删行不出现在冲突集合的候选池里 |
| **UT-B5-02** | 树行（带 `__nodeId`）两条同 `rowKey` 不同 `nodeId` | `collectConflicts` | **不判为冲突**（判重键 = `computeDedupKey + "@" + __nodeId`，两者不同） |
| **UT-B5-03** | 树行两条同 `rowKey` **同** `nodeId`（模拟"同一节点下两条相同行键的明细"） | `collectConflicts` | **判为冲突**（真撞键，不得被 nodeId 维度放过）——`api.md §3.3` 表格第 4 行的单测锚点 |
| **UT-B5-04** | 非树行两条同 `rowKey`（`__nodeId` 全 `null`） | `collectConflicts` | **判为冲突**，口径与改动前逐字节一致——`api.md §3.3` 表格第 5 行的单测锚点（**反向用例，证明非树侧未被误伤**） |
| **UT-B5-05** | `LineItemComps` 含 `deletedTreeNodesJson`（剪枝墓碑），某行 `__nodeId` 前缀命中剪枝墓碑 | `collectConflicts` | 该行在判重前已被剪枝过滤剔除，不参与 `rowIndices` |
| **UT-B5-06** | 剪枝过滤 + 行墓碑过滤复用的实现来源检查 | 静态检查/反射断言：`collectConflicts` 内调用的过滤函数与 `CardSnapshotService`/`DeletedRowKeys.keepMask` 是**同一个方法引用**，而非另写一套 | 满足 AP-22"同源纪律"——若发现是复制粘贴的另一份实现，判定不通过（无论结果是否碰巧一致） |

---

## 4. 单元测试 —— 前端（Vitest）

> 执行：`cd .claude/worktrees/repair-0727-tree-delete/cpq-frontend && npx vitest run <file>`

### 4.1 F0 — `useCardSnapshots.buildUniqueRowKeys`

| # | 步骤 | 预期结果 |
|---|---|---|
| **UT-F0-01** | 树行（有 `__nodeId`）+ 报价侧（`side='QUOTE'`）+ 该行在完整集里会被判定"有重复" | `buildUniqueRowKeys` 产出的 `rawKey` = `nodeId + "::" + base`，与后端 `quote_card_values.tabs[].formulaResults[].rowKey` 对拍一致 |
| **UT-F0-02** | 非树行 / 核价侧（`side='COSTING'`） | 产出逐字节不变（不带前缀），回归断言 |
| **UT-F0-03** | 模拟旧 `editRows`/`formulaResults` 用不带前缀的旧键存 | 新键查不到时按旧键回退查一次 | 能读到历史值 |

### 4.2 F1 — `deletedRows.ts`

文件：`cpq-frontend/src/pages/quotation/deletedRows.ts`，测试文件：`deletedRows.test.ts`

> 注：写用例时已发现该文件**存在未提交的 WIP 改动**（`keepRow` 已加 `nodeId?` 第 4 参，`Tombstone` 已加 `nodeId?` 字段），执行阶段以最终提交版本为准，用例本身不受影响。

| # | 步骤 | 预期结果 |
|---|---|---|
| **UT-F1-01** | 两行同 `fp` 不同 `nodeId`；新墓碑含 `nodeId`，只对应一行 | `keepRow(effKey, fp, deleted, nodeId)` | 只有匹配的那行返回 `false`（删除），另一行 `true`（保留）——与 `UT-B1-01` 后端镜像，两侧必须同结论 |
| **UT-F1-02** | 同上两行；墓碑不含 `nodeId`（旧数据） | 同上调用 | 两行都返回 `false`（删除）——与 `UT-B1-02` 镜像 |
| **UT-F1-03** | 非树行（不传 `nodeId` 或传 `null`） | 同上调用 | 按 `fp` 单键匹配，行为与改动前逐字节一致 |
| **UT-F1-04** | 现有 `deletedRows.test.ts` 全部既有用例 | 原样重跑 | 全部不改、全部通过 |

### 4.3 F2 — `buildSnapshotExpansions` 过滤时传 `__nodeId`

文件：`QuotationStep2.tsx`（约 `:1483-1496`），测试文件：`buildSnapshotExpansions.deletedRows.test.ts`

| # | 步骤 | 预期结果 |
|---|---|---|
| **UT-F2-01** | 两行同 `fp` 不同 `br.__nodeId`；墓碑含 `nodeId` 只对应一行 | `buildSnapshotExpansions` | 只过滤 1 行，`kept` 保留另一行；`uniqFull` 仍在**完整** `baseRows` 上算（不在过滤后子集上重算），`rowCount` 取 `kept.length` |
| **UT-F2-02** | 该文件现有 5 条 AP-54 不变量用例 | 原样重跑 | 全部通过（守住"过滤子集不重排/不重算 key"头号不变量） |

---

## 5. 接口测试（curl，`QT-20260726-0006` 真实数据）

> 每条用例默认在**干净初始状态**（§2.1 表格）上执行；执行前先跑 §2.3 复位 SQL 确认基线，执行后按 §2.3 复位。

### 5.1 IT-01 —— 症状① 核心闭环：删除后响应含权威投影，当次即可判定不需要刷新

**关联**：AC-1、`api.md §1.2`

**前置**：§2.1 基线（6 行 / 1 墓碑 / `row_data` 5 行）

**步骤**：
```bash
# 1. 预览（拿 previewToken）
curl -s --noproxy '*' -b "$COOKIE_JAR" -X POST \
  http://localhost:8081/api/cpq/quotations/69aab7ec-9140-427b-b717-ed0a806485d1/line-items/dfee1e78-94c7-4af1-899b-caa9b60fd29a/tree/delete-preview \
  -H 'Content-Type: application/json' \
  -d '{"componentId":"656c9b87-cda5-4c32-8d72-45d94714f77a","mode":"ROW","nodeId":"S-3120014539/S-80011/992"}'

# 2. 执行删除（rowKey 用预览响应里对应该行的 effKey；previewToken 用步骤1返回值）
curl -s --noproxy '*' -b "$COOKIE_JAR" -X POST \
  http://localhost:8081/api/cpq/quotations/69aab7ec-9140-427b-b717-ed0a806485d1/line-items/dfee1e78-94c7-4af1-899b-caa9b60fd29a/tree/delete \
  -H 'Content-Type: application/json' \
  -d '{"componentId":"656c9b87-cda5-4c32-8d72-45d94714f77a","mode":"ROW","nodeId":"S-3120014539/S-80011/992","rowKey":"<步骤1回填>","previewToken":"<步骤1回填>"}'
```

**预期结果（均为可判定断言）**：
1. HTTP 200
2. 响应 `data.componentData` **存在且非空数组**（改动前实测缺失，见需求说明 §11.1）
3. `componentData` 中 `componentId=656c9b87...` 的条目：`JSON.parse(rowData).length === 4`（5 − 1）
4. `data.deletedRowKeys`（或 `componentData[].deletedRowKeys`）解析后长度 = 2，第 2 条含 `nodeId="S-3120014539/S-80011/992"`
5. **不依赖任何后续"刷新基础数据"调用**，仅凭这一次响应即可判定该行已从权威数据源移除——满足 AC-1"当帧消失"的后端前提

**验证手段**：接口测试（curl）+ SQL 复核（见 IT-02）

---

### 5.2 IT-02 —— ①-b 专项：`snapshot_rows`/`row_data`/`deleted_row_keys` 三者一致性

**关联**：需求说明 §11.1 实测表格（"改动前"已确认错位），本用例断言"改动后"不再错位

**前置**：IT-01 执行完毕（已删 idx6）

**步骤**：
```sql
SELECT jsonb_array_length(snapshot_rows) AS snap_len,
       jsonb_array_length(row_data) AS rowdata_len,
       jsonb_array_length(deleted_row_keys) AS tomb_len
FROM quotation_line_component_data
WHERE id = 'dda39b0c-3690-4a2c-8426-b3a4edfcee17';
```

**预期结果**：
| 字段 | 改动前实测（§11.1，已知坏） | 本用例断言的改动后值 |
|---|---|---|
| `snap_len` | 6 | 6（不变，树结构不因行删除变化） |
| `rowdata_len` | 5（未变，**含被删的 AgNi 行**） | **4**（= 6 − 2 墓碑，两存储不再错位） |
| `tomb_len` | 2 | 2 |

再调一次 `POST .../refresh-card-snapshot`（或前端「刷新基础数据」等价端点），复核 `rowdata_len` 仍为 4（不因刷新而回退或累加，AP-51 纪律）。

**验证手段**：接口测试 + SQL

---

### 5.3 IT-03 —— 症状② 核心闭环：只删一条，另一条逐字段值不变

**关联**：AC-2

**前置**：§2.1 基线（先只做本用例，不叠加 IT-01；若顺序执行需先复位）

**步骤**：
1. 记录删除前 idx5（`S-3120014539/992`）的完整 `driverRow` 字面值（见 §2.1 表格）。
2. 用与 IT-01 相同流程删除 idx6（`S-3120014539/S-80011/992`）。
3. 重新 `GET` 该报价单详情（或直接查 `row_data`），提取 idx5 对应行（`料件=AgNi11#-Ⅰ`、其父 `S-3120014539`）的全部字段。

**预期结果**：
- idx5 行仍在，`料件`/`row_index` 等值与删除前**逐字段一致**（无串行、无位移）
- 只有 idx6 那一条从 `row_data` 消失，`row_data.length` 从 5 变 4（而非从 5 变 3 或不变）

**验证手段**：接口测试（curl + jq 对比）

---

### 5.4 IT-04 —— 幂等：重复删除同一 `(nodeId, fp)`

**关联**：`api.md §1.2` 幂等条款

**前置**：IT-01 执行完（idx6 已删）

**步骤**：对同一 `nodeId="S-3120014539/S-80011/992"` 再发一次 `tree/delete-preview` → `tree/delete`

**预期结果**：HTTP 200；`deleted_row_keys` 长度**仍为 2**（不重复追加第 3 条）

**验证手段**：接口测试 + SQL

---

### 5.5~5.9 IT-05~IT-09 —— `api.md §3.3` 提交行为对照表逐行验证（P0，AC-3/AC-4）

> 每一行都从 §2.1 干净基线开始，执行后立即复位（`restore-driver-rows` 清墓碑 + `withdraw` 打回 DRAFT，必要时叠加 §2.3 SQL），不得让前一条的状态污染下一条。

#### IT-05 —— 表格第 1 行：两行都在，未删除

**预期（改动前 vs 改动后）**：`POST /api/cpq/quotations/{id}/submit` 从 422 变为 **成功**（`quotation.status → SUBMITTED`）

**步骤**：
```bash
curl -s --noproxy '*' -b "$COOKIE_JAR" -X POST \
  http://localhost:8081/api/cpq/quotations/69aab7ec-9140-427b-b717-ed0a806485d1/submit
```
**预期结果**：HTTP 200（不是 422），响应 `status=SUBMITTED`
**复位**：`POST .../withdraw`

#### IT-06 —— 表格第 2 行：删掉其中一行（idx6），另一行仍在

**步骤**：先执行 IT-01（删 idx6），再 `submit`
**预期结果**：HTTP 200（不是 422）
**复位**：`withdraw` + 清墓碑（`restore-driver-rows`）+ §2.3 SQL 复位到基线

#### IT-07 —— 表格第 3 行：两行都删（P0 核心场景，用户实际报的③）

**步骤**：依次删 idx5、idx6（各自走 delete-preview → delete），再 `submit`
**预期结果**：HTTP 200（改动前实测 422，见需求说明 §11.3 原文错误信息）——**这是本次修复最关键的单条断言**
**复位**：`withdraw` + `restore-driver-rows` + §2.3 SQL

#### IT-08 —— 表格第 4 行（反向）：同一节点下两条相同行键的明细，仍须拦截

**关联**：AC-4，`api.md §3.3` 第 4 行

**前置**：QT-20260726-0006 的树上**不存在**这种结构（6 个 `nodeId` 各自唯一，见 §2.1），需要临时构造。**不修改 QT-20260726-0006 已有 6 行**，改用「加叶子」在某个"零件"节点（如 idx2 `S-80011`，`__nodeType='零件'`）下新增一片与已有兄弟节点业务行键相同的叶子。

**步骤**：
1. `POST tree/add-leaf`：`hostNodeId="S-3120014539/S-80011"`，选一个候选料号，使新叶子的 `料件` 值与该节点下已存在的兄弟叶子相同（若当前候选料号库里凑不出天然同名，见 §10 风险点，本条可能需要另开一条最小化 SQL 注入行的方案，需与技术总监确认）。
2. `submit`
3. 断言 422，且 `conflicts[].rowIndices` 指向这两条新叶子

**预期结果**：HTTP 422（**仍拦截**，不因 nodeId 维度放过真撞键）

**复位**：用 `tree/delete`（ROW 模式）删掉新加的叶子行，验证树恢复 6 行

**⚠️ 本用例是本文件中唯一可能需要"临时改变树结构"的用例**，执行前必须再次确认候选料号库是否能凑出"同节点同行键"的组合；若凑不出，见 §10 风险点 3（建议改用单测 `UT-B5-03` 作为唯一证据源，本条降级为"尽力而为，做不到就跳过并说明"）。

#### IT-09 —— 表格第 5 行（反向）：非树页签同组件行键重复，仍须拦截（逐字节不变）

**关联**：AC-4/AC-8，`api.md §3.3` 第 5 行 + `api.md §4` 零回归红线

**前置**：需要一个非树页签且存在真实行键重复的场景。QT-20260726-0006 当前非树页签（材料成本/外购件成本/加工费）均各只 1 行，无法直接复现"组件内行键重复"。

**步骤（只读优先）**：
1. 优先方案：直接跑 `RowKeyUniquenessServiceTest`（`UT-B5-04`）作为权威证据，本条只做**接口契约层面**的回归检查——确认非树页签调用 `submit` 时命中的仍是**旧的判重代码路径**（无 `@nodeId` 后缀），可通过在请求前后抓 `RowKeyConflictException.rowKey` 格式核对（树行会带隐含 nodeId 上下文，非树行的 `rowKey` 字符串本身格式不变）。
2. 若技术总监要求必须有一个端到端可复现的非树撞键场景，需要另建一条最小化夹具（不在 QT-20260726-0006 上做，避免破坏主夹具），执行前需申报。

**预期结果**：非树页签撞键报 422 的行为与改动前**逐字节一致**（错误信息格式、`rowIndices` 语义都不变）

**验证手段**：单元测试为主（`UT-B5-04`）+ 代码审查确认非树分支未被新逻辑触碰；接口层面为补充

---

### 5.10 IT-10 —— AC-5 幂等 + AP-51 行数纪律：连续刷新 3 次行数稳定

**关联**：AC-5

**前置**：IT-01 执行完（`row_data` 应为 4 行）

**步骤**：连续 3 次调用整单 `GET /api/cpq/quotations/69aab7ec-.../` （或等价的 `refresh-card-snapshot`），每次记录 `row_data.length`

**预期结果**：3 次均为 4（不累加、不回退到 5、不跳到 3）

**验证手段**：接口测试

---

### 5.11 IT-11 —— AC-6 剪枝级联零回归：DAG 最后一个 occurrence 被删后 `retainedParts` 状态切换

**关联**：AC-6，`api.md §4` 不变更清单里的"delete-preview 请求响应零变化"契约边界验证

**前置**：§2.1 基线（idx5、idx6 都在，材料成本页签 992 那行也在）

**步骤**：
1. 先删 idx5（`S-3120014539/992`）——此时 992 在树上仍有 idx6（`S-80011` 那支）这一个 occurrence，`delete-preview` 对 idx6 的 `retainedParts` 应仍包含 992（因为树上还有它自己这一条要删的以外，材料成本页签的 992 行不该被级联删除，理由="仍有其他引用"）。
2. 再删 idx6（最后一个 occurrence）。此时对材料成本页签的 992 行再跑一次 `delete-preview`（PRUNE 模式，`nodeId` 传材料成本页签自己的节点，或走 ROW 级联判断），`retainedParts` 应不再列出 992（树上已无剩余引用）。

**预期结果**：`retainedParts` 内容随"树上剩余 occurrence 数量"正确切换，与 task-0721 交付时的规则一致（`docs/反模式.md` 三大核心模块基线 §7.B 场景描述的"树上无剩余 occurrence 才删"）

**复位**：先确认材料成本页签的 992 行是否被实际级联删除（若是，需连同其 `row_data`/`deleted_row_keys` 一并按 §2.3.4 复位），再复位树组件到 §2.1 基线

**验证手段**：接口测试（curl `delete-preview`，只读部分优先，最小化写操作）

---

### 5.12 IT-12 —— AC-6 `add-leaf` 零回归

**关联**：`api.md §4` 不变更清单

**前置**：§2.1 基线

**步骤**：`POST tree/add-leaf`（`hostNodeId` 选 idx2 或 idx3 的"零件"节点，`partNo` 任选一个候选）

**预期结果**：请求/响应结构与改动前逐字节一致（`api.md §4`："`tree/add-leaf`、`tree/delete-preview` 请求与响应零变化"）；新叶子在树上正确出现，回灌 `quoteCardValues` 即可见（`backtask.md` 备注"预计无需改动，但须实测确认"——本用例就是那个实测）

**复位**：`tree/delete`（ROW）删除新加的叶子

**验证手段**：接口测试

---

### 5.13 IT-13 —— AC-6 `delete-preview` 契约零变化

**关联**：`api.md §4`

**前置**：§2.1 基线

**步骤**：对 idx6 跑 `tree/delete-preview`，对比响应体字段结构（`treeNodes` / `cascadeTabs` / `retainedParts` / `previewToken`）与改动前的字段清单

**预期结果**：字段结构、类型、语义均无新增/删除/改名（纯只读，不产生任何写副作用）

**验证手段**：接口测试（结构 diff）

---

### 5.14 IT-14 —— AC-8 非树页签 `delete-driver-row` 零回归

**关联**：AC-8，`api.md §4`

**前置**：用「加工费」页签（`894a75c1...`，非树，1 行）

**步骤**：
```bash
curl -s --noproxy '*' -b "$COOKIE_JAR" -X POST \
  http://localhost:8081/api/cpq/quotations/69aab7ec-9140-427b-b717-ed0a806485d1/line-items/dfee1e78-94c7-4af1-899b-caa9b60fd29a/delete-driver-row \
  -H 'Content-Type: application/json' \
  -d '{"componentId":"894a75c1-c853-47d2-84cd-98f6f2356afe","effKey":"<该行effKey>","fp":"<该行fp>"}'
```

**预期结果**：响应结构、行为与改动前逐字节一致（该端点根本不涉及 `nodeId` 参数，`br.__nodeId` 对非树行恒为 `undefined`）

**复位**：`restore-driver-rows`（`componentId=894a75c1...`）

**验证手段**：接口测试

---

### 5.15 IT-15 —— AC-9 存量兼容：旧墓碑（无 `nodeId`）仍按 `fp` 单键生效

**关联**：AC-9（**本用例是 AC-9 在真实存量数据上的直接验证，最具说服力**）

**前置**：§2.1 基线——**现存的那条墓碑本身就是旧格式**（`{"fp":"...","effKey":"..."}`，无 `nodeId` 键），这是改动前系统真实写入的存量数据，完全符合"改动前写入的历史墓碑"这一 AC-9 场景定义，**不需要额外构造**。

**步骤**：
1. 不做任何删除操作，直接读当前 `row_data`（应为 5 行，idx6 那条 AgNi 仍在，idx5 已被这条旧墓碑排除）
2. 触发一次系统重算路径（如 `refresh-card-snapshot`），观察是否因为升级了 `nodeId` 匹配逻辑而导致旧墓碑失效（idx5 复活变回 6 行）或误伤（idx6 也被这条只对 idx5 生效的旧墓碑连带删除，变成 3 行）

**预期结果**：`row_data` 仍为 5 行，且是 idx5 缺失、idx6 仍在的那个具体组合（不是"行全回来"也不是"多删"）——精确匹配 `api.md §2.2` "旧墓碑（无 nodeId）× 树行 → 退化 fp 单键 → 存量单据行为逐字节不变"

**验证手段**：接口测试 + SQL 逐字段核对

---

### 5.16 IT-16 —— AC-7 核价侧零回归（备用夹具 `QT-20260726-0007`）

**关联**：AC-7

**⚠️ 前置说明**：`QT-20260726-0006` 未绑核价模板，无法做本条；改用 §2.2 的 `QT-20260726-0007`（**使用前需技术总监确认**，理由见 §0.2/§10）。

**前置**：`QT-20260726-0007`，`lineItemId=79ec4029-9fa4-4183-8533-cb073f044bd9`，树组件 6 行 0 墓碑

**步骤**：
1. 在报价侧对该单的树组件删除一行（走 `tree/delete`，ROW 模式，任选 idx）
2. 分别读取该 `lineItem` 的报价侧 `quoteCardValues` 与核价侧 `costingCardValues`（若接口暴露）或直接查 `quotation_line_component_data` 是否存在报价侧/核价侧分离存储

**预期结果**：核价侧渲染/取数路径完全不读、不受本次树删除墓碑影响（`keepMask` 在核价侧 `deleted==null` 分支，不进新逻辑）；若报价侧与核价侧共享同一份 `quotation_line_component_data`（据 `QuotationStep2.tsx` 的 `side` 参数区分渲染而非存储分离），则需确认核价视图渲染时传入的 `deletedRowKeys` 是否被正确置为 `null`/不生效

**复位**：`restore-driver-rows` 清墓碑

**验证手段**：接口测试 + 代码路径确认（`side==='COSTING'` 分支）

---

### 5.17 IT-17 —— F-1 补充：`formulaResults[].rowKey` 三处口径跨接口一致性（只读）

**关联**：§12.3 F-1

**前置**：`QT-20260726-0008`（§12.3 原文实测证据来源单），树组件无 FORMULA 列（当前全库皆无，见 §0.2 风险点 3），**本用例只能验证 key 格式对齐，不能验证"公式值正确取到"**

**步骤**：`GET` 该报价单详情，提取 `quote_card_values.tabs[]` 中树页签的 `formulaResults[].rowKey`，与同一响应体或 `row_data` 侧的查表键格式对比

**预期结果**：三处口径（若已按 B0 对齐）应统一带 `__nodeId::` 前缀（树行 + `deleted != null` 时）；若该单当前 `deleted_row_keys` 为空数组（0007/0008 均是干净夹具），需要先制造至少 1 条墓碑（走一次 ROW 删除）才能触发 `deleted != null` 分支，测完复位

**验证手段**：接口测试（只读格式核对为主）——**真正的正向闭环证据在 `UT-B0-02`**，本条只是补充交叉验证，不能替代单测

---

## 6. UI 手工测试

浏览器打开 `http://localhost:5174`，admin / Admin@2026 登录，进入 `QT-20260726-0006` 编辑页 → 报价视图 → 产品卡片 → BOM 树页签。

| # | 关联 | 前置 | 步骤 | 预期结果（可判定） |
|---|---|---|---|---|
| **UI-01** | AC-1 | §2.1 基线，浏览器打开该单编辑页 | 点 idx6 行（`S-80011` 支下的 AgNi11#-Ⅰ）的 `×` → 抽屉展示影响面 → 点「确认删除」 | **不点任何刷新按钮**，该行在当前页面表格中立即消失（行数从 6 变 5）；抽屉自动关闭；无红色报错 |
| **UI-02** | AC-2 | 承 UI-01 | 观察 idx5 行（`S-3120014539` 支下的 AgNi11#-Ⅰ） | 仍在原位，各列（料件/组成数量等）取值与删除前一致，未发生错位或串值 |
| **UI-03** | AC-1（体验细节） | 承 UI-01 | 观察删除确认瞬间到行消失之间的过渡 | 无闪烁、无"先消失再重新出现"的跳动（`pendingDeleteRef` 在途窗口保护生效） |
| **UI-04** | AC-3 | 承 UI-01/02，页面上此时无重复行键 | 点「提交审批」 | 提交成功（弹出成功提示或状态变为已提交），**不再**弹出"行键重复"报错 |
| **UI-05**（复位） | — | 承 UI-04 | 撤回提交（找「撤回」按钮或走 `withdraw`），并把 idx6 行恢复（管理员墓碑清空 + SQL 复位） | 单据恢复 §2.1 初始状态，供后续用例复用 |
| **UI-06** | AC-1/AC-3 组合闭环 | §2.1 基线复位后 | 依次删 idx5、idx6（两次都走 UI），再提交 | 两次删除均当帧消失，互不影响；提交成功——这是用户原始报告的完整复现场景 |
| **UI-07**（复位） | — | 承 UI-06 | 撤回 + 恢复两行 | 回到基线 |
| **UI-08** | AC-4 | 需要真实存在重复行键的场景（见 IT-08 的构造方式，或直接留 2 行都不删只观察） | 不删除任何行，直接点「提交审批」 | 由于 idx5/idx6 行键（料件=AgNi11#-Ⅰ）在**未裁决 nodeId 判重前**本就相同，本条验证：`Q2` 裁决生效后，**同料号挂不同父不算撞键，应放行**，不应再报错——即"两行都不删也能提交"，与 IT-05 是同一断言的 UI 版本 |
| **UI-09**（复位） | — | 承 UI-08 | 撤回 | 回到基线 |
| **UI-10** | §4.2「刷新基础数据」回归本职 | 删除一行后 | 点「刷新基础数据」按钮 | 按钮拉取最新已审核基础数据（原本职功能），不再兼任"让删除生效"的角色；点击前后被删的行不应因为这次点击而复活或产生新的变化 |
| **UI-11** | §4.2 提交失败抽屉行为不变 | 用 IT-08 构造出的真实撞键场景（若能构造成功） | 点「提交审批」触发 422 | `RowKeyConflictDrawer` 弹出，列出的行号可点击「定位」跳到对应行；若该场景无法构造成功，本条标记为**受阻**并在报告里注明原因（依赖 IT-08 的候选料号可用性） |
| **UI-12** | AC-6（剪枝 UI 回归） | §2.1 基线 | 对某个"零件"节点点剪枝按钮（`button[title="剪掉该节点及其子树..."]`），查看抽屉三块内容 | ①树节点 ②级联页签行 ③保留料号 三块内容与 task-0721 交付时的展示逻辑一致；**只预览不确认**，关闭抽屉不产生写副作用 |
| **UI-13** | AC-6（加叶子 UI 回归） | §2.1 基线 | 对"零件"节点点「+」→ 候选料号抽屉 → 选一个 → 提交 | 新叶子正确出现在树上，`quoteCardValues` 回灌后可见；测完用 UI 删除该新叶子行复位 |
| **UI-14** | §12.1 已知残留边界（**预期如此，不算失败**） | 需要人为构造"同节点下字节级完全相同的两行"（例如通过 `add-leaf` 两次添加同一料号到同一节点，若系统允许） | 删除其中一条 | **预期行为**：可能连另一条也一起删除（因为两行 `nodeId` 相同、`fp` 也相同，身份本就不可区分，`DeletedRowKeys.java` 注释里明确记录的已知边界）。**验证要点不是"应该只删一条"，而是"确认这个已知限制在文档里被显式标注、且不误报为新 bug"**。若测试中发现即使 `nodeId+fp` 双相同的情况下系统神奇地只删了一条，那反而需要向技术总监确认是否引入了未记录的新去重机制（超出预期，需澄清而非直接判通过） |

---

## 7. E2E 自动化测试（Playwright）

### 7.1 新增专项 spec（建议命名 `repair-0727-tree-delete-row.spec.ts`）

**关联**：AC-1/AC-2/AC-3/AC-5，串联 IT-01/03/05~07/10 的 UI 层验证

**执行**：
```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/repair-0727-tree-delete-row.spec.ts --reporter=list
```

**建议脚本骨架**（按 `docs/E2E测试方法.md` §4 标杆 + `quotation-bom-tree.spec.ts` 既有选择器约定）：
1. `loginAsAdmin` → 进 `QT-20260726-0006` 编辑页 → 报价视图 → 产品卡片 → BOM 树 Tab
2. 断言初始行数 = 6
3. 定位 idx6 行（含 `S-80011` 父路径的那一条 AgNi11#-Ⅰ occurrence，需要按 `__nodeId` 或行内可见特征区分，具体选择器由实现方在 UI 加 `data-node-id` 属性或用现有列文本区分——**这是需要向前端确认的实现细节，见 §10**）→ 点 `×` → 确认删除
4. **不刷新页面**，断言表格行数立即变为 5（`page.locator('.qt-cost-table tbody tr').count()`）
5. 断言另一条 AgNi11#-Ⅰ 行仍存在且列值不变
6. 点「提交审批」→ 断言无 `RowKeyConflictDrawer` 弹出、提交成功提示出现
7. 撤回 → 用墓碑清空恢复 idx6 → 复位断言行数回到 6
8. 全程 `'加载中'` 计数 = 0（`countLoading` 工具函数）
9. 刷新 3 次，断言行数稳定（AP-51）

**预期结果**：所有 `test` 用例 `passed`；`'加载中' final count = 0`；截图证据齐全（参照 `docs/E2E测试方法.md` §4.2 截图规范）

**⚠️ 环境依赖**：由于 `quotation-bom-tree.spec.ts` 头部注释提到"共享后端 8081 跑的是 master（无树端点）"——这个说法在 task-0721 已合并 master（`merge 7cd8f52d`）之后**已过期**，共享 8081 现在应该已内置树端点。执行前先用 `curl` 验证 `tree/delete-preview` 端点在共享 8081 上能返回而非 404，避免误判"环境未就绪"。

### 7.2 回归跑既有 spec 清单

| spec | 关联 AC | 预期 | 备注 |
|---|---|---|---|
| `quotation-flow.spec.ts` | AC-8 非树零回归 | `1 passed`，`'加载中' final count = 0` | 强制项（CLAUDE.md 修改后强制自检） |
| `quotation-bom-tree.spec.ts` | AC-6（task-0721 树渲染/加叶子/剪枝预览） | **预期无法直接跑通**——夹具 `QT-20260721-2067` 在当前 `cpq_db_0724` 不存在（§0.2 风险点 1），必须先由技术总监决定：改指向 `QT-20260726-0006`/`0007`/`0008` 重写夹具常量，或跳过并用 §5/§6 的新用例替代覆盖同等断言点 | **阻断项，需澄清** |
| `costing-bom-tree.spec.ts` | AC-7 核价侧零回归 | 同上，夹具 `QT-20260604-1577` 已不存在 | **阻断项，需澄清** |
| `rowkey-input-dedup.spec.ts` | AC-4 提交去重回归 | 通过 | 与本次 nodeId 判重逻辑有语义重叠，需确认该 spec 覆盖的场景是否为树页签，若是则直接是零回归证据之一 |
| `repro-1982-delete.spec.ts` | AC-8 非树删除零回归（历史 Phase 1 修复的回归锚点） | 通过 | 该 spec 用的单据 `bf0a6a25-...` 也需先确认在 `cpq_db_0724` 里是否存在，方法同上 |

---

## 8. 回归测试清单（修复后必须重测的关联场景）

| 场景 | 原因 | 覆盖用例 |
|---|---|---|
| 非树页签删除（`delete-driver-row`） | 共用 `keepMask`，`nodeId` 参数新增可能误伤旧调用点 | IT-14、UT-B2-02、UT-F1-03 |
| 核价侧渲染 | 共用 `CardSnapshotService`/`FormulaCalculator`，`deleted==null` 分支必须逐字节不变 | IT-16、UT-B0-04、UT-B2-02、UT-F0-02 |
| 提交审批非树组件判重 | `RowKeyUniquenessService.collectConflicts` 改动波及全部组件类型 | IT-09、UT-B5-04 |
| PRUNE 剪枝 + 跨页签级联 | task-0721 既有行为，`retainedParts` 规则不能退化 | IT-11、IT-13、UI-12 |
| 加叶子 `add-leaf` | 响应契约声明"零变化"，需实测确认 | IT-12、UI-13 |
| AP-51 行数纪律 | 历史高频翻车点（`Math.max` 累加陷阱） | IT-10、E2E 专项 spec 步骤 9 |
| 存量墓碑（无 `nodeId`）单据 | 唯一不做数据迁移，必须向后兼容读 | IT-15（**最高优先级回归项，直接用真实存量数据**） |
| `effKey` 四处口径 | F-1 若只改一处会引入新的不一致 | UT-B0-01~04、UT-F0-01~03 |

---

## 9. AC-1 ~ AC-9 覆盖矩阵

| AC | 内容摘要 | 覆盖用例 |
|---|---|---|
| **AC-1** | 删除后不刷新即消失 | UT-B3-04、IT-01、IT-02、UI-01、UI-03、E2E §7.1 步骤 3-4 |
| **AC-2** | 另一条同键行原样保留 | UT-B1-01、UT-F1-01、UT-F2-01、IT-03、UI-02、E2E §7.1 步骤 5 |
| **AC-3** | 提交不再因这两行报行键重复 | UT-B5-01/02、IT-05~07、UI-04、UI-06、E2E §7.1 步骤 6 |
| **AC-4** | 真重复仍须拦截 + 定位可用 | UT-B4-01、UT-B5-03、IT-08、UI-08、UI-11 |
| **AC-5** | 刷新幂等，连续 3 次稳定 | IT-04、IT-10、E2E §7.1 步骤 9 |
| **AC-6** | PRUNE 剪枝/级联/`retainedParts`/加叶子不回归 | UT-B3-04、IT-11、IT-12、IT-13、UI-12、UI-13、`quotation-bom-tree.spec.ts`（**阻断，见 §10**） |
| **AC-7** | 核价侧零回归 | UT-B0-04、UT-B2-02、UT-F0-02、IT-16（**依赖备用夹具，需确认**）、`costing-bom-tree.spec.ts`（**阻断，见 §10**） |
| **AC-8** | 非树页签零回归 + `quotation-flow.spec.ts` 通过 | UT-B5-04、UT-F1-03、IT-09、IT-14、E2E §7.2 |
| **AC-9** | 存量墓碑兼容 | UT-B1-02、UT-F1-02、**IT-15（用真实存量墓碑直接验证，最强证据）** |

补充：**F-1（§12.3）**由 UT-B0-01/02、UT-F0-01 覆盖（单测闭环）；**IT-17** 为只读补充，UI/E2E 级正向证据受限于 §0.2 风险点 3。**§12.1 已知边界**由 UI-14 记录（非失败项）。

---

## 10. 风险点与文档未写清楚之处（技术总监重点关注）

### 10.1 F-1（§12.3）缺可用于 UI/接口正向验证的真实数据（高优先级，影响验收口径）

`需求说明.md §12.3` 明确要求"一旦树页签配公式列（几乎必然）即刻显形"，`fronttask.md`/`backtask.md` 也把 B0/F0 定位为"地基性"改动。但实测（本文写作时用管理员会话核对）：**全库仅有的 3 个 BOM 树组件（`912fa00c`/`422fd880`/`656c9b87`）没有任何一个配置了 `FORMULA` 字段类型**，`QT-20260726-0006/0007/0008` 的 `formulaResults[].values` 恒为空对象。

这意味着：
- 后端/前端**单测**（合成数据）可以完整闭环验证 effKey 三处口径对齐（`UT-B0-02`/`UT-F0-01`），**这是唯一能拿到的强证据**。
- 但**接口/UI 级别**"树页签配了 FORMULA 列，公式值真的取到了"这个最终用户可见的验收标准，**当前没有任何一张单据能跑出来**。

需要技术总监二选一：
1. 接受"单测闭环 = 验收通过"，UI/接口级只做只读的 key 格式对拍（`IT-17`），不强求端到端公式取值证据；
2. 要求造一条测试数据（给 `656c9b87` 或新建一个树组件加一个 `FORMULA` 测试字段，绑一条最小化的测试报价单），走完整闭环——但这已经超出"不新造数据"的边界，需要明确批准，且要评估是否会污染共享组件配置（该组件被 `QT-20260726-0002~0008` 共 6 张单据引用，加字段是全局性改动，不是加一条数据那么轻）。

### 10.2 `cpq_db_0724` 迁移导致多个既有 E2E spec 的硬编码夹具 ID 全部失效（高优先级，影响"零回归"证据链）

不止本次任务相关的两个 spec（`quotation-bom-tree.spec.ts` 用 `QT-20260721-2067`、`costing-bom-tree.spec.ts` 用 `QT-20260604-1577`），凡是 2026-07-24 之前建立、硬编码具体 `quotationId` 的 E2E spec，在当前 `cpq_db_0724`（只有 8 张 2026-07-27 建的单据）里大概率都会在"进单据编辑页"这一步直接 0 行/超时失败——**这不是 repair-0727 引入的回归，是环境切库的遗留问题**，但会直接干扰"我改了代码后跑这些 spec 判断有没有回归"这件事：跑之前必须先确认 spec 本身在**改动前的干净分支**上是不是也失败（同型失败 = 环境噪声，按 `fronttask.md` F4 的 A/B 对照原则处理），否则会把"环境缺数据"误判成"我引入了新 bug"，或反过来把真回归当噪声放过。

建议：正式测试前，先对 `quotation-bom-tree.spec.ts` / `costing-bom-tree.spec.ts` / `repro-1982-delete.spec.ts` 做一次 A/B 空跑（改动前 vs 改动后同型失败与否），把结论写进交付报告，而不是在验收阶段第一次发现。

### 10.3 AC-7 核价侧回归验证依赖的夹具不在文档指定范围内（中优先级）

`需求说明.md`/`backtask.md`/`fronttask.md` 都没有明确指出"用哪张单做核价侧回归"。`QT-20260726-0006`（文档唯一指定的验收数据）没绑核价模板，物理上无法在它身上做核价视图切换。本文档 §2.2 补充探明了 `QT-20260726-0007`/`0008` 可以，但这是我在写用例阶段自行发现、非文档授权的数据，**是否可以拿来做写操作（哪怕只是墓碑写入 + 复位）需要技术总监明确批准**，否则建议把 AC-7 的验证降级为"代码路径审查 + 单测（`deleted==null` 分支未改）"，不做端到端写操作。

### 10.4 IT-08（真撞键场景）能否真实构造存疑（中优先级）

`api.md §3.3` 表格第 4 行"同一节点下两条相同行键的明细"要求一条端到端反向用例，但 `QT-20260726-0006` 树上天然不存在这种结构（6 个 `nodeId` 全部唯一）。§5.9 的构造方案依赖"加叶子候选料号库里能凑出同名料件"，这在写用例阶段无法验证是否可行（候选料号来自当次实际数据库存量，是否存在两个不同料号但业务 `料件` 名称相同的候选，未探明）。如果凑不出，**唯一证据来源退化为单测 `UT-B5-03`**，这条在提交给技术总监时需要显式声明"接口/UI 级证据可能缺失，以单测结论为准"，不能含糊带过当成已覆盖。

### 10.5 树行 UI 层稳定选择器缺失，影响 E2E 脚本编写（低优先级，实现期需前端配合）

`quotation-bom-tree.spec.ts` 现有做法是用 `hasText: 料号文本` 定位行，对"同料号出现两次"的场景（本次 992/AgNi11#-Ⅰ 正是这种）**天然无法用文本精确区分是 idx5 还是 idx6**（`.first()` 只能拿到 DOM 顺序里的第一个，凑巧和 `__nodeId` 顺序一致，但这是隐性假设，不是显式契约）。建议前端在树行 `<tr>` 上加一个 `data-node-id` 属性（哪怕只在 `NODE_ENV==='test'`/开发态渲染），否则 `repair-0727-tree-delete-row.spec.ts` 精确点击"挂 S-80011 那一条而非挂 S-3120014539 那一条"时只能依赖行内隐藏的其它列（如层级缩进宽度）做间接判断，脆弱且难维护。此点建议在前端开发阶段与 `cpq-frontend` 工程师同步，不阻塞本文档交付但会影响 E2E 落地质量。

### 10.6 `api.md` 未明确"墓碑 `effKey` 兼容字段"在新写入路径下的取值来源

`api.md §2.1` 新格式示例里 `effKey` 仍然保留（"兼容字段，不参与匹配"），但 B0（effKey 三处口径对齐，改成带 `__nodeId::` 前缀）落地后，`appendRowTombstone` 写入的这个兼容 `effKey` 字段，到底是写**旧口径**（不带前缀，兼容历史读者）还是**新口径**（带前缀，与 B0 对齐）？两个任务文档（`backtask.md` B0 与 B3）没有交叉说明这一点。虽然文档反复强调"`effKey` 不参与匹配"，理论上写哪个都不影响 `keepMask` 结果，但会影响未来任何"肉眼读墓碑 JSON 做人工排障"的可读性，也可能影响 `UT-B3-01` 的具体断言值该写成什么。建议开工前请后端工程师明确一句话，我好把 `UT-B3-01` 的预期值钉死，否则该用例目前只能断言"含 `nodeId` 字段"，断言不到 `effKey` 具体格式。

---

## 11. 变更记录

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-27 | 编写 test.md | 基于 `需求说明.md`/`api.md`/`backtask.md`/`fronttask.md` 设计用例；用管理员会话核对 `cpq_db_0724` 真实数据（§2 现成数据均为字面值，非编造）；发现 3 项环境/数据风险（§10.1~10.3）需技术总监裁决后才能按计划执行 |
