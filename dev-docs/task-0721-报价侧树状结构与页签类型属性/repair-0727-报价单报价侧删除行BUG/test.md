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

### 0.2 风险点摘要（技术总监已逐条裁决，详见 §10）

在动手写用例前，已用管理员会话核对 `cpq_db_0724` 实际数据，发现三个会直接影响"零回归"验证可行性的环境事实。**技术总监已复核并裁决（结论落进 §10.1~10.3，执行期照此进场，无需再问）**：

1. **`cpq_db_0724` 当前只有 8 张报价单**（`QT-20260726-0001~0008`，均 2026-07-27 建，DRAFT），2026-07-24 之前的历史夹具（含 task-0721 自己的 E2E 专属夹具 `QT-20260721-2067` / `costing-bom-tree.spec.ts` 用的 `QT-20260604-1577`）**在这个库里都不存在**。已有的树相关 E2E spec（`quotation-bom-tree.spec.ts`、`costing-bom-tree.spec.ts`）若不改指向，跑起来会在 `enterQuoteTreeTab` 直接 0 行失败/假阴性，**不能作为本次零回归的证据来源**。
2. **核价侧目前无任何一张"核价单据"**（`costing_order` 表 0 行）；但报价单编辑页内置的"核价视图"（`cardSide==='COSTING'`，靠 `quotation.costing_card_template_id` 是否绑定核价模板决定能不能切）里，`QT-20260726-0006` **未绑核价模板**（`costing_card_template_id IS NULL`），**无法在这张单据上做 AC-7 的 UI 级核价视图回归**；`QT-20260726-0007` / `0008` 绑了核价模板且树组件干净（6 行、0 墓碑），可作为 AC-7 的替代夹具，这属于我在写用例时临时探明的补充数据，**技术总监已批准可用**（`0007` 可写操作、`0008` 只读，详见 §10.3）。
3. **全库 3 个 BOM 树组件都没有配置任何 `FORMULA` 字段**（`912fa00c` / `422fd880` / `656c9b87`）。F-1（§12.3）要求的"树页签配 FORMULA 列时公式值能取到"这条**正向 UI/接口验证目前无米下锅**。**技术总监已裁决**：不给共享组件加测试字段，接受后端/前端单测的合成数据闭环作为验收证据，UI/接口级只做只读 key 格式对拍（详见 §10.1）。

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

### 2.1.1 两个前置基线（技术总监复核指出，务必先读，比"行数"更深一层的问题）

§2.1 描述的是**当前 DB 里躺着的真实状态**（下称「基线 A」）——它本身已经带 1 条旧格式墓碑（针对 idx5），也就是说**此刻页面上只看得到 idx6 这一条 AgNi11#-Ⅰ，idx5 早已不可见**。这导致两类用例不能直接写"前置：§2.1 基线"：

- **验证"删一条、另一条仍在"（AC-2）** 的用例，若直接在基线 A 上删 idx6，删完后 idx5/idx6 两条全灭——因为 idx5 根本不在页面上，没有"另一条"可验证仍在。必须先把树组件墓碑清空、回到**两条都可见**的状态，这类用例才有意义。
- **验证"提交时两行都在"（`api.md §3.3` 表格第 1 行，IT-05）**同理，"两行都在、未删除"必须是真的两行都在，不能默认套用基线 A（基线 A 已经是"一行在、一行不在"的中间态）。

因此定义两个前置基线，全文档统一引用这两个名字：

| 基线 | 状态 | 达成方式 | 用途 |
|---|---|---|---|
| **基线 A（存量态）** | 6 行 snapshot / 1 条旧格式墓碑（无 `nodeId`）/ `row_data` 5 行，**仅 idx6 那条 AgNi 可见，idx5 不可见** | 不做任何操作，DB 现状即是（字面值见 §2.1 表格） | **仅供 IT-15（AC-9 存量兼容）使用，且必须只读，不叠加任何删除/清空操作** |
| **基线 B（干净态）** | 6 行 snapshot / 0 墓碑 / `row_data` 6 行，**idx5、idx6 两条 AgNi 都可见** | 对 `componentId=656c9b87` 调一次 `restore-driver-rows` 清空该组件全部墓碑，并用 SQL 确认 `deleted_row_keys=[]`、`row_data` 长度回到 6 | `IT-01`~`IT-07`、`UI-01/02/04/06`、E2E 专项 spec —— 一切需要"两条 duplicate 真实同时存在"的场景 |

**执行顺序铁律（比行数校正更重要，务必遵守）**：
1. **先跑 `IT-15`**（只读）——它验证的就是基线 A 这个"存量态"本身，必须在任何人碰这个组件的墓碑之前完成，否则验证的就不是真存量数据了。
2. `IT-15` 跑完之后，才允许对树组件调 `restore-driver-rows` 进入基线 B，供后续用例复用。
3. **每一条用完基线 B 的用例，收尾必须用 §2.3 规则 3 的 SQL 字面量精确复位回基线 A**（原样写回那条旧格式、无 `nodeId` 的墓碑）。**不允许**用"再调一次删除 API 把 idx5 删掉"来伪造基线 A——改动后的删除 API 产出的是**新格式**墓碑（含 `nodeId`），字节上不等于原始旧格式墓碑，会悄悄污染 AC-9 的证据基础。
4. 若测试过程中不慎已把基线 A 的原始旧格式墓碑污染掉（例如误用 API 复位后又用相同 JSON 字面量"补写"），**必须在报告中如实说明**，因为字面值相同不代表来源相同——那不再是"改动前系统真实写入的历史数据"，只是长得像，不能再当 AC-9 的证据用。

> 下文 `IT-01`~`IT-10`、`UI-01/02/04/06`、E2E §7.1 的行数与前置已按此重新核对；未特别注明"基线 A"的，一律指基线 B。

### 2.2 备用夹具（补充探明，非文档指定；技术总监已批准，见 §10.3）

| 单据 | `quotationId` | `lineItemId` | 树组件状态 | 用途 |
|---|---|---|---|---|
| `QT-20260726-0007` | `62c3e1bd-5be0-4da4-87a3-9b7ab95e63ed` | `79ec4029-9fa4-4183-8533-cb073f044bd9` | 同一 `656c9b87` 树组件，6 行、**0 墓碑**（干净），`costing_card_template_id` **已绑** | AC-7 核价侧零回归备选。**技术总监已批准可做写操作**（墓碑写入 + 复位），条件：每条用例配套复位 + 报告附复位后校验输出（§10.3） |
| `QT-20260726-0008` | `2168c574-746b-491a-8ba5-7a03851574b3` | `3941020f-e87e-4eab-9c33-1373af0f80d2` | 同上，6 行、0 墓碑，`costing_card_template_id` 已绑 | F-1 `effKey` 格式只读核对（§11.2/§12.3 原文实测用的就是这张单）。**仅只读，不批准写操作**（§10.3） |

