# 测试方案与 AC 追溯矩阵 · task-260904-页签类型收缩

> 闸门 A 的**前置产物**，开工前定稿。执行结果另出 `test-report.md`。
> AC 原文在 `需求文档.md §③`。

---

## 1. 测试环境与红线

| 项 | 规定 |
|---|---|
| 库 | `10.177.152.12:5432/cpq_db_0724` —— 🚨 **这就是开发库本身**（`application-test.properties` 默认值），`mvnw test` 直接写它 |
| 🚫 **禁止** | 任何清库 / `TRUNCATE` / 无 `WHERE` 的 `DELETE` / 重置全局状态的测试，**哪怕写在 `beforeAll` 里**（`CLAUDE.md` §3.2「测试也算」） |
| 夹具纪律 | 自造数据一律加任务前缀 `t260904_`，用完按主键精确清理；🚫 不按表清 |
| 后端存活判据 | 业务端点返 **401**（应用在跑、鉴权正常）。🚫 `/q/health` 返 404，**它不是健康探针** |
| curl | 一律加 `--noproxy '*'`（本机 `http_proxy` 会让 localhost 走代理返 502） |

---

## 2. AC 可追溯矩阵

**双向覆盖已自检通过**：每条 AC 至少一个 `F-x`/`B-x` 认领 + 至少一条用例；每个 `F-x`/`B-x` 至少指回一条 AC。

| AC | 类型 | 认领的任务项 | 用例 | 验证手段 |
|---|---|---|---|---|
| AC-1 | 单点 | B-1, B-2, B-3, B-4 / F-1, F-2, F-3 | TC-01 | UI + 编译产物比对 |
| AC-2 | 单点 | B-1 / F-1, F-2, F-3 | TC-02 | UI + SQL 含 `LEFT JOIN f_material_element_price` |
| AC-3 | 单点 | B-1, B-2 / F-1, F-2 | TC-03 | UI ×3 数据源 |
| AC-4 | 单点 | B-5 | TC-04 | API + `__nodeType` 断言 |
| AC-5 | 单点 | B-5 | TC-05 | API ×2（零件/外购件） |
| AC-6 | 单点 | B-6 / F-5 | TC-06 | API 400 + 行数不变 |
| AC-7 | 单点 | B-7 / F-5 | TC-07 | API 400 ×2（环 / 自环） |
| AC-8 | **反向** | B-8 | TC-08 | API 400 ×2（材质宿主 / 外购件宿主） |
| AC-9 | 单点 | B-12 | TC-09 | `output_material_type` 逐字比对 |
| AC-10 | **序列** | F-6 | TC-10 | Playwright E2E |
| AC-11 | **序列** | B-5, B-13 | TC-11 | E2E + `snapshot_rows` 比对 |
| AC-12 | **边界** | B-5 | TC-12 | API + 二次加下级 |
| AC-13 | **边界** | B-13 | TC-13 | md5 逐字比对 |
| AC-14 | **边界** | B-4, B-13 | TC-14 | 27 模板抽样 + 行数比对 |
| AC-15 | **边界** | B-11 / F-4 | TC-15 | 3 个存量组件所属单据 |
| AC-17 | **反向** | B-9 | TC-17 | API 400 |
| AC-18 | **反向** | B-10 | TC-18 | 保存 400/200/400 三态 |
| AC-19 | **反向** | F-1 | TC-19 | 改名 → 体检重跑 → 409 影响确认 → SQL 逐字不变 |
| AC-20 | **反向** | B-1, B-2 | TC-20 | 4 个数据源逐列清点 + 停用前后字段清单比对 |
| AC-21 | 单点 | B-17 | TC-21 | 新建树组件保存后 `bom_recursive_expand=true` + 能拿到 union driver |
| AC-22 | **反向** | B-18 | TC-22 | 全工程 `"BOM".equals` 计数归零 + 6 处全部改走双判据 |
| AC-24 | **反向** | F-8 | TC-24 | 新组件与**存量组件**在 UI 上三处均可用 |
| AC-25 | **反向** | B-2, B-4, B-13, B-18 | TC-25 | **109 个存量组件行为零变化**（21 个树组件逐字比对） |
| AC-27 | 单点 | B-4 | TC-27 | 双判据三态分流（新/存量/两者都有） |
| AC-26 | **反向** | B-20 | TC-26 | 成品料号加叶子仍 400 |

**三类覆盖齐全**（v3，共 **25** 条）：单点 10 条（AC-1~7, 9, 21, 27）· 序列 2 条（AC-10, 11）· 边界 4 条（AC-12~15）· **反向 9 条**（AC-8, 17, 18, 19, 20, 22, 24, 25, 26）。

> ⛔ **AC-16 / AC-23 已随 S-8 删列取消**（v3 用户裁决「只考虑新建组件」）。编号保留不复用。
> 🔑 **反向 AC 占 9/25** —— v3 的性质已从「改造 + 清理」变为「**新增新路径、存量原样不动**」，绝大部分验收力量用在证明「没弄坏既有的」。

