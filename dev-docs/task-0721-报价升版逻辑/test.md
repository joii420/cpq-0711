# 测试用例文档 · task-0721 报价数据版本升级（报价升版逻辑）

> 测试负责人：cpq-tester｜优先级：**P0**｜编写日期：2026-07-21
> 依据：`需求说明.md`（AC-1~AC-18 + §4.3 规则一~七 + §11 与树任务接触面 + §12 澄清记录）＋ `backtask.md`（B0~B9 后端实现点）＋ `api.md`（接口契约 + 错误码）＋ `fronttask.md`（F1~F3 前端交付项）＋ `docs/E2E测试方法.md`（E2E SOP）＋ 项目根 `CLAUDE.md`「修改后强制自检」。
> **本文档只做用例设计，不执行**。开发完成后按本文档逐条执行，"实际结果"列现全部留空。
> **判定原则**：本需求最高风险区是 **B3 视图改写（延迟生效正确性）** 与 **B5 回填两条路径（尤其 DAG 去重 + 主子同步不撞键）**，用例数量与颗粒度向这两块倾斜；凡"隔离/回填/撞键"类用例，必须记录**操作前 → 操作后**两次实测行数/数据作为证据，不看单点断言。
> 🩸 **铁律 1（一票否决）**：核价侧零回归（AC-17）—— 判断任何 `SqlViewExecutor`/`VersionedV6Writer` 改动是否影响核价侧，必须用同一核价单据「改动前 / 改动后」两次渲染逐字段比对，不能凭经验目测。
> 🩸 **铁律 2**：`plating_scheme` 全局升版是**接受设计非缺陷**（AC-18），测试不得按"跨客户隔离"的直觉去判它 FAIL —— 但**延迟生效**（未审核前不影响现役方案）与**已建单冻结**仍是强制验收项，不能因为"全局"就放松这两点。

---

## 0. 一句话验收目标

报价侧（`system_type='QUOTE'`）Excel 导入产生的 7 张版本化 V6 表数据（`unit_price`/`material_bom(+item)`/`element_bom(+item)`/`capacity`/`plating_scheme`）与 2 张特殊表（占号表 `material_customer_map`/主档 `material_master`）在导入时**只落 `pending_quotation_id`，不翻全局 `is_current`**（延迟生效）；该报价单物化时能读到自己的 pending 数据（本单可见），其余报价单读不到、行数不翻倍（他单隔离）；财务在核价通过前先看**只读回填影响预览**（`previewToken` 防 TOCTOU），确认后同一事务内把报价单**此刻的有效行集**（改值 ⊕ 新增 ⊕ 墓碑剔除）回填进 7 张表触发升版（有历史）+ 主档覆盖 upsert（无历史）+ 占号表 pending→approved；此后「从已有产品添加」才能看到该料号，且带出数据与本报价单最终态逐字段一致（AC-8 核心）；`plating_scheme` 因全局共享，回填按 `scheme_no` 全局升版（接受设计，非跨客户隔离）；核价侧（`PRICING`）导入/渲染逐位不变；已提交报价单读 `snapshot_rows` 冻结不受回填影响。

---

## 1. 测试环境与前置

| 项 | 值 |
|----|----|
| 后端 | 共享 dev server `http://localhost:8081`（本任务含 `ALTER TABLE ADD COLUMN`，非破坏性 DDL，可复用共享 `cpq_db`；探活 `GET /api/cpq/components` 期望 **401**） |
| 前端 | 共享 dev server `http://localhost:5174` |
| 鉴权 | 沿用现状，**不新增鉴权代码**：核价通过（含回填/预览）= `isFinanceOrAdmin`；导入/编辑报价单 = 现有报价单端点类级鉴权；启动校验诊断端点 `GET /admin/quote-backfill/view-validation` = `SYSTEM_ADMIN` |
| E2E 标准夹具（SIMPLE） | 苏州西门子 + 报价模板0608 v1.9 + 产品 `10110002`（见记忆 `cpq-e2e-quotation-flow-test-data`） |
| E2E 标准夹具（COMPOSITE） | `composite-product-flow.spec.ts` 自带 |
| **⚠️ 已知环境缺口** | 干净 master 上 `quotation-flow.spec.ts` **恒有 3 个失败**（夹具单缺产品分类，见记忆 `task0712-update071501-category-axis`）。判断本次改动是否引入新回归，**必须做 A/B 同型对比**，只看失败数是否**新增** |
| **DAG / 主子同步 fixture** | 复用姊妹任务（`task-0721-报价侧树状结构与页签类型属性`）附录 A 的黄金 fixture：根料号 `3120018220` → 子 `2120011658`/`2120011659` → 两者均挂子 `3110520789`（DAG 重复子件，2 occurrence）→ 其子 `2101110225`。用于 UT-B5-DAG / UT-B5-MC 主子同步与去重验证 |
| **`plating_scheme` 现役方案编号样例** | 现网 Q16/P21 存在电镀方案编号（如 `A0001`，见 `docs/RECORD.md` 2026-06-17 条目），可直接引用作为 `scheme_no` 测试锚点 |
| **数据清理纪律** | 需求说明 §6：测试产生的报价侧快照/报价单/7 张表 QUOTE 侧数据须于测试后清除；核价侧（`PRICING`）任何数据/配置**不得**改动/清除 |
| **两任务并行依赖（见 §6 Q2）** | 本任务 B4/B5 依赖姊妹「树任务」定义的 `__v6_id` 注入协议、`deleted_row_keys`/`deleted_tree_nodes`、手工叶子标记；若树任务代码未合并，本任务多数用例需在**平铺（非树）页签**上验证，树页签相关用例（UT-B5-DAG 等）需与树任务联调后再执行，见 §6 |

---

## 2. 已识别测试风险

除需求说明 §9 R-1~R-6 外，本次评审新发现以下风险点（部分已升级为待澄清项，见 §6）：

