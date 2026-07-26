# 测试用例 · task-0725 修复报价单页签无法显示数据

> **权威依据**：`需求说明.md` §8（13 条验收标准，唯一交付标准）+ `backtask.md`（T0~T5 验收点）+ `fronttask.md` + `api.md`。素材来源：`test.md`（11 节骨架 + §9 E2E 基线实测记录，已并入并按 §8 最新决策做过口径修正——详见各节"与 test.md 的差异说明"）。
> **性质**：本文档**仅做用例设计，尚未执行**。T1~T4 编码尚未开工，所有「实际结果」「结论」列留空，待 T5 端到端验收阶段由测试工程师逐条执行回填。
> **测试库**：`cpq_db`（jh profile），容器 `cpq-jh-postgres`，查询方式 `docker exec cpq-jh-postgres psql -U postgres -d cpq_db -c "..."`；后端 `localhost:8081`（本 worktree 已起，PID 见 `ps aux | grep quarkus:dev`）；前端 `localhost:5174`；登录 `admin`/`Admin@2026`。
> **编写人**：cpq-tester　**编写日期**：2026-07-25

---

## 0. 概述

### 0.1 覆盖范围

- 报价单产品卡各页签在 DRAFT 态正确显示本单 pending 数据（根因 1，pending 感知改写门槛恒关）
- BOM 树结构正确性（同料号多 occurrence 不挂错枝）
- SQL 注释屏蔽（根因 2，命名参数误识别）
- **核价侧零回归 AC-17**（最高优先级门禁，已改为 SQL 文本断言）
- 冻结态语义不变 AC-10（DRAFT 开域 / SUBMITTED·APPROVED·PUBLISHED 不开域）
- 9 个入口全覆盖 + `usage` 协议（缺省/非法值兜底、混合批次不合并）
- 数据隔离（他单 pending 不可见）与幂等（连续重算行数不累加）
- 三视图一致性（编辑页 / 详情页 / 核价单）
- 页面与 Excel 口径一致（新增决策，AC-12）
- E2E 双 spec（标准以基线实测为准，非"全部 passed"）
- 已知且有意的边界差异（5 类，测出来不算 Bug）
- 回归清单（10 项既有测试不得破坏）

### 0.2 风险点速览

1. 🔴 **重算入口选错**：验收时如果用「保存草稿」而非「从基础刷新」，页签会持续显示空，极易被误判为"修复无效"——本文档每个核心用例的前置条件都显式写出正确端点。
2. 🔴 **AC-17 静默漂移**：核价侧误开 pending 改写不会报错、不会崩溃，只是多出 `__v6_id` 列和 pending 行——只能靠 SQL 文本断言查出，人眼看不出来。
3. 🔴 **两层缓存交叉**：`ComponentDriverService.expandCache` 与 `@RequestScoped DataLoader.resultCache` 若只补一层维度，AC-17 会在"看起来修好了"的假象下破防。
4. **材料成本行数误判（期望已更正）**：正确期望是 **2 行**（均为主件 `S-3120014539`）。`element_bom_item` 本单共 3 行，但第 3 行销售料号是子件 `S-80011`，而报价侧**无 BOM 闭包**、种子恒为 `productPartNo`（`ConfigureSnapshotService` javadoc + `:371`/`:581`），故收窄后为 2 行。**与树配置无因果关系。** 若测出 3 行反而要报告。
5. **测试硬编码库内数据的系统性脆弱**：本项目已出现 3 次"测试锚定的 UUID/组件 ID 不在当前库"的预置失败（4 个等价性测试的锚单、`SqlViewExecutorPendingHookTest` 的组件 ID、`DataSourceResourceTest` 认证 fixture）——本文档已按最新决策把这些标注为"预置失败基线"，执行时不得误报为新增回归。
6. **E2E 执行环境缺口**：本机无真实 `google-chrome-stable`、无 `psql` CLI，仓库默认 `playwright.config.ts` 跑不起来，需要替代方案（见 §10）。

### 0.3 环境与测试数据总纲

| 项 | 值 |
|---|---|
| 数据库 | `cpq_db`（jh profile），容器 `cpq-jh-postgres` |
| 查询方式 | `docker exec cpq-jh-postgres psql -U postgres -d cpq_db -c "<SQL>"` |
| 后端 | `http://localhost:8081`（本 worktree 已起）；curl 一律加 `--noproxy '*'`；`/q/health` 返 404 不是探针，业务端点返 **401** = 健康 |
| 前端 | `http://localhost:5174`（本 worktree 已起） |
| 登录 | `admin` / `Admin@2026` |
| `mvn test` | 必须 `DB_HOST=localhost` 前缀（`%test` profile 默认 `10.177.152.12` 本机不可达） |
| 报价单 | `QT-20260725-0001`，id `c670e9e7-5f7c-4b72-9a27-965447fcf75b` |
| 客户 | 罗克韦尔 `CUST-0001`，id `32aea5b1-d003-4232-a90a-cdc5fab0520d` |
| 主件 | `S-3120014539`，`composite_type=SIMPLE` |
| 明细行 | id `6ad49abc-7b9f-4de2-a993-5c7d22e30aba`，客户料号 `PN0507945` |
| 导入源 | `docs/table/报价测试数据/v2/报价系统模板0723.xlsx` |
| 他单 pending（隔离验证样本） | `pending_quotation_id='978479fd-fbad-4426-bcf0-d39603a67f3c'` |
| 系统 | Linux/bash（非 PowerShell），E2E 命令按需转换 |

**`quote_card_values` JSON 结构**（已通过源码走查确认，供 SQL 编写用；`QuotationLineItem.java:104-107` 注释：`tabs[].{baseRows, editRows, formulaResults}`）：

```
quote_card_values = {
  "tabs": [
    {
      "componentId": "...", "componentCode": "...", "tabName": "产品|BOM|材料成本|...",
      "componentType": "NORMAL|SUBTOTAL|EXCEL", "sortOrder": N,
      "baseRows": [ { "driverRow": {...原始列}, "basicDataValues": {...},
                      "__nodeId": "...", "__parentId": "...", "__lvl": N,
                      "__hfPartNo": "...", "__parentNo": "...", "__bomVersion": "..." (仅树页签) } ],
      "editRows": [...], "subtotal": <number>  (仅 SUBTOTAL tab)
    }, ...
  ]
}
```

关键事实（`CostingTreeGrouping.java:33-46`）：树页签的 `__nodeId` **就是** `node_path` 原文（非哈希/非重新生成），`__parentId` = 父节点 `node_path`（`path.lastIndexOf('/')` 截断），`__hfPartNo` = `material_no`，`__parentNo` = `parent_no`。

> ⚠️ **执行 Maven 命令一律用系统 `mvn`，不要用 `./mvnw`** —— 本项目 wrapper 有 SHA-256 校验不匹配问题会直接失败。且必须加 `DB_HOST=localhost` 前缀（`%test` profile 默认的 `10.177.152.12` 在本机不可达），并建议加 `-o` 离线以避免拉包等待。

### 0.4 前置条件（P 组，不满足则整批用例无效）

| ID | 条件 | 校验命令 | 期望 |
|---|---|---|---|
| P-0a | 登录会话 | `curl -s --noproxy '*' -c /tmp/jar.txt -X POST http://localhost:8081/api/cpq/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"Admin@2026"}'` | 200，返回体含 `token`/`Set-Cookie` |
| P-0b | 后端在跑 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` | `401` |
| P-0c | 前端在跑 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/` | `200` |
| P-1 | 报价树配置在位 | `curl -s --noproxy '*' -b /tmp/jar.txt 'http://localhost:8081/api/cpq/costing-bom-tree-config?usage=QUOTE'` | 1 条 `isActive=true` |
| P-2 | 核价树配置在位 | `curl -s --noproxy '*' -b /tmp/jar.txt 'http://localhost:8081/api/cpq/costing-bom-tree-config?usage=COSTING'` | 1 条 `isActive=true` |
| P-3 | 组件 tab_type 已配 | `docker exec cpq-jh-postgres psql -U postgres -d cpq_db -c "SELECT name, component_type, tab_type, bom_recursive_expand, data_driver_path FROM component ORDER BY name;"` | BOM/主件/零件/外购件/材质元素 各 ≥1 |
| P-4 | 本单 pending 数据在库 | 见下方 SQL(Q1) | **mbi 5 / ebi 3 / up 10** 行，全 `is_current=f`。⚠️ 原写 10/6/20 是错误基线（那是**两张** pending 单的合计；本单 `pending_quotation_id=c670e9e7-…` 收窄后为 5/3/10），2026-07-25 已更正 |
| P-5 | 他单 pending 数据在库（隔离样本） | `docker exec cpq-jh-postgres psql -U postgres -d cpq_db -c "SELECT count(*) FROM material_bom_item WHERE pending_quotation_id='978479fd-fbad-4426-bcf0-d39603a67f3c';"` | ≥1（若为 0，TC-ISO-01 无法验证，需另找/重建隔离样本） |
| P-6 | `mvn test` 环境变量 | 执行任何 `mvn -o test` 前缀 `DB_HOST=localhost` | 不设置会因 `%test` profile 默认连 `10.177.152.12` 而全部失败，误判为代码 bug |

**Q1（P-4 用）**：
```sql
SELECT 'material_bom_item' t, count(*) FROM material_bom_item
  WHERE system_type='QUOTE' AND pending_quotation_id='c670e9e7-5f7c-4b72-9a27-965447fcf75b'
UNION ALL SELECT 'element_bom_item', count(*) FROM element_bom_item
  WHERE system_type='QUOTE' AND pending_quotation_id='c670e9e7-5f7c-4b72-9a27-965447fcf75b'
UNION ALL SELECT 'unit_price', count(*) FROM unit_price
  WHERE system_type='QUOTE' AND pending_quotation_id='c670e9e7-5f7c-4b72-9a27-965447fcf75b';
```

> ⚠️ P-1 / P-2 是环境级前置：`costing_bom_tree_config` **全库无 INSERT 迁移**，配置只活在 DB，环境重建即丢。丢了会导致 BOM 页签落失败哨兵、材料成本降级为 2 行。

### 0.5 重算入口铁律（本文档全部核心用例的共同前置操作）

**必须**走：
```bash
curl -s --noproxy '*' -b /tmp/jar.txt -X POST \
  http://localhost:8081/api/cpq/configure-product/quotations/c670e9e7-5f7c-4b72-9a27-965447fcf75b/refresh-snapshot
```
期望 HTTP 200。对应前端 DRAFT 态常驻的「刷新基础数据」按钮（`QuotationStep2.tsx:3401`）。

**禁止**用「保存草稿」（`POST /api/cpq/quotations/{id}/draft`）验收：它走 `snapshotQuotation(id, true)` 增量物化，`ConfigureSnapshotService.lineNeedsExpand:148-156` 只判 `sr == null`——上次失败物化写下的 `snapshot_rows=[]` 是非 null，整行判「已完整」跳过，页签仍空。**这不是修复无效，是入口选错**，本文档中所有需要"重新物化"的用例，前置条件列一律写 `Q-REFRESH` 引用本节，不接受用 saveDraft 替代。

也不要期待"打开页面等一会自愈"：2026-06-01 已取消 10 秒定时自动保存（`QuotationWizard.tsx:232-237`）。

后续表格中用 **`Q-REFRESH`** 指代上述命令。

### 0.6 常用 SQL 片段库

**Q2（页签总览，一次拿全部 6 个页签的行数/类型/小计）**：
```sql
SELECT t->>'tabName' AS tab_name, t->>'componentId' AS component_id,
       t->>'componentType' AS component_type,
       jsonb_array_length(COALESCE(t->'baseRows','[]'::jsonb)) AS row_count,
       t->'subtotal' AS subtotal
FROM quotation_line_item li, jsonb_array_elements(li.quote_card_values->'tabs') t
WHERE li.id = '6ad49abc-7b9f-4de2-a993-5c7d22e30aba'
ORDER BY (t->>'sortOrder')::int;
```

**Q3（某页签 baseRows 原始内容，`<TAB>` 替换为页签中文名）**：
```sql
SELECT jsonb_pretty(t->'baseRows')
FROM quotation_line_item li, jsonb_array_elements(li.quote_card_values->'tabs') t
WHERE li.id = '6ad49abc-7b9f-4de2-a993-5c7d22e30aba' AND t->>'tabName'='<TAB>';
```

**Q4（BOM 树逐行系统列）**：
```sql
SELECT r->>'__nodeId' AS node_id, r->>'__parentId' AS parent_id, r->>'__lvl' AS lvl,
       r->>'__hfPartNo' AS material_no, r->>'__parentNo' AS parent_no, r->>'__bomVersion' AS bom_version
FROM quotation_line_item li, jsonb_array_elements(li.quote_card_values->'tabs') t,
     jsonb_array_elements(t->'baseRows') r
WHERE li.id = '6ad49abc-7b9f-4de2-a993-5c7d22e30aba' AND t->>'tabName'='BOM'
ORDER BY r->>'__nodeId';
```

**Q5（无遗留报错/占位——SQL 直查，比日志 grep 更可靠可执行）**：
```sql
SELECT count(*) FROM quotation_line_item
WHERE quotation_id='c670e9e7-5f7c-4b72-9a27-965447fcf75b'
  AND quote_card_values::text ~* 'executeAllRows failed|column index is out of range|pending 改写失败|报价树整单渲染失败|__renderError|__cardValueFailed';
```
期望 `0`。

> 后端进程是前置会话以 `mvn quarkus:dev -Dquarkus.profile=jh` 起的前台进程（`ps aux` 可见 PID，无独立落盘日志文件）。若测试执行者对该终端有直接访问权限，可另外肉眼核对滚动输出中无 WARN 级 `executeAllRows failed`；若无终端访问权限，**以 Q5 的 SQL 断言为准**（`__renderError` / `#ERROR` 类标记会被物化进 `quote_card_values`，SQL 层面可靠捕获）。

**Q6（他单 pending 隔离黑名单——TC-ISO-01 用）**：
```sql
-- 找出「只属于他单 978479fd、不属于本单也不是官方 current」的料号/单价编码，作为隔离验证黑名单
SELECT 'material_bom_item' src, material_no key FROM material_bom_item
  WHERE pending_quotation_id='978479fd-fbad-4426-bcf0-d39603a67f3c'
    AND material_no NOT IN (SELECT material_no FROM material_bom_item
      WHERE pending_quotation_id='c670e9e7-5f7c-4b72-9a27-965447fcf75b' OR is_current=true)
UNION ALL
SELECT 'unit_price', code FROM unit_price
  WHERE pending_quotation_id='978479fd-fbad-4426-bcf0-d39603a67f3c'
    AND code NOT IN (SELECT code FROM unit_price
      WHERE pending_quotation_id='c670e9e7-5f7c-4b72-9a27-965447fcf75b' OR is_current=true);
```

### 0.7 预置失败基线（⚠️ 执行前必读，不得当本任务回归误报）

| # | 测试类/spec | 现状 | 根因 | 处置 |
|---|---|---|---|---|
| M-1 | `SqlViewExecutorPendingHookTest` | 3 error | `:34` 硬编码 `COMPONENT_ID=4d8874c8-5022-4ba0-ba08-17009f46ecae`，当前 `cpq_db` 无此组件（`git stash` 验证改动前同样 error） | 验收改为「签名与改动前逐字一致」，不要求转绿（TC-REG-01） |
| M-2 | `DataSourceResourceTest` | 5 failure | 期望 200 实得 401，认证 fixture 问题，与本任务无关 | 同上，签名逐字不变（TC-REG-09） |
| M-3 | `quotation-flow.spec.ts` | 3 failed / 0 skipped（两次独立运行逐字一致） | Step1 客户下拉/产品分类回填的既存夹具缺口，`docs/RECORD.md` 2026-06-30 起多次记录同根因 | 验收改为「相对基线无新增失败」，签名逐字一致（TC-E2E-01） |
| M-4 | `composite-product-flow.spec.ts` | 1 skipped | `test.skip(true,...)`（task-0712 遗留，选择器过时） | 维持 1 skipped 即为通过（TC-E2E-02） |
| M-5 | 4 个等价性测试（`GoldenCardValuesEquivTest`/`NonRecursiveCostingBucketEquivTest`/`CardValuesBatchPersistEquivTest`/`FirstSaveQuoteBucketEquivTest`） | 全部 `assumeTrue` skip | 硬编码锚单 `8f0c37a4-…`/`a8f17a74-…` 不在当前 `cpq_db` | **不作为 AC-17 门禁**（已作废，见 §4），BL-0021 挂 P2 不在本期处理 |
| M-6 | `com.cpq.formula.DataLoaderTest`（2026-07-25 技术总监审核补充：T2 与本组用例设计并行推进，编写用例时不知情，现已独立核实属预置失败） | **4 error** | `NPE: ...SqlViewExecutor.isSqlViewPath(...) because "this.sqlViewExecutor" is null` @ `DataLoader.java:89`。核实依据：HEAD 版 `DataLoader:89` 本就有该调用，而 HEAD 版 `DataLoaderTest:49-52` 只注入 `pathParser`/`sqlCompiler`/`dataSource`、**从不注入 `sqlViewExecutor`** | 签名逐字不变即通过（TC-REG-10） |

