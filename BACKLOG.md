# BACKLOG

> 项目待办池（推迟/未排期任务的单一登记处）。
> **优先级**：`P0` = 阻断或须尽快；`P1` = 重要、计划内后续期；`P2` = 增强 / 锦上添花 / 待评估。
> **格式**：每条一个 `### [BL-NNNN] 标题`，含 `优先级 / 来源 / 状态 / 登记日期 / 背景 / 范围 / 依赖 / 验收要点`。
> 完成后把条目移到文末「## 已完成」并标 `[DONE 日期]`，不要删除（保留追溯）。

---

## P0

### [BL-0069] `mat_*`（V44）废弃表「已断供仍被读」故障族 —— 5 条实证失效路径
- **优先级**：P0（其中漂移检测假阴性破坏的是安全属性，最高）
- **来源**：2026-07-21「废弃表检查」会话——用户就新库部署脚本为何仍建 `mat_*` 表发起排查，三路审计（代码消费方 / 视图配置 / 数据痕迹）+ 技术总监亲验 SQL + 临时后端空库实测。
- **状态**：TODO（未排期）
- **登记日期**：2026-07-21
- **推迟原因**：本会话任务是部署脚本澄清，非修复；这批是代码缺陷（非脏数据），修复牵动漂移检测/报价加载/料号版本三块，需独立立项。**但严重性已超出常规 backlog，建议尽快排期**。
- **背景（核心事实）**：`mat_*` 系表**自 2026-06-02 起停止写入**（实测 max(created_at)：`mat_bom`/`mat_part`=06-02，`mat_fee`/`mat_plating_*`/`mat_customer_part_mapping`=05-20~22，`*_staging`=05-15）；新数据全部经 V6 路径写 `material_*`/`element_*`/`unit_price`（今日 07-20 仍在写）。**凡仍 READ `mat_*` 的功能，读到的都是 6 月初的冻结快照；6 月后新增料号在老表中根本不存在。** 现役 82 个料号中 `mat_part` 只有 2 个（97.6% 盲区），`mat_part.unit_weight` 对全部 264 行均为 NULL。
- **⚠️ 与既有条目关系**：这是 [[BL-0035]]（生产料号 BNF 重定向）与 [[BL-0036]]（mat_part 退役）在**运行时层面的实证后果清单**——那两条是"架构级重构"视角，本条是"哪些用户可见功能此刻已错"视角。修 BL-0036 可根治，但周期以多周计；本条的 P0/P1 子项应先做点状止血。
- **已验证的 5 条失效路径**（技术总监亲验 SQL + 07-19 构建 jar 空库实测）：
  1. **[P0·安全属性] 漂移检测假阴性** —— `DriftDetectionService.collectReferencedVersions()`(`:121`) 写死采集 `mat_process`/`mat_fee`/`mat_plating_fee` 三张冻结表。实测 **7 月 13 张报价单 `referenced_versions` 全为 NULL（13/13）** → `detect()` 恒返 `hasDrift=false`。系统**主动告诉用户"基础数据未变化"，实则查的是两个月没动的表**，静默、无日志、无告警。修复面小（改采集源为 V6 版本列），价值最高。
  2. **[P1] 报价卡片客户料号三字段恒空** —— `QuotationService.loadLineItems`(`:2491`) 读 `mat_customer_part_mapping` 的 customer_part_name/product_no/drawing_no。实测**现役 35 报价行 0/35 命中 V44，同键 V6 `material_customer_map` 命中 19/35**。讽刺：紧邻的 `hfPartInfo` 块（`:2540`）**有** `internal_material→material_master` fallback，客户映射块**没有**，一行之隔的不对称。
  3. **[P1] 料号版本锁定恒为默认 2000** —— `mat_customer_part_mapping.current_version` 冻结致 `part_version_locked` **35/35 = 2000**。空库实测 `GET /part-version/{cpn}/{hf}` 返 `currentVersion:2000`——**2000 是无匹配行时的设计默认值**，故 mcm 冻结/为空时每个料号静默拿 2000，使下游版本切换/版本抽屉即便修好也无数据可用。
  4. **[P2·硬失败可见] 料号版本切换 / 版本抽屉** —— 切到 v>2000 抛「版本不在历史中」；`mat_part_version_log` 冻结 44 行。空库实测 switch→404（非 500，可见错误）。
  5. **[P2] 元素价格下拉选项陈旧** —— `available-elements` 读 `mat_bom`(bom_type=ELEMENT) 返 20 个冻结元素名，V6 新增 4 个元素不在列表。空库实测返 `[]`（graceful）。
     > ✅ **2026-07-23 已闭合**：由 task-0722 元素价格策略交付（已合并 master）——`ElementPriceService.listAvailableElements()` 已从废弃的 `mat_bom` 改读 `element` 主表 ACTIVE 元素。**本子项勾销**，其余 1~4 子项不受影响、仍待排期。
- **两个必须澄清的边界（避免误导修复工时）**：
  - **报价/核价渲染主链路是干净的**：全库扫描组件 `data_driver_path`/`fields`/`formulas`/`excel_columns` + `component_sql_view.sql_template` + 模板快照，**live config 零引用 `mat_*`**（唯一违规 `dp_view`/COMP-0078 电镀费未挂任何模板，爆炸半径 0）。损害全在**从未迁移的辅助元数据/版本功能**，非渲染主线。
  - **`SchemaContext.defaultContext()`(`:143-151`) 的 10 个 BNF 逻辑名全指向 V44**（元素BOM/来料BOM/组成件BOM→mat_bom、生产料号→mat_part、工序资料→mat_process、料号费用→mat_fee、客户料号对应→mat_customer_part_mapping 等），但配置层**点号语法 `逻辑名.列` 实际引用 0 命中**（11 个配置载体全扫过）——是**潜伏陷阱**（谁新配一个 `元素BOM.xxx` 字段即静默读冻结表），非正在发生的故障。此即 [[BL-0035]] 的运行时现状。
  - `v_costing_summary_full` 恒 0 行**不能全归因 mat_***：其主轴 `costing_summary` 空表根因是"本开发库无人创建过核价汇总单"（`CostingSummaryService` 有正常 persist 路径），非写入链路损坏；6 个 PUBLISHED 模板（3 default）各绑 15~18 列于它，功能被使用时取数是否正常**尚未实测**，另行确认。
- **对新库部署的影响**：新库 `mat_*` 全空，故上述 1/2/3/5 在生产会**原样复现且更彻底**（漂移恒 false、客户料号恒空、版本恒 2000、`v_part_material_recipe` 直接返 0 行）。**这批是代码缺陷会带到生产，不是老库脏数据。** 部署脚本仍须建这些表（规范 `docs/方案制定前必读.md:69`「V44 老表保留、可读不可写」+ 4 视图硬依赖 + 33 条 `basic_data_config` 仍指向它们），删表会直接 500——退役是 BL-0036 的多周工程，非部署可顺手做。
- **范围**：子项 1（漂移采集源迁 V6）优先独立止血；子项 2（客户料号块补 V6 fallback，抄 hfPartInfo 现成范式）；子项 3/4/5 依赖 mcm/version_log 迁 V6，与 [[BL-0036]] 合并推进更稳。
- **依赖**：子项 1/2 可独立做（V6 表已有等价数据）；子项 3/4/5 依赖 [[BL-0036]] 的 mcm/version_log 迁移。
- **预估规模**：子项 1 = S（1-2 天）；子项 2 = S；子项 3/4/5 随 [[BL-0036]] = L。
- **验收要点**：①漂移检测对 V6 基础数据变更能真报 `hasDrift=true`，7 月存量报价单 `referenced_versions` 非空；②报价卡片客户料号三字段对现役料号非空；③版本锁定反映真实版本非恒 2000；④回归无静默取空/取零。

---

### [BL-0097] 组件 `$view` 查询报错会毒化整个事务 → 整张卡片算值失败，且局部 `catch` 兜不住、错误原文丢失
- **优先级**：P0（单点 SQL 错误 → 整张产品卡片不可用；且故障原因对用户和排查者**完全不可见**）
- **来源**：2026-08-02 用户报「QT-20260802-0049 显示『该料号卡片数据待重算』」，技术总监逐层排查定位
- **状态**：TODO（未排期）
- **登记日期**：2026-08-02
- **故障链（已实证 + 代码注释佐证）**：
  1. 某组件（尤其 `tab_type='BOM'` 树页签）的 `$view` 查询在 PostgreSQL 层报错（列名/表结构不匹配等）；
  2. `componentDriverService.expand` 抛出的异常**被 Java 侧 catch 住并置空条目** —— 看起来"已处理"；
  3. **但 PostgreSQL 已把当前事务标记为 aborted**，此后同一事务内的**每一条 SQL 都失败**（`current transaction is aborted`）；
  4. 算值后续步骤全部倒下 → `buildCardValues` 抛异常 → 返回 null → 落 `__cardValueFailed` 哨兵；
  5. 前端只显示一句「该料号卡片数据待重算」，**不含任何错误原文**。
- **代码已记载该陷阱但未根治**：`CardSnapshotService:2179-2183` 注释（2026-07-22 真实事故）明确写了
  "异常会在此处被 catch 且置空条目——但 PostgreSQL 层面该 SQL 错误已把当前事务置于 aborted 状态，
  调用方紧随其后的同事务 SQL 也会失败"。当时的处置是"树组件跳过平铺展开"，属点状规避，
  **未解决"局部 catch 兜不住事务毒化"这个通用问题**。
- **危害**：
  - **爆炸半径不成比例**：任意一个组件的 `$view` 有一处列名写错 → 整张卡片 9 个页签全部算不出；
  - **排查成本极高**：报价侧哨兵**不带 `__errorMsg`**（只有核价树整单渲染失败才走 `failedSentinelWithError`），
    用户和排查者都看不到是哪条 SQL、哪个视图出错，只能靠翻后端控制台日志（dev 环境日志还不落文件）；
  - **"改配置就好了"具有迷惑性**：本次用户把公式里的跨组件引用换成常量后问题消失，
    实际只是绕开了那条报错 SQL，**根因视图仍然是坏的**，换个公式又会复现。
- **范围（三选一或组合，需架构评估）**：
  1. **Savepoint 隔离**（推荐方向）：`expand` 每个组件的 `$view` 查询前设 SAVEPOINT，出错回滚到 SAVEPOINT，
     事务不被整体毒化，后续组件照常算 —— PostgreSQL 原生支持，改动集中在 driver 层；
  2. **独立事务**：`$view` 查询走 `REQUIRES_NEW`，与主算值事务隔离（注意与既有 `REQUIRES_NEW` 用法的一致性，
     参见 task-0712 编排落 Resource 层的先例）；
  3. **至少让错误可见**（无论选哪条都应做）：报价侧也走 `failedSentinelWithError`，
     把首个 SQL 错误原文落进 `__errorMsg`，前端显示「渲染失败: 原文」而非通用的「待重算」
     —— 核价侧 BL-0030 已有此能力，报价侧缺失。
- **依赖**：无。触及 `CardSnapshotService` / `ComponentDriverService`，属报价渲染核心链路（三大基线），**须走 architect 评估 + E2E**
- **预估规模**：M（方案 3 单独做是 S）
- **验收要点**：①故意把某组件 `$view` 写错一列 → 该页签显示错误、**其余页签仍正常渲染**；②哨兵带错误原文且前端可见；③不再出现 `current transaction is aborted` 连锁。

---

## P1

### [BL-0098] FORMULA 字段未显式绑定公式时按「位置」回退匹配 → 配置静默漂移
- **优先级**：P1（静默改变计算结果，无报错无提示）
- **来源**：2026-08-02 排查 QT-20260802-0049 时发现，用户实测佐证
- **状态**：**[DONE 2026-08-03]** —— 分支 `fix/bl-0098-formula-bind-by-id`，13 个提交，实施记录见
  `dev-docs/repair-0803-BL0098-公式绑定改绑ID/实现计划.md`。落地摘要见本条末尾「✅ 交付记录」。
- **登记日期**：2026-08-02
- **背景**：`FormulaCalculator.resolveFormula` 解析 FORMULA 字段用哪条公式时有 4 级回退：
  ```
  0. 显式 formula_name
  1. 模板级 formula_assignments[字段下标]
  2. 字段名 == 公式名
  3. positional fallback ← 按该字段在 FORMULA 字段中的相对位置，取 formulas[同位置]
  ```
  第 3 级是历史兼容设计，**副作用是配置会静默漂移**。
- **实证（当前生产配置就踩在上面）**：`COMP-0157「物料」`的 `材料成本` 字段是 FORMULA 类型但
  `formula_name` 为空、也没有同名公式，于是按位置匹配到 `formulas[2]` = **「银点材料成本公式」**：

  | FORMULA 字段（第 N 个） | formulas 数组（第 N 条） |
  |---|---|
  | 来料回收费 (0) | 来料回收费取值公式 (0) |
  | 来料财务费 (1) | 来料财务费取值公式 (1) |
  | **材料成本 (2)** | **银点材料成本公式 (2)** ← 隐式绑定 |

  用户本人认为"这条公式没有被字段绑定"，实际它正在决定「材料成本」列的算法。
- **危害**：只要有人在公式列表里**插入一条新公式、删除一条、或调整顺序**，所有未显式绑定的 FORMULA 字段
  都会**静默换成另一条公式** —— 不报错、不提示、UI 上也看不出来，只有算出来的钱变了。
---

#### 🔑 2026-08-03 方案变更：改为「公式绑稳定 ID」（用户裁决）

> 原范围是「把按位置猜出来的公式名**固化**进 `formula_name`」。2026-08-03 讨论 task-0803 时用户提出
> **「公式绑定可以绑公式 ID 吗，避免乱猜公式」**，经实测论证后**采纳**。原范围作废，以本节为准。

**为什么换方案** —— BL-0098 其实是两个独立问题捆在一起：

| 问题 | 现状 | 固化 `formula_name` | 绑 ID |
|---|---|---|---|
| **A「没绑就按位置猜」** | 4 处受害，插/删/调序一条公式就静默换算法 | ✅ 能解 | ✅ 能解 |
| **B「改公式名就断链」** | 0 处受害但**机制活着**：绑了名字找不到 → `FormulaCalculator:1411-1412` 该字段**整个不进计算列表** → 那一列静默不出值、不报错；条件公式同理（`:1405` 静默丢规则） | ❌ 不解（名字仍是主键） | ✅ 根治 |

裁决理由：**名字是用户随时可改的东西，拿它当主键早晚出事**；且「改名时级联更新所有引用」这个替代做法的失败模式是**静默的**（漏一处不报错，只是那列没值），可靠性与「引用永远有效」不是一个量级。

**实测数据（2026-08-03 核实 `cpq_db_0724`，实施时直接用，不必重查）**：

| 项 | 实测 |
|---|---|
| 公式对象落库的键 | **只有 `name` / `expression` / `result_type`** —— 39 个对象**全部无 `id`、无 `key`** |
| 前端 `FormulaItem.key` | `formula-${Date.now()}-${Math.random()}`，**纯 React 列表临时键，落库前丢弃**，不是稳定标识 |
| 靠位置回退的字段 | **4 处**（全在 ACTIVE 组件）：`COMP-0032`「物料」BOM/材料成本→银点材料成本公式；`COMP-0157`「物料」BOM/材料成本→银点材料成本公式；`COMP-0090`「材料成本」材质元素/材料成本→公式1；`COMP-0049`/公式测试→**位置越界，该列算不出值** |
| 靠同名匹配的字段 | 0 |
| `formula_name` 指向不存在公式（已断链） | **0**（机制活着但暂无受害者） |
| 组件内公式重名 | **0**（名字今天确实唯一） |
| 模板级 `formula_assignments` | **101 条 `template_component` 全为空**，第 1 级回退从未被使用 |
| 🚨 **公式定义的承载点** | **3 个**：`component.formulas`（正本，15 个组件）+ `template.components_snapshot`（11 个模板）+ `quotation.submission_snapshot`（2 张已提交单）。`template.formulas` 与 `template_component.fields_override` 均为 0 |

- **新范围**：
  1. **造稳定 ID**：给公式对象补一个不可变 ID 字段。**作用域 = 组件内**（不需要全局唯一），因此组件复制 / 导入 bundle **原样带走即可**，无需重新生成、无跨环境冲突。
  2. **迁移回填**：给存量 39 个公式对象生成并写入 ID。🚨 **必须同时覆盖上表 3 个承载点**——只动 `component.formulas` 就是 [[AP-39]] 重演（当年 V190~V193 只动 `component.fields`，漏了所有引用方 jsonb 列，PUBLISHED 模板 snapshot 残留老散字段）。
  3. **字段绑定改存 ID**：FORMULA 字段新增 ID 引用键；`formula_name` 降级为**展示冗余 + 存量兼容读**，不再是解析主键。条件公式 `conditional_formula.rules[].formula` / `.default` 同步改绑 ID。
  4. **求值解析改口径**：`FormulaCalculator.resolveFormula` 改为 **ID 优先**；**砍掉第 3 级 positional fallback**（或降级为仅存量兼容且打 warn）。
  5. **存量 4 处固化**：按当前位置解析出的公式，固化成 ID 绑定。复用已在 master 的 `FormulaCalculator.resolveFormulaNameForField`（commit `2d12bde2`）作为解析口径，**不要另写一套**（口径漂移就是 BL-0098 换个层面重演）。
  6. **UI 显式化**：字段配置必须显式选公式（存 ID，界面照常显示名字）；未绑定时明确提示，不再静默猜。
- **已在 master 的脚手架（commit `2d12bde2` repair-0803 B3）**：`FormulaCalculator.resolveFormulaNameForField()` 已对外暴露解析口径，`FormulaNameResolutionTest` 已把 4 级回退语义与 BL-0098 危害钉成测试。⚠️ **但修复本身一件没做**——该方法在整个 `src/main` **无任何生产调用方**，只有测试在调；`ComponentService` 里零固化逻辑。属「手术台备好了，刀没下」。
- **依赖**：无。改动触及 `FormulaCalculator` + `ComponentService` + 组件管理前端 + 一次性迁移，**不触发 AP-44**（无 `field_type` 变动）
- **预估规模**：M
- **验收要点**：①存量 4 处全部绑上 ID，且绑定结果 = 修复前实际生效的公式（值不变）；②**改公式名后各字段算法不变**（B 问题根治的核心判据）；③调整公式顺序 / 插入 / 删除后各字段算法不变；④3 个承载点的 ID 一致，模板 snapshot 与已提交单据快照均已回填；⑤新建 FORMULA 字段未选公式时有明确提示，不再静默按位置猜。
- **下游影响**：**task-0803（BOM 页签父子取值公式）已把本条列为实施前置**（2026-08-03 用户裁决两者**严格串行**）。

---

#### ✅ 交付记录（2026-08-03）

**🚨 首先纠正本条此前的两处误判**（含 `dev-docs/repair-0803-公式计算BUG修复/修复方案.md §1.2` 的原始诊断）：

1. **「4 处按位置猜」实为 1 处。** 统计 SQL 漏了排除条件公式字段。`COMP-0032`/`COMP-0090`/`COMP-0157` 的「材料成本」**是条件公式字段**（`conditional_formula`），`collectFormulaFields` 先判条件分支（`FormulaCalculator:1396-1409`），`resolveFormula` 对它们**从未被调用**，位置回退根本轮不到。真正靠位置回退的只有 `COMP-0049`「物料与元素BOM」的「公式测试」，且它位置越界、本就解析不到。
2. **「COMP-0157 材料成本被按位置绑到银点材料成本公式」是误判。** 银点材料成本公式是那条条件公式**显式配置的 `default` 分支**，用户自己配的。之所以没人发现，是因为 `formulas[2]` 恰好也是它——两条路径答案相同，纯属巧合。

**实测口径（`cpq_db_0724`，2026-08-03）**：22 个 FORMULA 字段 = 3 个条件公式字段 + 19 个普通字段（18 已显式绑定 + **1** 靠位置回退）。

**范围扩大**：条件公式按名字引用 `rules[].formula` 与 `default`，同样有「改名即静默丢规则/丢默认分支」的问题 B，且**比普通字段更阴险**（列还有值，只是悄悄换了分支）。用户裁决一并修，故本条实际交付覆盖两类绑定。

**落地内容**：
1. 公式对象补不可变 `id`（作用域=组件内）；`resolveFormula` 插入 `formula_id` 最高优先级，原 4 级回退**原样保留**给 13 张不迁移的老冻结单兜底。
2. 条件公式新增 `rules[].formula_id` / `default_formula_id`，`condRefFormula` 按 id→名字解析；绑了 id 查不到返 null 不回落名字。
3. `FormulaIdBinder`：补 id / 固化绑定 / 用 id 反查刷新名字冗余 / 强制显式绑定校验。固化口径复用 `FormulaCalculator`，不另写一套。
4. 迁移 **V375**（公式补 id + 显式绑定翻译）+ **V376**（条件公式引用翻译），均为纯映射零推断，覆盖 `component.*` + `template.components_snapshot`；按裁决 D2 **不动** `quotation_view_structure`（13 张老单）与 `quotation.submission_snapshot`（只读留证）。
5. 一次性固化端点 `POST /api/cpq/admin/formula-binding/consolidate`（`dryRun` 出清单）。
6. 前端配置侧（字段下拉 + 条件公式抽屉）与求值侧（`resolveFormulaForField` + 条件解析）全部改绑 id，与后端镜像；`newFormulaRow` 新建即生成 id。
7. 保存/导入强制显式绑定。

**过程中另抓到 3 个会让修复形同虚设或引入新问题的点**：
- 🔒 **端点漏鉴权**：本项目 RBAC 是 **opt-in** 的（`RoleFilter:65`：无 `@RoleAllowed` 则 `skip auth check too`），新端点初版无 token 可调（实测返 200）。已补 `@RoleAllowed({"SYSTEM_ADMIN"})`。**新增任何 `/api/cpq/**` 资源都必须显式加注解，否则默认全开。**
- **`CardSnapshotService.buildCardStructure` 是白名单逐键搬运**，漏搬 `formula_id` → 此后新建的每张报价单冻结结构都拿不到 id，求值永久退回猜。已补。
- **`validateFormulas` 会挡住改名**：它校验 `formula_name` 必须存在，而 UI 改公式名时引用处的名字冗余不跟着变 → 保存 400。已加 `refreshNameRedundancyFromIds` 并重排调用顺序。
- **SQL 空数组陷阱**：库里 79 个 tab 的 `formulas=[]`、10 个 `fields=[]`，`jsonb_agg` 对空集返回 NULL 会把 `[]` 写成 JSON null（渲染层读到即崩）。两个迁移的内层 `jsonb_agg` 全部 `COALESCE(...,'[]'::jsonb)`。

**验证证据**：
- 后端纯 JUnit **38 passed**（`FormulaIdBinderTest` 18 / `FormulaBindByIdTest` 15 / `FormulaNameResolutionTest` 5，后者锁定原 4 级回退语义未被污染）；前端 vitest **190 passed**；`tsc` 0 错误；改动的 5 个前端文件 Vite transform 均 200。
- **值不变**：22 个 FORMULA 字段迁移前后生效的公式逐条 `diff` 一致。
- **改名不断链（端到端）**：改公式名 + 字段留旧名冗余 → `PUT /components/{id}` 返 **200**（原为 400），落库 `formula_id` 不变、名字冗余自动刷新。数据层对照实验：id 链路仍指得到，名字链路已断（= 改造前行为）。
- 强制校验：未绑定 → 400「以下公式字段未绑定公式…：未绑定列」；显式绑定 → 200 且自动补 id。

**遗留待用户处置**：`COMP-0049`「物料与元素BOM」（ACTIVE，被 2 个模板引用）有 1 个 FORMULA 字段「公式测试」但组件**0 条公式**，该列本就算不出值。强制校验开启后该组件在 UI 上不可保存，需先给该字段选公式或改字段类型。这是预期行为（暴露坏配置），但用户首次碰到会意外。

### [BL-0099] 跨组件引用 token 把「列名」写进了 `tab_name` 字段
- **优先级**：P2（当前被回退链兜住，未造成可见故障，但是定时炸弹）
- **来源**：2026-08-02 排查 QT-20260802-0049 时发现
- **状态**：TODO（未排期）
- **登记日期**：2026-08-02
- **背景**：`formulaSerialize.ts:642-647` 构造跨组件小计列引用时：
  ```js
  { type: 'component_subtotal',
    value: col,            // "税率"
    tab_name: col,         // "税率"  ← 应为页签名（如「产品」），却放了列名
    component_code: td.alias }
  ```
  后端 `FormulaCalculator.appendToken` 的 6 级回退里，第 2 级会拼 `tab_name + "#" + colName`，
  即 `"税率#税率"` —— **永远命中不了**。
- **为什么现在没出事**：第 1 级 `component_code + "#" + colName`（`"产品#税率"`）能命中，
  且 `CardSnapshotService` PASS1 同时写入了 `cid#col` / `code#col` / `tabName#col` 三种键，所以兜住了。
- **风险**：一旦 `component_code` 缺失或 alias 变化导致第 1 级落空，就会连续跌到第 3/4 级
  **取整个组件的小计合计**（例如「产品」组件 = 管理费 + 税率之和），静默算错且极难发现。
- **范围**：修正 token 构造，`tab_name` 填页签名；同时补一条前后端共用的 token 形状单测。
- **依赖**：无
- **预估规模**：S
- **验收要点**：①新建的跨组件引用 token 中 `tab_name` 为页签名；②存量 token 兼容（回退链不变）；③单测覆盖四种 key 形状。

### [BL-0100] 变更日志是「只读空壳」——查询/导出/定时清理俱全，**唯独没有任何写入方**
- **优先级**：P1（可观测性 + 追责能力缺失；不影响功能，但每次配置类故障都要付出数倍排查成本）
- **来源**：2026-08-02 排查 [[BL-0097]]（「卡片数据待重算」）时被此缺口直接卡住，技术总监顺藤查证
- **状态**：TODO（未排期）
- **登记日期**：2026-08-02
- **现状（已逐项查证）**：
  | 组成部分 | 状态 |
  |---|---|
  | 表 `basic_data_change_log`（24 列：`field_changes` jsonb / `old_value` / `new_value` / `change_source` / `affects_calculation` / `importance` …） | ✅ 设计完善 |
  | 查询 API `ChangeLogService.search()` | ✅ 有 |
  | 导出 API `ChangeLogService.export()` | ✅ 有 |
  | REST 端点 `ChangeLogResource` | ✅ 有 |
  | 定时清理 `ScheduledTaskService.cleanupChangeLog()`（CL-RETENTION-07，每月 1 号 03:00 删 5 年前数据） | ✅ 有 |
  | **写入方** | ❌ **全工程零 `INSERT`、无实体类、`ChangeLogService` 只有 `search`/`export` 两个方法** |
  | 表中数据 | **0 行** |

  即：**系统里有一个每月定时任务，专门清理一张从未被写入过任何数据的表。**
  `global_variable_change_log` 有一处 INSERT（`GlobalVariableService:664`），但该表同样 **0 行**（路径未被触发过）。
- **危害**：
  1. **比没有更危险** —— 前端若有「变更日志」入口，查出来恒空，给人"已有审计"的错觉；
  2. **配置类故障无法追溯**：本次 [[BL-0097]] 排查中，`COMP-0157「物料」`与模板在 `2026-08-03 02:04:52` 被改过
     （正卡在故障单算值 02:03:14 与正常单算值 02:05:28 之间，是判断因果的关键证据），
     但**改了哪个字段、从什么改成什么，库里查不到任何记录**，只能靠用户口述回忆；
  3. 组件/模板配置直接决定报价金额的算法，**改动无留痕 = 算错钱无法追责、无法回滚参考**。