| # | 风险 | 说明 | 应对 |
|---|---|---|---|
| R-1（需求自带） | 表 token 文本替换健壮性 | 避开字符串字面量/注释/同名 CTE | UT-B3-4/UT-B3-6 + 启动期全量校验 |
| R-2（需求自带） | 计算列/UNION 无法回写 | pgjdbc 元数据返回空 → 安全降级 | UT-B3-6 |
| R-3（需求自带） | `unit_price` 展开 ~49 列，expand 是首存热路径 | 性能风险 | PERF-1 A/B 计时 |
| R-4（需求自带） | `plating_scheme` 全局共享，通过后全局升版 | 接受设计，非缺陷 | AC-18 系列用例把关"延迟"与"冻结"两项强制点 |
| R-5（需求自带） | 财务通过即改写全局 + 丙的级联删除不可逆 | 只读预览 + previewToken + 老版本留存 | UT-B6-* / API-APPROVE-* |
| R-6（需求自带） | 「刷新基础数据」按钮吃到新数据 | 保留 + 二次确认 | UI-F2-* |
| **R-7（本次新发现，风险最高）** | **`__manual` 命名与既有平铺页签"手动新增行"标记 `_origin:'manual'` 不一致** —— 现网 `QuotationStep2.tsx:1737` `handleAddRow` 生成的新行标记是 `_origin: 'manual'`（单下划线，已存在字段），而 backtask B5.1 描述的新增行判据是 `__manual=true`（双下划线，姊妹树任务新定义的系统列）。若 B5 回填只识别 `__manual`，现有**非树（平铺）页签**里用户点"新增一行"手填的数据在核价通过时会**既不算改值（无 `__v6_id`）也不算新增（无 `__manual`）**，成为悬空数据，永远回填不进 V6 | 见 §6 Q1；UT-B5-ADD-2/API-APPROVE-6 专项覆盖 |
| **R-8（本次新发现）** | 两个并行任务（本任务 vs 树任务）的合并顺序/接口稳定性——B4/B5 明确"读树任务定义的系统列"，若树任务未先落地或字段命名调整，B5 回填在树页签场景下无法验证 | 见 §6 Q2；测试分两阶段执行（平铺页签独立可测 / 树页签场景需联调后再测） |
| **R-9（本次新发现）** | AC-2「本单可见」与 AC-5「回填-改值」的读取时点差异未被文档显式点出：B3 改写只是把 V6 **pending 原始行**（导入时刻的值）暴露给本单渲染，用户在报价单编辑期用页面改的值只落在 `snapshot_rows`/`row_data`，**不会**实时写回 V6 pending 行；真正把"用户编辑后的最终值"写回 V6 是 B5 在核价通过时才发生的一次性动作。若这个"两阶段"理解有误，AC-2 与 AC-5 的断言点会写错 | 见 §6 Q3；UT-B2-EDIT-1 专门验证"改值不经过 VersionedV6Writer 重写 V6 pending 行" |
| **R-10（本次新发现）** | `previewToken` 幂等性未定义：同一报价单状态下连续两次调用 preview，若因 Map 遍历顺序不稳定导致 hash 不同，会出现"什么都没变却报 409"的误报 | 见 §6 Q4；UT-B6-IDEMPOTENT 专项覆盖 |
| **R-11（本次新发现）** | B5.2「有 snapshot 表征」vs「无 snapshot 表征」的判定边界未定义：若某表所属组件在当前产品下**渲染出 0 行**（如 driver 条件不满足，但页签配置客观存在），算"有表征"（该组是空的，回填也应产生空）还是"无表征"（走 flip 原样转正）？两种解读会导致完全不同的回填结果 | 见 §6 Q5 |
| **R-12（本次新发现）** | 手工新增料号（此前 `material_master` 无记录）回填后在 `unit_price`/`material_bom_item` 等表产生了引用行，但 B9 只处理既有 Handler（Q18/Q02/Q04/Q13/MaterialBomMerge）对 `material_master` 的暂存变更，未提及"纯手工新增料号"是否需要同步建 `material_master` 档，可能导致下游 JOIN `material_master` 取名称的视图渲染出空品名 | 见 §6 Q6；UT-B5-ADD-3 覆盖该边界场景 |

---

## 3. AC 覆盖矩阵（18 条）

| AC | 验收项 | 覆盖用例 |
|---|---|---|
| AC-1 | 延迟生效 | UT-B2-1~6、UT-MIG-*、API-IMPORT-1 |
| AC-2 | 本单可见 | UT-B3-1、UT-B4-1、UT-B2-EDIT-1、E2E-BACKFILL-1 |
| AC-3 | 他单隔离 | UT-B3-2、UT-B3-3、UT-B3-9、E2E-BACKFILL-1 |
| AC-4 | 闸门 | UT-B7-1~5、API-EXIST-1、API-CAND-1、E2E-GATE-1 |
| AC-5 | 回填-改值 | UT-B5-CHG-1~2、UT-B2-EDIT-1、API-APPROVE-3 |
| AC-6 | 回填-新增 | UT-B5-ADD-1~3、API-APPROVE-6 |
| AC-7 | 回填-删除 | UT-B5-DEL-1~2、UT-B5-HIST-1 |
| AC-8 | 一致性总纲 | UT-B5-CONSIST-1、E2E-BACKFILL-1★核心 |
| AC-9 | 有历史 | UT-B5-HIST-1~2、UT-MIG-* |
| AC-10 | 已建单不受影响 | UT-FREEZE-1~2、E2E-BACKFILL-1 |
| AC-11 | 预览 | UT-B6-1~3、UT-B6-IDEMPOTENT、API-APPROVE-2、UI-F1-* |
| AC-12 | 预览不可挑选 | UT-B6-4、API-APPROVE-NOPART-1 |
| AC-13 | 状态机 | UT-B8-1~5、API-WITHDRAW-1 |
| AC-14 | 主子同步 | UT-B5-MC-1~3★核心 |
| AC-15 | 启动校验 | UT-B3-7~8、API-ADMIN-VIEW-1~2 |
| AC-16 | 无 N+1 | UT-B7-4、UT-B6-PERF-1、代码审查清单 §5 |
| AC-17 | 核价侧零回归 | REG-COST-1~4★门禁 |
| AC-18 | `plating_scheme` 全局升版 | UT-B5-FLIP-1~2、UT-SCHEME-DELAY-1、UT-SCHEME-FREEZE-1、UI-F1-3 |

---

## 4. 测试用例

### 4.1 后端单元测试（UT-*，按 B1~B9 分组）

#### 迁移（B1）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| UT-MIG-1 | 迁移已跑 | `SELECT version,success FROM flyway_schema_history WHERE version='<NN>'` | `success=t` | |
| UT-MIG-2 | 同上 | `\d unit_price` / `\d material_bom` / `\d material_bom_item` / `\d element_bom` / `\d element_bom_item` / `\d capacity` / `\d plating_scheme` / `\d material_customer_map` | 均新增 `pending_quotation_id uuid`（可空） | |
| UT-MIG-3 | 同上 | `\d unit_price` 等 7 表（不含 `material_customer_map`） | 均新增 `pending_supersedes uuid[]`（可空） | |
| UT-MIG-4 | 同上 | `\di ix_*_pending` | 8 个部分索引均存在（`WHERE pending_quotation_id IS NOT NULL`） | |
| UT-MIG-5 | 同上 | Entity 层：`UnitPrice`/`MaterialBom(Item)`/`ElementBom(Item)`/`Capacity`/`PlatingScheme`/`MaterialCustomerMap` 各持久化一条含 `pendingQuotationId` 的行再读回 | 字段读写一致，`pendingSupersedes` 数组类型正确映射（`uuid[]`） | |
| UT-MIG-6 | — | 存量数据（若上线时保留）迁移检查 | 存量行 `pending_quotation_id` 保持 NULL（视为已生效），不需要额外迁移脚本 | |

#### 导入写 pending（B2，对应 AC-1）