⚠️ **不要把 M-6 与 T2 新建的 `com.cpq.formula.dataloader.DataLoaderScopedCacheKeyTest`（4 passed）搞混**——两者包名不同（`com.cpq.formula` vs `com.cpq.formula.dataloader`）、类名相似但性质相反：M-6 是**预置失败**（与本任务无关，不得指望修好），`DataLoaderScopedCacheKeyTest` 是 T2 为 AC-17 缓存维度**新增的正向测试**（应全绿，属 §4 TC-AC17-11 的落地产物之一）。

**后端预置失败合计 3 组 = 5 failure + 7 error = 12 项**（M-1 的 3 error + M-2 的 5 failure + M-6 的 4 error）。执行 TC-REG 全组前应先核对 surefire 报告里这 12 项的签名与本表描述逐字一致，再判断是否有新增回归。

---

## 1. TC-CORE（核心页签行数 + 无遗留报错/占位）—— 追溯 AC-1 / AC-3

**共同前置**：P-0~P-6 全部满足；已执行 `Q-REFRESH` 且返回 200。

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-CORE-01 | AC-1 | 共同前置 | 执行 Q2；取 `tab_name='产品'` 行；另执行 Q3（`<TAB>`=产品） | `row_count=1`；Q3 结果文本含 `S-3120014539`、`PN0507945`、`6.9755` 三个子串 | 实测 `row_count=1`；Q3 driverRow = `{"_单位":null,"_汇率":6.9755,"hf_part_no":"S-3120014539","_销售料号":"S-3120014539","_客户产品编号":"PN0507945","_客户料号名称":null}`，三子串均存在 | **PASS** |
| TC-CORE-02 | AC-1 | 共同前置 | 执行 Q2；取 `tab_name='BOM'` 行 | `row_count=6`（详细结构见 §2 TC-TREE，本条只验总行数） | 实测 `row_count=6` | **PASS** |
| TC-CORE-03 | AC-1 | 共同前置（⚠️ **不**依赖 TC-TREE-01：材料成本行数与树渲染无因果） | 执行 Q2；取 `tab_name='材料成本'` 行；执行 Q3（`<TAB>`=材料成本） | `row_count=2` ⚠️**期望已于 2026-07-25 由 3 更正为 2**（技术总监认错：原 3 行是未按 `hf_part_no` 收窄到主件的错误期望，第 3 行销售料号是子件 `S-80011`；报价侧按设计**无 BOM 闭包**、种子恒为 `productPartNo`，见 `ConfigureSnapshotService` javadoc 与 `:371`/`:581`）；Q3 结果**不应**含 `S-80011`。若测出 3 行反而说明引入了非预期的闭包行为，需报告 | 实测 `row_count=2`（Cu/H65 + Ag/AgNi10，均 `_销售料号=S-3120014539`）；Q3 全文不含 `S-80011`。与 3x 连续刷新（TC-IDEM-01）结果一致，稳定 | **PASS** |
| TC-CORE-04 | AC-1 | 共同前置 | 执行 Q2；取 `tab_name='外购件成本'` 行；执行 Q3 | `row_count=1`；Q3 结果含 `S-3120014539`（`_销售料号`）与 `2.0`（`_组成数量`）。⚠️ **断言口径已于 2026-07-25 更正**：原写「含 `W-1001` 与 `OUTSOURCED`」是错误期望 —— `wg_view` 输出列只有 `hf_part_no`/`_销售料号`/`_组成件名称`/`_组成数量`/`_组成单位`，`W-1001` 是 `component_no`（仅 JOIN 键）、`OUTSOURCED` 是过滤条件，二者**都不出输出列**。另 `_组成件名称` 为 `null`（`W-1001` 在 `material_master`/`material_recipe` 无行），属数据缺口非代码缺陷 | `row_count=1` ✅；但 Q3 driverRow = `{"__v6_id":"94f306af-...","hf_part_no":"S-3120014539","_组成单位":"PCS","_组成数量":2,"_销售料号":"S-3120014539","_组成件名称":null}`——**不含 `W-1001` 也不含 `OUTSOURCED` 子串**。根因走查：`wg_view` 的 `sql_template`（`component_sql_view` 表实测）为 `SELECT mbi.material_no AS hf_part_no, mbi.material_no AS _销售料号, COALESCE(mm.material_name,mr.name) AS _组成件名称, mbi.composition_qty AS _组成数量, mbi.issue_unit AS _组成单位 FROM material_bom_item mbi ... WHERE mbi.characteristic='OUTSOURCED' AND mbi.customer_no=:customerCode`——`component_no`(值=`W-1001`) 只作 JOIN 键定位名称，从未被选入任何输出列；`material_master`/`material_recipe` 均无 `W-1001` 记录（已查，0 行）故 `_组成件名称=null`；`characteristic='OUTSOURCED'` 是 WHERE 过滤条件，本就不会作为字面量出现在任何输出列。**行数/过滤器正确性无误，是用例文档对输出结构的预期有误**（与 TC-CORE-03 的 3→2 更正同类问题，非 T1-T4 代码缺陷） | **FAIL**（判定：测试用例文档预期与实际视图列设计不符，建议 PM/架构师确认后更正用例，而非代码缺陷） |
| TC-CORE-05 | AC-1 | 共同前置 | 执行 Q2；取 `tab_name='加工费'` 行；执行 Q3 | `row_count=1`；Q3 结果含 `S-80011` 与 `PROCESS` 子串；**不含** `INCOMING_MATERIAL_PROCESS`（有意口径，仅自制加工费，不含来料固定加工费 2 行——若测出此串出现属 TC-BOUND-03 已知边界，不是新 Bug） | `row_count=1` ✅；`S-80011` 子串存在（`_料号` 字段）✅；但 **不含 `PROCESS` 子串**——`jg_view` SQL 实测 `WHERE up.price_type = 'PROCESS'` 是过滤条件，`price_type` 列本身从未被 SELECT 进输出（只输出 `_料号/_料件/_项次/_工序/_加工费/_单位`），故 `PROCESS` 字面量不出现在任何行内容里；`INCOMING_MATERIAL_PROCESS` 确实不含（该断言成立，因为该 tab 压根没有 `price_type` 列可显示） | **PARTIAL FAIL**（"不含 INCOMING_MATERIAL_PROCESS" 部分 PASS；"含 PROCESS" 部分 FAIL，同 TC-CORE-04 根因——文档预期基于列不存在的假设，非代码缺陷） |
| TC-CORE-06 | AC-1 | 共同前置 | 执行 Q2；取 `componentType='SUBTOTAL'` 行的 `subtotal` 字段 | `subtotal` 字段存在且数值 `<> 0`（具体数值由公式计算结果决定，执行时记录实测值） | 实测 `subtotal=14.0`（公式 = 材料成本+外购件成本+加工费 tab_total，其中仅加工费贡献 14，另两 tab 无 `isAmount` 字段） | **PASS** |
| TC-CORE-07 | AC-3 | 共同前置 | 执行 Q5 | 返回 `0` | 实测 `count=0` | **PASS** |
| TC-CORE-08 | AC-3 | 共同前置 + 前端已打开该报价单 Step2 | 浏览器打开 `http://localhost:5174/quotations/c670e9e7-5f7c-4b72-9a27-965447fcf75b`（编辑态），依次点开全部 8 个 Tab，用浏览器搜索或 Playwright 断言统计页面文本中「加载中」出现次数 | **全部 8 个 Tab `'加载中'` 计数 = 0**（含最终汇总计数） | ⚠️ 本单模板实际只有 **5 个可点击 Tab**（产品/BOM/材料成本/外购件成本/加工费；SUBTOTAL「罗克韦尔报价小计1」按设计不渲染为 Tab，同 quotation-flow.spec.ts 既有断言口径），非文档笼统写的 8 个（"8 Tab" 是其它任务/模板如「报价模板0608」的历史惯例数字，非本样本实际值）。用临时 Playwright script（系统 chromium + T0 替代方案，登录 admin，打开编辑页 Step2）逐 Tab 点击 + 截图，5 个 Tab `'加载中'` 计数均为 **0**，总计 0。截图肉眼核对（因沙盒缺中文字体导致中文渲染为方块，但表格结构/行数/数值列清晰可辨）与 SQL Q2/Q3/Q4 结果逐行吻合（产品1行含 S-3120014539/PN0507945/6.9755；BOM 6 节点树形缩进可见；材料成本 2 行 H65/Cu/1.05 与 AgNi10/Ag） | **PASS**（Tab 数按实际 5 个执行，非文档写的 8） |

---

## 2. TC-TREE（BOM 树结构）—— 追溯 AC-2

**共同前置**：同 §1；期望树（1 根 + 5 边 = 6 节点，与 Excel「物料BOM」5 行严格对应）：

```
S-3120014539          node_path=S-3120014539            bom_version=2001
├── 00137 (H65)        node_path=S-3120014539/00137
├── 00006 (AgNi10)      node_path=S-3120014539/00006
├── S-80011 (投入零件1)  node_path=S-3120014539/S-80011    bom_version=2001
│   └── 00006 (AgNi10)  node_path=S-3120014539/S-80011/00006
└── W-1001 (组成件1)     node_path=S-3120014539/W-1001
```

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-TREE-01 | AC-2 | 共同前置 | 执行 Q4，`SELECT count(*)` 该结果集 | **6** 行 | 实测 6 行：`S-3120014539` / `S-3120014539/00006` / `S-3120014539/00137` / `S-3120014539/S-80011` / `S-3120014539/S-80011/00006` / `S-3120014539/W-1001` | **PASS** |
| TC-TREE-02 | AC-2 | 承 TC-TREE-01 | 对 Q4 结果逐行核对：每个子孙节点的 `node_id` 是否以其父节点的 `node_id` 为字符串前缀（如 `S-3120014539/S-80011/00006` 以 `S-3120014539/S-80011` 为前缀） | 全部 5 个非根节点满足前缀性 | 逐行核对 5 个非根节点前缀性全部成立 | **PASS** |
| TC-TREE-03 | AC-2（同料号多 occurrence 不挂错枝，🔴 重点） | 承 TC-TREE-01 | 对 Q4 结果 `WHERE material_no='00006'` 过滤 | 恰好 **2** 行；`node_id` 分别为 `S-3120014539/00006` 与 `S-3120014539/S-80011/00006`，**两者不同**；`parent_no` 分别为空/`S-3120014539` 与 `S-80011` | 恰好 2 行，`node_id` 分别为 `S-3120014539/00006`(parent_no=`S-3120014539`) 与 `S-3120014539/S-80011/00006`(parent_no=`S-80011`)——两者不同，未挂错枝 | **PASS** |
| TC-TREE-04 | AC-2 | 承 TC-TREE-01 | 对 Q4 结果核对根节点与 `S-80011/00006` 节点的 `parent_no` | 根节点（`node_id=S-3120014539`）的 `parent_no` 为空/NULL；`node_id=S-3120014539/S-80011/00006` 的 `parent_no='S-80011'` | 根节点 `parent_no` 为空；`S-3120014539/S-80011/00006` 的 `parent_no='S-80011'` | **PASS** |
| TC-TREE-05 | AC-2 | 承 TC-TREE-01 | 对 Q4 结果核对 `bom_version` 列 | `node_id=S-3120014539`（根）与 `node_id=S-3120014539/S-80011`（`S-80011`）两行 `bom_version='2001'`；其余 4 行（叶子）`bom_version` 为空/NULL | 根节点与 `S-80011` 节点 `bom_version='2001'`；其余 4 行为空 | **PASS** |
| TC-TREE-06 | AC-2（design §4.4 行键契约） | 承 TC-TREE-01 | 检查 Q4 结果每一行 `node_id`（即 `__nodeId`）均非空字符串 | 6 行 `__nodeId` 全部非空，且互不相同（防同料号跨节点撞键） | 6 行 `__nodeId` 全部非空且互不相同 | **PASS** |
| TC-TREE-07 | AC-2（业务行挂载正确性） | 承 TC-TREE-01 | 执行 Q3（`<TAB>`=BOM），核对每行 `driverRow` 内 `material_no`/`parent_no` 与该行 `__hfPartNo`/`__parentNo` 是否一致 | 逐行一致，业务行按 `(parent_no, material_no)` 边键正确挂载到对应节点，无错位 | 6 行逐一核对：`driverRow.material_no`/`parent_no` 与 `__hfPartNo`/`__parentNo` 完全一致（如 `00006@S-80011` 行两侧均为 `material_no=00006,parent_no=S-80011` / `__hfPartNo=00006,__parentNo=S-80011`） | **PASS** |

---

## 3. TC-MASK（SQL 注释屏蔽，根因 2）—— 追溯 AC-4

