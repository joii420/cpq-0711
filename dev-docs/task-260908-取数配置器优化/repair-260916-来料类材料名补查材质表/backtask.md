# backtask · repair-260916 后端任务分解

> **只按本文件做。** 验收标准以 `问题说明.md ⑥` 的 AC **原文**为准；本文与 AC 原文有出入，以 AC 原文为准并报主线。
> 接口契约唯一出处：`api.md`。
> 必读：`问题说明.md`（全文）· `api.md` · `docs/rules/backend.md` · `docs/方案制定前必读.md`（改动 5）· 迁移样板 `cpq-backend/src/main/resources/db/migration/V430__task260908_lookup_name_nodes_and_edges.sql`

## 0. 任务总表

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| B-1 | AC-1, AC-2, AC-3, AC-4, AC-5, AC-10, AC-11, AC-12 | 数据迁移 `V444`：11 个数据源的材料名改成「物料表 → 材质表」两段查 |
| B-2 | AC-6, AC-7, AC-8, AC-9, AC-10, AC-13 | `BuilderRecompileService`：新增「按组件」预览 / 执行，与全量重编译共用同一段编译与写入逻辑 |
| B-3 | AC-6, AC-7, AC-8, AC-10, AC-13 | `ConfigCenterResource`：新增 `POST /recompile-components` 端点（入参校验、鉴权、响应形状） |
| B-4 | AC-7, AC-8, AC-13 | 审计：每个文本有变化的视图批量写 1 行 `operation_log` |
| B-5 | AC-12 | 内网部署脚本 `deploy/db/update-260916-lookup-name-recipe-fallback.sql` + README §① + 临时库验证 |
| B-6 | AC-6, AC-7, AC-8, AC-9 | 后端自测（开发者单测/集成测，自造前缀数据）+ N+1 例外声明 + 自检声明 |

---

## B-1 · 迁移 `V444`

**文件**：`cpq-backend/src/main/resources/db/migration/V444__repair260916_lookup_name_recipe_fallback.sql`
⚠️ 版本号是**移动靶**：动笔前再查一次 `ls db/migration | sort -V | tail`、全部分支与 `cpq_db_test` 的 `flyway_schema_history` 最大值；若 `444` 已被占用，**停下报主线**，不要自行顺延。

**要做的事**（11 个数据源，见 AC-1 表）：

| 方言 | 数据源 node_key | 锚点料号列 |
|---|---|---|
| QUOTE | `INCOMING_FIXED_FEE` / `INCOMING_OTHER_FEE` / `INCOMING_RECOVERY` / `INCOMING_ANNUAL` / `SELF_PROCESS_FEE` | `input_material_no` |
| COST_BASIC | `INCOMING_OTHER_FEE` / `INCOMING_OTHER_FIXED_FEE` / `INCOMING_PROCESS_FEE` | `incoming_material_no` |
| COST_DETAIL | `INCOMING_OTHER_FEE` / `INCOMING_OTHER_FIXED_FEE` / `INCOMING_PROCESS_FEE` | `incoming_material_no` |

1. **既有物料表查名连线**（`from = 数据源`、`to = 本方言 MAT_NAME_LK`、`edge_kind='LOOKUP'`、`status='ACTIVE'`）：`coalesce_group='PART_NAME'`、`fallback_order=1`，同时刷新 `updated_at`。
   - 🔑 **按 `(dialect, from node_key, to node_key)` 定位，不按 V430 的确定性 id 定位** —— 语义图有管理界面（`operation_log` 里有 `SEMANTIC_EDGE_UPDATE/CREATE` 记录），不能假设 id 从未变过。
   - 连接键**一条都不改**。
2. **新增材质表查名连线**（`to = 本方言 RECIPE_NAME_LK`）：`edge_kind='LOOKUP'`、`cardinality='MANY_TO_ONE'`、`coalesce_group='PART_NAME'`、`fallback_order=2`、`assert_status='NA'`、`status='ACTIVE'`、`note` 注明来源任务。
   连接键**只有一条** `seq=0`：`left_column = <锚点料号列>`、`right_column = 'code'`。🚫 **不加客户键**（材质表无客户维度）。