### 2.3 通用复位纪律

1. **任何会写墓碑 / 改 `row_data` / 触发提交状态流转的用例，执行前先跑一遍确认当前状态与目标基线（§2.1.1）一致**（防止上一条用例复位失败留下脏状态污染下一条）。
2. `restore-driver-rows` 只用来**从基线 A 进入基线 B**（清空全部墓碑），或复位其它非树页签（加工费/材料成本/外购件成本，这些没有"保留旧格式墓碑"的顾虑）。**对树组件 `656c9b87` 绝不能把 `restore-driver-rows` 当"最终复位手段"使用**——它会把基线 A 那条旧格式墓碑一并清空，之后如果只是留在"0 墓碑"状态或用 API 重删 idx5，都得不到字节级相同的基线 A（后者会产出新格式墓碑）。`withdraw` 把 `SUBMITTED` 打回 `DRAFT` 没有这个顾虑，可以正常用。
3. **树组件收尾复位一律用下面的 SQL 字面量精确写回基线 A**（不接受用业务端点"凑"出来）：
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

> **技术总监复核发现的关键缺陷（§10.6，开工前必读）**：`QuotationTreeService` 现状是手工拼 `nodeId + "::" + rowKey` 写墓碑 `effKey`；但 B0 对齐后，前端传来的 `rowKey`（即 `__effKey`）**本身就已经带 `nodeId::` 前缀**了——两者一拼会产出 `nodeId::nodeId::base` 的**双重前缀**。B3 落地时**必须直接写"对齐后的 effKey"，不再手工拼接**。下面 `UT-B3-01` 的断言已按这个结论钉死；新增 `UT-B3-05` 专门防这个回归。

| # | 步骤 | 预期结果 |
|---|---|---|
| **UT-B3-01** | ROW 模式删除，调 `appendRowTombstone`（模拟真实链路：前端传入的 `rowKey` 已经是 B0 对齐后带 `nodeId::` 前缀的 effKey） | 写入的墓碑 JSON：`effKey` **恰好等于该行 B0 对齐后的 effKey**（即前端传入的 `rowKey` 原样，**不含重复的 `nodeId` 段**，不是 `nodeId + "::" + rowKey` 拼接结果）；`nodeId` 字段独立存在且等于被删节点的 `__nodeId` |
| **UT-B3-02** | 级联分支（非树页签行）调 `appendRowTombstone` | 写入的墓碑 `nodeId=null`（保持 fp 单键语义，不影响非树页签既有匹配） |
| **UT-B3-03** | 幂等：连续两次对同一 `(nodeId, fp)` 调用删除 | 墓碑判重条件是 **"`fp` 相同 且 `nodeId` 相同"**，第二次不重复追加（对应 `api.md §1.2` "重复删同一 `(nodeId, rowKey)` → 200 且墓碑不重复追加"） |
| **UT-B3-04** | `refreshQuoteProjection` 返回 `null`（模拟非 `DRAFT` 场景） | `executeDelete` 必须回落 `snapshotQuoteSideOnly(li, q)` 兜底，且响应体**不带** `componentData`；不得静默不刷新（抛异常或空响应都算失败） |
| **UT-B3-05**（防回归，技术总监指定） | 用真实带前缀的 `rowKey`（如 `"S-3120014539/S-80011/992::AgNi11#-Ⅰ"`）走一遍 `executeDelete` → `appendRowTombstone` 全链路 | 写入的墓碑 `effKey` 字符串里 `nodeId`（如 `"S-3120014539/S-80011/992"`）**只出现一次**，不是 `"S-3120014539/S-80011/992::S-3120014539/S-80011/992::AgNi11#-Ⅰ"` 这种双重前缀；用字符串断言 `effKey.indexOf(nodeId) === effKey.lastIndexOf(nodeId)` 或等价手段验证 |

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

**前置**：**基线 B**（§2.1.1）—— 先对 `componentId=656c9b87...` 调 `restore-driver-rows` 清空树组件全部墓碑，SQL 确认 `snapshot_rows=6`、`row_data=6`（idx5、idx6 两条 AgNi 都在）、`deleted_row_keys=[]`。**执行前必须已经跑过 `IT-15`**（§2.1.1 顺序铁律）。

**步骤**：
```bash
# 0. 先进基线 B（见上）
curl -s --noproxy '*' -b "$COOKIE_JAR" -X POST \
  http://localhost:8081/api/cpq/quotations/69aab7ec-9140-427b-b717-ed0a806485d1/line-items/dfee1e78-94c7-4af1-899b-caa9b60fd29a/restore-driver-rows \
  -H 'Content-Type: application/json' \
  -d '{"componentId":"656c9b87-cda5-4c32-8d72-45d94714f77a"}'

# 1. 记录本次删除前该组件 subtotal（见预期结果第 6 点）
psql ... -c "SELECT subtotal FROM quotation_line_component_data WHERE id='dda39b0c-3690-4a2c-8426-b3a4edfcee17';"

# 2. 预览（拿 previewToken，对 idx6）
curl -s --noproxy '*' -b "$COOKIE_JAR" -X POST \
  http://localhost:8081/api/cpq/quotations/69aab7ec-9140-427b-b717-ed0a806485d1/line-items/dfee1e78-94c7-4af1-899b-caa9b60fd29a/tree/delete-preview \
  -H 'Content-Type: application/json' \
  -d '{"componentId":"656c9b87-cda5-4c32-8d72-45d94714f77a","mode":"ROW","nodeId":"S-3120014539/S-80011/992"}'

# 3. 执行删除（rowKey 用预览响应里对应该行的 effKey；previewToken 用步骤2返回值）
curl -s --noproxy '*' -b "$COOKIE_JAR" -X POST \
  http://localhost:8081/api/cpq/quotations/69aab7ec-9140-427b-b717-ed0a806485d1/line-items/dfee1e78-94c7-4af1-899b-caa9b60fd29a/tree/delete \
  -H 'Content-Type: application/json' \
  -d '{"componentId":"656c9b87-cda5-4c32-8d72-45d94714f77a","mode":"ROW","nodeId":"S-3120014539/S-80011/992","rowKey":"<步骤2回填>","previewToken":"<步骤2回填>"}'
```