- **范围（建议分两步）**：
  1. **第一步：接入配置类实体的写入**（价值最高，直接解决排查困境）——
     `component`（`fields` / `formulas` / `excel_columns` / `data_driver_path` / `row_key_fields`）、
     `template`（`components_snapshot` / 绑定关系）、`component_sql_view`（`sql_template`）。
     挂载点为各自 `@Transactional` 保存入口（如 `ComponentService.update`，`:342`）。
     表结构无需新设计，现有 24 列足够（`field_changes` 存 jsonb diff、`affects_calculation` 标记是否影响算钱）；
  2. **第二步：补 `basic_data` 与全局变量的写入**，并验证 `global_variable_change_log` 那条既有 INSERT 是否真能触发。
- **实现注意**：
  - 组件保存是**整份 jsonb 覆盖**（`fields`/`formulas` 是数组），需要做**字段级 diff** 才有价值，
    只记"整份旧值→整份新值"等于没记（一次改动几十 KB，看不出改了哪一列）；
  - 写日志**不得阻断主流程**（保存失败优先级高于留痕），建议 try/catch 包裹 + WARN；
  - 注意 [[BL-0097]] 的教训：**日志写入若与主保存同事务且 SQL 出错，会毒化整个事务** —— 应评估独立事务或 Savepoint。
- **依赖**：无（表与查询侧已就绪，纯补写入）
- **预估规模**：M（第一步 S~M，第二步 S）
- **验收要点**：①改一次组件字段 → 日志有记录，能查到「谁 / 何时 / 哪个字段 / 旧值→新值」；②变更日志页面不再恒空；③写日志失败不影响配置保存成功；④定时清理任务从此有实际清理对象。

### [BL-0101] 前端页签算序不认 `component_subtotal` 依赖 —— 前后端建图口径不对齐
- **优先级**：P2（当前无可见故障，但与 QT-1743 同型，属静默算错风险）
- **来源**：2026-08-03 repair-0803（QT-20260803-0052 假环）修复时发现
- **状态**：TODO（未排期）
- **登记日期**：2026-08-03
- **背景**：后端为修 QT-1743（管理费=0）把 `component_subtotal` 跨组件引用并入了页签拓扑依赖
  （`CardSnapshotService` / `ConfigureSnapshotService`），**前端从未跟进** ——
  `crossTabOrder.ts` 只有 `extractSourceRefs`（仅收 `cross_tab_ref`），全工程没有 `extractSubtotalRefs`，
  `QuotationStep2.tsx:960` 据此排序。后端 `CrossTabComponentOrder.java:66` 那句
  「与前端 `extractSubtotalRefs` 对齐」的注释是不实描述（repair-0803 已订正）。
- **暴露方式**：正因为前端不收这类边，QT-20260803-0052 在编辑页看着正常、一到后端渲染就崩——
  同一份配置两边算出的依赖图不同。修复方向相反时，两边会各错各的。
- **风险**：前端 `computeAllFormulas` 若在被引用页签之前算引用方，`component_subtotal` 取到未回填的值 → **前端显示 0**，
  与后端重算结果不一致（用户看到的和存库的不是一回事）。当前未见报障，可能是现网模板的页签顺序恰好正确。
- **范围**：前端补齐列粒度的 subtotal 依赖收集，与后端 `CrossTabComponentOrder.buildComponentDeps` **同规则**
  （引用公式列/整页签合计才建边，引用 INPUT 等零依赖列不建边）；补一组前后端共用的建图对拍用例。
  ⚠️ 不要简单照搬「页签粒度」——那正是 repair-0803 修掉的假环成因，直接搬会让前端也开始报环。
- **依赖**：repair-0803 已合并（后端规则已定型，可作为对拍基准）
- **预估规模**：S~M
- **验收要点**：①同一份 structure 前后端算出的页签序一致；②施耐德BUG2 v1.3 这类含反向引用的模板前端不报环；③对拍用例覆盖「引用输入列/引用公式列/整页签合计」三种形状。

### [BL-0102] 价格策略接管的 `元素单价` 列仍可编辑 —— 改了保存后被静默改回
- **优先级**：P1（用户可见的「改了不生效」，与 2026-08-03「行数据即快照」不变式语义冲突）
- **来源**：2026-08-03 排查「保存后重开数字复原」时顺带查证（spec `2026-08-03-row-data-snapshot-authority-design.md` §7）
- **状态**：TODO（未排期）
- **登记日期**：2026-08-03
- **背景**：`PriceReconciler.reconcileQuotation` 在每次 `saveDraft` 后，对「元素 ∈ 调价清单 ∧ 料号 ∈ 范围 ∧ 策略启用」的行**无条件覆盖** `row_data` 的价格列（`PriceReconciler.java:204-233`），解不出价时直接 `remove` 该键。
  实测 `cpq_db_0724`：`CUST-0001 罗克韦尔` 有 1 条 enabled 策略（2026-08-03 07:23 建）、`material_scope_mode=ALL`、元素清单 `Ag`/`Cu`；8 个组件配了 `element_price_field=元素单价`（COMP-0021/0027/0049/0090/0102/0122/0130/0133）。
- **问题**：价格列被系统接管后，UI 上**仍然是可编辑的输入框**。用户改了或清空后保存，会被策略价悄悄改回 —— 与「行数据即快照、用户定值不可被系统改写」的不变式（2026-08-03 落地）直接冲突。用户无法区分这是 bug 还是设计。
- **范围**：产品裁定后二选一 ——（a）价格列在被策略接管时置灰 + 悬浮提示「由价格调整策略管控（版本 X）」，`__priceLocked` / `__priceVersion` 标记已在传输链路里（`CardSnapshotService` 已透传到 `quoteCardValues` 行上）；（b）允许手工覆盖，归位时跳过被手工改过的格子。**不要保持现状**。
- **依赖**：task-0729（`feat/task-0729-price-adjust`）合并落地后再动 —— 该功能当前仍是主工作区的未提交改动，但**已在共享 dev server 8081 上热加载生效**。
- **预估规模**：S
- **验收要点**：①被策略接管的价格格子行为对用户可预期（要么改不了，要么改了算数）；②不再出现「改了保存后被静默改回」；③与「键存在即权威」不变式不再冲突（或在文档里明确写出这是唯一的合法例外及其理由）。

### [BL-0081] 复制报价单的从属数据与"复制单能否改基础数据"的行为约定
- **优先级**：P1（repair-0729 的收尾缺口，非阻断）
- **来源**：repair-0729 交付评审（技术总监裁决决策 4 + 架构师"对裁决的补充"）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-29
- **背景**：repair-0729 已把「同模板复制」改为**值快照整份继承**并交付（见 `dev-docs/repair-0729-copy报价单数据丢失的问题/`）。本期只补建了 `quotation_view_structure`，其余带 `quotation_id` 的从属表未处理，且继承方案留下一个未定义的用户行为。
- **范围**：
  1. **从属表逐表裁定**：`quotation_component_sql_snapshot`（全库仅 15 行/3 单，样本单无数据，需先确认语义）、`quotation_comparison_config`（比对视图配置，随单复制更符合预期？）、`costing_order`（核价单是否随复制产生 = 业务裁定）。`quotation_approval` / `quotation_withdraw_request` / `import_record` 已确认**本就不该复制**。
  2. **"复制单能否改基础数据"**：继承方案使新单成为"无源之水"——新单 pending 域为空，渲染读的是继承来的冻结 `snapshot_rows`；用户若在新单编辑基础数据，写路径会在**新单** pending 域下新建行，而渲染仍读冻结快照，除非显式刷新；显式刷新又因新单 pending 域缺源单数据而只能拿到正式版本（现由 AP-62 护栏保住旧值不变空）。需要定义用户可见约定（例如复制单的基础数据编辑入口是否置灰 + 提示文案）。
  3. `Quotation.referencedVersions` 在继承方案下**永不重算**，漂移检测基线恒 NULL（与 [[BL-0069]] 子项 1 同源，可合并处理）。
- **依赖**：无（repair-0729 已合并 master）
- **预估规模**：M
- **验收要点**：①每张从属表有明确的"复制/不复制"结论并落到代码或文档；②复制单的基础数据编辑行为对用户可预期，不出现"改了但看不到"或"刷新就没了"的静默态。

### [BL-0082] repair-0729 之前复制产生的存量报价单：数据已不可逆损坏，需盘点与处置
- **优先级**：P1（存量数据正确性；已发生，非潜在风险）
- **来源**：repair-0729 交付（技术总监裁决决策 6）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-29
- **背景**：修复前，凡经「复制」创建并走过向导（点过「下一步」）的报价单，其 `quotation_line_component_data.row_data` 已被渲染态空值覆盖（实测 BOM 6 行 → 1 行、外购件 1 行 → 0 行），**无备份则不可恢复**；且因金额靠重算回填，这些单的金额是偏低的且不报警，理论上可一路走到提交/对外报价。
- **范围**：按 `quotation.source_quotation_id IS NOT NULL` 盘点存量复制单，比对其与源单的页签行数/金额差异，裁定"仅通知 / 批量标记 / 作废重建"。
- **依赖**：无。**预估规模**：S（盘点）+ 业务决策
- **验收要点**：盘点结果有清单；受影响单据的处置有明确结论并执行到位。

### [BL-0077] 手工叶子（add-leaf）插在树中部时，删除该叶子致 `row_data` 错位 / 幽灵重复行
- **优先级**：P1（数据完整性；触发条件明确但后果是静默数据损坏）
- **来源**：repair-0727 测试执行阶段发现（测试工程师 Bug-2），技术总监做归因 A/B 后确认为 pre-existing
- **状态**：TODO（未排期）
- **登记日期**：2026-07-26
- **背景（实测）**：在 BOM 树的"零件"节点下用 `tree/add-leaf` 加一片叶子，若该叶子**不在树尾**，随后触发 `row_data` 物化（repair-0727 后 ROW 删除会触发；此前 `restore-driver-rows` / 编辑失焦等路径也会触发），物化结果整体错位一格：
  - worktree（含 repair-0727）：`主料1 | 投入零件1 | H65(错误继承组成件1的 PCS/2) | AgNi ×3` —— 组成件1 整行消失、AgNi 从 2 条变 3 条
  - **master（不含 repair-0727，A/B 对照）**：`主料1 | 投入零件1 | 组成件1 | AgNi(错误继承 PCS/2) ×4` —— H65 整行消失、AgNi 变 4 条，**同样损坏且更严重**
- **归因**：**task-0721 加叶子路径的既有缺陷，非 repair-0727 引入**。根因 = 手工叶子行不参与 `row_data` 物化（`snapshot_rows` 7 行 vs `row_data` 6 行），而 `mergeRowDataInputsIntoEdits` 按**位置**把 row_data 合并回 snapshot → 手工叶子之后的所有行整体偏移一格。repair-0727 只是让它更早暴露（树删除现在会触发物化）。
- **范围**：让手工叶子行也进入 row_data 物化（占位空行），或把位置合并改为按行身份（effKey/nodeId）合并。后者更彻底但触及 AP-54 单一口径区，需评审。
- **依赖**：无。**预估规模**：M（含 AP-54 回归验证 + add-leaf/删除组合 E2E）
- **验收要点**：①树中部加叶子后删除该叶子，其余行内容与数量逐字段不变；②`snapshot_rows` 与 `row_data` 行数关系可解释（不再出现"物化少一行"）；③非手工叶子的普通行删除行为逐字节不变。

### [BL-0078] E2E 夹具随 `cpq_db_0724` 迁库全部失效（硬编码 quotationId 的 spec 集体空转）
- **优先级**：P1（不修则"零回归验证"长期失去证据能力）
- **来源**：repair-0727 测试用例设计阶段发现，技术总监独立复核确认
- **状态**：TODO（未排期）
- **登记日期**：2026-07-26
- **背景（实测）**：`cpq_db_0724` 当前**只有 8 张报价单**（`QT-20260726-0001~0008`，均 2026-07-27 建）。凡 2026-07-24 之前建立、硬编码具体 `quotationId` 的 E2E spec 在此库中都进不了编辑页：`quotation-bom-tree.spec.ts`（`QT-20260721-2067`）、`costing-bom-tree.spec.ts`（`QT-20260604-1577`）、`ap51-row-count-stable.spec.ts`（`QT-20260522-1604`）、`card-formula-flow.spec.ts` / `card-aggregate-dynamic-flow.spec.ts`（`QT-20260602-1497`）、`costing-card-formula.spec.ts`（`QT-20260603-1528`）、`child-parts-zcj-bom.spec.ts`（`QT-20260527-1651`）等。
- **危害**：这些 spec 会以"同型失败"长期存在，导致每次改动都要先做 A/B 空跑才能区分"环境噪声 vs 真回归"——成本高且容易误判（把真回归当噪声放过，或反之）。
- **🔴 2026-08-01 范围扩大（task-0801 公式计算精度优化验收时发现，同一根因族）**：失效的**不止是硬编码 quotationId 的 spec**，`CLAUDE.md`「修改后强制自检」第 5 项点名要求的**两个强制 E2E spec 本身也已失效**：
  - `quotation-flow.spec.ts` —— 硬编码客户「苏州西门子」（库中 **0 命中**）+ 模板「报价模板0608」（**0 命中**）
  - `composite-product-flow.spec.ts` —— 客户「罗克韦尔」在，但模板「组合产品 v1.16」（**0 命中**）
  - `quote-manual-row.spec.ts` —— 模板「组合产品 v1.10」（**0 命中**）

  全库现只有 7 个模板（罗克韦尔模板1/2/3 + 核价模板1，均 2026-07-27~28 建）。
  **后果最严重的一条**：CLAUDE.md 规定协议级改动"跳过 E2E 等于跳过自检"，而这两个 spec 现在**根本跑不起来** ——
  等于所有触碰报价渲染链路的任务都在裸奔（AP-37/38/40~43 那类静默协议 bug 只在 E2E 暴露）。
- **🔴 还有三重叠加阻断（2026-08-01 实测，修 fixture 时逐层挖出，只换客户/模板名解不开）**：
  1. **产品分类交互已过时**：task-0712 起分类改为客户绑定带出、只读不可选（`QuotationCreateForm.tsx` 的 `lockedCategoryId`，`disabled` + `showSearch=false`），而旧 spec 还在"点开下拉→输入→点选项"，会等一个**永远不会出现**的 `.ant-select-item-option`（真实 stack trace 佐证）。**凡有绑定分类的客户都卡在这一步**，与模板名无关。
  2. **`sel_template` 表 0 行**：「选配添加」子系统对任何客户都进空态，加不了产品。
  3. **占号悬空致「从已有产品添加」全库返空**：见 [[BL-0092]]（已临时清数据解锁，代码缺陷未修）。
- **修复建议（已探明可行路径）**：客户用「罗克韦尔」（有 `product_category_id`）+ 模板「罗克韦尔模板1/2/3」（PUBLISHED，各绑 6 个组件）+ 料号 `S-3120014539`（`PN0507945`，且 `exchange_rate=6.97550000` 恰好可作精度用例真实样本）+ 加产品走「从已有产品添加」。**注意模板只绑 6 个组件，原 spec 断言的"7 个 NORMAL Tab"与 CLAUDE.md 的"8 Tab"数量断言须同步调整；但 `'加载中' final count = 0`、控制台错误监控等验证意图一条都不能削弱** —— 修 fixture 是为了让防线重新生效，不是让它变绿。
- **task-0801 的处置**：该任务已用「后端 93 + 前端 886 单测全绿 + 人工三视图截图 + 实测精度证据」替代 E2E 作为交付证据，并做了 master 基线 A/B 对照证明失败非本次引入；E2E fixture 修复**未完成**，转入本条目。
- **范围**：①建一套可重建的种子夹具（脚本化建单，不再硬编码历史 UUID）；或②把 spec 改为"运行时按客户+模板新建单据"再断言。同时清理已确认过期的 spec。
- **依赖**：无。**预估规模**：M
- **验收要点**：干净库上全部 E2E spec 可跑通或显式 skip（带原因），不再有"看似失败其实是缺数据"的中间态。

### [BL-0079] 树页签配 `FORMULA` 列的端到端验证缺口（F-1 修复目前只有单测证据）
- **优先级**：P2
- **来源**：repair-0727 §12.3 F-1 修复（effKey 四处口径对齐）+ 测试工程师风险点 10.1
- **状态**：TODO（未排期）
- **登记日期**：2026-07-26
- **背景**：repair-0727 修了"树行 effKey 三处口径不一致导致 FORMULA 叶子值与 editRows 绑定 miss"的地基问题，但实测**全库仅有的 3 个 BOM 树组件（`912fa00c` / `422fd880` / `656c9b87`）FORMULA 字段数均为 0**，`formulaResults[].values` 恒为空对象 → UI/接口级"树页签配公式列、值真的取到了"这条**没有任何单据能跑出来**，当前只有单测（`EffKeyNodeIdAlignmentTest` / `rowKeyUniquify.test.ts`）的合成数据闭环。
- **决策记录**：技术总监裁决**不为此改共享组件配置**（该组件被 6 张单据 + 模板快照引用，加字段是全局改动，风险大于收益），接受单测闭环，端到端验证转本条排期。
- **范围**：配一个带 FORMULA 列的树页签测试模板 + 专用测试单据，验证公式列取值、editRows 绑定、存量旧键回退三条链路。
- **依赖**：无。**预估规模**：S
- **验收要点**：树页签 FORMULA 列在 UI / Excel 视图 / row_data 三处取值一致且非空；改造前写入的旧键 editRows 仍能读到。
- 🔍 **2026-08-03 实探纠正（技术总监核实 `cpq_db_0724`，本条背景的核心断言已过期）**：
  - BOM 树组件实为 **10 个**（非 3 个）；其中 **`COMP-0032`「物料」7 个 FORMULA 字段**、**`COMP-0157`「物料」8 个 FORMULA 字段**，两者均 ACTIVE 且用了 `cross_tab_ref`。
  - **`COMP-0157` 已被 QUOTATION 模板「施耐德BUG2」(PUBLISHED) + 真实单据引用**（正是 repair-0803 排查的那张单）→ 「树页签配公式列、值真的取到了」在**报价侧已有活配置**，本条「没有任何单据能跑出来」不再成立。
  - **仍成立的缺口**：**核价侧**。`COMP-0048`「物料BOM」被核价模板 + 22 张单据引用，但 FORMULA 字段数为 **0** → 核价侧 spine 渲染下的树页签公式列仍无端到端证据。
  - **处置**：本条由 **task-0803（BOM 页签父子取值公式）** 吸收闭合——该任务的 AC-27（报价侧，宿主 `COMP-0157`）+ AC-28（核价侧，须新建测试组件）正是本条的验收内容。

### [BL-0001] 报价提交行键冲突的「编辑期实时预检 + 红点标记」（第二期）
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-06-29-submit-rowkey-conflict-locator-design.md` §4 非目标（第一期被动定位的增强）；两轮评审均指为"潜在第二期"
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期（Plan 1b）只做"点提交失败后被动定位"。更好的体验是在**编辑期 / 提交前**就实时算出行键重复，在对应料号/页签上打红点标记 + 可点击定位，不必等提交失败。
- **范围**：前端镜像后端行键算法做实时判重（已有 `rowkey-input-dedup` 编辑期判重基础可复用）；在 Step2 料号列表 / 页签 Tab 上挂 badge（数字=冲突数）；复用第一期的 `RowKeyConflictDrawer` / locate 联动。
- **依赖**：第一期 Plan 1b 落地（后端结构化返回 + Drawer + locate 联动）。
- **验收要点**：编辑产生撞键时无需提交即出现红点；标记数与后端提交校验结果一致；不引入额外 batch-expand 风暴（守 AP-31/AP-37）。

### [BL-0005] 第二期前置：版本感知 BOM 闭包展开（让切版本真正重算子料号）
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-06-29-核价管理财务核价工作台-design.md` §0/§9（第二期前置工程）；cpq-architect 评审 B-1 + 主线穿透核验
- **状态**：⚠️ **TODO（未排期）—— 但前提已过期，排期前必须先重新评估（2026-07-29 复核）**
- **登记日期**：2026-06-29
- **⚠️ 2026-07-29 复核结论（task-0729 技术方案期查证）**：本条描述的**根因载体已不存在** —— **`BomClosureService.java` 文件已被删除**（`find` 零命中），随 `task-0723 B3「料号版本族整族下线」`一并退役。
  - **更重要的是：本条想要的能力看起来已被别的路径实现了。** `docs/RECORD.md` 2026-07-26（V363 条目）记载：核价侧 BOM 树递归 SQL **保留 `:versionFilter` 宏**，`BomTreeRenderService.queryRecursive:361` 对含宏模板调 `VersionFilterMacro.expandForExecution` 展开成 `:__vfPart`/`:__vfVer` **双数组谓词**，**空 override 时退化为 `is_current` 零回归**；实测 seed 展开 **20 节点 / 2 根 / 4 层**。注释明确它是「**task-0713 核价单版本切换作用到 BOM 主树的唯一通道**」—— 这正是本条要的"版本感知 BOM 闭包"。
  - **⚠️ 未复测**：以上为读 RECORD + 查文件存在性得出，**没有实机验证**。排期前请先做最小验证（给某料号造两个版本，确认经 `versionFilter` 重算后子料号集合/值真变）。若成立，**本条可关闭**，[[BL-0006]] 的前置随之解除。
- ~~**背景**：核价工作台"财务切料号版本→重算子料号"在当前引擎**不生效**——`BomClosureService.CLOSURE_SQL` 硬编码 `is_current=true`（`:71/:88`）、`compute()` 在 P1 显式忽略 `versionOverrides`（`:120-124`）；核价卡片 `expandTemplateDriverBaseRows` 传 `partVersion=null`（`CardSnapshotService.java:1631`）→ `ComponentDriverService.expand:402` `set(null)` 清空版本上下文。~~（**上述行号与符号均已失效**，仅作历史追溯）
- **范围**：让 BOM 闭包支持按 `bom_version` 逐层迭代展开（注释所言"P2 走 Java 逐层迭代"）；把 `partVersion` 透传进核价卡片 expand 链路。先做最小验证（给某料号造两个版本，确认重算后子料号集合/值真变）。
- **依赖**：无（独立后端工程，是 BL-0006 的前置）。
- **预估规模**：L（1 周以上）
- **验收要点**：切到另一 `bom_version` 后核价卡片的子料号集合与数据按该版本变化；不破坏默认 is_current 行为（现网无版本锁的单逐字节不变）。

### [BL-0006] 第二期：核价单切版本调价主体（财务调价能力）
- **优先级**：P1
- **来源**：spec §9（第二期大纲）+ 12 轮 brainstorming 核心诉求"调价是常态"
- **状态**：TODO（未排期）— 🔒 **2026-07-29 业务方裁定：后续单独立项，明确不并入 task-0729**（详见本条末尾；它切的是**料号 BOM 版本**，与 task-0729 的**元素价格版本**是两条不同的轴）
- **登记日期**：2026-06-29
- **背景**：第一期只做只读复核+审批，财务**不能调价**。本条补上财务切版本调价：另存核价单版本、记录变更、单据总价随之重算，**不动报价单冻结快照**。
- **范围**：新增 `costing_order_revision`（追加）+ `costing_order_line_snapshot`（逐行核价快照 + part_version_locked）；核价专用切版本端点（只重算核价侧改动卡片 + 单据总价 + 写 revision，允许 SUBMITTED+财务，不调 regenerateAllSnapshots）；并发 `SELECT FOR UPDATE costing_order`+seq 序列防竞态（评审 M-1）；line_snapshot 加 FK（M-4）；可编辑工作台外壳 `CostingReviewCardContainer`（持 lineItem state+角色门+切版本入口，内层仍纯只读 `ReadonlyProductCard` 反 AP-50，M-x1）+ 复活 `PartVersionDrawer`；单据总价口径锁定（含/不含 Step3 折扣，倾向复用 `lineDiscountService.recompute`，M-5）。
- **依赖**：~~**BL-0005（版本感知 BOM 闭包）必须先就绪。**~~ → **2026-07-29 复核：该前置可能已由 `VersionFilterMacro` 满足**（见 [[BL-0005]] 的复核结论，**未复测**）。排期前先验 BL-0005，成立则本条前置解除。
- **预估规模**：L
- **🔒 2026-07-29 业务方裁定（task-0729 澄清期）**：**本条后续单独立项，明确不并入 task-0729。**
  - **两条不同的版本轴，别混**：本条切的是**料号 BOM 版本**（`part_version` / `bom_version`）；task-0729 做的是**元素价格版本**（客户 × 一次调价 → 料号版本指针）。两者**无依赖关系**。
  - **由此产生的 task-0729 硬约束**（见 `dev-docs/task-0729-客户价格调整策略和价格版本/需求说明.md` §11.12 #16 + 同文件 §12.2 范围纪律 #22）：task-0729 期间**不得**新建 `costing_order_revision` / `costing_order_line_snapshot`，**不得**改动 `:versionFilter` 宏与 `VersionFilterMacro`，`costing_order` frozen 快照**逐字节不动**（裁决 42 保持不变）。
  - **⚠️ 做本条时必须先解决命名二义**：届时"料号版本"会**同时**指 **BOM 版本**（本条）与**价格版本**（task-0729），且两者都会出现在核价界面上。task-0729 期间无此问题（`part_version` 族已由 task-0723 B3 整族下线，该词当前无占用），但本条一落地就会撞上。建议在本条的 spec 阶段就把 UI 措辞与字段命名钉死，**不出现裸的"版本"**。
  - 另注：本条原范围里的 `part_version_locked` 已随 task-0723 B3 停止写入（列保留），实现前需重新确认版本来源。
- **验收要点**：财务切某料号版本→该卡片子料号/值 + 单据总价按新版本重算、写入核价单新 revision；报价单原始快照不变；重提延续最新 revision（spec §6.4）；并发切版本不丢改动（连跑两次结果一致）。

### [BL-0007] 第二期：核价单覆盖读取层下沉（B-3，对外以核价单为准）
- **优先级**：P1
- **来源**：spec §9 + cpq-architect 评审 B-3
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期不切版本→核价单与报价单数据不分叉，故无需覆盖。第二期切版本后两者分叉，必须让"核价单最新 revision"在**所有读核价值/单据总价处**覆盖报价单原值，否则对外 PDF/Excel/邮件/比对/列表仍是报价原值。
- **范围**：覆盖不能只在 `loadLineItems` DTO 一处——还含导出 `ExcelViewService.exportExcelView:753`、`QuotationExportService`（totalAmount `:193/206/380/382`）、核价表 `CostingSheetService:157-159`、列表、邮件。方案二选一：切版本时回写一份供导出读取的稳定位置；或建统一"读时取核价覆盖值"服务。
- **依赖**：BL-0006（核价单 revision 已产生）。
- **预估规模**：M（3-5 天）
- **验收要点**：财务调价后，详情页核价视图/金额/对外 PDF·Excel·邮件/比对表 TOTAL/列表金额全部以核价单最新值为准；报价侧不受影响。