3. **幂等**：id 用确定性常量（新前缀，如连线 `26091601-0000-4000-8000-<序号>`、连接键 `26091602-…`），全部 `ON CONFLICT DO NOTHING`；第 1 步的 `UPDATE` 天然幂等。样板照 `V430`：用 `ON COMMIT DROP` 的临时规格表做单一事实来源，边与键都从它派生。
4. **迁移内自检**（失败即 `RAISE EXCEPTION`，让错误在启动时炸）：
   - 规格表恰好 11 行、方言分布 `COST_BASIC=3 COST_DETAIL=3 QUOTE=5`；
   - 11 个数据源**每个恰好 2 条** ACTIVE、`coalesce_group='PART_NAME'` 的 LOOKUP 连线，`fallback_order` 恰为 `{1,2}`，分别指向 `MAT_NAME_LK` / `RECIPE_NAME_LK`；
   - 新增的 11 条材质表连线各恰有 1 条连接键，且 `right_column='code'`。
   - 🚫 **不要写死全局总数**（如「共 57 条」）—— 管理界面可能改过别的连线，写死会让迁移在别的库上无故失败。
5. 🚫 **禁止**：改 `V430`；改 `semantic_node` / `semantic_node_column`；`gen_random_uuid()`；任何 `DELETE`；手工 `psql -f` 执行本迁移（`backend.md §2`）。
6. ⚠️ 已知约束：部分唯一索引 `uq_edge_fallback (from_node_id, coalesce_group, fallback_order) WHERE … ACTIVE` —— 这 11 个数据源此前无任何分组连线，不会冲突；若实测冲突，**停下报主线**。
7. **生效确认**：迁移在启动时执行，语义图由 `SemanticGraphLoader` 缓存。**必须实证**「重启后编译器读到的是新图」（例如在 worktree 临时后端上 `POST …/builder/compile` 看到 `material_recipe`），并在回报里写出证据；若发现加载早于迁移，停下报主线。

---

## B-2 · `BuilderRecompileService` 增加「按组件」能力

**语义见 `api.md §1.3`。要点**：

1. 新增两个公开方法（名称可自定，回报里写明）：
   - 预览：只编译、只比对，**零写入**，🚫 不加 `@Transactional`（与既有 `previewRecompile` 同理）；
   - 执行：`@Transactional`，**必须**由外部 bean（`ConfigCenterResource`）经注入代理调用（CDI 自调用会静默跳过拦截器，见既有 `recompileAndRealign` 注释）。
2. **与全量重编译共用逻辑**：把既有 `runRecompile` 循环体里「读 `builder_config` → 编译 → 比对 → 写入（`ComponentSqlViewService.update`）→ 对齐 `builder_version` / `axisScope`」抽成一个按「视图列表」工作的私有方法，全量路径与按组件路径都调它。
   - 🔑 全量路径（`previewRecompile` / `recompileAndRealign`）的**外部可观测行为逐位不变**（AC-9）：同样的入参返回同样的键、同样的计数、同样的错误码。
   - 🚫 不许为按组件路径另写一份编译/写入代码（`api.md §1.3` 第 6 条：两条路径对同一视图的产物必须逐字相同）。
3. 视图查询：按组件 id 列表**一次**查出取数配置器视图，判定口径与 `ComponentSqlViewRepository.listBuilderManaged()` 相同（建议在 repository 加一个按 `componentId IN (…)` 的同口径方法）。
4. **不做**：不调 `templateService.forceRealignSnapshots*`、不调 `realignSqlViewsSnapshots`、不写 `component` 表（`api.md §1.3` 第 5 条）。
5. 预览需返回每个变化视图的旧/新 SQL 与组件编码/名称（`api.md §1.4`）—— 组件信息**一次批量查询**取得，不许在循环里逐个查。

## B-3 · `ConfigCenterResource` 新端点

1. `@POST @Path("/recompile-components") @RoleAllowed({"SYSTEM_ADMIN"})`。
2. 入参校验顺序：缺失/空 → 400 `COMPONENT_IDS_REQUIRED`；非法 UUID → 400 `INVALID_COMPONENT_ID`（列出全部非法值）；去重；存在性 → 404 `COMPONENT_NOT_FOUND`（列出全部缺失 id）。错误一律抛 `BuilderApiException`（裸体格式，`api.md §1.7`）。
3. `confirm` 缺省 `false`。成功响应用 `ApiResponse.success(...)`，字段与顺序按 `api.md §1.4 / §1.5`。
4. 日志沿用 `[admin-backdoor]` 前缀，写清组件数 / 视图数 / 变化数。
5. 🚫 不动 `refresh-all-snapshots` 方法体。