> 📌 **v2 修订（2026-09-04 独立评审后）**：AC 由 20 条增至 26 条。新增的 6 条（AC-21~26）全部来自评审暴露的**消费点缺口** —— v1 把「五个消费契约」当成穷举，实际 9 个。
> 🚫 **v1 的「双向覆盖自检通过」不能作为质量证据**：它验的是矩阵闭合，验不出矩阵少了四行。

---

## 3. 用例要点（只写容易做成假绿的地方）

### TC-04 / TC-05 / TC-12 —— 加叶子类型判定

**必须用 dev 库真实料号，不许自造**，因为自造数据会绕开"现网数据长什么样"这个前提：

| 用例 | 取数 | 立项时快照（2026-09-04 早） | 半日后实测 |
|---|---|---|---|
| TC-04 材质 | `material_recipe.code` 命中的投入料号 | 6 | **7** |
| TC-05 零件/外购件 | `ds_quote_material` 且 `material_type` 非空 | 2 | **3** |
| TC-12 空值默认零件 | `ds_quote_material` 且 `material_type IS NULL` | 29 | **30** |
| TC-06 不存在 | 两表都不在的投入料号 | 1 | **4** |

🚨 **这些数字是移动靶，🚫 不许写进断言**。同日实测漂移：`ds_quote_material` **45→71**、`ds_quote_customer_part` **18→48**、`ds_quote_material_bom` **61→110**；`source='MANUAL'` 26 行、最近写入 `2026-09-05 00:45` —— **有别的会话正在往共享库写**。

⇒ **执行期一律先跑探测 SQL 取实际值，再断言「该分支被执行过」**，不要断言具体条数。测试报告里记录当次探测到的数字即可。

> ⚠️ TC-05 / **TC-08（外购件宿主）** 都依赖「存在 `material_type='外购件'` 的料号」，可用量极少且**可能全是同一种类型 ⇒ 用例空跑**。两条用例执行前都要先跑：
> `SELECT material_type, count(*) FROM ds_quote_material WHERE material_type IS NOT NULL GROUP BY 1;`
> 若「外购件」一个都没有 → **必须自造一条带 `t260904_` 前缀的物料**补齐，否则 AC-5 的外购件分支从未被执行（四类假绿之「断言从未执行」）。

### TC-07 —— 环检测

必须**同时**验两种：① 真环（A→B 存在，以 B 为宿主挂 A）；② 自环（宿主 = 料号）。
断言不能只看 400，还要看**响应里给出了环路径**——只判状态码的话，任何一个别的 400 都能让用例变绿。

### TC-13 / TC-14 —— 零回归

🚨 **基线必须在改动前取**：

```sql
-- 改动前跑一次，存盘
SELECT id, md5(snapshot_rows::text), md5(row_data::text)
  FROM quotation_line_component_data
 WHERE line_item_id IN (<5 张在途单的行>) ORDER BY id;
```

改完再跑一次逐行对比。**改完才想起取基线就没有对照了**。

### TC-09 —— 不再回填类型

断言 `output_material_type` **逐字不变**，不是"值合理"。
⚠️ 这条容易变成重言：如果测试用的那条 BOM 行本来 `output_material_type` 就是 NULL，改动前后都是 NULL，断言恒成立而什么都没证明。**必须选一条该列有值的行**（现网 61 行里 60 行有值）。

### 🚨 TC-27 —— 窗口期空跑（本期最高假绿风险）

`task-260819` 合并后到「第一个真实组件被保存」之间存在窗口期，期间 `component_sql_view.builder_version` **仍是 0 行** ⇒ **分支① 一次都不会命中，全部走分支②**。此时任何验分支①的用例都会**以「通过」的形态空跑**。

⇒ **强制**：TC-27 的组件 A、C **必须由用例自己经取数配置器真实保存路径产出**，🚫 不许依赖库里恰好存在；并且**先断言 `A.builder_version IS NOT NULL`**，再断言分流结果。

> 📌 这与本任务被独立评审打回的那个 P0 完全同型（判据的输入源是空的，而文档/用例当成有）。同一个坑不栽第二次。

### TC-25 / TC-27 —— 双判据是本期最容易假绿的地方

**TC-27 必须验三态，缺一不可**：新组件（有 `builder_config`）· 存量组件（只有 `tab_type`）· **两者都有的构造组件**（断言以 `semantic` 为准）。
只验前两态的话，「分支②优先」这种写反的实现照样全绿 —— 而它会让存量的 `tab_type` 值污染新组件的判定。

**TC-25 必须逐字比对，不能只看"能打开"**。21 个存量 BOM 树组件的树行数与树序要与改动前 md5 相同；🚨 **基线必须在改动前取**。

### TC-21 —— 已随 S-11 撤出（编号保留不复用）

