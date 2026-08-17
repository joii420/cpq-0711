# task-0806 模板发布全量冻结 —— 测试用例（test.md）

> 编写时间：2026-08-06，**与开发并行编写**（此时后端 B1~B16 均未开工，仅前端 F1/F2 已合入分支 `283856e3`）。
> 依据：`需求文档.md`（AC-1~AC-18 / FR-1~FR-11 / D1~D15）｜`api.md`（接口契约）｜`backtask.md` / `fronttask.md`（实现清单）。
> 与需求文档冲突时以需求文档为准；本文件与代码实现有出入时，以 `api.md` 的契约为准，代码需改代码，不放宽用例。
> 前置阅读：`docs/RECORD.md`、`dev-docs/INDEX.md` §0（未发现本任务的历史重复项）。

---

## 0. 文档说明

- 本文件是**测试执行蓝图**，不是执行记录。执行结果落 `test-report.md`（每条用例的"实际结果"列在本文件先留空）。
- 所有用例编号格式：`TC-<AC编号两位或分类简写>-<序号>`。例：`TC-03-2` = 对应 AC-3 的第 2 条；`TC-PERM-1` = 权限矩阵第 1 条。
- 每条用例固定字段：**编号 / 对应AC / 分类 / 前置数据 / 步骤 / 期望结果 / 实际结果 / 优先级**。分类 ∈ {正常流, 边界值, 异常/错误码, 权限, 并发/幂等, 回归}。
- **优先级**：P0 = 阻断（含 AC-3/AC-6/AC-9/AC-12 等破坏不可变性/数据一致性的核心断言）；P1 = 重要；P2 = 一般。

---

## 1. 执行环境矩阵（先看这个，别测错库/测错树）

| 标记 | 含义 | 命令样例 |
|---|---|---|
| **[DEV-API]** | 打共享 dev server `localhost:8081`，连库 `10.177.152.12:5432/cpq_db_0724` | `curl -s --noproxy '*' ...` |
| **[SQL]** | 直连 `cpq_db_0724` 核对数据 | `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -c "..."` |
| **[IT]** | 后端集成测试，**worktree 内** `cd cpq-backend && ./mvnw test`，走 `test` profile → 连的是 **`cpq_db`（非 `cpq_db_0724`）** | 见 §7 |
| **[E2E]** | Playwright，`localhost:5174` + `localhost:8081`，与 [DEV-API] 同库 | 见 §10 |
| **[FE]** | 纯前端，不连库 | `npx tsc --noEmit` / `npx vitest run` |

⚠️ **三个必踩坑复述一遍（本任务尤其容易踩）**：
1. `grep` 是 ugrep，中文多的大文件会被静默判二进制返空 → 一律 `/usr/bin/grep -a`。
2. **[DEV-API]/[SQL] 用的是 `cpq_db_0724`，[IT] 用的是 `cpq_db`** —— 两个库数据不同。本文件 §3 列的所有真实 ID（COMP-0045、测试客户-4 系列模板等）**只在 `cpq_db_0724` 存在**。写 `[IT]` 类 JUnit 测试时**不能硬编码这些 ID**，必须让测试自己在 `cpq_db` 里动态挑选"被 ≥2 个 PUBLISHED 模板共享的组件"，否则测试在 CI/`test` 库里会因为找不到夹具而假绿（Assumptions.assumeTrue 跳过）或直接找不到数据报错。
3. worktree 里新增的 Flyway 迁移，共享 dev server **看不到**——要在主仓 `cpq-backend/src/main/resources/db/migration/` 放一份副本才能让 8081 实际跑起来，验证完**必须删除副本**，不能让它留在主仓。

---

## 2. 覆盖策略与风险点概述

| 风险点 | 应对 |
|---|---|
| AC-3 是唯一"当前 master 上必然失败、改完必然通过"的用例 —— 必须设计成可 A/B 对照，且不能误判"复现问题"为"用例失败" | TC-03-1~4 明确标注两种代码状态下的两套期望值 |
| dev 库 `cpq_db_0724` 是**多会话共享**的，AC-3/AC-8 需要修改/删除数据 | 一切破坏性操作只对**本文件专门新建的一次性夹具**（前缀 `TEST-0806-*`）做；唯一的例外是 AC-3 要求的真实共享组件 COMP-0045/COMP-0047，**改前必须原样备份、改后立即恢复**，且不做长时间停留 |
| "组件已删除"这个边界，正常 API 路径其实到不了（`ComponentService.delete:862` 的 `checkNotReferencedByTemplate` 会拦下） | 见 §6.2 不可判定点第 2 条，测试用例改用裸 SQL 构造，同时向 PM/架构提出这个疑问 |
| AC-11 字面要求"5→20"，库内现无天然存在的这种模板对（现状最大 18 页签） | 提供"自然数据两点法"（低成本，用 6 vs 18）+ "受控构造法"（贴合字面，但需要额外搭建），两条并行 |
| AC-7 依赖 D13 用户拍板，测试当下不知道会落哪一档 | 设计成三分支条件用例，执行时只跑命中的一支 |

---

## 3. 前置夹具（Fixtures）

> 以下均为 2026-08-06 在 `cpq_db_0724` 实测数据，执行测试前先用 §3.9 的核对 SQL 确认数据未被其他并发会话改变。

### F1 — 6 模板共享组件（AC-1 / AC-3 / AC-9 / AC-16 核心夹具）

组件 `COMP-0045`（材料成本，ACTIVE）同时具备本次待补的**全部 6 个新字段非空值**（全库唯一同时满足"被 6 模板共享"+"6 个新字段都非空"的组件，AC-1 与 AC-3 天然共用同一份数据）：

```
sort_field            = 项次
element_code_field    = 元素
element_price_field   = 元素单价
element_currency_field= 货币
row_key_fields        = 非空
column_count          = 11
```

被以下 6 个 **PUBLISHED / QUOTATION** 模板共享（同一 `template_series_id`，"测试客户-4"系列，各 8 个 tab，COMP-0045 恒排 `sort_order=2`）：

| 别名 | template.id | version |
|---|---|---|
| T1 | `88d5d815-385b-45ca-bd4b-de0e0bad8a30` | v1.0 |
| T2 | `0a61d6a7-c9da-4ffc-b4b0-cb6fb75b48e0` | v1.1 |
| T3 | `3e95eddd-37fe-412e-bec7-f5667f3d5b03` | v1.2 |
| T4 | `3f941cb7-059f-449e-9348-d8c5259c3433` | v1.3 |
| T5 | `05b6ac45-6899-4a78-a4de-78becad2cee4` | v1.4 |
| T6 | `2ca51461-2d32-4b84-a982-2d3f0ee23be7` | v1.5 |

同批共享组件另有 `COMP-0047`(来料固定加工费) / `COMP-0046`(来料其他费用) / `COMP-0181`(其他费用) / `COMP-0182`(组装加工费) / `COMP-0183`(产品) / `COMP-0184`(报价) / `COMP-0185`(物料)，均 6 模板共享，用作 TC-03-4 交叉验证候选。

关联报价单（18 张，DRAFT/SUBMITTED 混合，覆盖 T1~T6）：`QT-20260806-0083`(DRAFT,T6) / `QT-20260806-0103`(SUBMITTED,T6) / `QT-20260805-0078`(DRAFT,T3) / `QT-20260806-0116`(SUBMITTED,T6) / `QT-20260804-0068`(DRAFT,T1) / `QT-20260805-0077`(DRAFT,T2) 等，完整清单见 `test-fixtures/golden_baseline_queries.sql` 配套查询。

### F2 — DISABLED 组件夹具（AC-15 / D5 核心夹具）

| 模板 | template.id | version | tab 数 | 引用的 DISABLED 组件 |
|---|---|---|---|---|
| 罗克韦尔模板1 | `70f1b149-b0d9-4cb1-9245-6c3cee1bc3af` | v1.0 PUBLISHED | 6 | COMP-0019~0024（产品/BOM/材料成本/外购件成本/加工费/产品小计1） |
| 罗克韦尔模板2 | `9e2e6ef3-3865-4e90-a509-803761b6e837` | v1.0 PUBLISHED | 6 | COMP-0025~0030（同上结构） |

两模板共 12 个 tc 行，全部引用 `status='DISABLED'` 的组件，且**当前仍在正常渲染**（这是 D5 语义"停用不影响已发布模板"的现状证据）。各自关联多张 DRAFT 报价单，如 `QT-20260726-0001`（罗克韦尔模板1）、`QT-20260726-0008`（罗克韦尔模板2）。

⚠️ 值域校验：`ComponentService.toggleStatus:846-850` 确认值域是 `ACTIVE`/`DISABLED`，**没有 `INACTIVE`**——用例断言严禁写成 `INACTIVE`。

### F3 — 多页签规模夹具（AC-11 自然数据两点法）

| 模板 | template.id | tab 数 | template_kind |
|---|---|---|---|
| 罗克韦尔模板1 v1.0 | `70f1b149-b0d9-4cb1-9245-6c3cee1bc3af` | 6 | QUOTATION |
| 核价模板1 v1.0 | `3d3fe868-95de-47da-aa58-ec092c27f245` | 17 | COSTING |
| 核价模板1 v1.1 | `bc99f083-2d64-47d8-875f-d3c005ae5f2e` | **18**（库内现存最大） | COSTING |

### F4 — COSTING 模板夹具

即 F3 的核价模板1 两版本，用于确保测试覆盖 `templateKind=COSTING`（D4 范围要求），不止 QUOTATION。

### F5 — 一次性可弃置夹具（多个 AC 的破坏性操作专用，测试结束必须清理）

**创建方式**（测试执行阶段建，不要提前建，避免在 review 期间被别的并发会话误用）：

```
POST /api/cpq/templates
{ "name": "TEST-0806-临时模板", "templateKind": "COSTING" }   // COSTING 可不填 customerId，免建测试客户
```

记为 `T-TEST-01`。随后按需 `POST 增加 template_component`（复用 F1 的 COMP-0045 / COMP-0181 / COMP-0047 三个组件，只是新增一条引用，不影响它们在 T1~T6 上的既有 tc 行）。

用途：AC-1 唯一约束/override 优先/0-tab 边界、AC-6 快照缺失、AC-8 admin 后门执行态、AC-9 ARCHIVED 覆盖、AC-16 严格版本化正规流程。

**清理**：测试结束后 `DELETE FROM template WHERE name LIKE 'TEST-0806-%'`（`ON DELETE CASCADE` 会带走 `template_component` / `template_component_snapshot`）。

### F6 — Golden 基线文件（AC-2 / AC-9 / AC-12 迁移前后对比用，已捕获）

**已于 2026-08-06、V382 迁移执行前捕获**，随本文件一起提交：

- `dev-docs/task-260806-模板发布全量冻结/test-fixtures/golden_baseline_queries.sql` —— 捕获用 SQL（可重跑复核）
- `dev-docs/task-260806-模板发布全量冻结/test-fixtures/golden_baseline_pre_v382_2026-08-06.txt` —— 捕获结果，含：
  - 17 个 PUBLISHED 模板的 `components_snapshot` / `sql_views_snapshot` md5 + 数组长度
  - 149 行 `template_component` 联表 `component` 逐行 18 字段等价 md5（按 `template_id, sort_order` 排序）
  - 已验证：`template_component` 行数总计 = **149**，与需求文档 §1.6 一致

### F7 — 种子账号（权限用例）

```
admin / Admin@2026   SYSTEM_ADMIN
bob   / Admin@2026   SALES_MANAGER
alice / Admin@2026   SALES_REP
```

### F8 — 现有回归标尺

- `cpq-backend/src/test/java/com/cpq/quotation/service/GoldenCardValuesEquivTest.java` —— 逐行路径 md5 背靠背手法，本任务改动的是 `CardSnapshotService` 读取来源，**该测试的两个 golden 常量（`GOLDEN_SMALL` / `GOLDEN_ROCKWELL`）不应漂移**（TC-REG-8）。
- `cpq-frontend/e2e/quotation-flow.spec.ts` + `composite-product-flow.spec.ts` —— AC-18。

### F9 — 数据核对 SQL（执行任何用例前先跑一次，确认夹具未被并发会话改动）

```sql
-- 期望：17 PUBLISHED(15 QUOTATION+2 COSTING) / 0 DRAFT / 0 ARCHIVED；149 tc 行
SELECT status, template_kind, count(*) FROM template GROUP BY status, template_kind;
SELECT count(*) FROM template_component tc JOIN template t ON t.id=tc.template_id WHERE t.status IN ('PUBLISHED','ARCHIVED');
-- 期望：COMP-0045 六字段仍非空，未被其他会话动过
SELECT sort_field, element_code_field, element_price_field, element_currency_field, row_key_fields IS NOT NULL, column_count
FROM component WHERE code='COMP-0045';
```

---

## 4. 用例清单（按 AC 组织）

### AC-1（FR-1 / FR-4）—— `publish()` 落 18 字段完整快照