## B-4 · 审计

- 每个**文本有变化**的视图 1 行，列取值见 `api.md §1.6`；与视图写入**同一事务**。
- 批量写入（一次构造全部行后统一持久化），🚫 不许循环里「写一行、查一次」。
- `operationLogIds` 与 `changedViewNames` 一一对应、同序。

## B-5 · 内网部署脚本

按 `deploy/db/README.md §②③⑥` 执行（**先通读该 README**）：

1. 生成 `deploy/db/update-260916-lookup-name-recipe-fallback.sql`：文件头（源迁移 `V444`、日期、一句话说明）· 幂等 · 末尾上调 flyway 基线到 `444` · 自检 SQL（期望值写在注释里）。
2. 满足 README §③ Navicat 七条（🚫 psql 元命令等）。
3. 更新 README §①「当前节点」与增量清单；§⑦ 变更记录追加一行。
4. **临时库验证**：新建一个一次性库（命名 `cpq_upd_verify_260916`），按 README 流程「全量脚本 + 全部增量按序执行（`ON_ERROR_STOP=1`）」→ **本增量再执行一次**（幂等）→ 跑自检 SQL → 与源库做六维比对。
   - 🚫 **跑完不许 `DROP` 该库**（§3.2 红线）—— 在回报里列入「待回收清单」（库名 / 创建时间 / 状态 / 回收命令），由主线随闸门 B 报用户批准。
   - 比对脚本不许吞 stderr，每一维打印基数（README §⑥）。

## B-6 · 自测与自检

1. 开发者自测放 `cpq-backend/src/test/java/com/cpq/repair260916/`，类名以 `Dev` 前缀区分（测试工程师的验收用例会放同包、不带该前缀，**互不修改**）。自造数据一律前缀 `R260916-B-`，`finally` 清理自己造的数据。
2. 至少覆盖：按组件预览零写入 · 执行只改所列组件视图 · 全量路径计数不变 · 404/400 零写入 · 第二次执行 `changed=0`。
3. **N+1**：循环体逐个检查；按 `api.md §1.8` 在代码处写 `// N+1 例外：…`（写明「一个视图 = 一个工作单元，条数随请求视图数线性增长，与业务数据量无关」）；回报里给出「视图数 1→3 时 SQL 条数」的实测。
4. 自检声明（`backend.md §2`）：真重启 · 业务端点 401 · `V444 success=t`（在 `cpq_db_test` 上）· N+1 声明。

---

## 1. 工作环境与禁区（派工 prompt 会重复，这里先写死）

| 项 | 规定 |
|---|---|
| 代码 | 只在 worktree 内读写/构建/测试，🚫 不 `cd` 回主工作区，🚫 不 `git commit`（主线统一提交） |
| 临时后端 | 端口 **8197**，`DB_NAME=cpq_db_test`。🚫 不占 `8081` / `8196`（后者留给测试与主线亲验） |
| 验收库 `cpq_db_260916` | 🚫 **不许连**。它存放 AC-6 / AC-7 / AC-11 的「执行前」状态，任何人在上面提前执行按组件重编译或全量重编译，都会让这三条 AC 的证据作废 |
| 共享开发库 `cpq_db_0724` | 🚫 不许连（`V444` 未合并前不能进共享库；否则其他会话的 `8081` 会遇到未知迁移） |
| 红线 | `DROP` / `TRUNCATE` / 无 WHERE 的 `DELETE` / `git reset --hard` / 改已应用迁移 → **停下报主线**，你没有批准权 |
| 契约问题 | 发现 `api.md` 不可实现或有歧义 → 停下报主线，不许自行改契约 |

## 2. 回报格式

逐项（B-1…B-6）写：做了什么（注明服务的 AC）· 证据（命令与原始输出）· 没做到 / **未验证**的 · 过程中规避掉的坑 · 待回收清单（B-5 临时库）。