> 原「双表数据源」用例随 S-11 进 BACKLOG。**新的 TC-21 服务 AC-21**（`bom_recursive_expand` 写入源）：
> 新建「物料BOM」组件 → 保存 → 查 `SELECT tab_type, bom_recursive_expand FROM component WHERE id=<新>`，断言 `bom_recursive_expand=true` 且 `tab_type` 为空。
> ⚠️ **必须同时验下游**：该组件在报价单上真能渲染出树行（`eligibleForBomUnion` 生效），只查 DB 列值不够 —— 那只证明写进去了，不证明下游认它。

### 已废弃：双表数据源用例

🚨 **直接用现网数据验证会得到一个假结论**。实测 `ds_quote_customer_part` 的 18 条记录里**只有 2 条**的 `material_no` 在 `ds_quote_material` 中存在：

| 客户 | 该客户行数 | 其中物料表无对应 |
|---|---|---|
| CUST-0004 | 12 | **11** |
| Q13CUST0617 | 2 | 2 |
| CUST-0001 | 2 | 2 |
| CUST-0002 | 1 | 1 |
| C1 | 1 | 0 |

⇒ 拖了「品名」「单重」几乎全是空 —— 这时**分不清是 JOIN 没接上、还是数据本来就没有**。

**必须做的**：造一条 `t260904_` 前缀的配对数据（物料表 + 客户料号表各一行、`material_no` 相同），断言该行的品名/单重**有值**；再用一条只有客户料号的行，断言它**仍然出现**且品名为空（证明是 LEFT 不是 INNER）。用完按主键精确删除。

✅ **用户已授权（2026-09-04）**：「两表对不上的你随便赋予一个，测试数据」—— 配对数据的 `material_no` / 品名 / 单重**取值随意**，不需要业务含义，只要两表一致、能证明 JOIN 接通即可。
🚫 但仍守夹具纪律：**一律加 `t260904_` 前缀、用完按主键精确删除**，🚫 不改动现网那 18 条客户料号的既有 `material_no`（那是真实业务数据，改了会影响别的会话）。

### TC-22 —— 存量主件组件

这是**行为预期会变**的用例，🚫 不能套用 TC-13 的「md5 逐字相同」。断言的是"变成什么"，不是"没变"：行数 45 → 该客户的客户料号数，列名列序不变，属于该客户的行值逐字不变。

### TC-20 —— 字段面板一列不少

**必须逐列比名字，不能只比数量。** 只断言"字段数 = 12"的话，某列被替换成另一列照样绿。
停用前先导出四个数据源的字段清单存盘，停用后逐字 diff。

### TC-19 —— 字段名编辑链路

必须验到 **409 影响确认**这一步才算数。只验"输入框能打字"是空验证 —— 那个 `<input>` 本次根本没改，恒绿。
真正的回归风险在：改名触发的**保存前体检 `useEffect` 依赖数组里含 `tabType`**，本次改造动了 `tabType` 的来源，可能误伤该依赖。

### TC-18 —— 公式闸门三态

三个断言缺一不可：非树页签用 `tree_ref` → **400**；树页签用 `tree_ref` → **200**；树页签用「上一行」→ **400**。
只验第一条的话，把闸门改成"全部拒绝"也能过。

---

## 4. 回归清单（改动前后各跑一遍）

| # | 范围 | 判据 |
|---|---|---|
| R-1 | 报价单 BOM 树渲染 | 27 个含树模板抽样，行数与树序不变 |
| R-2 | 树上删叶子 | `delete-preview` / `delete` 两端点行为不变 |
| R-3 | 受限页签准入 | AC-17 |
| R-4 | 组件保存校验 | 114 个未配组件仍可保存（AC-15） |
| R-5 | 公式父子取值闸门 | AC-18 |
| R-6 | 取数配置器编译产物 | 39 个数据源各编译一次，与改动前 SQL 逐字比对 |
| R-7 | 模板冻结与快照读取 | 5 张在途单（AC-13） |
| R-8 | 核价侧 spine 树渲染 | `usage=COSTING` 渲染不变 |
| R-9 | 后端全量单测 | `mvnw test`；**失败项必须 A/B 归因**（对照干净 master 是否同样失败），🚫 不许直接归因"本次引入" |

---

## 5. 假绿防范（本任务专项）

| 风险 | 防范 |
|---|---|
| **断言从未执行** | TC-05 外购件分支现网可能无数据 → 见 §3 的前置检查 |
| **重言断言** | TC-09 选 `output_material_type` 有值的行；TC-13 确认基线 md5 与改后确实取自同一批行 |
| **测了旧代码** | 每轮验证前确认后端已重编译（改 java 后看 dev 模式热重载日志，或重启后确认迁移 `success=t`） |
| **验证脚本空验证** | 🚨 **每条新增校验（B-6 / B-7）必须做证伪实验**：把校验代码改回去（或注释掉），重跑用例，**必须变红**。不变红 = 用例没测到东西 |
| **worktree 绿 ≠ 主仓绿** | 合并后必须在主工作区再跑一次 `mvnw test`（主仓 `target/` 可能残留旧 class） |
| **共享库数据漂移** | R-9 的失败项一律 A/B 对照归因；`quotation-flow` E2E 在干净 master 上本就有已知失败（见 `INDEX.md`） |