### [BL-0010] 降低首开 / warm 阻塞时长：核价卡片值 expand 集合化
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` §12 + 两轮评审（首开 ~9~12s 阻塞主因＝核价侧逐行实时 `bomClosureService.compute` + `expandTemplateDriverBaseRows`）
- **状态**：TODO（未排期）
- **推迟原因**：超出本期范围 + 动核心基线（须 architect）。本期已用 eager warm 把该成本移出用户感知路径（首存后台 warm + 后续秒开），窄窗口首开阻塞已可接受，故 expand 提速本身可后续单独治。
- **背景**：`ensureCardValues` 第一次跑大单（罗克韦尔 170 行）阻塞 ~9~12s，几乎全在核价侧 `buildCostingCardValues` 逐行实时 BOM 闭包 + driver expand（`CardSnapshotService.java:1037/1044`，Bug-B 闸门不合桶、不能并行）。这也是 saveDraft 首存历史耗时同一热点。
- **范围**：把核价侧多行 driver expand / BOM 闭包按集合化批量（与 `savedraft-setbased` 集合化项目同根，复用其 union 合桶成果）；单线程、逐位等价（md5）、守 `cpq-expand-layer-not-threadsafe`（禁并行）。先做最小验证再推广。
- **依赖**：与 savedraft-setbased 集合化项目协同；动核心基线须走 cpq-architect。
- **预估规模**：L（1 周以上）
- **验收要点**：大单首开/warm 阻塞时长显著下降；卡片值落库逐位等价（`GoldenCardValuesEquivTest` 不变）；无并行竞态（刷新多次行数/值稳定）。

### [BL-0017] `[页签(总计)]` 真实计算口径对齐「金额字段(is_amount)小计之和」
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-06-30-tabtotal-subtotal-token-corruption-fix.md` §3.6 划出范围 + 两轮评审「语义事实」提示
- **状态**：✅ **已落地合并 master（2026-06-30，commit `e6c53db`）**。方案 A′ 加性哨兵键（不动裸键、不动求值器）。前端 4 写键点 + 后端 5 点（含计划外新增 `CardSnapshotService` byColNode 排除哨兵）落地；`ComponentDataEffectiveRowsTest` 6/6（含哨兵=Σamount + 折扣缩放）、前端 vitest 210/210、tsc 0 错；**值中性硬证据**：`GoldenCardValuesEquivTest#rockwell` 含/不含本改动纯读 golden 逐位等价 `52380a82…`。详见 spec `…BL0017-tabtotal-amount-sum-impl.md` §10。
- **推迟原因**：超出本期范围（本期硬约束＝「所见即所存 + 不动公式计算」，只修显示忠实性）；且涉及改动求值口径，须单独立项评估影响面。
- **登记日期**：2026-06-30
- **背景**：`[页签(总计)]` 本应＝该页签所有「金额属性」字段(`is_amount && is_subtotal`)的小计之和（用户口述权威规则，已由显示行模块 `tabTotalLines.ts#sumTabColumns` 实现）。但公式引擎的裸键 `componentSubtotals[tabName]` 现＝**所有 `is_subtotal` 列之和**（含非金额的 汇率/利润比例/单率），且因解析期 `value` 被塞首个小计列，`[页签(总计)]` 实际只求**首个小计列的列小计**（前端 `产品#汇率` / 后端 `compCode+"#"+col`），**既非「金额字段之和」也非「整页签总计」**。`tabTotalLines.ts:4-7` 注释明确承认显示行与引擎值「有意分叉」。本期 [BL-本次] 只让它「显示对、保存忠实」，数字仍沿用现状（首个小计列）。
- **范围**：让 `[页签(总计)]` 求值＝`Σ(is_amount && is_subtotal 列的列小计)`，需三处对齐 —— 前端卡片裸键（`QuotationStep2.tsx#getComponentSubtotals` 裸键改 amount 过滤，复用 `sumTabColumns` 口径）+ 后端 Excel 路径（`ComponentDataEffectiveRows`：**当前根本没登记裸键**，需新增裸键＝amount 列之和，否则 fix 后该路径 `[页签(总计)]` 求值为 0）+ 配置快照（`ConfigureSnapshotService#accumulateColumnSubtotals`：同样只登记 `#列` 键、需补裸键）。同步评估其它读裸键的路径（产品小计兜底 `evalProductSubtotalFromSubtotals` / 按页签折扣）是否要跟随改口径，或给 `[页签(总计)]` 单独引一个「金额合计」键以缩小影响面（A 全局重定义 vs B 单独键，二选一）。
- **依赖**：本次「所见即所存」修复（[BL-本次] 引入的 `is_tab_total` 标记）先落地；A/B 口径方案须 architect 评审（动核心求值基线，守 `docs/三大核心模块基线.md`）。
- **预估规模**：M（3-5 天）
- **验收要点**：`[页签(总计)]` 在报价单卡片 / Excel 视图 / 配置快照三处求值一致＝该页签金额字段小计之和；与页签底部「本页签金额合计」显示行同值（口径统一）；非金额小计列不再计入；其它读裸键路径行为符合所选 A/B 方案预期。

### [BL-0018] 存量已塌缩 `[页签(总计)]` 公式的批量恢复
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-30-tabtotal-subtotal-token-corruption-fix.md` §3.6（本期不处理存量）
- **状态**：BLOCKED（需人工逐条确认意图，不可盲目脚本迁移）
- **推迟原因**：修复前「点页签总计」与「点小计列」存出的 token 字节级同形（均 `value=列名`、无 `is_tab_total`），脚本**无法安全区分**存量某条 `value=列名` 原意是「整页签总计」还是「用户确实选了该列」；盲目迁移会把合法列引用误改成总计。
- **登记日期**：2026-06-30
- **背景**：本期机制修复后，**新保存**的 `[页签(总计)]` 才带 `is_tab_total` 标记、显示忠实；修复前已存的旧公式无标记，重开仍显示为 `[页签.列]`，需用户重开该公式重存一次才恢复。
- **范围**：若确有批量恢复需求 —— 先排查存量（grep 快照/组件 JSONB 里 `component_subtotal` 且 `value ∈ subtotalCols` 的 token），再**人工逐条确认**意图后打标，不做无人值守迁移；或提供「一键重存当前公式」的辅助入口让用户自助修。
- **依赖**：[BL-本次] 机制修复落地（标记字段已存在）。
- **预估规模**：S（1-2 天，主要成本在人工确认）
- **验收要点**：被确认为「总计」意图的存量公式补上 `is_tab_total` 后显示恢复 `[页签(总计)]`；列引用意图的公式不被误改；求值不变。

### [BL-0022] 核价汇总视图 `v_costing_summary_full` 漏接已算的模具费/设计费 → 总成本系统性偏低
- **优先级**：P1
- **来源**：2026-07-01 核价单「已具备功能」深度复核（亲验 `V80` SQL）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-01
- **背景**：`CostingSummaryService.compute()` 已算出并入库 7 个 metric（含 `TOOLING_FEE`/`DESIGN_COST`），但 `V80__costing_summary_view_and_excel.sql:31-32,51-58` 里 `v_costing_summary_full` **只 PIVOT 了 `MATERIAL_COST`+`PROCESS_FEE` 两列，且根本没有模具费/设计费的承接列**——`TOOLING_FEE`/`DESIGN_COST` 被静默丢弃，其余 6 列（损耗/管理/财务/利润/税费/电镀/其他）恒 NULL。走该汇总视图的核价 Excel「汇总」页总成本 `=[L]+[M]+…+[T]` 实际只剩材料+加工，**总成本系统性偏低**。注意这**超出**「商务加价 6 项未实现」的已知限制（[[BL-无]] 总结 §八#1）——模具/设计是**已实现却漏接视图列**。
- **范围**：给 `v_costing_summary_full` 补 `tooling_cost`/`design_cost` 两个 PIVOT 列（`MAX(CASE WHEN metric_code='TOOLING_FEE'…)` / `'DESIGN_COST'`），并把默认核价 Excel 模板「汇总」页总成本列公式纳入这两列；确认与 `compute()` 的 `UNIT_TOTAL_COST` 口径一致（避免重复/漏加）。
- **依赖**：无（独立视图 + 模板列改动）；DDL 后须重启 Quarkus（守 CLAUDE.md「视图重建后重启」）。
- **预估规模**：S（1-2 天）
- **验收要点**：汇总视图总成本 = 材料+加工+模具+设计（+未来商务加价），与 `costing_summary_result` 各 metric 之和逐位一致；未实现的商务加价列仍 NULL 显示「—」。

### [BL-0023] 🚨 发版红线：V306 price_type 按 Sheet 细分「只改写入端、取价视图未重写」→ 重导即断链
- **优先级**：P1（发版安全红线）
- **来源**：2026-07-01 核价单深度复核（亲验实库 `unit_price` 分布 + `component_sql_view.sql_template` 谓词）
- **状态**：TODO（未排期）— **落地前禁止跑新一轮 PRICING 核价基础资料导入**
- **登记日期**：2026-07-01
- **背景**：V306 把核价 `unit_price.price_type` 写入端细分为 7 个新值（`INCOMING_PROCESS`/`SELF_PROCESS`/`FINISHED_OTHER`/`OUTSOURCE_PROCESS`/`MATERIAL_PRICE`/`PACKAGING`，10 个 P* handler + CHECK 已就绪），但**取价视图 sql_template 未同步重写**。实库查证：① 新细分值在 `unit_price` 表 **0 行**（V306 handler 尚未跑过导入）；② 现网核价取价视图仍按**旧值**过滤——`gx_view` 滤 `price_type='MATERIAL'`、`ll_view` 滤 `INCOMING_MATERIAL_PROCESS`、`qt_view` 滤 `FINISHED_MATERIAL_OTHER`、`wgj_view` 滤 `COMPONENT_OTHER`、`dd_view` 滤 `PLATING`。因数据与视图当前**都还是旧值**，**系统现在正常**；spec `V306:18` 已自认「视图 sql_template 重写另行处理（推迟）」。
- **⚠️ 触发条件（务必周知）**：**一旦用 V306 之后的 handler 重新导入核价基础资料** → 新行带细分 price_type → 上述视图旧谓词匹配不到 → 来料加工费/自制加工费/电镀/外加工/材料价等**取价返 NULL**；且因 `uq_unit_price` 唯一键含 `price_type` + V306 不回填存量，旧 `MATERIAL` 行与新行**并存**，读取端继续读旧 stale 行（**静默错价、导入看似成功、无报错**）。
- **范围**：重写核价取价视图（`component_sql_view.sql_template`，config 驱动存 DB）的 `price_type` 谓词以匹配新细分值（或做值映射兼容层）；同步核对 `V255` 种子与线上 `component_sql_view` 表已漂移的实际谓词；给存量 `MATERIAL` 行制定回填/迁移策略。**在此之前，运维/开发一律不得对核价侧跑 PRICING 重导。**
- **依赖**：无（后端视图 + 数据迁移）。
- **预估规模**：M（3-5 天）
- **验收要点**：用 V306 handler 重导后，各费用视图仍能按新细分 price_type 取到价；无 NULL 断链、无新旧行并存读旧值；`V255` 种子与线上 sql_template 对齐。

### [BL-0024] 独立核价单 override（what-if 差量）EXCHANGE fieldName 错配静默失效 + discount_rate 死选项
- **优先级**：P1
- **来源**：2026-07-01 核价单深度复核（亲验 `CostingSummaryDetailPage.tsx` 前端 + `CostingSummaryService.java` compute）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-01
- **背景**：独立核价单模块（配置中心→核价单，`CostingSummaryDetailPage`）的差量抽屉里，`fieldName` 下拉给 EXCHANGE 也列出 `costing_price`（标签误写「核价单价 / **核价汇率**」，`:302`），但 compute 对 EXCHANGE 只用 key 后缀 `costing_rate`（`CostingSummaryService.java:367,381`）。用户给汇率建差量若选被诱导的 `costing_price` → key `EXCHANGE:CNY/USD:costing_price` → Map miss → **差量静默忽略、无报错、状态照常 COMPUTED**，用户以为已生效。另 `discount_rate` 选项（`:304`）compute **全程无任何命中** → 死选项。
- **范围**：前端按 `targetKind` 联动 `fieldName` 可选集（ELEMENT/MATERIAL→`costing_price`；EXCHANGE→`costing_rate`），去掉误导标签与不可消费的 `discount_rate`；或后端为 EXCHANGE 同时接受 `costing_price` 别名。补一条保存后校验（key 无对应求值通道时给 warning）。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：EXCHANGE 差量只能选 `costing_rate` 且生效；不再出现选了不生效的静默失效；`discount_rate` 死选项移除或接通。

### [BL-0027] 核价树导入后首屏走「实时兜底」需手动刷新才出树（快照值加载时序）
- **优先级**：P1
- **来源**：2026-07-02 核价树配置排查（Playwright 实测 + 前端 `QuotationStep2` 链路核实）
- **状态**：TODO（未排期，动核价单渲染基线 → 须 architect）
- **登记日期**：2026-07-02
- **背景**：导入产品时前端在内存拼 `LineItem`（**不带 `costingCardValues`**），快照在 `saveDraft`/后端同步算好写库，但**前端不重新拉取** → `useSnapCosting = costingLineItems.every(li => !!li.costingCardValues)` 为假 → 走实时兜底（`QuotationStep2.tsx:3193` `useDriverExpansions(...)`）→ 该路径**未注入闭包 partSet、仅展根料号层**（`:3187` 注释原文）→ 核价递归组件（如子配件）**首屏渲染成平表、无「料号/父料号/版本」三系统列**。用户手动刷新触发 `getById` 拿到快照值 → `useSnapCosting` 转真 → 出树。**数据/配置均正确**（快照库里已含 spine），纯前端时序体验瑕疵。
- **范围**（推荐组合 A+B）：
  - **A（必做·前端）**：`saveDraft`（快照落库）成功后**自动 re-fetch `getById`**，把 `costingCardValues`/`quoteCardValues` 灌回内存 `lineItems` → `useSnapCosting` 自动转真、无需手动刷新。重拉须在快照落库**之后**；转真后**断开** batch-expand（`useSnapCosting ? EMPTY_LINEITEMS : ...`）避免两链同跑（守 AP-31）。
  - **B（兜底·前端）**：`useSnapCosting=false` 但核价模板含递归组件时，不静默显示错误平表，而显示「核价树快照生成中…」占位 + 自动重试 1–2 次 `getById`。
  - **C（可选·后端）**：`saveDraft`/导入响应直接带回 `costingCardValues`，免二次往返（代价：响应体增大，需评估）。
- **依赖**：无（前端为主；C 动后端响应）。
- **预估规模**：M（3-5 天，含 architect 评估 + E2E）
- **验收要点**：导入产品后**不手动刷新** → 核价单·递归页签（子配件等）**直接出树 + 三系统列**；`'加载中' final count = 0`；无 batch-expand 风暴（守 AP-31）。
- **注**：改 `QuotationStep2` / `saveDraft` 回调 / getById 时序 = 协议级 + 触碰「核价单渲染」基线（`docs/三大核心模块基线.md`），须走 cpq-architect + E2E（CLAUDE.md 强制）。

### [BL-0028] 🔧 spineKeys 叶子节点空 = `SqlViewExecutor` 数组绑定 `String.valueOf(null)→"null"`（已修待正式落地）
- **优先级**：P1
- **来源**：2026-07-02 核价树配置排查（实测 spineKeys 叶子空 → 逐层定位到绑定层）
- **状态**：**已修 + 已实测通过（应用于主工作区未提交）**；待正式落地（独立分支 + E2E + 提交）
- **登记日期**：2026-07-02
- **背景**：核价 BOM 树边源视图用 `:spineKeys(子件, 父件, 子件自身版本)` 过滤时,**叶子节点**(自身无下级 BOM → 版本=NULL)一直被误滤空,即使第 3 参写对(LATERAL 子件自身版本子查询)。根因:`SqlViewExecutor` 绑 `List→text[]` 时 `list.stream().map(String::valueOf)`,`String.valueOf((Object)null)` 返回**字符串 `"null"`** 而非 SQL NULL → `__skV` 里叶子的 NULL 版本变 `"null"` → `(子件版本) IS NOT DISTINCT FROM k.v` 里 真 NULL vs `"null"` = false → 叶子被滤。违背 `2026-06-06-spinekeys` 设计 §4.2「叶子 NULL-safe 命中」。
- **修法(已应用)**:两处绑定(`executeAllRows` / `executeJdbc`)`map(x -> x==null?null:String.valueOf(x))` 保留 null → `createArrayOf` 得 SQL NULL → NULL-safe 匹配生效。
- **实测**:1922/4141111115 子配件 spineKeys 版 27→19 行(消重)+ 叶子全填 + 仅根 1 空;Playwright 三系统列=true。
- **依赖**:无。
- **预估规模**：S（1-2 天,主要在 E2E + 评审）
- **验收要点**：spineKeys 视图叶子(版本=NULL)正确命中;不破坏非叶子(有版本)匹配;`ys_view` 等其它 spineKeys 视图无回归。
- **注**：**协议级**(动 spineKeys 绑定,影响所有 spineKeys 视图)→ 正式落地须独立分支 + `quotation-flow.spec.ts` E2E + 提交。当前改动在主工作区 `SqlViewExecutor.java`,未提交。

### [BL-0031] 选配「工序」落 V6 承载表 + mirror 视图（选配模板方案前置·architect 级）
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md` §2/§9 + 架构复核（`docs/反模式.md` AP-53 续6）
- **状态**：✅ **已由 task-0712 实质解决（2026-07-15，master `d02b7fe`）**——选配工序落 `unit_price`（`price_type=PROCESS`/`cost_type=自制加工费`，B2 完整落库）+ 组合工艺落 `capacity`（组装加工费，B6）；标识统一到 `process_master.process_no`（缺口1 加法式方案A，V336）；渲染走物理视图 `v_composite_child_processes`（读 `unit_price.operation_no`）。按 `material_no`(=销售料号 V315) 维度落库，与 BL-0031 目标一致。**未新建"专用工序承载表"**（用 unit_price 承载，符合《报价系统Excel导入落库方案》§10），如需独立表另评估，否则可关闭。
- **登记日期**：2026-07-07
- **推迟原因**：AP-53 续6 已标"工序在 V6 侧无承载表、需新建业务表 + mirror UNION，走 architect"；若一期强做则范围过大。
- **背景**：选配方案把"工序"列为一期固定参数，但 V6 侧尚无工序落库承载表（现役选配 Phase1 只落料号+元素+子件）。产品卡片工序 Tab 依赖按 `sales_part_no` 落库 + mirror 视图取数。
- **范围**：设计工序 V6 承载表 + mirror UNION 视图，供选配/核价按 `sales_part_no` 取工序；对齐「销售料号维度落库 V6.2」口径。
- **依赖**：material_master/element/bom V6 Phase1（已就绪）；报价料号统一 Spec1。
- **预估规模**：L（1 周以上）
- **验收要点**：选配产出报价料号的工序能按 `sales_part_no` 落库并在卡片工序 Tab 渲染；不破坏现役核价工序取数。

---

### [BL-0045] 已导入工序码与 process_master 编码体系不相交 → 名称带出为 null
- **优先级**：P1
- **来源**：task-0712 主数据维护-核价基础数据维护 验收发现（C8 名称带出）
- **状态**：**[x] 已完成（DONE 2026-07-13，合并 master `efa5224`；childtask-1）**
- **登记日期**：2026-07-12
- **方案（终）**：经 spec 评审逐条澄清（5 问），从原 spec 方案 A（导入 upsert 主表、仅工序）**重构为方案 B + (ii)**：主数据先行、**核价导入不写主表**；补齐**四码**名称（工序/元素/材质/料号）——① 工序建**批量导入**（对齐材质库，upsert `process_master` `ON CONFLICT DO UPDATE`）；② 元素靠材质库导入已落 `element` 主表，不新建；③ 材质走 `material_part_no → material_master.material_recipe_id → material_recipe.name` 两跳 join；④ 料号已被 P05/P06/P24 导入 upsert `material_master`，仅核对。**无 Flyway**（`uq_process_master_no` 已存在 V218:142）。
- **交付**：后端 `efa52245`（ProcessMasterImportService/DTO/Resource +import/+template、ColumnDef MASTER_2HOP、PricingSheetRegistry+PricingMaintenanceService 两跳 join）+ 前端 `33d628e`（ProcessMasterImportDrawer、v6MasterDataService、V6ProcessCrudTab 入口、EditableSheetTable 灰字兜底）。技术总监亲验：守 B(无 P-handler/无 Flyway)、前后端信封对齐、独立复跑 tsc 0 错 + 后端 10+1+16 测试全绿、合并后 8081 活体 401/5174 200。
- **文档**：`dev-docs/task-0712-主数据维护-核价基础数据维护/childtask-1/{需求说明,backtask,fronttask,api}.md`；原 spec 已标"方案 A 历史留档"。
- **落地后待业务动作（方案 B 主数据先行的必然，非缺陷）**：① 工序名须业务拿真工序 Excel 走新导入端点才落 `process_master`（现 0 个 Z 码）；② 材质名须走「材质管理→绑定料号」补绑（现 `material_master` 绑定率 0/39，全显"未绑定"，PRD §5 非目标）。
- **遗留（P2）**：导入未做 `process_no`(VARCHAR20)/`process_name`(VARCHAR50) 超长截断校验，超限 DB 层报错；backtask/api 未要求，待评估。

### [BL-0064] 外购件（`characteristic='OUTSOURCED'`）的渲染归属 —— 三态统一的展示侧兑现
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-07-20-material-bom-item-characteristic-三态统一-design.md` §9.1（本期明确不做下游）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-20
- **背景**：本期把 `material_bom_item.characteristic` 统一为 `RECIPE`/`ASSEMBLY`/`OUTSOURCED` 三态，业务在报价侧「组成件BOM」sheet 的新增列「组成类型」里区分零件 / 外购件。但**下游视图本期零改动**，导致数据层区分了、展示层没兑现：
  - `zpj_view`（子配件）子表谓词 `characteristic = 'ASSEMBLY'` → 外购件**不显示**；
  - `ll_view`（来料）子表 join **不过滤 characteristic**，只按 `material_no` 关联，叠加本期主表推导改动（含 OUTSOURCED 子行 → 主表判 `ASSEMBLY`）→ 外购件**会混在「来料」页签里显示，但与零件视觉上无法区分**（`ll_view` 不输出 `characteristic` 列，其「类型」列取的是 `component_usage_type` 映射：银点类/非银点类/组成件，与三态无关）。
  - 净效果：业务填了"外购件"，在报价单上看不出区别。**本期已确认接受此取舍。**
- **范围**（三选一，二期定）：① `ll_view` 增加一列输出 `characteristic` 的中文映射（改动最小，只动视图 SQL + 组件字段配置）；② `zpj_view` 放宽为 `IN ('ASSEMBLY','OUTSOURCED')`，外购件并入子配件页签；③ 新开独立视图 + 组件 + 模板绑定，外购件单独成页签。
- **二期必须一并评估的下游消费点全清单**（2026-07-20 穷举 `material_bom_item` × `characteristic` 得出，均硬编码 `= 'ASSEMBLY'` 故当前排除 OUTSOURCED）：

  | 位置 | 谓词 | 二期待定 |
  |---|---|---|
  | `zpj_view`（子配件，QUOTE 分支）| `characteristic = 'ASSEMBLY'` | 视方案而定 |
  | `QuotationService.java:577` | `characteristic='ASSEMBLY' AND operation_no IS NOT NULL` | 需业务确认：外购件带组装工序时是否计入 |
  | `QuotationService.java:2328` | 同上 | 同上 |
  | `ConfigureSnapshotService.java:524` | `mbi.characteristic='ASSEMBLY' AND mbi.component_no IS NOT NULL` | 需业务确认 |
  | `ll_view`（来料）| 走**主表** characteristic，子表 join 不过滤 | 已因主表推导纳入 OUTSOURCED 而生效（混显） |

  > **写入点已穷举为 4 个且本期全部收敛完毕**（`P06MaterialBomHandler` / `MaterialBomMergeHandler` / `PricingSheetRegistry`+`PricingMaintenanceService` / `ConfigureProductService`），二期不需要再找写入侧。`MaterialBomItemRepository:25` 用 `COALESCE(characteristic,'')=COALESCE(?4,'')` 参数化，无硬编码假设。
- **依赖**：本期（三态统一 + V344 迁移）交付。
- **预估规模**：S（方案①②）/ M（方案③）
- **验收要点**：报价单上外购件行可与零件行明确区分；不产生重复行（守 AP-22「X (共N项)」族）；改动视图后按 CLAUDE.md「视图 DROP CASCADE / 重建后必须重启 Quarkus」执行。

### [BL-0091] 报价单总额存在两套算法写同一个 DB 列 —— 草稿态与提交态数量级不一致
- **优先级**：P1（用户可见的数字矛盾；已存在，非潜在风险）
- **来源**：task-0801「公式计算精度优化」需求澄清（技术总监代码勘察发现，需求方裁决本期不修，见 `dev-docs/task-0801-公式计算精度优化/需求说明.md` §11.4~§11.6）
- **状态**：TODO（未排期）
- **登记日期**：2026-08-01
- **推迟原因**：需求方裁决「两个数字本来就不同，分开展示」「列表不动，只修精度」，本期只统一位数口径，算法口径差异另行处理。
- **背景（已实证）**：`quotation.total_amount` 这一个列被**两套算法**交替覆写：
  | 路径 | 算法 | 位置 |
  |---|---|---|
  | 草稿保存 / 重算 / 导入（列表看到的值） | `total_amount = (Σ 各行产品小计) × 整单折扣率 / 100` —— **不乘年用量**，且走整单折扣率 | `QuotationService:664` / `:706` / `:1943` / `:2385` |
  | 提交（Step3 页面与提交后看到的值） | `total_amount = Σ 行合计`，行合计 = 折后单价 × **年用量** | `QuotationService:850` + `LineDiscountService:116` |
  年用量可达几十万件，两者差的是**数量级**而非舍入。后果：①同一张单**提交瞬间列表金额跳变**；②草稿期列表金额系统性偏低；③"报价单总计与列表对不上"的用户困惑的**主因**（task-0801 只消除了其中的位数截断差）。
- **范围**：
  1. 裁定 `total_amount` 的唯一语义（建议 = 年采购金额 = Σ 行合计，与提交路径、Step3 UI、导出一致）；
  2. 把 4 处草稿/重算路径改为与提交路径同算法（先 `LineDiscountService.recompute` 每行，再 Σ `lineTotalAmount`）；
  3. 若业务同时需要「单件报价总额」，新增独立列而非复用 `total_amount`（一列一语义）；
  4. `finalDiscountRate`（整单折扣率）与行级折扣 `discountRateApplied` 的关系需一并厘清 —— 现两套折扣机制并存，整单折扣率仅在草稿路径生效（另见 [[step3-discount-half-built]] 记忆：Step3 优惠策略前端发 9 字段、后端曾长期未落地）。
- **依赖**：无（task-0801 已把精度与格式化收敛到 `PrecisionPolicy` / `precision.ts`，本条只动算法不动精度）
- **预估规模**：M
- **验收要点**：①同一张单在草稿态与提交后，列表金额**不跳变**；②列表金额 = 报价单内对应指标 = 导出金额；③每个金额列的语义在代码注释与 `api.md` 中各有且仅有一种说法。

### [BL-0096] task-0801 精度优化的 AC-14「亿级金额端到端」未验证（仅算法级单测覆盖）
- **优先级**：P1（本期核心风险项的验证缺口，非功能缺陷）
- **来源**：task-0801 公式计算精度优化，技术总监验收时的实测数据缺口
- **状态**：✅ **已完成（2026-08-02，技术总监造数实测，四处逐位一致 + EDGE-13 兜底实证）**

#### 验证结果（造数 → submit 权威重算 → 四处比对 → 清理复原）

**数据设计**：20 行，单价 `123.456789 + (i-1)×0.000007`（每行互异，避免"相同数相加"被优化）、
年用量 `799999`（非 10 的幂次，确保乘完仍带满 6 位小数）。
金标准用 psql `numeric` 精确计算（`SUM(subtotal * annual_volume)`）。

| # | 观测点 | 值 | 与金标准 |
|---|--------|-----|---------|
| 0 | psql numeric 金标准 | `1975307218.862890` | — |
| 1 | DB 落库 `quotation.total_amount` | `1975307218.862890` | **差值 0.000000** |
| 2 | DB `SUM(line_total_amount)` | `1975307218.862890` | **差值 0.000000** |
| 3 | API `GET /quotations/{id}` 的 `totalAmount` | `1975307218.862890` | 逐位一致 |
| 4 | 导出 HTML | `1975307218.86289` | 一致（去尾零，符合"至多 6 位"设计） |
| 5 | 导出 Excel | `<v>1.97530721886289E9</v>` | 一致（OOXML 对 double 的科学计数法序列化） |
| 6 | 前端列表页渲染 | `1975307218.86289` | 一致（Playwright 实测，1 passed） |

逐行验算亦全部 0 差值（抽 1/10/20 行：`line_total_amount - subtotal*annual_volume = 0.000000`）。
该总额为 **10 位整数 + 6 位小数 = 16 位有效数字**，已超 double 可靠边界，链路二的 BigDecimal 全程承载被证明有效。

#### EDGE-13（导出 Excel 超 15 位有效数字写文本）—— 用两组数据形成对照实证

第一组总额 `1975307218.862890` 经 `stripTrailingZeros()` 后是 `197530721886289` = **正好 15 位**，
未触发字符串分支。故另造一组（末行年用量改 `800000` 使总额末位非 0）得 `1975307342.319812` = **16 位**：

| 总额有效数字 | Excel 单元格 XML | 存储形式 |
|---|---|---|
| 15 位 | `<c r="I32" t="n" s="5"><v>1.97530721886289E9</v></c>` | **数值** |
| 16 位 | `<c r="I32" t="s" s="5"><v>27</v></c>` → `sharedStrings[27]` = `1975307342.319812` | **文本** |

正是 `backtask.md` B6 设计的 `stripTrailingZeros().precision() > 15 → setCellValue(String)` 兜底，
且文本值与金标准逐位相同（该组 DB 对账差值同样为 `0.000000`）。

#### 收尾
测试数据已完全清理复原（40 行 line_item + 2 张 submit 产生的 costing_order 已删，
两张单恢复 `DRAFT` / `total_amount=0`；库中 `AC14-TEST-*` / `EDGE13-*` 残留 **0 行**），
临时 Playwright 脚本与截图已删除，未留在交付物中。

> 过程记录：首次前端验证 FAIL，经排查是**用例写错**（路由用了 `#/quotations`，实际是 `/quotations`）
> 而非渲染缺陷 —— 截图显示已登录但停在工作台。改用点击侧边栏导航后 1 passed。
> 这条印证了 testcase.md 的纪律：任何 FAIL 都必须先区分「产品坏了」与「用例写错了」。