> 前置：一个已有正式（`is_current=true, pending_quotation_id IS NULL`）版本的料号组（如 `unit_price` 某 `code`），版本号记为 `v_old`（如 `2003`）。

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-B2-1**（AC-1 核心） | 上述料号组存在正式版本 `v_old` | 新建报价单 Q1，从基础数据导入含该料号新值的报价数据 | `SELECT is_current,count(*) FROM unit_price WHERE pending_quotation_id=:q1id GROUP BY 1` → 全 `false`；`SELECT count(*) FROM unit_price WHERE is_current AND pending_quotation_id IS NULL AND code=:code` → 计数与导入前**完全一致**（旧组 `is_current` 未被翻转） | |
| UT-B2-2（版本号） | 同上 | 检查新写入 pending 行的 `version_no` | `= v_old 对应数字 + 1`（含 pending 与正式行一起取 MAX，不会与后续再次导入撞号） | |
| UT-B2-3（主档暂存联动） | 导入的数据含 `material_master.unit_weight` 变更 | 导入后立即查 `material_master.unit_weight` | **未变化**（仍是导入前的值，变更只进暂存，见 UT-B9-1） | |
| UT-B2-4（占号表 pending） | 导入含新料号（`material_customer_map` 未曾存在的客户×料号组合） | 导入后查 `material_customer_map` | 新增行 `pending_quotation_id=:q1id`，非该报价单其它现存映射不受影响 | |
| UT-B2-5（重导覆盖） | Q1 已导入产生一批 pending | 同一 Q1 再次执行「重新导入」（如换一份 Excel 修正数据后重导） | 旧 pending 批次（`pending_quotation_id=:q1id`）先被清空，新批次写入；`SELECT count(*) FROM unit_price WHERE pending_quotation_id=:q1id` 不含旧批次残留行（无"两批 pending 并存"） | |
| UT-B2-6（`pending_supersedes` 正确性） | 同 UT-B2-1 | 检查新 pending 行的 `pending_supersedes` | 数组内容 = 被取代的正式 `current` 行 `id`（可用 `SELECT id FROM unit_price WHERE is_current AND code=:code AND pending_quotation_id IS NULL` 反查核对，二者一致） | |
| **UT-B2-EDIT-1**（★R-9 澄清验证，对应 AC-2/AC-5 边界） | Q1 导入产生 pending 后，销售在报价单编辑页把某行费用从 1.20 改成 1.35（仅前端/`snapshot_rows` 层面操作，未重新导入） | 改值后立即查 `unit_price WHERE pending_quotation_id=:q1id AND code=:code` | 该 pending 行的值**仍是 1.20**（导入原值，未被前端编辑实时同步）——验证"改值不经过 `VersionedV6Writer` 重写 V6 pending 行"这一架构假设成立；1.35 只存在于该 line item 的 `row_data`/`snapshot_rows`，直到核价通过（B5）才被读出写回 V6（见 UT-B5-CHG-1） | |

#### 视图 pending 感知改写（B3，技术核心，对应 AC-2/AC-3/AC-15）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-B3-1**（AC-2 本单可见核心） | Q1 有 pending 行（供应 3 行：1 条改值型 pending + 遮蔽 1 条旧 current + 2 条其余不相关 current） | 以 `SqlViewRuntimeContext{quotationId=Q1, status=DRAFT}` 执行该视图 | 返回行 = pending 行（本单私有） + 未被 supersede 的其余 current 行；**不含**被 supersede 的旧 current 行；行数按 POC 口径（3→5，视具体 fixture 而定），不是朴素 OR 的翻倍结果 | |
| **UT-B3-2**（AC-3 他单隔离核心） | 同上，另建报价单 Q2（无关） | 以 `SqlViewRuntimeContext{quotationId=Q2}` 执行同一视图 | 只见旧 `current` 数据（含 Q1 pending 取代前的旧值），**完全看不到** Q1 的 pending 行 | |
| **UT-B3-3**（AC-3 防翻倍） | 同 UT-B3-1 fixture | 分别用"朴素 `is_current OR pending_quotation_id=:pq`"与"遮蔽版"两种改写各跑一次并对比行数 | 朴素版行数 > 遮蔽版（复刻 POC 3→8 vs 3→5 case）；生产代码必须走遮蔽版 | |
| UT-B3-4（白名单准确性） | — | 检查改写只作用于 7 张白名单表 | `material_master`/`process_master`/`global_variable_value` 等维表 token **不被替换**，谓词与列不受影响 | |
| UT-B3-5（锚点） | — | 检查改写后 SQL 输出列 | 最外层含 `<主位别名>.id AS __v6_id`，值 = 该行 V6 主键；无别名/裸列名/多别名场景均正确（同 B0 POC 结论） | |
| UT-B3-6（安全降级） | 视图含 `COALESCE`/常量列/`UNION ALL` 场景 | 跑 `colToBase` 解析 | 该列返回空（无法回写），**不抛异常**，判为不可回写；对应快照该单元格 `__v6_id=null` | |
| **UT-B3-7**（AC-15 启动校验反例） | 故意构造/mock 一个报价侧视图，主位表非白名单（如直接 FROM `material_master`）或改写后追不到 `__v6_id` | 应用启动 | 启动**报错**（fail-fast），异常信息**指名**具体 `component`/`view` 与失败原因 | |
| UT-B3-8（AC-15 正例，全量扫描） | 现网全部报价侧 `component_sql_view` | 应用启动 | 全部通过校验（`ok=total, failed=0`）；结果快照可经 `GET /admin/quote-backfill/view-validation` 查得 | |
| UT-B3-9（frozen 不改写） | 报价单已 `SUBMITTED`/`APPROVED`（非可编辑态） | 以该报价单上下文执行视图 | **不触发** pending 改写，走现状原始视图（保证已提交单冻结 AC-10 + 不误改写） | |
| UT-B3-10（AC-17 核价侧不触发） | `SqlViewRuntimeContext` owner 为核价单（无 `quotationId` 或非报价侧） | 执行同一视图模板 | 不触发 pending 改写分支，SQL 与改动前逐字节一致 | |

#### 锚点写入快照（B4）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| UT-B4-1 | Q1 物化（建单/刷新） | 检查该组件 `snapshot_rows` | 每个可回写页签的每行含非空 `__v6_id`，未被 normalize/白名单过滤掉 | |
| UT-B4-2 | 同上，某页签主位为计算列 | 检查该页签 `snapshot_rows` | `__v6_id=null`，物化不因此报错/阻断 | |

#### 回填服务（B5，方案丙，核心）

