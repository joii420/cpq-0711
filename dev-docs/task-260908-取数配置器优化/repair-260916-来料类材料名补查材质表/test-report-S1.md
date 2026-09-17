# test-report · S-1（只读片）· repair-260916

> 执行者：test-engineer（S-1）｜执行时刻：2026-09-17T02:45Z（集合 A 取样 `2026-09-17T02:45:51.340Z`）
> 环境：验收后端 `http://localhost:8196`（worktree 提交 cc803299 副本，pid 1065514，`DB_NAME=cpq_db_260916`）· 验收库 `10.177.152.12:5432/cpq_db_260916`（flyway `444|t`）
> 命令（worktree `cpq-frontend` 下）：`npx playwright test -c e2e/repair260916-s1.config.ts` → **4 passed (14.5s)，exit=0**
> 用例：`cpq-frontend/e2e/repair260916-s1-readonly.spec.ts`（助手 `repair260916-s1-helpers.ts`，配置 `repair260916-s1.config.ts`，无 globalSetup）
> 用例只从 `问题说明.md ⑥` 的 AC 原文派生；未读实现代码。

## 1. 结果总览

| 用例 | AC | 结果 | 证据（`证据/` 下） |
|---|---|---|---|
| T1.0 判据自检（离线） | — | ✅ PASS | 控制台输出（见 §3） |
| T1.2 | AC-11 | ✅ PASS | `AC-11-集合A.json` · `S-1/AC-11-全量重编译预览-8196-原始响应.json` · `S-1/AC-11-集合对照.json` · `S-1/AC-11-预览前后写入指纹.json` · `S-1/AC-11-compile-COMP-0002-8196.json` · `S-1/AC-11-compile-COMP-0003-8196.json` |
| T1.1 | AC-1 | ✅ PASS | `S-1/AC-1-查名连线-迁移后.json`（含 SQL、57 行原始结果、violations=[]） |
| T1.3 | AC-9 | ✅ PASS | `S-1/AC-9-全量重编译预览-8196-原始响应.json` · `S-1/AC-9-键集合对照.json` · `S-1/AC-9-预览前后写入指纹.json` |

执行顺序：T1.0 → T1.2（第一个访问后端，集合 A 先落盘）→ T1.1 → T1.3。失败项：无。

## 2. 实际值（逐字，取自运行输出）

### 验明正身（T1.2 首次访问后端时）
```
[login] role=SYSTEM_ADMIN forceChangePassword=false
[identity] pid=1065514 DB_NAME=cpq_db_260916 cmdline~db=undefined
[identity] api totalElements=196 db quotation=196
[identity] flyway 444 = [{"success":true}]
[identity] other connections on cpq_db_260916 = 5
```

### T1.2 · AC-11
前置条件（验收库仍处于「执行前」状态）：
```
[T1.2] COMPONENT_VIEW_RECOMPILE 审计行 = 0
[T1.2] 与 8196 启动前快照（2026-09-17T02:05:13Z）相比变化的视图：[]
```
集合 A（`recompileChanged=9`，`recompileViews=110`）：
```
A=["builder_3fda805a52da","builder_4602c64a0c38","builder_46f244df7ede","builder_4aa23fde7ac9","builder_999eaf0a9c07","builder_9cc11850f425","builder_b2b718fe930a","builder_c35c2bd590fe","builder_ead1c831ca36"]
```
集合 B（来自基线 `refresh-all-snapshots-预览-8081.json`）：`[]`（`recompileChanged=0`）

期望集合（按 SQL 现查，9 个）：
```
COMP-2414 builder_3fda805a52da QUOTE [MAT_NAME_LK,SELF_PROCESS_FEE] nullKeyCols=0
COMP-0005 builder_4602c64a0c38 QUOTE [INCOMING_OTHER_FEE,MAT_NAME_LK] nullKeyCols=0
COMP-0008 builder_46f244df7ede QUOTE [INCOMING_RECOVERY,MAT_NAME_LK] nullKeyCols=0
COMP-2413 builder_4aa23fde7ac9 QUOTE [INCOMING_FIXED_FEE,MAT_NAME_LK] nullKeyCols=0
COMP-2425 builder_999eaf0a9c07 QUOTE [MAT_NAME_LK,SELF_PROCESS_FEE] nullKeyCols=0
COMP-2296 builder_9cc11850f425 QUOTE [MAT_NAME_LK,SELF_PROCESS_FEE] nullKeyCols=0
COMP-2424 builder_b2b718fe930a QUOTE [MAT_NAME_LK,SELF_PROCESS_FEE] nullKeyCols=0
COMP-0004 builder_c35c2bd590fe QUOTE [INCOMING_FIXED_FEE,MAT_NAME_LK] nullKeyCols=0
COMP-2426 builder_ead1c831ca36 QUOTE [MAT_NAME_LK,SELF_PROCESS_FEE] nullKeyCols=0
```
对照结果：
```
A−B=[...同上 9 个...]    B−A=[]    多出=[]    缺少=[]
```
第二段，编译 SQL 对照：
```
COMP-0002 sql 长度 基线=1399 现在=1399 逐字相同=true；declaredColumns 相同=true
COMP-0003 sql 长度 基线=835 现在=835 逐字相同=true；declaredColumns 相同=true
```
预览前后写入指纹逐项相等：`component_sql_view_md5=723446b9…`、`component_md5=c028f9e7…`、`template_md5=03a3cda9…`、`template_component_snapshot_md5=04ba5584…`、`semantic_edge_md5=fd2b8f66…`、`operation_log_rows=170`、`operation_log_max_created=2026-09-11 16:24:22.633708+00`。