---

## 6. E2E

按 `docs/E2E测试方法.md`。本次涉及**前端协议级改动**（`field-tree` 响应结构变更）⇒ **强制跑 E2E**：

- `quotation-flow.spec.ts`（报价主流程，验 AC-14 渲染回归）
- 新增 `tabtype-shrink.spec.ts`（覆盖 AC-10 序列 + AC-1/2/3 配置器三态）

⚠️ E2E 反复跑会把 `admin` 置为 `INACTIVE`，跑前确认账号状态为 `ACTIVE`。

---

# 第二批（AC-1 / 2 / 3 / 10 / 19 / 20 / 29 / 30）

> 2026-09-06 落。第一批的方案与结论在上文，**本节只写第二批**。
> 🚫 本节用例全部**从 AC 原文派生**，写用例期间未读 `cpq-backend/src/main/java/**` / `cpq-frontend/src/**`；
> 只在「用例写完之后」为了做证伪实验与校准 E2E 选择器读了实现，**且未据此改动任何断言**。

## 7. 🚨 与 AC 原文不符 / 无法构造的四处（不擅自改写，报主线裁决）

| # | AC | 原文要求 | 实测 | 本套用例的处置 |
|---|---|---|---|---|
| **7.1** | **AC-1③** | 「生成的 SQL 含 `WITH RECURSIVE` 与 `UNION ALL` 根分支」 | 语义编译器对 `MATERIAL_BOM` 锚点产出的 SQL **不含**二者。库侧交叉：`bom_recursive_expand=true` 的 **21** 个组件里，`sql_template` 含 `WITH RECURSIVE` 的 **0** 个（含 21 个存量手写视图组件）⇒ **递归展开从来就不在 `$view` SQL 层**，由 `bom_recursive_expand` + 渲染层 BOM union driver 承担（第一批 AC-21② 已取证）。**改动前后一致，非本次引入** | 🚫 **不写成断言**（明知必红，且红的原因是 AC 写错不是代码错）；也🚫不擅自改写成能过的断言。`DataSourceCatalogAcTest#ac1_treeSourceCompilesWithoutRecursiveCte` **只取证 + 打印存档**，另断言 AC-1③ 真正要保护的等价性质（产物 FROM 锚点表 + 带 `:total_material_no` 按单收窄）。**待裁决：改文档还是改实现** |
| **7.2** | **AC-19③** | 「（改字段名后）保存返回 **409 `IMPACT_CONFIRM_REQUIRED`**」 | 组件被 1 个模板引用时：**改名 → 200**（`affectedTemplates:0`）；**删列 → 409**（`removedColumns:["_物料与元素BOM_组成含量（%）"]`）。影响确认的键是 **`removedColumns`（viewColumn 差集）**，而 **AC-19④ 明确要求 viewColumn 不随改名变** ⇒ **③ 与 ④ 在逻辑上不可能同时成立**，是 AC 内部自相矛盾，不是产品缺陷 | 按 AC-19 自述的目的（「本 AC 是**防止误伤的回归断言**」）改用**删列**这个真实触发动作，验证链路三段完整：409 + 列出受影响模板 + **未确认时不落库** + `confirmedImpact` 重发后落库；并把「纯改名 → 实际 HTTP 几」作为**阴性对照打印存档**。**待裁决** |
| **7.3** | **AC-20②** | 注「⚠️ 仅 QUOTE 方言成立：`COST_BASIC`/`COST_DETAIL` 的全部 5 类页签各挂 **2** 组，多一个 `QUOTE_MATERIAL_BRIDGE(AUX)`」 | **已过期**（`task-260819` B-50 按 NARROW 边剔除桥之后）。实测**三方言均 1 组**，且 `BOM`/`零件`/`外购件` 三坐标的字段清单（含组结构）**三方言下全部逐字相同** | 按实测断言「三方言下三坐标清单逐字相同」（比原文更强，覆盖面从 1 个方言扩到 3 个），并把各方言实际组结构打印存档。**AC 原文注记需回写** |
| **7.4** | **AC-20④** | 「主源组末尾的查名展开字段（`syntheticLookupFields`）照常出现」 | 该机制**在实现里存在**（MAIN 组末尾追加），但**当前数据下三方言 39 个数据源产出的查名字段合计为 0**（全库仅 QUOTE/材质元素 的 PRICE 组有 `lookupLib` 非空字段，那是价格策略原子组、不是查名展开）。⇒ 断言「照常出现」只能写成空集断言，正是 testing.md §3「断言从未执行 = 假绿」 | **本套用例不覆盖，显式列为交付缺口**。🚫 不改写成别的可测含义。**待裁决：补数据使其可验，还是删掉该子项** |
| **7.5** | **AC-29③** | 「C 绑「自制加工费」**只配名称列** → 200」 | **无法构造**：全库语义图**没有任何一列带 `PART_NAME` 角色**（三方言 39 个数据源共 147 个角色标记：`ROW_KEY` 84 / `PART_NO` 39 / `SORT` 24，`PART_NAME` **0**）⇒ 在取数配置器里配不出「只配名称列」 | **本套用例不覆盖**（本次派工范围也只点名 ①+阴性对照）。已列为缺口。**待裁决** |

