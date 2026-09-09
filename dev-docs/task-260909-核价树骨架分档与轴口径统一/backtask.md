# backtask · 后端任务分解

> 只按本文件做。AC 原文在 `需求文档.md §③`，**这里只标编号，不复制原文**。
> 契约以 `api.md` 为准；`api.md` 若在开工后变更，主线会显式通知，**不要自行推断**。
> 🚨 本任务属**协议级改动**（`change-protocol.md §1` 第 1/2/4 类），§0 的检查点清单**必须逐项勾掉**才算写完。

---

## §0 协议级改动检查点清单（写代码前先跑一遍，写代码中逐项勾）

**跑法**：`codegraph_impact` 优先；grep 一律用 `/usr/bin/grep -a`（本环境 `grep` 是 `ugrep -I` 别名，会把中文注释多的源文件静默判为二进制返空）。
**空结果 ≠ 不存在** —— 下「无引用」结论前，先拿一个已知存在的引用点反查验证工具本身有效。

| # | 检查点 | 关注什么 | 勾 |
|---|---|---|---|
| 1 | `CostingBomTreeConfigService.VALID_USAGES` / `normalizeUsage` | 值域白名单，漏了就是「存不进去」 | ☐ |
| 2 | `CostingBomTreeConfig.usage` 列与 `findActive(usage)` | 列 `varchar(16)`、**无 CHECK**；唯一索引 `UNIQUE(usage) WHERE is_active` ⇒ **零 DDL**。确认无第二处硬编码 `"COSTING"` | ☐ |
| 3 | `BomTreeRenderService.render(...)` 四参重载的 `usage` 实参 | 全部调用方（`CardSnapshotService` ×4、`CostingVersionService`、`PriceAdjustJobExecutionService`、`ConfigureSnapshotService`）今天传的是**字面量**，逐个确认改后语义 | ☐ |
| 4 | `collectTotalMaterialNoUnion(lineItems, usage)` | 它也按 usage 取配置，**必须与 render 同源**，否则闭包与渲染口径分叉（`task-260907 B-7a` 的原教训） | ☐ |
| 5 | 前端类型 `BomTreeConfigUsage` | 跨端契约，见 `fronttask.md F-1` | ☐ |
| 6 | `SemanticCompiler` 的 NARROW 桥（`narrowEdge` / `applyFullScope` / `treeChildColumn`） | 停发桥后 `assertAxisParamSingleSemantic` 护栏的「结构化认出的桥 vs 产物里数出的桥」对账必须仍成立 | ☐ |
| 7 | **缓存 key 维度**（`AP-37` / `expandCache` / `DataLoader.resultCache`） | 轴语义变了但 key 不变 ⇒ **串号**。确认 `expandUncached` 这条路径确实绕开 `expandCache`（类注释称如此，**要实证**） | ☐ |
| 8 | **历史快照兼容**（`AP-39`） | 已 PUBLISHED 模板的 `components_snapshot` / `quotation_component_sql_snapshot` 是否冻了旧 `sql_template`。实测本单 `quotation_component_sql_snapshot` **0 行**（草稿不冻），但发布态模板要确认 | ☐ |
| 9 | 渲染分支三处（列表 / 编辑 / 详情） | `ReadonlyProductCard` 与 `QuotationStep2` 是 `AP-50` 同族，行归属键改动两处都要看到效果 | ☐ |
| 10 | `CostingTreeSqlValidator.REQUIRED_COLS` | 新骨架 SQL 必须过校验（5 列 + `:production_part_nos`；`:customerCode` 由校验器加桩） | ☐ |
| 11 | 导出分支（Excel / PDF） | 核价 Excel 视图是否复用同一份 baseRows；行数变了导出会不会跟着变 | ☐ |
| 12 | `TemplateService.publish` 的返回体 | 新增 `warnings` 是**加法式**扩展，不得改动既有字段（`api.md §3`） | ☐ |

---

## B-1 · 守卫判据：区分「列不存在」与「列值为 NULL」

**服务的 AC**：AC-6, AC-7, AC-8, AC-21

- 位置：`BomTreeRenderService`（`:448~489` 统计段 + `:581` `assertParentNoPresent`）
- 现状：`missingParent` 用 `r.driverRow.get("parent_no") == null` 统计 ⇒ 把**根行的合法 NULL 父件**误计入
- 改法：统计改为 **`!r.driverRow.containsKey("parent_no")`** —— 守卫的**语义一字不改**（「整个视图没输出这一列 = 配置错误」），只是判据换成能真正表达该语义的那个
- 🔬 **前置证伪验证（先做，做完再改代码）**：`SqlViewExecutor:382` 是 `row.put(label, rs.getObject(c))` —— **每列都 put，NULL 值也 put**。需实证「根行的 `driverRow` 确实 `containsKey("parent_no")==true`」，若中途有 map 拷贝把 null 键过滤掉，本改法不成立 ⇒ **立即停下报主线**，不要自行换方案
- 🚫 **不许放宽触发条件**：`recursive && kept>0 && 全部行都不含该列` 三个条件一个不能少（原注释「触发条件不得放宽」仍然有效）
- 单测：`BomTreeParentNoGuardTest` 补两例 —— ①全根行（列存在、值全 NULL）**不抛**；②列不存在**仍抛 400**

