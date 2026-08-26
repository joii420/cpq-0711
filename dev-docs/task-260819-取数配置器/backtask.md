# backtask.md · 取数配置器（task-260819）· 后端

> **后端只按本文件做。** 与前端的唯一协调物是 `api.md`（你们互相看不见）。
> AC 原文在 `需求文档.md §3`，本文件**只标编号不复制原文**（复制会双写漂移）。
> 🚦 遇 `CLAUDE.md §3.2` 不可逆操作红线**停下报主线**，你没有批准权。

## 0. 开工前必读（不读就动手 = 违规）

| 文档 | 为什么 |
|---|---|
| `docs/方案制定前必读.md` | 7 类改动决策树 + AP-44 的 17 个协议检查点 |
| `docs/rules/backend.md` | 🚫 **N+1 硬指标**：单个业务操作的 SQL 条数必须是常数。种子迁移、四道校验、批量对账都是高危区 |
| `docs/反模式.md` AP-39 / AP-40 | 刷 snapshot **必须按 `sortOrder` 精确匹配**（同 componentId 多实例会反向污染） |
| `docs/反模式.md` AP-53 | `data_driver_path` / `basic_data_path` **禁止直接写 PG 视图名**，必须 `$<sql_view_name>`；`component_sql_view.sql_template` 必须 FROM V6 表 |
| `需求文档.md §4.5` | 7 张表的 DDL 草案 + 三个建模判据（写错就是 bug，不是风格问题） |
| `api.md §0` | **编译器只在后端**，前端不实现任何一份 SQL 生成 |

⚠️ **开工当天第一件事**：重取 Flyway 最大版本号（`SELECT max(CAST(version AS numeric)) FROM flyway_schema_history WHERE version ~ '^[0-9]+$'`）。
2026-08-20 实测为 **387**，但共享库是移动靶（见 `RECORD.md` 2026-08-17 条），**不要照抄本文档里的 V388**。

---

## 1. 任务分解