---

<details>
<summary>原始条目内容（保留追溯）</summary>

- **原状态**：TODO（未排期）
- **登记日期**：2026-08-01
- **背景**：task-0801 全部设计依据是「年用量达几十万件 → 整单金额冲到亿级 → 15 位有效数字已达 double 极限 → 小数第 5、6 位不可信」，据此把金额汇总链路（产品小计 → ×年用量 → 行合计 → Σ整单总额）改为全程 BigDecimal/Decimal。
- **缺口**：**该场景在现网数据中完全不存在** —— 实测 `quotation_line_item` 全表 `max(annual_volume) = 1`、平均 = 1、最大行金额 14.00 元。因此：
  - ✅ **已覆盖**：算法级单测（后端 `FormulaCalculatorGoldenCasesTest` 的 G-10/G-11 + T3/T4 链路基线、前端 `precision.test.ts` 同款），证明**算法在该量级下正确**；
  - ❌ **未覆盖**：整条「落库 → API → 前端渲染 → 导出」链路在亿级金额下的**端到端保真**，以及导出 Excel 触顶 15 位有效数字时「写字符串而非数值单元格」的兜底逻辑（`testcase.md` 的 PREC-AC14-b / EDGE-13，两条都标注为强制人工造数）。
- **范围**：按 `testcase.md` §5.3 的造数步骤执行一次：罗克韦尔 + 罗克韦尔模板1 → 加 20 个产品行 → 年用量填 500000~800000 → 单价含 6 位小数 → 保存草稿 → 四处比对（前端显示 / `GET /api/cpq/quotations/{id}` / DB 落库 / 导出 Excel+PDF）+ 与 `psql` 的 `SUM(line_unit_price * annual_volume)` numeric 精确参考值对账，重点看**第 6 位小数**。
- **依赖**：与 [[BL-0078]] 部分重叠（该造数流程同样受"加产品路径"阻断影响，需先确认「从已有产品添加」可用）
- **预估规模**：S（0.5~1 天，主要是人工造数与四处对账）
- **验收要点**：亿级金额下四处数值第 6 位小数一致；导出 Excel 中超 15 位有效数字的金额单元格为文本类型且数值完整。
  → **两条均已实测通过，见上方验证结果**。实际执行绕开了 BL-0078 的 UI 加产品阻断：
  改为直接造 `quotation_line_item` 行 + 调 `POST /{id}/submit` 触发后端权威重算
  （`LineDiscountService.recompute` → `Σ lineTotalAmount`），验的仍是**真实的链路二计算路径**，
  只是没走 UI 加产品那一段（那段属于 BL-0078 范围，与精度无关）。

</details>

### [BL-0092] ~~报价单删除时不清占号~~ → 孤儿 pending 占号无防御机制
- **优先级**：P1（用户可见功能全灭：「从已有产品添加」对**所有客户**返回空）
- **来源**：task-0801 测试工程师修 E2E fixture 时探测发现，技术总监亲验 SQL 确认根因
- **状态**：✅ **已完成（2026-08-02，commit `fe612af9` / merge `3fc3f204`）**

#### ⚠️ 先更正原始判断（登记时错了）

原条目断言「删除路径无任何清理，只有 `QuoteBackfillService:145` 在审批转正时清占号」——**不成立**。
`QuotationService.delete()` 早已调用 `cleanupPendingV6Data()`（task-0721 B8 加的），
覆盖 `B8_PENDING_TABLES` 8 张表 + `material_master` 的 `deletePendingWithGuard`，**9 张表全覆盖**。

**实测验证**：造 pending 行 → 调 `DELETE /api/cpq/quotations/{id}` → 该行残留 **0 条**。删单回收路径完整且有效。

登记时我只看到转正路径就下了结论，没查 `delete()` 的实现 —— 属未穷举调用点的判断失误。

#### 241 条孤儿的真实来源

```
task-0721 B8 修复合并    2026-07-21
孤儿数据产生时间        2026-07-27 ~ 07-28   ← 全部在修复之后
```
正是 `cpq_db_0724` 迁库重建期，即**绕过应用层直接重建 `quotation` 表**的产物
（与 [[BL-0078]]「E2E 夹具随迁库集体失效」同一批操作）。

#### 真实缺口（本次修复的对象）

1. 9 张表的 `pending_quotation_id` **零外键约束** —— 任何绕过应用层的操作（迁库 / DBA 直删 / 建库脚本）
   都会留下永久僵尸，且系统内**没有任何机制能发现**；
2. `material_master` 的引用守卫**有意**留下悬空（`deletePendingWithGuard` 删不掉仍被引用的行，
   只打一行 WARN 说"需人工核查引用方"），但从无兜底机制去核查。

#### 交付内容

- `PendingHygieneService`：`inspect()` 只读体检 + `cleanup(dryRun)` 清理。语义与删单回收**完全一致**
  （8 张表直接 DELETE，`material_master` 走同款引用守卫），区别仅在筛选条件从「属于某张单」
  换成「归属的单已不存在」。`inspect()` 另用 `information_schema` 反查所有带 `pending_quotation_id`
  的表做交叉校验，**漏加新表会在 `unmanagedTables` 里暴露，不会静默**。
- `MaterialMasterRepository#deleteOrphanPendingWithGuard()`：原守卫的"悬空版"，三处引用检查逐条对称改写
  （引用方是正式行或归属单仍存在 → 有效引用，不删）。
- `PendingHygieneResource`：`GET /api/cpq/admin/pending-hygiene/inspect`、
  `POST /api/cpq/admin/pending-hygiene/cleanup?dryRun=`（**默认 true**，真删须显式传 `false`）。
- `PendingHygieneServiceTest` 4 用例全绿，每例都同时断言"孤儿被删"与"非孤儿仍在"
  —— 误删活数据是本服务最严重的失败模式，安全性优先于清理彻底性。

#### 历史数据清理结果（2026-08-02 经端点执行，已备份）

| 表 | 清理前孤儿 | 已删 | 剩余 |
|---|---|---|---|
| `material_bom_item` | 85 | 85 | 0 |
| `element_bom` / `element_bom_item` | 43 / 43 | 43 / 43 | 0 / 0 |
| `capacity` | 32 | 32 | 0 |
| `material_bom` | 22 | 22 | 0 |
| `unit_price` | 12 | 12 | 0 |
| `plating_scheme` | 2 | 2 | 0 |
| `material_customer_map` | 0（08-01 已单独清） | — | 0 |
| **`material_master`** | 2 | **0（守卫拦下）** | **2** |
| **合计** | **241** | **239** | **2** |

备份表 `bl0092_orphan_backup_20260802`（241 行 `row_to_json` 全量，可回滚）。
**活数据完好性已逐表核验**：`unit_price 94-12=82` / `material_bom_item 170-85=85` /
`element_bom_item 93-43=50` / `capacity 64-32=32`，全部精确匹配，挂活单的 pending 行一条未动。

#### 遗留：`material_master` 2 条需业务判断（不阻断）

守卫拦下的 2 条及其引用方（8 张表孤儿清完后，这些引用方都是**有效数据**，故守卫拦得对）：

| 料号 | 被 BOM 子件引用 | 被 BOM 母件引用 | 被客户映射引用 |
|---|---|---|---|
| `W-1001` | 19 | 0 | 1 |
| `S-80011` | 18 | 13 | 1 |

这两个料号实际**正在被使用**，只是 pending 标记没清干净。合理处置是**转正**
（`pending_quotation_id` 置 NULL）而非删除，但这属于业务判断，未擅自执行。
可用 `MaterialMasterRepository#flipPending` 的同款语义处理，或确认后手工
`UPDATE material_master SET pending_quotation_id = NULL WHERE material_no IN ('W-1001','S-80011')`。

#### 未做（有意）
未给 `pending_quotation_id` 加外键约束。`ON DELETE CASCADE` 会绕过 `material_master` 的引用守卫、
`ON DELETE SET NULL` 会把 pending 行变成"无主僵尸"（仍占唯一约束位）——两种语义都不对。
现方案是「应用层删单即时回收 + 端点兜底体检清理」，覆盖绕过应用层的场景。

---

<details>
<summary>原始条目内容（保留追溯）</summary>

- **原状态**：TODO（数据已临时清理，**代码缺陷未修**）
- **登记日期**：2026-08-01
- **背景（已实证）**：
  - 占号写入：新建报价单时向 `material_customer_map` 写 `pending_quotation_id` 占住料号；
  - 占号清理：**只有一处** —— `QuoteBackfillService.java:145`
    `UPDATE material_customer_map SET pending_quotation_id = NULL WHERE pending_quotation_id = :qid`，
    走的是**报价单审批转正**路径；
  - **报价单被删除时没有任何地方清占号** → `pending_quotation_id` 指向一个已不存在的 `quotation.id`，形成悬空引用，该料号**被永久锁死**。
- **用户可见后果**：`ExistingProductService` 的查询条件是 `system_type='QUOTE' AND pending_quotation_id IS NULL`。
  2026-08-01 实测：全库 `system_type='QUOTE'` 仅 3 行，**可用行数 = 0**（3 行全部被悬空引用锁住，
  分别指向 `9928116f-...`(7/27) 与 `6bc9a6b7-...`(8/01)，两个 quotation 均查无此单）。
  即 **「添加产品 → 从已有产品添加」对任何客户、任何模板都返回空**，UI 显示"未查到匹配的产品"。
  叠加 `sel_template` 表 0 行（选配子系统全灭），Step2 的两条加产品路径当时**全部不可用**。
- **已做的临时处置（不是修复）**：2026-08-01 经需求方授权，技术总监执行数据清理并留备份表 `mcm_pending_backup_20260801`：
  ```sql
  UPDATE material_customer_map SET pending_quotation_id = NULL
  WHERE pending_quotation_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM quotation q WHERE q.id = material_customer_map.pending_quotation_id);
  -- UPDATE 3 → QUOTE 可用行数 0 → 3
  ```
  **这只解了当下的数据死锁，代码缺陷仍在** —— 再删一次报价单就会再产生悬空占号。
- **范围**：
  1. 报价单删除路径（含级联删除、撤回、作废等所有会让 `quotation` 行消失的路径）必须同步清占号；
  2. 评估是否给 `pending_quotation_id` 加外键约束 + `ON DELETE SET NULL`，从 schema 层根治（需先确认无跨库/异步写入场景）；
  3. 加一个兜底清理（启动时或定时）：清除所有指向不存在报价单的悬空占号；
  4. 同步检查 `material_master`、`QuoteBackfillService:116/124` 涉及的其它带 `pending_quotation_id` 的 V6 表是否有同样问题（`MaterialMasterRepository.java:326` 也有一处清理逻辑，须一并核对触发条件）。
- **依赖**：无
- **预估规模**：S（点状修）～ M（含 schema 约束与全表兜底）
- **验收要点**：①删除一张有占号的报价单后，其占用的料号立即可被「从已有产品添加」选到；②全库不存在悬空 `pending_quotation_id`；③其它带该字段的表同口径核对通过。

</details>

## P2

### [BL-0070] Q04/Q05 元素BOM 相关测试 fixture 用 stale 列名（pre-existing 坏测试）
- **优先级**：P2
- **来源**：task-0709/update-0723 报价导入模板 0723 适配 · 技术总监交付验收（2026-07-23）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-23
- **背景**：`Q04ElementBomHandlerTest` / `Q04ElementBomResolveTest` / `Q05ElementRecoveryHandlerTest` / `Q05ElementRecoveryResolveTest` 的 fixture 仍用 `m.put("投入料号", MAT)`，而对应 handler 早已改读「销售料号」/「材质料号」→ 读不到值、测试恒失败。**在 master 上即已如此，非 update-0723 引入**（`git diff master` 实证本次对这些文件零业务逻辑改动，仅补 `@Transactional` 适配 MANDATORY 传播）。
- **范围**：把 fixture 的列名对齐现役 handler 的读取键（销售料号 / 材质料号）。
- **依赖**：无。
- **预估规模**：S
- **验收要点**：4 个测试类转绿；不改 handler 业务逻辑。

### [BL-0071] 报价导入异步调度固定开销（内部 elapsed 与外部实测 250~900ms gap）+ 千行级性能未测
- **优先级**：P2
- **来源**：task-0709/update-0723 U8 性能返修（2026-07-23）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-23
- **背景**：U8「百行内端到端 < 2s」已通过 `writeAll` 进度写入均匀分桶节流达标（17 次远程往返→2 次，实测端到端 ~1.5s）。但诊断中发现 `managedExecutor.runAsync(...)`（生产 `BasicDataImportV6Resource` 同款异步派发）在内部 `[v6import] QUOTE TOTAL` 与外部实测 elapsed 之间存在 **250~900ms、方差很大**的 gap（疑 `@ActivateRequestContext` / executor 调度 / `finalizeImportRecord` 等固定开销），超出本次「只降低 updateProgress 往返」的授权范围未展开。另：本次性能仅验证黄金样例（约 25 行）量级，**千行级客户文件未实测**。
- **范围**：① 若未来需要更紧的性能余量，定位并压缩异步派发固定开销；② 用真实千行级客户文件（如西安中熔/森萨塔）实测导入耗时，确认线性可扩展、不超时不 OOM。
- **依赖**：无。
- **预估规模**：M
- **验收要点**：gap 收敛且方差降低；千行级文件导入成功且耗时线性。

### [BL-0072] `clearPreviousPending` 未覆盖 `pending_material_master_staging`（重导覆盖遗留孤儿行）
- **优先级**：P2
- **来源**：task-0709/update-0723 测试用例设计风险点 R3（2026-07-23）
- **状态**：[x] **已关闭（2026-07-26，repair-0726 随暂存表退役天然解决）**——V362 已 `DROP TABLE pending_material_master_staging`，孤儿行的载体不复存在；同时 `QuoteImportService.clearPreviousPending` 在 8 表 DELETE 后补调 `materialMasterRepo.deletePendingWithGuard(pq)`，重导覆盖时会带引用守卫回收本单旧 pending 料号行。
- **登记日期**：2026-07-23
- **背景**：`QuoteImportService.PENDING_TABLES`（8 张：`unit_price/material_bom/material_bom_item/element_bom/element_bom_item/capacity/plating_scheme/material_customer_map`）**不含** `pending_material_master_staging`。重导覆盖场景下，若新文件相比旧文件「减少」了某个只凭名称发号的料号，旧 staging 行会成为孤儿残留（不影响本次导入正确性，但 promote 时可能带入多余料号）。
- **范围**：评估是否把 `pending_material_master_staging` 纳入 `clearPreviousPending`（注意其键是 `quotation_id + material_no`，与其余 8 表的 `pending_quotation_id` 列名不同）。
- **依赖**：task-0721 pending 机制。
- **预估规模**：S
- **验收要点**：重导覆盖后 staging 的料号集与新文件一致，无孤儿行。

### [BL-0073] `quotation_line_process` 工序反填 SQL 未按 `pending_quotation_id` 过滤
- **优先级**：P2（待评估）
- **来源**：task-0709/update-0723 测试用例设计风险点 R5（2026-07-23）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-23
- **背景**：`QuotationService:590` / `:2437` 的工序 seed SQL 只按 `system_type/customer_no/material_no/characteristic='ASSEMBLY'/operation_no IS NOT NULL/is_current=true` 过滤，**不看 `pending_quotation_id`**。这符合 V6「customer × material 全局共享、非按报价单隔离」的既有设计，但在 task-0721 pending 隔离语义下，是否应收窄到本单 pending 范围需要业务确认（update-0723 已实测该 seed 在 pending 阶段能正常取到数据，功能不受影响）。
- **范围**：与业务确认工序反填的隔离口径；如需收窄则加 pending 维度。
- **依赖**：task-0721 报价升版逻辑。
- **预估规模**：S
- **验收要点**：明确并固化该 SQL 在 pending / 正式两种场景的预期行为。

### [BL-0074] 跨单复用 pending 料号后无转正路径（孤儿 pending 标记）
- **优先级**：P1
- **来源**：repair-0726 代码质量评审 I2/I4（2026-07-26）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-26
- **背景**：`material_master` 改为行级 `pending_quotation_id` 标记后，`MaterialNoResolver.resolve` 按名查重**不加 pending 谓词**（防重号，需求方 Q5 裁决），因此报价单 B 会复用在途报价单 A 新建的 pending 料号；而 upsert 的 `ON CONFLICT` 刻意不写该列（不抢占），行仍归属 A。后果：**B 核价通过时 `flipPending(B)` 匹配不到该行**，料号继续挂 A 的标记 → 主数据列表永久不可见（B4 过滤）；若 A 随后被删，引用守卫会拦下（B 的数据在引用它），标记指向一张已不存在的报价单，**无任何代码路径能再清除**。已实测复现：删单时守卫拦下 2 条并打出 WARN 日志（`QuotationService.cleanupPendingV6Data`），这是目前唯一的可观测信号。
- **第二条触发路径（更常见，2026-07-26 最终评审补充）**：**建单前重复导入**同一张单 —— 上传得 R1（料号落行、`pq=R1`）→ 用户改文件重传得 R2 → R2 命中 `ON CONFLICT`、按设计不改写 `pending_quotation_id`，行仍归 R1 → `clearPreviousPending(R2)` 只清 R2 → `repointPendingOwnership(R2→Q)` 只搬 R2 的行 → `flipPending(Q)` 匹配不到。R1 是 `import_record` **不是 `quotation`**，删单路径永远不会触发，标记**永久无法清除**（渲染不受影响，无谓词；仅主数据列表永久不可见）。janitor 方案须把 `import_record` 也纳入「归属方是否存在」的判定。
- **范围**：需产品决策「共享 pending 料号归谁」——候选：①被引用时改挂新单（re-tag）；②任一引用方核价通过即转正（flip-on-reference）；③定期 janitor 扫描 `pending_quotation_id` 指向不存在报价单的行并转正/清标记。
- **依赖**：repair-0726 已落地（WARN 日志已提供检出手段）。
- **预估规模**：S（janitor 兜底）/ M（re-tag 或 flip-on-reference 需改生命周期语义）
- **验收要点**：不存在 `pending_quotation_id` 指向已删报价单的行；跨单复用场景下料号能随任一引用方核价通过而转正。

### [BL-0075]（技术债）`MaterialMasterRepository` 位置性 NULL 填充 + 长参数列表
- **优先级**：P2
- **来源**：repair-0726 代码质量评审 Q1/Q2（2026-07-26）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-26
- **背景**：三个批量 upsert 各自手工 StringBuilder 拼多行 VALUES，靠**位置性 `NULL` 占位**对齐列清单（如 `(:m0, :n0, NULL, NULL, NULL, :t0, NULL, NULL, NULL, :p0, NOW(), NOW(), :u, :pq)`）。加一列要改 3 方法 × 3 处（列清单 / VALUES 模板 / 参数绑定）= 9 点；且**同类型列错位是静默数据损坏**（`material_type` 落进 `usage_property`，都是 varchar，PG 不报错），只有列数不匹配才会报错。repair-0726 加 `pending_quotation_id` 之所以安全，只因它加在**末尾**。另 `upsertByMaterialNo` 有 7 个连续 `String` 参数，相邻位置互换可编译通过（`QuoteBackfillService` 的调用点 12 个实参里 8 个是字面量 `null`）。
- **范围**：①利用 PG 的 `EXCLUDED` 对**未列出列**同样可见（取默认值 NULL）这一语义，把 `upsertBatchNameType` 的列清单从 14 列瘦到 8 列、`upsertBatchWithWeight` 从 13 列瘦到 6 列，消灭全部位置性 NULL；②抽 `private static String onConflictSet(boolean preserveDescriptive)` 共享 SET 子句，下次加列扇出从 9 点降到 3 点；③视情况把长参数列表换成参数对象/record。
- **依赖**：无。**注意**：这是会改 SQL 文本的重构，必须配套「与改动前逐位等价」的回归证据（参考 `MaterialMasterBatchUpsertEquivTest` 的既有等价性测试范式）；前提假设是 `material_master` 不会有列带非 NULL DEFAULT。
- **预估规模**：S（①②）/ M（含③）
- **验收要点**：三个批量方法 SQL 无位置性 NULL 占位；等价性测试证明与重构前逐位一致。

### [BL-0076]（测试债）repair-0726 接线层回归测试缺口
- **优先级**：P2
- **来源**：repair-0726 最终整体评审 M-5（2026-07-26）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-26
- **背景**：repair-0726 的 repo 层有 `MaterialMasterPendingTest` 8 个用例（写入语义/不降级/不抢占/引用守卫/排序）覆盖扎实，但**接线层**四处目前只有手工全链路验收（RECORD 的 AC-2/3/6）背书，无自动化回归：①`MaterialMasterCrudService.list` 的 `pendingQuotationId is null` 过滤（B4）；②Q02 销售料号补 `material_type='零件'`（B5）；③`repointPendingOwnership` 把 `material_master` 纳入过户循环（建单过户）；④**`QuoteBackfillService.execute` 中 `flipPending` 先于 `cleanupPending` 且后者的 8 表清单不含 `material_master`** —— 第 ④ 项正是 backtask 点名「本任务最容易写错的一处」（写错会把刚转正的行删掉），恰恰没有测试锁死。
- **范围**：优先补 ④（一条针对 `QuoteBackfillService.execute` 的集成测试，断言核价通过后料号行仍在且 `pending_quotation_id IS NULL`）；②可用 handler 级测试低成本覆盖；①③视投入决定。
- **依赖**：无。注意本地 docker 库缺 `CUST-1269` 等共享 dev 库夹具，写测试时别依赖它们。
- **预估规模**：S
- **验收要点**：把 `material_master` 误加进 `QuoteBackfillService.PENDING_TABLES`、或把 `flipPending` 挪到 `cleanupPending` 之后时，测试必须失败。

### [BL-0019] 零金额列页签 `[页签(总计)]`=0 的配置期 lint 警告 + 回退裁决
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-30-BL0017-tabtotal-amount-sum-impl.md` §7 风险 5 / §9 范围外；实现 spec 评审「零金额列语义」
- **状态**：TODO（未排期，需 PM 裁决）
- **登记日期**：2026-06-30
- **背景**：BL-0017 落地后，有 is_subtotal 列但**零 is_amount 列**的页签，`[页签(总计)]` 哨兵键 = 0（Σ 空集）。符合「金额字段之和」规则，但可能违反用户对「总计」的直觉（看着有小计列却显示 0）。BL-0017 本期按规则取 0、不回退。
- **范围**：① 组件管理配置期：当页签有 is_subtotal 列但无 is_amount 列、且被 `[页签(总计)]` 引用时，给 lint 警告提示「该页签无金额字段，总计将为 0」；② 由 PM 裁决是否提供「无金额列时回退 Σis_subtotal」的可选口径。**本条仅在 PM 给出回退需求后才动求值口径**。
- **依赖**：BL-0017 落地（哨兵键 Σamount 口径已生效）。
- **预估规模**：S（1-2 天，lint 部分）
- **验收要点**：零金额列页签被 `[页签(总计)]` 引用时配置期有明确警告；若 PM 要回退，回退仅作用于该场景、不影响正常金额页签。

### [BL-0057]（技术债）task-0712 选配工序 `quotation_line_process` 收缩迁移：删 `process_id` 列 + 换主 FK 到 `process_master`
- **优先级**：P2
- **来源**：task-0712 缺口1 工序 id 契约修复（架构评审.md「工序 id 契约修复设计」方案 A）；实现取**加法式变体**（迁移 V336）
- **状态**：TODO（延后，待所有选配相关并发会话/分支收束）
- **登记日期**：2026-07-15
- **推迟原因**：V336 用加法式（加 `process_no` + FK→`process_master` + 放开 `process_id` NOT NULL，**保留** `process_id` 列/旧 FK），因 `process_id` 列被共享 8081(master 实体映射) 及其它并发 worktree 会话引用，`DROP COLUMN` 会致其 Hibernate 映射失效崩溃。功能已完整（选配写 `process_no`、`process_id` 留 NULL），收缩纯属清理。
- **前置条件**：所有引用 `quotation_line_process.process_id` 的并发分支合并/收束；确认无进程再依赖旧列。
- **范围**：新迁移 `DROP COLUMN process_id` + 删旧 `quotation_line_process_process_id_fkey`；`QuotationLineProcess` 实体删 `processId` 字段。
- **预估规模**：S（1-2 天，含并发协调）
- **验收要点**：删列后选配/编辑/saveDraft 工序落库读取全走 `process_no` 无回归；无进程因缺 `process_id` 列崩溃。

### [BL-0020]（技术债）config 路径 `[页签.列]` 经 FormulaCalculationService 只读裸 code 的粗化
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-30-BL0017-tabtotal-amount-sum-impl.md` §9；实现 spec 评审 #7（pre-existing，非 BL-0017 引入）
- **状态**：TODO（未排期，先确认实际触达面）
- **登记日期**：2026-06-30
- **背景**：`FormulaCalculationService.java:187-192` 的 component_subtotal 分支**只读裸 `component_code`、从不读列键** → 经此引擎求值的 `[页签.列]` 列引用会被粗化成整组件裸键值，而非该列的列小计。这是既有缺陷（与 BL-0017 无关，只是评审顺带发现）。需先确认配置快照产品小计实际走的是 `FormulaCalculator`（有列键，正确）还是 `FormulaCalculationService`（粗化）——若仅后者在边缘路径触达，影响面小。
- **范围**：先排查 `FormulaCalculationService` 的真实调用面（哪些场景的 `[页签.列]` 经此引擎）；若确有错算，让其 component_subtotal 分支对齐 `FormulaCalculator` 的「列键优先、裸键回退」逻辑。
- **依赖**：无（独立技术债）。
- **预估规模**：S（1-2 天）
- **验收要点**：经 FormulaCalculationService 的 `[页签.列]` 求值 = 该列列小计（非整组件裸键）；不回归现有正确路径。