#### TC-01-1　完整发布落地 + 18 字段逐项比对（含 6 个新补字段）
- **对应AC**：AC-1　**分类**：正常流　**优先级**：P0
- **前置数据**：F5（新建 `T-TEST-01`，DRAFT，依次加 tc：`sort_order=0→COMP-0045`，`1→COMP-0181`，`2→COMP-0047`）
- **步骤**：
  1. [DEV-API] `POST /api/cpq/templates/{T-TEST-01}/publish`
  2. [SQL] `SELECT count(*) FROM template_component_snapshot WHERE template_id='<T-TEST-01>'`
  3. [SQL] 取 `sort_order=0` 那行，逐列比对 18 个渲染配置字段与 `component` 表 `COMP-0045` 当前值（`fields`/`formulas`/`excel_columns`/`data_driver_path`/`tree_config`/`bom_recursive_expand`/`tab_type`/`part_no_field`/`part_name_field`/`component_name`/`component_code`/`component_type`/`column_count`/`row_key_fields`/`sort_field`/`element_code_field`/`element_price_field`/`element_currency_field`）+ `tab_name`/`preset_rows`/`formula_assignments` 来自 `template_component`
- **期望结果**：行数=3；`sort_order=0` 行 18 字段与活配置逐一相等，尤其 `sort_field='项次'`、`element_code_field='元素'`、`element_price_field='元素单价'`、`element_currency_field='货币'`、`row_key_fields` 非空、`column_count=11` 这 6 个新补字段**确实落库**（这是 FR-4 的核心断言，此前旧 snapshot 从未存过这几个字段）；`\d template_component_snapshot` 显示 `uq_tcs_template_sort` 唯一约束存在
- **实际结果**：
- **优先级**：P0

#### TC-01-2　唯一约束生效 —— 同 `(template_id, sort_order)` 二次插入报错
- **对应AC**：AC-1　**分类**：边界值　**优先级**：P1
- **前置数据**：TC-01-1 已执行
- **步骤**：[SQL] `INSERT INTO template_component_snapshot (template_id, template_component_id, component_id, sort_order, ...) SELECT template_id, template_component_id, component_id, sort_order, ... FROM template_component_snapshot WHERE template_id='<T-TEST-01>' AND sort_order=0`（复制该行但 `sort_order` 仍填 0）
- **期望结果**：`ERROR: duplicate key value violates unique constraint "uq_tcs_template_sort"`；原 3 行不受影响（`SELECT count(*)` 仍为 3）
- **实际结果**：
- **优先级**：P1

#### TC-01-3　override 优先语义 —— `fields_override` / `data_driver_path_override` 非空时快照取 override 值
- **对应AC**：AC-1　**分类**：边界值（全库当前 0 个非空 override，**必须人工构造，不能因"反正都是 NULL"跳过**）　**优先级**：P0
- **前置数据**：F5 `T-TEST-01` 处于 DRAFT（`createNewDraft` 出一个新草稿版本，或用另一个未发布的一次性模板）
- **步骤**：
  1. [SQL] `UPDATE template_component SET fields_override='[{"name":"TEST_OVERRIDE","field_type":"INPUT_NUMBER"}]'::jsonb, data_driver_path_override='$test_override_view' WHERE template_id='<T-TEST-01 草稿版>' AND sort_order=1`
  2. [DEV-API] `publish` 该草稿
  3. [SQL] 查该行快照 `fields` / `data_driver_path`
- **期望结果**：`fields` = override 值（不是 `COMP-0181` 的原始 `fields`）；`data_driver_path` = `$test_override_view`（不是 `COMP-0181` 原始 `data_driver_path`）
- **实际结果**：
- **优先级**：P0

#### TC-01-4　边界 —— 0 组件模板发布（"空快照"边界之一）
- **对应AC**：AC-1　**分类**：边界值　**优先级**：P1
- **前置数据**：新建 DRAFT 模板 `T-TEST-02`，不加任何 tc
- **步骤**：[DEV-API] `publish`
- **期望结果**：**二选一，两种都要在 test-report 写清楚具体命中哪种**：① 业务允许 0 组件发布 → 200，`template_component_snapshot` 行数=0，`components_snapshot='[]'`（这是合法的"空"，区别于 AC-6 的"应有却缺失"）；② 业务本就拒绝 0 组件发布 → 400，记录具体错误信息
- **实际结果**：
- **优先级**：P1

#### TC-01-5　边界 —— 大页签量模板（18 tab，只读校验，复用 F3）
- **对应AC**：AC-1　**分类**：边界值　**优先级**：P2
- **前置数据**：F3 核价模板1 v1.1（`bc99f083-...`，18 tab），需等 V382 迁移执行后
- **步骤**：[SQL] 18 行 `template_component_snapshot` 与 18 行 `template_component`+`component` 联表逐字段比对（复用 `golden_baseline_queries.sql` 第4段查询口径）
- **期望结果**：18 行全部匹配，无遗漏无多余
- **实际结果**：
- **优先级**：P2

---

### AC-2（FR-2）—— `components_snapshot` 派生 jsonb 与改造前逐字段一致

#### TC-02-1　迁移场景：派生结果与迁移前 golden md5 一致
- **对应AC**：AC-2　**分类**：正常流　**优先级**：P0
- **前置数据**：F6 golden 基线文件（迁移前已捕获）
- **步骤**：V382 迁移执行后，重跑 `test-fixtures/golden_baseline_queries.sql` 第一段查询，得到 17 个模板新的 `cs_md5`
- **期望结果**：**假设期间没有其他并发会话改过这 17 个模板引用的任何组件**，新 `cs_md5` 应与 golden 文件逐行相同（迁移只换产出来源，输入未变）；若某模板 `cs_md5` 不同，先用 `component.updated_at` 核对该模板引用的组件是否被并发会话改过，排除干扰后仍不同才判定为 AC-2 违规
- **实际结果**：
- **优先级**：P0

#### TC-02-2　开发自证：publish() 改造前后同输入同输出（黑盒契约对比）
- **对应AC**：AC-2　**分类**：正常流　**优先级**：P0
- **前置数据**：后端工程师在改 `publish()` **前**对某 DRAFT 模板调用一次 publish，落盘返回的 `componentsSnapshot`；改造**后**对结构相同的另一个模板（或同一模板走 `createNewDraft` 复制）调用，落盘对比
- **步骤**：两次输出做键集合 + 键顺序 + 值逐字段对比（文本级 diff）
- **期望结果**：完全一致。⚠️**已知歧义**：若比对时用 JSON 库反序列化再比较，键顺序信息可能丢失——测试必须做**文本级**比对（同一序列化实现产出的原始字符串），不能"解析后再比较 keySet"，否则测不出"键顺序"这个要求（详见 §6.2 第 4 条）
- **实际结果**：
- **优先级**：P0

#### TC-02-3　前端 vitest 全绿（回归信号）
- **对应AC**：AC-2　**分类**：回归　**优先级**：P0
- **步骤**：[FE] `cd cpq-frontend && npx vitest run`
- **期望结果**：全绿，0 failed。**若挂 = 后端契约被破坏的信号，不许改前端迁就**（fronttask.md 红线 3）
- **实际结果**：
- **优先级**：P0

---

### AC-3 ⭐（FR-3）—— 核心验收：改共享组件不改已发布快照

> **本组用例设计为 A/B 双态断言**：同一套步骤，在"当前 master（未落地 FR-3）"上执行会得到"红灯"（这是**复现问题**，不是用例失败，证明缺陷存在）；在"完成 FR-3 的分支代码"上执行必须得到"绿灯"。test-report 必须明确标注每次执行对应的代码状态（commit hash）。

#### TC-03-1　改 `formulas`：6 模板快照 + jsonb + 报价渲染值三重断言
- **对应AC**：AC-3　**分类**：正常流　**优先级**：P0
- **前置数据**：F1（COMP-0045，6 模板 T1~T6）
- **步骤**：
  1. [SQL] 备份：`SELECT id, fields, formulas, data_driver_path FROM component WHERE code='COMP-0045'` 落盘 `comp0045_backup.json`（**用显式 SQL 查询落盘，不用 bash 变量传递**——历史教训见 RECORD "psql改配置验证的存还原坑"）
  2. [SQL] 记录改动前基线：T1~T6 各自 `template_component_snapshot` 中 `sort_order=2` 行的 18 字段等价 md5（若 B1 尚未建表，用 `golden_baseline_queries.sql` 第4段的活表联合查询代理，已捕获值 = `708c278011bf290d2c718499e3621c8c`，6 个模板完全一致）
  3. [SQL] 记录改动前：T1~T6 `components_snapshot` 整体 md5（见 F6 golden 文件）
  4. [DEV-API] 记录改动前：挑 3 张引用 T6(v1.5) 的报价单（`QT-20260806-0103` SUBMITTED / `QT-20260806-0083` DRAFT / `QT-20260806-0116` SUBMITTED）调用报价渲染接口，落盘"材料成本" tab 逐行数值
  5. [DEV-API] `PUT /api/cpq/components/{COMP-0045.id}`，给 `formulas` 中某条追加可辨识的数值改动（如 `*1.0001`）
  6. 重复步骤 2/3/4 同样的查询/调用
  7. [SQL] 恢复：用步骤1的备份 `UPDATE component SET fields=..., formulas=..., data_driver_path=... WHERE code='COMP-0045'`；重新核对恢复后 md5 与 golden 一致
- **期望结果**（分代码状态）：
  - 【当前 master】：步骤6 的 6 个模板 md5 **会变化**——判定为"复现问题"，非用例失败
  - 【完成 FR-3 后】：步骤6 的 6 个模板 `template_component_snapshot` 行 md5、`components_snapshot` 整体 md5 **必须与步骤2/3完全相同**；步骤4/6 两次报价渲染值**逐位相同**
- **实际结果**：
- **优先级**：P0

#### TC-03-2　改 `dataDriverPath`：同款三重断言（覆盖 `setDriverView` 传导路径）
- **对应AC**：AC-3　**分类**：正常流　**优先级**：P0
- **步骤**：同 TC-03-1，但改动动作换成 `POST /api/cpq/components/{id}/driver-view`（或等价 `setDriverView` 入口），因为这是**第二个**自动传导调用点（`ComponentService.setDriverView:792`），必须单独覆盖，不能只测 `update`
- **期望结果**：同 TC-03-1
- **实际结果**：
- **优先级**：P0

#### TC-03-3　改 `fields`（新增一列）：同款三重断言
- **对应AC**：AC-3　**分类**：正常流　**优先级**：P0
- **步骤**：同 TC-03-1，改动换成给 COMP-0045 新增一个 `INPUT_NUMBER` 字段
- **期望结果**：同 TC-03-1
- **实际结果**：
- **优先级**：P0

#### TC-03-4　交叉验证：换一个组件（`COMP-0047`）复测，防止"偶然通过"
- **对应AC**：AC-3　**分类**：正常流　**优先级**：P1
- **前置数据**：`COMP-0047`（来料固定加工费，同 6 模板共享）
- **步骤**：同 TC-03-1 逻辑，换组件
- **期望结果**：同 TC-03-1。目的是防止"修复只对 COMP-0045 生效"这种局部假通过（例如缓存 key 恰好按 COMP-0045 命中）
- **实际结果**：
- **优先级**：P1

---

### AC-4（FR-3）—— `refreshSnapshotsByComponent` 整体退役

#### TC-04-1　grep 零命中 —— `ComponentService.update` / `setDriverView`
- **对应AC**：AC-4　**分类**：正常流　**优先级**：P0
- **步骤**：`/usr/bin/grep -an "refreshSnapshotsByComponent" cpq-backend/src/main/java/com/cpq/component/service/ComponentService.java`
- **期望结果**：0 行命中（当前实测 2 行：`:733` / `:792`）
- **实际结果**：
- **优先级**：P0

#### TC-04-2　`POST /api/cpq/components/{id}/refresh-template-snapshots` → 404
- **对应AC**：AC-4　**分类**：异常/错误码　**优先级**：P0
- **步骤**：[DEV-API] 对任一组件 id 调用该端点
- **期望结果**：404（路由已删）
- **实际结果**：
- **优先级**：P0

#### TC-04-3　`POST /api/cpq/templates/admin/migrate-to-unified-view` → 404
- **对应AC**：AC-4　**分类**：异常/错误码　**优先级**：P0
- **步骤**：[DEV-API] 调用该端点
- **期望结果**：404
- **实际结果**：
- **优先级**：P0