**路径一：有 snapshot 表征**（`unit_price` 电镀费用，轴含 `customer_no + code`，按料号回填）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-B5-CHG-1**（AC-5 改值核心） | Q1 该电镀费用行 pending 值 1.20，用户已编辑为 1.35（承接 UT-B2-EDIT-1） | 核价通过（`costingApprove`） | 同事务内：`unit_price` 新版本该行值 = **1.35**（用户最终值，非导入原值 1.20）；旧版本该行仍 1.20，`is_current=false` 留存 | |
| UT-B5-CHG-2（多行改值） | 同一报价单 3 行改值 | 通过 | 3 行均正确进入新版本，互不影响；`changedRows=3` | |
| **UT-B5-ADD-1**（AC-6 新增核心） | 该产品新增一行手工电镀费用（无 `__v6_id`，标记为手工新增） | 通过 | `unit_price` 新版本出现该新行，轴列（`customer_no`+`code`）正确合成；`addedRows` 计数含此行 | |
| **UT-B5-ADD-2**（★R-7/Q1 澄清验证，平铺页签既有"新增一行"标记） | 用现有 `handleAddRow` 交互在**非树平铺页签**新增一行（标记为 `_origin:'manual'`，非 `__manual`），填好业务值 | 通过 | 该行**必须**被识别为新增行并正确回填（不因命名不一致而漏检测为悬空数据）——若实现只认 `__manual` 而漏了 `_origin:'manual'`，本用例应 FAIL 并要求裁决 §6 Q1 | |
| UT-B5-ADD-3（★R-12，新料号无主档） | 手工新增行的料号是**全新料号**（`material_master` 无任何记录） | 通过后查 `material_master` | 需与技术总监确认预期：若预期是"必须同步建档"，则该表应出现新行；若预期是"允许无主档，下游容忍空品名"，则测试记录该行为并标注为已知边界（见 §6 Q6），不作为 FAIL 依据，仅作为证据留存 | |
| **UT-B5-DEL-1**（AC-7 删除核心） | 某行被用户删除（命中 `deleted_row_keys`） | 通过 | 新版本**不含**该行；旧版本（含该行）`is_current=false` 留存，`SELECT * FROM unit_price WHERE id=:oldRowId` 仍可查到（物理未删） | |
| UT-B5-DEL-2（剪枝/墓碑排除） | 树墓碑命中（`deleted_tree_nodes`，需树任务联调后验证） | 通过 | 该节点对应料号从新版本消失，逻辑同 UT-B5-DEL-1 | |
| **UT-B5-DAG-1**（DAG 去重） | 复用附录 A fixture：料号 `3110520789` 在树上以 2 个 occurrence 存在（经 `2120011658`/`2120011659`） | 通过 | `material_bom_item` 回填按 **V6 身份**（父+子）去重：只产生该料号在**各自父节点下**各一条记录，不因树上 2 occurrence 而重复写出内容完全相同的行；两条父子关系（`2120011658→3110520789` 与 `2120011659→3110520789`）均正确存在 | |
| UT-B5-SPINE-1（spine 排除） | 某节点在该页签为骨架空行（无业务数据） | 通过 | 该空行**不**产生 V6 新行（排除出有效行集） | |

**路径二：无 snapshot 表征**（`plating_scheme`，轴 = `scheme_no`，全局升版·flip）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-B5-FLIP-1**（AC-18 核心） | Q1 导入产生 `plating_scheme` 的 pending 新方案（`scheme_no=S1`），当前报价单**任何模板均未渲染**该组件（无 snapshot 表征） | 核价通过 | B5 判定该组**无 snapshot 表征**，走 **flip**：`UPDATE plating_scheme SET is_current=true, pending_quotation_id=NULL WHERE pending_quotation_id=:q1id`；`pending_supersedes` 指向的旧 current 行同步降 `is_current=false`；**不**调用 `writeVersionedGroup`（若误调用，因 `newRows` 为空会被写入器 I1 校验 `IllegalArgumentException("newRows 为空...")` 拒绝，可作为反向验证） | |
| UT-B5-FLIP-2（flip 路径不重建） | 同上 | 检查回填逻辑调用链 | 该组未经过"重建有效行集"（无改值/新增/墓碑运算），直接原样 flip 导入时的 pending 内容 | |

**主子同步**（AC-14，核心）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-B5-MC-1**（`material_bom`/`material_bom_item`） | 附录 A fixture，涉及主表 `material_bom` + 子表 `material_bom_item` 的 pending 数据（含改值+新增+删除混合） | 核价通过（走 `writeVersionedMasterDetail`） | 新 `bom_version` 在主表与**全部**子表行**完全一致**（不出现主表已升版、子表仍旧版本的失步，复刻已知 V333/V339 教训） | |
| **UT-B5-MC-2**（撞键防护） | 构造多行子件在 `uq_material_bom_item` 唯一索引维度（`system_type, customer_no, material_no, characteristic, bom_version, seq_no, component_no, part_no`，NULL 用 `COALESCE` 归一）刻意接近碰撞（如两行仅 `component_no` 不同但其余全同） | 回填执行 | 无 `duplicate key value violates unique constraint "uq_material_bom_item"` 报错，两行正确区分落库 | |
| UT-B5-MC-3（`element_bom`/`element_bom_item`） | 同 UT-B5-MC-1，换成 `element_bom` 主子对 | 回填 | 主子表 `characteristic`（作为版本列）同步升版，无失步 | |

**一致性总纲**（AC-8，最高优先级综合用例）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-B5-CONSIST-1**（★AC-8 核心） | Q1 完成"导入 + 改值 + 新增 + 删除"混合操作后核价通过 | 新建报价单 Q2，从「已有产品添加」引用 Q1 的产品料号，物化渲染 | Q2 带出的**每张受管表**每个字段值 = Q1 提交时的最终态（逐字段对比：改值后的新值、新增行存在、删除行不存在），**不是**导入原始值，也不是"部分回填"的中间态 | |

**有历史**（AC-9）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| UT-B5-HIST-1 | 通过后 | `SELECT is_current,count(*) FROM unit_price WHERE code=:code GROUP BY 1` | 1 条 `true`（新）+ N 条 `false`（历史，含本次升版前的旧版） | |
| UT-B5-HIST-2 | 同上 | 查 `material_master` 该料号 | 仅 1 条当前记录（覆盖式，**无历史行**），符合方案甲预期 | |

**事务性**

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| UT-B5-TXN-1 | 模拟回填中途某张表写入失败（如人为构造唯一约束冲突） | 触发核价通过 | 整个事务**回滚**：报价单 `status` 保持 `SUBMITTED`；pending 数据**保留**（可重试）；不出现"部分表已升版、部分未升版"的中间态 | |

**清理**

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| UT-B5-CLEAN-1 | 核价通过成功后 | `SELECT count(*) FROM unit_price WHERE pending_quotation_id=:q1id`（7 表 + mcm 逐一查） | 全部 0（pending 草稿使命完成，已清理） | |

#### 预览 + previewToken（B6，对应 AC-11/AC-12）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| UT-B6-1（AC-11 结构） | Q1 有待回填影响 | `GET .../costing-approve/preview` | 返回 `summary`（versionedGroups/addedRows/deletedRows/changedRows）与 `groups[].rows[]` 逐行明细（`op` ∈ CHANGE/ADD/DELETE），与 UT-B5 系列断言的实际回填内容一致 | |
| **UT-B6-2**（★AC-11 TOCTOU 核心） | 已调用一次 preview 拿到 `previewToken` | 预览与提交之间，另一操作（如 autosave）改变了报价单数据 | 用旧 `previewToken` 提交 → **409**，`message`="报价数据在预览后发生变化，请重新预览" | |
| UT-B6-3（重新预览恢复） | 承接 UT-B6-2 | 重新调 preview 拿新 token 再提交 | 提交成功，`code=0` | |
| **UT-B6-4**（AC-12 不可挑选） | — | 检查提交接口 body schema | 仅 `comment`（可选）+ `previewToken`（必填），**不存在**"挑选具体行/组"的参数；即便构造这样的请求体，后端也应忽略/不识别该字段（全量对齐执行） | |
| UT-B6-5（空影响） | Q1 pending 数据与当前 snapshot 完全一致（无任何增删改） | preview | `summary` 全 0，`groups: []`；仍可正常提交（仅完成 flip/闸门/状态流转） | |
| UT-B6-6（AC-18 标注） | Q1 涉及 `plating_scheme` 组 | preview | 该组 `isGlobalShared=true`（信息性标注，不拦截提交） | |
| **UT-B6-IDEMPOTENT**（★R-10/Q4，previewToken 幂等性） | Q1 数据不变 | 连续调用 preview 两次 | 两次 `previewToken` **相同**（hash 计算对影响清单做了稳定排序，不受 Map/集合遍历顺序影响） | |