### [BL-0021]（测试债）`GoldenCardValuesEquivTest#rockwell` golden 常量过期 + `RefreshCardSnapshotTest:206` 预存失败
- **优先级**：P2
- **来源**：BL-0017 落地自检时甄别（2026-06-30）
- **状态**：TODO（需 golden owner 重新校准；非 BL-0017 引入）
- **登记日期**：2026-06-30
- **背景**：`GoldenCardValuesEquivTest#rockwell_determinism_and_capture` 的 golden 常量 `GOLDEN_ROCKWELL=3837c2bd…`（2026-06-25 捕获）已过期 —— 在**干净 HEAD（移除 BL-0017）上同样漂移到 `52380a82…`**，系 2026-06-25 后 master 合入的 `2440ab3`（首存集合化落库）/ `9dd6cbc`（失败行落非 NULL 哨兵）/ `6928090`（懒算 Excel）等提交及 LIVE DB 数据变动所致。BL-0017 经背靠背纯读对比证明**值中性**（含/不含 BL-0017 均 `52380a82…`，逐位等价）。另 `RefreshCardSnapshotTest:206`（幽灵 editRow 丢弃断言）在干净 HEAD 同样 FAIL，走 editRow/baseRows 路径，与 BL-0017 无关。
- **范围**：(1) 由 golden owner 确认 `52380a82…` 为当前正确基线后回填 `GOLDEN_ROCKWELL`（或改用对数据漂移不敏感的锚单/夹具）；(2) 排查 `RefreshCardSnapshotTest:206` 是 driver 种子数据漂移致 baseRows 不再含幽灵 rowKey，还是 refresh 重 expand 链路真回归。
- **依赖**：无（独立测试债；不阻断 BL-0017）。
- **预估规模**：S（1-2 天）
- **验收要点**：两测试在当前 master 复绿（golden 重校准 + refresh 根因厘清）；保留确定性护栏语义。

### [BL-0002] 冲突定位下钻到「具体冲突行」高亮
- **优先级**：P2
- **来源**：spec §4 非目标（第一期明确降级，仅定位到料号+页签级）
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期定位到"料号卡片 + 页签"即停，不滚动/高亮到具体重复行。后端其实已返回 `rowIndices`，可进一步把卡片内对应行做视觉高亮。
- **范围**：把 `rowIndices`（driver 展开行++手动行的合并序）映射到卡片内**可见行序**（需处理墓碑删除行 / 树形布局错位），对冲突行做短暂高亮/滚动定位。
- **依赖**：第一期 locate 联动；行序映射需对齐 `CardEffectiveRows` / `useCardSnapshots` 的可见行口径。
- **验收要点**：高亮行与后端 rowIndices 语义一致；含墓碑行/组合产品场景不错位（守 AP-51/AP-54）。

### [BL-0003] 核价单提交的同类冲突结构化定位
- **优先级**：P2
- **来源**：spec §4 非目标（第一期只覆盖报价单提交）
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期只处理报价单 submit 的行键冲突友好定位。核价单（COSTING 视图）若有同类提交校验，应复用同一套 Drawer + locate 机制做功能对等。
- **范围**：核价单提交链路的行键/数据冲突结构化返回 + Drawer + 定位到核价卡片页签（mainTab='costing'）。
- **依赖**：第一期 Plan 1b；需先确认核价单是否存在等价的提交期硬校验。
- **验收要点**：核价单视图下冲突可定位；与报价单视图共用组件不串味（守 AP-41 prop-drilling 对齐）。

### [BL-0004]（待评估）提交侧行键消歧与渲染侧 `#序号` 对齐
- **优先级**：P2（需求决策待定）
- **来源**：spec §4 非目标 + 历史 [[cpq-rowkey-uniqueness-disambiguation]]（渲染侧已 `#序号` 消歧，提交侧 `RowKeyUniquenessService` 未消歧的设计不对称）
- **状态**：BLOCKED（待产品/架构确认设计意图，不可直接开发）
- **登记日期**：2026-06-29
- **背景**：渲染/编辑绑定侧对撞键自动加 `#0/#1` 消歧，但提交侧仍按原始键硬拦截 → "页面看着正常、唯独提交报错"。是否让提交侧也消歧，会与 Plan 1「组合行键不可重复」的硬约束直接冲突。
- **范围**：仅在产品确认"撞键应自动消歧放行 vs 必须人工去重"后才定方案；**本期不动校验语义**（spec §4 已明确排除）。
- **依赖**：需 `docs/superpowers/specs/2026-06-09-multi-subtotal-conditional-formula-design.md` §7/设计 E 的需求复核。
- **验收要点**：先有书面需求结论再开 spec，不在 Plan 1b 内附带修改。
- 🔄 **2026-07-26 更新（repair-0727，部分解除 BLOCKED）**：
  - **树页签的产品结论已由需求方裁决**（repair-0727 §12 Q2）：树页签判重键 = `computeDedupKey + "@" + __nodeId` —— **同一料号挂不同父节点属合法 DAG 结构，放行；同一节点下两条相同行键仍视为真撞键，照拦**。已实现并实机验证（QT-20260726-0006 提交 422→200）。
  - **剩余仍 BLOCKED 的是非树页签**：渲染侧对撞键自动加 `#0/#1` 消歧、提交侧 `RowKeyUniquenessService` 仍按原始键硬拦的**设计不对称依旧存在**，"撞键应自动消歧放行 vs 必须人工去重"对非树场景仍无产品结论。
  - 另：repair-0727 已让提交校验**消费墓碑**（`deleted_row_keys` + `deleted_tree_nodes`），页面上不可见的行不再参与判重 —— 这解决了"删干净了仍报重复"的死局，但不改变撞键本身的判定语义。

### [BL-0008] 撤回扩到 SENT/ACCEPTED + 客户累计金额回退
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-核价管理财务核价工作台-design.md` §7/§12（第一期撤回明确排除 SENT/ACCEPTED）+ 评审 M-2
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期一步撤回只覆盖 `SUBMITTED/COSTING_REJECTED/APPROVED`；`SENT/ACCEPTED` 涉及已对客户发出 + `accept()` 已累加 `customer.accumulated_amount`（无回退路径），风险高故第一期排除。
- **范围**：撤回扩到 SENT/ACCEPTED；从 ACCEPTED 撤回须回退 `customer.accumulated_amount`（`QuotationService.accept():1623-1627` 的逆操作）；解冻须兼顾已发送态。
- **依赖**：无（独立增强）。
- **预估规模**：M（3-5 天）
- **验收要点**：从 ACCEPTED 撤回后客户累计金额正确回退、无重复扣减；SENT 撤回不留发送残留；其余撤回行为不回归。

### [BL-0009]（技术债）`QuotationWithdrawRequest` 残留实体清理
- **优先级**：P2
- **来源**：第一期 T5 收尾（废弃两步撤回时保留实体供 `delete()` 清理）
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期废弃两步撤回 `QuotationWithdrawService`/`Resource`，但 `QuotationWithdrawRequest` 实体+DTO 保留，因 `QuotationService.delete()` 仍调 `QuotationWithdrawRequest.delete()` 清理关联（无 DB CASCADE）。该实体现已无任何写入方，属残留技术债。
- **范围**：评估彻底移除 `QuotationWithdrawRequest` 实体/表/`delete()` 引用（或给 FK 加 DB CASCADE 后删）；确认无历史数据/外部依赖。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：移除后 `delete()` 仍正常、无悬挂引用、无历史数据丢失风险。

### [BL-0011] 窄窗口 warming-in-progress 前端轮询进度条
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` §3.2/§3.3/§12 + v2 评审 B-3
- **状态**：TODO（未排期）
- **推迟原因**：增强体验。本期 try-lock 返回 warming-in-progress + 简单 spinner + 第一次打开延迟基准已满足正确性与可用性。
- **背景**：窄窗口（刚导入立刻打开同一大单、warm 在飞）首开会等 ~10s。本期显示简单 spinner；更佳体验是轮询 warm 进度（已算行数 / 总行数）显示进度条。
- **范围**：ensure 端点暴露 warm 进度（落库行数 / 总行数）；前端轮询渲染进度条 + 完成自动切快照。
- **依赖**：本期 try-lock 单飞 + ensure 端点已就绪。
- **预估规模**：S（1-2 天）
- **验收要点**：窄窗口首开显示真实进度（非裸 spinner）；进度到 100% 自动读快照、不发 batch。

### [BL-0012] 失败哨兵行「重算此行」卡内交互入口
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` §3.5/§4.4/§12 + v2 评审 C-2
- **状态**：TODO（未排期）
- **推迟原因**：增强。本期失败行已显式「数据待重算」占位 + warn，并可用既有 admin `POST /components/{id}/refresh-template-snapshots` 重算，故卡内按钮非必需。
- **背景**：卡片值 build 确定性失败的行落 `__cardValueFailed` 哨兵 + 占位。更顺手的是占位上直接给「重算此行」按钮，单行触发重算并回灌。
- **范围**：占位组件加「重算此行」入口 → 调单行重算端点 → 回灌该 line 卡片值 → 切快照；与既有 refresh 端点对齐。含核价侧 sentinel 的重算（现有 `refreshCardSnapshot`/`refreshDraftQuoteCards` 仅刷报价侧，核价侧占位本期不带可用重算入口——只显式静态提示，需按侧/按行重算端点）。
- **依赖**：本期哨兵 + 占位渲染已就绪。
- **预估规模**：S（1-2 天）
- **验收要点**：点「重算此行」后该行卡片值正确补回、占位消失、不发整侧 batch 风暴。

### [BL-0013] saveDraft 回 `hasMissingCardValues` 提示以彻底去抖 eager warm
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` §4.1/§12 + v2 评审 E-中
- **状态**：TODO（未排期）
- **推迟原因**：性能增强，非正确性。本期靠「仅导入完成 / 显式手动保存 + 客户端防抖」已避免高频空发。
- **背景**：§3.4 删了 saveDraft 响应里的 `newLines` → 前端无法廉价判断是否真有缺值行。若 saveDraft 回一个 `hasMissingCardValues` 布尔，前端可仅在确有缺值时才 fire warm，彻底消除冗余 ensure（即便幂等返 0 也省一次取锁+查询）。
- **范围**：saveDraft 响应增 `hasMissingCardValues`（一次 `EXISTS(... IS NULL ...)` 廉价查）；前端据此条件触发 warm。
- **依赖**：本期 ensure + warm 触发已就绪。
- **预估规模**：S（1-2 天）
- **验收要点**：无缺值时不发 warm；有缺值时发且只发一次；不回归打开秒开。

### [BL-0014] 报价单列表页批量提交审批的行键冲突明细可读化
- **优先级**：P2
- **来源**：2026-06-29 行键冲突友好定位（Plan 1b）排查发现的第三个提交入口
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：报价提交审批共 3 个入口——向导 `QuotationWizard` + 详情页 `QuotationDetail` **均已**接结构化冲突 Drawer + 定位；列表页 `QuotationList.tsx:188` 批量提交走 `runBatch` 多选场景，撞键单失败仍只在聚合 `message` 里列纯文本，未结构化。
- **范围**：批量场景不适合弹单个 Drawer（多单各自冲突）；改进方向 = `runBatch` 失败明细按单分组、把每单的行键冲突结构化展示（料号/页签/行键），可选一个汇总抽屉列出「哪些单、哪些行键冲突」。
- **依赖**：复用 `RowKeyConflictDTO` + `RowKeyConflictDrawer`（或新建汇总组件）。
- **验收要点**：批量提交撞键时，失败明细能读到具体料号+页签+行键，不再是纯文本拼串。

### [BL-0015] 核价单彻底冻结残留 live 侧信道（防 V6/主数据 republish 漂移）
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-核价单表与报价单核价单状态机重构-design.md` v3 §0/§5.3/§11（务实版接受残留）+ 第二轮 cpq-architect 聚焦评审 N3（焦点一：§5.2"唯一 live 缺口"不成立，实测 4 条 live 侧信道）
- **状态**：TODO（未排期）
- **登记日期**：2026-06-29
- **背景**：第一期"务实版真冻结"只冻结构（`frozen_dto` 含 enrich 后 componentData + gvDefs）+ 依赖既有 costing 卡片/Excel 零计算快照冻值。残留 3 条 live 侧信道——`usePathFormulaCache` 仍 live 求值**未被卡片快照覆盖的**少数 path 单元、`useConfigTemplates`（LIST_FORMULA 配置模板）、比对视图 `comparisonTags`——仅在「模板 / 配置模板 / 全局变量 / 对比标签 / V6 底层主数据 **republish**」时漂移，**不被报价单重做触发**（验收#5 照过）。用户从未提出该边角，第一期接受之以消除 N1（两侧 path-cache 捕获互覆盖）/ N2（非 wizard 提交入口不带 cache）两个脆弱点。
- **范围**：若未来确有审计强需求（历史核价单连 V6/主数据漂移也要 1:1 回看），补：①提交时捕获并冻结 path-cache（**取 quote+costing 两侧 `usePathFormulaCache` 返回值的并集**，避第二轮 N1）②冻结 config-template 值③冻结 comparisonTags 元数据；工作台冻结模式短路对应 live 调用。
- **依赖**：无（独立增强，建立在第一期 `frozen_dto` 之上）。
- **预估规模**：M（3-5 天）
- **验收要点**：模板/GV/V6 republish 后打开历史核价单，path 单元/LIST_FORMULA/比对分组仍是提交时值；工作台冻结模式 `batch-evaluate` 请求 0 次。

### [BL-0016] 切料号版本后失效卡片值（lazy 模型 staleness gap）
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-06-29-lazy-card-values-design.md` 实现期 Task 4 代码评审 Important（范围外观察）
- **状态**：🔻 **[-] 实质失效（2026-07-29 复核）** —— 本条描述的代码**已不存在**
- **⚠️ 2026-07-29 复核结论（task-0729 技术方案期查证）**：本条的核心对象 **`QuotationService.updateLineItemPartVersion` 已被删除**（`QuotationService.java:2624` 注释：`task-0723 B3: 料号版本族整族下线 — updateLineItemPartVersion 已删除`），同期 `regenerateAllSnapshots` 亦删除（`ExcelViewService.java:584` 同款注释），`PartVersionService` / 前端 `PartVersionDrawer` 均已退役，`part_version_locked` 列保留但**不再写入**。**故本条描述的 staleness gap 已无载体。**
  - **不要直接关闭**：若日后 [[BL-0006]] 落地并重新引入"切版本"入口，**同款失效缺口会再次出现**（切版本改了行却不置空 `quoteCardValues`/`costingCardValues`，lazy 模型下 `ensureCardValues` 只按 `IS NULL` 重选）。建议**降级为 [[BL-0006]] 的实现期检查项**而非独立条目。
  - **与 task-0729 无关**：task-0729 的"升版"走**元素价格版本**轴，其卡片值失效由 `需求说明.md` §11.15.2 的重算通道 S3~S5 显式处理，不依赖本条。
- **登记日期**：2026-06-29
- **推迟原因**：超出本期（saveDraft 重建）范围；且**当前潜在**——按 [[BL-0005]] 切版本引擎尚未生效（`BomClosureService` 硬编码 `is_current`、核价 expand 传 `partVersion=null`），故切版本暂不改变卡片值相关数据，staleness 暂不显现。第二期版本切换真生效后必须补。
- **背景**：`QuotationService.updateLineItemPartVersion`（`:2632`，`li.persist()` `:2667` + `regenerateAllSnapshots`）改动行但**不置空** `quoteCardValues/costingCardValues`。lazy 模型下 `ensureCardValues` 只按 `IS NULL` 重选 → 切版本后卡片值非 NULL 不被重选 → 打开仍显示切版本前的陈旧卡片值。
- **范围**：在 `updateLineItemPartVersion` 的 `regenerateAllSnapshots` 之后把该行 `quoteCardValues/costingCardValues` 置 NULL（与 Task 4 的 D-1 同款失效；宜抽 `invalidateCardValues(li)` 私有助手，三处复用：processBatchStage1 / 逐行路径 / 切版本）。
- **依赖**：[[BL-0005]] 版本感知 BOM 闭包（切版本真生效后此 gap 才显现）；[[BL-0006]] 核价切版本调价主体。
- **预估规模**：S（1-2 天）
- **验收要点**：切版本后该行卡片值被重算（打开显示新版本值，不再陈旧）；未切版本的行不受影响；不引入 batch 风暴。

### [BL-0017] 报价料号统一 Spec 2 —— 选配发号统一（CFG-→XXXX-YYMMNNNNNN）
- **优先级**：P1
- **来源**：`docs/superpowers/specs/2026-07-06-报价料号统一-design.md` §9（Spec 1 落地时明确拆出）
- **状态**：[x] **已被覆盖（2026-07-08，选配 Plan 3b/3c）**。`ConfigureProductService.resolvePart`（custom SIMPLE）+ COMPOSITE 父级发号已从 `partNoProvider`(CFG- 前缀) swap 成 `QuoteMaterialNoAllocator.mintAndRegister`，产出报价料号格式 `{4位客户码}-{yyMM}{6位流水}`（正则 `^\d{4}-\d{6,}$`），与本条诉求一致；`config_fingerprint` 落库改为恒 NULL（R1，防跨客户撞生产侧全局唯一索引），复用判定改走销售侧客户维度指纹 `sel_part_signature`（R3）；`isCfg` 拒绝逻辑（`MaterialBomMergeHandler`）按 spec 保留未放开。详见 `docs/superpowers/plans/2026-06-25-savedraft-setbased-rearchitecture.md` 关联的选配 Plan 3b 集成设计 + `ConfigureProductServiceTest` / `ConfigureProductServiceSalesFingerprintTest` R1/R3 断言。
- **登记日期**：2026-07-07
- **推迟原因**：Spec 1（数据基座 + 发号服务）先行；选配是另一子系统。
- **背景**：`PartNoProvider`/`AutoAllocatePartNoProvider` 现按 `part_no_sequence` 发 `CFG-{符号}-{6位流水}` 作选配 `hf_part_no`；统一后应复用 `QuoteMaterialNoAllocator` 发 `XXXX-YYMMNNNNNN`（选配 `XXXX` 客户码取自选配所在报价/客户上下文）。`ConfiguratorInstanceService` 接入；重估 `MaterialBomMergeHandler.isCfg` 拒绝逻辑（选配号统一后是否放开回填）。
- **前置条件**：✅ Spec 1 的 `QuoteMaterialNoAllocator` 已就绪。
- **预估规模**：M（3-5 天）
- **关联**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md`（**该方案是本条的严格超集**：含发号统一 `CFG-→XXXX-YYMMNNNNNN` + `isCfg` 重估，再叠加参数池/行业模板/销售侧指纹去重；若该方案落地则本条随之完成，**勿重复立项**）。

### [BL-0018] 报价料号统一 Spec 3 —— 客户料号维护页面
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-07-06-报价料号统一-design.md` §9
- **状态**：TODO
- **登记日期**：2026-07-07
- **推迟原因**：UI 子系统，依赖 Spec 1 数据基座。
- **背景**：`material_customer_map` 加了 `production_no`（生产料号，报价侧后补）。需 UI 手工维护三码映射（客户料号 / 报价料号 / 生产料号）、回填 `production_no`、`source=MANUAL` 标记。
- **前置条件**：✅ Spec 1 表结构（`system_type`/`production_no`）已就绪。
- **预估规模**：M（3-5 天）

### [BL-0019] 清理 9 字头发号死代码 + 修历史 VersionedV6MasterDetailTest
- **优先级**：P2
- **来源**：报价料号 Spec 1 实现期子代理观察（范围外）
- **状态**：TODO
- **登记日期**：2026-07-07
- **推迟原因**：超出 Spec 1 范围的连带清理。
- **背景**：① `MaterialMasterRepository.maxNineLeadingMaterialNo`/`lockForMaterialNoGeneration` 在 Spec 1 后已无生产调用方（`MaterialNoResolver.generateNextMaterialNo` 已删），仅剩 `MaterialMasterRepositoryTest` 一个测试引用 → 是"测死代码的测试"，宜连方法+测试一并删。② `VersionedV6MasterDetailTest` 两个用例（`materialBom_nullCharacteristic_idempotent`/`childChange_bumpsMaster`）在 **master 上就已失败**（`VersionedV6Writer` 的 `CHILD_UQ=Map.of()` 空实现致 `material_bom_item` 无冲突目标；两名子代理用 git stash 背靠背验证与本次改动无关）→ 属独立历史 bug，需专项修 `CHILD_UQ` 登记。
- **前置条件**：无
- **预估规模**：S（1-2 天）

### [BL-0025] `CostingSummaryService.compute()` 料号级查询忽略 part_version（多版本激活后跨版本累加）
- **优先级**：P2
- **来源**：2026-07-01 核价单深度复核（亲验实库 `costing_part_*` distinct part_version=1）
- **状态**：TODO（潜伏，未排期）
- **登记日期**：2026-07-01
- **推迟原因**：**当前不触发**——实库各 `costing_part_*` 表 distinct `part_version` 均 = 1（多版本未激活）；与 [[BL-0005]] 版本切换尚未生效一致。
- **背景**：compute 的所有料号级查询（`CostingSummaryService.java:177-185`：matBom/element/process/tooling/design）**只按 `hfPartNo+isActive`、不带 part_version 维度**，weight 用无 `ORDER BY` 的 `firstResult()`。若同一 `hf_part_no` 出现多个 `is_active=true` 的 part_version：成本类**跨版本累加**（膨胀）、weight 取任意行（非确定）。
- **范围**：随第二期版本感知改造（[[BL-0005]]/[[BL-0006]]）一并给 compute 传入并过滤 `part_version`；weight 查询加确定性 `ORDER BY`。
- **依赖**：[[BL-0005]]（多版本真正启用后此 gap 才显现）。
- **预估规模**：S（1-2 天，随 BL-0005/0006 同步做）
- **验收要点**：多版本激活后 compute 只取指定 part_version 数据；未激活时逐位不变。

### [BL-0026] 核价侧低危隐患群（状态机旁路 / 渲染死代码 / 兜底反设计）
- **优先级**：P2
- **来源**：2026-07-01 核价单深度复核（多为审计代理报告、主线未逐一独立复核，标 PLAUSIBLE）
- **状态**：TODO（未排期，逐条确认后可拆分）
- **登记日期**：2026-07-01
- **背景/清单**（按面归类，均低危或旁路）：
  - **状态机**：遗留 `QuotationService.approve()/reject()`（`:1266,1298`）旁路核价流、不更新 `CostingOrder`，直连 API 调用可致报价单死锁 + 工作台僵尸排队项（前端 0 调用，仅 API 可触发）；`frozen_dto` 冻入 `status="DRAFT"`（submit `:910` 冻结早于 `:912` 赋 SUBMITTED，展示偏差）；`withdraw` 用 `findLatest` 覆写终态 `REJECTED`→`WITHDRAWN` 丢审计；`/copy`、`/delete`（含 `/approve /reject`）缺 `@RoleAllowed`，`RoleFilter` 无注解即放行（安全）。
  - **BOM/缓存**：`CardSnapshotService.java:1790` `recursive` 在 `dc[1]` 非 Boolean 时兜底 TRUE（与「默认关」相反，当前被 `NOT NULL DEFAULT false` 屏蔽）；单值 `$view.col` 路径 `DataLoader` `resultCache` key 缺 componentId（同名导入副本条件串号，守 [[cpq-sqlview-cache-key-needs-component-dim]]）；DAG 单节点多业务行树形「首条胜」展示瑕疵。
  - **渲染**：旧 `CostingSheetView` + `/costing-sheet` + `CostingSheetService` 是死组件、读「已无人维护」的 `costing_sheet` 表（重新挂回即双源不一致）；frozen 模式 QUOTE 分支在历史单 `quoteCardStructure` 缺失时回落 live `/templates` 请求（`ReadonlyProductCard.tsx:213,219`）。
- **范围**：逐条确认后拆分处理——优先下线遗留 `/approve`·`/reject`（或补 CostingOrder 联动）+ 补写端点 `@RoleAllowed`（安全项）；其余按需修。
- **依赖**：无。
- **预估规模**：M（逐条确认 + 修，主要成本在确认）
- **验收要点**：遗留旁路端点不再能制造报价单死锁；写端点有鉴权门；死代码/兜底反设计逐条裁决（修或标注保留）。

### [BL-0029] 核价递归 SQL 校验器「空 seed 盲区」—— 保存通过、渲染必崩的一类 SQL 漏网
- **优先级**：P1
- **来源**：2026-07-03 QT-20260703-1928 核价卡片全空根因定位
- **状态**：[-] **保存期不可行 → 由 [[BL-0030]] 兜底（2026-07-03 结论）**。实测:①空 seed + LIMIT 0 漏（现状）；②`EXPLAIN` 也抓不到（`cannot compare dissimilar column types` 是**运行期**、非 plan 期错）；③合成非空 seed 若不递归（占位料号无 BOM 子件）→ CYCLE 不触发比较 → 仍抓不到。即那类「只在真数据真递归时暴露」的错**无法在保存期用空/合成 seed 拦下**。真正安全网 = BL-0030（render 失败显式透出错误原文到前端），已实现。若将来仍要保存期兜底,唯一路是「探测库里一个真实有 BOM 子件的料号做 seed 真跑一层」,但耦合数据、且不同递归 SQL 引用的表未知,性价比低。**本条降级为 wontfix/观察,不单独修。**
- **登记日期**：2026-07-03
- **背景**：`CostingTreeSqlValidator.validate()` 用**空 seed** `ARRAY[]::text[]` + `LIMIT 0` 做 dry-run。递归 CTE / `CYCLE` 的**运行时错在空数据下不触发**（0 行→不进递归→不做 CYCLE 行比较），导致「保存期校验通过、真实 render 必崩」的 SQL 漏网。**实证**：用户存的递归 SQL 缺 `material_no::text`，seed 绑 `text[]`(见 `CostingTreeRenderService.queryRecursive` `createArrayOf("text")`)、递归列 `varchar` → `CYCLE material_no` 报 `cannot compare dissimilar column types` → render 崩 → 快照 NULL → 全 77 卡片空。校验器却因空 seed 放行。
- **范围**：dry-run 改为「用一个**非空样例 seed**探测」（如取库里任一有 BOM 子件的真实料号，或注入 1 个占位料号让递归真正走一层 + CYCLE 真比较）；至少要能触发递归分支的类型/语义错。评估样例 seed 来源（固定占位 vs 探测库）。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：一条缺 `::text`（或其它仅运行时暴露）的递归 SQL 在**保存期**即被拦下，不再能存成生效配置。