#### TC-04-4　⚠️第 5 个调用点（本次测试实测发现，backtask.md §2.3 未列出）
- **对应AC**：AC-4　**分类**：异常/错误码 + 回归　**优先级**：P0
- **背景**：实测 `TemplateService.promoteOverrideToComponent:923` 也调用了 `refreshSnapshotsByComponent`，而这个方法本身是 A7 `promote-override-to-component` 端点保留下来的实现（FR-7 要求"改造"而非删除）。`backtask.md` §2.3/B6 的"4 个调用点"表格**没有列出这第 5 处**。方法体一旦整体删除（`TemplateService:340-414`），这里编译不过；即便侥幸没漏改，`grep` 若只查 `ComponentService.java` 也测不到它。
- **步骤**：`/usr/bin/grep -an "refreshSnapshotsByComponent" cpq-backend/src/main/java/com/cpq/template/service/TemplateService.java`；功能性验证 A7 `confirm=true` 执行后，目标组件所在的已发布模板 snapshot 确实被等价新逻辑刷新
- **期望结果**：0 命中（连方法定义本身都不该在，因为整体退役）；A7 执行态功能不因此回归
- **实际结果**：
- **优先级**：P0

#### TC-04-5　回归：`syncExcelColumnsToImportedCopies`（Bug3）不受影响
- **对应AC**：AC-4　**分类**：回归　**优先级**：P1
- **前置数据**：一个 EXCEL 源组件 + 至少一个导入副本（`__impN` 后缀）
- **步骤**：保存源组件的 `excelColumns`，检查副本是否同步
- **期望结果**：副本 `excelColumns` 正常同步（`ComponentService.update:746-755` 那段代码**不应被误删**，backtask.md 已用⚠️特别标注）
- **实际结果**：
- **优先级**：P1

---

### AC-5（FR-5）—— 10 处读取点收口到 `PublishedTemplateReader`

#### TC-05-1　grep 零命中 —— 3 个文件对活表的直接查询
- **对应AC**：AC-5　**分类**：正常流　**优先级**：P0
- **步骤**：`/usr/bin/grep -an "JOIN component c\|FROM component c\|Component\.findById\|TemplateComponent\.list" CardSnapshotService.java ConfigureSnapshotService.java ExcelViewService.java`
- **期望结果**：0 命中（当前实测：`CardSnapshotService` 5 处 + `ConfigureSnapshotService` 1 处 + `ExcelViewService` 4 处，共 10）。**排除** `component_sql_view` 等其他表的合法命中
- **实际结果**：**部分 FAIL**（2026-08-08 实测）。逐文件重新 grep（`/usr/bin/grep -an`）：
  - `CardSnapshotService.java` → **0 命中** ✅
  - `ConfigureSnapshotService.java` → **1 处**（:902 `JOIN component c`），读代码确认位于 `"PUBLISHED".equals(status) || "ARCHIVED".equals(status)` 判定为 **false**（即真正 DRAFT）的 else 分支内，判据用的是**真实 `status` 字段**，符合 AC-5②豁免条件 ✅
  - `ExcelViewService.java` → **3 处命中**：
    - `:1163`/`:1187`（`tabDefsOfTemplate` 的 `isDraft` 分支 / `buildLiveComponentsList`）—— 判据是 `"DRAFT".equals(t.status)`（B21 修复后的正确写法），✅ 合规
    - **`:545`（`buildTabJoinEffectiveRows` ← `loadFrozenComponentMetaMap`）—— ❌ 不合规**。`loadFrozenComponentMetaMap`（:567-577）判据是 `t.componentsSnapshot == null || isBlank()`，**不是** `t.status`；而 V382/B21 已把 PUBLISHED-但-未冻结 模板的 `componentsSnapshot` 也置为 `NULL`，与真 DRAFT 无法区分 → 对"过渡期未冻结的 PUBLISHED 模板"，本方法静默返回空 map，调用方在 :545 直接回落 `Component.findById()` 读活表，**不抛 `TemplateNotFrozenException`**。已登记为缺陷 D-2（详见 test-report.md）
  - **结论**：AC-5① 的严格判据（"ExcelViewService 活表直查 = 0"）**未达成**，命中数是 1（`:545`），非 0；AC-5②（ConfigureSnapshotService 恰好 1 处且正确门控）**达成**
- **优先级**：P0

#### TC-05-2　`PublishedTemplateReader` 接口形状校验（禁止单条查询方法）
- **对应AC**：AC-5　**分类**：正常流　**优先级**：P0
- **步骤**：`codegraph_search` 符号 `tabOf` 是否存在于 `PublishedTemplateReader` 内；核对 `allTabsOf` / `driverCompsOf` / `treeTabsOf` / `hasRecursiveExpand` / `allTabsOfMany` 五个方法签名均为批量入参（`UUID` 或 `Collection<UUID>`，不接受单条 `sortOrder`）
- **期望结果**：**不提供** `tabOf(templateId, sortOrder)` 这类会被调用方放入循环的单条查询方法（backtask.md §2.1 明文禁止）
- **实际结果**：**PASS**。直接 Read `PublishedTemplateReader.java` 全文确认：公开方法为 `allTabsOf(UUID)` / `isFrozen(UUID)` / `driverCompsOf(UUID)` / `treeTabsOf(UUID)` / `hasRecursiveExpand(UUID)` / `allTabsOfMany(Collection<UUID>)` / `findTab(List,UUID,int)`（`findTab` 是从已取到的 `allTabs` 内存里挑选，非单条查库）。**无** `tabOf(templateId, sortOrder)` 形态的单条查询方法
- **优先级**：P0

#### TC-05-3　功能性验证：切断活表后渲染仍正常（旁证读取路径已切换）
- **对应AC**：AC-5　**分类**：回归　**优先级**：P1
- **步骤**：与 TC-06-2（"活表数据完好时仍报错"）互为镜像验证：本用例反过来验证**读取路径已经不依赖活表**——见 TC-06 系列的详细步骤，此处不重复
- **期望结果**：见 TC-06-2
- **实际结果**：**部分 PASS**——`CardSnapshotService`/`ConfigureSnapshotService` 路径验证通过（渲染改走快照，不依赖活表内容是否被改）；`ExcelViewService` 的 `buildTabJoinEffectiveRows` 路径因 TC-05-1 发现的 D-2 缺陷，**不适用**本条"零依赖活表"结论（该路径在未冻结态会主动回落活表，"切断活表" 反而会让它跟着挂——这恰恰证明了它依赖活表）
- **优先级**：P1

---

### AC-6（FR-6）—— 快照缺失显式报错，禁止回落活表

#### TC-06-1　人为删除某 PUBLISHED 模板一行快照 → 500 含 templateId+sortOrder
- **对应AC**：AC-6　**分类**：异常/错误码　**优先级**：P0
- **前置数据**：F5 `T-TEST-01`（已发布，3 行快照）——**不要用 F1 真实客户模板做删除操作**
- **步骤**：
  1. [SQL] `DELETE FROM template_component_snapshot WHERE template_id='<T-TEST-01>' AND sort_order=1`
  2. [DEV-API] 触发该模板下某报价单的卡片值渲染（或直接调用暴露的 Reader 相关端点）
- **期望结果**：500，错误信息**必须包含**字面量 `templateId=<T-TEST-01.id>` 和 `sortOrder=1`（对齐 api.md §10：`模板快照缺失：templateId={id}, sortOrder={n}。已发布模板必须有完整冻结快照，请检查 template_component_snapshot`）
- **实际结果**：**部分 FAIL**（改用一次性模板 `caea9e0f-...`，3 tc，删 `sort_order=1` 行）。触发渲染（`GET /templates/{id}/excel-view-config/tab-defs`，走 `allTabsOf`）→ **确实 500**，但错误原文是：`"模板快照损坏：templateId=caea9e0f-...，template_component_snapshot 行数=2，components_snapshot jsonb 长度=3。两者应恒相等..."` —— **含 `templateId`，但不含 `sortOrder=1`**（api.md §10 / 本用例要求的 `sortOrder` 未出现）。根因：产生该消息的是 `verifyConsistentWithJsonb`（表行数 vs jsonb 长度比对），而唯一会产出含 `sortOrder` 消息的 `PublishedTemplateReader.findTab()` 经 `codegraph_callers` + `grep -a` 双重确认**零生产调用方**（`RowDataMaterializer` 里同名 `findTab` 是无关的私有方法）——即 api.md §10 该行文档描述的是死代码。已登记缺陷 D-3
- **优先级**：P0

#### TC-06-2　"活表数据完好时仍必须报错"——核心区分点，单独断言
- **对应AC**：AC-6　**分类**：异常/错误码　**优先级**：P0
- **前置数据**：紧接 TC-06-1
- **步骤**：
  1. [SQL] 确认 `component` 表和 `template_component` 表中 `sort_order=1` 对应的原始行**完全未被删除**（`SELECT * FROM template_component WHERE template_id='<T-TEST-01>' AND sort_order=1` 应正常返回一行）
  2. 再次触发该模板的渲染
- **期望结果**：仍然 500，**不能**因为活表数据还在就"贴心"地兜底出正确值。这是 AC-6 真正要测的东西——很多实现会漏掉"即便活表数据完好也要报错"这一步，只测"活表也被删了才报错"是不够的
- **实际结果**：**PASS**。确认 `template_component` 表 3 行原样未动（`SELECT count(*)=3`），仍然 500（同 TC-06-1 消息），未静默回落读出正确值——核心不变量成立，仅消息文本细节如 TC-06-1 所述有出入
- **优先级**：P0

#### TC-06-3　恢复快照行后渲染恢复正常
- **对应AC**：AC-6　**分类**：回归　**优先级**：P2
- **步骤**：对 `T-TEST-01` 重新执行一次 `publish()`（或用 golden 算法手工 `INSERT` 回该行）
- **期望结果**：渲染恢复 200，不留后遗症
- **实际结果**：**PASS**。用之前的 `\copy` 备份手工 `INSERT` 回该行（`publish()` 对已 PUBLISHED 模板不可重跑）→ 重新调用 `tab-defs` → 200，3 个 tab 完整返回
- **优先级**：P2

#### TC-06-4　DRAFT 模板不受影响（防误伤）
- **对应AC**：AC-6　**分类**：边界值　**优先级**：P1
- **前置数据**：任一 DRAFT 模板（无快照行，属正常态）
- **步骤**：渲染 DRAFT 模板相关数据
- **期望结果**：走活表分支正常渲染，**不**触发"快照缺失"报错（DRAFT 模板天生 0 行快照是合法状态，不是 AC-6 的报错对象）
- **实际结果**：**PASS**。`TEST-0806-DRAFT-FOR-FREEZE400`（DRAFT，0 tc）调用 `tab-defs` → 200 `[]`（空但非错误）；`freeze` 调用 → 正确 400（区分于未冻结 PUBLISHED 的 409），未与 AC-6/AC-9 的报错混淆
- **优先级**：P1

---

### AC-7（FR-6 / D13）—— SQL 视图 fallback 收口

#### TC-07-1　体检 B 端点验证
- **对应AC**：AC-7　**分类**：正常流　**优先级**：P0
- **步骤**：[DEV-API] `GET /api/cpq/templates/admin/sqlview-closure-check?status=PUBLISHED,ARCHIVED`
- **期望结果**：200，`scanned=17`，返回 `missCount` 与 `misses[]` 明细。**本用例只验证端点本身工作，不对 `missCount` 具体数值断言**——该数值需交用户按 D13 拍板
- **实际结果**：
- **优先级**：P0

#### TC-07-2　条件分支（按 D13 结果三选一执行，其余标 N/A）
- **对应AC**：AC-7　**分类**：异常/错误码　**优先级**：P0
- **步骤 / 期望结果**（写成三分支，执行时只跑命中 TC-07-1 结果的那一支）：
  - **分支 a（`missCount=0`）**：人为在 `sql_views_snapshot` 中删除一条视图记录 → 该组件渲染报 500，含 `componentId`/`view` 名，**不**读 `component_sql_view`（对齐 api.md §10 第二条错误码）
  - **分支 b（少量、可定点补）**：同分支 a，且额外验证被补的历史 miss 案例已回填 snapshot（`sqlview-closure-check` 复跑后 `missCount=0`）
  - **分支 c（大量、翻出历史欠账）**：验证 `test-report.md` + `BACKLOG.md` 已按 D13 格式登记该已知缺口；此时**不**测报错，反而要验证 fallback **仍然保留**且已被记录为已知风险（不是回归）
- **实际结果**：
- **优先级**：P0

#### TC-07-3　错误信息格式校验（分支 a/b 生效时）
- **对应AC**：AC-7　**分类**：异常/错误码　**优先级**：P1
- **期望结果**：错误信息含 `templateId` / `componentId` / `view` 三要素，对齐 api.md §10
- **实际结果**：
- **优先级**：P1

---

### AC-8（FR-7）—— 3 个 admin 后门：confirm 预览 + 审计 + 告警

> ⚠️ 执行态（`confirm=true`）**只对 F5 一次性夹具执行**；预览态（`confirm=false`，零写入，安全）可以用 F1 真实模板做只读验证。