#### 闸门（B7，对应 AC-4，无 N+1）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-B7-1**（AC-4 正例） | Q1 未核价通过，料号 P1 的 `material_customer_map` 行 `pending_quotation_id=Q1的id` | 以另一报价单 Q2 上下文调用 `ExistingProductService.list` | 结果**不含** P1 | |
| UT-B7-2（通过后可见） | Q1 核价通过后 | 同上查询 | 结果**包含** P1，各页签数据 = Q1 最终态（承接 AC-8） | |
| UT-B7-3（选配发号同规则） | 选配发号产生的 `material_customer_map` 行 `pending_quotation_id` 非空 | 同上查询 | 同样不出现；通过（占号 flip）后出现 | |
| **UT-B7-4**（AC-16 无 N+1） | — | 用 SQL 执行计数（DataSource 拦截器/日志）跑一次 `list()` | 只产生**一条** SELECT（单表谓词内联，`AND mcm.pending_quotation_id IS NULL`），无逐行查询 | |
| UT-B7-5（`CustomerPartCandidateService` 同源） | 同 UT-B7-1 | 调用 `customer-part-candidates` 端点 | 同样过滤 pending 料号，行为与 `ExistingProductService.list` 一致 | |

#### 状态机（B8，对应 AC-13）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-B8-1**（驳回保留） | Q1 `SUBMITTED`，有 pending | `costingReject` | pending 行**保留**（`pending_quotation_id` 不变，`is_current` 不变），供销售改后重交 | |
| **UT-B8-2**（撤回不回滚） | Q1 已 `APPROVED`（已回填），此后新版本已被其它报价单引用 | `withdraw` | 报价单回到可编辑态；**已回填的 V6 数据不变**（不触发任何 UPDATE/回滚），下游已引用数据不受影响 | |
| UT-B8-3（重交覆盖） | Q1 被驳回后销售修改并重新提交 | 检查 pending | 旧 pending 先被 `DELETE`，新 pending 写入；不产生"两批 pending 并存"（承接 UT-B2-5） | |
| **UT-B8-4**（删单级联） | Q1 有 pending（未通过） | 删除 Q1 | 7 表 + `material_customer_map` 中 `pending_quotation_id=Q1id` 的行 = 0（全部级联删除） | |
| UT-B8-5（删单不影响已回填数据） | Q1 已 `APPROVED` 且已回填（pending 已在回填时清空转正） | 删除 Q1 | 已回填的正式版本（`is_current=true` 数据）**不受影响**（因回填时已清空 pending，删单无残留可删） | |

#### 主档暂存（B9，方案甲）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| UT-B9-1 | 导入改了 `material_master.unit_weight` | 通过前查询 | 值不变（暂存未 upsert，承接 UT-B2-3） | |
| UT-B9-2 | 核价通过（同事务） | 查询 | 值变为新值（覆盖式 upsert） | |
| UT-B9-3（清理） | 删单/重导 | 查询本单暂存记录 | 已清空 | |

#### 已建单冻结（AC-10）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-FREEZE-1**（回填前已提交的单不受影响） | Q0（与 Q1 使用相同料号，先于 Q1 提交且已 `APPROVED`/冻结） | 触发 Q1 的核价通过回填后，重新打开 Q0 | Q0 渲染数据与回填前**逐字节一致**（读 `snapshot_rows` 冻结，不因基础数据升版而漂移） | |
| UT-FREEZE-2（同料号跨单隔离叠加验证） | 同上 | 对比 Q0 打开前后的 `snapshot_rows` JSON hash | hash 相同 | |

#### `plating_scheme` 专项（AC-18，延迟 + 冻结两条强制点）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| **UT-SCHEME-DELAY-1**（①延迟） | Q1 导入产生新方案 `S1` 的 pending | 未审核前，另一报价单/核价查询 `WHERE scheme_no=:s1 AND is_current AND pending_quotation_id IS NULL` | 仍返回**旧版本**（现役方案不受影响） | |
| UT-SCHEME-GLOBAL-1（②生效，全局非按客户） | Q1（客户 A）核价通过 | 客户 B 的报价单查询该 `scheme_no` | 客户 B 同样看到新版本（全局共享生效，**非**跨客户隔离，接受设计） | |
| **UT-SCHEME-FREEZE-1**（③冻结） | Q0（早于 Q1 提交，引用同一 `scheme_no`） | Q1 通过后重新打开 Q0 | Q0 渲染数据不变（读 snapshot 冻结） | |
| UT-SCHEME-PREVIEW-1（④预览标注） | Q1 preview | 检查响应 | 该组 `isGlobalShared=true`（信息性提示） | |

#### 负向确认（V44 老表不纳入）

| 编号 | 前置 | 步骤 | 预期 | 实际 |
|---|---|---|---|---|
| UT-NEG-1 | 导入 + 回填全流程执行前后 | `SELECT count(*) FROM plating_plan` | 行数**不变**（未被本任务任何逻辑触碰） | |
| UT-NEG-2 | 同上 | `SELECT count(*) FROM plating_fee` | 行数不变 | |
| UT-NEG-3（代码审查） | — | grep 全工程 `plating_plan`/`plating_fee` 引用 | 本任务新增代码（`QuotePendingRewriter`/`QuoteBackfillService` 等）**零引用**这两张表 | |

---

### 4.2 接口测试（API-*，RestAssured/curl）

#### 核价通过两段式（api.md §1）

| 编号 | 请求 | 预期 | 实际 |
|---|---|---|---|
| API-PREVIEW-1 | `GET /quotations/{id}/costing-approve/preview` | 200，`data` 含 `quotationId`/`previewToken`/`summary`/`groups[]` | |
| API-APPROVE-1 | `POST .../costing-approve` body 不带 `previewToken` | **400**（老调用方强制走预览） | |
| API-APPROVE-2 | 带漂移的 `previewToken` | **409**，`message`="报价数据在预览后发生变化，请重新预览" | |
| API-APPROVE-3 | 正确 `previewToken` 提交 | 200，`data.backfill`（versionedGroups/addedRows/deletedRows/changedRows）与预览响应一致 | |
| API-APPROVE-4 | 报价单非 `SUBMITTED` 状态 | 400 | |
| API-APPROVE-5 | 非财务/管理员账号调用 | 403 | |
| API-APPROVE-6（★R-7/Q1） | 提交请求（含 `_origin:'manual'` 平铺新增行的报价单） | 200，`data.backfill.addedRows` 应计入该行；回填后该行进入 V6（与 UT-B5-ADD-2 呼应） | |
| API-APPROVE-NOPART-1（AC-12） | 构造带"挑选行"字段的非标准请求体 | 后端忽略该字段/或 400 参数不识别，**不支持部分回填** | |
| API-APPROVE-500-1 | 模拟回填失败（内部异常） | 500；报价单状态不变（`SUBMITTED`），pending 保留 | |

#### 从已有产品添加 —— 闸门（api.md §2）