## B-2 · 骨架 `usage` 值域扩到四值

**服务的 AC**：AC-1, AC-3, AC-4, AC-5, AC-22

- 位置：`CostingBomTreeConfigService`（`VALID_USAGES` / `normalizeUsage`）、`CostingBomTreeConfig`（字段注释）
- 新值域：`QUOTE` / `COST_BASIC` / `COST_DETAIL` / `COSTING`
- **`COSTING` 是只读兼容别名**：查询时等价于 `COST_BASIC`；**新建/编辑一律不许再写入 `COSTING`**（写入时校验拒绝，消息「`COSTING` 已停用，请选择 `COST_BASIC` 或 `COST_DETAIL`」）
- 🚫 **零 DDL、零迁移**：列已是 `varchar(16)` 且无 CHECK；唯一索引按 `usage` 分组，扩值天然生效。**不许新增迁移文件**
- 非法值仍返 400，消息列出四个合法值
- ⚠️ **既有保存期校验一字不改**（`CostingTreeSqlValidator`）：缺输出列仍返 400 且消息不变（AC-4 是本项的回归门禁）
- ⚠️ **`usage='QUOTE'` 的存量记录一个字节不许动**（AC-22 是本项的回归门禁）：不许写迁移改它、不许在代码里顺手规范化它

## B-3 · 渲染期按树页签方言解析骨架配置

**服务的 AC**：AC-5, AC-6, AC-7, AC-8, AC-20

- 位置：`BomTreeRenderService.renderInternal` + `collectTotalMaterialNoUnion`
- 解析链：`templateId` → 该模板的树页签组件（`assertAtMostOneTreeTab` 保证至多 1 个）→ `component_sql_view.builder_config ->> 'dialect'` → `usage`
- 映射：`QUOTE→QUOTE`、`COST_BASIC→COST_BASIC`、`COST_DETAIL→COST_DETAIL`
- **回落链**（三级，缺一级就是静默走错分支）：
  1. 解析到方言 → 用它
  2. 解析不到（组件无 `builder_config`，如手写视图）→ 用调用方传入的 `usage` 实参（今天的行为）
  3. 该 usage 无生效配置 → 交给 B-4 报错，**不许再回落到别的 usage**
- ⚠️ **N+1 纪律**：解析方言最多 **1 条额外 SQL**，与报价行数、闭包料号数无关。🚫 不许在 `for (Object[] dc : driverComps)` 循环里逐个查
- ⚠️ `collectTotalMaterialNoUnion` **必须与 `renderInternal` 用同一份解析结果**（检查点 §0-4）

## B-4 · 骨架缺失的报错文案带数据集名

**服务的 AC**：AC-5

- 位置：`collectTotalMaterialNoUnion` 与 `renderInternal` 里 `cfg == null` 的分支
- 现状文案恒为 `…（costing_bom_tree_config 无 usage=COSTING 且 isActive=true 记录）`
- 改为按实际解析出的 usage 渲染，并带上中文名：`未配置生效的「详细核价」树递归 SQL（costing_bom_tree_config 无 usage=COST_DETAIL 且 isActive=true 记录）`

## B-5 · 核价侧轴口径统一为生产料号（方案甲）

**服务的 AC**：AC-10, AC-11, AC-12, AC-13, AC-14

- 位置：`SemanticCompiler`（`applyFullScope` / `narrowEdge` / `treeChildColumn` 一带）
- 目标产物形态：
  - 非树 `COST_*` 页签：`<方言轴列> = ANY(:total_material_no)`，**不再包 `ds_quote_material` 子查询**
  - 树 `COST_*` 页签：边分支 `<子件列> = ANY(:total_material_no)`；根分支 `<轴列> = ANY(:total_material_no)`，两支都不发桥
  - `QUOTE` 方言**一个字节不动**
- 依据：`CompileDialect.COST_BASIC.axisColumn()` 本来就声明为 `production_no`；桥是 `repair-260908 B-1` 为「轴值是销售料号」这一前提加的，前提被本次取消
- **客户隔离由骨架承担**：销售→生产的翻译只在骨架 SQL 的种子处发生一次（带 `:customerCode`），见 B-9。核价三张基础表本就无 `customer_no` 维度
- ⚠️ 护栏 `assertAxisParamSingleSemantic` 的对账口径要跟着调整（**调整登记，不是放宽**：产物里数出的桥变 0，结构化认出的桥也必须变 0）
- ⚠️ 已存在的 3 个 `COST_BASIC` 组件（`COMP-2298/2299/2300`）需**重新编译落库**；`COMP-2319` 改方言后同样重编（改方言动作由主线在配置阶段做，见 §配置动作）

## B-6 · 非树页签行归属键改用 `hf_part_no`

**服务的 AC**：AC-10, AC-11, AC-12, AC-15, AC-21