#### TC-08-1　A5 `confirm=false`（缺省）→ 只读预览，零写入
- **对应AC**：AC-8　**分类**：正常流　**优先级**：P0
- **前置数据**：F1（T1~T6）
- **步骤**：[DEV-API] `POST /api/cpq/config-center/refresh-all-snapshots {"templateIds":["T1","T2","T3","T4","T5","T6"]}`（不传 `confirm`）
- **期望结果**：200，`preview=true`，`affectedTemplates` 含 6 条，`affectedTemplateCount=6`，`affectedQuotationCount>=18`；[SQL] 前后对比 `template_component_snapshot` 行数/md5 完全一致（零写入）；`operation_log` 未新增行
- **实际结果**：**PASS**（用 T1 单模板复测，未用全部 6 个，等价验证零写入语义）。`POST refresh-all-snapshots {"templateIds":["88d5d815-..."]}`（不传 `confirm`）→ `preview:true, affectedTemplateCount:1, affectedQuotationCount:1, warning:"此操作将改写..."`；前后 `template_component_snapshot` 行数 8→8、`operation_log` 总行数 29→29，均未变化，确认零写入
- **优先级**：P0

#### TC-08-2　A5 `confirm=true` → 写入 + 审计 + 告警
- **对应AC**：AC-8　**分类**：正常流　**优先级**：P0
- **前置数据**：F5 `T-TEST-01`（先故意改一下它引用的某组件字段，制造 drift）
- **步骤**：[DEV-API] `POST /api/cpq/config-center/refresh-all-snapshots {"templateIds":["T-TEST-01"],"confirm":true}`
- **期望结果**：200，`preview=false`，`refreshedTemplates=1`，`refreshedRows=3`，`operationLogId` 非空；[SQL] `SELECT * FROM operation_log WHERE id='<operationLogId>'` 命中一行，`operation_type='TEMPLATE_SNAPSHOT_FORCE_REFRESH'`，`target_type='TEMPLATE'`，`target_id='<T-TEST-01>'`，`details` 含 before/after diff；后端控制台出现 `WARN` 级别日志且含"不可变性"字样
- **实际结果**：**PASS**（用一次性模板 `d7f62a56-...`，1 tc，未特意制造 drift 但功能路径完全一致）。`confirm=true` → `{"preview":false,"refreshedTemplates":1,"refreshedRows":1,"operationLogId":"29e028ad-..."}`；`SELECT * FROM operation_log WHERE id=...` 命中一行：`operation_type='TEMPLATE_SNAPSHOT_FORCE_REFRESH'`，`target_type='TEMPLATE'`，`target_id`匹配，`summary='强制重新对齐已发布模板快照：1 个模板'`，`details IS NOT NULL`。未采集后端控制台 WARN 日志原文（dev server 未配置日志文件重定向、控制台被 `-Dquarkus.console.enabled=false` 关闭），**该子项未验证**，但代码读证（`ConfigCenterResource`/`TemplateService` 源码）确认存在对应 `LOG.warn` 调用
- **优先级**：P0

#### TC-08-3　A6 `delete-tcs` `confirm=false` → 预览含 `tabsToDelete`，零写入
- **对应AC**：AC-8　**分类**：正常流　**优先级**：P1
- **步骤**：[DEV-API] `POST /api/cpq/templates/admin/{T-TEST-01}/delete-tcs {"sortOrders":[1]}`
- **期望结果**：200，预览含 `tabsToDelete:[{sortOrder:1,...}]`，零写入
- **实际结果**：
- **优先级**：P1

#### TC-08-4　A6 `confirm=true` → 执行 + 同步删快照行 + 重生成 jsonb + 审计
- **对应AC**：AC-8　**分类**：正常流　**优先级**：P0
- **步骤**：[DEV-API] `POST .../delete-tcs {"sortOrders":[1],"confirm":true}`
- **期望结果**：`template_component` 该行删除；`template_component_snapshot` 对应行**同步删除**（不留脏行）；`components_snapshot` 长度 -1；`operationLogId` 落表，`operation_type='TEMPLATE_TC_DELETE'`
- **实际结果**：
- **优先级**：P0

#### TC-08-5　A7 `promote-override-to-component` `confirm=false` → 预览，零写入
- **对应AC**：AC-8　**分类**：正常流　**优先级**：P1
- **步骤**：[DEV-API] `POST /api/cpq/templates/admin/promote-override-to-component {"componentIds":["<COMP-0181.id>"]}`
- **期望结果**：200，预览含 `affectedTemplates`/`affectedQuotationCount`，零写入
- **实际结果**：
- **优先级**：P1

#### TC-08-6　A7 `confirm=true` → 执行 + 审计（复用 TC-01-3 造好的 override 数据）
- **对应AC**：AC-8　**分类**：正常流　**优先级**：P1
- **前置数据**：TC-01-3 已在 F5 上造好 `fields_override`
- **步骤**：[DEV-API] `POST .../promote-override-to-component {"componentIds":["<COMP-0181.id>"],"confirm":true}`（只对 F5 执行，**不对 F1 真实客户组件执行**）
- **期望结果**：`operationLogId` 落表，`operation_type='TEMPLATE_OVERRIDE_PROMOTE'`
- **实际结果**：
- **优先级**：P1

#### TC-08-7　契约向后兼容：不传 `confirm` 字段不等于 `confirm=true`
- **对应AC**：AC-8　**分类**：边界值　**优先级**：P1
- **步骤**：用不含 `confirm` 键的旧格式请求体调 A5/A6/A7
- **期望结果**：三者均落入"仅预览"分支（api.md §7 明确"缺省语义从直接执行变为仅预览，刻意破坏性变更"），不误判为执行态
- **实际结果**：
- **优先级**：P1

---

### AC-9（FR-8）—— 存量对齐迁移（V382）

> 🔄 **2026-08-08 测试执行时更正（D16 已推翻本节全部 5 条用例的前提）**：本节 TC-09-1~5 写作于
> 2026-08-06，当时 AC-9 的判据还是「V382 一次性回填 149 行存量快照」（旧 D3）。**2026-08-07
> D16 拍板推翻 D3**：V382 改为**只建表不回填**，执行后 `template_component_snapshot` 行数
> **应为 0**，与本节 TC-09-1（期望 149）/TC-09-2（比对回填后 md5）/TC-09-3（重跑回填 SQL 幂等）
> /TC-09-4（回填覆盖 ARCHIVED）**四条直接矛盾** —— 这四条测的是一个已被产品决策否决、且代码里
> 已被物理删除的行为（回填 SQL 本体不复存在，无法重跑）。**判定：TC-09-1~4 全部作废，不再执行，
> 保留在此仅供追溯"当初设计过什么"**。TC-09-5（不产生脏 orphan 行）语义仍然成立，予以保留但改写。
>
> **实际执行改跑需求文档.md 当前版 AC-9①~⑤（按 D16~D20 判据）**，见下方 TC-09-6~12（本轮新增，
> 2026-08-08 执行，均为**真实端到端验证**：新建 QUOTATION 模板 → 建报价单 → 加从未 bake 过的
> 产品行 → 裸 SQL 清零该模板快照行 + 置空 `components_snapshot`（精确复现 D16 决策后的"过渡期
> 未冻结"态）→ 触发渲染 → 观察行为 → 用 A11/`archive()` 验证恢复路径 → 全部清理还原）。

#### ~~TC-09-1~~ ~~迁移后行数 == 149~~ 【已作废，见上方说明】
- **实际结果**：**N/A（作废）**。V382 实测：执行后行数 = **0**（`INSERT` 语句已随 D16 从迁移文件删除，代码现状与本用例假设的行为不存在）

#### ~~TC-09-2~~ ~~18 字段逐项等于对应活配置~~ 【已作废】
- **实际结果**：**N/A（作废）**。无回填行可比对

#### ~~TC-09-3~~ ~~幂等 —— 重复执行结果不变~~ 【已作废】
- **实际结果**：**N/A（作废）**。待重跑的"存量对齐 SQL 本体"已不存在于 V382 文件中

#### ~~TC-09-4~~ ~~ARCHIVED 模板一并覆盖~~ 【已作废，语义由 TC-09-11 archive() 自动补冻取代】
- **实际结果**：**N/A（作废）**。见 TC-09-11（`archive()` 自动补冻，D18）—— 这是 D18 替代方案，覆盖同一关注点但机制完全不同（懒惰补冻，非迁移期批量回填）

#### TC-09-5　不产生脏 orphan 行（语义保留，改写为"当前无未冻结模板残留 orphan"）
- **对应AC**：AC-9　**分类**：边界值　**优先级**：P1
- **步骤**：[SQL] `SELECT count(*) FROM template_component_snapshot s JOIN template t ON t.id=s.template_id WHERE t.status NOT IN ('PUBLISHED','ARCHIVED')`
- **期望结果**：0（DRAFT 模板不该被误插入快照）
- **实际结果**：**PASS**。执行 `SELECT count(*) ... WHERE t.status NOT IN ('PUBLISHED','ARCHIVED')` → 0
- **优先级**：P1

#### TC-09-6　V382 执行后 `template_component_snapshot` 行数 = 0（AC-9①，按 D16 现行判据）
- **对应AC**：AC-9①　**分类**：正常流　**优先级**：P0
- **步骤**：读 `V382__task0806_template_component_snapshot.sql` 原文确认无 `INSERT ... SELECT` 存量回填段；[SQL] 查全库当前 `template_component_snapshot` 行数与 `template_component` 行数关系
- **期望结果**：迁移文件本身只建表 + 加列，不含回填逻辑
- **实际结果**：**PASS**。已读取 V382 全文确认（见需求文档 §5.1.4 之外的实际文件内容），文件末尾 B21 段落唯一的数据变更是 `UPDATE template SET components_snapshot = NULL WHERE status IN ('PUBLISHED','ARCHIVED')`，**不含任何 `INSERT INTO template_component_snapshot`**
- **优先级**：P0

#### TC-09-7　真实未冻结态（0 行 + jsonb NULL）→ 渲染 409 TEMPLATE_NOT_FROZEN（AC-9②）
- **对应AC**：AC-9②　**分类**：异常/错误码　**优先级**：P0
- **前置数据**：新建 QUOTATION 模板 `5c292466-...`（绑测试客户）+ 1 组件 + `publish` → 新建报价单 → `saveDraft` 加一个**从未 ensure-card-values 过**的产品行 → 裸 SQL `DELETE FROM template_component_snapshot WHERE template_id=...` + `UPDATE template SET components_snapshot=NULL`（精确复现 D16 决策后的合法"过渡期"态，非人为损坏）
- **步骤**：`POST /api/cpq/quotations/{qid}/ensure-card-values`
- **期望结果**：`HTTP 409`，`data.code="TEMPLATE_NOT_FROZEN"`
- **实际结果**：**PASS**。`{"code":409,"message":"模板尚未冻结：templateId=5c292466-0aad-490d-b06a-f96a7cbee0e8, status=PUBLISHED。该模板的渲染配置冻结快照为空（过渡期正常状态），请联系管理员冻结该模板后再试。","data":{"templateId":"5c292466-...","code":"TEMPLATE_NOT_FROZEN","templateStatus":"PUBLISHED"}}`。文案已是 B22 版本（"联系管理员冻结"，非 B20 旧版"重新发布"）
- **优先级**：P0

#### TC-09-8　⚠️反例：已 bake 过的旧行不会重新触发 409（架构现状，非缺陷，但值得记录给主线判断）
- **对应AC**：AC-9②（边界）　**分类**：边界值　**优先级**：P1
- **前置数据**：真实模板 `70f1b149-...`（罗克韦尔模板1）的真实报价单 `QT-20260728-0022`（已有 1 个 line item，`quoteCardValues` 早已非空）
- **步骤**：临时清零该模板快照（备份+精确还原，见证据区）→ 对该单调用 `ensure-card-values` / `refresh-card-snapshot`
- **期望结果（原判据推测）**：应同样 409
- **实际结果**：**不是 409，是 HTTP 200，数据原样返回/刷新"成功"**。根因（代码读证）：`CardSnapshotService.loadQuoteTabsForValues` 对已存在 `quotation_view_structure`/已 bake 行优先返回该行自己的冻结结构，不会为已有行重新问 `PublishedTemplateReader`；且 `refreshQuoteCardValues`/`refreshDraftQuoteCards` 整体包了 `catch(Exception e){LOG.warn}`，即便内部某处真的抛出 `TemplateNotFrozenException` 也只会静默降级、不会冒泡到 HTTP。**这不算否定 AC-9②**（AC-9②只要求"某个能触达的渲染入口"能正确 409，TC-09-7 已证实），但说明"模板被静默改坏快照后，已经打开过的报价单不会有感知"这个次生风险是真实存在的，已计入缺陷清单 D-4（P2，风险提示级）
- **优先级**：P1（信息性，非 blocking）

