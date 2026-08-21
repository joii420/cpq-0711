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
| **B-5** | AC-1, AC-3, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-38 | **编译器核心**：路径求解（任意跳数、两条路都通时报错不猜）→ 按边基数生成 SQL（`GRAIN`→展开行 / `SUB`→相关标量子查询 / `LOOKUP`→`LEFT JOIN` + 多源 `COALESCE`）→ 铁律内建。<br>🚨 **三条闭包铁律**（照抄 `ll_view` 注释，写错会静默毁数据）：① 白名单表必须在**顶层 FROM** ② 必须 `LEFT JOIN bom_closure_d` + `COALESCE` 兜底 ③ 闭包用 `UNION` 去重不能 `UNION ALL`，且 `(root,node)` 要 `MIN(lvl)` 唯一化。<br>🚨 **版本化表禁 LEFT JOIN**：`unit_price` 等写进带 `is_current` 的 `LEFT JOIN` 会参数错位、**dry-run 照过、只在真实渲染炸** → 必须编译成相关标量子查询 |
| **B-6** | AC-11 | **别名生成**：一律 `_<Sheet简称>_<列名>`，是 `(Sheet, 列)` 的**纯函数**。⚠️ Sheet 简称的取法**开工前定死，定后不可改**（改了等于改全部绑定路径 —— D-13） |
| **B-7** | AC-14, AC-15, AC-47 | **字段树接口**：列清单 = 该 Sheet **真实导入列**；`roles` 两层合并（节点级默认 + 页签级覆盖，D-35）；行粒度随所选字段动态推导 |
| **B-8** | AC-16 ~ AC-19 | **粒度冲突判定**：拖拽期数据（前端据此置灰）+ 保存期兜底。⚠️ 附属源的维度用 `addDims`（相对主源**额外增加**的维度），只有 `GRAIN` 类带 Sheet 自身维度 —— 照抄 Sheet 维度会误拦（D-26 实证：置灰数 9→0） |
| **B-9** | AC-20 ~ AC-24 | **价格策略原子组**：`f_material_element_price` + 别名 `cep` + **双条件 JOIN**；三向一致的整组删除；支持元素键指向手填列（形态 B）；用户先拖的元素列不被回收 |
| **B-10** | AC-37 | **方言参数化**：别名规则 / 收窄策略 / 字段绑定键做成**按侧取的参数**。核价侧三处方言：英文列名无前缀 · `:versionFilter(...)` + `code = ANY(:total_material_no)` · `basic_data_path`。二期只填声明不改架构 |
| **B-11** | AC-26 ~ AC-28 | **预览执行**：只读连接 + `LIMIT 50` + 5s 超时；0 行给**可操作**诊断（哪一层收窄滤没了）；区分「整列全 NULL」与「个别行无记录」 |
| **B-12** | AC-13, AC-29, AC-30 | **保存前体检**：金额↔小计成对 / 料号列与名称列至少一个 / 行键撞键 / 粗粒度列勾小计 / 字段名重复**只告警不阻断**；费用值默认绑对列（实测：`INCOMING_MATERIAL_PROCESS` 的值落 `base_value`，`pricing_price` 恒空） |
| **B-13** | AC-2, AC-12, AC-22, AC-31, AC-38 | **一体化保存事务**（五步原子，见 `api.md §2.4`）。⚠️ 刷 snapshot 走 `refreshSnapshotsByComponent` 且**按 `sortOrder` 精确匹配**（AP-40）；改字段名时 `viewColumn` 与 SQL **必须纹丝不动** |
| **B-14** | AC-32, AC-34 | `component_sql_view` 加 `builder_config` / `builder_version` **两列均 nullable**；存量 `builder_config IS NULL` = 手写模式，**行为逐字不变**；`builder_version` 低于当前编译器版本 → 过期标记 |
| **B-15** | AC-33 | **转为手写**（不可逆脱钩）：清 `builder_config`，此后 SQL 编辑器可写 |
| **B-16** | AC-8, AC-25 | `ComponentService.java:55` `VALID_TAB_TYPES` 由 5 值扩到 **6**（加「费用类」）。<br>🚦 **两处需各裁决一次，开发期报主线**：① 费用类是否进 `TAB_TYPES_REQUIRE_PART_NO_FIELD` ② `BomNodeTypeResolver` 认不认这个新值（今天「没填」与「填了值」走**不同分支**，直接影响报价单树的节点类型判定）。<br>🚫 **存量 19 个来料费用组件（`ll_view` 13 + `lqt_view` 6）`tab_type` 一律保持留空，一行不改**（D-07） |
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