> 📌 另记一处**派工简报与 AC 原文的出入**：简报把 **AC-10** 描述为「数据源的 `semantic` 三态取值正确」，
> 而 `需求文档.md` 的 **AC-10 原文是「配置期状态连续性」（序列 AC）**。
> ⇒ 本套按**原文**写 AC-10（序列），`semantic` 三态则作为 AC-1/2/3 共同依赖的契约在 `ac3_...` 里覆盖。两者都做了。

## 8. 第二批用例清单

用例目录：`cpq-backend/src/test/java/com/cpq/task260904/`（公共基座 `Batch2Base.java`，继承第一批的 `Task260904Base`）

| # | AC | 用例（类·方法） | 层级 | 关键判据 |
|---|---|---|---|---|
| 1 | AC-1①、S-4 | `DataSourceCatalogAcTest.ac1_retiredTabTypesAbsentFromCatalogButRowsUntouched` | 接口 | 三方言 `availableSources`/`availableTabTypes` 均不含「零件/外购件」；**清单坐标集合 == 库里 ACTIVE 且非退役的坐标集合**（现算）；**阳性对照**：`semantic_tab_view` 退役 6 行仍 ACTIVE（证明过滤在 API 层，不是把数据删了） |
| 2 | AC-3、semantic 三态 | `.ac3_catalogScopedByDialectAndSemanticTriState` | 接口 | 每条 `dialect` 恒等于入参；`sourceKey` 方言内唯一；`semantic` 键必在；**TREE 恰好 1 / MATERIAL_ELEMENT 恰好 1 / 其余显式 null**；出现第四种取值直接 fail |
| 3 | AC-2③、AC-3③ | `.ac2_priceStrategyGroupOnlyUnderMaterialElement` | 接口 | 三方言 **39 个数据源逐个**清点：挂 PRICE 组的 ⊆ `semantic='MATERIAL_ELEMENT'` 的；**空真守卫**：先断言确实存在 PRICE 组与 MATERIAL_ELEMENT 源 |
| 4 | AC-2④ | `.ac2_priceColumnPullsInJoinAndElementCode` | 接口 | 拖入「元素单价」后产物含 `LEFT JOIN f_material_element_price` + 元素编码列被自动补入；**阴性对照**：不拖单价时二者都不出现（证明断言有分辨力） |
| 5 | AC-3④ | `.ac3_plainSourcesCompileWithoutRecursiveCte` | 接口 | 9 个 `semantic=null` 普通源逐个编译，均不含 `WITH RECURSIVE`；覆盖数 <3 直接 fail（防空跑） |
| 6 | AC-1③ | `.ac1_treeSourceCompilesWithoutRecursiveCte` | 接口 | ⚠️ **只取证不断言**，见 §7.1 |
| 7 | AC-1②、AC-2② | `.ac1and2_readonlyBadgeSourceFields` | 接口 | 三方言下 TREE / MATERIAL_ELEMENT 源的 `label` == 锚点节点 `display_name`（只读回显的数据来源） |
| 8 | AC-20① | `FieldPanelColumnsAcTest.ac20_namedSourcesExposeAllBusinessColumns` | 接口 | 四个点名源**逐列比名字**（集合相等，不比数量）== 锚点表物理列 − AC 原文系统列；实测 12/12/9/9 与 AC 原文**逐表一致** |
| 9 | AC-20① 扩展 | `.ac20_everySourceExposesItsBusinessColumns` | 接口 | 39 个源全覆盖：**幻列 0**、非白名单缺列 0（白名单仅视图型锚点的 `is_current`） |
| 10 | AC-20② | `.ac20_retiredTabTypesShareIdenticalFieldListWithBom` | 接口 | 三方言 `BOM`/`零件`/`外购件` 组签名逐字相同；前置断言退役坐标仍 ACTIVE 且可查（护住 AC-25①） |
| 11 | AC-20③ | `.ac20_onlyElementBomCarriesPriceGroup` | 接口 | 四源中只有「物料与元素BOM」含 PRICE 组 |
| 12 | AC-10 | `BuilderStateContinuityAcTest.ac10_builderStateSurvivesSaveReloadRenameSaveReload` | 接口·**序列** | 建 → 选物料BOM 拖 5 列 → 存 → **重读** → 改名 → 再存 → **再重读**；全程 semantic 恒 TREE、5 列与改名逐字保留、`component.tab_type` 始终为空 |
| 13 | AC-19④⑤ | `.ac19_renameDoesNotTouchViewColumnOrSql` | 接口 | 改名后 `viewColumn` 逐字不变、编译产物与落库 `sql_template` **逐字节相同**；**分辨力守卫**：先断言改名真的落库了（否则三条「不变」全是重言） |
| 14 | AC-19③ | `.ac19_impactConfirmationStillGuardsColumnRemoval` | 接口 | 见 §7.2：删列 → 409 + 列出模板 + **未确认不落库（仍 2 列）** → `confirmedImpact` → 落库（1 列） |
| 15 | AC-29①② | `IdentifierColumnGateAcTest.ac29_identifierGateFollowsDataSourceSemantic` | 接口 | A(TREE 无标识列) → **200**；B(普通源同样不配) → **400 `INSPECT_BLOCKED`**，文案含「料号列/名称列/至少」；B 被拒后 `builder_version` 未被写入；**前置守卫**：断言所选列在语义图里确实不带 `PART_NO/PART_NAME/ROW_KEY` 角色 |
| 16 | AC-30①②③ | `PriceGroupDuplicationAcTest.ac30_priceGroupNotDuplicatedOnQuote` | 接口 | QUOTE 恰好 2 组且 PRICE 组无重复 `groupKey`；保留块 `isCore=true` / `viewColumn='元素单价'`；核价两套仍各 1 组且无 PRICE 组；**前置取证**：QUOTE 的 `FUNC_ELEMENT_PRICE` AUX 挂载仍 1 行（证明修法没走「删 V413 种子」那条禁区） |
| 17 | AC-30④ | `Sec34PriceStrategyTest`（既有 5 条） | 接口 | 阴性对照，随本批一起跑，5/5 绿 |