**预期结果（均为可判定断言）**：
1. HTTP 200
2. 响应 `data.componentData` **存在且非空数组**（改动前实测缺失，见需求说明 §11.1）
3. `componentData` 中 `componentId=656c9b87...` 的条目：`JSON.parse(rowData).length === 5`（6 − 1，本次从基线 B 的 6 行删 1 行）
4. `data.deletedRowKeys`（或 `componentData[].deletedRowKeys`）解析后长度 = **1**（本次是从 0 墓碑新增第 1 条，不是叠加在存量墓碑上），且该条含 `nodeId="S-3120014539/S-80011/992"`
5. **不依赖任何后续"刷新基础数据"调用**，仅凭这一次响应即可判定该行已从权威数据源移除——满足 AC-1"当帧消失"的后端前提
6. **小计重算**（需求说明 §4.1"页签小计随之重算"）：读取 `componentData[].subtotal`。**已核实**该组件全部字段 `is_subtotal`/`is_amount` 均为 `false`（无参与小计的列），且删除前 `subtotal` 实测为 `0.0000`——**本条在当前夹具下只能断言"小计重算流程被正确调用、返回值非 `null`/非 `NaN`、且与删除前读到的值一致（= 0，因为没有可参与求和的列，理论上不应该变）"，不能断言"数值发生了变化"**。如需验证"小计随被删行贡献值变化"这条更强的断言，需要一个至少配了 1 个 `is_subtotal:true` 字段的树组件夹具，目前没有（与 §10.1 F-1 同类数据缺口，已并入该风险点一并登记 BACKLOG，见 §10.1）。

**验证手段**：接口测试（curl）+ SQL 复核（见 IT-02）

---

### 5.2 IT-02 —— ①-b 专项：`snapshot_rows`/`row_data`/`deleted_row_keys` 三者一致性 + 同序对齐

**关联**：需求说明 §11.1 实测表格（"改动前"已确认错位），本用例断言"改动后"不再错位

**前置**：`IT-01` 执行完毕（基线 B → 已删 idx6，此时 `row_data` 应为 5 行）

**步骤**：
```sql
SELECT jsonb_array_length(snapshot_rows) AS snap_len,
       jsonb_array_length(row_data) AS rowdata_len,
       jsonb_array_length(deleted_row_keys) AS tomb_len,
       row_data
FROM quotation_line_component_data
WHERE id = 'dda39b0c-3690-4a2c-8426-b3a4edfcee17';
```

**预期结果**：

| 字段 | 改动前实测（§11.1，已知坏，起点是基线 A 那种"叠加删除"场景） | 本用例断言的改动后值（起点基线 B） |
|---|---|---|
| `snap_len` | 6 | 6（不变，树结构不因行删除变化） |
| `rowdata_len` | 5（未变，**含被删的 AgNi 行**，即改动前"删了没生效"的坏现象） | **5**（= 6 − 1 墓碑，两存储不再错位——**数值恰好与改动前坏值相同，务必不要因为数字一样就误判"没改对"，关键是看下面第③步的内容级对齐**） |
| `tomb_len` | 2 | 1 |

再调一次 `POST .../refresh-card-snapshot`（或前端「刷新基础数据」等价端点），复核 `rowdata_len` 仍为 5（不因刷新而回退到 6 或累加，AP-51 纪律）。

**同序对齐补充断言（不仅比数量，还要比内容和顺序）**：
1. 把上面 SQL 查到的 `row_data`（5 个对象，各带 `料件` 字段）依序取出 `料件` 值，得到序列 `S1 = [主料1, 投入零件1, 组成件1, H65, AgNi11#-Ⅰ]`。
2. 同一次响应里的 `quoteCardValues`（或整单 `GET`）中，该组件 `baseRows` 按墓碑过滤后（用 §2.2 `api.md §2.2` 规则手工模拟一遍，或直接读前端会渲染的那份 `kept` 结果）依序取出 `driverRow.料件`（或 `_料件`）值，得到序列 `S2`。
3. 断言 `S1` 与 `S2` **逐位相等**（不仅长度相等）——这是"行数对但顺序错位"这类历史故障（AP-54 族）的直接证据点，比单纯比 `length` 更严格。

**验证手段**：接口测试 + SQL

---

### 5.3 IT-03 —— 症状② 核心闭环：只删一条，另一条逐字段值不变

**关联**：AC-2

**前置**：**基线 B**（§2.1.1）——务必先清空树组件全部墓碑、确认 idx5/idx6 两条 AgNi 都在（6 行），**不能**直接套用 §2.1 的存量态：存量态里 idx5 本来就不可见，没有"另一条"可验证。若紧接在 `IT-01`/`IT-02` 之后跑，可复用其已进入的基线 B 状态；若独立跑，需重新执行"清空 → 确认 6 行"。

**步骤**：
1. 记录删除前 idx5（`nodeId="S-3120014539/992"`）在 `row_data` 中对应条目的**完整 JSON 对象**（不只挑 `料件`/`row_index` 两个字段——理由见下方"断言范围"）。
2. 删除 idx6（`nodeId="S-3120014539/S-80011/992"`），流程同 `IT-01` 步骤 2-3。
3. 重新查 `row_data`，定位删除后仍带 `料件="AgNi11#-Ⅰ"` 的那一条（此时应该只有一条，即 idx5 的），提取其**完整 JSON 对象**。
4. 对步骤 1 和步骤 3 取到的两个 JSON 对象做**整体 diff**（键的并集逐一比较，允许的例外只有 `row_index`——因为前面 idx6 那条被摘掉后，idx5 后续所有行的物化下标会整体前移 1，这是设计内的正常位移，不算"串行"）。

**预期结果**：
- idx5 行仍在，除 `row_index` 因整体前移而改变之外，**其余全部字段值与删除前逐字段一致**（无串行、无值混淆）——**断言范围覆盖该行当前实际拥有的全部键**，不只是 `料件`。当前该组件没有配置输入列/公式列（见 §10.1），idx5 行实际只有 `料件`/`row_index` 两个键；若后续该组件加了输入列或公式列（哪怕只是为了满足 §10.1 F-1 的验证补造），同一套"整体 diff、排除 `row_index`"的方法必须原样覆盖新增的列，不能因为字段变多了就退回到"只挑几个字段比对"。
- 只有 idx6 那一条从 `row_data` 消失，`row_data.length` 从 6 变 5（而非变 4 或不变）

**验证手段**：接口测试（curl + jq 对比，`jq` 直接对两个 JSON 对象做 `diff`/逐 key 比较）

---

### 5.4 IT-04 —— 幂等：重复删除同一 `(nodeId, fp)`

**关联**：`api.md §1.2` 幂等条款

**前置**：`IT-01` 执行完（基线 B → 已删 idx6，此时 `deleted_row_keys` 长度应为 1）