- 位置：`BomTreeRenderService`（`:459` 分桶段 + `:527~545` 非树平铺段）
- 改法：非树页签分桶键取 **`hf_part_no`**；`hf_part_no` 缺失时**回落 `material_no`**（保护全库唯一 1 个非配置器视图）
- 依据（写进行内注释，这是压在这个文件上的契约）：
  - `SemanticCompiler:337` `declaredColumns.add(0, "hf_part_no")` —— **无条件第 0 列**，树/非树都发
  - `material_no` / `parent_no` 只在 `if (treeContract)` 分支加 ⇒ **是树契约专用的边键，非树页签本就不该有**
  - `SqlViewExecutor:342/410` 平台外层过滤用的就是 `inner_q.hf_part_no = ANY(:hfPartNos)`
  - 全库实测 42 个生效视图：**41 个有 `hf_part_no`、仅 5 个有 `material_no`**（正好 5 个树页签）
- 🚫 **树页签的边键 `(parent_no, material_no)` 不动** —— 树行上 `hf_part_no ≡ material_no`（`SemanticCompiler:334` 树的 `hf_part_no` 取子件），改了等价但无收益
- `kept == 0` 那条 `LOG.warnf` 的文案同步改成提 `hf_part_no`

## B-7 · 模板发布期告警：含非本数据集页签

**服务的 AC**：AC-16

- 位置：`TemplateService.publish`（`assertAtMostOneTreeTab` 附近，同一处冻结前把关点）
- 判定：以**树页签组件的方言**为该模板的基准方言；其余取数页签方言与之不同的，逐个计入告警
- 🚫 **只告警不拦**（`D-3`/`D-7`）：`publish` 仍返 200，**不得抛异常**
- 产物：响应体新增 `warnings: string[]`（**加法式**，见 `api.md §3`）；同时 `LOG.warnf` 一条
- 模板无树页签 / 无取数页签 → `warnings` 为空数组，**不是 null**

## B-8 · 端点角色收紧

**服务的 AC**：AC-18

- 位置：`CostingBomTreeConfigResource` 类级 `@RoleAllowed`
- `{"SALES_MANAGER", "SYSTEM_ADMIN"}` → `{"SYSTEM_ADMIN"}`
- 覆盖类下**全部**端点（`GET` / `POST` / `PUT` / `activate` / `DELETE`）；不新增方法级注解
- 影响面已实测：`SALES_MANAGER` 仅 3 个账号且全为测试号

## B-9 · 产出 `COST_BASIC` 骨架递归 SQL（交付物是 SQL 文本，不是代码）

**服务的 AC**：AC-7, AC-8, AC-9, AC-14

- 产出一段递归 SQL 文本，随 `test-report.md` 一并交主线，由主线通过「核价树配置」界面配置并设为生效（**子代理不得直接写库**）
- 硬约束：
  1. 引用 `:production_part_nos`（seed = 报价行的 `product_part_no_snapshot`，即**销售料号**）
  2. 输出五列 `root_no / material_no / bom_version / parent_no / node_path`（`CostingTreeSqlValidator.REQUIRED_COLS`）
  3. **种子处翻译一次**：`ds_quote_material` join `customer_no = :customerCode` 把销售料号翻成 `production_no`
  4. 递归体走 `v_ds_cost_basic_material_bom_all`（`production_no` = 父、`component_no` = 子），带 `:versionFilter(...)` 宏
  5. `bom_version` 取 `version_no::text`
  6. 带 `CYCLE material_no SET is_cyc USING cyc_path` 防环
- 自验：用 seed `ARRAY['S0001','S0004','S0008','S0012']` + `customerCode='CUST-0004'` 跑出 **10 行**（S0001 族 7 行 + 其余 3 个各 1 行），且 `S0001` 族最大 `lvl=4`

## B-10 · 测试与回归

**服务的 AC**：AC-15，兼护 AC-21/AC-22

- `BomTreeParentNoGuardTest` 扩例（见 B-1）
- 新增行归属键用例：三分支（仅 `hf_part_no` / 仅 `material_no` / 两者皆无）
- 新增 usage 解析用例：方言→usage 映射 + 三级回落
- 🚫 **不许写清库型测试**（`CLAUDE.md §3.2`：`mvnw test` 直接写共享开发库 `cpq_db_0724`）

---

## 配置动作（主线执行，子代理**不得**代劳）

| 动作 | 谁做 | 为什么不派 |
|---|---|---|
| 配置 `COST_BASIC` 骨架 SQL 并设为生效 | 主线 | 改的是**共享库的生效配置**，属全局状态 |
| 老 `usage='COSTING'` 配置下线 | 主线 | 同上 |
| `COMP-2319 加工费` 方言 `QUOTE`→`COST_BASIC` 并重编译 | 主线 | 改的是用户的业务配置 |

🚫 **红线提醒**：本任务**不涉及**任何 `DROP` / `TRUNCATE` / 清库 / 删迁移。子代理遇到需要执行不可逆操作的情形，**立即停下报主线**，不得自行执行（`CLAUDE.md §3.2`，子代理无批准权）。