**三类覆盖**：单点 11（#1~#11, #15, #16 部分）· **序列 1**（#12 AC-10）· 反向/阴性对照 5（#4 阴性、#10、#13、#14、#17）。

### 8.1 🚨 断言纪律：为什么本批几乎不出现具体数字

主线实测 `availableSources` = QUOTE 11 / COST_BASIC 10 / COST_DETAIL 18。
**这些是共享 dev 库当前配置数据的快照，不是常量** —— 写死它们，下一次语义图种子迁移就把用例打红，
而那个红**长得和产品回归一模一样**。
⇒ 期望值一律**执行期从库里现算**（`semantic_tab_view` / `information_schema.columns`），
断言的是**结构不变量**（不含退役值 / `dialect` 恒等于入参 / `sourceKey` 唯一 / TREE 恰好 1 个 /
字段集合 == 物理列 − 系统列），实测数字只**打印**。

**唯一两处写死数字，且都是契约值不是数据快照**：
- AC-20① 的系统列名单（逐字取自 AC 原文）与四表 12/12/9/9（作**交叉核对**打印，不一致只提示不判失败）；
- AC-30 的「QUOTE 2 组 / 核价各 1 组」——判据线来自 `api.md §1.3` 声明的**期望形态**。

### 8.2 ⚠️ 环境陷阱（本批实际踩到，写下来省下一个人的时间）

| 陷阱 | 症状 | 处置 |
|---|---|---|
| **`node_key` 跨方言撞车** | `MATERIAL_BOM` / `ELEMENT_BOM` 在三方言下**各有一个独立节点、列集合不同**。按 `node_key` 查列会跨方言拿到别的表的列 ⇒ 保存报 `PHYSICAL_EXISTENCE: 该表在数据库里没有这一列` | 一律按 **坐标解析出的锚点节点 id** 查列（`Batch2Base.anchorNodeId/activeColumnsOf/partNoColumnOf`）。本批首轮 2 条失败即此因，**是用例夹具错不是产品缺陷** |
| **`dialect` 参数名** | 传错名字（如 `dataset`）被 JAX-RS **静默忽略**，三方言全返默认 QUOTE 清单，用例照样绿 | `Batch2Base.assertDialectParamIsHonored` 作阳性对照：**先证明换方言结果确实不同**，再跑按方言分支的断言 |
| 🚨 **`target/` 与 8089 dev server 争抢** | `NoClassDefFoundError` / `Could not load class with name: XxxTest` —— **这类红不是代码问题，是构建目录被并发改写** | 本批最终结论**全部在隔离副本里跑**（`src/ pom.xml mvnw .mvn` 整体拷出），拷完 `diff -rq` 逐文件确认与 worktree 一致后再跑。🚫 不 `mvnw clean`（会铲掉 8089 脚下的 `target/classes`） |
| **antd Select 虚拟滚动** | E2E 里 `allInnerTexts()` 只拿得到视口内那几项，选项一多就漏 | `readAllSourceOptions()` 滚动虚拟列表累加，直到无新项 |