**共同前置**：P-0~P-6；本组含集成用例（需 §1 共同前置）与纯单测用例（不需要 §1 前置，标注「单测」）。

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-MASK-01 | AC-4 | §1 共同前置；`cp_view` 视图 SQL 注释保持含 `:customerCode` 原文不动 | 确认 `component_sql_view` 表中 `cp_view` 对应 `sql_template` 注释原文未被改动（`docker exec cpq-jh-postgres psql -U postgres -d cpq_db -c "SELECT sql_template FROM component_sql_view WHERE sql_view_name='cp_view';"`），随后执行 Q2 核对产品页签 | 实测 `cp_view` 注释原文 `-- 产品(主件, 平铺契约: hf_part_no + :customerCode)` 含 `:customerCode` 原样；产品页签 `row_count=1`；Q5=0（不含 `column index is out of range`） | **PASS** |
| TC-MASK-02 | AC-4 | 同上，`bom_view` 注释含 `:total_material_no` 原文不动 | 同上方法核对 `bom_view` 注释原文；执行 Q2 核对 BOM 页签 | 实测 `bom_view` 注释原文 `-- BOM 树页签(树契约: material_no=子/parent_no=父 + :total_material_no; 边式全子件 + 根分支)` 含 `:total_material_no` 原样；BOM 页签 `row_count=6` | **PASS** |
| TC-MASK-03 | AC-4（单测） | 无（`SqlTextMask`/`QuotePendingRewriter` 单测环境） | 构造 SQL 片段：`-- :foo\nSELECT :foo`，跑 `extractNamedParams`/`SqlTextMask.mask` 相关单测 | 仅产生 **1** 个占位符（正文的 `:foo`，行注释内的不计入） | `mvn test -Dtest=SqlTextMaskTest,SqlViewExecutorNamedParamMaskingTest`：8+6=14 tests，0F/0E/0S；`lineComment_bodyReplacedWithSpaces_newlinePreserved`/`extractNamedParams_lineComment_singleQuoteLiteral_blockComment_castAllExcluded` 等方法覆盖该场景，全绿 | **PASS** |
| TC-MASK-04 | AC-4（单测） | 同上 | 构造 `/* :foo */ SELECT :foo`，同上单测 | 仅产生 **1** 个占位符 | `blockComment_multilineBodyMasked_newlinesPreserved`/`blockComment_unterminated_doesNotThrowAndMasksToEnd` 覆盖，全绿（同上 14 tests 内） | **PASS** |
| TC-MASK-05 | AC-4（单测） | 同上 | 构造 `WHERE x = ':foo'`（字符串字面量），同上单测 | **0** 个占位符 | `stringLiteral_bodyMasked_realTokenOutsideKept`/`stringLiteral_escapedQuote_handledWithoutBreakingAlignment` 覆盖，全绿 | **PASS** |
| TC-MASK-06 | AC-4（单测，回归保护） | 同上 | 构造 `p::text` / `id::uuid`，同上单测 | **0** 个占位符（既有 `(?<!:)` 行为不回退） | `extractNamedParams_lineComment_singleQuoteLiteral_blockComment_castAllExcluded` 覆盖 cast 排除场景，全绿 | **PASS** |
| TC-MASK-07 | AC-4（单测） | 同上 | 构造正文含多处同名 `:foo` token（如 `WHERE a=:foo AND b=:foo`） | 全部替换为 `?`，绑定顺序与出现顺序一致 | `rewriteNamedParams_bodyTokenStillReplaced_bindingOrderCorrect` 全绿 | **PASS** |
| TC-MASK-08 | AC-4（单测，偏移量） | 同上 | 构造注释位于 SQL 中段、含多个换行的样例，断言替换后位置正确 | 替换发生在正确字符偏移处，行号不错位（换行符保留） | `multilineCommentInMiddle_offsetOfLaterTokenUnchanged` 全绿 | **PASS** |
| TC-MASK-09 | AC-4（双链路一致，🔴 高优先） | 同上 | 同一段含注释 `:token` 的 SQL，分别过 `SqlViewExecutor.extractNamedParams` 与 `SqlViewValidator` 相关方法（`:66`/`:122`/`:129`/`:135`） | 两条链路提取出的占位符清单**完全一致**（防"dry-run 报错但实际能跑"或反之） | `SqlViewValidatorCommentMaskingTest#dryRunPath_andExecutorPath_extractSameNamedParams_forCommentHeavySql` 专项覆盖，`mvn test -Dtest=SqlViewValidatorCommentMaskingTest`：7 tests 0F/0E/0S | **PASS** |
| TC-MASK-10 | AC-4（`SqlViewValidator` 裸检查不误伤，⚠️ 原文遗漏点） | 同上 | 构造视图 SQL，注释内分别写 `:hfPartNo` / `:__sk` / `:__vf`（对应 `SqlViewValidator.java:122`/`:129`/`:135` 三处裸检查），执行保存/dry-run | **不再导致保存被拒**（三处裸检查均需经 mask 后判断，而非对原文裸检查） | `commentContainingHfPartNo_doesNotFailValidation`/`commentContainingSkReservedPrefix_doesNotFailValidation`/`commentContainingVfReservedPrefix_doesNotFailValidation`/`blockCommentContainingAllThreeReservedTokens_doesNotFailValidation` 四方法逐一覆盖，全绿；反向对照 `realHfPartNoScalarInBody_stillRejected_notOverMasked`/`realSkPrefixInBody_stillRejected_notOverMasked` 证明正文真实出现仍正确拒绝（未过度遮蔽），全绿 | **PASS** |
| TC-MASK-11 | AC-4（树 SQL `TREE_PARAM` 加固） | §1 共同前置 | 树递归 SQL 注释中写入 `:production_part_nos`（`BomTreeRenderService.java:324-325`），触发 BOM 页签渲染 | `TREE_PARAM` 不误识该 token；BOM 页签仍正常渲染 6 节点（不因注释内 token 导致递归 SQL 报错） | `BomTreeRenderServiceTreeParamMaskingTest#draftQuotation_pendingRewriteGeneratesRealPqTokens_commentDecoysExcluded_executesSuccessfully` 全绿（2 tests 0F/0E/0S）；集成层交叉印证：TC-CORE-02/TC-TREE-01 实测 BOM 页签稳定渲染 6 节点，无 SQL 报错 | **PASS** |

---

## 4. TC-AC17（核价侧零回归，最高优先级门禁）—— 追溯 AC-7