### [BL-0030] 核价树 render 失败被静默吞成空卡片（AP-31 静默失败族）—— 无任何前端可见报错
- **优先级**：P1
- **来源**：2026-07-03 同上根因定位
- **状态**：[x] **已完成（2026-07-03，master `092f48a`）**。`CardSnapshotService` 批量层 `render()` 加 try/catch:失败时不上抛（否则整单 500+全 NULL→前端无限「加载中…」），逐 li 落带错误原文的失败哨兵 `{tabs:[],__cardValueFailed:true,__errorMsg:"核价渲染失败: …"}`；前端 `cardValueFailed.ts#getCardValueError` 取原文，`QuotationStep2` 核价卡片占位以 error Alert 显式展示原文 + 指引查「核价树配置」。配置员一眼定位，取代翻后端日志。
- **登记日期**：2026-07-03
- **背景**：`CostingTreeRenderService.render()` 抛错（递归 SQL 崩 / 无生效配置 / 页签 $view 崩）后，被 `CardSnapshotService.buildCostingCardValues` 的 `try/catch` **catch 成返回 null + 仅 `LOG.warnf`** → `costing_card_values` 留 NULL → 前端**只看到空卡片、无任何红错**。用户无法自知是递归 SQL 崩了，只能靠翻后端日志。另页签 $view 忘输出 `material_no` 时也是渲染期 WARN + 静默落选（已有 WARN 守卫但 UI 不可见）。
- **范围**：让核价树渲染失败对用户**可见**——如 `ensure-card-values` / 渲染响应带回一个「核价树配置错误 + 具体消息（递归 SQL 报错原文 / 未配置生效 SQL / 页签缺 material_no）」的结构化提示，前端在核价卡片区显式提示而非空白。区分「真无数据」与「配置/SQL 报错」两种空。
- **依赖**：与 [[BL-0029]] 同批修更省（都属核价树配置期防呆）。
- **预估规模**：M（3-5 天，含前端提示位）
- **验收要点**：递归 SQL / 页签 $view 报错时，核价卡片区出现明确错误提示（含原因），而非静默空白。

### [BL-0032] 选配模板版本 / 发布状态机
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md` §9
- **状态**：TODO（未排期）
- **登记日期**：2026-07-07
- **推迟原因**：一期模板"直接生效"即可满足；草稿/发布为增强。
- **背景**：`sel_template` 一期无版本/发布态，保存即生效。未来需草稿→发布、历史版本留痕，避免编辑中的模板影响线上选配。
- **范围**：给 `sel_template` 加状态机（草稿/发布/停用）+ 版本；选配运行时只取"已发布"版本。
- **依赖**：选配模板 CRUD 落地。
- **预估规模**：M（3-5 天）
- **验收要点**：模板可草稿编辑不影响线上；发布后选配取新版本；历史版本可查。

### [BL-0033] 选配组合体报价料号 BOM 关系落表
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md` §4.3/§9
- **状态**：TODO（未排期）
- **登记日期**：2026-07-07
- **推迟原因**：组合体 BOM=N 子件的完整关系表需单列设计；现役 `insertMaterialBomAssemblyV6` 仅雏形。
- **背景**：组合产品选配的组合体报价料号 BOM（=N 个子件报价料号）+ 组合工艺挂载关系的落表口径未定。
- **范围**：设计组合体↔子件报价料号 BOM 关系落库（对齐现役 `insertMaterialBomAssemblyV6`），组合工艺按 `sales_part_no` 落。
- **依赖**：子件报价料号落库稳定；[[BL-0031]]（工序承载表）。
- **预估规模**：M（3-5 天）
- **验收要点**：组合体报价料号 BOM 正确记录 N 子件 + 组合工艺，核价/渲染可读。

### [BL-0034] 选配料号编辑重算撞指纹的处置 / 合并策略
- **优先级**：P2
- **来源**：`docs/superpowers/specs/2026-07-06-选配模板方案-design.md` §5.4（时机 B）/§9
- **状态**：TODO（未排期）
- **登记日期**：2026-07-07
- **推迟原因**：spec §5.4 已定一期"拦截并提示复用"；合并/并存是后续高级形态。
- **背景**：允许编辑已生成报价料号的材质/元素/工序 → 重算销售侧指纹 → 若撞该客户已有料号，一期仅"拦截提示改为复用"。更完整的合并（把两个料号合一、迁移已引用报价单）留后续。
- **范围**：撞指纹时的合并（引用迁移 / 软删旧料号）或并存策略；含被引用报价单/核价单的影响面处理。
- **依赖**：`sel_part_signature` 唯一约束方案落地。
- **预估规模**：M（3-5 天）
- **验收要点**：编辑撞指纹后按所选策略处置，不产生悬挂引用 / 重复料号。

### [BL-0035] 「生产料号」BNF 逻辑名重定向 + 概念收敛（架构级）
- **优先级**：P1
- **来源**：task_0708 导入落库料号纠偏澄清（决策外溢；`docs/table` 落库方案配套）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-08
- **推迟原因**：本次任务只做 V6 导入落库；BNF 映射重定向牵动公式引擎，需 architect 评审 + 公式回归，独立立项更稳。
- **背景**：「生产料号」概念现三处分裂 —— ① `SchemaContext.java:146` 把 BNF 逻辑名`生产料号`映射到**已废弃**的 `mat_part`；② `internal_material`（生产料号管理 UI）；③ task_0708 新落的 V6 表 `production_no` 列。若公式/组件引用 BNF 路径`生产料号`，会解析到废弃 `mat_part` 而**非**新落的 `production_no`，导致"落了数据公式取不到"。
- **范围**：把 `SchemaContext` 逻辑名`生产料号`映射从 `mat_part` 改指向 live 源（`production_no` / `internal_material`，方案待定）；盘点并回归所有引用`生产料号`/`mat_part.*` BNF 路径的公式模板。
- **前置条件**：✅ 已就绪（task_0708 的 `production_no` 落库已合入 master，2026-07-09 结案）。
- **依赖**：[[BL-0036]]（若与 mat_part 退役合并推进）。
- **预估规模**：M（3-5 天）
- **验收要点**：引用`生产料号`的公式解析到 live 数据（非 mat_part）；公式回归全绿；无静默取空。

### [BL-0036] `mat_part`（V44）全面退役 → 迁 V6 等价物（architect 主导 · 高风险）
- **优先级**：P2
- **来源**：task_0708 调研外溢（用户误以为已退役，实测仍为多块核心活跃底座）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-08
- **推迟原因**：动 **2 个核心基线**（报价渲染 + 公式/组件），多周工程、高风险，必须 `cpq-architect` 出迁移方案后分阶段实施，不可并入落库任务。
- **背景**：`mat_part` 被标记 V44 废弃（AP-53），但实测仍是活跃底座：**7 个现役视图** JOIN 它（`v_composite_child_materials/processes/elements/weights`、`v_part_material_recipe`、电镀方案视图，喂组合产品渲染）；**选配加产品**写它（`ConfigureProductService`：config_fingerprint / material_recipe_id / product_type）；**单重** `mat_part.unit_weight` 被报价+核价公式 BNF 引用 **19 处**；产品维度 width/length/height；共 **47 个 java 文件 + 20 个迁移**引用。
- **范围**：组合子件视图迁 V6；选配写入迁 V6；`unit_weight`/维度 BNF 路径迁 V6；PartVersion / 导入 staging 解耦；数据迁移 + 老表 DROP；配套清理过时注释（`QuotationDTO.java:159` 等把 mat_part 当主档的旧注释）。
- **依赖**：V6 表覆盖组合/选配/单重全部维度；[[BL-0035]]。
- **预估规模**：L（1 周以上，实为多周）
- **验收要点**：组合产品 / 选配 / 单重公式 / 电镀 全部改读 V6 且 E2E 全绿；`mat_part` 可安全 DROP 无残留引用。

### [BL-0037] V6 基础资料查询页料号列标签校正 + 可选展示新列
- **优先级**：P2
- **来源**：task_0708 前端影响面评估（前端非强制改动项）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-08
- **推迟原因**：不在 task_0708「数据正确落库」验收范围内；属语义一致性优化，可择期。
- **背景**：task_0708 后 V6 表 `material_no` 值语义翻转为**销售料号**。直接读 V6 表的前端页（`V6BomQueryTab`/`V6BomItemDetailDrawer`、`CustomerMaterialMappingTab`、物料主数据页）若列标签仍写旧名（宏丰料号/生产料号），会"标签写 X 实显销售料号"错位；按生产料号搜索也会搜不到。
- **范围**：校正上述页面料号列标签为「销售料号」；按需增列展示 `production_no`（生产料号）/ `material_part_no`（材质料号）；搜索键口径对齐。
- **前置条件**：✅ 已就绪（task_0708 落库已合入 master，2026-07-09 结案）。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：V6 查询页标签与实际值语义一致；如加新列则正确回显。

### [BL-0038] task_0708 遗留验收数据二次验证（R1 production_no 取值 + R4 报价铸号回归）
- **优先级**：P2
- **来源**：task_0708 测试报告 §四 未覆盖遗留项（R1/R4）
- **状态**：进行中 —— ✅ **R1 已闭合**（repair-1 用自洽文件正向验证 production_no=生产料号≠销售料号，QA 报告 + 技术总监亲验 SQL 通过，2026-07-09）；⏳ R4（报价铸号正向路径）仍待补数据
- **登记日期**：2026-07-09
- **推迟原因**：官方测试文件数据不具备触发条件，非代码缺陷；task_0708 已结案，遗留为"补样例二次验证"。
- **背景**：
  - **R1**：官方核价 6.0 文件「生产料号」列全空 → `production_no` 落库全 NULL，取值映射（生产料号值→production_no 且 ≠ 销售料号）未被官方数据证伪。**开发方已用两列都填的补充文件自测 `capacity.production_no=PN-3120018220` 逐值吻合**，逻辑已验证，仅缺官方数据走查。
  - **R4**：官方报价 V3「投入料号」列全空 → V308「组件缺料号→按名铸号 `XXXX-YYMMNNNNNN`」正向路径未触发/未回归。反向（Q04 不再错误铸号）已由 TC-B5b 覆盖通过；铸号代码本次未改动。
- **范围**：制作/并入含真实生产料号值的核价样例 + 含"有名称无料号"组件行的报价样例，各正式重导一次，断言 `production_no=对应生产料号值` 及生成 `XXXX-YYMMNNNNNN` 号正确落库。

### [BL-0039] 导入引用校验：成本行销售料号必须在客户映射表存在（防静默断链/畸形数据）
- **优先级**：P2
- **来源**：task_0708 repair-1 复验（RR-1：新文件映射表销售料号裸号/含元素码，与成本表零交集，导入不报错静默建断链记录）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-09
- **推迟原因**：本质是导入防呆增强，非某次落库缺陷；repair-1 只修数据不加校验，另立项评估。
- **背景**：成本各 Sheet 引用的 `销售料号` 若在 `宏丰-客户料号对应关系`（客户映射表）不存在，或映射表把元素/材质编号误列为销售料号，当前导入**不报错**——照样把断链/畸形记录写库（如 material_master 出现同产品裸号+S-号两行、元素码被当销售料号登记）。只能靠人工比对发现。
- **范围**：导入时对成本行 `销售料号` 做引用校验（须在客户映射表存在），不匹配则告警/记入 `failedRows` 或预检报告；可选：映射表销售料号列疑似元素码（与元素/材质编号集合交集）时提示。需定"硬失败 vs 软告警"口径。
- **前置条件**：无。
- **依赖**：无。
- **预估规模**：M（3-5 天）
- **验收要点**：畸形/断链导入数据被导入层主动拦截或告警，不再静默落库。
- **前置条件**：✅ 已就绪（task_0708 代码已合入 master）。
- **依赖**：无。
- **预估规模**：S（1-2 天，主要成本在造数据）。
- **验收要点**：R1/R4 两条路径各跑通一次官方级复验，消除数据遗留。

### [BL-0041] 材质↔料号关联功能（导入绑定 / 关联料号 tab / 智能建议）
> 注：原登记为 BL-0039，与并发会话「导入引用校验」撞号，2026-07-09 改为 BL-0041。
- **优先级**：P1
- **来源**：`dev-docs/task-0708-材质库规范澄清/`（材质库规范化需求澄清，Q4 明确本期推迟）
- **状态**：TODO（**2026-07-09 业务方明确"先不做、继续隐藏"**；前置已就绪但暂不排期）
- **登记日期**：2026-07-09
- **推迟原因**：本期聚焦"材质库规范化 + 导入"，料号关联另属一块，用户明确"料号这块的功能后期再议"。2026-07-09 澄清时业务方再次确认**先不做、关联料号 tab 继续隐藏**。
- **背景**：材质库导入本期**只读** `材质编号`+`材质对应元素` 两 sheet，`材质对应料号` sheet 忽略；编辑抽屉「关联料号」tab 本期**隐藏**；现有绑定基建（`material_master.material_recipe_id`、`/material-recipes/{id}/parts|bind-parts|unbind-parts|search-parts|suggest-bindings|confirm-bindings`、`MaterialRecipePartsTab.tsx`）**保留未删**、仅不挂载/不调用。
- **范围**：重新启用"材质↔料号"绑定 —— 恢复编辑抽屉「关联料号」tab + 顶部「智能建议」入口；评估是否需从 Excel `材质对应料号` sheet 批量导入绑定；对齐 V6 落库口径（`material_master.material_recipe_id`）。
- **依赖**：**task-0708 材质库规范化（材质编号/元素/element 主表）必须先落地**——料号绑定需以规范化后的材质编号为锚。
- **前置条件**：✅ **已就绪**（task-0708 2026-07-09 验收通过：material_recipe 253 落库 + element 主表 39 元素）。
- **预估规模**：M（3-5 天）
- **验收要点**：材质编辑抽屉可绑定/解绑料号并落 `material_recipe_id`；智能建议可用；选配抽屉反查材质不回归。

### [BL-0040] 元素主表管理 UI（元素字典 CRUD）
- **优先级**：P2
- **来源**：`dev-docs/task-0708-材质库规范澄清/`（Q5）→ **已澄清立项 `dev-docs/task-0709-元素主表管理/`（2026-07-09）**
- **状态**：**[x] 已完成（2026-07-09 合 master `c27f604`）**。B 模型全落地并终验收通过（技术总监独立查隔离库 cpq_db_elemtest + 代码核实）：element_no 不可改业务主键(V319 补 Au/CdO 号)、material_recipe_element 加 element_no(V320 628行全回填)、符号锁(被引用改符号 409)、只软删、导入按编号 upsert 不覆盖人工值、253/1 零回归、定价 join 通、不动选配/定价边界；前端「元素」页签 + 符号锁 UI。8081 `/elements` 404→401 上线、cpq_db 已应用 V319/V320。
- **登记日期**：2026-07-09
- **推迟原因**：本期 `element` 主表只作字典（seed + 导入按符号 upsert 回填），无独立管理界面需求；元素 CRUD 属增强。
- **背景**：task-0708 新建 `element(element_code 符号 PK / element_name 中文 / element_no 编号 / status)`，仅由 seed + 导入维护；管理端无处增删改元素、无处补录字典外新符号的中文名。
- **范围**：主数据维护下增「元素」管理页（SelectableTable + 工具栏动作 + Drawer 编辑），支持元素增删改停用、补录中文名、维护元素编号；与导入 upsert 语义对齐（不冲突）。
- **依赖**：task-0708 的 `element` 主表已建。
- **前置条件**：✅ **已就绪**（task-0708 2026-07-09 验收通过：`element` 主表已建、39 元素含数字牌号；补录中文名的实际缺口 = 191/206/223/258/721 五个数字牌号暂无中文名）。
- **预估规模**：S（1-2 天）
- **验收要点**：可在管理页维护元素字典；补录的中文名不被后续导入的符号占位覆盖；停用元素不破坏已引用它的材质渲染。

---

### [BL-0042] 报价侧产能 `Q14 组装加工费` 触发列拉平（报价/核价升版口径统一）
- **优先级**：P2
- **来源**：`dev-docs/tesk-0709-…版本升级与版本维护/`（C12 / 版本升级规则文档 §5.4）；本期核价 `capacity` 已去触发列，报价侧留档暂不动
- **状态**：TODO（未排期）
- **登记日期**：2026-07-10
- **推迟原因**：本期范围 = 仅核价侧（C13 报价侧零代码改动）；报价 `capacity`(Q14) 改触发列属报价侧改动，另立项。
- **背景**：核价 `capacity` 本期去触发列 → 金额/币种/单位变化也升版（甲·任一值变即升版）；报价 `Q14 组装加工费` 仍保留触发列 `process_no,seq_no`（金额原地更新不升版）。二者短期内对"产能金额是否升版"口径不一致。
- **范围**：评估报价侧 `Q14CapacityHandler`（或对应 handler）是否去触发列与核价拉平；若拉平需跑报价 E2E 回归确认不破坏现有升版行为。
- **依赖**：本任务（核价侧）落地后再评估是否需要拉平。
- **预估规模**：S（1-2 天）
- **验收要点**：拉平后报价/核价 `capacity` 升版口径一致；报价重导回归 `failedRows=0`、既有版本线不被误升。

### [BL-0043] `pricing_price` 表纳入版本化（报价侧多 sheet 附带写、当前无版本历史）
- **优先级**：P2
- **来源**：`dev-docs/tesk-0709-…版本升级与版本维护/`（版本升级规则文档 §四·差异④）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-10
- **推迟原因**：`pricing_price` 附带写发生在**报价侧** handler（Q07/Q08/Q10/Q11/Q13/Q15/Q17），本期报价侧不动（C13）；核价 P* handler 不写 `pricing_price`，本期无触及。
- **背景**：报价多张 unit_price sheet 除写 `unit_price` 外还"另写 `pricing_price` 表"，该表本身**未接版本化**（无 is_current/版本线）。与 unit_price 主线的版本化不对齐。
- **范围**：评估 `pricing_price` 是否需随对应 unit_price 组同步版本化（同 groupKey/同版本号），或明确其"衍生表、不独立版本"定位并留档。
- **依赖**：BL-0042 一类报价侧改造窗口；需先厘清 `pricing_price` 下游取数是否依赖版本。
- **预估规模**：M（3-5 天）
- **验收要点**：`pricing_price` 与其来源 unit_price 组版本一致（若纳入）或有明确"不版本化"决策留档；下游取价不断链。

### [BL-0044] `material_version_mgmt`（P04 核价版本包）"按版本号钉住历史价"能力
- **优先级**：P2
- **来源**：`dev-docs/tesk-0709-…版本升级与版本维护/`（C6 / 版本升级规则文档 §5.1 A 组 P04）
- **状态**：TODO（待评估）
- **登记日期**：2026-07-10
- **推迟原因**：本期核价取数口径 = A·永远取 `is_current=true` 最新版（C6），不按版本号复现历史价；P04 版本包"用元素/材料/汇率版本号钉住历史核价"的职责随之退化，划出本期范围（与 C3"全局登记表暂不纳入"一致）。
- **背景**：`material_version_mgmt`（P04 核价版本 sheet）原设计承载"一张核价单锁定当时的元素价/材料价/汇率版本号 → 复现历史核价"。当前业务永远取最新价，该能力未启用。
- **范围**：若未来需求要"复现历史核价/审计当时价格"，再设计版本包引用链（P01/P02/P03 保留业务版本号 + P04 登记 + 核价取数按版本号回溯）。属"版本感知取数"大工程，与 [[BL-0005]] 版本感知 BOM 闭包同族。
- **依赖**：[[BL-0005]] 版本感知 BOM 闭包展开（历史复现的前置能力）。
- **预估规模**：L（1 周以上）
- **验收要点**：能按核价单锁定的版本号复现当时的元素/材料/汇率价与子料号 BOM；不影响默认"取最新"路径。

### [BL-0046] 物料BOM 组成件 `component_no` 按 `calc_type` 动态下拉
- **优先级**：P2
- **来源**：task-0712（C13）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：本期 C13 定为自由文本，动态下拉实现稍复杂、非核心路径。
- **背景**：P06 物料BOM 组成件按 `calc_type`（材料/元素）语义不同——材料→`material_master`、元素→`element`；本期统一自由文本、名称尽力解析。
- **范围**：`EditableSheetTable` 支持按同行 `calc_type` 动态切换 `component_no` 下拉源；后端 lookup 复用。
- **依赖**：无（前端为主）。
- **预估规模**：S（1-2 天）
- **验收要点**：切 `calc_type` 时组成件下拉源正确切换、选中带出对应名称。

### [BL-0047] 核价基础数据历史版本"恢复为当前"（回滚）
- **优先级**：P2
- **来源**：task-0712（C7）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：本期历史版本定为纯只读，回滚非必需。
- **背景**：料号核价维护抽屉可切历史版只读查看；用户可能需要"把某历史版内容恢复为新当前版"。
- **范围**：新增"恢复为当前"操作，取历史版行集作为新提交走升版（复用 `saveGroup`，结果 UPGRADED/CREATED）。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：从历史版一键恢复生成新当前版、内容=该历史版、旧当前版 `is_current=false`。

### [BL-0048] 全局 4 表（P01/P02/P03/P21）维护入口
- **优先级**：P2
- **来源**：task-0712（C1）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：全局表不挂销售料号、不适配"料号核价"抽屉模型，本期排除，另立项。
- **背景**：元素核价价格(P01)/材料核价价格(P02)/汇率(P03)/电镀方案(P21) 各按自身轴独立升版，需独立维护页（列表+版本切换+编辑）。
- **范围**：为 4 张全局表各建维护入口，可复用本任务元数据驱动 registry/`EditableSheetTable` 模式（去料号维度轴）。
- **依赖**：无。
- **预估规模**：M（3-5 天）
- **验收要点**：4 表可查/改/升版，取数取 `is_current` 最新版。

---

### [BL-0049] create-quotation 建行失败当前 500 掐整单 → 评估降级为「空单+前端兜底」
- **优先级**：P2
- **来源**：task-0712 核价展示修复 最终评审 Important-2
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：本期尊重 spec §5「建单+建行强一致」，materializeLines 在 createQuotation 同事务内；Critical 修复(候选正确框定)后抛错风险已低，且幂等重入可安全重试，暂不改事务模型。
- **背景**：旧流程 create-quotation 恒建出（可能空）报价单，明细行由前端 autoPopulate 补；新流程把建行塞进强一致事务，materializeLines 抛异常会使整单创建 500 且报价单也不落库（相对旧行为的可用性回归）。
- **范围**：评估将 materializeLines 移到建单事务提交后（Resource 层 materialize 的 step 0，best-effort try/catch），失败则留空单 + 前端 autoPopulate 兜底（与 Task5 的 backendBuiltLinesRef=false 分支天然衔接）；权衡 §5 原子性 vs 可用性。
- **依赖**：无。
- **预估规模**：S（1-2 天）
- **验收要点**：模拟 materializeLines 抛错 → 报价单仍创建成功（空单）、前端进编辑页 autoPopulate 兜底建行，不 500。

### [BL-0050] create-quotation 降级时前端消费 CommitResult.warnings/cardValuesReady 给用户提示
- **优先级**：P2
- **来源**：task-0712 核价展示修复 最终评审 Minor-4
- **状态**：TODO（未排期）
- **登记日期**：2026-07-12
- **推迟原因**：后端已回填 warnings/cardValuesReady/costingTreeRows，但前端导入流未读；降级时靠下游失败哨兵占位兜底（不误导），非阻断。
- **背景**：物化失败时 cardValuesReady=false + warnings 列出料号，用户在创建当下拿不到 toast/banner 提示，只能进编辑/详情页被动看到失败哨兵。
- **范围**：`QuoteBasicDataImportV6Drawer`/导入流创建成功回调处，若 `data.cardValuesReady===false` 或 `data.warnings?.length` 弹一次 `message.warning` 列出未就绪料号。
- **依赖**：无。
- **预估规模**：S（1 天）
- **验收要点**：降级建单后前端弹 warning 提示，正常建单无多余提示。

### [BL-0051] task-0713 收尾：`:spineKeys` 死代码清理
- **优先级**：P2
- **来源**：task-0713 backtask B8（计划内可选，本期跳过）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-14
- **背景**：`SpineKeysMacro`/`SpineKeysContext` 已确认死代码（`SqlViewExecutor` 侧从未读取、纯 no-op）；task-0713 用 `CostingTreeVarsContext`+`VersionFilterMacro` 取代。B8 清理与新宏解耦、低风险，本期未做。
- **范围**：删 `SpineKeysMacro`/`SpineKeysContext` 及残留引用（`zpj_view` 等 $view 里的 `:spineKeys` 文本 V334 已被 versionFilter 改造）。
- **依赖**：无。**预估规模**：S。
- **验收要点**：删后核价树渲染无变化、E2E 绿。

### [BL-0052] task-0713：`CostingSubtotalUtil` 模板缺 SUBTOTAL 组件时静默返 0 → 是否显式报错
- **优先级**：P2
- **来源**：task-0713 3a 返修实现期披露
- **状态**：TODO（待 PM 裁决）
- **登记日期**：2026-07-14
- **背景**：`CostingSubtotalUtil.extractUnitSubtotal` 对「找不到 SUBTOTAL 组件 / 公式为空」静默返 ZERO（不抛错不 warn）。若未来某核价模板忘配 SUBTOTAL 组件，单据总价会悄悄变 0 而非报错——当前是有意的宽松兜底。
- **范围**：若要「配置缺失显式可见」，加配置期 lint / 渲染期 warn。
- **依赖**：无。**预估规模**：S。
- **验收要点**：核价模板缺 SUBTOTAL 组件时有明确提示，不再静默 0。

### [BL-0053] task-0713：版本切换扩到其余核价模板克隆副本 + DELETE override 端点
- **优先级**：P2
- **来源**：task-0713 backtask 已知限制
- **状态**：TODO（未排期）
- **登记日期**：2026-07-14
- **背景**：本期只精确改了 7 个已核实的 `component_sql_view` 物理行（"BOM树演示-核价模板"5 件套 + pj/ys_view）；其余克隆副本（如"核价模板0603"）$view 未加 `view_version`+`versionFilter`，暂不支持版本切换。DELETE override 端点（api.md §5 标可选）未实现（用「切回 is_current 版本」代替）。
- **范围**：给需版本切换的其余核价模板 $view 加约定列+宏（机制通用、平台零改动）；按需实现 DELETE override 端点。
- **依赖**：无。**预估规模**：S-M。
- **验收要点**：目标核价模板的 $view 输出 view_version 即可版本切换；DELETE 复位生效（若实现）。

### [BL-0054] task-0713 连带：`GoldenCardValuesEquivTest` golden 常量重校准
- **优先级**：P2
- **来源**：task-0713 后端回归排查（金标准 hash 漂移）
- **状态**：TODO（需 golden owner 重新捕获，非 task-0713 引入 bug）
- **登记日期**：2026-07-14
- **背景**：task-0713 给 `buildTabNode` 加 `componentType` + 让 SUBTOTAL tab 真出 subtotal（修 3a）+ 顺手修 `zpj_view` bug（"子配件"页签从恒空变真实出数据），使核价卡片值**合法变化** → `GoldenCardValuesEquivTest`/`NonRecursiveCostingBucketEquivTest` golden hash 漂移。经临时回退验证：漂移是「值合法变化」非回归，且 golden 常量本就已过期（与 [[cpq-golden-cardvalues-preexisting-drift]] 一致）。**未擅改 golden 常量**，留 owner 复核重捕获。
- **范围**：golden owner 确认新基线正确后回填常量（或改用对数据漂移不敏感的锚单/夹具）。
- **依赖**：无。**预估规模**：S。
- **验收要点**：两测试在当前 master 复绿、保留确定性护栏。