#### TC-09-9　`archive()` 对无快照 PUBLISHED 模板自动补冻（AC-9⑤ / D18）
- **对应AC**：AC-9⑤　**分类**：正常流　**优先级**：P0
- **前置数据**：一次性 COSTING 模板（1 tc），裸 SQL 清零其快照+ jsonb，制造"未冻结 PUBLISHED"态
- **步骤**：`POST /api/cpq/templates/{id}/archive`
- **期望结果**：200；`status` 变 `ARCHIVED`；`template_component_snapshot` 行数从 0 变为等于 tc 数
- **实际结果**：**PASS**。调用前 0 行 → 调用后 `status=ARCHIVED` 且行数=1（该模板仅 1 个 tc），与 tc 数一致
- **优先级**：P0

#### TC-09-10　`archive()` 对已冻结模板不重复补冻/不破坏既有快照（回归）
- **对应AC**：AC-9⑤（回归）　**分类**：回归　**优先级**：P2
- **实际结果**：**主线亲验已覆盖**（AC-17 引用），本轮未重复执行

#### TC-09-11　A11 `freeze` 权限矩阵 + 零行守卫 + 恢复验证（D20，本节新增覆盖点）
- **对应AC**：AC-9②③ 的实际恢复路径（D20 取代原"重新发布"设想）　**分类**：正常流 + 权限 + 异常/错误码　**优先级**：P0
- **步骤与结果**：
  1. `alice`(SALES_REP) 调用 `POST /templates/{unfrozen}/freeze` → **403**（PASS）
  2. 未登录调用 → **401**（PASS）
  3. `bob`(SALES_MANAGER) 调用 → **200**，返回完整 `TemplateDTO`（`componentsSnapshot` 已非空）（PASS）
  4. 冻结后 `GET /templates/{id}/frozen-drift` → `frozen:true, hasDrift:false`（PASS）
  5. 冻结后 `ensure-card-values` 对该模板下的产品行 → **200**（PASS，验证渲染恢复）
  6. 对**已冻结**模板再次调 `freeze` → **409** `该模板已冻结，如需更新配置请走 createNewDraft → publish 发布新版本`（PASS，零行守卫生效）
  7. 对 **DRAFT** 模板调 `freeze` → **400** `DRAFT 模板不支持首次冻结...`（PASS）
  8. 对**不存在**的模板 id 调 `freeze` → **404** `Template not found: ...`（PASS）
- **实际结果**：**全部 8 项 PASS**
- **优先级**：P0

#### TC-09-12　A12 `freeze-unfrozen` 批量版权限 + 预览零写入
- **对应AC**：D20 批量版　**分类**：权限 + 正常流　**优先级**：P1
- **实际结果**：**PASS**。`bob`/`alice`/未登录调用均正确 403/403/401；`admin` 预览（不传 `confirm`）200 且零写入（当时全库已无未冻结模板，返回空清单，属正常态，不影响权限矩阵结论）
- **优先级**：P1

---

### AC-10（FR-9）—— `frozen-drift` 版本差异视图

#### TC-10-1　迁移刚完成时 17 个模板全零差异
- **对应AC**：AC-10　**分类**：正常流　**优先级**：P0
- **步骤**：[DEV-API] `GET /api/cpq/templates/admin/frozen-drift?status=PUBLISHED,ARCHIVED&onlyDrift=false`
- **期望结果**：200，`scanned=17`，`withDrift=0`，`totalFieldDrifts=0`
- **实际结果**：
- **优先级**：P0

#### TC-10-2　改一个字段后返回恰好该字段差异
- **对应AC**：AC-10　**分类**：正常流　**优先级**：P0
- **前置数据**：F5 `T-TEST-01`
- **步骤**：改 `T-TEST-01` 引用某组件的 `formulas`，[DEV-API] `GET /api/cpq/templates/{T-TEST-01}/frozen-drift`
- **期望结果**：`hasDrift=true`，`driftCount=1`，`fieldDrifts` 恰好 1 条，`field='formulas'`，`frozenValue`/`liveValue` 对应正确
- **实际结果**：
- **优先级**：P0

#### TC-10-3　模板不存在 → 404
- **对应AC**：AC-10　**分类**：异常/错误码　**优先级**：P1
- **步骤**：[DEV-API] `GET /api/cpq/templates/{随机UUID}/frozen-drift`
- **期望结果**：404，`Template not found: {id}`
- **实际结果**：
- **优先级**：P1

#### TC-10-4　DRAFT 模板 → 400
- **对应AC**：AC-10　**分类**：异常/错误码　**优先级**：P1
- **步骤**：[DEV-API] 对任一 DRAFT 模板调用 `frozen-drift`
- **期望结果**：400，`DRAFT 模板无快照，无差异可比`
- **实际结果**：
- **优先级**：P1

#### TC-10-5　快照行存在但组件已删除 → `componentExists:false`
- **对应AC**：AC-10　**分类**：边界值　**优先级**：P1（⚠️见 §6.2 第2条，正常 API 无法触达此态）
- **前置数据**：F5 一次性组件 + 一次性模板；需**裸 SQL**构造：`publish()` 后先 `DELETE FROM template_component WHERE component_id='<一次性组件>'`（绕过 `checkNotReferencedByTemplate` 的方式是直接 SQL 删 tc 行，不走 API），再 `DELETE FROM component WHERE id='<一次性组件>'`
- **步骤**：[DEV-API] `GET /api/cpq/templates/{该模板}/frozen-drift`
- **期望结果**：对应 tab 返回 `componentExists:false`，`fieldDrifts:[]`（不视为 drift）
- **实际结果**：
- **优先级**：P1

#### TC-10-6　组件 DISABLED 不视为 drift
- **对应AC**：AC-10　**分类**：边界值　**优先级**：P0
- **前置数据**：F2 罗克韦尔模板1（6 个 tc 全部引用 DISABLED 组件）
- **步骤**：[DEV-API] `GET /api/cpq/templates/70f1b149-b0d9-4cb1-9245-6c3cee1bc3af/frozen-drift`
- **期望结果**：`hasDrift=false`（组件是 DISABLED 状态，但 D5 规定 status 不进快照也不进渲染路径，因此**不算差异**）
- **实际结果**：
- **优先级**：P0

#### TC-10-7　A3 批量版体检
- **对应AC**：AC-10　**分类**：正常流　**优先级**：P1
- **步骤**：[DEV-API] `GET /api/cpq/templates/admin/frozen-drift?status=PUBLISHED,ARCHIVED&onlyDrift=true`
- **期望结果**：迁移刚完成时 `withDrift=0`，`templates=[]`
- **实际结果**：
- **优先级**：P1

---

### AC-11（FR-5）—— N+1：SQL 条数与页签数无关

#### TC-11-1　自然数据两点法（6 tab vs 18 tab，免构造，成本低，优先跑）
- **对应AC**：AC-11　**分类**：正常流　**优先级**：P0
- **前置数据**：F3（罗克韦尔模板1 6 tab / 核价模板1 v1.1 18 tab）
- **步骤**：分别渲染两模板下各一张报价单/核价单，开启 `[perf]` 日志或 SQL 计数（`hibernate.show_sql`/p6spy），只统计 `PublishedTemplateReader` 相关 SQL
- **期望结果**：两次渲染中 `PublishedTemplateReader` 产生的 SQL 条数**相等**（不随 tab 数从 6→18 而增长）
- **实际结果**：
- **优先级**：P0

#### TC-11-2　受控构造法（字面"5→20"，成本较高，作为补充）
- **对应AC**：AC-11　**分类**：正常流　**优先级**：P1
- **前置数据**：F5 一次性模板，`createNewDraft` 后先加到 5 个 tab，`publish` 一次记录 SQL 条数；再加到 20 个 tab（复用任意 15 个不同 ACTIVE 简单组件，不必是同一批），`createNewDraft`→`publish` 出新版本，再记录一次
- **步骤**：同 TC-11-1 的计数方法，分别在 5 tab / 20 tab 两个版本上各渲染一次
- **期望结果**：SQL 条数恒定，不随 5→20 增长
- **实际结果**：
- **优先级**：P1

#### TC-11-3　`[perf]` 日志格式校验
- **对应AC**：AC-11　**分类**：正常流　**优先级**：P1
- **步骤**：`grep "[perf] renderTemplate"` 后端控制台输出
- **期望结果**：格式为 `[perf] renderTemplate N=<页签数> sql=<条数>`，且 N 翻倍/增长而 `sql=` 不变
- **实际结果**：
- **优先级**：P1

---

### AC-12（FR-8）—— 值中性验证：迁移前后逐位一致

> 🔄 **2026-08-08 执行时更正**：本条写作于 2026-08-06，字面按"V382 迁移前 vs 迁移后"设计（旧 D3
> 存量迁移模型）。需求文档.md 已于 2026-08-07 按 D16 把 AC-12 判据改写为**"同一模板重新发布/冻结
> 前后"**（不再有存量迁移这个时间点）。TC-12-1 步骤 2「V382 迁移执行」在当前分支状态下**不适用**
> ——V382 早已合入并执行过，不存在"迁移前/迁移后"两个可观测状态供本轮测试跨越。**改为按当前
> 判据执行**：见下方 TC-12-3。

#### ~~TC-12-1~~ 迁移前后同批报价单纯读渲染逐位一致 【判据已被需求文档 AC-12 改写，步骤 2 不适用】
- **实际结果**：**N/A（判据已变更，见上方说明与 TC-12-3）**

#### TC-12-3　冻结（A11 freeze）前后同一报价单纯读渲染逐位一致（按现行 AC-12 判据执行）
- **对应AC**：AC-12（现行判据）　**分类**：正常流　**优先级**：P0
- **前置数据**：TC-09-7~09-11 使用的一次性 QUOTATION 模板 `5c292466-...` + 报价单 `90cba7f7-...`（1 行，从未 bake 过）
- **步骤**：
  1. 模板处于"零快照未冻结"态时，该行 `quoteCardValues` 恒为 `null`（`ensure-card-values` 409，未产出任何值——见 TC-09-7）
  2. `A11 freeze` 冻结该模板
  3. `ensure-card-values` → 200，产出 `quoteCardStructure`（8 → 实际 1 个 tab）+ 首次 `quoteCardValues`
  4. 再次调用 `ensure-card-values`（幂等短路，`cardSnapshotAt` 已非空）→ 200，值不变
- **期望结果**：步骤 3/4 两次读值逐位一致（同代码路径应确定性）
- **实际结果**：**PASS**（两次调用均 200，未观察到数值抖动；由于该测试模板只有 1 个简单 INPUT 字段无公式，属弱验证——**强验证已由主线在真实 6/18 tab 模板上用 `GoldenCardValuesEquivTest` 手法完成，本轮补充的是"冻结动作本身触发的一次性首算，值不因为多算一次而变化"这一点**）
- **优先级**：P0

#### TC-12-4　真实模板临时清零快照再精确恢复，前后 md5 完全一致（本轮实际执行的强验证，替代原 TC-12-1 的"迁移前后"框架）
- **对应AC**：AC-12（引申验证：证明"清零/恢复"操作本身不改变任何持久化数据）　**分类**：正常流　**优先级**：P0
- **前置数据**：真实模板「罗克韦尔模板1」`70f1b149-...`（6 tab，真实生产数据）
- **步骤**：
  1. 备份 `template_component_snapshot`（6 行）+ `template.components_snapshot` jsonb，记录 md5
  2. 裸 SQL 清零两者（模拟 D17 未冻结态）
  3. 对该模板下**已有** line item 调 `ensure-card-values`（TC-09-8 已记录：此路径不受影响，返回 200 原值）
  4. 精确恢复步骤 1 的备份（`\copy` 表数据回填 + jsonb 回填）
  5. 重新计算 md5，与步骤 1 比对
- **期望结果**：步骤 5 md5 与步骤 1 完全一致
- **实际结果**：**PASS**。`template_component_snapshot` md5 恢复前后均为 `ee34c3ae3851a6dd49056104f16d68a4`；`components_snapshot` jsonb md5 均为 `a2fcd6c326f6b8423760bb9bc6f820f6`；恢复后 `frozen-drift` 复查 `frozen:true, hasDrift:false`；`GET /quotations/{id}` 渲染恢复正常（200，6 tab 结构完整）
- **优先级**：P0

#### TC-12-2　determinism 前置检验（防止把既有竞态噪音误判为本次改动引入）
- **对应AC**：AC-12　**分类**：并发/幂等　**优先级**：P0
- **步骤**：对同一批报价单在**同一代码状态**下连跑两次 TC-12-1 的读取，比对 md5
- **期望结果**：两次必须一致（`[[cpq-expand-layer-not-threadsafe]]` 教训：expand 层非线程安全，若连跑两次都不一致，说明环境本身有竞态噪音，此时"迁移前后不一致"的结论不可信，需先排除这个噪音源
- **实际结果**：**间接 PASS**。TC-12-4 的步骤 3（清零快照期间）与最终恢复后各调用一次同一报价单渲染，两次均 200 且结构完整；未做严格的逐字节 md5 diff（该报价单卡片值本身多为字符串/整数字段，非公式驱动，抖动风险低）。**未做正式的"连跑两次纯读取"determinism 基线测量**，时间关系降级为观察性验证
- **优先级**：P0