**步骤**：对同一 `nodeId="S-3120014539/S-80011/992"` 再发一次 `tree/delete-preview` → `tree/delete`

**预期结果**：HTTP 200；`deleted_row_keys` 长度**仍为 1**（不重复追加第 2 条）

**验证手段**：接口测试 + SQL

**收尾**：本轮 `IT-01`~`IT-04` 结束后，按 §2.1.1 规则 3 用 SQL 字面量把树组件精确复位回基线 A，供后续需要基线 A 的用例（若有）使用；若后续用例只需要基线 B，可以留在基线 B 继续。

---

### 5.5~5.9 IT-05~IT-09 —— `api.md §3.3` 提交行为对照表逐行验证（P0，AC-3/AC-4）

> 每一行都从**基线 B**（§2.1.1：先清空树组件全部墓碑，确认 6 行 0 墓碑）开始，执行后立即复位（`withdraw` 打回 DRAFT + 按 §2.1.1 规则 3 用 SQL 字面量精确复位回基线 A），不得让前一条的状态污染下一条。**这五条必须在 `IT-15` 之后才能跑**（§2.1.1 顺序铁律）。

#### IT-05 —— 表格第 1 行：两行都在，未删除

**前置**：**基线 B**——本条是"两行都在"这一行的直接验证，**必须显式先做清空**，不能默认沿用 §2.1 存量态（那里 idx5 已经不可见，不是"两行都在"）：
```bash
curl -s --noproxy '*' -b "$COOKIE_JAR" -X POST \
  http://localhost:8081/api/cpq/quotations/69aab7ec-9140-427b-b717-ed0a806485d1/line-items/dfee1e78-94c7-4af1-899b-caa9b60fd29a/restore-driver-rows \
  -H 'Content-Type: application/json' \
  -d '{"componentId":"656c9b87-cda5-4c32-8d72-45d94714f77a"}'
# SQL 确认 row_data=6、deleted_row_keys=[] 后再继续
```

**预期（改动前 vs 改动后）**：`POST /api/cpq/quotations/{id}/submit` 从 422 变为 **成功**（`quotation.status → SUBMITTED`）

**步骤**：
```bash
curl -s --noproxy '*' -b "$COOKIE_JAR" -X POST \
  http://localhost:8081/api/cpq/quotations/69aab7ec-9140-427b-b717-ed0a806485d1/submit
```
**预期结果**：HTTP 200（不是 422），响应 `status=SUBMITTED`
**复位**：`POST .../withdraw`，再按 §2.1.1 规则 3 SQL 复位回基线 A

#### IT-06 —— 表格第 2 行：删掉其中一行（idx6），另一行仍在

**步骤**：从基线 B 执行 `IT-01`（删 idx6，此时 idx5 仍在），再 `submit`
**预期结果**：HTTP 200（不是 422）
**复位**：`withdraw` + 按 §2.1.1 规则 3 SQL 复位回基线 A

#### IT-07 —— 表格第 3 行：两行都删（P0 核心场景，用户实际报的③）

**步骤**：从基线 B 依次删 idx5、idx6（各自走 delete-preview → delete），再 `submit`
**预期结果**：HTTP 200（改动前实测 422，见需求说明 §11.3 原文错误信息）——**这是本次修复最关键的单条断言**
**复位**：`withdraw` + 按 §2.1.1 规则 3 SQL 复位回基线 A

#### IT-08 —— 表格第 4 行（反向）：同一节点下两条相同行键的明细，仍须拦截

**关联**：AC-4，`api.md §3.3` 第 4 行

**前置**：QT-20260726-0006 的树上**不存在**这种结构（6 个 `nodeId` 各自唯一，见 §2.1），需要临时构造。**不修改 QT-20260726-0006 已有 6 行**，改用「加叶子」在某个"零件"节点（如 idx2 `S-80011`，`__nodeType='零件'`）下新增一片与已有兄弟节点业务行键相同的叶子。基线 A/B 均可（这个节点与 992/AgNi 的重复占用无关），建议用基线 B 减少心智负担。

**步骤**：
1. `POST tree/add-leaf`：`hostNodeId="S-3120014539/S-80011"`，选一个候选料号，使新叶子的 `料件` 值与该节点下已存在的兄弟叶子相同。
2. `submit`
3. 断言 422，且 `conflicts[].rowIndices` 指向这两条新叶子

**预期结果**：HTTP 422（**仍拦截**，不因 nodeId 维度放过真撞键）

**复位**：用 `tree/delete`（ROW 模式）删掉新加的叶子行，验证树恢复 6 行

**技术总监已裁决（§10.4）：尽力构造，若候选料号库凑不出"同节点同行键"组合，本条降级为"接口级证据缺失，以单测 `UT-B5-03` 为准"，报告中必须显式写这句话，不得含糊带过当成已覆盖。**

#### IT-09 —— 表格第 5 行（反向）：非树页签同组件行键重复，仍须拦截（逐字节不变）

**关联**：AC-4/AC-8，`api.md §3.3` 第 5 行 + `api.md §4` 零回归红线

**前置**：需要一个非树页签且存在真实行键重复的场景。QT-20260726-0006 当前非树页签（材料成本/外购件成本/加工费）均各只 1 行，无法直接复现"组件内行键重复"。

**步骤（只读优先）**：
1. 优先方案：直接跑 `RowKeyUniquenessServiceTest`（`UT-B5-04`）作为权威证据，本条只做**接口契约层面**的回归检查——确认非树页签调用 `submit` 时命中的仍是**旧的判重代码路径**（无 `@nodeId` 后缀），可通过在请求前后抓 `RowKeyConflictException.rowKey` 格式核对（树行会带隐含 nodeId 上下文，非树行的 `rowKey` 字符串本身格式不变）。
2. 若需要一个端到端可复现的非树撞键场景，需要另建一条最小化夹具（不在 QT-20260726-0006 上做，避免破坏主夹具），执行前需申报。默认按方案 1 执行。

**预期结果**：非树页签撞键报 422 的行为与改动前**逐字节一致**（错误信息格式、`rowIndices` 语义都不变）

**验证手段**：单元测试为主（`UT-B5-04`）+ 代码审查确认非树分支未被新逻辑触碰；接口层面为补充

---

### 5.10 IT-10 —— AC-5 幂等 + AP-51 行数纪律：连续刷新 3 次行数稳定

**关联**：AC-5

**前置**：`IT-01` 执行完（基线 B → 已删 idx6，`row_data` 应为 5 行）