| 编号 | 请求 | 预期 | 实际 |
|---|---|---|---|
| API-EXIST-1 | `GET /existing-products?quotationId=...`，某料号未核价通过 | 响应结构不变，该料号不出现 | |
| API-CAND-1 | `GET /quotations/customer-part-candidates?customerId=...` | 同上过滤生效 | |
| API-EXIST-2 | 核价通过后同一查询 | 料号出现，数据 = 最终态 | |

#### 撤回（api.md §3）

| 编号 | 请求 | 预期 | 实际 |
|---|---|---|---|
| API-WITHDRAW-1 | `POST /quotations/{id}/withdraw`，`APPROVED`→撤回 | 200，契约不变；已回填 V6 数据不受影响（对应 UT-B8-2） | |

#### 启动期视图校验（api.md §4）

| 编号 | 请求 | 预期 | 实际 |
|---|---|---|---|
| API-ADMIN-VIEW-1 | `GET /admin/quote-backfill/view-validation`（`SYSTEM_ADMIN`） | 200，`data` 含 `checkedAt`/`total`/`ok`/`failed`/`failures[]` | |
| API-ADMIN-VIEW-2 | 非 `SYSTEM_ADMIN` 调用 | 403 | |
| API-ADMIN-VIEW-3 | 存在校验失败的视图（承接 UT-B3-7） | `failed>0` 且 `failures[]` 含具体 `component`/`view`/`reason` | |

---

### 4.3 前端 UI 验收（UI-*，手测）

#### 回填影响预览抽屉（F1，对应 AC-11/AC-12/AC-18④）

| 编号 | 步骤 | 预期 | 实际 |
|---|---|---|---|
| UI-F1-1 | 财务点「核价通过」 | 不直接提交，先弹 `Drawer`（右侧滑出）展示预览 | |
| UI-F1-2 | 检查顶部汇总 | 「将升版 N 组 / 新增 X 行 / 删除 Y 行 / 改值 Z 行」数字正确 | |
| UI-F1-3 | 展开逐行明细 | `CHANGE` 显示"列名：旧值→新值"；`ADD` 绿色标"新增"；`DELETE` 红色删除线标"删除" | |
| **UI-F1-4**（AC-18④） | 检查含 `plating_scheme` 的组 | `isGlobalShared=true` 该组有**醒目**红/橙 Tag「全局共享，影响所有客户」 | |
| UI-F1-5 | 点「确认通过」 | 请求携带 `previewToken`；成功后抽屉关闭 + 状态刷新 | |
| UI-F1-6 | 点「取消」 | 抽屉关闭，不提交，报价单状态不变 | |
| UI-F1-7 | 空影响场景 | 提示"本次通过无基础数据变更，仅完成审核状态流转"，仍可点确认 | |
| **UI-F1-8**（409 自动重预览） | 提交后端返回 409 | `message.error`「报价数据在预览后发生变化，请重新预览」，自动重新拉取 preview 刷新抽屉内容 | |

#### 「刷新基础数据」二次确认（F2）

| 编号 | 步骤 | 预期 | 实际 |
|---|---|---|---|
| UI-F2-1 | Step2 点击「刷新基础数据」 | 弹二次确认，文案"刷新后本单基础数据将更新为最新已审核版本，未提交的本单编辑保留" | |
| UI-F2-2 | 确认/取消 | 确认→原刷新逻辑；取消→无操作 | |
| UI-F2-3 | 非 DRAFT 态 | 按钮 no-op（沿用现状） | |

#### 撤回文案（F3）

| 编号 | 步骤 | 预期 | 实际 |
|---|---|---|---|
| UI-F3-1 | `APPROVED` 态点撤回 | 确认弹窗补一句"已回填生效的基础数据不会回退" | |
| UI-F3-2 | 非 `APPROVED` 态撤回 | 文案不变（现状） | |

---

### 4.4 E2E 用例（Playwright，强制不可跳过，本任务改动 `SqlViewExecutor`/`CardSnapshotService` 属协议级）

| 编号 | Spec | 内容 | 断言 | 实际 |
|---|---|---|---|---|
| E2E-QF-1 | `quotation-flow.spec.ts`（原样重跑） | 苏州西门子 + 报价模板0608 + 10110002 全流程 | **A/B 对比**：改动前基线失败数 vs 改动后失败数，只看是否新增失败点；全 Tab `'加载中'=0` | |
| E2E-CPF-1 | `composite-product-flow.spec.ts`（原样重跑） | 组合产品全流程 | 全部 passed；全 Tab `'加载中'=0` | |
| **E2E-BACKFILL-1**（新增建议 spec，★AC-8 综合） | `quote-backfill-flow.spec.ts` | 导入 → 物化（pending 可见）→ 建 Q2 验证他单隔离 → 核价通过（预览→确认提交）→ 建 Q3 从已有产品添加引用同料号 | Q3 带出数据与 Q1 提交时最终态一致；全程无「加载中…」残留 | |
| **E2E-BACKFILL-2**（TOCTOU 真机） | 同上 spec | 预览抽屉打开后，另开一个操作改变报价单数据，再点确认 | 提交返回 409，前端提示并自动重新预览（UI-F1-8） | |
| **E2E-GATE-1** | 同上 spec | 打开「从已有产品添加」，核价通过前后各查一次 | 通过前不出现候选料号，通过后出现 | |

---

### 4.5 回归测试用例（REG-*）

#### 核价侧零回归（★AC-17 一票否决门禁）

| 编号 | 步骤 | 预期 | 实际 |
|---|---|---|---|
| **REG-COST-1** | 改动前后各跑一次同一核价单据渲染（含 `unit_price`/`material_bom`/`element_bom`/`capacity`/`plating_scheme` 涉及的页签） | 逐字段值**不变** | |
| REG-COST-2 | 检查 `SqlViewRuntimeContext` owner 判断逻辑 | `PRICING` 侧（无 `quotationId` 或非报价上下文）**不触发** pending 改写分支 | |
| REG-COST-3 | 核价侧导入（`Q*`→`P*` 系列 Handler 对应的核价侧 Handler）跑一遍 | 导入行为与改动前一致（`system_type='PRICING'` 不受本任务任何 pending 逻辑影响） | |
| REG-COST-4 | 现役 `component_sql_view`（`zpj_view`/`pj_view`/`ys_view` 等核价核心视图） | 改写前后各跑一遍渲染，逐字段 hash 对比一致（注意记忆 `cpq-golden-cardvalues-preexisting-drift` 提示的金标准漂移已知问题，需先排除该干扰再下结论） | |

#### 视图/缓存重启纪律

| 编号 | 步骤 | 预期 | 实际 |
|---|---|---|---|
| REG-CACHE-1 | 改动 `SqlViewExecutor` 后 | 必须重启 Quarkus 清 `CachedSqlCompiler`/`ImplicitJoinRewriter.tableColumnsCache` 等进程级缓存 | |

#### 权限不收紧回归

| 编号 | 步骤 | 预期 | 实际 |
|---|---|---|---|
| REG-PERM-1 | 现有报价单编辑/导入/提交操作 | 改动后不受影响，未意外新增权限检查点 | |

#### 性能（R-3）

| 编号 | 步骤 | 预期 | 实际 |
|---|---|---|---|
| PERF-1 | 改动前后各建一个含 `unit_price`（~49 列展开）密集的报价单，记录首存耗时 | 记录 A/B 对比数值，不设硬性阈值（需求本身未定阈值），供后续评估 | |