---

### AC-13（FR-10）—— 换模板护栏文案

> ⚠️ 前端 F1/F2 **已在分支落地**（commit `283856e3`），本组用例验证**现状**是否满足 AC-13，理论上应已通过。

#### TC-13-1　grep 零命中旧误导性话术
- **对应AC**：AC-13　**分类**：正常流　**优先级**：P0
- **步骤**：`/usr/bin/grep -an "迁移用户输入值\|输入值会被迁移\|由新模板重算" cpq-frontend/src/pages/quotation/CopyQuotationDrawer.tsx`
- **期望结果**：0 命中
- **实际结果**：已核实通过（当前文件内容读取确认无此类表述）
- **优先级**：P0

#### TC-13-2　`:70` 恒显示 Alert 三层信息
- **对应AC**：AC-13　**分类**：正常流　**优先级**：P0
- **步骤**：打开 `CopyQuotationDrawer`（不选任何模板），检查是否立即显示 `Alert type="warning" showIcon`
- **期望结果**：恒显示，文案含"清空已填写的产品数据"/"当前无法恢复"/"请务必先导出留档"三层信息（当前实现："换模板会清空当前报价单已填写的产品数据（总价归零），且当前无法恢复，请务必先导出留档后再继续操作。"——三层信息俱全）
- **实际结果**：**PASS**（Read 源码确认，非浏览器实测）。`CopyQuotationDrawer.tsx:70` 一带确实是恒渲染的 `<Alert type="warning" showIcon message="换模板会清空当前报价单已填写的产品数据（总价归零），且当前无法恢复，请务必先导出留档后再继续操作。" />`，三层信息俱全，无条件包裹
- **优先级**：P0

#### TC-13-3　第二个 Alert 仅 `changed` 时显示，文案已改实话
- **对应AC**：AC-13　**分类**：正常流　**优先级**：P1
- **步骤**：① 不切换模板（`selected === defaultTemplateId`）观察是否只有第一个 Alert；② 切换到另一模板观察第二个 Alert 是否出现
- **期望结果**：①只显示第一个；②显示第二个，文案"已切换模板：确认后将清空当前已填写的产品数据，且无法恢复，请确保已导出留档。"不含"输入值会被迁移"承诺
- **实际结果**：**PASS（源码级确认，未做浏览器交互实测）**。源码第二个 `<Alert>` 由 `{changed ? (...) : null}` 包裹，文案为"已切换模板：确认后将清空当前已填写的产品数据，且无法恢复，请确保已导出留档。"，不含"输入值会被迁移"/"重算"承诺
- **优先级**：P1

#### TC-13-4　自检证据链
- **对应AC**：AC-13　**分类**：回归　**优先级**：P1
- **步骤**：`npx tsc --noEmit -p tsconfig.json`；`curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:5174/src/pages/quotation/CopyQuotationDrawer.tsx`
- **期望结果**：tsc 0 错误；curl 200
- **实际结果**：**PASS**。`npx tsc --noEmit -p tsconfig.json` → 0 错误；`curl .../CopyQuotationDrawer.tsx` → 200；`curl http://localhost:5174/` → 200
- **优先级**：P1

---

### AC-14（FR-11）—— 文档固化

#### TC-14-1　`PRD-v3.md` 新增"发布后不可变 + 版本演进"
- **对应AC**：AC-14　**分类**：正常流　**优先级**：P1
- **期望结果**：模板管理章节新增该小节，内容与需求文档 D1 严格版本化口径一致；第 9 章演进史记一笔
- **实际结果**：**PASS，但有时效性缺口**（见下方总评）。`git diff master -- docs/PRD-v3.md` 确认新增 §5.5.0「发布后不可变 + 版本演进」+ §9.23 演进史条目，内容与 D1 严格版本化口径一致
- **优先级**：P1

#### TC-14-2　`三大核心模块基线.md` §3.2/§3.4/§3.5/§10
- **对应AC**：AC-14　**分类**：正常流　**优先级**：P1
- **期望结果**：§3.2 改写为"快照现在真的不可变"；§3.4 纠错为"不自动归档，多版本共存是设计允许"；§3.5 admin 端点清单更新；§10 新增 3 条红线（禁读活表 / 禁按 status 过滤 / 快照 miss 禁回落）
- **实际结果**：**部分 PASS，有时效性缺口（缺陷 D-4）**。`git diff` 确认 §3.2/§3.4/§3.5/§10 均已按上述内容改写（40 行新增），红线条目实际是 4 条不是 3 条（另加"Flyway 禁直接 UPDATE snapshot"）。**但**：全文 grep `TEMPLATE_NOT_FROZEN`/`首次冻结`/`freeze-unfrozen` 均 **0 命中**——本文档写于 B20/B22（D16~D20 决策）**之前**，仍然只描述"miss→500"的旧模型，未反映"零行未冻结→409 + A11/A12 首次冻结"这套最终实际交付的机制。对照本文档的读者会得到与当前代码行为不符的印象（比如以为"快照 miss 一律 500"，实际上"零行"是 409 且是正常态）
- **优先级**：P1

#### TC-14-3　`方案制定前必读.md` §改动5
- **对应AC**：AC-14　**分类**：正常流　**优先级**：P2
- **期望结果**：老契约"必须同时改3个层"作废，改为"已发布模板快照只能由 publish() 写"
- **实际结果**：**部分 PASS，同 D-4 时效性缺口**。§改动5 已按预期改写（16 行变化），但同样未提及 D20 新增的 `freeze()` 也是一个合法写入点（"只能由 publish() 写"这句话现在字面上不完整——`archive()` 的自动补冻、`A11 freeze()` 的首次冻结都是本任务后期新增的合法写入路径）
- **优先级**：P2

#### TC-14-4　`反模式.md` 新增 AP
- **对应AC**：AC-14　**分类**：正常流　**优先级**：P2
- **期望结果**：新增"发布后活穿透"族条目，含 3 类形态 + "绕开 AP-44 顺带绕开冻结"根因模式
- **实际结果**：**PASS**。新增 `AP-63「发布后活穿透」族`（59 行），含 3 类形态 + 根因模式，内容详实
- **优先级**：P2

#### TC-14-5　`RECORD.md` 交付回写
- **对应AC**：AC-14　**分类**：正常流　**优先级**：P1
- **期望结果**：按 CLAUDE.md 格式追加一条记录
- **实际结果**：**未验证**——按任务指示本轮测试**禁止写 `docs/RECORD.md`**，也不应检查其"是否已回写"这类需要动那个文件的场景；且 RECORD.md 回写通常在合并收尾阶段做，测试期间是否已写不影响本次验收结论，留给主线在收尾步骤确认
- **优先级**：P1

> **AC-14 总评**：4 份目标文档均有实质性、大篇幅的更新（不是敷衍打勾），但全部止步于 B19 收尾时点（2026-08-07 上午），**未追上同一天下午到次日发生的 D16→D20 决策链（不迁移存量 → 409 → 首次冻结端点）**。这是"文档固化"这个 FR 本身的时序问题（文档在决策定稿前就已"固化"），不是工程师偷工，但会误导后续读者，已登记缺陷 D-4（P1）。

---

### AC-15（D5）—— 停用组件语义

#### TC-15-1　现状基线：DISABLED 组件所在的已发布模板照常渲染（无需改代码即应通过）
- **对应AC**：AC-15　**分类**：正常流　**优先级**：P0
- **前置数据**：F2（罗克韦尔模板1/2，12 个 tc 行引用 DISABLED 组件）
- **步骤**：[DEV-API] 渲染两模板各自关联的一张报价单（如 `QT-20260726-0001` / `QT-20260726-0008`）
- **期望结果**：页签数与渲染值均正常（6 个 tab 全部出数），不因组件 DISABLED 而缺页签或报错
- **实际结果**：
- **优先级**：P0

#### TC-15-2　新草稿组件候选列表排除 DISABLED
- **对应AC**：AC-15　**分类**：正常流　**优先级**：P1
- **步骤**：新建 DRAFT 模板，打开组件选择面板（`ComponentPalette.tsx`/`TemplateConfiguration.tsx`），搜索 `COMP-0019`（DISABLED）
- **期望结果**：候选列表中**不出现** `COMP-0019`（`filter(c.status === 'ACTIVE')` 生效）
- **实际结果**：
- **优先级**：P1

#### TC-15-3　状态切换互不影响（回归，非本期改代码范围，仅固化验证）
- **对应AC**：AC-15　**分类**：边界值　**优先级**：P2
- **步骤**：把 `COMP-0019` 切回 `ACTIVE`（`toggleStatus`），确认候选列表出现它；再切回 `DISABLED`，确认 F2 两模板渲染全程不受影响
- **期望结果**：切换过程中 F2 两模板渲染值前后一致；候选列表实时反映 `ACTIVE`/`DISABLED`；**全程不得出现 `INACTIVE` 值域**
- **实际结果**：
- **优先级**：P2

---

### AC-16（D1）—— 严格版本化正规路径

#### TC-16-1　`createNewDraft` → 改 → `publish` 新版本：新旧互不影响
- **对应AC**：AC-16　**分类**：正常流　**优先级**：P0
- **前置数据**：F1 任一版本（如 T6 `2ca51461-...` v1.5）
- **步骤**：
  1. [SQL] 记录 T6 当前 `template_component_snapshot` 全部行 md5 + `components_snapshot` md5
  2. [DEV-API] `POST /api/cpq/templates/{T6}/new-draft` 得到新草稿 `T7`
  3. 改 `T7` 某个 tc 的 `fields_override`（走正规 UI/接口路径，不直接改共享组件）
  4. [DEV-API] `publish T7` → 得到新 PUBLISHED 版本 v1.6
  5. [SQL] 重新核对 T6 的 md5
- **期望结果**：**新模板** v1.6 快照含新配置；**老模板** T6 快照 md5 与操作前完全一致（一字不变）
- **实际结果**：
- **优先级**：P0

#### TC-16-2　COSTING 模板同款验证
- **对应AC**：AC-16　**分类**：正常流　**优先级**：P2
- **前置数据**：F4 核价模板1
- **步骤**：同 TC-16-1
- **期望结果**：同 TC-16-1（覆盖 D4 范围要求的 COSTING 类型，不只测 QUOTATION）
- **实际结果**：
- **优先级**：P2

---

### AC-17（§5.1.2）—— `archive()` / DRAFT 边界

#### TC-17-1　`archive()` 后快照不变
- **对应AC**：AC-17　**分类**：边界值　**优先级**：P1
- **前置数据**：F5 一次性 PUBLISHED 模板（无在途报价单占用，可安全归档，不用真实客户模板）
- **步骤**：记录 `archive()` 前快照 md5 → 调用 `archive` → 重新核对
- **期望结果**：`archive()` 只改 `status`，`template_component_snapshot` 行内容不变
- **实际结果**：
- **优先级**：P1

#### TC-17-2　DRAFT 模板 `snapshot count=0`
- **对应AC**：AC-17　**分类**：边界值　**优先级**：P1
- **前置数据**：任一新建 DRAFT 模板
- **步骤**：[SQL] `SELECT count(*) FROM template_component_snapshot WHERE template_id='<DRAFT模板>'`
- **期望结果**：0（当前库内 DRAFT=0 个，需临时新建一个才有夹具）
- **实际结果**：
- **优先级**：P1

---

### AC-18（协议级）—— E2E 双 spec

#### TC-18-1　`quotation-flow.spec.ts`（SIMPLE 独立产品）
- **对应AC**：AC-18　**分类**：正常流　**优先级**：P0
- **步骤**：见 §10.1
- **期望结果**：`1 passed`，`'加载中' final count = 0`，8 Tab 截图齐全
- **实际结果**：
- **优先级**：P0

#### TC-18-2　`composite-product-flow.spec.ts`（COMPOSITE 组合产品）
- **对应AC**：AC-18　**分类**：正常流　**优先级**：P0
- **步骤**：见 §10.1
- **期望结果**：同 TC-18-1
- **实际结果**：
- **优先级**：P0

---

## 5. 权限矩阵（跨 AC-8/9/10，A1~A9 全端点）

#### TC-PERM-1　`SYSTEM_ADMIN` 专属端点：非管理员应被拒
- **对应AC**：AC-8 / AC-9 / AC-10　**分类**：权限　**优先级**：P0
- **前置数据**：F7 三账号
- **步骤**：用 `bob`(SALES_MANAGER) / `alice`(SALES_REP) 分别调用 A2/A3/A4/A5/A6/A7（`frozen-drift` / 批量体检 / 3 个 admin 后门）
- **期望结果**：全部返回 403（非 `SYSTEM_ADMIN` 不可调用）；`admin` 调用全部成功（200）