| 编号 | 服务的 AC | 任务内容 |
|---|---|---|
| **B-1** | AC-51, AC-54 | **语义图 7 张表 + 迁移 + 种子数据**。表结构照 `需求文档.md §4.5`。三个建模判据必须落实：① `add_dims` 挂 `semantic_tab_view_node`（挂错 = 把 D-26 修掉的 bug 固化进 schema）② `semantic_edge.from/to` 用 **RESTRICT** 外键（CASCADE 会静默连带删边，正是最坏情况 —— AC-54 要求 `psql` 绕过应用仍必须失败）③ `dialect` / `variant_key` / 第 ⑦ 张 `semantic_tab_view_column` 一次建齐。<br>种子 = **17 节点 / 19 边（展开 22 条连接）/ 7 行页签视图 / 孤儿 4 张**，逐项对照 `原型图/语义图管理页/data.js` 与定稿原型的 `deriveModel()` 输出 |
| **B-2** | AC-57 | **加载器**：启动全量加载为**不可变内存图**，保存成功后**整体换引用**（🚫 不许原地改）。本项目已在 expand/公式层实证过「共享可变对象 + 并发」的竞态并于 2026-06-22 回滚过一次 |
| **B-3** | AC-52, AC-53, AC-55, AC-36 | **四道保存期校验**，次序与阻断性照 `api.md §1.3` 固定。🚨 **② 的样本不足盲区必须实现**：目标表行数 `< 30` 时返回 `THIN` 而非 `PASS`（D-32 实测 RECYCLE 全库仅 1 行，任何基数声明都能过） |
| **B-4** | AC-56 | **语义图读写端点 + 权限**。`GET` 放开 4 角色且**内容完全相同**；`POST/PUT/DELETE` 限 `SYSTEM_ADMIN`，非超管 **403 且库中数据逐行不变** |
| **B-5** | AC-1, AC-3, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-38 | **编译器核心**：路径求解（任意跳数、两条路都通时报错不猜）→ 按边基数生成 SQL（`GRAIN`→展开行 / `SUB`→相关标量子查询 / `LOOKUP`→`LEFT JOIN` + 多源 `COALESCE`）→ 铁律内建。<br>🔄 **D-50 改写：原「三条闭包铁律」整条作废** —— 不再生成 `WITH RECURSIVE` / `bom_closure`，改为在锚点料号列上生成 `= ANY(:total_material_no)` 收窄，`hf_part_no` 与 `cep.material_no` 保持锚点自身列不改写（AC-3 新断言）。`closureCte()` 停用（保留代码但不再被调用路径引用，删除与否由主线在收尾时裁决）。<br>🚨 **版本化表禁 LEFT JOIN**：`unit_price` 等写进带 `is_current` 的 `LEFT JOIN` 会参数错位、**dry-run 照过、只在真实渲染炸** → 必须编译成相关标量子查询 |
| **B-6** | AC-11 | **别名生成**：一律 `_<Sheet简称>_<列名>`，是 `(Sheet, 列)` 的**纯函数**。⚠️ Sheet 简称的取法**开工前定死，定后不可改**（改了等于改全部绑定路径 —— D-13） |
| **B-7** | AC-14, AC-15, AC-47 | **字段树接口**：列清单 = 该 Sheet **真实导入列**；`roles` 两层合并（节点级默认 + 页签级覆盖，D-35）；行粒度随所选字段动态推导 |
| **B-8** | AC-16, AC-17, AC-18, AC-19 | **粒度冲突判定**：拖拽期数据（前端据此置灰）+ 保存期兜底。⚠️ 附属源的维度用 `addDims`（相对主源**额外增加**的维度），只有 `GRAIN` 类带 Sheet 自身维度 —— 照抄 Sheet 维度会误拦（D-26 实证：置灰数 9→0） |
| **B-9** | AC-20 ~ AC-24 | **价格策略原子组**：`f_material_element_price` + 别名 `cep` + **双条件 JOIN**；三向一致的整组删除；支持元素键指向手填列（形态 B）；用户先拖的元素列不被回收 |
| **B-10** | AC-37 | **方言参数化**：别名规则 / 收窄策略 / 字段绑定键做成**按侧取的参数**。核价侧三处方言：英文列名无前缀 · `:versionFilter(...)` + `code = ANY(:total_material_no)` · `basic_data_path`。二期只填声明不改架构 |
| **B-11** | AC-26 ~ AC-28 | **预览执行**：只读连接 + `LIMIT 50` + 5s 超时；0 行给**可操作**诊断（哪一层收窄滤没了）；区分「整列全 NULL」与「个别行无记录」 |
| **B-12** | AC-13, AC-29, AC-30 | **保存前体检**：金额↔小计成对 / 料号列与名称列至少一个 / 行键撞键 / 粗粒度列勾小计 / 字段名重复**只告警不阻断**；费用值默认绑对列（实测：`INCOMING_MATERIAL_PROCESS` 的值落 `base_value`，`pricing_price` 恒空） |
| **B-13** | AC-2, AC-12, AC-22, AC-31, AC-38, AC-61 | **一体化保存事务**（五步原子，见 `api.md §2.4`）。⚠️ 刷 snapshot 走 `refreshSnapshotsByComponent` 且**按 `sortOrder` 精确匹配**（AP-40）；改字段名时 `viewColumn` 与 SQL **必须纹丝不动** |
| **B-14** | AC-32, AC-34 | `component_sql_view` 加 `builder_config` / `builder_version` **两列均 nullable**；存量 `builder_config IS NULL` = 手写模式，**行为逐字不变**；`builder_version` 低于当前编译器版本 → 过期标记 |
| **B-15** | AC-33 | **转为手写**（不可逆脱钩）：清 `builder_config`，此后 SQL 编辑器可写 |
| **B-16** | AC-8, AC-25 | `ComponentService.java:55` `VALID_TAB_TYPES` 由 5 值扩到 **6**（加「费用类」）。<br>🚦 **两处需各裁决一次，开发期报主线**：① 费用类是否进 `TAB_TYPES_REQUIRE_PART_NO_FIELD` ② `BomNodeTypeResolver` 认不认这个新值（今天「没填」与「填了值」走**不同分支**，直接影响报价单树的节点类型判定）。<br>🚫 **存量 19 个来料费用组件（`ll_view` 13 + `lqt_view` 6）`tab_type` 一律保持留空，一行不改**（D-07） |
| **B-19** 🆕 | AC-58 | **报价侧非 BOM 页签的 `:total_material_no` 注入**（D-52，本期新增范围）。现状：全工程仅 `BomTreeRenderService:268/:292` 与 `CostingVersionService:414` 三处 open 上下文，而报价侧树路由判据是 `isQuoteTreeTabType(t) = "BOM".equals(t)` —— **非 BOM 页签走 `ComponentDriverService.expand`，从不 open**。要做的：在报价侧渲染链路（`ConfigureSnapshotService` / `CardSnapshotService` 的非树分支）expand 前算出**整单 BOM 料号并集**并 `BomTreeVarsContext.set(...)`，渲染结束**必须 remove**（ThreadLocal 泄漏会串单）。<br>🚨 **N+1**：料号并集必须**整单一次**算完（常数条 SQL），🚫 不许每行/每页签查一次。<br>⚠️ 并集口径要与 `BomTreeRenderService` 现有算法**一致**，不要另写一套 —— 两套口径分叉正是 D-50 要收敛掉的历史问题 |
| **B-20** 🆕 | AC-59 | **上下文缺失显式报错**（D-53）。现状 `SqlViewExecutor:626` 未绑定占位符降级为字面量 `NULL` → `ANY(NULL)` 恒 0 行且不报错（实测 `ANY(NULL)`=0 / `ANY(ARRAY[])`=0 / `ANY(ARRAY['X'])`=1）。要做的：SQL 文本含 `:total_material_no` 却拿不到绑定值时**抛可识别错误**（错误码点名该参数），并让错误到达用户可见处。<br>⚠️ **只收窄本参数的降级行为**，不要把 `SqlViewExecutor` 对所有参数的通用降级一并改掉 —— 那会波及 `:versionFilter` 等既有路径（`SqlViewExecutor` 注释里记着「绑空数组而非 NULL」的既有约定，别推翻）。<br>🚨 本条是**反证型**：必须同时证明「人为去掉上下文后确实报错」，只跑通不算 |
| **B-21** 🆕 | AC-62 | **回分改用「后代→根」映射**（D-56，用户裁决走 A）。`collectTotalMaterialNoUnion()` 算并集时本就走过树结构 —— 顺手产出 `Map<后代料号, 根料号>`；`expandMulti` 回分时先把行的料号映射回根，再入桶。SQL 侧 `hf_part_no` 保持锚点自身列不改写。<br>🚨 **这是本轮最高风险项**（`AP-31`/`AP-37` 核心地带），三条硬约束：① **不串单** —— 一张单里两个成品**共用同一子件**时，该子件的行要**同时**出现在两个桶里，各自独立（AC-62 ③ 专守此点，必须用这种数据验）；② 映射与并集**同一次树遍历**产出，🚫 不许为映射再查一次（N+1）；③ 找不到映射的料号**按其自身入桶**（兜底不丢行，等价于原 `COALESCE` 的兜底语义）。<br>⚠️ 与 B-19 同属一条链路，**先做 B-19 的注入、再做本项的回分**，两者分开自检 |
| **B-22** 🆕 | AC-37 | **`BuilderService.doCompile()` 读请求体 `cfg.dialect`**（D-59），缺省 `QUOTE`。现状写死 `CompileDialect.QUOTE` → AC-37 的核价侧编译路径根本走不到，一期 B-10「方言参数化」无法验收。⚠️ 只改 dialect 取值来源，🚫 不要顺手改编译器里按 dialect 分支的逻辑（那是 B-10 已交付的部分） |
| **B-23** 🆕🔴 | AC-26, AC-27, AC-28, AC-57 | **`/preview` 端点注入 `:total_material_no`**（D-63，修本轮引入的真回归）。现状：预览不走渲染链路、无 `BomTreeVarsContext` → 参数无人绑定 → 裸 `:` 进 SQL → **PG 语法错误 `syntax error at or near ":"`，预览完全不可用**（A/B 实测：`Sec36a.ac57` 并发预览 20 次全败；`Sec35` 报 `PREVIEW_EXECUTION_FAILED`）。<br>**注入什么**：用户预览时选定的**那个料号自己的 BOM 闭包**（成品 + 全部后代），复用 `BomTreeRenderService.collectTotalMaterialNoUnion(...)`，🚫 不是只注入料号自身（那样预览永远看不到子件行，与 AC-26 乙组断言冲突）。<br>⚠️ 生命周期同 B-19：try/finally `remove()`，ThreadLocal 泄漏会串单。<br>⚠️ 预览是**单料号**场景，与单卡路径口径一致（D-55③「传几行算几行」）。<br>✅ 验收：`Sec36a.ac57` 与 `Sec35` 的预览类用例由红转绿，且 AC-26 甲/乙两组行数差额符合实测基准 |
| **B-24** 🆕🔴 | AC-20, AC-21 | **field-tree 补 `groupKind='PRICE'` 分组 + `elemKey` 标记**（D-65，修用户真机发现的功能级缺陷）。<br>**现状**（主线实调 `GET /field-tree?tabType=材质元素` 实测）：只返回 `MAIN`/`SAME`/`SUB` 三组，「元素单价」(`isCore=true`)、「货币」混在 `MAIN` 里；所有列 `elemKey` 均为 `None`。前端 `SqlViewBuilderTab.tsx:95` / `:415` 两处判据都要求 `group.kind === 'PRICE'` → 永不成立 → **价格策略永不成块、永不自动带出元素列**。<br>**要做的**：① 把价格策略相关列（`isCore` 的元素单价 + 货币）单独归入一个 `groupKind='PRICE'` 的组返回；② **给元素符号列打 `elemKey=true`**（前端 `:419` 遍历全部 groups 找 `elemKey` 的列来自动带出，缺它则 AC-20② 没有数据依据）。<br>⚠️ **元素键列不必移出原组** —— 前端 `toSelColumn(ek.col, ek.group, {autoElem:true})` 靠 `autoElem` 进块，来源组不影响；但**「货币」必须进 `PRICE` 组**，因为它靠 `raw`(=`kind==='PRICE'`) 才进块（AC-21 前置要求块内含 元素/元素单价/货币 三列）。<br>🚫 **前端零改动** —— 用户裁决走 A：「哪些列构成价格策略原子组」由后端权威给出。<br>✅ 验收：用户真机拖入「元素单价」→ 出现带框块（标题含「元素单价（接价格策略）」+ `f_material_element_price`）+ 自动带出「元素」列；AC-21 三向删除一致 |
| **B-17** | AC-35, AC-36 | **CI 两道断言**：边基数（**从表读边定义**，不再读代码声明）+ handler 双向对账。两条都是**反证型** —— 必须同时证明「人为改错后测试确实失败」 |
| **B-18** | AC-40 | **自检声明**：后端新增端点 `curl` 返非 500（鉴权路径 401 视为正常）；`SELECT success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1` 为 `t`；PR 中**显式声明 `field_type` 枚举未改动**并附 `grep` 证据（据此不触发 AP-44 强制 E2E） |

---

## 2. 三条硬约束（违反即打回，不接受"跑通了"）

1. 🚫 **N+1**：种子迁移灌 17 节点 + 22 连接 + 7 视图、四道校验批量跑、handler 对账扫全部节点列 —— 这三处都必须是**常数条 SQL**。循环体里出现查询 = 违规（`backend.md`）
2. 🚫 **不许在共享库上跑会清库的测试**（`CLAUDE.md §3.2`）。`test` profile 指向 `cpq_db`，与 dev 库 `cpq_db_0724` **不是同一个库**，写集成测试时注意
3. 🚫 **不许手工 `psql -f V_xx.sql`** —— Flyway `migrate-at-start` 自动跑；DDL 后必须重启才生效

## 3. 开工前须复核（§3.9 遗留，复核结论写进 PR）

- 取价函数仍为 `f_material_element_price` + 双条件 + 别名 `cep`（AC-1 / AC-3 依赖）
- `annual_discount` 三种 `discount_type` 的维度声明（年降三张按 N-7 挂空，二期才用，但种子要登记）
- builder 端点的角色口径：`SYSTEM_ADMIN` + `PRICING_MANAGER` 是否与组件管理现有口径一致