**步骤**：连续 3 次调用整单 `GET /api/cpq/quotations/69aab7ec-.../` （或等价的 `refresh-card-snapshot`），每次记录 `row_data.length`

**预期结果**：3 次均为 5（不累加到 6、不回退、不跳到 4）

**验证手段**：接口测试

---

### 5.11 IT-11 —— AC-6 剪枝级联零回归：DAG 最后一个 occurrence 被删后 `retainedParts` 状态切换

**关联**：AC-6，`api.md §4` 不变更清单里的"delete-preview 请求响应零变化"契约边界验证

**前置**：**基线 B**（§2.1.1，idx5、idx6 两条都在，材料成本页签 992 那行也在）——**不能**用 §2.1 存量态，那里 idx5 已经是删除态，"先删 idx5" 这一步无从做起。

**步骤**：
1. 先删 idx5（`S-3120014539/992`）——此时 992 在树上仍有 idx6（`S-80011` 那支）这一个 occurrence，`delete-preview` 对 idx6 的 `retainedParts` 应仍包含 992（因为树上还有它自己这一条要删的以外，材料成本页签的 992 行不该被级联删除，理由="仍有其他引用"）。
2. 再删 idx6（最后一个 occurrence）。此时对材料成本页签的 992 行再跑一次 `delete-preview`（PRUNE 模式，`nodeId` 传材料成本页签自己的节点，或走 ROW 级联判断），`retainedParts` 应不再列出 992（树上已无剩余引用）。

**预期结果**：`retainedParts` 内容随"树上剩余 occurrence 数量"正确切换，与 task-0721 交付时的规则一致（`docs/反模式.md` 三大核心模块基线 §7.B 场景描述的"树上无剩余 occurrence 才删"）

**复位**：先确认材料成本页签的 992 行是否被实际级联删除（若是，需连同其 `row_data`/`deleted_row_keys` 一并按 §2.3 规则 4 的记录方式复位），再按 §2.1.1 规则 3 SQL 复位树组件回基线 A

**验证手段**：接口测试（curl `delete-preview`，只读部分优先，最小化写操作）

---

### 5.12 IT-12 —— AC-6 `add-leaf` 零回归

**关联**：`api.md §4` 不变更清单

**前置**：基线 A 或基线 B 均可（本条操作的是 idx2/idx3 节点，与 992 那对 duplicate 的墓碑状态无关）；执行前用 SQL 确认树组件当前实际处于哪个基线，写进报告避免和其它用例的状态判断混淆。

**步骤**：`POST tree/add-leaf`（`hostNodeId` 选 idx2 或 idx3 的"零件"节点，`partNo` 任选一个候选）

**预期结果**：请求/响应结构与改动前逐字节一致（`api.md §4`："`tree/add-leaf`、`tree/delete-preview` 请求与响应零变化"）；新叶子在树上正确出现，回灌 `quoteCardValues` 即可见（`backtask.md` 备注"预计无需改动，但须实测确认"——本用例就是那个实测）

**复位**：`tree/delete`（ROW）删除新加的叶子

**验证手段**：接口测试

---

### 5.13 IT-13 —— AC-6 `delete-preview` 契约零变化

**关联**：`api.md §4`

**前置**：基线 A 或基线 B 均可（idx6 在两个基线下都是可见/存在的，`delete-preview` 是只读操作不产生副作用）

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

**⚠️ 执行顺序：本条必须是本文件第一个碰这张单据树组件的用例**（§2.1.1 顺序铁律）——一旦任何其它用例先把这条旧格式墓碑清空/替换成新格式墓碑，本条就再也测不出"真存量数据"了，只能测"长得像存量数据但其实是新格式"。

**前置**：基线 A（§2.1 表格，未做任何改动的原始 DB 状态）——**现存的那条墓碑本身就是旧格式**（`{"fp":"...","effKey":"..."}`，无 `nodeId` 键），这是改动前系统真实写入的存量数据，完全符合"改动前写入的历史墓碑"这一 AC-9 场景定义，**不需要额外构造**。

**步骤**：
1. 不做任何删除操作，直接读当前 `row_data`（应为 5 行，idx6 那条 AgNi 仍在，idx5 已被这条旧墓碑排除）
2. 触发一次系统重算路径（如 `refresh-card-snapshot`），观察是否因为升级了 `nodeId` 匹配逻辑而导致旧墓碑失效（idx5 复活变回 6 行）或误伤（idx6 也被这条只对 idx5 生效的旧墓碑连带删除，变成 3 行）

**预期结果**：`row_data` 仍为 5 行，且是 idx5 缺失、idx6 仍在的那个具体组合（不是"行全回来"也不是"多删"）——精确匹配 `api.md §2.2` "旧墓碑（无 nodeId）× 树行 → 退化 fp 单键 → 存量单据行为逐字节不变"

**验证手段**：接口测试 + SQL 逐字段核对

---

### 5.16 IT-16 —— AC-7 核价侧零回归（备用夹具 `QT-20260726-0007`）

**关联**：AC-7

**⚠️ 前置说明**：`QT-20260726-0006` 未绑核价模板，无法做本条；改用 §2.2 的 `QT-20260726-0007`（**技术总监已批准可做写操作**，见 §10.3；条件：本条必须配套复位，且报告里附复位后的校验输出）。

**前置**：`QT-20260726-0007`，`lineItemId=79ec4029-9fa4-4183-8533-cb073f044bd9`，树组件 6 行 0 墓碑（本身已是干净态，无需额外清空）

**步骤**：
1. 在报价侧对该单的树组件删除一行（走 `tree/delete`，ROW 模式，任选 idx）
2. 分别读取该 `lineItem` 的报价侧 `quoteCardValues` 与核价侧 `costingCardValues`（若接口暴露）或直接查 `quotation_line_component_data` 是否存在报价侧/核价侧分离存储

**预期结果**：核价侧渲染/取数路径完全不读、不受本次树删除墓碑影响（`keepMask` 在核价侧 `deleted==null` 分支，不进新逻辑）；若报价侧与核价侧共享同一份 `quotation_line_component_data`（据 `QuotationStep2.tsx` 的 `side` 参数区分渲染而非存储分离），则需确认核价视图渲染时传入的 `deletedRowKeys` 是否被正确置为 `null`/不生效

**复位**：`restore-driver-rows` 清墓碑，随后 SQL 确认 `deleted_row_keys=[]`、`row_data` 长度回到 6（该单没有"保留旧格式墓碑"的顾虑，`restore-driver-rows` 可以直接当最终复位手段用，不需要像树组件 `656c9b87`/`QT-20260726-0006` 那样额外用 SQL 字面量精确复位）——**复位后的这次 SQL 查询输出必须附进报告**

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