### T1.1 · AC-1
- 迁移前 46 条 → 迁移后 **57 条**（= 46 + 11）
- 连接键 60 → **71**（= 60 + 11）
- `violations=[]`

11 个数据源的现状：
```
COST_BASIC/INCOMING_OTHER_FEE -> MAT_NAME_LK@COST_BASIC grp=PART_NAME ord=1 keys=0:incoming_material_no=production_no
COST_BASIC/INCOMING_OTHER_FEE -> RECIPE_NAME_LK@COST_BASIC grp=PART_NAME ord=2 keys=0:incoming_material_no=code
COST_BASIC/INCOMING_OTHER_FIXED_FEE -> MAT_NAME_LK@COST_BASIC grp=PART_NAME ord=1 keys=0:incoming_material_no=production_no
COST_BASIC/INCOMING_OTHER_FIXED_FEE -> RECIPE_NAME_LK@COST_BASIC grp=PART_NAME ord=2 keys=0:incoming_material_no=code
COST_BASIC/INCOMING_PROCESS_FEE -> MAT_NAME_LK@COST_BASIC grp=PART_NAME ord=1 keys=0:incoming_material_no=production_no
COST_BASIC/INCOMING_PROCESS_FEE -> RECIPE_NAME_LK@COST_BASIC grp=PART_NAME ord=2 keys=0:incoming_material_no=code
COST_DETAIL/INCOMING_OTHER_FEE -> MAT_NAME_LK@COST_DETAIL grp=PART_NAME ord=1 keys=0:incoming_material_no=production_no
COST_DETAIL/INCOMING_OTHER_FEE -> RECIPE_NAME_LK@COST_DETAIL grp=PART_NAME ord=2 keys=0:incoming_material_no=code
COST_DETAIL/INCOMING_OTHER_FIXED_FEE -> MAT_NAME_LK@COST_DETAIL grp=PART_NAME ord=1 keys=0:incoming_material_no=production_no
COST_DETAIL/INCOMING_OTHER_FIXED_FEE -> RECIPE_NAME_LK@COST_DETAIL grp=PART_NAME ord=2 keys=0:incoming_material_no=code
COST_DETAIL/INCOMING_PROCESS_FEE -> MAT_NAME_LK@COST_DETAIL grp=PART_NAME ord=1 keys=0:incoming_material_no=production_no
COST_DETAIL/INCOMING_PROCESS_FEE -> RECIPE_NAME_LK@COST_DETAIL grp=PART_NAME ord=2 keys=0:incoming_material_no=code
QUOTE/INCOMING_ANNUAL -> MAT_NAME_LK@QUOTE grp=PART_NAME ord=1 keys=0:input_material_no=material_no;1:customer_no=customer_no
QUOTE/INCOMING_ANNUAL -> RECIPE_NAME_LK@QUOTE grp=PART_NAME ord=2 keys=0:input_material_no=code
QUOTE/INCOMING_FIXED_FEE -> MAT_NAME_LK@QUOTE grp=PART_NAME ord=1 keys=0:input_material_no=material_no;1:customer_no=customer_no
QUOTE/INCOMING_FIXED_FEE -> RECIPE_NAME_LK@QUOTE grp=PART_NAME ord=2 keys=0:input_material_no=code
QUOTE/INCOMING_OTHER_FEE -> MAT_NAME_LK@QUOTE grp=PART_NAME ord=1 keys=0:input_material_no=material_no;1:customer_no=customer_no
QUOTE/INCOMING_OTHER_FEE -> RECIPE_NAME_LK@QUOTE grp=PART_NAME ord=2 keys=0:input_material_no=code
QUOTE/INCOMING_RECOVERY -> MAT_NAME_LK@QUOTE grp=PART_NAME ord=1 keys=0:input_material_no=material_no;1:customer_no=customer_no
QUOTE/INCOMING_RECOVERY -> RECIPE_NAME_LK@QUOTE grp=PART_NAME ord=2 keys=0:input_material_no=code
QUOTE/SELF_PROCESS_FEE -> MAT_NAME_LK@QUOTE grp=PART_NAME ord=1 keys=0:input_material_no=material_no;1:customer_no=customer_no
QUOTE/SELF_PROCESS_FEE -> RECIPE_NAME_LK@QUOTE grp=PART_NAME ord=2 keys=0:input_material_no=code
```
判据还逐条核对了以下几项，均无违反：
- 物料连线的 edge_id 与键和基线逐字相同；
- 材质连线是新增的 edge_id；
- 新增连线恰为这 11 条；
- 其余 35 条的 `dialect/from/to/coalesce_group/fallback_order/status/edge_keys` 逐条不变。

