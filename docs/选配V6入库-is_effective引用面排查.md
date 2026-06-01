# is_effective 引用面排查（P1 Task 1）
> 日期：2026-06-01 | 目的：确认**新增** is_current 与核价现有 is_effective 并存、不冲突（不改 is_effective）
> 分支：feat/selopt-v6-ingestion | 范围：仅调查，不改任何代码

## 背景
拟给所有「带版本的 V6 基础表」**新增** `is_current` 生效标志（选配/报价侧用于判定当前生效版本）。其中 `capacity` / `tooling_cost` / `material_version_mgmt` 三张表**已有** `is_effective` 列且被核价系统使用。本任务摸清 `is_effective` 引用面，确认新增 `is_current`（仅 ADD COLUMN ... DEFAULT TRUE）不会与核价现有逻辑冲突。

## 全工程命中（grep `is_effective` / `isEffective`）
- SQL 迁移：V218、V220、V255
- Java：6 个文件（3 个 entity + 4 个导入 handler）
- 前端 `cpq-frontend/src`：**0 命中**（前端不直接引用 is_effective）

## 逐条排查清单

| # | 文件:行 | 表 | 用途(核价/报价/导入/视图) | 新增 is_current 后是否影响此处 | 结论 |
|---|---------|----|--------------------------|------------------------------|------|
| 1 | `db/migration/V218__create_v6_master_data_tables.sql:84` | material_version_mgmt | 建表 DDL，定义 `is_effective BOOLEAN NOT NULL DEFAULT TRUE`（核价版本绑定列） | 不影响（仅新增另一列，不动此定义） | 保持不动 |
| 2 | `db/migration/V218...:96` | material_version_mgmt | 查询索引 `idx_material_version_mgmt_lookup(material_no, customer_no, is_effective)`（核价 lookup） | 不影响（is_current 若需可后续单独建索引） | 保持不动 |
| 3 | `db/migration/V220__create_v6_pricing_resource_tables.sql:102` | capacity | 建表 DDL，`is_effective BOOLEAN`（无 NOT NULL/DEFAULT，可空） | 不影响（仅新增列） | 保持不动 |
| 4 | `db/migration/V220...:331` | tooling_cost | 建表 DDL，`is_effective BOOLEAN`（可空） | 不影响（仅新增列） | 保持不动 |
| 5 | **`db/migration/V255__v12_create_component_sql_views.sql:287`** | **tooling_cost** | **视图渲染**：`component_sql_view` 模板 `v12_tooling_cost`（component code `COMP-V5-TOOLING-V12`），`WHERE COALESCE(tc.is_effective, true) = true` | **不影响**（继续按 is_effective 过滤；is_current 不参与此视图） | 保持不动；**见下方冲突分析** |
| 6 | `entity/ToolingCost.java:56-57` | tooling_cost | ORM 字段映射 `isEffective`（核价/导入读写） | 不影响（新增 is_current 需另加字段，不动此处） | 保持不动 |
| 7 | `entity/Capacity.java:72-73` | capacity | ORM 字段映射 `isEffective` | 不影响 | 保持不动 |
| 8 | `entity/MaterialVersionMgmt.java:47-48` | material_version_mgmt | ORM 字段映射 `isEffective`（默认 TRUE） | 不影响 | 保持不动 |
| 9 | `pricing/P12ToolingCostHandler.java:42,51` | tooling_cost | **导入**：Excel 导入 upsert，INSERT 列含 is_effective，`ON CONFLICT DO UPDATE SET is_effective = COALESCE(EXCLUDED..., tooling_cost...)` | 不影响（is_current 由迁移 DEFAULT TRUE 兜底，导入暂不写它） | 保持不动 |
| 10 | `pricing/P04PricingVersionHandler.java:36,41,48,57` | material_version_mgmt | **导入**：读 Excel「是否生效」→ is_effective，upsert（null 兜底 TRUE） | 不影响 | 保持不动 |
| 11 | `pricing/P08CapacityHandler.java:39,45,48,53` | capacity | **导入**：读 Excel「是否有效」→ is_effective，upsert（null 兜底 TRUE） | 不影响 | 保持不动 |
| 12 | `quote/Q14AssemblyProcessFeeHandler.java:47` | capacity | **导入（报价侧组装费）**：INSERT capacity 列含 is_effective，写死 `true` | 不影响（is_current DEFAULT TRUE 自动落） | 保持不动 |

## 同表双标志冲突分析（重点）

- **唯一的视图（渲染）级读取**：`v12_tooling_cost`（V255:287），仅读 `tooling_cost`，过滤 `COALESCE(tc.is_effective, true) = true`。
  - `capacity` / `material_version_mgmt` 在 V255 及所有迁移中**没有任何渲染视图按 is_effective 过滤**，这两张表的 is_effective 仅出现在「建表 DDL / lookup 索引 / ORM 字段 / 导入 upsert」，不进 driver 视图。
- **tooling_cost 是潜在「同一张表被两套生效标志读写」的表**：
  - 核价/渲染侧：`v12_tooling_cost` 视图按 `is_effective` 过滤。
  - 选配/报价侧将来：拟按 `is_current` 判定生效。
  - → 若后续 P2/P3 要让选配也复用这张表/或改造该 driver 视图，需明确：`v12_tooling_cost` 视图的过滤条件是继续只用 `is_effective`，还是叠加 `is_current`。两标志语义需在写入计划里对齐（避免「核价认为生效、选配认为失效」或反之）。
- `capacity`、`material_version_mgmt` 暂无同表双标志读写冲突（无视图按 is_effective 过滤），但同样存在两套标志列并存，建议在 P2/P3 写入计划里统一语义口径。

## 结论
- **is_effective 现有读写：保持原样，本期不改。** 全部 12 处命中均无需改动，新增 `is_current` 走纯 ADD COLUMN ... DEFAULT TRUE，不触碰现有列定义、索引、ORM 字段、导入 upsert、视图过滤。
- **核价侧是否受新增 is_current 影响：否。** is_current 仅新增列且 DEFAULT TRUE，不改任何现有查询/视图/索引；核价主线（含 `v12_tooling_cost` 渲染视图）继续仅读 is_effective。
- **选配/报价侧将来用 is_current 判定生效，是否存在「同一张表被两套生效标志读写」的冲突：是（潜在，限 tooling_cost）。**
  - 涉及表：`tooling_cost`
  - 涉及视图：`v12_tooling_cost`（V255__v12_create_component_sql_views.sql，component code `COMP-V5-TOOLING-V12`）
  - 处理建议（移交 P2/P3 写入计划）：明确 `v12_tooling_cost` 视图过滤策略是否需叠加 is_current，并统一 is_effective（核价）与 is_current（选配）两标志的语义与写入责任，避免双标志相互打架。
  - `capacity` / `material_version_mgmt` 两表无视图级过滤，本期无冲突，但同存两标志，建议 P2/P3 一并对齐语义口径。