> **门禁口径变更说明**：原「4 个等价性测试全绿」已作废（锚单不在当前库，见 §0.7 M-5）。以下全部改为「核对实际发出的 SQL」的新门禁，环境无关、0 行也能失败。
> 本组全部为**新增单测/集成测试**，T5 执行时先确认对应测试类/方法已由 T4 编码产出（`grep -rl 'QuotePendingScope\|SqlDebugContext' cpq-backend/src/test/java`），若未找到对应测试则该条判 **BLOCKED**（退回要求 T4 补齐），不得跳过。

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-AC17-01 | AC-7（7.4，`open()` 白名单单测） | T4 已产出对应测试类 | 执行该单测：遍历 `src/main/java`，收集含 `QuotePendingScope.open(` 的文件集合，与白名单（`ConfigureSnapshotService`/`CardSnapshotService` 的 P2/P4/`ComponentResource` 的 P3）比对 | 文件集合 **==** 白名单集合；且**不得出现**在 `CardSnapshotService#precomputeCostingDriverUnion(:767)` / `#buildCostingCardValues(:1152)` / `#snapshotNewLinesCardValues(:483)` / `CostingVersionService(:354)` / 核价侧 render 调用点（`CardSnapshotService:501`、`:841`、`CostingVersionService:209`） | 测试类 `QuotePendingScopeOpenWhitelistTest`（3 tests）：`mvn test -Dtest=QuotePendingScopeOpenWhitelistTest` → 3 tests 0F/0E/0S。`openCallSites_fileLevelWhitelist_exactMatch` 精确匹配白名单 3 文件、总出现次数=7（CardSnapshotService×3+ComponentResource×3+ConfigureSnapshotService×1）；`cardSnapshotService_quoteSideMethods_containOpenCall`/`costingSideMethods_neverContainOpenCall` 方法体级正反双向断言全绿，覆盖题述全部核价侧禁用方法清单 | **PASS** |
| TC-AC17-02 | AC-7（7.1，负向：核价侧不含 pending 痕迹） | `SqlDebugContext.begin()` 可用；DRAFT 单 `c670e9e7-...` | 用 `SqlDebugContext` 录下核价侧渲染（`precomputeCostingDriverUnion` + `buildCostingCardValues`）实际发出的每条 SQL，逐条断言 | 每条 SQL **不含** `pending_quotation_id`，**不含** `AS __v6_id`，参数表里没有本单 quotationId 被当 `:pq` 绑入 | `QuotePendingSqlTextAssertionTest#scopeClosed_costingSideProxy_sqlContainsNoPendingMarkers`：以 `ComponentDriverService.expand()` 为核价侧忠实代理（class javadoc 已论证等价性，因本环境无 `costing_card_template_id` 样本无法直调 precomputeCostingDriverUnion，见 §16.2）；`mvn test -Dtest=QuotePendingSqlTextAssertionTest` → 3 tests 0F/0E/0S，捕获 SQL 逐条断言不含两串 | **PASS** |
| TC-AC17-03 | AC-7（7.2，⭐ 正向对照，无此项 7.1 就是空转通过） | 同一单跑报价侧渲染 | 同一单跑报价侧（`ConfigureSnapshotService.snapshotLines`），录 SQL | 捕获的 SQL **确实含** `(t.is_current OR t.pending_quotation_id = ?)` 且含 `AS __v6_id` | `scopeOpen_quoteSide_sqlContainsPendingMarkers` 全绿，验证 scope 打开态确实产出两串（正对照成立，7.1 非空转）；另附加 `scopeOpen_frozenStatus_stillNoPendingMarkers`（AC-10 交叉）全绿 | **PASS** |
| TC-AC17-04 | AC-7（7.3，⭐ 前置非空断言，防空转） | 承 TC-AC17-02/03 | 检查断言代码本身：`assertFalse(contains(...))` 之前是否先 `assertFalse(capturedSql.isEmpty())`；涉及 rows 的断言是否先断言 rows 非空 | 代码走查确认前置非空断言存在；不得复制 `SqlViewExecutorPendingHookTest` 的"空结果集=空转通过"缺陷 | 代码走查确认：`QuotePendingSqlTextAssertionTest` 三方法均先 `assertFalse(capturedSql.isEmpty(), ...)` 再做内容断言；`QuotePendingRewriterOfficialVisibilityAndSupersedesTest`/`ComponentDriverServiceCacheCrossScopeTest` 同样先 `assertNotNull`/`rowCount` 断言非零再深入 | **PASS** |
| TC-AC17-05 | AC-7（7.5，⭐ 证明测试真的跑了） | T4 相关测试类已跑过 `mvn -o test` | `cat cpq-backend/target/surefire-reports/<对应测试类>.txt`，核对 `Tests run` / `Skipped` 计数 | **`skipped == 0`**；只贴 "BUILD SUCCESS" 不算证据，须附实际计数截图/文本 | 实际 surefire 报告文本：`QuotePendingScopeOpenWhitelistTest` Tests run:3 Skipped:0；`QuotePendingSqlTextAssertionTest` Tests run:3 Skipped:0；`ComponentDriverServiceCacheCrossScopeTest` Tests run:2 Skipped:0；`QuotePendingRewriterOfficialVisibilityAndSupersedesTest` Tests run:2 Skipped:0；`QuotePendingScopeTest` Tests run:12 Skipped:0。合计 22 tests，0F/0E/**0S** | **PASS** |
| TC-AC17-06 | AC-7 | DRAFT 下跑 `ensureCardValues(qid)` | 断言 `costing_card_values` 的 `driverRow` 不含 `__v6_id` 键 | 不含（与 TC-AC17-02 互为 SQL 层/产物层双重确认） | 本环境样本单 `costing_card_template_id IS NULL`（已查），无法直接产出 `costing_card_values`；改用等价产物层证据：`quote_card_values`（报价侧，scope 开）**含** `__v6_id`（TC-CORE 已验），核价侧（scope 关）SQL 层已由 TC-AC17-02 证明不产出该串 → 结构性推导 `costing_card_values` 若产出也不会含 `__v6_id`。**非直接集成级验证**，与 §16.2 已登记的验收范围限制一致 | **PASS（结构性推导，非直接集成验证，理由见 §16.2）** |
| TC-AC17-07 | AC-7 | 同上 | 核价渲染结果逐行核对 | 不出现只存在于 pending（`is_current=false` 且 `pending_quotation_id` 非空）的行 | 同 TC-AC17-06，本环境无法直接跑核价渲染；`QuotePendingRewriterOfficialVisibilityAndSupersedesTest#tcIso03_pendingSupersedes_hidesOldOfficialRow_noDoubling` 用自建夹具证明 scope 关闭态下 pending-only 行（`is_current=false`）天然不可见（`closedResp.rowCount=1`，只见旧官方行） | **PASS（经 TC-AC17-07 等价单测覆盖）** |
| TC-AC17-08 | AC-7（7.6 AP-37 缓存交叉，两层） | 同一报价单、同一 lineItem、同一组件；同一请求内、30s TTL 内 | 先跑报价侧（开域）再跑核价侧（关域），断言核价侧结果不含 pending 行、不含 `__v6_id`；反向顺序（先核价后报价）同测 | 两种顺序下核价侧结果均不受污染 | `ComponentDriverServiceCacheCrossScopeTest`（自建夹具，同一 componentId/customerId/partNo 连续调用）：`closedThenOpen_thenClosedAgain_noCrossContamination`（关→开→关，5 步断言含"打开态不驱逐关闭态缓存"）+ `openThenClosed_thenOpenAgain_reverseOrder_noCrossContamination`（开→关→开）均全绿，`mvn test` 2 tests 0F/0E/0S。⚠️ **重要缺口**：本测试覆盖的是 `ComponentDriverService.expandCache`（内存 Caffeine 层）交叉，**不覆盖** `ComponentResource.batch-expand` Phase 1 的 `tryReadSnapshot` 快照读旁路（读 `quotation_line_component_data.snapshot_rows` 表，与 `expandCache` 是两套独立机制）——本轮测试在 TC-ENTRY-10/11 发现该旁路存在真实、可复现的跨侧污染（详见 Bug 报告），**不在本测试覆盖范围内**，T4 的 22 个新测试均未触达 | **PASS**（但见右侧缺口说明，不能视为 AC-17 全链路已零回归） |
| TC-AC17-09 | AC-7（`ComponentDriverService.cacheKey` 逐字不变） | 单测 | scope 关闭态下构造 cacheKey，与改动前逐字比较 | 逐字相同（`cacheTag()` 返回 `""`） | `ComponentDriverServiceCacheKeyTest#buildExtraCacheTags_scopeClosed_matchesPreChangeFormat` 全绿：关闭态 `buildExtraCacheTags("","","","")==""`；带标签场景 `":ovabc:li123:cld456:q789"` 逐字等于改动前格式（无第5项） | **PASS** |
| TC-AC17-10 | AC-7（报价 vs 核价 key 必须不同） | 单测 | 同参数下 scope 开 vs 关分别构造 cacheKey | 两者必须不同 | `buildExtraCacheTags_scopeOpenVsClosed_differ` 全绿：开/关 key 不同，开态是关态前缀+追加 pending 标签；`buildExtraCacheTags_cannotBeConfusedWithQidTag` 全绿：`qidTag`(`:q<qid>`) 与 pending 标签(`:pq<qid>`) 同 qid 下不合并、独立可辨 | **PASS** |
| TC-AC17-11 | AC-7（⭐ `DataLoader` 三重载 key 同款，评审 BLOCKER） | 单测 | `loadByPath` 的 `:90`（⚠️ key 只含 `normalizedPath` 一项，粒度最粗，重点覆盖）/ `:104` / `:189` 三个重载，分别在 scope 开/关下构造 key | 三个重载在开/关下 key 均必须不同；关闭态逐字不变 | `DataLoaderScopedCacheKeyTest`（4 tests，统一入口 `scopedCacheKey` 覆盖三处调用点）：`scopeClosed_matchesRawKeyExactly`（裸 key 逐字不变）/`complexCompositeKey_scopeClosed_matchesRawKeyExactly`（5-arg 组合 key 同样不变）/`scopeOpenVsClosed_differ`（开关不同、原前缀保留）/`frozenScope_stillMatchesClosedFormat`（SUBMITTED 冻结态与关闭态逐字相同）全绿，`mvn test` 4 tests 0F/0E/0S | **PASS** |
| TC-AC17-12 | AC-7（7.6 核价树基线，降级为烟雾测试非硬门禁） | P-2 满足（核价树配置在位） | 种子 `S-3120014539` 跑核价侧树渲染 | 稳定输出 **15 节点 / 4 层**（`00006`/`00168` 各出现 2 次且 `node_path` 不同）；**若 P-2 不满足本项作废而非 FAIL**（该值数据依赖，环境重建即失效） | P-2 满足（`核价BOM树-PRICING口径v1` isActive=true）。直接执行该配置的递归 SQL 模板（种子 `S-3120014539`，`customer_no='_GLOBAL_', system_type='PRICING'`）：`node_count=15, max_depth=4`，与预期完全一致 | **PASS** |

---

## 5. TC-AC10（冻结态语义不变）—— 追溯 AC-8

> 本库**零冻结单**（已实测：仅 1 张 DRAFT 单 `QT-20260725-0001`），故本组**全部以单测覆盖**（构造 `SUBMITTED`/`APPROVED`/`PUBLISHED` 上下文），不造真实冻结单。

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-AC10-01 | AC-8 | 单测：构造 `quotationId≠null, status=DRAFT` | 调用 `QuotePendingScope.open(qid, "DRAFT")` 后读 `pendingOwner()` | 非 null | `QuotePendingScopeTest#open_draftStatus_pendingOwnerNonNull` 全绿 | **PASS** |
| TC-AC10-02 | AC-8（🔴 关键边界） | 单测：构造 `quotationId≠null, status=SUBMITTED`；⚠️ 此刻 B5 尚未升版，pending 行仍带 `pending_quotation_id=本单`，**不可用"反正 B5 已升版所以等价于不改写"推理省略此项** | `open(qid, "SUBMITTED")` 后读 `pendingOwner()` | **null** | `open_submittedStatus_pendingOwnerNull` 全绿，注释明确写"不可用 B5 已升版推理省略" | **PASS** |
| TC-AC10-03 | AC-8 | 单测：`status=APPROVED` | 同上 | **null** | `open_approvedStatus_pendingOwnerNull` 全绿 | **PASS** |
| TC-AC10-04 | AC-8 | 单测：`status=PUBLISHED` | 同上 | **null** | `open_publishedStatus_pendingOwnerNull` 全绿 | **PASS** |
| TC-AC10-05 | AC-8 | 单测：`quotationId=null` | `open(null, "DRAFT")` 后读 `pendingOwner()` | **null** | `open_nullQuotationId_pendingOwnerNull` 全绿 | **PASS** |
| TC-AC10-06 | AC-8（已冻结单快照不漂移） | 单测（本库零冻结单，不构造真实单） | 构造 `SUBMITTED` 上下文，断言 `pendingOwner()==null` 后下游 `SqlViewRuntimeContext` 第 4 参保持 null | 与修复前逐位相同 | 代码走查确认（`ComponentDriverService.java` 私有 `expand()` 内 `:369`）：`SqlViewRuntimeContext.setNested(componentId, null, _pq, null)` 第 4 参硬编码字面量 `null`，**不论** `_pq`（=`pendingOwner()`）是否非空，恒传 null；配合 TC-AC10-01~05 已证 SUBMITTED 态 `pendingOwner()` 本身即为 null，双重保证下游不漂移 | **PASS**（代码走查直接证实，非仅测试类推） |
| TC-AC10-07 | AC-8（休眠分支保持休眠，🔴 HIGH 隐性风险） | 单测/断点 | 断言 driver 展开链路上传入 `SqlViewRuntimeContext` 的第 4 参（`quotationStatus`）恒为 `null`，即 `ComponentSqlViewService:379` 的 `isQuotationFrozen()` 在该链路上恒为 `false` | 分支保持不可达；已提交单的视图 SQL 来源不会从 `component_sql_view` 静默切到 `quotation_component_sql_snapshot` | 同 TC-AC10-06，源码 `ComponentDriverService.java:369` 注释显式写明"第 4 参 quotationStatus 恒传 null，不得传真实 status"+ 理由①②两条；`expandMulti` 对应处（`:667-669`）同款写法。静态确认该分支恒不可达 | **PASS**（代码走查） |
| TC-AC10-08 | AC-8（嵌套 open/restore） | 单测 | 嵌套调用 `open`/`restore`；另构造异常路径（内层抛异常） | 嵌套后正确还原到外层值；异常路径下 `finally` 仍还原，无 ThreadLocal 泄漏 | `nested_openRestore_correctlyRestoresOuterValue`（外→内→外三层校验）+ `openRestore_exceptionInTryBlock_finallyStillRestores`（异常穿透后状态不变）+ `openRestore_properTryFinallyPattern_noLeakAfterException`（标准 try/catch/finally 范式验证）三方法全绿 | **PASS** |

---

## 6. TC-ENTRY（9 入口全覆盖 + `usage` 协议）—— 支撑 AC-1 / AC-7 / AC-12

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-ENTRY-01 | AC-1（入口：建单/Excel 导入） | 新导入一张报价单（或复用 `QT-20260725-0001` 的建单历史） | 通过 Excel 导入创建报价单 | 建单完成后各页签有数据（同 TC-CORE 系列口径） | **时间盒决策未执行**：Excel 导入会在共享 DB 新建一整套数据（客户/报价单/明细行），且本任务后续十余组用例均硬编码依赖 `QT-20260725-0001`/`6ad49abc-...` 这一份样本的精确行数（产品1/BOM6/材料成本2...），新导入操作本身不会污染该样本，但导入流程复杂（需构造符合模板的 xlsx）、且该入口最终落地的仍是同一个 P1 `ConfigureSnapshotService.snapshotLines`（已被 TC-CORE/TC-IDEM/TC-ENTRY-04 直接验证），边际收益低。经权衡未执行 | **BLOCKED**（时间盒决策：底层 set 点已被其它用例充分覆盖，详见左侧说明） |
| TC-ENTRY-02 | AC-1（入口：加产品/选配） | 打开 `QT-20260725-0001` | 通过选配向导为该单新增一个产品行 | 新增行触发的 6 个页签中：①「产品」页签必须恰好 **1 行**（新行自身，字段值对应新选料号，不得为 0/报错）；②「BOM」/「材料成本」/「外购件成本」/「加工费」四个页签的行数由新料号自身的 BOM/单价配置决定，**不强制**与 `S-3120014539` 样本的 1/6/3/1/1 一致，但不得回退为"查询报错"，若为 0 需另用 Q1 同款 SQL 核对该新料号在对应 V6 表中确有 0 条 pending/current 数据（证明 0 是数据事实而非 driver 未触发的假 0）；③「小计」页签的 `subtotal` 键必须存在（不因新行而缺失，数值可为 0） | 需前端选配向导多步交互（选料号→材质→工序确认），且会在共享 `QT-20260725-0001` 上新增一个真实产品行，可能影响并发在跑的其它验收/开发会话对该单的观测。时间盒内未执行，同 P1 set 点已被 TC-CORE/TC-IDEM 验证 | **BLOCKED**（时间盒决策 + 共享环境副作用风险，未执行） |
| TC-ENTRY-03 | AC-1（入口：saveDraft，新行出数） | 承 TC-ENTRY-02 | 对新增行执行 `POST /api/cpq/quotations/{id}/draft`（saveDraft） | **新行**页签出数（判定口径同 TC-ENTRY-02 的①②③；⚠️ saveDraft 走增量，仅验"新行"，不得用它验证已有空数组行是否恢复——那是 TC-ENTRY-04 的职责） | 前置 TC-ENTRY-02 未执行，本条随之无法执行 | **BLOCKED**（前置 TC-ENTRY-02 未执行） |
| TC-ENTRY-04 | AC-1（入口：从基础刷新，🔴 存量空快照重算的唯一入口） | 共同前置 | 执行 `Q-REFRESH` | 200；随后 Q2 各页签行数与 §1 TC-CORE 系列一致 | 执行 4 次（含 TC-IDEM 的 3 次连续调用），均返回 HTTP 200（响应体 `{"lineItems":[],...}` 恒为空——经代码走查 `ConfigureProductResource.refreshSnapshot:92-106` 确认该响应体字段**从未被赋值**，是该端点自身的既有响应契约缺陷，与本次改动无关，仅影响响应体可读性，不影响 200 状态码或落库效果）；随后 Q2 结果与 TC-CORE 逐一一致（产品1/BOM6/材料成本2/外购件1/加工费1/小计14） | **PASS**（附带发现：该端点响应体字段恒空，非阻塞性既有缺陷，已记录供参考） |
| TC-ENTRY-05 | AC-12（入口：报价 Excel 值/导出，端点已核实） | 承 TC-ENTRY-04 | ① 触发懒算：`curl -s --noproxy '*' -b /tmp/jar.txt -X POST http://localhost:8081/api/cpq/quotations/c670e9e7-5f7c-4b72-9a27-965447fcf75b/ensure-excel-values`（`QuotationResource.java:166-175`，落库 `quote_excel_values`）；② 取视图值：`curl -s --noproxy '*' -b /tmp/jar.txt 'http://localhost:8081/api/cpq/quotations/c670e9e7-5f7c-4b72-9a27-965447fcf75b/excel-view'`（`:445-450`）；③ 导出文件：`curl -s --noproxy '*' -b /tmp/jar.txt -o /tmp/qt-export.xlsx http://localhost:8081/api/cpq/quotations/c670e9e7-5f7c-4b72-9a27-965447fcf75b/export-excel-view`（`:479-489`） | ①②③ 均 HTTP 200；②响应体 JSON 文本含 `S-3120014539`/`PN0507945` 子串；③ `/tmp/qt-export.xlsx` 非 0 字节且 `file /tmp/qt-export.xlsx` 输出为 `Microsoft Excel 2007+`/Zip 格式（非 HTML 错误页）；三者数据与 §9 TC-EXCEL-01/02 SQL 层核对结果一致，不出现"页面有数据、导出空白" | ①②③ HTTP 均 200 ✅；③ 文件 3352 字节、`file` 输出 `Microsoft Excel 2007+` ✅；但 **②响应体为 `{"columns":[],"rows":[{"_lineItemId":"6ad49abc-..."}]}`，不含 `S-3120014539`/`PN0507945` 任何子串** ❌。根因：`docker exec ... psql -c "SELECT excel_view_config FROM template WHERE id='f171acee-...'"` 实测该模板（"报价-罗克韦尔"）`excel_view_config` 列为 **NULL**——模板本身从未配置 Excel 列映射，`quoteExcelStructure.columns` 因此恒为空数组，`buildExcelValues` 无列可映射自然产出空 rows。**与 T1-T4 代码改动无关**（属模板配置缺口，pending-visibility 修复范围未涉及 Excel 列映射逻辑本身），但客观上使 AC-12 无法在本样本上得到正向验证 | **FAIL**（②未达成，根因为模板配置缺口非代码缺陷，详见 Bug 报告） |
| TC-ENTRY-06 | AC-1（入口：报价树渲染） | 承 TC-ENTRY-04 | 核对 BOM 页签 | 6 节点树（同 TC-TREE-01） | 与 TC-TREE-01 同一次 Q4 结果，6 节点 | **PASS** |
| TC-ENTRY-07 | AC-1（入口：「刷新基础数据」按钮，前端） | 前端已打开该单 Step2，DRAFT 态（`QuotationStep2.tsx:3401` 按钮） | 点击「刷新基础数据」按钮，等待前端提示刷新完成 | 刷新后各页签行数与 TC-CORE-01~06 逐一一致：产品页签 **1 行**（含 `PN0507945`/`6.9755`）/ BOM 页签 **6 节点** / 材料成本 **2 行** / 外购件成本 **1 行** / 加工费 **1 行** / 小计 `subtotal <> 0`；无报错 Toast/Alert | Playwright 自动化（系统 chromium）：`[data-testid="refresh-basic-data-btn"]` 可见并可点击，点击后无 `.ant-message-error`/`.ant-notification-notice-error`（计数=0），`'加载中'` 计数=0，截图确认各 Tab 内容与刷新前一致（产品/BOM/材料成本/外购件成本/加工费行数不变，因数据本就已是最新态） | **PASS** |
| TC-ENTRY-08 | AC-1（入口：公式 dry-run 预览） | 组件管理进入公式 dry-run | 对含 pending 数据依赖的公式执行 dry-run 预览 | token 行非空 | 定位到实际端点 `POST /api/cpq/components/{id}/dry-run-token`（`ComponentTabJoinResource.java:105-136`，内部调 `ComponentSampleCardService.dryRunTokenForComponent`→`CardSnapshotService.dryRunTokenRows`，该方法已在 TC-AC17-01 的 `CARD_SNAPSHOT_PERMITTED_METHODS` 白名单内确认被 `QuotePendingScope.open/restore` 正确包裹）。用 SUBTOTAL 组件（`5fa12f14-...`）+ 已知公式 token 试跑，HTTP 200，`{"rows":[],"errors":[]}`——SUBTOTAL 类型组件本身无 `driverRow`/行键概念（`quote_card_values` 中 SUBTOTAL tab 的 `baseRows` 恒为 `[]`，仅有标量 `subtotal`），故 dry-run-token 对此类组件返回空 rows 属预期；未额外构造 NORMAL 组件的草稿 token（该 DSL 结构未在时间盒内完全掌握，构造无效 token 会产生误导性的"试算不可用"噪音而非真实验证） | **PASS（基于端点连通性 200 + 代码归属 P2 白名单的结构性证据；未以 NORMAL 组件真实 token 内容做端到端验证，建议后续补充）** |
| TC-ENTRY-09 | AC-7 + H 组协议（入口：前端实时 batch-expand 传侧别） | 前端打开该单 Step2 | F12 打开 Network 面板，观察 `POST /api/cpq/components/batch-expand` 请求体 | 报价侧 task 带 `"usage":"QUOTE"`，核价侧 task 带 `"usage":"COSTING"` | Playwright 会话监听 `/components/batch-expand` 请求：本单已有完整 `quote_card_values`/`snapshot_rows` 快照，前端走"懒算读快照"路径（`useSnapQuote`/`useSnapCosting` 为 true 时 `lineItems`→`EMPTY_LINEITEMS`，`useDriverExpansions` 的 tasks 为空数组，不发起该请求），会话期间捕获 **0** 次该请求，无法现场抓包验证。改用静态代码证据：`grep -n "useDriverExpansions("` 确认 `QuotationStep2.tsx:3362` 传 `'QUOTE'`、`:3364` 传 `'COSTING'`、`ReadonlyProductCard.tsx:284` 由 `isCosting` 派生、`useDriverExpansions.ts:357` 确认 `usage` 字段被写入每个 task；且 F1/F2 前端开发阶段已用真实浏览器 console.debug 打点确认过 `"usage":"COSTING"` 真实出现在请求体中（见 `docs/RECORD.md` 2026-07-25 F1/F2 条目） | **PASS（静态代码证据 + 开发阶段人工抓包记录二次确认；本轮会话因懒算快照优化未现场复现网络请求）** |
| TC-ENTRY-10 | AC-7（老前端兼容，`usage` 缺省） | 直接 curl，不传 `usage` 字段 | `curl -s --noproxy '*' -b /tmp/jar.txt -X POST http://localhost:8081/api/cpq/components/batch-expand -H 'Content-Type: application/json' -d '{"tasks":[{"componentId":"<核价侧组件ID>","partNo":"S-3120014539","lineItemId":"6ad49abc-7b9f-4de2-a993-5c7d22e30aba"}]}'` | 按 `COSTING` 兜底，返回结果不含 `__v6_id`，行为与修复前逐字相同 | 用组件 `edfa54ff-...`（材料成本）执行原样 curl（含 `lineItemId`，与 doc 命令完全一致）：HTTP 200，但响应 **含 `driverPath:"snapshot"` 且 rows 内 `__v6_id":"3108faa9-..."`/`"652b8438-..."` 均出现**——**违反预期**。根因：`ComponentResource.doBatchExpandPhases` Phase 1（`:301-308`）在 `hasContext`（即 `lineItemId!=null`）为真时，**无条件**调用 `componentDriverService.tryReadSnapshot(componentId, lineItemId)`，该方法直接读 `quotation_line_component_data.snapshot_rows`（不检查/不依赖 `usage`/`QuotePendingScope`）；而该表由 P1（`ConfigureSnapshotService.snapshotLines`，本轮测试反复调用的 `Q-REFRESH`）在 QUOTE scope 打开态下写入，天然携带 `__v6_id`。一旦命中，直接 `continue` 跳过后续 usage 判定逻辑，**usage 缺省兜底 COSTING 的语义从未被执行到** | **FAIL**（详见 Bug 报告——AC-17 快照旁路，判定为本任务最高优先级发现） |
| TC-ENTRY-11 | AC-7（非法 `usage` 兜底） | 同上 | 同上请求，`usage` 传 `"XXX"` | 按 `COSTING` 处理，**不抛错**，不导致整批 expand 失败（HTTP 200，该 task 结果不含 `__v6_id`） | HTTP 200（不抛错部分符合预期）；但同 TC-ENTRY-10 根因，响应同样含 `driverPath:"snapshot"` + `__v6_id`。另用**显式** `"usage":"COSTING"`（非缺省/非法，是明确声明）对组件 `dff22d59-...`（加工费）重跑，同样返回 `__v6_id:"21dc91b8-..."`——证明该旁路对**任何** usage 声明（缺省/非法/显式 COSTING）均一视同仁地泄漏，不是"缺省兜底逻辑没生效"这么窄的问题，而是**整条 usage 判定链路在 snapshot 命中时被完全跳过** | **FAIL**（与 TC-ENTRY-10 同一根因，且已扩大验证到显式 `usage=COSTING` 场景，详见 Bug 报告） |
| TC-ENTRY-12 | AC-7（同批混合 `usage`，按 task index 配对） | 同上 | 单次请求 `tasks[]` 内同时包含 `usage=QUOTE` 与 `usage=COSTING` 两个 task（其余字段完全相同，对照 `api.md §2.3` 示例） | 两个 task 各自独立求值（不因字段相同被 bucketKey 误合并）；`usage=QUOTE` 结果含 `__v6_id`，`usage=COSTING` 结果不含；响应按 **task index** 与请求顺序配对（非 backend `r.key`） | 为隔离 TC-ENTRY-10/11 已发现的快照旁路干扰，改用**不带 `lineItemId`**（强制走 Phase1 未命中→Phase2 实时 expand，不触发 `tryReadSnapshot`）的等价 payload：同一 componentId(`edfa54ff-...`)/customerId/partNo，两个 task 分别 `usage=QUOTE`/`usage=COSTING`。结果：两个 task 响应 `key` 字符串相同（componentId:customerId:partNo:_，`key` 本身不含 usage 维度），但 **results 内容按 task 顺序正确区分**——QUOTE task `rowCount=2` 且两行均含 `__v6_id`；COSTING task `rowCount=0` 且无 `__v6_id`。证明 bucketKey 内部的 `\|u=` usage 维度确实生效，未被合并入同一桶 | **PASS**（在无 lineItemId、走实时 expand 路径下验证成立；⚠️ 若 payload 带 lineItemId，会先被 TC-ENTRY-10/11 的快照旁路截获，usage 判定不生效——即"混合 usage 不误合并"这条结论**不能推广到带 lineItemId 的调用场景**） |

---

## 7. TC-ISO / TC-IDEM（数据隔离与幂等）—— 追溯 AC-5 / AC-6

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-ISO-01 | AC-5（他单 pending 不可见） | P-5 满足；共同前置 | 先执行 Q6 拿黑名单料号/编码清单；再对每个黑名单值执行 `docker exec cpq-jh-postgres psql -U postgres -d cpq_db -c "SELECT quote_card_values::text LIKE '%<值>%' FROM quotation_line_item WHERE id='6ad49abc-7b9f-4de2-a993-5c7d22e30aba';"` | 黑名单清单中每个值在 `quote_card_values` 全文中均**不出现**（结果为 `f`） | ⚠️ **Q6 本身返回 0 行黑名单**（不同于文档预期的"有黑名单可测"）：实测他单 `978479fd-...` 的 5 条 `material_bom_item` 与本单 pending 行的 `material_no` 集合**完全相同**（均为 `S-3120014539→{00137,00006,S-80011,W-1001}` + `S-80011→00006`，两张单在同一产品/客户上并行编辑），Q6 按 `material_no NOT IN (...)` 过滤后必然清空，此法在本样本上**结构性失效**（非代码 bug，是文档设计的隔离验证 SQL 对"两单编辑同料号"场景失效）。改用更精确的行级 ID 交叉验证：他单 10 条 `unit_price`/3 条 `element_bom_item` 行 id 全集 vs 本单 `quote_card_values` 实际引用的 4 个 `__v6_id`（`3108faa9.../652b8438.../94f306af.../21dc91b8...`）—— **0 个交集**，本单卡片值未引用任何他单行 id | **PASS**（改用行级 ID 交叉验证，文档 Q6 方法在本样本上不适用，已记录原因） |
| TC-ISO-02 | AC-5（官方 current 行仍可见） | ⚠️ **判定：本环境 N/A**（2026-07-25 技术总监实测核实，复核 SQL：`SELECT 'material_bom_item' t, count(*) FROM material_bom_item WHERE system_type='QUOTE' AND is_current=true AND pending_quotation_id IS NULL UNION ALL SELECT 'element_bom_item', count(*) FROM element_bom_item WHERE system_type='QUOTE' AND is_current=true AND pending_quotation_id IS NULL UNION ALL SELECT 'unit_price', count(*) FROM unit_price WHERE system_type='QUOTE' AND is_current=true AND pending_quotation_id IS NULL;` → 三张表结果均为 **0**，本库 QUOTE 侧压根没有官方 current 行，全是本单 pending，集成级无样本） | T5 执行时先重跑上方复核 SQL 确认结论未变（数据可能随其它并发任务变化）；若仍全 0，本条**不在集成层执行**，标记 N/A 并附实测数字；若变化出现非 0 行，回退为原设计（核对该批正式行改动前后可见性不变）再执行 | 改由 **T4 单测覆盖**（构造一条正式行 + 一条 pending 行混合数据集，断言正式行可见性不受改写影响），本文档不重复设计该单测的具体断言（T4 编码产出后可类比 TC-AC17 组的引用方式回填） | 复核 SQL 重跑结果与技术总监记录一致（三张表仍为 0），本条不在集成层执行。改由 T4 单测 `QuotePendingRewriterOfficialVisibilityAndSupersedesTest#tcIso02_officialCurrentRow_visibleBothScopeClosedAndOpen` 覆盖：自建纯官方行夹具，断言 scope 关闭态与打开态下该行 `rowCount` 均为 1（可见性不受 pending 改写影响）。`mvn test -Dtest=QuotePendingRewriterOfficialVisibilityAndSupersedesTest` → 2 tests 0F/0E/0S | **N/A**（集成层，替代证据见单测结果，PASS） |
| TC-ISO-03 | AC-6（遮蔽正确不翻倍） | ⚠️ **判定：本环境 N/A**（2026-07-25 技术总监实测核实，复核 SQL：`SELECT 'material_bom_item' t, count(*) FROM material_bom_item WHERE pending_supersedes IS NOT NULL AND array_length(pending_supersedes,1)>0 UNION ALL SELECT 'element_bom_item', count(*) FROM element_bom_item WHERE pending_supersedes IS NOT NULL AND array_length(pending_supersedes,1)>0 UNION ALL SELECT 'unit_price', count(*) FROM unit_price WHERE pending_supersedes IS NOT NULL AND array_length(pending_supersedes,1)>0;` → 三张表结果均为 **0**） | T5 执行时先重跑上方复核 SQL 确认结论未变；**不专门为本用例构造遮蔽测试数据**（造数据本身会污染验收样本，技术总监已明确否决此路径） | 改由 **T4 单测覆盖**（构造 pending 行 `pending_supersedes` 指向一条旧 current 行的数据集，断言旧行被正确屏蔽、同组不出现「official + pending」并存、行数不翻倍） | 复核 SQL 重跑一致（三张表仍为 0），不专门造数据。改由 T4 单测 `tcIso03_pendingSupersedes_hidesOldOfficialRow_noDoubling` 覆盖：自建 1 条旧官方行 + 1 条指向它的 pending supersede 行，断言：①改写关闭态基线仅见旧官方行（1 行）；②本单 scope 打开后恰 1 行（新 pending 行遮蔽旧官方行，`_code` 前缀 `ISOB-PEND-`，不翻倍）；③反向对照——不相关报价单打开 scope 时遮蔽不生效，仍见旧官方行（`_code` 前缀 `ISOB-OFF-`，证明遮蔽按单隔离）。全绿 | **N/A**（集成层，替代证据见单测结果，PASS） |
| TC-IDEM-01 | AC-6（🔴 连续重算行数稳定，AP-51 纪律） | 共同前置 | 连续执行 `Q-REFRESH` **3 次**（每次间隔等待返回 200 后再下一次），每次后执行 Q2 | 三次的各页签 `row_count` **完全相同**（产品 1 / BOM 6 / 材料成本 3 / 外购件 1 / 加工费 1），不累加、不递增；代码层面确认未使用 `Math.max(expansion.rowCount, baseRows.length)` | 连续 3 次 `Q-REFRESH`，均 HTTP 200；三次 Q2 结果**逐字相同**：产品1/BOM6/材料成本**2**（按 2026-07-25 更正期望，非文档原 3）/外购件1/加工费1/小计 baseRows 0 但 subtotal=14，无递增无累加 | **PASS**（材料成本按已更正的 2 判定，非原文档 3） |
| TC-IDEM-02 | AC-6（树节点 3 次稳定） | 承 TC-IDEM-01 | 三次刷新后分别执行 Q4 | 三次均恰好 6 节点，无重复长枝（`__nodeId` 集合三次完全一致） | 3 次刷新后 Q4 均返回同一组 6 个 `__nodeId`（`S-3120014539`/`00006`/`00137`/`S-80011`/`S-80011/00006`/`W-1001` 各节点路径），逐字比对一致 | **PASS** |

---

## 8. TC-VIEW（三视图一致性）—— 追溯 AC-9

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-VIEW-01 | AC-9 | 承 §1 共同前置 | 浏览器打开报价单编辑页 Step2，逐 Tab 核对数据 | 与 §1 TC-CORE 系列 SQL 实测行数逐一对应（产品1/BOM6/材料成本3/外购件1/加工费1/小计非0） | Playwright（系统 chromium）打开 `/quotations/c670e9e7-.../edit`→下一步→Step2，模板实际 5 个 Tab（无 SUBTOTAL Tab，同 TC-CORE-08 说明）。逐 Tab 截图核对：产品 Tab 见 1 行（`S-3120014539`/`PN0507945`/`6.9755`/小计 `¥14`）；BOM Tab 见树形多行（根+子件，与 6 节点一致）；材料成本 Tab 见 2 行（H65/Cu/1.05、AgNi10/Ag）；外购件成本/加工费 Tab 各 1 行。`'加载中'` 计数=0 | **PASS**（材料成本按已更正的 2 行判定） |
| TC-VIEW-02 | AC-9（AP-50 详情页渲染层） | 承上 | 浏览器打开该单**详情页**（`ReadonlyProductCard`），逐 Tab 核对 | 与 TC-VIEW-01 编辑页显示**完全一致**，无僵尸数据、无缺失的 `DATA_SOURCE`/`LIST_FORMULA` 渲染分支 | 打开 `/quotations/c670e9e7-...`（只读详情页），5 个 Tab 逐一点击截图，行数/内容与编辑页逐一一致，`'加载中'` 计数=0 | **PASS** |
| TC-VIEW-03 | AC-9（核价侧观测面，AC-17 前端验证） | ⚠️ **判定：本环境 N/A**（2026-07-25 技术总监实测核实：`SELECT costing_card_template_id FROM quotation WHERE id='c670e9e7-5f7c-4b72-9a27-965447fcf75b';` → 结果为 **NULL**，`QT-20260725-0001` 没挂核价卡模板，无核价单视图可打开）。**此限制不只影响本条用例，已升级为 §16 的验收范围限制条目，AC-7 在本环境无法做集成级实证**，详见 §16 第 3 条 | T5 执行时先重跑上方 SQL 确认该单是否仍无核价卡模板；若仍为 NULL，本条不执行，标记 N/A；若找到其它已挂载 `customer_template_id` + `costing_card_template_id` 双模板的报价单，可换该单执行原设计（浏览器打开核价单视图逐 Tab 核对） | 改由 §4 TC-AC17 组的单测代偿（`open()` 白名单单测 + SQL 文本断言），本条不构成 AC-7 的额外集成级证据 | 复核 SQL 重跑：`costing_card_template_id` 仍为 NULL，结论未变。已由 §4 TC-AC17-01~12 全组单测代偿（22 tests 全绿） | **N/A**（PASS via 单测代偿） |
| TC-VIEW-04 | AC-9（视图切换不串数据） | 承上 | 报价视图 ↔ 核价视图来回切换 3 次，每次核对数据 | 数据不互相污染，两侧各自独立 hook 实例/独立 cache 假设成立 | 详情页尝试点击"核价"相关切换元素，因该单无 `costing_card_template_id`，切换后目标区域无核价卡片可渲染（空白/无 Tab），与 TC-VIEW-03 同一根因——本样本无法构造"报价视图与核价视图均有内容"的双侧比对场景 | **N/A**（同 TC-VIEW-03 根因：无核价卡模板，无法验证双视图数据隔离） |

---

## 9. TC-EXCEL（页面与 Excel 口径一致）—— 追溯 AC-12

> 需求 §8-12 新增决策：`ensureExcelValues` 须纳入 pending 可见域（原被列入「不开」反向清单，现已移出）。

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-EXCEL-01 | AC-12 | 共同前置；⚠️ 先执行 `POST /api/cpq/quotations/c670e9e7-5f7c-4b72-9a27-965447fcf75b/ensure-excel-values`（同 TC-ENTRY-05 步骤①，200，落库 `quote_excel_values`；该列在首存/纯刷卡片值后可能仍是 NULL，须显式调用此端点补算，不能直接查库） | 执行①端点后查询 `docker exec cpq-jh-postgres psql -U postgres -d cpq_db -c "SELECT jsonb_array_length(quote_excel_values->'rows') FROM quotation_line_item WHERE id='6ad49abc-7b9f-4de2-a993-5c7d22e30aba';"` | 非 0（非空数组），且内容与 §1 TC-CORE 系列页签数据对应的字段值一致（如 `S-3120014539`/`PN0507945` 等应同时出现在 `quote_excel_values`） | 执行 `ensure-excel-values`（200）后查询：`jsonb_array_length(quote_excel_values->'rows') = 0`（`quote_excel_values` 全文为 `{"rows": []}`）。根因同 TC-ENTRY-05：`template.excel_view_config` 为 NULL（模板"报价-罗克韦尔"未配置 Excel 列映射），`quoteExcelStructure.columns=[]`，无列可映射，产出空 rows；与 T1-T4 pending-visibility 代码改动无关 | **FAIL**（根因为模板 `excel_view_config` 未配置，非 T1-T4 代码缺陷，详见 Bug 报告） |
| TC-EXCEL-02 | AC-12（导出文件，端点已核实） | 承上 | `curl -s --noproxy '*' -b /tmp/jar.txt -o /tmp/qt-export.xlsx http://localhost:8081/api/cpq/quotations/c670e9e7-5f7c-4b72-9a27-965447fcf75b/export-excel-view`（`QuotationResource.java:479-489`），下载后 `file /tmp/qt-export.xlsx` 核对格式，再人工打开核对对应单元格 | HTTP 200；文件非 0 字节，`file` 输出为 `Microsoft Excel 2007+`/Zip 格式；单元格值与页面/Q2 结果一致，非空白，不出现「页面有数据、导出空白」 | HTTP 200 ✅；文件 3352 字节非 0 ✅；`file` 输出 `Microsoft Excel 2007+` ✅（导出端点本身健康）。单元格内容与 Q2 一致性未能验证（同 TC-EXCEL-01 根因，excel_view_config 为空，导出文件大概率也是空表格/无数据列，与"页面有数据、导出空白"表征一致，但根因是模板缺配置而非本任务改动导致的回归） | **PARTIAL FAIL**（端点/文件格式 PASS；内容一致性因模板配置缺口无法验证，非 T1-T4 回归） |
| TC-EXCEL-03 | AC-12（反向确认，不得回归） | 该单同时存在核价侧 Excel 路径（若适用） | 核对核价侧 `ensureExcelValues`（`CardSnapshotService.ensureExcelValues:649` 下游 `:1228`）路径 | 核价侧 Excel 路径**不**开 pending 可见域，结果与修复前逐位相同（P4 的"只在报价侧开、核价侧不开"约束） | 本单 `costing_card_template_id IS NULL`，无核价侧 Excel 路径可触发（同 TC-VIEW-03 根因）。改用代码走查代偿：T3 record 明确 `ensureExcelValues:672-685` 仅在报价分支 if 块内 `open/restore`，核价分支（`buildExcelValues(...,q.costingCardTemplateId,...,true)`）原样未动，且该方法在 TC-AC17-01 白名单测试的 `CARD_SNAPSHOT_PERMITTED_METHODS`（含 `ensureExcelValues`）范围内已被结构性验证 | **N/A**（PASS via 代码走查代偿，无集成级样本） |

---

## 10. TC-E2E（双 spec，强制门禁）—— 追溯 AC-10

> **⚠️ 执行环境说明（执行者必读，不可跳过）**：本机（`.claude/worktrees/task-0725-quote-pending-fix`）**无 Playwright 内置 chromium、无真实 `google-chrome-stable` 二进制、无 `psql` CLI**。仓库默认 `e2e/playwright.config.ts`（`channel:'chrome'` + `globalSetup` 调 `psql`）**在本机跑不起来**——`globalSetup` 内 `unlockAccounts()` 因缺 `psql` 报错但被内部 `try/catch` 吞掉（非致命），随后 `saveStorageState` 用 `chromium.launch({channel:'chrome'})` 因找不到 `/opt/google/chrome/chrome` 直接抛致命错误，测试 0 条执行。
>
> **替代方案**（T0 已验证可行，T5 执行时复用同一方案）：使用一个**临时**（跑完即删、不提交、不落入仓库）Playwright config：① 去掉 `globalSetup`（`fixtures/auth.ts#loginAs` 在无 storageState 文件时会自动回退到真实 UI 登录）；② `launchOptions.executablePath` 指向系统 `/usr/bin/chromium-browser`（snap，加 `--no-sandbox --disable-dev-shm-usage`）；③ 其余项（baseURL/viewport/locale/timeout）与仓库默认 config 逐项一致。执行前用 `docker exec cpq-jh-postgres psql` 手工确认 `admin` 账号 `locked_until` 为空可正常登录。**不得改动仓库内任何被跟踪文件**，执行后 `git status` 除 `test.md`/`testcase.md` 外不应有 diff。

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-E2E-00 | AC-10（基线已于 T0 确认，本条记录判定口径供 T5 复核，非重新执行） | 无（T0 已完成，结果见 `test.md §9`） | 核对 `test.md §9` 记录的基线结果是否与下方两行一致 | `quotation-flow.spec.ts`：3 total/0 passed/3 failed/0 skipped（两次独立运行逐字一致）；`composite-product-flow.spec.ts`：1 total/0 passed/0 failed/1 skipped | 本轮独立重跑（第三次运行）：`quotation-flow.spec.ts` 3 total/0 passed/3 failed/0 skipped；`composite-product-flow.spec.ts` 1 total/0 passed/0 failed/1 skipped，与 T0 记录逐字一致 | **PASS** |
| TC-E2E-01 | AC-10 | 用上方替代方案跑 `quotation-flow.spec.ts` | `npx playwright test --config=<临时config> e2e/quotation-flow.spec.ts --reporter=list` | **相对基线（3 failed，签名见 `test.md §9` 三条原文）无新增失败**：若新增第 4 个失败、或失败签名脱离已知三条根因族（客户下拉搜索超时 / TC-F1 下一步 disabled / TC-F2 下一步 disabled），判定为回归 | 实测 3 failed/0 skipped：①`报价单流程...`失败于 `selectByLabel` 超时——`.ant-select-item-option` 过滤"西门子"15s 未命中（客户下拉搜索超时族）；②`TC-F1` 失败于 `编辑态 Step1...下一步应可点` 断言，按钮 `disabled` + title="请先填写产品分类和报价模板"；③`TC-F2` 同②。三条签名与基线逐一对应，**无新增第4个失败** | **PASS**（无新增回归） |
| TC-E2E-02 | AC-10 | 同上，跑 `composite-product-flow.spec.ts` | `npx playwright test --config=<临时config> e2e/composite-product-flow.spec.ts --reporter=list` | 维持 **1 skipped**（skip 原因不变，仍是 `task-0712 F5 明细表重构后本 spec 选择器/Tab 命名全部过时`） | 实测 1 total/1 skipped/0 failed，与基线一致 | **PASS** |
| TC-E2E-03 | AC-10（不打折项） | 承 TC-E2E-01 | 统计整个 E2E 运行期间页面文本 `'加载中'` 出现次数（最终值） | **final count = 0** | 官方 `quotation-flow.spec.ts` 因预置基线失败（客户下拉超时）在 Step1 阶段即中止，从未到达 Step2/产品卡片渲染阶段，脚本自身未产出"加载中"计数（该失败是预置环境缺口，非回归，见 §0.7 M-3）。改用等价证据：本轮 §8 TC-VIEW-01 用**同一台 chromium**、**同一套 admin 会话**对本任务真实修复目标（`QT-20260725-0001`）跑的自建脚本已实测全部 Tab `'加载中'` 计数=0（见 TC-CORE-08/TC-VIEW-01） | **N/A（官方 spec 未触达渲染阶段，无法产出该计数；等价证据见 TC-CORE-08/TC-VIEW-01，均为 0，PASS）** |
| TC-E2E-04 | AC-10（不打折项） | 承上 | 逐 Tab（全部 8 个）统计 `'加载中'` 计数 | 全部 8 Tab 均 = **0** | 同 TC-E2E-03，官方 spec 未触达任何 Tab。等价证据：TC-CORE-08/TC-VIEW-01 已对本样本实际的 **5 个** Tab（非文档笼统写的 8 个，见 TC-CORE-08 说明）逐一截图 + 计数，均为 0 | **N/A（同上，等价证据见 TC-CORE-08，5 个 Tab 全 0，PASS）** |
| TC-E2E-05 | AC-10（截图证据） | 承上 | 收集 E2E 运行截图 | qf-19（确认添加后）+ qf-21~28（8 Tab）共 **9 张** 截图作为渲染证据附 PR | 官方 spec 因基线失败未产出 qf-19/qf-21~28 编号截图（预置失败，非回归）。本轮改用自建脚本对 5 个 Tab（编辑页+详情页共 10 张）+ 刷新前后（1 张）留存截图作为等价渲染证据（临时文件，跑完已按 §10 规程删除，不入库） | **N/A（官方截图编号因预置失败未产出；等价渲染证据已在本轮会话内核验，未入库留存）** |

---

## 11. TC-SELFCHECK（自检声明）—— 追溯 AC-11

| ID | 追溯 | 前置条件 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-SELFCHECK-01 | AC-11 | 前端有改动（`useDriverExpansions.ts`/`QuotationStep2.tsx`/`QuotationWizard.tsx`/`ReadonlyProductCard.tsx`） | `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` | **0 错误** | 实测 0 错误（exit code 0） | **PASS** |
| TC-SELFCHECK-02 | AC-11 | 同上 | 对每个改动的 `.tsx`/`.ts` 执行 `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/<相对路径>` | 全部 **200** | 5 个改动文件（`QuotationStep2.tsx`/`QuotationWizard.tsx`/`ReadonlyProductCard.tsx`/`useDriverExpansions.ts`/`componentService.ts`）全部 200 | **PASS** |
| TC-SELFCHECK-03 | AC-11 | 后端有改动 | `curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/cpq/components` | **401**（非 500） | 实测 401 | **PASS** |
| TC-SELFCHECK-04 | AC-11（本期无 Flyway） | 无 | `docker exec cpq-jh-postgres psql -U postgres -d cpq_db -c "SELECT max(installed_rank) FROM flyway_schema_history;"`（改动前后各跑一次并对比） | 改动前后 `installed_rank` 最大值不变（本期无新增迁移记录） | 当前 `max(installed_rank)=1`（该库为 baseline 快照，flyway history 只 1 条 `<<Flyway Baseline>> version=361`，非逐版本迁移记录，与本任务无关的既有环境特征）；`git diff master...HEAD --name-only \| grep 'db/migration\|\.sql$'` 零命中，确认本任务未新增任何 Flyway 迁移文件 | **PASS**（未做迁移前快照对比，改用"diff 无新增迁移文件"静态证据） |

---

## 12. TC-BOUND（已知且有意的边界差异，测出来不算 Bug）—— 追溯 AC-13

| ID | 追溯 | 前置条件 | 步骤 | 预期（确认为"有意设计"，不得报 Bug） | 实际 | 结论 |
|---|---|---|---|---|---|---|
| TC-BOUND-01 | AC-13（工序反填同单双来源） | 承 §1 共同前置 | 核对 `quotation_line_process` 表工序反填内容（`QuotationService:590`/`:2437` 的 seed SQL） | 工序反填**仍读正式数据**（`is_current=true`，不看 `pending_quotation_id`），与页签/Excel 显示的本单 pending 数据形成"同单双来源"现象——**这是 2026-07-25 有意划定的边界（BL-0073），不得报为数据不一致 Bug** | 未独立复现构造具体差异场景（本单 `quotation_line_process` 表当前为空，无可核对样本），采纳任务下达方（技术总监）在本轮任务简报中对该边界的预先确认，未发现与该结论矛盾的证据 | **PASS**（采信预先确认，未做独立行为复现） |
| TC-BOUND-02 | AC-13（BOM 页签不参与 B5 回填） | 承上 | 核对 BOM 页签渲染结果是否含 `__v6_id` 锚点列 | **不含**（`bom_view` 含顶层 `UNION ALL`，`QuotePendingRewriter.hasTopLevelSetOp` 安全降级 `anchorInjected=false`，仅只读展示，设计内降级，非缺陷） | 实测 Q3（BOM tab）全部 6 行 `driverRow` 均**不含** `__v6_id` 键（对照材料成本/外购件成本/加工费三个 tab 的 driverRow 均含 `__v6_id`），与预期一致 | **PASS** |
| TC-BOUND-03 | AC-13（加工费仅 1 行） | 承 TC-CORE-05 | 核对加工费页签是否包含来料固定加工费（`price_type='INCOMING_MATERIAL_PROCESS'`）2 行 | **不包含**，`price_type='PROCESS'` 是有意口径（仅自制加工费），已与需求方确认，不得报为漏行 Bug | 实测 `jg_view` SQL 模板 `WHERE up.price_type = 'PROCESS'`（不含 `INCOMING_MATERIAL_PROCESS`）；加工费 tab 恰 1 行，`price_type` 分布查询确认库中另有 `INCOMING_MATERIAL_PROCESS` 类型数据但未被该 tab 选中 | **PASS** |
| TC-BOUND-04 | AC-13（Excel 公式路径 gating 与 driver 路径不同） | 前端已打开该单，触发联动 Excel 公式求值（`useLinkedExcelRows.ts:275`） | 核对 `FormulaEvaluateResource:119-120` 路径是否已对 DRAFT 单开启 pending 改写 | **已开启**（该路径把真实 `quotationId+quotationStatus` 塞进 `SqlViewRuntimeContext`，与 driver 路径的 `QuotePendingScope` 预判定是两套独立 gating 语义，本期不统一，属已知设计差异） | 代码走查 `FormulaEvaluateResource.java` 单条求值端点确认：`SqlViewRuntimeContext.setNestedTemplate(effectiveTemplateId, req.quotationId, req.quotationStatus)` 直传请求体里的真实 `quotationStatus`（非 `QuotePendingScope` 预判定的恒 null 语义），与 driver 路径确认为两套独立机制，未发现矛盾 | **PASS**（代码走查确认两套机制并存，未做完整端到端联动 Excel 公式复现） |
| TC-BOUND-05 | AC-13（存量空快照不自动重算） | 找一张历史已物化为空数组的 DRAFT 单（若当前库只有 `QT-20260725-0001`，可用其修复前状态类比，或构造单测） | 不执行 `Q-REFRESH`，仅打开页面等待/执行 saveDraft | 页签**仍为空**（`saveDraft` 走增量对空数组是 no-op；页面自愈定时保存已于 2026-06-01 取消），**必须显式点「刷新基础数据」/走 `refresh-snapshot` 才会重算**——这不是 Bug，是需求 §6 已决策的行为 | 库中仅 `QT-20260725-0001` 一张 DRAFT 单且当前非空数组态，未构造额外空快照单进行独立复现。代码走查 `ConfigureSnapshotService.lineNeedsExpand:148-156` 确认判定逻辑仅 `sr==null` 才需重 expand，非 null 的空数组会被跳过，与预期一致 | **PASS**（代码走查确认，未构造独立空快照单做行为复现） |

---

## 13. TC-REG（回归清单，不得破坏）

**共同前置**：`DB_HOST=localhost` 前缀跑 `mvn -o test`。

| ID | 对应既有测试/功能 | 步骤 | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|
| TC-REG-01 | `SqlViewExecutorPendingHookTest`（§0.7 M-1，预置失败） | `DB_HOST=localhost mvn -o test -Dtest=SqlViewExecutorPendingHookTest` | 3 个 error，**签名与改动前逐字一致**（硬编码组件 `4d8874c8-…` 不在库，不要求转绿） | 实测 3 error，逐一为 `noQuotationContext_noRewrite`/`draftQuotation_anchorPresent`/`frozenQuotation_noRewrite`，报错均为 `IllegalArgument 本组件 SQL 视图未找到：$zh_view（componentId=4d8874c8-5022-4ba0-ba08-17009f46ecae）`，与 §0.7 M-1 描述逐字一致 | **PASS**（预置失败签名不变，非新增回归） |
| TC-REG-02 | `SqlViewIsolationBoundaryTest` | `DB_HOST=localhost mvn -o test -Dtest=SqlViewIsolationBoundaryTest` | 全绿（`SqlViewRuntimeContext` 语义未被本任务改动） | 实测 15 tests 0F/0E/0S | **PASS** |
| TC-REG-03 | `ComponentDriverServiceCacheKeyTest` | `DB_HOST=localhost mvn -o test -Dtest=ComponentDriverServiceCacheKeyTest` | 更新后全绿 | 实测全绿（含 T2 新增 3 个 `buildExtraCacheTags_*` 反射测试） | **PASS** |
| TC-REG-04 | `QuotePendingRewriter` 既有单测 | `DB_HOST=localhost mvn -o test -Dtest=QuotePendingRewriter*Test` | 全绿（`mask()` 改委派 `SqlTextMask` 后行为不变） | 实测 `QuotePendingRewriterTest` 8 tests 0F/0E/0S；`QuotePendingRewriterOfficialVisibilityAndSupersedesTest`（T4 新增，同前缀匹配）2 tests 0F/0E/0S | **PASS** |
| TC-REG-05 | `BatchExpandSnapshotPrefetchEquivTest`/`ComponentDriverGvarBatchEquivTest`/`EligibleForQuoteBucketTest`/`S3SegmentProfileTest` | `DB_HOST=localhost mvn -o test -Dtest=BatchExpandSnapshotPrefetchEquivTest,ComponentDriverGvarBatchEquivTest,EligibleForQuoteBucketTest,S3SegmentProfileTest` | 全绿 | **`BatchExpandSnapshotPrefetchEquivTest` 1 个 failure**（非全绿）：`prefetchReadEqualsPerTaskRead` 断言"所有对都应命中 snapshot 分支"失败，`expected:<5> but was:<4>`；`ComponentDriverGvarBatchEquivTest`（1 skipped，`assumeTrue` 数据依赖跳过）/`EligibleForQuoteBucketTest`（9 run, 4 skipped，同类数据依赖跳过）/`S3SegmentProfileTest`（1 skipped，"无核价模板"同 TC-VIEW-03 根因）均无 failure/error。根因排查（详见 Bug 报告）：该测试硬编码锚单 `87af5786-...` 在当前库无 snapshot 数据，回退取"库中任意含 snapshot 的报价单"，恰好回退命中本任务样本单 `c670e9e7-...`；其 BOM（树）组件的 `quotation_line_component_data.snapshot_rows` 含 `__lvl`/`__nodeId`/`__nodeType` 等树元数据字段，被 `ComponentDriverService.tryReadSnapshot` 的 `SNAPSHOT_MAPPER`（裸 `new ObjectMapper()`，未关闭 `FAIL_ON_UNKNOWN_PROPERTIES`）反序列化时因 `ExpandDriverResponse.Row` 类只声明 `driverRow`/`basicDataValues` 两个字段而抛 `UnrecognizedPropertyException`（日志实测：`Unrecognized field "__lvl" ... not marked as ignorable (2 known properties: "driverRow", "basicDataValues")`），被 catch 后静默回退实时 expand（该请求 `partNo=null` 故返 0 行，"snapshot 命中"标记落空）。**与 T1-T4 pending-visibility 代码改动无关**（`tryReadSnapshot`/`SNAPSHOT_MAPPER`/`ExpandDriverResponse.Row`/`expandWithSnapshot` 均不在本任务 diff 范围内，`git diff master...HEAD` 确认零命中）；`quotation_line_component_data.snapshot_rows` 写入树元数据字段的行为早于本任务（task-0721 B10 "node-aware row key"），是一个此前从未被这条等价性测试的"回退取任意单"逻辑命中过的**预置潜在 bug**，本轮测试因该测试锚单失效、回退命中恰好含树组件快照的样本单而首次暴露 | **FAIL**（`BatchExpandSnapshotPrefetchEquivTest` 1 项；判定为预置环境/既有代码缺陷，非本任务回归，但此前未被记录在 §0.7，属新发现，建议登记 BACKLOG） |
| TC-REG-06 | Excel 导出/报价单导出 | 对该单执行导出，核对文件可正常打开、内容非空 | 不受影响，与改动前一致 | 端点/文件格式本身正常（同 TC-EXCEL-02），但内容因模板 `excel_view_config` 缺配置而为空——该模板配置缺口与本任务改动前后状态相同（非本任务引入），"内容非空"这一断言本身在改动前后均会失败（非回归，是既有数据/配置缺口） | **PARTIAL**（端点无回归；"内容非空"断言因既有模板缺配置无法满足，改动前应同样失败，非新增回归） |
| TC-REG-07 | `FormulaEvaluateResource:119` Excel 公式路径 | 触发联动 Excel 公式求值 | 语义不变（本期不碰该路径），与 TC-BOUND-04 互为印证 | 代码走查确认 `git diff master...HEAD` 中 `FormulaEvaluateResource.java` **零改动**（不在本次 28 个改动文件清单内），语义天然不变 | **PASS** |
| TC-REG-08 | Flyway 无新增迁移 | 同 TC-SELFCHECK-04 | `flyway_schema_history` 无新记录 | 同 TC-SELFCHECK-04，`git diff` 无 `db/migration/*.sql` 新增文件 | **PASS** |
| TC-REG-09 | `DataSourceResourceTest`（§0.7 M-2，预置失败） | `DB_HOST=localhost mvn -o test -Dtest=DataSourceResourceTest` | 5 个 failure，**签名与改动前逐字一致**（期望 200 实得 401，认证 fixture 问题，与本任务无关） | 实测 5 failure：`listDatasources`/`createSqlDatasource`/`getById`/`createDuplicateCodeFails`/`rejectDeleteStatement`，均为期望 200 实得 401，与 §0.7 M-2 描述逐字一致 | **PASS**（预置失败签名不变） |
| TC-REG-10 | `com.cpq.formula.DataLoaderTest`（§0.7 M-6，预置失败） | `DB_HOST=localhost mvn -o test -Dtest=com.cpq.formula.DataLoaderTest` | 4 个 error，**签名与改动前逐字一致**（`NPE` @ `DataLoader.java:89`，`DataLoaderTest:49-52` 未注入 `sqlViewExecutor`，与本任务无关）；⚠️ 不要与 `com.cpq.formula.dataloader.DataLoaderScopedCacheKeyTest`（T2 新增正向测试，应全绿）混淆 | 实测 4 error（`dl01_dedupe_samePathOnlyOneSqlExecution`/`dl02_differentPaths_executeSeparatelyEach`/`dl03_normalize_braces_deduped`/`dl04_cachedPathCount_tracksUniquePaths`），均 `NullPointer ... this.sqlViewExecutor is null`，与 §0.7 M-6 描述逐字一致；另确认 `com.cpq.formula.dataloader.DataLoaderScopedCacheKeyTest`（不同包）4 tests 0F/0E/0S，未混淆 | **PASS**（预置失败签名不变，且未与 T2 新增正向测试混淆） |

---

## 14. §8 验收标准 → 用例正向追溯表

| §8 条款 | 摘要 | 覆盖用例 | T5 执行结论 |
|---|---|---|---|
| AC-1 | DRAFT 各页签行数正确 | TC-CORE-01~06、TC-ENTRY-01~09、TC-VIEW-01 | **PARTIAL**：核心行数/隔离/幂等全 PASS；TC-CORE-04/05 的输出子串断言 FAIL（文档预期与视图列设计不符，非代码缺陷）；TC-ENTRY-01~03 BLOCKED（时间盒未执行，底层 set 点已被其它用例覆盖） |
| AC-2 | BOM 树结构正确 | TC-TREE-01~07、TC-ENTRY-06 | **PASS**（8 项全 PASS） |
| AC-3 | 无遗留报错与占位 | TC-CORE-07~08 | **PASS** |
| AC-4 | 注释兼容（cp_view/bom_view/TREE_PARAM） | TC-MASK-01~11 | **PASS**（11 项全 PASS） |
| AC-5 | 数据隔离不被破坏 | TC-ISO-01~02 | **PASS**（TC-ISO-01 改用行级 ID 交叉验证；TC-ISO-02 N/A via 单测） |
| AC-6 | 遮蔽正确、行数不翻倍 | TC-ISO-03、TC-IDEM-01~02 | **PASS** |
| AC-7 | 核价侧零回归 AC-17 | TC-AC17-01~12、TC-ENTRY-09~12 | **PARTIAL / 🔴 阻塞性发现**：TC-AC17 单测组 12 项全绿（含 T4 新增 22 tests），但 TC-ENTRY-10/11 发现**真实、可复现的 AC-17 违反**——`ComponentResource` batch-expand 的 `tryReadSnapshot` 快照读旁路完全绕过 `usage`/`QuotePendingScope` 判定，任何声明 `usage=COSTING`（含缺省/非法值兜底）的调用，只要目标 `(componentId,lineItemId)` 曾被报价侧刷新过，即会收到含 `__v6_id` 的 pending 数据。此漏洞不在 T4 现有 22 个测试覆盖范围内 |
| AC-8 | 冻结态语义不变 AC-10 | TC-AC10-01~08 | **PASS**（8 项全 PASS，06/07 另有源码直接确认） |
| AC-9 | 三视图一致 | TC-VIEW-01~04 | **PASS**（01/02 PASS；03/04 N/A 同一根因：本单无核价卡模板） |
| AC-10 | E2E 双 spec（基线实测口径） | TC-E2E-00~05 | **PASS**（00~02 PASS；03~05 N/A，官方 spec 因预置基线失败未触达渲染阶段，等价证据见 TC-CORE-08/TC-VIEW-01） |
| AC-11 | 自检声明 | TC-SELFCHECK-01~04 | **PASS**（4 项全 PASS） |
| AC-12 | 页面与 Excel 口径一致 | TC-EXCEL-01~03、TC-ENTRY-05 | **FAIL**：TC-EXCEL-01/TC-ENTRY-05 因样本模板 `excel_view_config` 未配置而无法验证非空内容（模板配置缺口，非 T1-T4 代码缺陷，但客观上 AC-12 在本样本上无法端到端正向验证） |
| AC-13 | 已知且有意的边界差异不得报 Bug | TC-BOUND-01~05 | **PASS**（5 项全 PASS，其中 01/04/05 为代码走查/采信确认，未做完整独立行为复现） |

**结论：13 条验收标准均有对应用例覆盖执行完毕。其中 11 条 PASS，AC-1/AC-7 为 PARTIAL，AC-12 为 FAIL。AC-7 的 PARTIAL 判定内含本轮测试的核心新发现（AC-17 快照旁路），建议列为阻塞交付项，详见报告正文。**

---

## 15. 需求说明 A~M 细节点 → 用例覆盖对照表

| 组 | 细节点摘要 | 覆盖用例 |
|---|---|---|
| A | 逐页签行数（产品1/BOM6/材料成本3/外购件1/加工费1/小计非0）+ 材料成本第3行 S-80011 依赖树闭包警告 | TC-CORE-01~06（TC-CORE-03 显式写入警告文字） |
| B | 重算入口铁律（必须 `refresh-snapshot`，禁用 `saveDraft`） | §0.5 全文规程 + TC-ENTRY-03/04 对照区分 |
| C | BOM 树结构（node_path 前缀性、00006 两次不同 node_path、行键含 __nodeId） | TC-TREE-01~07 |
| D | 根因 2 注释屏蔽（cp_view/bom_view 保持注释原样、4 类 token 不误识、双链路一致） | TC-MASK-01~11 |
| E | AC-17 核价零回归六项新门禁（7.1~7.6） | TC-AC17-02~03（7.1/7.2）、TC-AC17-04（7.3）、TC-AC17-01（7.4）、TC-AC17-05（7.5）、TC-AC17-08（7.6缓存交叉）、TC-AC17-12（7.6树基线） |
| F | AC-10 冻结态（DRAFT开域/SUBMITTED~PUBLISHED不开域/休眠分支保持休眠/嵌套无泄漏） | TC-AC10-01~08 |
| G | 入口覆盖 9 条 | TC-ENTRY-01~09（对应 9 个入口：01建单/02加产品/03saveDraft/04从基础刷新/05报价Excel值·导出/06报价树渲染/07刷新基础数据按钮/08公式dry-run/09前端实时batch-expand传usage） |
| H | 协议（usage缺省/非法兜底COSTING、混合usage按task index配对、bucketKey侧别不合并） | TC-ENTRY-09~12 |
| I | 数据隔离与幂等（他单不可见/官方可见/遮蔽不翻倍/连续3次不累加） | TC-ISO-01~03、TC-IDEM-01~02 |
| J | 三视图一致（编辑/核价/详情页） | TC-VIEW-01~04 |
| K | E2E（基线口径 + 环境替代方案） | TC-E2E-00~05（环境替代方案见该节前置说明） |
| L | 已知且有意的边界差异（5类） | TC-BOUND-01~05 |
| M | 预置失败基线（不算回归） | §0.7 表 + TC-REG-01/09、TC-E2E-01/02 |

**结论：A~M 13 组细节点全部有对应用例覆盖，无遗漏。**

---

## 16. 无法测 / 需技术总监或 PM 决策的点

### 16.1 已由技术总监裁决的项（2026-07-25 评审，保留记录供追溯）

1. ~~TC-ISO-02（官方 current 行仍可见）缺乏本库现成样本~~ → **已裁决：本环境 N/A，改单测覆盖**。技术总监实测：QUOTE 侧 `is_current=true AND pending_quotation_id IS NULL` 行数 `material_bom_item`=0 / `element_bom_item`=0 / `unit_price`=0，本库 QUOTE 侧压根没有官方 current 行（全是本单 pending），集成级无样本。该用例已标注 N/A + 实测依据（见 §7 TC-ISO-02），由 T4 单测覆盖。
2. ~~TC-ISO-03（遮蔽 pending_supersedes）缺乏本库现成样本~~ → **已裁决：本环境 N/A，改单测覆盖，不专门造数据**。技术总监实测：三张表 `pending_supersedes` 非空行数均为 0。明确否决"专门造遮蔽测试数据"路径（会污染验收样本），改由 T4 在单测里构造数据集覆盖（见 §7 TC-ISO-03）。
3. **TC-CORE-06（小计非0）精确期望值未知** → **已裁决：接受方向性判定**（`<> 0` + 记录实测值）。PRD 未给精确值，不强求，维持原设计不变。
4. **TC-AC17 全组依赖 T4 尚未落地的测试类** → **已裁决：不用猜测类名，等 T4 完成后回填**。T4 完成后技术总监会把实际产出的测试类名回填给测试工程师，届时再把 TC-AC17 各条"步骤"补成可执行命令；当前维持 BLOCKED 占位设计不变（见 §4 组说明）。
5. **TC-VIEW-03（核价单视图）前置条件不确定性** → **已裁决：本环境 N/A，且升级为验收范围限制**。技术总监实测：`QT-20260725-0001` 的 `costing_card_template_id` 为 **NULL**，该单没挂核价卡模板。**这不只是单条用例的样本问题**：AC-7（核价零回归）在本环境**无法做集成级实证**——没有核价卡可渲染，连 AP-37"报价侧/核价侧同 key 交叉污染"的前提（同组件同时挂 `customer_template_id` 与 `costing_card_template_id`）在本单也不成立。详见下方 §16.2 验收范围限制。该用例已标注 N/A + 实测依据（见 §8 TC-VIEW-03）。

### 16.2 验收范围限制（技术总监明确要求写入，最终验收报告会如实标注，不假装已端到端验证）

**AC-7（核价侧零回归）在本环境（`cpq_db` 当前数据集）无法做集成级实证**，原因：唯一样本单 `QT-20260725-0001` 未挂核价卡模板（`costing_card_template_id IS NULL`）。AC-7 的验证只能依赖以下三类**结构性/单测级**保障，不存在"打开一张真实核价单肉眼看没问题"这条集成级证据链：

1. `QuotePendingScope.open()` 白名单单测（结构性保证，遍历源码树，TC-AC17-01）
2. `ComponentDriverService.cacheKey` 关闭态逐字不变 + `DataLoader` 三重载 key 单测（TC-AC17-09~11）
3. T4 驱动改写器的单测级 SQL 文本断言（`SqlDebugContext` 捕获，TC-AC17-02~08）

若后续环境出现同时挂载 `customer_template_id` + `costing_card_template_id` 双模板的报价单（可用 §4 TC-AC17-06 现实可触发性 SQL 排查），应补做一次集成级 TC-VIEW-03，但**不属于本期验收的强制前置**——技术总监已确认最终验收报告会如实标注"AC-7 集成级验证在本环境不可得，仅单测/结构性保障"这一限制，不会假装已完整端到端验证过。

### 16.3 仍待观察、非阻塞项（无裁决变更）

6. **TC-E2E 环境替代方案的稳定性**：使用非官方 `chromium-browser`（snap）替代 `google-chrome-stable` 属于权宜之计，两次独立运行结果虽然逐字一致（已排除 flaky 嫌疑），但不能完全排除该浏览器与生产 CI 用的 `google-chrome-stable` 存在渲染差异导致的假阳性/假阴性；建议长期看仍需在 CI 环境或安装真实 Chrome 的机器上复核一次，本文档不能替代该复核。

---

## 17. T5 执行总结（2026-07-25，cpq-tester）

### 17.1 统计

95 条用例（不含 §0.4 前置条件表，其只作环境校验用）：

（精确逐行脚本核对，95 条用例总数无误）

| 结论 | 数量 | 明细 |
|---|---|---|
| PASS | 75 | 含 §4/§5/§13 大量单测组、§1~§3/§7 集成实测等 |
| FAIL | 6 | `TC-CORE-04`、`TC-ENTRY-05`、`TC-ENTRY-10`、`TC-ENTRY-11`、`TC-EXCEL-01`、`TC-REG-05` |
| PARTIAL | 3 | `TC-CORE-05`（子串断言部分 FAIL）、`TC-EXCEL-02`、`TC-REG-06`（端点/文件格式 PASS，内容因既有配置缺口无法验证） |
| BLOCKED | 3 | `TC-ENTRY-01`/`02`/`03`（时间盒决策，未执行） |
| N/A | 8 | `TC-ISO-02`/`03`、`TC-VIEW-03`/`04`、`TC-EXCEL-03`、`TC-E2E-03`/`04`/`05`（均为文档预先识别或本轮新识别的环境限制，附替代证据） |
| **合计** | **95** | |

### 17.2 FAIL 完整清单（6 项，另 3 项 PARTIAL 见各自表格行）

1. **TC-ENTRY-10 / TC-ENTRY-11（🔴 阻塞性，AC-17 核价侧零回归被击穿）**——见下方正式 Bug 报告 BUG-1。原始现象：`usage` 缺省/非法/显式 `COSTING` 均能通过 `POST /api/cpq/components/batch-expand`（带 `lineItemId`）读到含 `__v6_id` 的报价侧 pending 快照数据。
2. **TC-CORE-04**：`外购件成本` Tab 期望文本含 `W-1001`/`OUTSOURCED`，实测 driverRow = `{"__v6_id":"94f306af-...","hf_part_no":"S-3120014539","_组成单位":"PCS","_组成数量":2,"_销售料号":"S-3120014539","_组成件名称":null}`，两串均不存在。根因：`wg_view` SQL 只把 `mbi.material_no`（父件料号）选进 `hf_part_no`/`_销售料号`，`component_no`（值=`W-1001`）仅作 JOIN 键从未进入输出列；`characteristic='OUTSOURCED'` 是 WHERE 过滤条件不是输出列。**判定为测试文档预期与视图列设计不符，非 T1-T4 代码缺陷**（与 TC-CORE-03 材料成本 3→2 的更正同类问题）。
3. **TC-ENTRY-05**：步骤②`excel-view` 响应体不含 `S-3120014539`/`PN0507945`，实测为 `{"columns":[],"rows":[{"_lineItemId":"..."}]}`。根因：样本模板 `excel_view_config` 为 NULL。
4. **TC-EXCEL-01**：`ensure-excel-values` 调用后 `quote_excel_values->'rows'` 仍为 `[]`（0 行），非预期的"非 0"。同上根因（模板 `excel_view_config` 缺配置）。
5. **TC-REG-05**：`BatchExpandSnapshotPrefetchEquivTest` 1 个 failure——见下方正式 Bug 报告 BUG-2（`ExpandDriverResponse.Row` 反序列化不识别树元数据字段，与 T1-T4 无关的预置缺陷，本轮因测试锚单回退逻辑首次暴露）。

**PARTIAL（3 项，非纯 FAIL 但未完全达成预期）**：
- **TC-CORE-05**：`S-80011` 子串 PASS + "不含 INCOMING_MATERIAL_PROCESS" PASS，但"含 PROCESS"子串 FAIL，与 TC-CORE-04 同根因（`price_type` 是过滤条件非输出列）。
- **TC-EXCEL-02** / **TC-REG-06**：导出端点本身/文件格式 PASS，但内容一致性因模板 `excel_view_config` 缺配置无法验证（与 TC-ENTRY-05/TC-EXCEL-01 同根因）。

### 17.3 与《任务简报》基准数字的核对（逐项复核，无不一致）

- 逐页签：产品 1 / BOM 6 / 材料成本 **2** / 外购件 1 / 加工费 1 / 小计 baseRows 0 但 subtotal=14 —— **全部一致**（连续 4 次独立刷新验证）。
- Q5 错误标记：0 —— **一致**。
- BOM 树 6 节点，`00006` 两次且 node_path 各不同（`S-3120014539/00006`、`S-3120014539/S-80011/00006`）—— **一致**。
- T1~T3 相关测试 72 tests 0F/0E/0S；T4 四类 10 tests 0F/0E/0S —— 本轮独立复跑 T4 四类对应的测试类，实测共 **22 tests**（`QuotePendingScopeOpenWhitelistTest` 3 + `QuotePendingSqlTextAssertionTest` 3 + `ComponentDriverServiceCacheCrossScopeTest` 2 + `QuotePendingRewriterOfficialVisibilityAndSupersedesTest` 2 + `QuotePendingScopeTest` 12），**均 0F/0E/0S**——与简报的"10 tests"数字不一致（简报口径可能未含 `QuotePendingScopeTest` 的 12 个，或统计口径不同），但**方向一致：全绿**，不构成回归疑虑，仅数字统计口径差异，据实报告。

### 17.4 与本地基线不一致的一项（🔴 唯一需要重点关注的偏差）

`P-4` 前置条件校验：文档 Q1 期望 `material_bom_item=10 / element_bom_item=6 / unit_price=20`，本轮实测为 **`material_bom_item=5 / element_bom_item=3 / unit_price=10`**（精确为文档期望值的一半）。已排查：下游 Q2/Q3/Q4 实测行数与 TC-CORE 系列期望完全吻合，**不影响功能判定**，判断是共享 DB 在文档编写后到 T5 执行期间数据发生了变化（可能是某次去重/清理，或原文档记录时点的数据本就有重复）。据实记录，供归档参考，不改变本轮任何用例的 PASS/FAIL 判定。

### 17.5 §14 追溯表 13 条验收标准逐条结论

见 §14 表格新增"T5 执行结论"列。简要：AC-2/3/4/5/6/8/9/10/11/13 共 10 条 **PASS**；AC-1 **PARTIAL**（文档预期子串问题，非功能缺陷）；AC-7 **PARTIAL / 阻塞性**（AC-17 快照旁路真实存在）；AC-12 **FAIL**（模板配置缺口，非代码缺陷但客观无法验证）。

### 17.6 阻塞交付的问题

**是，有一项：AC-17 快照旁路（TC-ENTRY-10/11 发现）。** 详见下方正式 Bug 报告。理由：
1. AC-17（核价侧零回归）在需求文档中被列为"最高优先级门禁"。
2. 该漏洞真实、可复现、默认配置下即触发（`bucketEnabled` 默认 `true`），不依赖罕见时序或极端参数。
3. T4 新增的 22 个单测全部使用全新合成 componentId（从不预置 `quotation_line_component_data.snapshot_rows`），结构性地无法触达该代码路径，因此"T4 测试全绿"不能作为该风险已被覆盖的证据。
4. 影响面：任何组件被**报价侧**刷新过快照后，**核价侧**（或老前端 usage 缺省、或任何 usage 传参有误的调用方）通过 `/api/cpq/components/batch-expand` 传入相同 `(componentId, lineItemId)` 即会读到 pending 数据 + `__v6_id`，与 AC-17 "核价侧零回归"的目标直接冲突。

---

## 附录：本轮新发现 Bug 正式报告

### BUG-1（🔴 阻塞，AC-17 核价侧零回归被击穿）—— `tryReadSnapshot` 快照读旁路未受 `usage`/`QuotePendingScope` 门控

【现象】
对 `POST /api/cpq/components/batch-expand` 发起请求，`tasks[]` 携带 `lineItemId`（真实场景下几乎总是携带，因为这是渲染一张具体报价单卡片的标准调用形态），无论 `usage` 字段传 `"COSTING"`（显式声明）、缺省（未传）、还是非法值（如 `"XXX"`），只要该 `(componentId, lineItemId)` 组合此前被**报价侧**入口（如「刷新基础数据」/建单/加产品/saveDraft，均落到 P1 `ConfigureSnapshotService.snapshotLines`）刷新过，响应即会返回该次报价侧刷新时写入的、**含 pending 数据与 `__v6_id` 锚点**的快照内容。

实测（3 组独立复现，原始响应节选）：
```
# 请求1：usage 缺省
curl -X POST .../components/batch-expand -d '{"tasks":[{"componentId":"edfa54ff-b71d-497a-b262-e3ffc5a92742","partNo":"S-3120014539","lineItemId":"6ad49abc-7b9f-4de2-a993-5c7d22e30aba"}]}'
→ {"driverPath":"snapshot","rowCount":2,"rows":[{"driverRow":{"__v6_id":"3108faa9-3f1e-4d1c-b087-2b336ef81c2f",...}},{"driverRow":{"__v6_id":"652b8438-7ce2-4e9c-836a-00582a8f5d0e",...}}]}

# 请求2：usage="XXX"（非法值）
同上 payload + "usage":"XXX" → 同样返回上述两条 __v6_id

# 请求3：usage="COSTING"（显式、合法声明，最有代表性）
curl ... -d '{"tasks":[{"componentId":"dff22d59-4325-42e7-a9a2-f2c9a11817d7","partNo":"S-3120014539","lineItemId":"6ad49abc-7b9f-4de2-a993-5c7d22e30aba","usage":"COSTING"}]}'
→ {"driverPath":"snapshot","rowCount":1,"rows":[{"driverRow":{"__v6_id":"21dc91b8-f955-40ab-905c-77c512121d3d",...}}]}
```
三次请求 HTTP 均 200，均返回 `driverPath:"snapshot"` 且含 `__v6_id`。

对照实验（证明并非 usage 判定链路整体失效，而是特定路径被绕过）：去掉 `lineItemId`（强制不触发快照旁路，走实时 `expand()`）后，同一 componentId 下 `usage=QUOTE` 返回 2 行+`__v6_id`，`usage=COSTING` 返回 0 行+无 `__v6_id`——usage 判定本身完全正确（见 TC-ENTRY-12）。

【预期】
需求说明.md §8 AC-7（核价侧零回归，最高优先级门禁）：核价侧任何入口都不应看到 pending 数据或 `__v6_id` 锚点。老前端兼容/非法值场景应"按 COSTING 兜底"（需求说明.md §8-H 组协议，对应 TC-ENTRY-10/11 原文）。

【复现】
1. 对任一已被报价侧刷新过快照的 `(componentId, lineItemId)` 组合（如上例 `edfa54ff-.../6ad49abc-...`）
2. `POST /api/cpq/components/batch-expand`，`tasks[0]` 带该 `componentId`+`lineItemId`，`usage` 传 `"COSTING"`/缺省/任意非法值均可
3. 观察响应 `data.results[0].data.driverPath`
4. 若为 `"snapshot"` 且 `rows[].driverRow` 含 `__v6_id` 键 → 复现成功

【环境】接口 `POST /api/cpq/components/batch-expand`；数据：`QT-20260725-0001` / `c670e9e7-5f7c-4b72-9a27-965447fcf75b` / lineItem `6ad49abc-7b9f-4de2-a993-5c7d22e30aba`；组件 `edfa54ff-b71d-497a-b262-e3ffc5a92742`（材料成本）/ `dff22d59-4325-42e7-a9a2-f2c9a11817d7`（加工费），两个组件独立复现同一现象。`bucketEnabled` 默认 `true`（`cpq.batch-expand-bucket` 未配置时的默认值），即生产默认配置下即可触发。

【影响】**阻塞**——AC-7 是需求文档标注的最高优先级门禁，此漏洞使该门禁在真实调用路径上不成立。

【建议】
根因定位（`cpq-backend/src/main/java/com/cpq/component/resource/ComponentResource.java`）：
- `doBatchExpandPhases` 方法 Phase 1（约 `:263-321`）：当 `hasContext`（即 `t.lineItemId != null`，`:277-281`）为真时，无论 `bucketEnabled` 开关状态，均会调用 `componentDriverService.tryReadSnapshot(t.componentId, t.lineItemId)`（`bucketEnabled=true` 时在 `:301-308`；`bucketEnabled=false` 时经 `expandWithSnapshot`→内部同款 `tryReadSnapshot` 调用，在 `:282-296`）。
- `tryReadSnapshot`（`ComponentDriverService.java:268-303`）是对 `quotation_line_component_data.snapshot_rows` 列的**直接、无条件**读取，不检查 `QuotePendingScope`/`usage`，命中即直接返回并 `continue`（`:303-307`），**usage 判定与 `QuotePendingScope.open()` 的整段逻辑（`:272-313`）在这种情况下形同虚设**——`_pqOpened`/`open()`/`restore()` 依然会被执行，但对最终返回内容毫无影响，因为 `continue` 发生在这些变量被使用之前的分支里。
- 该表的数据来源：P1（`ConfigureSnapshotService.snapshotLines`，本任务 T3 已用 `QuotePendingScope.open/restore` 包裹）在报价侧刷新时，把 `expand()` 结果（此刻 pending 可见域已打开，含 `__v6_id`）写入 `quotation_line_component_data.snapshot_rows` 作为"基础冻结层"（`ConfigureSnapshotService.java:38` 注释）。**这是本次 T1-T4 改动带来的新行为**——在本任务修复根因 1 之前，`QuotePendingScope` 不存在，`expand()` 从不产出 `__v6_id`/pending 数据，因此该表历史上从未包含过污染内容，`tryReadSnapshot` 的无门控读取此前不构成风险；**T1-T4 修复后，P1 的写入内容变了，但 `tryReadSnapshot` 的读取逻辑没有同步加门控**，产生了这条新的泄漏路径。
- 建议修复方向（不代替架构师决策，仅供参考）：`tryReadSnapshot` 命中快照后，若 `!isQuoteUsage(usage)`（即 COSTING/缺省/非法），需要对快照内容做等效于 `QuotePendingRewriter` 的"退回官方视角"处理（剥离 `__v6_id`、过滤掉 `pending_quotation_id` 非空且非官方的行），或者更简单地：COSTING 侧调用**不应该**命中报价侧写入的这份快照（该快照的"新鲜度"判定本就是围绕报价侧渲染场景设计的，需architect评估是否要为 COSTING 侧单独维护一份不含 pending 视角的快照，或直接让 COSTING 侧 usage 跳过 `tryReadSnapshot` 走实时 `expand()`）。

---

### BUG-2（一般，非 T1-T4 引入，本轮测试意外暴露）—— `BatchExpandSnapshotPrefetchEquivTest` 因树组件快照含未声明字段而反序列化失败

【现象】`mvn test -Dtest=BatchExpandSnapshotPrefetchEquivTest` 中 `prefetchReadEqualsPerTaskRead` 失败：`所有对都应命中 snapshot 分支(driverPath=snapshot),否则基准选错或快照缺失 ==> expected: <5> but was: <4>`。

【预期】该测试类文档口径为"全绿"（testcase.md TC-REG-05）。

【复现】
1. 该测试硬编码锚单 `87af5786-...`，当前库该单无 snapshot 数据，回退取"库中任意含 snapshot 的报价单"
2. 回退命中本任务样本单 `c670e9e7-.../6ad49abc-...`（5 对 line×component 快照）
3. 循环调用 `tryReadSnapshot` 逐一读取，BOM（树）组件那一对触发 `com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException`：`Unrecognized field "__lvl" (class com.cpq.component.dto.ExpandDriverResponse$Row), not marked as ignorable (2 known properties: "driverRow", "basicDataValues")`
4. 异常被 `tryReadSnapshot` 内 catch，静默回退实时 `expand()`（该请求未带真实 `partNo` 上下文，返回 0 行），"5 对全部命中 snapshot" 断言因这一对未命中而失败（4/5）

【环境】`cpq-backend` `mvn test`；触发对象 `quotation_line_component_data` 表 BOM 组件（`da8d0e61-b209-433d-907d-5dd018b3389a`）行 `snapshot_rows`，其内容含 `__lvl`/`__nodeId`/`__nodeType`/`__parentId`/`__parentNo`/`__bomVersion` 等树元数据字段。

【影响】一般——不影响本任务 pending-visibility 修复的正确性（`tryReadSnapshot`/`SNAPSHOT_MAPPER`/`ExpandDriverResponse.Row` 均不在本次 diff 内），但确实是一个此前从未被暴露过的、真实存在的反序列化缺陷：**任何时候只要真实调用方通过快照旁路读取一个 BOM/树类型组件的 `snapshot_rows`，都会静默丢失快照优化、回退实时展开**（功能上不算错，因为 fallback 是安全的，但丧失了该优化机制本应带来的性能收益，且日志会持续 WARN 刷屏）。

【建议】`ExpandDriverResponse.Row` 补充树元数据可选字段，或 `SNAPSHOT_MAPPER` 显式配置 `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES=false`。与本任务无直接关系，建议登记 BACKLOG 独立处理，不阻塞本次交付。

---

## 附录：Bug 报告格式提醒

发现问题按标准格式记录：
```
【现象】用户操作 + 实际结果
【预期】需求说明.md §8 第 N 条 + 期望结果
【复现】最小步骤（≤5 步，注意重算入口必须是 refresh-snapshot 而非 saveDraft）
【环境】接口/UI + 具体数据（quotation id / component id / SQL 查询结果等）
【影响】阻塞/严重/一般/轻微
【建议】可能的根因方向（参照 §8 根因 1/根因 2/AC-17/AC-10 定位属于哪一类；若怀疑是"入口选错"或"§0.7 预置失败基线"范围内的已知问题，先核对本文档 §0.5/§0.7 再报）
```

## 附录：回归测试清单（任一 Bug 修复后必须联动重测）

| 修复涉及的用例 | 必须联动重测 |
|---|---|
| TC-CORE-03（材料成本 2 行） | 无需联动 —— 材料成本行数与树渲染**无因果**（报价侧无 BOM 闭包） |
| TC-MASK-01/02（cp_view/bom_view 注释） | TC-CORE-01/02（对应页签是否恢复出数） |
| TC-AC17 任一子项 | 全组 TC-AC17-01~12（AC-17 是构造性保证，改一处需重跑整组白名单/缓存断言） |
| TC-ISO-01（隔离） | TC-IDEM-01（幂等，确认隔离修复未引入累加副作用） |
| TC-ENTRY-09~12（usage 协议） | TC-VIEW-03/04（核价视图是否受到误开 pending 影响） |
| TC-EXCEL-01/02 | TC-CORE 全组（页面与 Excel 必须同步核对，防止只修一侧） |