## 9. 第二批证伪实验（testing.md §4.4，逐条留档）

**harness 纪律**：每轮先备份目标文件 → 施加破坏 → **先断言 md5 已变（证明干预真的进了文件）** → 跑用例 → 逐字节还原并核对 md5。
🚫 不用 `git checkout` 还原（工作区有开发代理的未提交改动）。

| # | 注入点 | 干预生效？ | 用例是否变红 | **失败信息说的是那件事吗** |
|---|---|---|---|---|
| **F1** · AC-1 | `FieldTreeBuilder` 去掉 `RETIRED_TAB_TYPES` 过滤 | ✅ md5 变 | ✅ 红 | ✅ `availableSources 里仍出现已退役的页签类型…实际命中=[MATERIAL_BOM→零件, MATERIAL_BOM→外购件]` |
| **F2** · AC-2/3/10 | `src.semantic` 恒为 `null` | ✅ | ✅ 红 ×3 | ✅ `semantic='TREE' 的数据源应恰好 1 个…实际=[]`；`没有任何 semantic='MATERIAL_ELEMENT' 的源`；AC-10 的 `semantic 应为 TREE，实际=null` |
| **F3** · AC-3 | 去掉 `if (!graphDialect.equals(tv.dialect)) continue;` | ✅ | ✅ 红 ×2 | ✅ 命中的是**阳性对照**：`QUOTE 与 COST_DETAIL 的 availableSources 完全相同 ⇒ dialect 入参很可能没被消费` |
| **F4** · AC-29 | `BuilderService` 的 `isTreeTab` 恒 `false` | ✅ | ✅ 红 | ✅ `AC-29①：…保存应成功 200…实际=400 INSPECT_BLOCKED` |
| **F5** · AC-19 | `SemanticCompiler` 别名改用 `col.fieldName` 派生 | ✅ | ✅ 红 | ✅ `AC-19 改名后：保存应成功，实际=409 IMPACT_CONFIRM_REQUIRED, removedColumns:["_物料与元素BOM_组成含量"]`<br>🔑 **这条顺带把 §7.2 坐实了**：viewColumn 一旦跟着 fieldName 走，改名就**真的**会产生 409 —— 说明 AC-19③ 描述的是「viewColumn 跟随 fieldName」那种系统的行为，与 AC-19④ 互斥 |
| **F6** · AC-20 | 字段组装里跳过 `loss_rate` 一列 | ✅ | ✅ 红 ×2 | ✅ `缺失=[loss_rate]（用户在配置器里配不出这些列）…期望业务列 12 列，实际 11 列` |
| **F7** · E2E | 前端把「数据源」下拉整块渲染注释掉 | ✅ | ✅ **两个 spec 都红** | ✅ `② 应存在「数据源」下拉（data-role="builder-source"）` / `AC-115④: 「数据集」必须排在「数据源」之前` |
| **F8** · AC-30 | 去掉 B-23 的 `priceGroupNodeId` 去重过滤 | ✅ | ✅ 红 | ✅ `PRICE 组里出现了重复 groupKey=[FUNC_ELEMENT_PRICE]…实际组=[ELEMENT_BOM[MAIN], FUNC_ELEMENT_PRICE[PRICE], FUNC_ELEMENT_PRICE[PRICE]]` —— 与 B-23 修复前主线实测的 3 组形态**逐字一致** |

**还原自检**：8 轮全部逐字节还原（md5 与备份一致）；全工程 `FALSIFY-INJECT` 残留扫描 **0**。

> ⚠️ **F6 首轮曾以「Could not load class」变红 —— 那是 harness 故障不是用例生效**（`target/` 被 8089 并发改写）。
> 按 §4.4「看失败信息，不只看退出码」查明后重跑才拿到真结论。**这一次差点被记成「用例有效」。**

## 10. 第二批对既有 E2E spec 的改写（用户 2026-09-06 裁决由本任务改）

两条 `task-260819` 的**已验收** E2E 用例断言的正是本任务移除掉的「页签类型」下拉，必然变红。
🚨 **这不是回归，是它们编码了一条已被用户推翻的需求。判红时不得按回归归因。**

| spec | 原断言 | 改成什么 |
|---|---|---|
| `cpq-frontend/e2e/sql-view-builder.spec.ts` | `AC-25①: 页签类型下拉含6项（主件/材质元素/零件/外购件/费用类/BOM 树）` | 断言**新形态**：① 面板内「页签类型」四字消失；② 「数据源」下拉存在；③ 选项**全部来自服务端 `availableSources`**（运行期现比，🚫 不写死清单）；④ 不含「零件/外购件」；⑤ **label 无重复** |
| `cpq-frontend/e2e/task260819v9-dataset-selector.spec.ts` | `AC-115④: 「数据集」必须排在「页签类型」之前`（`dsBeforeTab`） | 🚫 **布局约束保留，只换参照物**：「数据集」必须排在**「数据源」**之前。用户当初要的是「先选数据集、再选源」的**顺序**，该语义一字未变 |

