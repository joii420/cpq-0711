# 行键配置改回"字段勾选"+ BNF 反查真实列名 — 设计方案

- 日期: 2026-06-02
- 状态: 设计已确认，待实现计划
- 关联反模式: AP-44（字段类型联动）、AP-54（行键对齐错位）、AP-37（前后端协议重复）、AP-53（V6 表/$视图引用规则）
- 关联迁移: V278（行键列引入）、V279（命名空间修正存量预填）、5192b9e（行键改顶部输入框）

## 1. 背景与问题

### 1.1 行键（`rowKeyFields`）的真实职责
行键用于**报价单草稿态重刷（snapshot 重算）时，按"行身份"把新算出来的行与用户之前编辑过的行对齐**，从而保留用户编辑值，防止 driver 行重排导致编辑错位（AP-54）。它解决的是"多行数据纵向哪一行对哪一行"，不是"值落到哪一列"。

行键计算口径：对每行 `driverRow` 这个 `Map<String,Object>`，按 `rowKeyFields` 列出的 key 取值 `||` 拼接成行身份。因此 **`rowKeyFields` 里的每一项必须是 `driverRow` 真实存在的 key**。

### 1.2 `driverRow` 的列名来源（已核实）
`driverRow` 的 key = 组件 driver 路径执行的 SQL 结果列名/别名：
- `DataLoader.executeQuery` → `row.put(meta.getColumnName(c), rs.getObject(c))`（`DataLoader.java:301`）
- `SqlViewExecutor`（V6 `$视图` 路径）→ `row.put(meta.getColumnLabel(c), rs.getObject(c))`（`SqlViewExecutor.java:308 / 375`）
- 执行 SQL 形态：`SELECT * FROM (component_sql_view.sql_template) inner_q [WHERE 谓词]`

→ **列名最终由 `component_sql_view.sql_template` 的 SELECT 投影/别名决定**。别名写中文（如 `up.code 子件`）则列名就是中文；写英文则是英文。**不是数据库不能用中文，而是"以 SQL 实际产出的列名为准"**。

### 1.3 历史 bug 与现状
- **V278 初版**：在 `FieldConfigTable` 行内放"行键"勾选框，勾的是**字段显示名**。但字段显示名 ≠ driver 列名（不同命名空间）→ `driverRow.get("材质代码")` 恒 null → 行键全空 → 草稿重刷编辑值张冠李戴（AP-54）。
- **V279**：把存量预填改为真实 driver 列名。注释明确"命名空间因组件而异"：
  - 材质 → `["child_hf_part_no","material_code"]`
  - 选配-元素含量 → `["child_hf_part_no","element_name"]`
  - 选配-工序列表 → `["child_hf_part_no","process_code"]`
  - 选配-组合工艺 → `["process_code"]`
  - "工序"（5c47fb41，driverRow 用中文 key `子件`/`工序代码`）→ 保持不变
- **5192b9e**：删掉勾选框，改为组件级顶部手输框 + 提示"填底层英文列名，不是中文显示名"。

### 1.4 现状的两个问题
1. **手输框反人性**：用户要手敲底层列名，易错。
2. **提示文案过度概括且误导**："填底层英文列名"只对"视图产出英文列"的组件成立；对中文别名视图（如"工序"组件、用户自建视图）是错的，正确值就是中文列名。

## 2. 目标

把行键配置改回**字段后勾选**（直观），同时根除"显示名 ≠ 列名"的命名空间错配：**勾选的是字段，存储的是该字段 BNF 路径反查出的 driver 真实列名**。

## 3. 方案设计

### 3.1 交互（前端 `FieldConfigTable.tsx` + `ComponentManagement.tsx`）
- 在字段配置表恢复一列 **"行键"勾选框**（位置：小计列后、备注列前，与历史一致）。
- **删除** `ComponentManagement.tsx` 顶部组件级手输框整块（现 L654-697）及其误导文案。
- 勾选框可用性遵循本项目"列表操作规范"——**置灰 + hover 原因，不隐藏**：
  | 字段情况 | 勾选框 | hover 提示 |
  |---|---|---|
  | 能反查出 driver 真实列 | 可勾 | `行键列：子件` |
  | 无 `basic_data_path`（INPUT/FORMULA 等） | 置灰 | `该字段无 driver 列，不能作行键` |
  | BASIC_DATA 但叶子列不在 driver 视图列内（跨表 lookup） | 置灰 | `该字段不取自 driver 行，不能作行键` |
- 回显：打开组件时，把已存的 `rowKeyFields`（列名）反向匹配到"反查列名 == 该列名"的字段，对应勾选框打勾。

### 3.2 "反查真实列名" 解析逻辑
- 对字段的 `basic_data_path` 调 `extractLeafField()`（已存在，`ComponentDriverService.java:903`）取末段叶子列名。
  - 支持形态：`{table[pred].field}` → `field`；`table.field` → `field`；`table[pred1].sub[pred2].field` → `field`。
- 用 driver 视图的 `declaredColumns`（保存 SQL 视图时 dry-run 已解析并存入 `component_sql_view.declared_columns`）做交叉校验：
  - `eligible = (leaf != null) && declaredColumns 含 leaf`
  - 反查得到的 `leaf` 即写入 `rowKeyFields` 的真实列名。
- driver 视图定位：从 `data_driver_path` 用 `extractSqlViewName()`（已存在）取 `$<viewName>`，查 `ComponentSqlView.declaredColumns`。

### 3.3 解析放后端（避免 AP-37 协议重复）
新增**无状态**端点：