### [BL-0055] 报价单删除行 Phase 2：内容指纹身份（uniqFp）根治重复行"连删"
- **优先级**：P2
- **来源**：删除删错行 Phase 1 交付后重评估（2026-07-15）
- **状态**：TODO（依赖 partno 专项，暂缓）
- **登记日期**：2026-07-15
- **背景**：Phase 1（commit `9245555`）已把墓碑匹配从 effKey+fp 双命中改为 **fp 内容身份单键**（真根因=前后端 computeRowKey 算的 effKey 不一致），删除删对行、值不串、多次重渲染稳定。**唯一残留**=字节级完全重复行 fp 相同 → 删一个"连删"同 fp 行（实测：删 AgNi → 两 AgNi 都删、两 H65 保留）。而重复行的**唯一来源** = `element_bom_item` 销售号+生产号双重登记（见 [[BL-0035]]/partno 暂搁专项）。设计文档 `dev-docs/task-删除行删错架构重构/设计方案.md` 的 Phase 2（uniqFp 加 `#序号` + editRows/formulaResults/React-key/写回 统一身份 + 向后兼容读）可精确只删重复对里的一个。
- **范围**：**若 partno 数据修好（消除重复行）→ 大概率可不做**；否则按设计文档 T2.0~T2.5（spike→后端契约→前端契约→写回退耦→只读页→E2E）。删除侧已由 fp-match 覆盖，可缩到只做**编辑侧 + Excel 只读快照同源**。
- **依赖**：**partno 专项（消除重复行数据源）——建议先做，做完再评估本项是否还需要**。**预估规模**：L（全量）/ M（缩到编辑侧）。
- **验收要点**：重复行删一个只删一个；editRows/formulaResults/React-key/写回全走统一身份；存量墓碑向后兼容读。
- 🔄 **2026-07-26 更新（repair-0727）**：
  - **原判定的前提已被证伪**：本条原写「重复行的**唯一来源** = `element_bom_item` 销售号+生产号双重登记（脏数据），partno 数据修好→大概率可不做」。task-0721 报价侧接 BOM 树后该前提不再成立 —— **DAG 重复子件（同一料号挂多个父）是合法业务结构**，重复行是常态而非脏数据（实测 QT-20260726-0006：料号 992 同时挂 `S-3120014539` 与 `S-80011`）。
  - **树页签部分已由 repair-0727 解决**：墓碑加 `nodeId` 维度（结构身份），树行删一条只删一条已实机验证闭环。
  - **本条剩余范围收窄为「非树页签」**：非树侧仍按 `fp` 内容指纹单键匹配，字节级完全重复的两行仍会连删（`DeletedRowKeys.java` 注释记录的已知边界）。另**同一树节点下字节级完全相同的两行**同样仍不可区分（同 nodeId 同 fp），属本条覆盖范围。
  - 优先级维持 P2（触发需"两行内容逐字节相同"，实际发生率低），但**不再以"等 partno 修数据即可绕过"为由继续搁置**。

### [BL-0056] 报价单编辑页 driver INPUT 编辑落库存疑（需真人复现定性）
- **优先级**：P1（若属实 = 编辑数据丢失，需先确认真伪）
- **来源**：Phase 2 影响面实测（2026-07-15）顺带发现
- **状态**：TODO（需真人手动复现）
- **登记日期**：2026-07-15
- **背景**：合成 E2E（Playwright `fill`+`blur`）编辑 c4d9b1dc（QT-20260713-1963）来料 row1 的「加工费」→88.88：**卡片正确显示 + 公式（材料成本）更新**（本地算、落对行、无混行），但 **DB `row_data` 仍 0.04326、`quote_card_values.editRows` 空** → 编辑未落库（刷新会丢）。可能：①Playwright `fill/blur` 没完全驱动 `EditableCellInput` 的 onBlur→onCommitBlur→`handleSnapshotCellEdit`（QuotationStep2 L2675-2676，`useSnapEdit` 门控）提交流（**测试假象**）；②既有编辑持久化 gap（`editQuoteCardValue` 静默失败被 catch 吞 / autosave `skipRowsWithSnapshot` 跳过快照行）。**与本次删除修复（Phase 1）无关**（编辑路径 handleSnapshotCellEdit/editCardValue/autosave 一字未改）。
- **范围**：真人手动改一个 driver INPUT 值 → **刷新页面看是否还在**。若丢：查 `handleSnapshotCellEdit`(L1934) 是否被调、`editQuoteCardValue` 是否返 null、autosave 是否跳过快照行。
- **依赖**：无。**预估规模**：S（复现+定性）；修复规模视根因定。
- **验收要点**：编辑 driver INPUT 值刷新后仍在（editRows 或 row_data 落库）。

### [BL-0058]（技术债）`DataLoader.resultCache` 缺 versionFilter（mode/override）维度 → 同请求内同 `$view` 的 LIST/RENDER expand 串号
- **优先级**：P2（生产影响低；非阻断）
- **来源**：repair-071501 排查核价单版本切换 Bug2 时顺带发现（2026-07-15）
- **状态**：TODO
- **登记日期**：2026-07-15
- **背景**：`DataLoader` 为 `@RequestScoped`，`resultCache` 按 `$view` 归一化 path 为 key、**不含 versionFilter 的 mode/override 维度**（AP-37 / [[cpq-sqlview-cache-key-needs-component-dim]] 同族"缺维度缓存串号"）。同一请求内对同一 `$view` 先 LIST 模式 expand（`:versionFilter`→`TRUE` 返全版本）再 RENDER 模式 expand（按 override 渲染）时，第二次命中第一次缓存 → 返回全版本混版。
- **生产影响 = 低**：`switchVersion` 生产是独立 HTTP 请求（每请求新 `@RequestScoped DataLoader`、单请求内该 `$view` 只 expand 一次）→ 无串号、版本过滤正确（用户 live 数据实为干净单版本，已印证）。仅 `listVersionOptions` 端点自身一个请求内 LIST→RENDER 连查同 path，会让"当前版本高亮 `currentVersion`"在**无 override 兜底时**可能取错版本（纯高亮、不影响实际切换；有 override 时 currentVersion 读 override 表不受影响）。`@QuarkusTest` 因两次服务调用共享同一 request scope 会放大成"混版"（测试假象）。
- **范围**：`DataLoader.resultCache` key 增加 versionFilter 维度（override 指纹 + mode）；或 `listVersionOptions` 两次 expand 之间清 resultCache。触及核价渲染取数缓存核心（AP-37 高风险区 + `docs/三大核心模块基线.md`），需单独评审。
- **依赖**：无。**预估规模**：M（含 AP-37 回归验证）。
- **验收要点**：同请求内同 `$view` 先 LIST 后 RENDER，RENDER 结果按 override/is_current 正确过滤、不返全版本；`listVersionOptions` 无 override 时 currentVersion 取到真实 is_current 版本。

### [BL-0059] 按列折扣的后端提交重算：cross_tab 公式列折扣不生效（引擎行值缺真值）
- **优先级**：P1（按列折扣 + cross_tab 模板组合场景折扣静默失效；整体折扣 SUBTOTAL 源不受影响）
- **来源**：subtotal-fix-071701 全链路口径统一（2026-07-17，QT-20260716-2033 排查）已知限制
- **状态**：TODO
- **登记日期**：2026-07-17
- **背景**：`LineDiscountService.recompute` 的按列折扣 S1 依赖 `ComponentDataEffectiveRows.computeScaled` 的 Pass1 `columnSums(row_data)`——但 cross_tab_ref 公式列（如 来料.材料成本 = SUM(元素行 用量×单价)）的真值不落 `row_data`（实测恒 0）→ 缩放该列无效果（折扣比例=1）。本次修复已把 S0/行合计对齐前端完整口径（S0 采信 li.subtotal + 比例映射），按列折扣残留此限制（与修复前一致、不更糟）。
- **范围**：提交链路引擎行值改读卡片值快照（`quote_card_values.tabs[].resolvedRows` 列和，后端 CardSnapshotService 物化时已含 cross_tab 对称逻辑），与 `row_data`（手动行真相源）按列 merge（公式列取快照、输入列取 row_data）；submit 前需 `ensureCardValues` 保证快照非 NULL。注意 [[quote-card-values-excludes-manual-input-rows]]（qcv 不含手动行）的合并语义。
- **依赖**：无（quote_card_values 物化链路已就绪）。**预估规模**：M。
- **验收要点**：按列折扣（如 来料#材料成本 打 9 折）提交后 lineFinalPrice 与前端 Step3 显示一致；整体折扣与无折扣提交回归不变。

### [BL-0060] 报价单比对视图「导出」按新模型重做（task-0717 二期）
- **优先级**：P2
- **来源**：`dev-docs/task-0717-比对视图/需求说明.md §11.F`（本期明确不做导出）；技术总监澄清定稿 2026-07-18
- **状态**：TODO（未排期）
- **登记日期**：2026-07-18
- **背景**：task-0717 比对视图改造把列模型换成「用户配置的页签对比列 + 每料号 3 行块 + 阈值红/橙双色 + 差异行」。旧导出（`ComparisonExportService` / `POST /{id}/comparison/export`，2 行 tag 模型 + 单色高亮）与新模型对不上；本期为聚焦主功能，去掉比对视图上的导出按钮、旧端点保留不动、不再被调用。
- **范围**：按新比对视图模型重做导出——每料号 3 行块（报价/核价/差异）、用户配置列、差异格红/橙双色、单边料号变灰标注；沿用旧导出"前端传已算好的模型、后端 POI 只写值+填色、不重算"思路；前后端各动一处。
- **依赖**：task-0717 比对视图本期功能（前后端）落地。
- **预估规模**：M（3-5 天）
- **验收要点**：导出的 Excel 与页面比对视图逐值/着色一致；单边料号标注正确；不触碰旧 tag 导出回归（`CostingComparisonResourceTest` 仍绿）。
### [BL-0061] 核价（PRICING）侧 handler 料号语义对齐 RECIPE 模型
- **优先级**：P1
- **来源**：task-0717 投入料号扩围 RECIPE 收尾自查——本次只改了报价（QUOTE）侧 Q06-Q10/Q13 handler + 报价/核价视图品名兜底，**核价侧导入 handler 本身未动**
- **状态**：TODO（未排期）
- **登记日期**：2026-07-18
- **背景**：并发分支 `feat/pricing-sales-part-no` 仍在用旧口径写 `sales_part_no`，核价（PRICING）侧对应投入/材质料号的 handler 尚未按本次报价侧确立的"投入料号=材质料号→恒按材质、原始码、不进 material_customer_map/不登记 material_master"语义对齐，存在报价/核价两侧行为不一致的风险（核价侧材质料号仍可能被当真实组件 resolve+登记，重蹈报价侧修复前的"跨客户串号"覆辙）。
- **范围**：核对核价侧对应 Sheet 的 handler（核价 24 Sheet 体系中来料/自制加工费/组成件其他费用等同构 Sheet），按本次报价侧 Q06-Q10/Q13 的模式做等价改造；需先与 `feat/pricing-sales-part-no` 分支的口径冲突理清（[[cpq-shared-flyway-history-churn]] 类并发风险，见历史记忆 task0708-partno-semantics-delivered）。
- **依赖**：`feat/pricing-sales-part-no` 分支归属 / 合并状态需先明确（当前架构疑似冲突，用户暂搁）。
- **预估规模**：M（3-5 天，含核价侧对应 handler 数量核实 + 测试）。
- **验收要点**：核价侧材质料号导入不再触发跨客户串号类错误；核价侧 unit_price/material_bom_item 等落库 code 为原始材质料号；不进 material_customer_map(PRICING)/material_master。

### [BL-0062] material_customer_map 存量组件级脏占号行清理（RECIPE 模型下已无害）
- **优先级**：P2
- **来源**：task-0717 投入料号扩围 RECIPE 收尾自查（repair-2 已确认无需 DELETE 迁移）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-18
- **背景**：repair-2 + task-0717 落地后，投入/材质料号统一走原始码路径，不再新增 `material_customer_map` 占号行；但历史遗留约 31 行组件级脏占号行（材质料号被当年旧逻辑误登记为客户专属料号映射）仍残留在共享 DB。architect 已证这批行属"只写不读"（RECIPE 模型下渲染/校验均不再读它们），当前对功能无害，故本次不做 DELETE 迁移。
- **范围**：评估是否值得专门清理（存量数据整洁度 vs 改动风险），若清理需先 SELECT 精确圈定这 31 行（区别于合法的真实组件料号映射）再 DELETE，且需在报价/核价双视图跑一遍回归确认零影响。
- **依赖**：无。
- **预估规模**：S（1-2 天，主要成本在圈定+回归验证）。
- **验收要点**：清理后 `material_customer_map` 不再含材质料号误登记行；报价/核价渲染、导入回归无变化。

### [BL-0063] material_recipe 缺库告警：材质料号缺配方时料件名仍空
- **优先级**：P2
- **来源**：task-0717 投入料号扩围 RECIPE 收尾自查（14 视图品名兜底 V341/V342 的已知边界情况）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-18
- **背景**：本次给 10 个报价侧 + 4 个核价侧视图的 `_料件`/组件名列加了 `COALESCE(material_master.name, material_recipe.name)` 兜底，解决了"材质料号不在 material_master 时品名显示为空"的问题——但前提是该材质料号必须在 `material_recipe` 表里已建库。若某材质料号在 `material_recipe` 里也缺失（配方库未覆盖该牌号），兜底仍会落空、料件名列继续显示空白，且当前导入/渲染链路对此**不告警**，只能人工肉眼发现"这行料件名是空的"。
- **范围**：导入或渲染时对"材质料号在 material_bom_item/element_bom_item 出现，但 material_master 与 material_recipe 均无对应行"的情况做告警（导入侧 `recordError`/软告警，或渲染侧标记"缺配方"提示），避免静默空白误导业务判断为"数据正常但恰好没名字"。
- **依赖**：无。
- **预估规模**：S-M（视告警落地在导入侧还是渲染侧而定）。
- **验收要点**：材质料号缺配方时，导入结果或详情页有明确"缺配方/未知材质"提示，而非单纯空白料件名列。

---

### [BL-0065] 报价 Excel 视图 / 导出的树状渲染
- **优先级**：P2
- **来源**：task-0721 `需求说明.md` §2 不做什么 / spec §10
- **状态**：TODO（未排期）
- **登记日期**：2026-07-21
- **推迟原因**：本期已明确 Excel 侧只展示主料号数据、维持平铺（业务确认）。核价侧的 Excel 树是独立一期（2026-06-05 P2B），报价侧同理应单独立项。
- **背景**：本期报价卡片视图按 BOM spine 树渲染，但 Excel 视图与导出仍为平铺，两个渲染面形态不一致。
- **范围**：报价 Excel 逐 spine 节点出行 + lvl 缩进；可参考核价侧 `ExcelViewService.buildLineTreeRows` 的既有实现。
- **依赖**：task-0721 交付。
- **预估规模**：M
- **验收要点**：Excel 视图/导出与卡片视图树结构一致；核价 Excel 零回归。

### [BL-0066] 字段配置 `master_source` 动态候选下拉（通用能力）
- **优先级**：P2
- **来源**：task-0721 澄清过程（本期改为「从页签已有料号本地选择」后不再需要）
- **状态**：TODO（未排期）
- **推迟原因**：task-0721 确认树上新增料号必须已存在于某页签，候选集完全本地化，无需远程搜索。该能力遂与本期解耦。
- **登记日期**：2026-07-21
- **背景**：现有 `field_type` 八种类型均无枚举/下拉能力；`MasterSelectCell`（`EditableSheetTable.tsx:76`）+ `PricingBasicDataMaintenanceResource:81` 的 `lookup/{masterType}` 端点已具备远程搜索基础，但只服务核价维护页。
- **范围**：新增字段 config 键 `master_source`（支持 `by_field` 按同行字段动态切换候选源）；`MasterSelectCell` 提取到共享层供 `ComponentCell` 复用；`MASTER` map 增加 `recipe → material_recipe(code,name)`。
- **依赖**：无。**与 `BL-0046`（物料BOM 组成件按 calc_type 动态下拉）合流** —— 二者是同一 `by_field` 机制的两个应用点，应一并实现。
- **预估规模**：S
- **验收要点**：配了 `master_source` 的字段渲染为远程搜索下拉；`by_field` 能按同行字段值切换候选源；BL-0046 场景退化为一条配置。
- ⚠️ **注意**：新增 config 键触发 AP-44，须按矩阵核对协议传播点（约 4-5 处，远少于新增 field_type 的 17 处）。

### [BL-0067] 手工叶子自动带出来源页签数据
- **优先级**：P2
- **来源**：task-0721 待确认 B（已确认本期为「留空手填」）
- **状态**：TODO（未排期）
- **推迟原因**：业务确认待填字段不多，手填可接受；自动带出需要跨组件字段映射设计，非本期核心。
- **登记日期**：2026-07-21
- **背景**：树上新增叶子时业务列全部留空，但该料号在来源页签（材质元素/零件/外购件）中本已有数据，用户需重复录入。
- **范围**：定义树页签与来源页签的字段映射规则（按字段名？按语义？对不上如何处理），新增叶子时按映射回填。
- **依赖**：task-0721 交付。
- **预估规模**：M
- **验收要点**：新增叶子后可映射字段自动填充，不可映射字段留空且有明确标识。

### [BL-0068] 树上新增非叶子节点
- **优先级**：P2
- **来源**：task-0721 `需求说明.md` §2 不做什么
- **状态**：TODO（未排期）
- **推迟原因**：本期树骨架的人工扩展仅限叶子层。新增非叶子节点意味着人工构造子树，行键、刷新存活、级联删除规则均需重新设计。
- **登记日期**：2026-07-21
- **范围**：支持在节点下挂入带自身 BOM 子树的料号（组合产品场景可能需要）。
- **依赖**：task-0721 交付。
- **预估规模**：M
- **验收要点**：待细化。

---

## 已完成

### [DONE 2026-07-09] task_0708 导入报价单/核价单落库料号语义纠偏
- **交付**：master 提交 `4ce28a3`(feat) / `8d61cc0`(P24单重) / `257b8cd`(TC-B1) / `8767d87`(文档) / `1f47c9c`(record) + 迁移 `V315`。
- **验收**：测试报告全项 PASS（schema 终态 / 报价核价 material_no=销售料号 / element_bom 撞键一票否决 / is_current 唯一不累加 / 契约零变更 / 前端零改动）；R1/R4 转 [[BL-0038]]。
- **未并入本次（另立项）**：[[BL-0035]] 生产料号 BNF 重定向、[[BL-0036]] mat_part 退役、[[BL-0037]] V6 查询页标签校正。

### [DONE 2026-07-15] task-0712 选配模板 + 报价单选配功能
- **交付**：master `d02b7fe`（origin 已推）。后端6/6（B5 model_config 新表 V330 / B1 选配模板 / B2 选配落库改造六处齐全 / B6 组合工艺收敛 process_master ASSEMBLY / B3 已有产品端点 F005 过滤 / B4 加入链路复用）+ 前端 F1-F5（1:1 复刻原型：选配模板管理页 / 3D模型配置页 / 从已有产品添加 / 选配添加明细表）。
- **缺口补后端**：缺口1 工序 id 契约（加法式方案A，process_no 全链，V336）、缺口2 lookup-fingerprint 3a（确认前实时预览、与提交端同源零副作用）；F5 协同去兜底。
- **Critical 修复**：选配工序首存被 saveDraft 全删全建静默清空的 data-loss bug（gap1 漏跟 process_no，被 grep=ugrep 二进制坑掩盖，见 [[cpq-grep-ugrep-binary-pitfall]]）；V336 迁移改幂等（共享 DB churn 安全）。
- **验收**：后端服务测试全绿（六处齐全/幂等/N+1/指纹零副作用/孤儿 TP10 均独立复跑真绿）；F6 E2E 临时服务跑通——quotation-flow 回归 pass（渲染未破坏 '加载中'=0）+ 选配 SIMPLE/COMPOSITE 冒烟 pass；33 张截图为证。
- **关闭/关联**：[[BL-0031]] 由本次实质解决（工序落 unit_price/自制加工费 + v_composite_child_processes mirror）；收缩迁移转 [[BL-0057]]；F6 发现 2 个既有 bug（QuotationCreateForm stale closure / TC-F1F2 夹具漂移）另立项。

（暂无）

### [DONE 2026-07-26] repair-0726 BOM 中料件类投入料号没有落库（机制替换：暂存表 → 行级 pending 标记）
- **交付**：worktree 分支 `worktree-repair-0726-material-master-pending` 提交 `8c784cef`(V362) / `f615fd89`(V362 评审修订) / `12da6626`+`8fd259fd`(B2+B3+B6) / `81daaa9c`(B2/B3/B6 评审修订) / `aab46481`(B4+B5) / `98cb8aed`(B7 测试) + 迁移 `V362`。
- **验收**：AC-1~AC-8 全链路实测通过（导入即落正表 / 建单过户 / 核价通过转正 / 删单双向守卫 / 渲染可见 / 列表隔离 / 二次导入不重号 / 迁移 success=t 且暂存表已 DROP）；AC-9 回归 175 tests，12 项失败经逐条判定均为 pre-existing 环境缺口（本地库缺 991/992 材质、缺组件 zh_view、缺核价单 fixture、CostingComparisonResourceTest 既有 401 鉴权缺口）。
- **关闭**：[[BL-0072]]（暂存表退役 + clearPreviousPending 补带守卫回收）。
- **新登记技术债**：[[BL-0074]] 跨单复用 pending 料号无转正路径（P1）、[[BL-0075]] MaterialMasterRepository 位置性 NULL 填充与长参数列表（P2）。
- **方案纠偏**：原 backtask「删单必须先删 8 表后删料号，否则守卫顶住」经单测实证**不成立**（守卫 `<> :qid` 已排除本单自己的 pending 行）；真正的不变量是该子句本身，注释已改写并警示。

### [BL-0080] repair-0727 事故遗留：已被写坏的报价侧基础数据组待处置
- **优先级**：P1
- **来源**：repair-0727 核价通过回填事故（2026-07-27）
- **状态**：TODO（需求方 2026-07-27 明确"先不动，等修完再说"）
- **登记日期**：2026-07-27
- **背景**：HJ-20260726-0007 通过时，`material_bom_item` 组 (QUOTE, CUST-0001, S-3120014539) 被写成 v2010 单行空壳（`component_no`/`characteristic`/`seq_no` 全 NULL，只剩 `composition_qty=2`），原 4 行（00137/992/S-80011/W-1001）丢失；`element_bom_item` 同组 `base_qty` 0.624610→NULL。**代码缺陷已在 repair-0727 修复，但存量脏数据不会自愈**——新建报价单选该产品仍会看到空 BOM。
- **范围**：①决定重导还是脚本修数据；②若修数据，可从其他报价单的 pending 版本（v2000~v2008 仍在库，`pending_quotation_id` 非空）反推正确行集；③排查是否还有其他组在 07-27 之前的通过中被同样写坏（扫 `is_current=true` 且 `component_no IS NULL` 的行）
- **依赖**：repair-0727 合并
- **验收要点**：新建报价单选 S-3120014539，BOM 页签渲染出 4 行子件

### [BL-0081] 回填「清空不传导」限制
- **优先级**：P2
- **来源**：repair-0727 需求说明 §3.1 已知限制
- **状态**：TODO
- **登记日期**：2026-07-27
- **背景**：用户在页签里把某格清空为 NULL 时，`mapColumns` 的 `isNull → continue` 使其等同"未提供"，回填不会把基础数据对应列清空。区分"未提供"与"用户主动清空"需要额外信息（如前端显式脏标记）。
- **验收要点**：清空某格 → 核价通过后基础数据该列确实变 NULL；未编辑的格子不受影响

### [BL-0082] 首次创建即删除的行无审计痕迹
- **优先级**：P2
- **来源**：repair-0727 测试工程师评审提出
- **状态**：TODO（待 PM 确认是否可接受）
- **登记日期**：2026-07-27
- **背景**：「导入后当场删掉某行、从未核价通过过」这类纯 pending 行会被 `cleanupPending` 物理清除，事后完全查不到曾经存在过。这符合 §7 状态机既定语义（删单级联删 pending）、**不是 bug**，但若财务/审计有追溯诉求需另立方案（如 pending 操作流水表）。
- **验收要点**：由 PM 裁决是否需要；需要则设计 pending 生命周期审计

### [BL-0083] pending 架构下重复导入使版本号无界累积（`writeVersionedGroup` 短路失效）
- **优先级**：P1（数据膨胀 + 使一整类验收断言失去证伪能力）
- **来源**：repair-0727-process-no 验收阶段发现，技术总监用 `created_at` 时序做 A/B 归因确认
- **状态**：TODO（未排期）
- **登记日期**：2026-07-27
- **背景（实证）**：task-0721 B2 起 `QuoteImportService.java:97` 无条件 `ctx.pendingQuotationId = recordId`，故报价 V6 导入写 `capacity`/`unit_price` 等 7 张版本化表时**恒为 `is_current=false` + 带 pending**。而 `VersionedV6Writer.writeVersionedGroup` 的"触发列与内容都未变→复用旧版本号不写"短路判断依赖 `existing` 查询（`WHERE is_current=TRUE`）——**pending 行永远查不到 existing，短路永不生效**。后果：**重复导入同一份内容完全未变的 Excel，`calc_version` 每次都 +1**。
  实测：`capacity` 的 `S-3120014539 / QUOTE_ASSEMBLY` 在 2026-07-27~28 两天内从 20 行涨到 54 行、版本 2000→2026（27 个版本）。**关键佐证**：2000~2008 这 9 个版本产生于本次改动之前，其间 `process_no` 恒为「焊接」从未变化，仍然逐次升版 —— 证明与 `VERSION_TRIGGER` 无关，纯由 pending 机制导致。
- **衍生影响（测试方法论，已在 repair-0727 踩到）**：凡对报价 V6 导入结果写 `WHERE is_current` 的验收断言**都测不到新导入的数据**，会变成"真空通过"（无论代码对错都 PASS）。repair-0727 的 AC-6b（双 current）即因此失去证伪能力，AC-8 断言②（再导不升版）则直接不可达成、已废止。**后续任何报价导入相关的验收标准，断言必须走 `WHERE pending_quotation_id = <recordId>` 或模拟 `QuotePendingRewriter` 的 `:pq` 改写**。
- **范围**：①让 `existing` 查询在 pending 模式下按 `pending_quotation_id` 作用域查找，使"内容未变不升版"短路重新生效；②评估存量版本行清理策略；③把上述断言口径写进 `docs/E2E测试方法.md` 或 `docs/方案制定前必读.md`。
- **依赖**：需先与 task-0721 的 pending 生命周期设计对齐（`repointPendingOwnership` / `cleanupPending`），不宜单点改写入器。
- **预估规模**：M（含版本行为回归 + 存量评估）
- **验收要点**：①同一份未变 Excel 连导 3 次，`calc_version` 不再增长；②内容确有变化时仍正常升版；③官方态（`is_current=true`）行为逐字节不变。

### [BL-0084] 「组装加工费」组件（`$zz_view`）未被任何模板引用，功能备而不用
- **优先级**：P2（不是缺陷，是配置缺口；但会让该页签的改动无法被验证）
- **来源**：repair-0727-process-no 验收阶段发现（AC-7 无法做真实 UI 验证），技术总监查库确认
- **状态**：TODO（待 PM/业务确认是否需要配置）
- **登记日期**：2026-07-27
- **背景（实测）**：`component_sql_view.sql_view_name='zz_view'` 对应组件「组装加工费」（`f170b0a8`，状态 ACTIVE）在 `template_component` 中**绑定 0 个模板**；对照 `jg_view`（「加工费」组件）绑 2 个已发布模板。因此报价单 UI 上根本不存在「组装加工费」页签，`zz_view` 的取名口径改动（repair-0727 已把它对齐为 `pm.process_name → c.process_name → c.process_no`）当前不会被任何用户看到。
- **影响**：①该页签的任何渲染改动都只能靠 SQL 层验证，无法走真实 UI；②`capacity` 的 `QUOTE_ASSEMBLY` 数据已在正常落库，但业务侧看不到。
- **范围**：由 PM/业务确认「组装加工费」是否应作为页签出现在报价模板中；若是，走 rule-0724 配置流程绑定到相应客户模板。
- **依赖**：无。**预估规模**：S（纯配置）
- **验收要点**：绑定后打开报价单能看到组装加工费页签，「工序」列显示工序名称（非编号）。