**⑤「label 无重复」为什么比「不含零件/外购件」更有分辨力**：
`BOM 树`/`零件`/`外购件` 三个坐标**共用锚点 `MATERIAL_BOM`**，label 全都是「物料BOM」——
退役过滤一旦失效，下拉里会冒出**三个「物料BOM」**，而「零件/外购件」这两个字**根本不会出现在 label 里**。

**连带改动（机械必需，非扩范围）**：`sql-view-builder.spec.ts` 的共享 helper
`createComponentAndOpenBuilderTab` 原本用「页签类型」下拉选类型，且其建组件的选择器
（`新建|新增` 按钮 + `input[placeholder*="名称"]` + `确定|保存`）**与真实 UI 对不上、在真机上从未走通过**
（本次实测 `fill` 直接 timeout）。
⇒ ① 入口动作换成 `task260819v9-dataset-selector.spec.ts` 里**已被真机验证过**的那套
（选目录 →「新 建」→ `input[placeholder*="投料成本表"]` →「创 建」→「取数配置」）；
② 第二参数由「页签类型名」改为「数据源名」（`主件→物料`、`材质元素→物料与元素BOM`，同一坐标换名字）。
**该文件其余 task-260819 用例的断言一个字没动。**

两个 spec 内均已加改写留痕注释（原断言 / 失效原因 / 改成什么）。

## 11. 第二批执行结果

**跑测命令**（🚨 在**隔离副本**里跑，与 worktree 的 8089 dev server 零争抢；拷贝后已 `diff -rq` 逐文件确认一致）：

```bash
# 1) 拷贝并验明正身
rsync -a --delete <worktree>/cpq-backend/{src,pom.xml,mvnw,.mvn} <scratch>/isobuild/
diff -rq <worktree>/cpq-backend/src <scratch>/isobuild/src        # 必须无输出
# 2) 跑
cd <scratch>/isobuild
./mvnw -o test -Dtest='DataSourceCatalogAcTest,FieldPanelColumnsAcTest,IdentifierColumnGateAcTest,\
BuilderStateContinuityAcTest,PriceGroupDuplicationAcTest,Sec34PriceStrategyTest'
```

```
[INFO] Tests run: 5, Failures: 0, Errors: 0 -- Sec34PriceStrategyTest（AC-30④ 阴性对照）
[INFO] Tests run: 3, Failures: 0, Errors: 0 -- AC-10/AC-19
[INFO] Tests run: 7, Failures: 0, Errors: 0 -- AC-1/2/3
[INFO] Tests run: 4, Failures: 0, Errors: 0 -- AC-20
[INFO] Tests run: 1, Failures: 0, Errors: 0 -- AC-29
[INFO] Tests run: 1, Failures: 0, Errors: 0 -- AC-30
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**E2E**（`PW_BASE_URL=http://localhost:5178` → 临时 vite（worktree 源码）→ `VITE_API_TARGET=8089`）：

```
AC-1(task-260904)①③④  1 passed (58.0s)
AC-115（改写后）        1 passed (57.1s)
```

🔑 **E2E 探活验明正身**（testing.md §4.2：只看 200 会探到别人的实例）：

| 端口 | `availableSources` | `availableTabTypes` |
|---|---|---|
| 8081（主仓 master） | **MISSING** | `[主件, 材质元素, 零件, 外购件, 费用类, BOM]` |
| 8089（本 worktree） | **11** | `[主件, 材质元素, 费用类, BOM]` |
| **5178（E2E 实际打到的）** | **11** | `[主件, 材质元素, 费用类, BOM]` |

⇒ 5178 确实打在 worktree 后端上；同时这张表本身就是 **A/B 对照**，证明退役过滤是本次改动带来的。

## 12. 第二批夹具残留自检（共享 dev 库）

```
component(t260904_)           0
template(t260904_)            0
orphan_component_sql_view     0
orphan_template_component     0
E2E 遗留(SQLVB-E2E-/V9T-)     0   ← 5 个由本次 E2E 产生，已按主键精确删除（删前预检：被模板引用 0）
semantic_tab_view             45  ← 一行未动（B-2 红线）
semantic_tab_view 非 ACTIVE    0
semantic_tab_view_node        46  ← 未动（AC-30 修法禁区②：不改 V413 种子）
admin                         ACTIVE
```

🚫 全套用例**无 TRUNCATE / DROP / 无 WHERE 的 DELETE / 清库 / 全局配置重置**；
每条 DELETE 的命中面被「自建 id / 自建 `customer_no` / `t260904_` 前缀」限死，写在 `@AfterEach`（等价 finally）。