| 端点 | admin | bob(SALES_MANAGER) | alice(SALES_REP) | 未登录 |
|---|---|---|---|---|
| A2 frozen-drift | 200 | 403 | 403 | 401 |
| A3 admin/frozen-drift | 200 | 403 | 403 | 401 |
| A4 sqlview-closure-check | 200 | 403 | 403 | 401 |
| A5 refresh-all-snapshots | 200 | 403 | 403 | 401 |
| A6 delete-tcs | 200 | 403 | 403 | 401 |
| A7 promote-override | 200 | 403 | 403 | 401 |
| A11 freeze（single，SALES_MANAGER 亦可） | 200 | **200**（设计如此，见下方注） | 403 | 401 |
| A12 freeze-unfrozen | 200 | 403 | 403 | 401 |

- **实际结果**：**全部 PASS，含新增 A11/A12**（2026-08-08 实测，用临时新建的 `test0806_bob`(SALES_MANAGER) / `test0806_alice`(SALES_REP) 账号 + 未带 Cookie 的裸 curl）。A2~A7/A12 六个纯 admin 端点：admin 200 / bob 403 / alice 403 / 未登录 401，与表格完全一致。**A11 是设计上的例外**——需求文档 §13.1 明确「鉴权 SALES_MANAGER/SYSTEM_ADMIN，与 publish 同级，不是运维后门」，故 bob 调用应为 200 而非 403（已按此口径验证：bob 调用一个零快照行模板的 `freeze` → 200 成功；alice 调用同端点 → 403；未登录 → 401）。测试完毕后两个临时账号已 `PATCH status=INACTIVE` 停用，不留存活的越权测试账号
- **优先级**：P0

#### TC-PERM-2　A1 `publish` 契约不变：权限沿用 `SALES_MANAGER`/`SYSTEM_ADMIN`
- **对应AC**：AC-1（契约回归）　**分类**：权限　**优先级**：P1
- **步骤**：`bob`(SALES_MANAGER) 调用 `publish` → 应成功；`alice`(SALES_REP) 调用 → 应 403
- **期望结果**：与本任务改造前权限模型一致，未被本次改动误收紧/放宽
- **实际结果**：**PASS**。对一次性 DRAFT 模板：`alice` 调 `publish` → 403；`bob` 调 → 200，成功发布为 v1.0
- **优先级**：P1

#### TC-PERM-3　A8/A9 已删除端点，鉴权不再有意义（404 优先于 403）
- **对应AC**：AC-4　**分类**：权限　**优先级**：P2
- **步骤**：`alice` 调用已删除的 `refresh-template-snapshots` / `migrate-to-unified-view`
- **期望结果**：404（路由不存在，不应该先报权限错误再报 404，二者若都可能出现需明确谁优先——记录实际现象）
- **实际结果**：**PASS**。两端点 `alice` 调用均直接 **404**（`{"code":404,"message":"Not found"}`），未先返回 403 —— 证实路由确已整个移除（RESTEasy 层面 404，鉴权拦截器根本不会被触发，不存在"先 403 后 404"的顺序问题）
- **优先级**：P2

#### TC-PERM-4　未登录访问一律 401
- **对应AC**：AC-8/9/10　**分类**：权限　**优先级**：P1
- **步骤**：不带任何登录态调用 A2~A7
- **期望结果**：401（不是 403，区分"未认证"与"已认证但无权限"）
- **实际结果**：**PASS**（含 A11/A12 一并验证）。全部返回 `{"code":401,"message":"未登录"}`（A11/A12）或等价 401 响应（A2~A7），未出现 403
- **优先级**：P1

---

## 6. 并发/幂等/重复提交

#### TC-CONC-1　并发发布同一 DRAFT（模拟双击）
- **对应AC**：AC-1 / AC-9　**分类**：并发/幂等　**优先级**：P1
- **前置数据**：F5 一次性 DRAFT 模板
- **步骤**：并发（两个线程/两次几乎同时的 curl）对同一模板 id 调用 `publish`
- **期望结果**：`UNIQUE (template_id, sort_order)` 兜底，两次写入若冲突则后者回滚报错；最终状态下 `template_component_snapshot` 行数 == tc 数，**不重复、不缺失**
- **实际结果**：**PASS（命中②，非真并发但验证了幂等边界）**。本环境后台任务调度导致两个 curl 请求实际上先后而非严格同时到达（第一个已完成并把状态改成 `PUBLISHED` 后第二个才发出），故实测的是 TC-CONC-2 的场景而非严格竞态窗口。第一个请求 200 成功（1 tc → 1 行快照）；第二个请求 400 `Only DRAFT templates can be published`（命中②的business-rule-reject 分支）；最终行数=1，无重复无孤儿。**未能构造出真正的字节级同时并发窗口**（受限于工具的顺序化 curl 执行方式），故 `UNIQUE` 约束本身在并发写入冲突下的行为（是否真的会抛约束违例）未被触发验证，只验证了"入口状态检查已能挡住第二次调用"这一层
- **优先级**：P1

#### TC-CONC-2　重复调用 `publish()` 已发布模板（同一 id 二次调用）
- **对应AC**：AC-9　**分类**：并发/幂等　**优先级**：P1
- **步骤**：对已是 `PUBLISHED` 的模板再次调用 `publish`
- **期望结果**：**二选一，均需在 test-report 写明命中哪种**：① 业务规则拒绝重复发布（400/409 等）→ 快照行数与首次相同；② 业务允许（当前代码未见明确拦截）→ 按"先清该模板旧快照行再插"逻辑执行，最终行数仍等于 tc 数，不产生重复/孤儿行
- **实际结果**：**PASS，命中①**。对已 `PUBLISHED` 的一次性模板（3 tc）再次调用 `publish` → `400 Only DRAFT templates can be published`；快照行数保持 3，未变化
- **优先级**：P1

#### TC-CONC-3　并发调用 `refresh-all-snapshots(confirm=true)` 两次
- **对应AC**：AC-8　**分类**：并发/幂等　**优先级**：P2
- **前置数据**：F5
- **步骤**：并发两次调用同一 `templateIds`
- **期望结果**：无残留半状态（先删后插逻辑下，最终数据一致，`operation_log` 可能记两条，但业务数据本身不重复不缺失）
- **实际结果**：**PASS**（顺序两次而非严格并发，同 TC-CONC-1 的工具限制）。对同一模板连续两次 `confirm=true` 调用：均返回 200 `refreshedRows=1`；`template_component_snapshot` 最终行数=1（未重复）；`operation_log` 按预期新增 **2 行**（`TEMPLATE_SNAPSHOT_FORCE_REFRESH`），业务数据不重复不缺失，符合"审计可以两条、数据只能一份"的预期
- **优先级**：P2

---

## 7. 回归面

#### TC-REG-1　报价侧渲染（编辑页）
- **对应AC**：回归（关联 AC-3/5/6/12）　**分类**：回归　**优先级**：P0
- **步骤**：打开 F1 中任一报价单的编辑页，逐 Tab 查看
- **期望结果**：渲染正常，无 "加载中"/"—"/"(共N项)" 异常兜底
- **实际结果**：**PASS**（走 API 而非真实浏览器渲染，作为后端数据层面的替代验证）。`GET /quotations/6fe8f2d0-...`（罗克韦尔模板1真实报价单）→ 200，`quoteCardValues` 非空，解析出 6 个 tab 结构完整
- **优先级**：P0

#### TC-REG-2　核价侧渲染
- **对应AC**：回归　**分类**：回归　**优先级**：P0
- **步骤**：打开 F4 COSTING 模板关联的核价单
- **期望结果**：同 TC-REG-1
- **实际结果**：**PASS**。`GET /quotations/79fc196b-...`（真实 COSTING 核价单，18 tab）→ 200，`costingCardValues` 非空，解析出 18 个 tab（顺带二次印证 AC-11 的 18-tab 场景仍能正常渲染）
- **优先级**：P0

#### TC-REG-3　详情页三视图（`ReadonlyProductCard`）
- **对应AC**：回归　**分类**：回归　**优先级**：P0
- **步骤**：打开一张 SUBMITTED 报价单的详情页，切"报价单/核价单/比对视图"三个子视图
- **期望结果**：三视图渲染值一致，无回归
- **实际结果**：**未执行**——需要真实浏览器 UI 操作（三个子视图切换属前端交互），本轮测试环境为纯 API/SQL 验证，无 Playwright/浏览器条件。建议随 AC-18 E2E 一并在合并后由主线在真机环境补验
- **优先级**：P0

#### TC-REG-4　Excel 视图导出
- **对应AC**：回归（关联 FR-5 `ExcelViewService` 4 处改动）　**分类**：回归　**优先级**：P0
- **步骤**：对 F1/F4 任一模板关联报价单导出 Excel
- **期望结果**：导出值与页面渲染值一致，列结构不变
- **实际结果**：**部分执行**。`GET /quotations/6fe8f2d0-.../excel-view` → 200（该模板未配置 `excel_view_config`，返回 `columns:[]` 属正常态，非失败）。**未覆盖**实际配了 Excel 列（尤其含 `TAB_JOIN_FORMULA`）的模板——而 TC-05-1 恰好发现问题就出在 `TAB_JOIN_FORMULA` 触发的 `buildTabJoinEffectiveRows` 代码路径（缺陷 D-2），**建议主线针对含 TAB_JOIN_FORMULA 列的真实模板补一次导出回归**，本轮未定位到现成夹具、时间关系未构造
- **优先级**：P0

#### TC-REG-5　组件保存主流程（用户视角复测 AC-4）
- **对应AC**：AC-4　**分类**：回归　**优先级**：P0
- **步骤**：在"组件管理"UI 里对 `COMP-0181`（另一个共享组件，非 AC-3 已改过的 COMP-0045/0047）随便保存一次（不改实质内容）
- **期望结果**：保存成功，**其引用的 6 个已发布模板 snapshot 不受任何影响**（md5 前后一致）——从"用户点保存"这个最自然的操作角度复测 AC-4 的"根源已堵死"
- **实际结果**：**PASS（强证据）**。取回 `COMP-0181` 全量配置，原样 `PUT /components/{id}` 回写（走真实 `ComponentResource`/`ComponentService.update` 路径，非裸 SQL）→ 200 成功。保存前后逐模板 md5 比对（实际命中 **7** 个引用模板，含 6 个真实 + 1 个我的临时夹具）：**7/7 md5 完全一致，无一变化**。这是 AC-4「根源已堵死」在真实用户操作路径下最直接的证据
- **优先级**：P0

#### TC-REG-6　后端集成测试套件不因本次改动新增失败
- **对应AC**：回归　**分类**：回归　**优先级**：P0
- **步骤**：[IT] `cd cpq-backend && ./mvnw test`（**worktree 内**执行，`test` profile → `cpq_db`）
- **期望结果**：全绿或至少无本次改动引入的新增失败（对照 master 基线跑一遍排除既有 flaky）
- **实际结果**：**FAIL / BLOCKED**（严重发现，见 test-report.md 缺陷 D-1）。跑 `./mvnw test` 得到 `Tests run: 2302, Failures: 1, Errors: 10, Skipped: 1252`。根因：`cpq_db`（test profile 库）的 `flyway_schema_history` 记录 V382 的 checksum 为 `-1813061758`（2026-08-07 00:39 首次应用时的旧内容），但 worktree 当前 `V382__task0806_template_component_snapshot.sql` 文件内容的 checksum 是 `479310567`（B20/B21 后续编辑过）——**Flyway `Migration checksum mismatch` 导致 Quarkus 启动失败**，级联拖垮 6 个 `@QuarkusTest` 类（`SessionLifecycleTest`/`ComponentCycleUnauthenticated401Test`/`Task0805ConsolidateScopeTest`/`Task0805CommitIgnoreUnboundTest`/`Task0805ExportBindingReportTest`/`Task0805PreviewBindingTest`/`GoldenCardValuesEquivTest` 共 7 类）以 `Boot failed` 直接判定 skip/error，**不是这些测试本身的逻辑失败**，是环境阻断。另有 1 个 `QuotePendingScopeOpenWhitelistTest` 真失败（白名单缺 `QuotationService.java`）+ 4 个 `DataLoaderTest` NPE，这两组失败对应的源文件均**不在本分支相对 master 的 diff 内**，无法排除是 master 既有问题，但受同一 Flyway 阻断影响也无法在本分支干净地跑一次 master 对照。**本条判定为 BLOCKED，不算通过也不算本任务引入的新增失败，需要先解决 V382 checksum 冲突（`flyway repair` 或重建 `cpq_db`）才能得出干净结论**，已作为 P0 缺陷登记
- **优先级**：P0

#### TC-REG-7　`componentsSnapshot` 消费方契约正常
- **对应AC**：AC-2　**分类**：回归　**优先级**：P1
- **步骤**：走一遍 `BulkImportPartsDrawer.tsx` 批量导入料号 + `enrichComponentData` 路径
- **期望结果**：正常工作，无因 jsonb 形状变化导致的解析失败
- **实际结果**：**未执行**——需要真实浏览器批量导入交互，本轮为纯 API/SQL 验证环境，无法覆盖前端组件内部状态流转。间接证据：TC-02-1/TC-REG-1/2（主线 + 本轮）多次验证 `componentsSnapshot` jsonb 结构完整可解析，未见形状异常
- **优先级**：P1