> **前置基线提醒（§2.1.1）**：本节所有涉及"删一行、看另一行是否还在"或"提交是否放行"的用例（UI-01/02/04/06/08），前置一律是**基线 B**（先清空树组件墓碑，确认 6 行、idx5/idx6 都可见），**不是** §2.1 描述的存量态——存量态里 idx5 已经不可见，"删一条留一条"在那个状态上做不出来。进入基线 B 的方式：管理员在「组件管理」或直接 curl 调 `restore-driver-rows`（`componentId=656c9b87...`）清空该组件全部墓碑，刷新页面确认 6 行。本节顺序也必须排在 `IT-15` 之后（避免提前清空存量墓碑）。

| # | 关联 | 前置 | 步骤 | 预期结果（可判定） |
|---|---|---|---|---|
| **UI-01** | AC-1 | **基线 B**（见上方提醒），浏览器打开该单编辑页，确认页面上 idx5、idx6 两条 AgNi11#-Ⅰ 都可见（行数 6） | 点 idx6 行（`S-80011` 支下的 AgNi11#-Ⅰ）的 `×` → 抽屉展示影响面 → 点「确认删除」 | **不点任何刷新按钮**，该行在当前页面表格中立即消失（行数从 6 变 5）；抽屉自动关闭；无红色报错 |
| **UI-02** | AC-2 | 承 UI-01 | 观察 idx5 行（`S-3120014539` 支下的 AgNi11#-Ⅰ）**全部列**（不只挑一两个字段看） | 仍在原位，**当前实际展示的每一列**（该行目前只有料件等固定列，若组件后续加了输入列/公式列同样要看）取值与删除前一致，未发生错位或串值；行内容与 `IT-03` 的接口层 JSON diff 结论一致（两者互相印证） |
| **UI-03** | AC-1（体验细节） | 承 UI-01 | 观察删除确认瞬间到行消失之间的过渡 | 无闪烁、无"先消失再重新出现"的跳动（`pendingDeleteRef` 在途窗口保护生效） |
| **UI-04** | AC-3 | 承 UI-01/02，页面上此时无重复行键 | 点「提交审批」 | 提交成功（弹出成功提示或状态变为已提交），**不再**弹出"行键重复"报错 |
| **UI-05**（复位） | — | 承 UI-04 | 撤回提交（找「撤回」按钮或走 `withdraw`）；**不要**用「墓碑清空」按钮当作"把 idx6 恢复"的手段——那会把 idx5/idx6 两条墓碑一起清空，回到的是"两条都在"而不是原始存量态；改用 §2.1.1 规则 3 的 SQL 字面量精确复位 | 单据恢复 §2.1 存量态（基线 A：1 条旧格式墓碑、`row_data` 5 行），供后续需要基线 A 的用例复用 |
| **UI-06** | AC-1/AC-3 组合闭环 | **基线 B**（先清空树组件墓碑，确认 6 行都在） | 依次删 idx5、idx6（两次都走 UI），再提交 | 两次删除均当帧消失，互不影响；提交成功——这是用户原始报告的完整复现场景 |
| **UI-07**（复位） | — | 承 UI-06 | 同 UI-05，用 §2.1.1 规则 3 SQL 字面量精确复位回基线 A，**不要**用业务端点简单清空了事 | 回到基线 A |
| **UI-08** | AC-4 | **基线 B**（idx5、idx6 都在，未删除） | 不删除任何行，直接点「提交审批」 | 由于 idx5/idx6 行键（料件=AgNi11#-Ⅰ）在**未裁决 nodeId 判重前**本就相同，本条验证：`Q2` 裁决生效后，**同料号挂不同父不算撞键，应放行**，不应再报错——即"两行都不删也能提交"，与 `IT-05` 是同一断言的 UI 版本 |
| **UI-09**（复位） | — | 承 UI-08 | 同 UI-05，SQL 字面量精确复位回基线 A | 回到基线 A |
| **UI-10** | §4.2「刷新基础数据」回归本职 | 删除一行后 | 点「刷新基础数据」按钮 | 按钮拉取最新已审核基础数据（原本职功能），不再兼任"让删除生效"的角色；点击前后被删的行不应因为这次点击而复活或产生新的变化 |
| **UI-11** | §4.2 提交失败抽屉行为不变 | 用 `IT-08` 构造出的真实撞键场景（若能构造成功，见 §10.4 已裁决的降级策略） | 点「提交审批」触发 422 | `RowKeyConflictDrawer` 弹出，列出的行号可点击「定位」跳到对应行；若该场景无法构造成功，本条标记为**受阻**并在报告里注明原因（依赖 `IT-08` 的候选料号可用性），不得含糊算作已覆盖 |
| **UI-12** | AC-6（剪枝 UI 回归） | 基线 A 或基线 B 均可（idx2/idx3 节点与 992 duplicate 无关） | 对某个"零件"节点点剪枝按钮（`button[title="剪掉该节点及其子树..."]`），查看抽屉三块内容 | ①树节点 ②级联页签行 ③保留料号 三块内容与 task-0721 交付时的展示逻辑一致；**只预览不确认**，关闭抽屉不产生写副作用 |
| **UI-13** | AC-6（加叶子 UI 回归） | 基线 A 或基线 B 均可 | 对"零件"节点点「+」→ 候选料号抽屉 → 选一个 → 提交 | 新叶子正确出现在树上，`quoteCardValues` 回灌后可见；测完用 UI 删除该新叶子行复位 |
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
2. 前置动作：先调 `restore-driver-rows` 把树组件清成基线 B（0 墓碑），刷新页面
3. 断言初始行数 = **6**（基线 B，idx5/idx6 都在——**不是** 5，§2.1 描述的存量态不是本 spec 的起点）
4. 定位 idx6 行（含 `S-80011` 父路径的那一条 AgNi11#-Ⅰ occurrence）→ 点 `×` → 确认删除。选择器用 `[data-node-id="S-3120014539/S-80011/992"]`（技术总监已批准前端在树行 `<tr>` 加 `data-node-id` 属性，见 §10.5，无需再靠列文本/缩进宽度间接猜测）
5. **不刷新页面**，断言表格行数立即变为 **5**（`page.locator('.qt-cost-table tbody tr').count()`）
6. 断言另一条 AgNi11#-Ⅰ 行（`[data-node-id="S-3120014539/992"]`，即 idx5）仍存在且列值不变
7. 点「提交审批」→ 断言无 `RowKeyConflictDrawer` 弹出、提交成功提示出现
8. 撤回（`withdraw`）→ **用 §2.1.1 规则 3 的 SQL 字面量精确复位回基线 A**（不是简单调墓碑清空，那样只会停在"0 墓碑"而不是原始存量态）→ 复位后 SQL 断言 `row_data` 长度回到 5、墓碑 1 条且为旧格式
9. 全程 `'加载中'` 计数 = 0（`countLoading` 工具函数）
10. 从基线 B 重新开始，刷新 3 次，断言行数稳定 = 6（AP-51；若在删除后的状态刷新则应稳定 = 5，两种口径都要各测一次，不要混着断言）