### [BL-0085] 全项目 tsc 自检命令空转 —— `-p tsconfig.json` 检查 0 个文件
- **优先级**：P1（破坏的是质量门禁本身：历史所有「TS 0 错误 ✅」声明都没真正检查过代码）
- **来源**：task-0728 主数据维护版式优化（前端工程师发现，技术总监实测复核）
- **状态**：TODO（未排期；**改 CLAUDE.md 需用户拍板**）
- **登记日期**：2026-07-27
- **背景（实测）**：`CLAUDE.md`「修改后强制自检」第 1 条与各任务 fronttask 抄写的 `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` 是**空转命令**。`tsconfig.json` 是 `{"files": [], "references": [./tsconfig.app.json, ./tsconfig.node.json]}` 的 solution 文件，**不带 `-b` 时 tsc 不下钻子项目** —— 实测 `--listFiles | wc -l` = **0**，恒返回退出码 0。
- **正确命令**：`npx tsc --noEmit -p tsconfig.app.json`（实测 2444 个文件）；`tsconfig.node.json` 另跑（0 错误）。或用 `tsc -b`（`npm run build` 用的就是它，故 build 是真检查的，只有"自检"这一步是空的）。
- **暴露出的既有存量**：真实检查跑出 master 上 **2 个既有类型错误** —— ① `src/pages/config/ElementEditDrawer.tsx:148` `Divider orientation="left"` 不匹配 antd v6 收窄后的 `Orientation` 类型（antd 6 升级欠账）；② `src/pages/quotation/useDriverExpansions.ts:172` TS1016 必选参数 `usage` 跟在可选参数之后（**协议级文件**，改动触发 E2E 全套）。
- **范围**：① 改 `CLAUDE.md`「修改后强制自检」第 1 条为 `-p tsconfig.app.json`（一处改，全项目后续任务受益）；② 清掉上述 2 个存量错误（`useDriverExpansions.ts` 需按 AP-44/E2E 规范走）；③ 可选：加一条 CI/pre-commit 防回归。
- **依赖**：无。**预估规模**：S（①几分钟；②含 E2E 约 1 天）
- **验收要点**：新命令能真实报出注入的类型错误（可故意写一处验证）；2 个存量错误清零；后续任务的「已自检」声明具备实际效力。

### [BL-0086] 全站分页页大小显示英文 `20 / page`（antd v6 Pagination 未拿到 zhCN locale）
- **优先级**：P2（纯文案，不影响功能；但中文系统里显眼）
- **来源**：task-0728（F3/F4 实现方发现，技术总监 A/B 复核 + 根因排查）
- **状态**：TODO（未排期，**根因未定位**）
- **登记日期**：2026-07-27
- **背景（实测）**：凡开启 `showSizeChanger` 的列表，页大小下拉显示 `10 / page`、`20 / page` 而非 `10 条/页`。`rc-pagination` 的 label 模板是 `${value} ${locale.items_per_page}`，英文 locale 的 `items_per_page` 恰为 `/ page` ⇒ 说明 `antd/lib/pagination/Pagination.js` 里 `useLocale('Pagination', en_US)` **回退到了英文兜底**。
- **已排除的可能**：`App.tsx` 的 `ConfigProvider locale={zhCN}` 配置正确且包住 `RouterProvider`；`main.tsx` 无第二个 ConfigProvider；`node_modules` 无重复 antd；`antd/locale/zh_CN` 顶层确有 `Pagination.items_per_page = "条/页"`；Vite `optimizeDeps.include: ['antd','antd/locale/zh_CN']` 强制同构预构建后**问题依旧**（故非 ESM 双实例）。
- **A/B 证据**：共享 5174（master 代码）与 task-0728 分支（5250）在同一页面均为 `共 1 条 1 20 / page` —— **全站既有，非任何单个任务引入**。注意「共 N 条」是中文（那是各页自己传的 `showTotal` 函数），只有 antd 自带文案是英文。
- **范围**：定位 antd 6.3.5 下 Pagination locale 未生效的真因（疑与 v6 的 locale context 实现变化有关）；修法二选一 —— ① 全局根治（改 ConfigProvider 用法或升级 antd）；② 兜底：在 `listConventions.ts#commonPagination` 显式传 `locale: { items_per_page: '条/页' }`（**注意：局部修会造成"主数据维护中文、其它页英文"的新不一致，除非同步铺开全站**）。
- **依赖**：无。**预估规模**：S（若为已知 antd issue）/ M（需深挖）
- **验收要点**：全站开启 `showSizeChanger` 的列表统一显示「N 条/页」；不引入 ConfigProvider 层面的回归。

### [BL-0087] P16「来料其他费用（比例）」sheet 名与真实客户文件不符 → 该 sheet 数据从未被导入
- **优先级**：P1（静默数据缺失：导入看似成功，该 sheet 内容全丢）
- **来源**：task-0728 B4（核价 24 Sheet 模板生成时，实现方比对权威导入文件发现）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-27
- **背景**：`P16IncomingOtherRatioFeeHandler.sheetName()` 返回 **「来料其他费用（比例）」**，而权威导入文件（`docs/table/核价测试数据/核价系统功能基础数据功能结构所需字段（导入版本)-新版.xlsx` 及 `-自洽`/`-罗克韦尔` 各变体）里该 sheet 实际叫 **「来料其他费用」**。`PricingImportService` 用 `wb.getSheet(h.sheetName())` **精确匹配** ⇒ 取不到 sheet ⇒ 跳过 ⇒ **现网客户文件里这个 sheet 的数据从来没被导进去过**，且导入报告不报错（按"sheet 不存在"静默跳过）。
- **注**：task-0728 新增的模板下载端点用 handler 名生成，**自洽（下载的模板能导回去）**，但与客户手上那份文件对不上 —— 修 sheet 名时须同步确认模板与客户文件两侧。
- **顺带记录（同批发现，均既有）**：① `P16`/`P17` 的 `getInt("二级项次","项次")` 因 contains 语义 + 一级列排在前，会**先命中「一级项次」**；② `PricingImportService` 里 4 个 merge sheet 名仍是硬编码字面量，与 `P16/P17/P19/P20` 的 `sheetName()` 重复（task-0728 用 `PricingHandlerCatalog` 单点登记 + 双向断言锁住同源性，但未消除重复）。
- **范围**：核对全部 24 个 handler 的 `sheetName()` 与真实客户文件逐一对齐（不止 P16）；决定是改 handler 名还是加别名匹配；给"sheet 未命中"加显式告警（不再静默跳过）。
- **依赖**：无（但属导入链路，改动需回归 `PricingVersioningImportE2ETest` 等）。**预估规模**：M
- **验收要点**：用真实客户文件导入，24 个 sheet 全部被消费（报告里逐个可见）；任何未命中的 sheet 在报告中显式列出而非静默跳过。

### [BL-0088] 跨页签公式：匹配对从「字段名字符串」升级为「列血统」
- **优先级**：P1（现状有静默匹配错行的可能）
- **来源**：task-0728「组件SQL视图的用户便捷化配置功能」需求澄清第三轮（用户问「列名=字段名后，页签之间通过行键进行公式关联如何优化」）
- **状态**：TODO（未排期；用户明确本期只存血统、不改公式引擎）
- **登记日期**：2026-07-27
- **背景（已核实）**：跨页签引用当前 `cross_tab_ref.match = [{a:'字段名', b:'字段名'}]` 由用户在 `CrossTabRefDrawer` **手工一对对配**；可行性判定在 `cpq-frontend/src/pages/component/formulaSerialize.ts:700` 的 `comparable(rkf_a, rkf_b)`，比较的是**行键字段名的字符串集合**是否互为子集（后端 `com/cpq/component/formula/RowKeyCompare.java` 为镜像，主代码中暂未被调用）。两个失效模式：① 同一业务实体不同叫法（「材质」vs「材质名称」）→ 明明可对齐却判为不可比；② 不同业务实体同名（两个页签都有「名称」）→ 误判可对齐，**静默匹配错行**。
- **前置条件**：task-0728 取数配置器落地后，`builder_config.columns[].columnKey` 会忠实记录每列血统（来自哪个码列 / 哪个查名库 / 哪个附属源）——这是本条的数据基础。
- **范围**：① 打开跨页签抽屉时按血统**自动推荐匹配对**（用户只确认）；② `comparable()` 改为**血统集合**子集判定；③ 不可对齐时给业务化原因（「本页签行键=材质料号，目标页签行键=零件料号，不同源」）；④ **存量手写视图无血统 → 回落现有字符串匹配，行为逐字不变**。
- **风险**：`comparable()` 位于公式校验链路（报价渲染主链路），前后端双轨镜像，改动须配套跑 E2E（`quotation-flow.spec.ts` + `cross-tab-ref.spec.ts`）。
- **预估规模**：M
- **验收要点**：血统相同但名字不同的两列能自动配对；名字相同但血统不同的两列不再被判为可对齐；存量组件（无 builder_config）的跨页签公式求值结果逐字不变。

### [BL-0089] 取数配置器 Phase 2：核价侧配方库 + 铁律升级重编译（BOM 树已移入本期）
- **优先级**：P2
- **来源**：task-0728 需求说明 §2.3「不做什么」+ §10「后续」
- **状态**：TODO（未排期）
- **登记日期**：2026-07-27
- **范围**：~~① BOM 树页签配方~~（**2026-07-28 已改为 task-0728 本期范围**，用户要求纳入）；② **核价侧配方库**（`:versionFilter` 宏 / `_GLOBAL_` / 禁客户过滤 / spine 树 / `basic_data_path` 绑定，17 页签）；③ **一键重编译全部 builder 视图**（铁律升级时使用，`builder_version` 列已在 Phase 1 预留）；④ 存量手写视图的半自动迁移工具（人工确认，不做 SQL 反解）。
- **前置条件**：task-0728 Phase 1（报价侧 5 类平铺页签）交付并稳定运行。
- **预估规模**：L
- **验收要点**：核价侧能用配置器配出至少 1 个页签且与手写版渲染逐行等值；铁律升级后一键重编译不改变任何既有视图的语义。

### [BL-0090] 渲染层改用稳定 fieldKey 替代中文字段名做 key
- **优先级**：P2（架构债；当前靠"改名时同步 6 处 + 冻结单阻断"规避）
- **来源**：task-0728 需求澄清第七轮（用户问「已选输出列可以更换名称，会影响 SQL 吗」）
- **状态**：TODO（未排期）
- **登记日期**：2026-07-28
- **背景（实测 `cpq_db_0724`）**：全链路**没有一处用稳定 ID**，全部以中文字段名为 key —— `quotation_line_component_data.snapshot_rows.driverRow` = `{"_销售料号":...}`（视图别名）、`basicDataValues` = `{"{$cp_view._销售料号}":...}`（完整路径字符串）、公式 token `{"type":"field","value":"单价"}`、`row_key_fields` = `["料号","材质","元素"]`、`part_no_field`/`sort_field`、`cross_tab_ref.match` = `[{"a":"料件","b":"料件"}]`。后果：改一个字段名要在 6 处同步，且**已冻结报价单的快照无法迁移**（key 是旧名），只能靠阻断改名规避。
- **范围**：引入稳定 `fieldKey` 作为渲染层 key；迁移全部历史 `snapshot_rows` / `row_data` / 公式 token / 组件级属性；中文名降级为纯展示 label。
- **风险**：极高——触及报价渲染主链路 + AP-44 协议面 + 全量历史数据迁移。须独立立项，不可夹带。
- **预估规模**：L
- **验收要点**：改字段名不再影响任何已有报价单渲染；冻结单改名限制可解除。

### [BL-0093] 两个 dry-run 端点自 2026-08-01 起无前端调用方，待统一清理
- **优先级**：P2
- **来源**：task-0801 页签连表公式配置优化（澄清 C4）
- **状态**：TODO（未排期）
- **登记日期**：2026-08-01
- **背景**：task-0801 移除了公式抽屉的试算功能，`POST /components/{id}/dry-run`、
  `POST /components/{id}/dry-run-token`、`GET /components/{id}/sample-cards` 三个端点
  **前端已全部停调**，后端按裁决原样保留（不删、不标 @Deprecated）。
- **⚠️ 清理前必读**：`dry-run-token` 背后的 `CardSnapshotService.dryRunTokenRows` 挂着
  `CardSnapshotDryRunParityTest`（断言「试算逐行值 == 渲染逐行值」，实际保护**渲染路径**正确性），
  且被 `QuotePendingScopeOpenWhitelistTest` 列入 pending 域开放白名单。**删端点前必须先给渲染路径
  补等价的 parity 断言**，否则会静默削弱渲染侧保障。
- **范围**：确认无其他消费方后，删端点 + `ComponentSampleCardService` 对应方法，并保留/改写 parity 测试。
- **依赖**：无。**预估规模**：S
- **验收要点**：①端点删除后全工程零引用；②渲染路径的 parity 保障不弱于清理前。

### [BL-0094] `QuotePendingScopeOpenWhitelistTest` 恒红 —— 安全护栏的报警能力已失效
- **优先级**：P1（破坏的是安全属性的**信号能力**，非功能本身；且污染所有人的回归判断）
- **来源**：task-0801 后端守卫任务 B2 执行时暴露，技术总监做 A/B 归因后确认为 pre-existing
- **状态**：TODO（未排期）
- **登记日期**：2026-08-01
- **背景（已实证）**：`QuotePendingScopeOpenWhitelistTest.openCallSites_fileLevelWhitelist_exactMatch`
  用 `content.contains("QuotePendingScope.open(")` 做**纯文本**匹配，未排除注释与字符串字面量。
  `QuotationService.java:1586` 有一句中文注释含该字样（`repair-0729` commit `40badf08` 引入，2026-07-28），
  被误判为"未授权开 pending 可见域"，导致断言失败。
  **确认为纯假阳性**：该文件全文仅此 1 处命中，且**根本没有 import `QuotePendingScope`**，不可能有真实调用。
- **A/B 归因**：主仓 master（`3e25809c`，零 task-0801 改动）上同一断言、同一实际命中集合同样失败 → pre-existing，与 task-0801 无关。
- **⚠️ 真正的危害（比失败本身严重）**：
  1. 该测试是 pending 可见域的**安全护栏**（注释原文：「多出的文件 = 有人在未授权位置开了 pending 可见域（可能破坏 AC-17）」）。
     它现在**恒红**，此后若真出现未授权调用，表现仍是"红变红"，**没有人能从信号上区分** —— 护栏事实上已停止工作。
  2. 任何人跑全量 `mvnw test` 都会拿到 `BUILD FAILURE`，使"改动是否引入回归"的判断被迫依赖人工 A/B，成本高且易误判
     （与 [[BL-0078]] 的 E2E 夹具失效同型危害）。
- **范围**：把文本匹配改为**排除注释与字符串字面量**后再扫（或改用语法级扫描 / AST）；修好后确认白名单回到 3 个文件精确相等。
- **依赖**：无。**预估规模**：S
- **验收要点**：①当前 master 上该测试转绿；②人为在某个非白名单文件里加一处**真实** `QuotePendingScope.open(` 调用，测试必须失败（护栏有效性正向验证）；③人为加一句含该字样的注释，测试必须仍绿（假阳性已消除）。

### [BL-0095] 测试库 `cpq_db` 的 V366 撞号 + 脚本丢失，导致所有 `@QuarkusTest` 起不来
- **优先级**：P1（阻断全部后端集成测试，不阻断纯单测）
- **来源**：task-0801 后端守卫 B2 第二次执行时暴露，技术总监 A/B 归因确认 pre-existing
- **状态**：✅ **已解决（2026-08-01，由 task-0801「公式计算精度优化」合并时顺带修复）**
  - 处置：把该任务的 `V366__widen_amount_columns_to_scale6.sql` 改号为 **V367**（dev 库 366 槽位已被
    并发会话的 `V366__task0729_costing_element_price_field.sql` 占用且已 `success=t`，按
    「已应用到共享库的迁移禁止改号」原则改本任务这一支）；同步 `DELETE` 掉 test 库
    `flyway_schema_history` 里改号后成孤儿的 366 记录。
  - 过程中另发现同型坑：`target/classes/db/migration/` 残留改名前的 V366 编译产物
    （Maven 不清理 target 孤儿文件），Flyway 从 classpath 同时扫到新旧两份、连续应用 2 次，
    test 库一度出现 366/367 两条同名记录 —— 已删残留 + 清重复记录。
  - 验证：test 库 `WHERE version='366'` 返 **0 行**；Flyway `Schema "public" is up to date`；
    `FormulaCalculationTest`（`@QuarkusTest`）5/5 绿，证明 Quarkus 能正常启动。
  - 参见 commit `700531d5`。
- **登记日期**：2026-08-01
- **现象**：任何 `@QuarkusTest`（如 `CardSnapshotDryRunParityTest`）启动即抛
  `org.flywaydb.core.api.exception.FlywayValidateException: Validate failed: Migrations have failed validation`
  → `Failed to start quarkus`。纯单元测试（如 `TabJoinPlanEvaluator*Test`，不启 Quarkus）不受影响，仍全绿。
- **根因（实测）**：**两个会话都占用了 V366 版本号**，且测试库记录的那个脚本已不在任何工作区：

  | 位置 | V366 是什么 |
  |---|---|
  | 测试库 `cpq_db`.`flyway_schema_history` | `V366__widen_amount_columns_to_scale6.sql`（success=t，已应用） |
  | 主工作区 | `V366__task0729_costing_element_price_field.sql`（**git 未跟踪**，另一任务的文件） |
  | 各 worktree | 两个都没有（worktree 是干净 checkout，带不走未跟踪文件） |

  Flyway 在 classpath 找不到 history 里记录的 `V366__widen_amount_columns_to_scale6.sql` → validate 失败。
- **A/B 归因**：主仓 master（零 task-0801 改动）跑 `CardSnapshotDryRunParityTest` **同样失败、同样异常** → pre-existing。
- **⚠️ 处置纪律**：**不要**擅自改共享测试库已应用的迁移记录，也**不要**删除他人未跟踪的迁移文件
  （见历史教训：已应用到共享库的迁移禁改名改号；删 untracked 孤儿迁移会让 8081 重启 validate 挂）。
  正确修法二选一：①找回 `V366__widen_amount_columns_to_scale6.sql` 并提交进版本库；
  ②与占号的另一方协商重排版本号后，同步修正 `flyway_schema_history`。**须由知情人处理，不是顺手能做的。**
- **关联**：与 [[BL-0094]] 同属"回归验证能力被环境问题侵蚀"一类——一个让白名单测试恒红，一个让集成测试全起不来，
  合并效果是**后端回归网基本失效**，每次改动都要靠人工 A/B 归因，成本高且易误判。建议一并排期。
- **依赖**：无。**预估规模**：S（定位已完成，剩下是协调与执行）
- **验收要点**：①`@QuarkusTest` 能正常启动；②`flyway_schema_history` 与版本库中的迁移文件一一对应，无孤儿记录。

### [BL-0103] Excel 列模型支持 BOM 父子取值公式（`tree_ref` / `tree_attr`）
- **优先级**：P2
- **来源**：task-0803 BOM 页签父子取值公式 · 需求澄清（2026-08-03）· 用户确认推迟
- **状态**：TODO（未排期）
- **登记日期**：2026-08-03
- **背景**：task-0803 给 BOM 页签新增了父子取值公式（`PGET` / `CSUM` 族 / 树属性 chip），求值靠**单元格（行 × 列）级拓扑**在树上遍历。而 Excel 列模型（`TabJoinPlanEvaluator`）是**按列拉平**的第二套引擎，没有「行」和「树」的概念，天生算不了这类公式。
- **本期处置**：照 KSUM 的模型 B 降级先例——遇 `tree_ref` / `tree_attr` **显式抛错**，上层降级为该列空值 + warn 日志，**不静默少算、不 500**（见 task-0803 需求说明 §4.3.7）。
- **范围**：若业务确需在 Excel 大表里看到成本 rollup / 累计用量，需给 Excel 列模型引入行/树上下文，或改由「页签连表渲染（模型 A）」承接。
- **推迟原因**：改造量比 task-0803 主体还大（要给一套无行概念的引擎从零加行/树语义），风险高；且当前无实际业务诉求，「碰到就报错」已是安全行为。
- **依赖**：task-0803 落地。**预估规模**：M
- **验收要点**：Excel 列引用含父子公式的组件字段时能算出正确值，且与页签视图取值一致。

### [BL-0104] BOM 父子公式增加「整棵子树」聚合函数（后代族，如 `DSUM` / `DCOUNT`）
- **优先级**：P2
- **来源**：task-0803 BOM 页签父子取值公式 · 需求澄清（2026-08-03）· 用户确认推迟
- **状态**：TODO（未排期）
- **登记日期**：2026-08-03
- **背景**：task-0803 裁决 `CSUM/CAVG/CMAX/CMIN/CCOUNT` **仅聚合直接子行**——因为成本这类指标靠「自底向上逐层滚算」即可正确上卷（孙辈先滚进子辈，子辈再滚给父辈），且能避免「被聚合列本身也是聚合列」时的层层重复计数。
- **缺口**：**跨层一次性汇总**逐层滚不出来，典型如「这台整机一共用了多少个零件」——需要一口气穿透整棵子树数所有后代。
- **范围**：新增一组后代聚合函数（`DSUM` / `DAVG` / `DMAX` / `DMIN` / `DCOUNT`），语义 = 对 `r` 的**全部后代**（不含自身）求表达式后聚合；边界口径（空集返 0、「有值」判据、墓碑行排除）沿用 task-0803 §4.3.3 / §4.3.4，不另立规则。
- **推迟原因**：函数数量翻倍（5→10），配置界面 / 双端实现 / 测试用例同步翻倍；且用户容易分不清 `CSUM` 与 `DSUM`，配错会重复计数。当前无实际业务诉求。
- **实现提示**：与 task-0803 共用同一套单元格拓扑引擎，依赖边从「直接子」改成「全部后代」即可，不需要新引擎。
- **依赖**：task-0803 落地。**预估规模**：S
- **验收要点**：三层树上 `DSUM` = 子 + 孙全部之和（与逐层 `CSUM` 的结果**不同**且各自正确）；与 `CSUM` 在同一页签共存互不干扰。

### [BL-0105] task-0803 前端 `resolveRowForTree` 与 `computeAllFormulas` 的字段解析逻辑重复（约 120 行）
- **优先级**：P2
- **来源**：task-0803 Task 7 交付评审（2026-08-03），实现工程师主动标注
- **状态**：TODO（未排期）
- **登记日期**：2026-08-03
- **背景**：task-0803 给 BOM 树页签新增了页签级求值入口 `computeTabFormulasTree`（单元格拓扑）。为满足**技术总监定的「`computeAllFormulas` 一字不改」零回归门禁**，新入口里的 `resolveRowForTree` **重新实现了约 120 行字段值解析逻辑**（BASIC_DATA / DATA_SOURCE / INPUT 各类型取值、default_source 回填、单位换算时机等），与 `computeAllFormulas` 内部同款逻辑并存。
- **风险**：日后有人修改 `computeAllFormulas` 的字段解析而未同步 `resolveRowForTree`，两条路径会**对 BOM 页签静默漂移**——非 BOM 页签正常、BOM 页签算错，且不报错。代码里已留交叉引用注释，但**无自动化守卫**。
- **这是有意识的取舍，不是疏忽**：当时 `computeAllFormulas` 服务着全部非 BOM 页签与 20 张在跑的单据，动它的回归风险远大于重复的维护成本。零回归 > DRY 是当时的正确权衡。
- **范围**：把两处共用的字段解析抽成共享函数，两条路径都改调它；抽取后必须跑满前端全量测试 + 前后端共享夹具比对（`tree-formula-parity-cases.json`，16 条）确认零漂移。
- **依赖**：无。**预估规模**：M（抽取本身不难，难在证明抽取没改变任一条路径的行为）
- **验收要点**：①两条路径共用同一份字段解析；②前端全量测试与共享夹具 16 条全绿；③非 BOM 页签渲染值与抽取前逐位一致（背靠背对比）。

### [BL-0106] `FormulaBuilder.tsx` / `CrossTabRefDrawer.tsx` / `TreeRefDrawer.tsx` 是孤儿组件（无生产引用）
- **优先级**：P2
- **来源**：task-0803 Task 8 交付评审（2026-08-03）
- **状态**：TODO（未排期）
- **登记日期**：2026-08-03
- **背景**：实测这三个组件在生产代码里**零引用**。真实的组件公式编辑入口是 `src/pages/template/TabJoinFormulaDrawer.tsx`（由 `ComponentManagement.tsx` 调用），它是**文本表达式编辑器**（用户编辑字符串 → `formulaSerialize.ts` 解析成 `FormulaToken[]`）。
- **实际代价（已经发生过一次）**：task-0803 的需求文档与实现计划均由技术总监撰写，当时**靠文件名推断编辑入口**（"叫 FormulaBuilder 所以它是公式构建器"）而未验证是否被引用，导致 Task 8 一整轮开发做在了打不到的组件上，返工一轮（Task 8b）才接到真实入口。
- **现状**：Task 8 的产物（父子取值分区、`TreeRefDrawer`、chip 文案）保留未删——代码正确、测试全绿，若日后把公式编辑迁移到 chip 式构建器可直接复用。
- **范围**：二选一 ——（a）删除这批孤儿组件，杜绝后人再次误判；（b）保留但在文件头部加醒目标注「本组件当前无生产引用，真实公式编辑入口是 TabJoinFormulaDrawer」。**建议 (b)**，因为 task-0803 已在其上投入了可复用的实现。
- **依赖**：无。**预估规模**：S
- **验收要点**：任何人从文件名或目录结构出发，都不会再把这几个组件误认成活跃的编辑入口。

### [BL-0107] task-0803 增强项：EXCEL 列显式提示 + 父子取值语法高亮
- **优先级**：P2
- **来源**：task-0803 Task 8b 交付评审（2026-08-03），实现工程师标注
- **状态**：TODO（未排期）
- **登记日期**：2026-08-03
- **背景**：两项已知的体验缺口，均**不构成静默算错**，故未阻断交付：
  1. **EXCEL 列**：`buildColumn` 走纯字符串保存不解析，用户在 EXCEL 视图列里打 `PGET(...)` 会被原样存成字符串。渲染时 `LinkedExcelView.evaluateFormula` 的安全闸（只允许 `[\d+\-*/().,\s%<>=!&|?:]`）会让它返 `—`，即**用户看到可见空值而非错误数字**。已按 `componentType !== 'EXCEL'` 收敛了语法提示，但没有显式报错。
  2. **语法高亮**：`FormulaRichInput`/`parseFormulaSegments` 未给 `tree_ref`/`tree_attr` 做专属着色；`[层级]` 走既有"无点裸字段"分支（紫色，不报错但非专属高亮），`PGET(...)` 函数名不着色 —— 与既有 `SUM`/`KSUM` 同样不着色的行为一致，**非本次引入的新缺口**。
- **依赖**：无。**预估规模**：S