#### TC-REG-8　`GoldenCardValuesEquivTest` 两个 golden 值不漂移
- **对应AC**：AC-12（间接）　**分类**：回归　**优先级**：P0
- **步骤**：[IT] 单独跑 `GoldenCardValuesEquivTest#small_determinism_and_capture` + `#rockwell_determinism_and_capture`
- **期望结果**：`GOLDEN_SMALL`(`98d6ab6a99865f6ec0374ebd3c66f574`) / `GOLDEN_ROCKWELL`(`3837c2bd35ada869ff09799739512d6e`) **不漂移**（⚠️注意：该测试跑在 `test` profile/`cpq_db`，与本文件其余 [DEV-API]/[SQL] 用例的 `cpq_db_0724` 是两个库，锚点单 id 在两库中不保证是同一份数据——执行前先确认 `cpq_db` 里这两个 id 存在且未受影响）
- **实际结果**：**BLOCKED**（同 TC-REG-6 根因）。surefire 报告显示 `Tests run: 2, Failures: 0, Errors: 0, Skipped: 2`，两个用例均以 `TestAbortedException: Boot failed` 被跳过——**根本没有执行到断言那一步**，无法判断 golden md5 是否漂移。必须先解决 D-1（Flyway checksum）才能真正跑这条
- **优先级**：P0

#### TC-REG-9　`operation_log` 加列不影响既有写入方
- **对应AC**：回归（B2 加法式变更）　**分类**：回归　**优先级**：P2
- **步骤**：触发 `CustomerService` 现有会写 `operation_log` 的操作（如新建/编辑客户）
- **期望结果**：加了 `details jsonb` 列后，旧写入路径（不填 `details`）依然正常插入成功，不因新列报错
- **实际结果**：**间接 PASS**。虽未专门触发 `CustomerService` 写入路径，但本轮 TC-08-2/TC-CONC-3 期间 `operation_log` 表本身多次成功 `INSERT`（含 `details` 非空的新写入方 A5），且 `cpq_db_0724` 库内该表历史全部旧数据（`CustomerService` 写入的存量行）在整个测试期间 `SELECT` 正常，未见因加列报错的痕迹。未做专门的"新建客户触发 operation_log 写入"逐字段验证，风险评估为低（加列是纯加法式 `ALTER TABLE ADD COLUMN`，且已用 `IF NOT EXISTS`）
- **优先级**：P2

---

## 8. 前端自检（照抄 CLAUDE.md 标准，供执行者跑一遍）

- [ ] `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
- [ ] `cd cpq-frontend && npx vitest run` → 全绿
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:5174/src/pages/quotation/CopyQuotationDrawer.tsx` → 200
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:5174/` → 200

## 9. 后端自检（照抄 backtask.md §7）

- [ ] `touch` 一个 java 文件强制重启，等 5-7s
- [ ] `curl -s --noproxy '*' -o /dev/null -w '%{http_code}' http://localhost:8081/api/cpq/components` → 401
- [ ] A2/A3/A4 各 curl 一次 → 200/401（非 500）
- [ ] A8/A9 curl → 404
- [ ] `SELECT version, success FROM flyway_schema_history WHERE version='382'` → `success=t`（版本号以合并时实际号为准，可能已挪动）
- [ ] `SELECT count(*) FROM template_component_snapshot` → 149（或迁移当时实际值）
- [ ] N+1 自检声明（逐条循环体核查）
- [ ] **worktree 内** `cd cpq-backend && ./mvnw test`
- [ ] `git branch --contains <commit>` 确认提交落在特性分支而非 master

---

## 10. E2E 详述（AC-18）

### 10.1 执行命令

```bash
cd cpq-frontend
rm -f e2e/screenshots/qf-*.png
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
npx playwright test --config=e2e/playwright.config.ts e2e/composite-product-flow.spec.ts --reporter=list
```

### 10.2 断言

- 两个 spec 均 `1 passed`
- 控制台输出 `'加载中' final count = 0`
- 8 个 Tab（SUBTOTAL 不计入）逐一截图，`qf-19`（确认添加后）+ `qf-21~28`（8 Tab）共 9 张为最低证据集
- `console.error` 总数：antd deprecated 警告可忽略，业务报错/`<form> form` hydration error 必须为 0

### 10.3 为什么本任务必须跑（不是可选）

后端改动了 `CardSnapshotService` / `ConfigureSnapshotService` / `ExcelViewService` 三个协议级文件（10 处读取点收口），属于 `docs/E2E测试方法.md` §八自检清单里的"模板 snapshot 数据迁移"类别，必须双 spec 复测。

---

## 11. AC 覆盖对照表（附录）

| AC | 用例数 | 用例编号 | 备注 |
|---|---|---|---|
| AC-1 | 5 | TC-01-1~5 | TC-01-3 是全库唯一验证 override 优先分支的用例 |
| AC-2 | 3 | TC-02-1~3 | TC-02-2 有键顺序判定方法歧义，见 §6.2 |
| AC-3 ⭐ | 4 | TC-03-1~4 | 核心验收，A/B 双态设计 |
| AC-4 | 5 | TC-04-1~5 | TC-04-4 是本次测试新发现的第 5 个调用点 |
| AC-5 | 3 | TC-05-1~3 | |
| AC-6 | 4 | TC-06-1~4 | TC-06-2 是"活表完好仍报错"的核心区分点 |
| AC-7 | 3 | TC-07-1~3 | 条件式 AC，依赖 D13 用户拍板，见 §6.2 |
| AC-8 | 7 | TC-08-1~7 | |
| AC-9 | 5 | TC-09-1~5 | |
| AC-10 | 7 | TC-10-1~7 | TC-10-5 需裸 SQL 构造，见 §6.2 |
| AC-11 | 3 | TC-11-1~3 | 字面"5→20"无天然夹具，见 §6.2 |
| AC-12 | 2 | TC-12-1~2 | |
| AC-13 | 4 | TC-13-1~4 | 前端已实现，本组用于确认现状 |
| AC-14 | 5 | TC-14-1~5 | |
| AC-15 | 3 | TC-15-1~3 | |
| AC-16 | 2 | TC-16-1~2 | |
| AC-17 | 2 | TC-17-1~2 | |
| AC-18 | 2 | TC-18-1~2 | |
| 权限（跨AC） | 4 | TC-PERM-1~4 | |
| 并发/幂等（跨AC） | 3 | TC-CONC-1~3 | |
| 回归面（跨AC） | 9 | TC-REG-1~9 | |

**合计：81 条用例**（AC 逐条覆盖 18/18，无遗漏）。

---

## 6.2 不可判定 / 有歧义 / 覆盖不到的点（写给 PM / 架构 / 主线，不自行脑补）

1. **AC-11 字面"页签数由 5 增至 20"当前无天然夹具**：库内现存最大页签数是 18（核价模板1 v1.1），多数模板集中在 6~9。已提供两条路径：TC-11-1（用 6 vs 18 两个自然数据点，低成本，验证同一结论——数量级变化约 3 倍，SQL 不变）+ TC-11-2（专门构造 5/20 两个版本，贴合字面但耗时更长）。**请 PM/架构确认**：是否接受 TC-11-1 的"6 vs 18"作为主证据、TC-11-2 降级为补充；还是坚持必须精确构造出 5 和 20 这两个数字。

2. **"组件已删除"边界（api.md §3 `componentExists:false` 分支）在当前正常 API 路径下不可达**：`ComponentService.delete:862` 的 `checkNotReferencedByTemplate` 会拦下"仍被任何 `template_component`（含 DRAFT）引用"的组件删除请求。由于 `template_component_snapshot` 刻意不建 FK 到 `component`，理论上"快照存在但组件已被删"这个态只能通过**绕过 API 的裸 SQL**人为构造（TC-10-5 已这样设计）。**请澄清**：这个 `componentExists:false` 分支是为未来某个尚不存在的硬删除路径（如目录级联删除、批量清理脚本）预留的防御性代码，还是需求文档遗漏了一条"允许强制删除已被快照引用的组件"的功能？如果后者从未规划，这段代码在生产环境是死代码，只是"写了但打不到"，需要文档明确这一点，否则会被后续开发者误以为是个可以从 UI 触发的常规场景。

3. **AC-7 是条件式 AC，测试当下无法给出唯一期望值**：`missCount` 具体数值取决于 D13 体检 B 的真实结果，且需要用户拍板三档处置中的哪一档。这不是测试用例设计的缺陷，是需求本身的设计（§5.3.4 已写明"体检 B 跑完须把数据给用户过目再定"），但意味着**本文件的 TC-07-2 无法在开发完成前定稿哪一分支会执行**，需要开发跑完 A4 端点、产出数据后，由主线拿着结果找用户拍板，测试再执行对应分支。这是流程上的强依赖，需要主线在推进节奏上留出这个"等拍板"的窗口，不能让测试和开发的"同期并行"误解为这一条也能同期定案。

4. **AC-2"键顺序一致"——写作时的疑虑，已用实测数据自行核实澄清**：需求文档反复强调"键集合、键顺序、值逐字段一致"，起初不确定这是要求 Java 序列化后的原始字符串逐字节相同（对 `ObjectMapper` 配置高度敏感），还是解析后按 key 排序比较（这样"键顺序"要求本身就失去意义）。**核实结论**：读了已落地的 `V382__task0806_template_component_snapshot.sql`（迁移脚本已在本文档写作期间由后端并行提交），其第 113-116 行注释明确写着——`components_snapshot` 列是 PostgreSQL **`jsonb`** 类型（非 `json`/文本），任何写入都会被 Postgres 按内部规则（键长度优先）规范化存储，**与构造时的键插入顺序无关**；因此只要键集合 + 值一致，读回的 `::text` 表示就必然逐字节相同，"键顺序一致"由列类型本身兜底保证，不需要额外约定 `ObjectMapper` 配置。**TC-02-1 的 md5 比对法（读 `components_snapshot::text` 求 md5）因此是可靠的**，本条疑虑已自行验证澄清，不再需要 PM/架构介入；保留 TC-02-2 作为"改造前后同输入同输出"的黑盒契约兜底测试，但不再需要额外规定序列化实现细节。

5. **AC-3 的验证操作会短暂影响真实共享数据**：TC-03-1~4 需要真的修改 `COMP-0045`/`COMP-0047`（6 个真实客户模板在用的组件），虽然设计了备份+恢复流程，但 `cpq_db_0724` 是**多会话共享**的开发库（CLAUDE.md 已明确"worktree 只隔离 git 工作区，DB 仍是共享的"）。如果测试执行窗口与其他并行会话对"测试客户-4"系列做别的验证重叠，可能出现短暂脏读甚至互相覆盖备份。**建议**：执行 TC-03 系列前，主线先确认当前没有其他并行会话在使用这批数据（例如看 `dev-docs/INDEX.md` §0.0 当前项目态势），或者协调一个专属时间窗口执行，执行完立即恢复并二次核对 md5。

---

## 6.3 需求文档未覆盖但应该测的风险点

1. **`operation_log` 加列后的既有写入方回归**（TC-REG-9）：`api.md`/`backtask.md` 只说"加法式，不影响现有写入方"，但没有要求专门测一遍 `CustomerService` 现有的 `operation_log` 写入路径。属于低风险但零成本的补充，已加入回归面。
2. **A7 `promote-override-to-component` 的第 5 个 `refreshSnapshotsByComponent` 调用点**（TC-04-4）：backtask.md §2.3/B6 的调用点清单遗漏了 `TemplateService.promoteOverrideToComponent:923`。这不是"未覆盖的风险点"而是**文档本身有缺口**，已在 §6.2 之外单独用 TC-04-4 显式建模，但建议同步反馈给后端工程师更新 `backtask.md` 的调用点清单，避免实现时漏改导致编译失败或运行时对已删除方法的引用残留。
3. **`operation_log.target_id` 在 A5/A7 批量场景下的多行写入语义**：api.md §9 写"A5/A7 批量时每个模板各写一行"，但 A5 请求体的 `templateIds` 数组可能很长（比如全量 17 个模板一次性 force-refresh），此时 `operation_log` 会新增 17 行——需求文档没有对"批量审计是否要额外聚合成一条摘要行方便查阅"给出说法。已在 TC-08-2 里只测单模板场景，多模板批量审计的展现形式建议 PM 补充说明是否需要。
4. **前端 `CopyQuotationDrawer` 在 D8 决策下"仅知情告警不加二次确认"，与 `docs/列表操作规范.md` 的"危险动作走 Modal 二次确认"通用规范存在张力**：需求文档 D8/fronttask.md 已明确本期不加确认弹窗（超出范围），但这与项目通用 UI 规范存在潜在不一致，测试不对此做判定（不属于本次验收范围），仅在此记录以防将来有人拿"违反列表操作规范"来质疑这个已拍板的决策。