### T1.3 · AC-9
```
[T1.3] 键路径 基线=23 现在=23 仅基线有=[] 仅现在有=[]
[T1.3] recompileViews=110 库内 builder 视图=110
```
- HTTP 200，`preview=true`。
- 预览前后写入指纹逐项相等（数值同 T1.2）。

非列表字段的实际值：`affectedTemplateCount=43, affectedQuotationCount=158, recompile=true, recompileViews=110, recompileChanged=9, snapshotTemplates=43, snapshotEntries=199, snapshotEntriesStale=65, axisScopeToWrite=0`。

## 3. 证伪实验

| 判据 | 在已知答案上的结果 |
|---|---|
| AC-1 | 同一判据跑在改动前基线 CSV 上报 **14 条违反**（如 `QUOTE/INCOMING_FIXED_FEE: 查名连线应恰 2 条，实为 1`），能区分「修了」与「没修」。按 AC 原文构造的「已修」样本得 0 条违反，说明判据不是恒红。以下 4 种破坏均被抓到：材质连线多带客户键、物料连线键被改、其余 35 条之一被归组、范围外多一条连线（T1.0） |
| AC-11 | 改动前 A=B=∅，`A−B=∅≠期望 9 个`，判据会失败。实际 A 从 ∅ 变为 9 个，且与 SQL 现查的期望集合逐一吻合，说明观察对象确实变化了。**「B−A 为空」因 B=∅ 恒成立，按主线裁决只作防回归，不计分** |
| AC-9 键集合 | 比较器在 T1.0 中能抓到「删除 `data.recompileViews`」和「新增 `data.affectedTemplates[].newKey`」 |
| 验明正身 | 反向对照：共享 8081 的进程既无 `DB_NAME` 环境变量、命令行里也无库名，判据会失败。本次 8196 取到 `DB_NAME=cpq_db_260916` |
| 零写入指纹 | 本片只证明了指纹是确定性的（连取两次相同），**尚未证明能抓到写入**。阳性对照要依赖 S-全局 的 E-2：AC-7 执行后指纹必须变化。在 E-2 出结果之前，AC-9/AC-11 的「零写入」只算有条件成立 |

## 4. 未验证 / 不在本片

- AC-9「`BuilderRecompileService` 既有公开方法签名不变」：需要读实现，本片未验证。主线已核对 diff，结论见主线消息。
- 零写入观察手段的阳性对照：见 §3，待 S-全局 E-2。

## 5. 观察（不影响判定）

- `snapshotEntriesStale` 在基线 8081 上是 33，在 8196 上是 65。AC-9 只要求键集合不变，这个值不在断言范围内。数值变化与「9 个视图将被重编译」同时出现，推测相关，**未验证**。

## 6. 本片实际写入

- **数据库**：无。所有 SQL 都在 `default_transaction_read_only=on` 下执行。HTTP 只调用了登录、`refresh-all-snapshots {recompile:true, confirm:false}` ×2、`/builder/compile` ×2。
- 跑完后复核：
  - `COMPONENT_VIEW_RECOMPILE` 审计行 = 0；
  - `component_sql_view` 的「各视图 md5(sql_template)+updated_at」聚合指纹为 `d2cea41634d7a8e1bb6cbafc2cb086a1`，与 8196 启动前快照算出的值相同。
- **文件**：`证据/AC-11-集合A.json`、`证据/S-1/*`（9 个文件）、`夹具/s1/验收库-8196启动前-视图快照.json`（阶段 1 取的只读快照）。
- **一次性库**：本片未建库，待回收清单为「无」。验收库 `cpq_db_260916` 由主线管理。