**预期结果**：所有 `test` 用例 `passed`；`'加载中' final count = 0`；截图证据齐全（参照 `docs/E2E测试方法.md` §4.2 截图规范）

**⚠️ 环境依赖**：由于 `quotation-bom-tree.spec.ts` 头部注释提到"共享后端 8081 跑的是 master（无树端点）"——这个说法在 task-0721 已合并 master（`merge 7cd8f52d`）之后**已过期**，共享 8081 现在应该已内置树端点。执行前先用 `curl` 验证 `tree/delete-preview` 端点在共享 8081 上能返回而非 404，避免误判"环境未就绪"。

### 7.2 回归跑既有 spec 清单

**技术总监已裁决（§10.2）：强制 A/B 空跑，不在本次重写夹具。** 执行顺序：
1. 先在**主工作区（未改动的 `master`）**跑一遍下列会用到硬编码夹具 ID 的 spec，记录失败形态（哪一步失败、什么错误）。
2. 再在本 worktree（改动后）跑同一批 spec。
3. **同型失败**（都在"进编辑页"这一步 0 行/超时）= 环境噪声，写进报告即可放行；**只有"master 通过、worktree 失败"才是真回归**，必须定位修复。
4. 夹具重建（把这些 spec 的硬编码 ID 换成 `cpq_db_0724` 里真实存在的单据）**登记 BACKLOG，不塞进本次**。

| spec | 关联 AC | 预期 | 备注 |
|---|---|---|---|
| `quotation-flow.spec.ts` | AC-8 非树零回归 | `1 passed`，`'加载中' final count = 0` | 强制项（CLAUDE.md 修改后强制自检）；此 spec 走登录后动态建单流程，不依赖历史硬编码 ID，理论上不受 §0.2 风险点 1 影响，**应该能正常跑通**，不需要 A/B 对照 |
| `quotation-bom-tree.spec.ts` | AC-6（task-0721 树渲染/加叶子/剪枝预览） | 按上方 A/B 流程判定；大概率 master 与 worktree **同型失败**（夹具 `QT-20260721-2067` 在当前 `cpq_db_0724` 都不存在），按环境噪声放行 | 需要 A/B 空跑出具体结论 |
| `costing-bom-tree.spec.ts` | AC-7 核价侧零回归 | 同上，夹具 `QT-20260604-1577` 已不存在，预期同型失败 | 需要 A/B 空跑出具体结论 |
| `rowkey-input-dedup.spec.ts` | AC-4 提交去重回归 | 通过 | 与本次 nodeId 判重逻辑有语义重叠，需确认该 spec 覆盖的场景是否为树页签，若是则直接是零回归证据之一；先确认其夹具是否也已随迁移失效 |
| `repro-1982-delete.spec.ts` | AC-8 非树删除零回归（历史 Phase 1 修复的回归锚点） | 按 A/B 流程判定 | 该 spec 用的单据 `bf0a6a25-...` 也需先确认在 `cpq_db_0724` 里是否存在，方法同上 |

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
| **AC-6** | PRUNE 剪枝/级联/`retainedParts`/加叶子不回归 | UT-B3-04、IT-11、IT-12、IT-13、UI-12、UI-13、`quotation-bom-tree.spec.ts`（**按 §10.2 裁决走 A/B 空跑判定，同型失败可放行**） |
| **AC-7** | 核价侧零回归 | UT-B0-04、UT-B2-02、UT-F0-02、IT-16（**备用夹具 `QT-20260726-0007`，技术总监已批准，见 §10.3**）、`costing-bom-tree.spec.ts`（**按 §10.2 裁决走 A/B 空跑判定**） |
| **AC-8** | 非树页签零回归 + `quotation-flow.spec.ts` 通过 | UT-B5-04、UT-F1-03、IT-09、IT-14、E2E §7.2 |
| **AC-9** | 存量墓碑兼容 | UT-B1-02、UT-F1-02、**IT-15（用真实存量墓碑直接验证，最强证据）** |

补充：**F-1（§12.3）**由 UT-B0-01/02、UT-F0-01 覆盖（单测闭环）；**IT-17** 为只读补充，UI/E2E 级正向证据受限于 §0.2 风险点 3。**§12.1 已知边界**由 UI-14 记录（非失败项）。

---

## 10. 风险点与文档未写清楚之处（技术总监已逐条裁决，2026-07-27）

> 以下 6 点均已经技术总监独立复核并裁决（查了 `component.fields` 的 FORMULA 计数、三张单的 `costing_card_template_id`、库里报价单总数——全部属实）。本节保留原始分析供执行期回查，每条附裁决结果。

### 10.1 F-1（§12.3）缺可用于 UI/接口正向验证的真实数据

`需求说明.md §12.3` 明确要求"一旦树页签配公式列（几乎必然）即刻显形"，`fronttask.md`/`backtask.md` 也把 B0/F0 定位为"地基性"改动。但实测：**全库仅有的 3 个 BOM 树组件（`912fa00c`/`422fd880`/`656c9b87`）没有任何一个配置了 `FORMULA` 字段类型**，`QT-20260726-0006/0007/0008` 的 `formulaResults[].values` 恒为空对象。

**裁决：选方案 1（接受单测闭环）。** 不给 `656c9b87` 加 FORMULA 测试字段——该组件被 6 张单据 + 模板快照共同引用，加字段是全局配置改动，可能触发 snapshot 刷新连锁，风险大于收益。执行期按此落地：
- 后端/前端**单测**（合成数据）完整闭环验证 effKey 三处口径对齐（`UT-B0-02`/`UT-F0-01`），**这是本次验收采信的证据**。
- UI/接口级只做 `IT-17` 的只读 key 格式对拍，**不强求**端到端公式取值证据。
- "树页签配公式列的端到端验证"另行登记 `BACKLOG.md`（技术总监负责登记，测试工程师执行期不必再重复提出）。

### 10.2 `cpq_db_0724` 迁移导致多个既有 E2E spec 的硬编码夹具 ID 全部失效