---

## 5. 达标判定标准（DoD）—— 18 条 AC 通过/不通过判据

| AC | PASS 判据 | FAIL 判据 |
|---|---|---|
| AC-1 延迟生效 | UT-B2-1/2/4 全过：新行 `is_current=false`+`pending_quotation_id` 正确、旧组不变 | 任一新行翻了旧组 `is_current`，或 pending 行 `is_current=true` |
| AC-2 本单可见 | UT-B3-1/UT-B4-1 过，本单渲染无「加载中」残留 | 本单看不到自己的 pending 数据，或渲染报错 |
| AC-3 他单隔离 | UT-B3-2/3 过，行数不翻倍 | 他单能看到 Q1 pending 数据，或行数异常翻倍 |
| AC-4 闸门 | UT-B7-1~3 过：未审核/选配发号料号不出现，通过后出现 | 任一未审核料号出现在候选列表 |
| AC-5 回填-改值 | UT-B5-CHG-1 过：新值进新版本 | 新版本值仍是导入原值（未反映用户编辑） |
| AC-6 回填-新增 | UT-B5-ADD-1/2 过（含 R-7 澄清后的统一判定） | 手工新增行未进入新版本 / 平铺页签新增行被漏检测 |
| AC-7 回填-删除 | UT-B5-DEL-1 过：新版本不含、旧版本留存可查 | 墓碑行仍在新版本，或旧版本被物理删除 |
| AC-8 一致性总纲★核心 | UT-B5-CONSIST-1 逐字段全部一致 | 任一字段值不一致，或出现"部分回填"中间态 |
| AC-9 有历史 | UT-B5-HIST-1/2 过 | 7 表无历史行，或 `material_master` 出现历史行 |
| AC-10 已建单不受影响 | UT-FREEZE-1/2 逐字节一致 | 已建单渲染数据因回填而变化 |
| AC-11 预览 | UT-B6-1/2 过，`previewToken` 漂移正确返回 409 | 预览数据与实际回填不符，或漂移未触发 409 |
| AC-12 预览不可挑选 | UT-B6-4 过，无部分回填能力 | 存在可挑选提交部分行的路径 |
| AC-13 状态机 | UT-B8-1~5 全过 | 驳回清了 pending / 撤回回滚了数据 / 重交产生两批 pending / 删单未清干净 |
| AC-14 主子同步★核心 | UT-B5-MC-1~3 全过，无撞键报错 | 主子版本失步，或触发 `uq_material_bom_item` 冲突 |
| AC-15 启动校验 | UT-B3-7/8 过：反例报错指名视图，正例全通过 | 带病视图未被拦截启动成功，或指名信息缺失 |
| AC-16 无 N+1 | UT-B7-4 确认闸门单条 SQL；回填/预览走批量聚合 | 任一环节出现逐行 SQL 查询 |
| AC-17 核价侧零回归★一票否决 | REG-COST-1~4 逐位不变 | 任一核价侧字段值因本任务改动而变化 |
| AC-18 全局升版接受 | UT-SCHEME-DELAY-1/FREEZE-1 过（延迟+冻结成立），全局生效本身不判 FAIL | 延迟生效被破坏（未审核前现役方案就变了）或已建单被吃到新方案 |

**总体 PASS** 需同时满足：AC-8/AC-14/AC-17 三项核心用例**全部**通过（一票否决级）+ 其余 15 条 AC 用例主要路径通过（允许个别边界用例待 §6 裁决后回填）。

**BLOCKED** 场景：
- §6 Q1（`__manual` 命名）未裁决，UT-B5-ADD-2/API-APPROVE-6 无法判定 PASS/FAIL；
- §6 Q2（两任务合并顺序）未协调，涉及树页签的 UT-B5-DAG-1/UT-B5-MC-1 需要等姊妹任务落地后联调；
- §6 Q5（有/无 snapshot 表征判定边界）未裁决，`plating_scheme` 以外是否还有其它表会走 flip 路径不确定。

---

## 6. 待技术总监 / 架构师澄清项

> 以下问题按不同答案会写出两套互斥的确定性用例，需先裁决才能执行判定。已按风险从高到低排列。

### Q1（★最高优先级）`__manual` 与既有 `_origin:'manual'` 命名不一致

- backtask B5.1："`__manual=true` 无 `__v6_id` = 新增行"——这是姊妹树任务新定义的系统列。
- 现网既有代码 `QuotationStep2.tsx:1737`（`handleAddRow`）：`const emptyRow = { _origin: 'manual', row_index: ... }`——**单下划线**，早于两个任务已存在，用于**平铺（非树）页签**"新增一行"交互。
- **需要裁决**：B5 回填在识别"新增行"时，是否要**同时**兼容 `_origin==='manual'`（平铺页签既有机制）与 `__manual===true`（树任务新机制）？还是要求姊妹树任务统一改名/桥接，让平铺页签的新增行也标记 `__manual`？
- 影响：若不兼容，现有（早于本任务就存在的）"手填新增一行"功能在核价通过后会产生**永久悬空数据**（无 `__v6_id` 判不了改值，无 `__manual` 判不了新增，静默丢失），且此 bug 只在有该交互历史的存量报价单上才会暴露，不易在纯本任务的新建测试数据里发现。

### Q2 两个并行任务（本任务 vs 树任务）的合并顺序与联调时点

- backtask B4/B5 明确"读取树任务定义的系统列（`__v6_id` 注入点复用树任务的物化管线 / `deleted_tree_nodes` / 手工叶子标记）"，本任务与「task-0721-报价侧树状结构与页签类型属性」是两个并行开发的姊妹任务（各自独立 worktree/分支）。
- **需要裁决**：两者谁先合并 master？若本任务先合并，树页签（`tab_type=BOM`）相关的回填场景（DAG 去重 UT-B5-DAG-1、主子同步 UT-B5-MC-1 若数据来自树页签）暂时**无法**在树任务代码落地前完整验证，只能先在**平铺页签**场景验证 B5 核心逻辑（改值/新增/删除/flip），树页签场景需要等两任务都合并后**联调**补测。
- 建议：本文档 §4 的 UT-B5-DAG-1/UT-B5-MC-1 若涉及树页签结构，标注为"待联调"，不阻塞本任务平铺页签场景的独立验收。

### Q3 AC-2「本单可见」与 AC-5「回填-改值」的读取时点假设

- 需求描述隐含一个"两阶段读取"架构：**阶段一**（B3 物化期）只是把 V6 pending **原始行**（导入时刻的值）通过表替换暴露给本单渲染；**阶段二**（B5 核价通过）才把用户在报价单编辑期实际改动的值（存在 `snapshot_rows`/`row_data`）读出来写回 V6，触发正式升版。
- 这个假设意味着：如果用户导入后又手改了值，在核价通过**之前**，无论是本单还是其它任何查询，V6 的 pending 行本身**仍是导入原值**，用户改的值只活在报价单快照层，不会实时同步。
- **需要裁决**：这个两阶段理解是否与实现意图一致？若技术总监认为"用户编辑应该实时同步回 V6 pending 行"（即 B3/中间层还需要一次双向绑定），则 UT-B2-EDIT-1 的断言方向需要反转，且实现复杂度会显著上升（每次前端 autosave 都要触发 V6 pending 行的 UPDATE）。