```
POST /api/cpq/components/row-key-candidates
入参: { dataDriverPath: string, fields: Field[] }   // 用前端当前编辑态，支持未保存
出参: { candidates: [ { fieldName, displayName, resolvedColumn, eligible, reason } ] }
```

- 后端用既有 `extractLeafField` + `ComponentSqlView.declaredColumns` 计算，**前端不复制解析逻辑**。
- 前端在打开字段配置、或 `dataDriverPath`/`fields` 变化时调用，据返回渲染勾选框可用性 + 回显。
- 入参用编辑态 `fields` 而非已存库值，保证未保存编辑（如刚改了某字段的 `basic_data_path`）也能正确反查。

### 3.4 存储与后端对齐逻辑
- `rowKeyFields` 仍存**真实列名数组**（JSON），后端草稿重刷对齐 `driverRow.get(key)` **完全不改**。
- `ComponentService.validateRowKeyConfig` 顺手收紧：每个 key 应 ∈ driver `declaredColumns`（**软告警 warn，不硬拦**；保留 `["__seq_no__"]` 哨兵显式豁免；driver 非 `$视图`、无 declaredColumns 时跳过校验）。

### 3.5 存量数据迁移（Flyway，上线前）
- 问题：纯勾选后，行键含**无对应字段的锚定列**（如 `child_hf_part_no`）的组件，在新 UI 里没有勾选项能代表该列 → 保存即静默丢失 → AP-54 重现。
- 决策（已确认）：**给这些组件补隐藏 BASIC_DATA 字段**（叶子 = 该锚定列），使其在新 UI 可勾、不丢行键。
- 待数据库可用时实测：对每个 `row_key_fields IS NOT NULL` 的组件，求 `row_key_fields` 列名集合 与 `fields[*].basic_data_path` 反查列名集合 的差集；差集非空者即需补隐藏字段。预期命中：材质 / 选配-元素含量 / 选配-工序列表（含 `child_hf_part_no`）。
- 迁移以 Flyway `V284+` 落地（更新 `component.fields` 追加隐藏字段；不动 `row_key_fields`）。

## 4. 受影响文件清单（预估）

前端：
- `cpq-frontend/src/pages/component/FieldConfigTable.tsx` — 恢复"行键"勾选列 + 置灰逻辑
- `cpq-frontend/src/pages/component/ComponentManagement.tsx` — 删顶部手输框；接 row-key-candidates；回显
- `cpq-frontend/src/pages/component/types.ts` — 如需候选 DTO 类型
- `cpq-frontend/src/services/componentService.ts` — 新端点封装

后端：
- `cpq-backend/.../component/resource/ComponentResource.java`（或新 Resource）— `POST row-key-candidates`
- `cpq-backend/.../component/service/ComponentDriverService.java` — 暴露/复用 `extractLeafField` + 候选计算
- `cpq-backend/.../component/service/ComponentService.java` — `validateRowKeyConfig` 收紧（软告警）
- 新 DTO：`RowKeyCandidatesRequest` / `RowKeyCandidatesResponse`
- 迁移：`db/migration/V284+__add_hidden_anchor_fields_for_rowkey.sql`

## 5. 测试与自检（CLAUDE.md 强制）

- `FieldConfigTable.tsx` 在 E2E 强制清单上 → 跑 `quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE），断言 `'加载中' final count = 0`、8 Tab 渲染正常。
- 前端：`tsc --noEmit` 0 错；改动的 `.tsx` 经 Vite transform HTTP 200。
- 后端：新端点 `curl` 期望 200/401（非 500）；Flyway `V284` `success=t`；schema 改动后 `touch` java 触发 Quarkus 重启。
- 功能验收：
  1. "工序"组件（中文列）→ 勾"子件""工序代码"→ 存储 `["子件","工序代码"]`（与现状一致，不回归）。
  2. 用户自建中文别名视图 → 勾选后存中文列名，草稿重刷对齐保编辑。
  3. INPUT/FORMULA 字段勾选框置灰且 hover 有原因。
  4. 补过隐藏字段的材质/元素组件 → `child_hf_part_no` 可勾，保存后 `row_key_fields` 不丢锚定列。
  5. `POST refresh-template-snapshots` 后各 Tab snapshot 行键对齐正确。

## 6. 非目标（YAGNI）

- 不引入下拉多选 / 自动补全 / 可点 tag（已评估否决）。
- 不保留顶部手输框作为兜底（纯勾选；锚定列靠迁移补隐藏字段解决）。
- 不改行键计算与草稿重刷对齐算法本身（仅改"列名从哪来"的配置入口）。
- 不改 `rowKeyFields` 的存储格式（仍为真实列名 JSON 数组）。

## 7. 风险与对策

- **同名列短路歧义**：`extractLeafField` 短路假设 driver 与 target 同名列同义（`ComponentDriverService.java:871` 注释）。本方案用 driver `declaredColumns` 交叉校验，只承认 driver 视图确有的列，风险可控。
- **未保存编辑态不一致**：候选端点入参用前端编辑态 `fields`，避免读库旧值导致回显/可用性错位。
- **存量漏迁移**：迁移前必须用差集脚本盘点全部 `row_key_fields IS NOT NULL` 组件，逐一确认，避免遗漏某组件的锚定列。
- **driver 非 `$视图`**：无 `declaredColumns` 时，候选端点对该组件所有字段返回"无法校验"——此类组件按 AP-53 本就应迁移为 `$视图`；过渡期置灰并提示"请先将 driver 配为 SQL 视图"。