不止本次任务相关的两个 spec（`quotation-bom-tree.spec.ts` 用 `QT-20260721-2067`、`costing-bom-tree.spec.ts` 用 `QT-20260604-1577`），凡是 2026-07-24 之前建立、硬编码具体 `quotationId` 的 E2E spec，在当前 `cpq_db_0724`（只有 8 张 2026-07-27 建的单据）里大概率都会在"进单据编辑页"这一步直接 0 行/超时失败——**这不是 repair-0727 引入的回归，是环境切库的遗留问题**。

**裁决：强制 A/B 空跑，不在本次重写夹具。** 执行期流程已落进 §7.2：先在**主工作区（未改动的 `master`）**跑一遍 `quotation-bom-tree.spec.ts` / `costing-bom-tree.spec.ts` / `repro-1982-delete.spec.ts`，记录失败形态；worktree 跑出**同型失败** = 环境噪声，写进报告即可放行；**只有"master 通过、worktree 失败"才是真回归**，必须定位修复。夹具重建登记 `BACKLOG.md`，不塞进本次。

### 10.3 AC-7 核价侧回归验证依赖的夹具不在文档指定范围内

`需求说明.md`/`backtask.md`/`fronttask.md` 都没有明确指出"用哪张单做核价侧回归"。`QT-20260726-0006`（文档唯一指定的验收数据）没绑核价模板，物理上无法在它身上做核价视图切换。

**裁决：批准使用 `QT-20260726-0007`。** `QT-20260726-0007` 可做写操作（墓碑写入 + 复位），`QT-20260726-0008` 只读。前提：每条用例配套复位并在报告中给出复位后的校验输出（已落进 `IT-16` 与 §2.2 表格）。

### 10.4 IT-08（真撞键场景）能否真实构造存疑

`api.md §3.3` 表格第 4 行"同一节点下两条相同行键的明细"要求一条端到端反向用例，但 `QT-20260726-0006` 树上天然不存在这种结构（6 个 `nodeId` 全部唯一）。`IT-08` 的构造方案依赖"加叶子候选料号库里能凑出同名料件"，这在写用例阶段无法验证是否可行。

**裁决：尽力构造，构造不成以 `UT-B5-03` 为准。** 若候选料号库凑不出同节点同行键的组合，直接在报告里标注"接口级证据缺失，以单测为准"，**不许含糊带过当成已覆盖**（已落进 `IT-08`/`UI-11` 正文）。

### 10.5 树行 UI 层稳定选择器缺失

`quotation-bom-tree.spec.ts` 现有做法是用 `hasText: 料号文本` 定位行，对"同料号出现两次"的场景（本次 992/AgNi11#-Ⅰ 正是这种）天然无法精确区分是 idx5 还是 idx6。

**裁决：已批准并已通知前端工程师加 `data-node-id`。** E2E 可以按 `[data-node-id="S-3120014539/S-80011/992"]` 精确定位 occurrence（已落进 §7.1 E2E 脚本骨架）。执行前先确认前端改动已合入，若尚未合入需回退到旧的"缩进宽度间接判断"法并在报告注明。

### 10.6 墓碑 `effKey` 兼容字段在新写入路径下的取值来源——**已升级为需要在 B3 落地时同步防的真 bug**

原始疑问：`api.md §2.1` 新格式示例里 `effKey` 仍然保留（"兼容字段，不参与匹配"），B0 对齐后 `appendRowTombstone` 写入的这个兼容字段该写旧口径还是新口径？

**裁决：写 B0 对齐后的新口径，且必须去掉手工拼接——技术总监复核发现这比预想的更严重：** `QuotationTreeService` 现在写的是 `nodeId + "::" + rowKey`，而 B0 之后前端传来的 `rowKey`（即 `__effKey`）**本身就已经含 `nodeId::` 前缀** → 会写出 `nodeId::nodeId::base` 的**双重前缀**。B3 必须改成直接写"对齐后的 effKey"，不再手工拼接。已落进 §3.4：`UT-B3-01` 断言钉成"`effKey` 恰好等于该行对齐后的 effKey（不含重复的 `nodeId` 段）"，新增 `UT-B3-05` 专项防这个回归。

---

## 11. 变更记录

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-27 | 编写 test.md | 基于 `需求说明.md`/`api.md`/`backtask.md`/`fronttask.md` 设计用例；用管理员会话核对 `cpq_db_0724` 真实数据（§2 现成数据均为字面值，非编造）；发现 3 项环境/数据风险（§10.1~10.3）需技术总监裁决后才能按计划执行 |
| 2026-07-27 | 技术总监复核 + 二次修订 | 技术总监逐条复核 §10 六项风险点并全部裁决（详见各条"裁决"段）；同时指出 5 处用例本身的问题并要求修正：①UI-01/E2E 行数基线口径矛盾——深挖后发现比"数字写错"更深一层：§2.1 存量态（基线 A）本身已有 1 条墓碑，idx5 早已不可见，"删一条、留一条"这类用例不能直接套用存量态，否则"留一条"根本不存在；引入**基线 A（存量态，只供 `IT-15` 只读用）/ 基线 B（干净态，0 墓碑，供一切"两条 duplicate 同时存在"场景用）**双基线模型（新增 §2.1.1），并规定 `IT-15` 必须最先跑、基线 B 用完后必须用 SQL 字面量精确复位回基线 A（不能用业务端点简单清空，那样会用新格式墓碑污染存量证据）；相应重写 `IT-01`~`IT-10`、`UI-01/02/04/05/06/07/08/09`、E2E §7.1 步骤 2-10 的前置与行数断言；②`IT-05` 前置与基线矛盾，补充显式"先清空确认 6 行"步骤；③AC-2（`IT-03`/`UI-02`）断言范围从"料件/row_index 两个字段"扩到"整行 JSON diff"（排除因位移正常变化的 `row_index`）；④`IT-01`/`IT-02` 补小计重算断言，同时如实标注当前夹具无 `is_subtotal` 字段、只能断言"流程被调用、值不为 NaN"而非"数值变化"；⑤`IT-02` 补 `row_data` 与 `quoteCardValues` 过滤后 baseRows 的同序内容比对（不只比数量）。另外，技术总监复核 `effKey` 兼容字段疑问（原 §10.6）时发现 `QuotationTreeService` 手工拼接 `nodeId + "::" + rowKey` 会在 B0 对齐后产生 `nodeId::nodeId::base` 双重前缀的真 bug，已同步钉死 `UT-B3-01` 断言并新增 `UT-B3-05` 防回归。**本轮修订后仍未执行任何测试**，等前后端交付并通过技术总监复验后进场，进场顺序遵循"先 A/B 空跑基线 → 再跑本次用例（`IT-15` 优先）"。 |