### Q4 `previewToken` 幂等性未明确定义

- api.md/backtask 只说"对规范化影响清单（组+行+op+值，稳定排序后 JSON）算 SHA-256"，但未点明"规范化/稳定排序"的具体排序键（按 `table+groupKey+op+column`？还是别的）。
- **需要裁决**：实现须保证"同一报价单状态下连续两次 preview 得到相同 token"（UT-B6-IDEMPOTENT），否则会出现"数据完全没变却报 409"的误报，前端 F1 的 409 处理逻辑会被这种噪音污染，用户体验差。建议实现层显式记录排序键并补充该幂等性单测。

### Q5 B5.2「有/无 snapshot 表征」判定边界

- 需求原文："有 snapshot 表征（该组在某页签渲染/可编辑）→ 走 B5.3 重建"；"无 snapshot 表征（导入了但当前无任何报价模板渲染）→ flip"。
- **边界情形未定义**：若某表所属组件在当前产品下配置存在、但因 driver 条件不满足**渲染出 0 行**（客观上"有配置、无数据"），算哪一类？
  - 解读 A：判定依据是"页签配置是否存在"（哪怕渲染 0 行也算"有表征"），此时 B5 应按"空的有效行集"处理该组（即该组所有旧数据被清空，因为报价单里就是删光了）；
  - 解读 B：判定依据是"snapshot_rows 里是否存在含 `__v6_id` 的实际行"，0 行等价于"无表征"，走 flip 原样转正（不理会用户在该产品下可能做过的"全删"操作）。
- 两种解读在"用户在某产品下把某个电镀费用页签的所有行都删光后核价通过"这个场景下会产生**完全相反**的回填结果（清空 vs 转正）。需要裁决后才能确定 UT-B5-DEL 系列涉及"删到 0 行"的边界用例断言方向。

### Q6 手工新增料号是否需要同步建 `material_master` 主档

- B9 只处理既有 Handler（Q18/Q02/Q04/Q13/MaterialBomMerge）对 `material_master` 的暂存变更；未提及"纯手工新增料号"（此前 `material_master` 完全无记录）在回填后是否需要同步补建主档记录。
- **需要裁决**：若不补建，下游大量 JOIN `material_master` 取品名/规格的视图会对这个新料号渲染出空值/NULL（可能触发 AP-22 类"多行数据显示"隐患，虽然是空值而非错值，但用户体验上是"看不到名字"）；若需要补建，则 B5.2 的"新增行轴列合成"逻辑需要扩展一步：detect 该料号是否已在 `material_master`，不存在则一并 upsert 一条最小主档记录（哪些字段必填需要另行定义）。

---

## 7. 局限性说明（如实标注，不夸大覆盖度）

- **两任务并行的组合场景覆盖有限**：本文档的树页签相关用例（DAG 去重、树墓碑回填）依赖姊妹「树任务」的字段协议，在两者尚未联调前只能设计断言方向，无法给出确定性的执行结果，属已知局限（见 §6 Q2）。
- **`plating_scheme` 全局升版的"意外波及面"难以穷举**：一个 `scheme_no` 可能被多少客户/产品引用，测试只能在已知 fixture 范围内验证"延迟+冻结"两条强制线，无法在测试阶段模拟"生产环境该方案被 200 个客户引用"的真实爆炸半径，上线后需要做一次现网引用面审计（类比姊妹任务的 `REG-TEMPLATE-AUDIT`）。
- **性能（PERF-1）无硬阈值**：需求本身未设定具体的首存耗时上限，测试只能提供 A/B 对比数据，不能单独凭此判 PASS/FAIL。
- **previewToken 幂等性问题（Q4）在实现细节明确前**：只能先验证"完全无变化时 token 相同"和"人为制造明显冲突时触发 409"两种极端情况，中间地带（如仅改了不影响回填的无关字段是否也会导致 token 变化）留待实现后补测。
- **DAG 3-occurrence 及以上组合场景**：本文档主要基于 2-occurrence fixture（`3110520789`）设计断言，若需要验证 3 个及以上父节点共享同一子件的场景，建议开发自测时额外用合成数据补跑一遍，理论上"计数式"去重逻辑应天然支持，但本文档未单独构造用例验证。

---

## 附录 A：Fixture 数据速查

| 项 | 值 |
|---|---|
| SIMPLE E2E 标准夹具 | 苏州西门子 + 报价模板0608 v1.9 + 产品 `10110002` |
| COMPOSITE E2E 标准夹具 | `composite-product-flow.spec.ts` 自带 |
| DAG / 主子同步 fixture | 根 `3120018220` → 子 `2120011658`/`2120011659` → 均挂 `3110520789`（2 occurrence）→ 其子 `2101110225`（复用姊妹任务附录 A） |
| `plating_scheme` 现役方案编号样例 | `A0001`（见 `docs/RECORD.md` 2026-06-17 条目） |
| `uq_material_bom_item` 唯一索引维度（V315 现役定义） | `(system_type, customer_no, material_no, COALESCE(characteristic,''), COALESCE(bom_version,''), COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))` |
| `VersionedGroupSpec`/`VersionedV6Writer` 关键约束 | `newRows` 为空会被 I1 校验拒绝（`IllegalArgumentException`）——flip 路径**不可**误调 `writeVersionedGroup` |
| 需另建的测试专属报价单 | Q1（主测单，供本文档多数用例复用）/ Q2（他单隔离验证）/ Q0（已建单冻结基线，早于 Q1 提交）/ Q3（E2E-BACKFILL-1 一致性验证） |

---

## 附录 B：测试执行记录（执行时填）

| 用例组 | 判定 | 实测值/证据 | 备注 |
|---|---|---|---|
| UT-MIG | | | 迁移 |
| UT-B2-* | | | 导入 pending（AC-1） |
| UT-B3-* | | | ★视图改写核心（AC-2/3/15） |
| UT-B4-* | | | 锚点写入快照 |
| UT-B5-CHG/ADD/DEL | | | ★回填两路径核心（AC-5/6/7） |
| UT-B5-DAG/MC | | | ★DAG 去重 + 主子同步核心（AC-14） |
| UT-B5-CONSIST-1 | | | ★AC-8 一票核心 |
| UT-B5-FLIP | | | ★AC-18 flip 路径 |
| UT-B5-HIST/TXN/CLEAN | | | |
| UT-B6-* | | | 预览 + previewToken（AC-11/12） |
| UT-B7-* | | | 闸门（AC-4，无 N+1） |
| UT-B8-* | | | 状态机（AC-13） |
| UT-B9-* | | | 主档暂存 |
| UT-FREEZE / UT-SCHEME-* | | | ★AC-10/18 冻结门禁 |
| UT-NEG-* | | | V44 负向确认 |
| API-* | | | 接口契约 |
| UI-F1/F2/F3 | | | 前端手测 |
| E2E-QF-1 / E2E-CPF-1 | | | A/B 对比协议 |
| E2E-BACKFILL-* / E2E-GATE-1 | | | 新增 spec |
| REG-COST-*★ | | | AC-17 一票门禁 |
| REG-* 其余 / PERF-1 | | | |
| Q1~Q6 裁决记录 | | | 待技术总监回填 |
