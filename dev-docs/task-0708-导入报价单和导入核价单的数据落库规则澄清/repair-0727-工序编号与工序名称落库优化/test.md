# 测试任务书 —— 工序编号与工序名称落库优化

> 上游：`需求文档.md`（第四轮定稿）/ `backtask.md`
> 库：`cpq_db_0724`（`10.177.152.12:5432`）　客户：罗克韦尔 `CUST-1269`
> 数据：`docs/table/报价测试数据/v2/报价系统模板0723.xlsx`（「组装加工费」sheet 含 `焊接`/`铆接` 两行）

---

## 0. 本次测试的特殊性（先读，否则会做错）

### 0.1 报价导入是**整单原子**的，没有"部分成功"

`recordError` ≡ 整单失败。所以本次**不要**去验证「坏行被跳过、好行落库」——那是前三轮文档的错误描述，第四轮已废止（`需求文档.md` §13.1/§13.2）。

正确的失败断言是：**整单 `FAILED` + 全库零变动**。

### 0.2 改动会让"未录工序主数据时报价导入整体不可用"

这是**设计选择**，不是 bug。测试时若发现「不导工序主数据 → 整份 Excel 导不进去」，那是 AC-3 的**预期结果**，不要报 bug。

### 0.3 验收造的工序测试数据**不要删**

`process_master` 当前 0 行。你造的测试工序数据要**保留在 `cpq_db_0724`**（需求方 N7 决定），使代码合并到 master 后开发库立刻可用，不连坐其他并发会话的导入测试。

### 0.4 E2E 夹具在本库大面积失效（BL-0078）

`cpq_db_0724` 只有 8 张报价单（`QT-20260726-0001~0008`）。**凡硬编码旧 `quotationId` 的 spec 在本库都进不了编辑页**（`quotation-bom-tree.spec.ts` 等）。AC-7 的渲染验证请用**当前库里真实存在的报价单**，或走 UI 手工确认，**不要**用失效 spec 的红叉当作回归证据。

### 0.5 归因纪律

报 bug 前必须先做 **A/B 对照**：同一套操作在 `master`（不含本次改动）上跑一遍。若两边同样失败 → 是 pre-existing，登记 BACKLOG，**不算本次回归**。历史上两类误判代价对称：把 pre-existing 当回归会浪费返工，把真回归当噪声会放过缺陷。

另：**确认你测的是最新代码**。改完后端必须 `touch` 一个 java 文件让 Quarkus dev 重启并等 5-7 秒，否则可能在测半成品（历史假阳性来源）。

---

## 1. 测试前置

### P1 造工序主数据

构造工序主数据 Excel（sheet 名 `工序`，列头见 `ProcessMasterImportService`）：

| 工序编号 | 工序名称 | 工序类别 | 是否外协 | 标准币种 | 标准单位 | 默认不良率 |
|---|---|---|---|---|---|---|
| Z100 | 焊接 | 组装 | 否 | CNY | PCS | 0.01 |
| Z101 | 铆接 | 组装 | 否 | CNY | PCS | 0.01 |

经「主数据维护 → 工序 → 导入工序」录入。**验收后保留，不清理**（见 §0.3）。

### P2 记录基线

导入任何数据前，先存一份各表计数基线（AC-5 要用）：

```sql
SELECT 'capacity' t, count(*) FROM capacity WHERE resource_group_no='QUOTE_ASSEMBLY'
UNION ALL SELECT 'material_bom_item', count(*) FROM material_bom_item
UNION ALL SELECT 'unit_price', count(*) FROM unit_price
UNION ALL SELECT 'material_master', count(*) FROM material_master
UNION ALL SELECT 'element_bom_item', count(*) FROM element_bom_item;
```

### P3 确认双 current 起点

```sql
SELECT count(*) FROM unit_price WHERE price_type='COMPONENT_REDUCTION';
-- 期望：0（技术总监 2026-07-27 实测值）。若非 0，先向技术总监报告再继续（涉及 R6 老组清理）
```

---

## 2. 测试用例

### AC-1 正向 · 按名称落真编号

1. 确保 `process_master` 含 `Z100=焊接`、`Z101=铆接`
2. 导入 `报价系统模板0723.xlsx`（「组装工序」列为 `焊接`/`铆接`）
3. 断言：

```sql
SELECT process_no, process_name FROM capacity
 WHERE resource_group_no='QUOTE_ASSEMBLY' AND is_current ORDER BY process_no;
-- 期望：Z100|焊接、Z101|铆接
```
4. 导入报告：`failedRows = 0`，导入记录状态 `SUCCESS`

> ❌ 失败样例（改动前的现状）：`焊接|(null)`、`铆接|(null)`

### AC-2 正向 · 按编号落真编号（两段匹配第一段）

1. 复制一份 Excel，把「组装工序」列改填 `Z100` / `Z101`
2. 导入
3. 断言：结果与 AC-1 **完全一致**（`Z100|焊接`、`Z101|铆接`）——名称取自主数据规范名，不是 Excel 原文

### AC-3 反向 · 工序未登记 → 整单失败

1. 清空 `process_master`（或改用一份含未登记工序的 Excel，如把某行改成 `点胶`）
2. 导入
3. 断言：
   - 导入记录状态 **`FAILED`**
   - `failedRows > 0`，错误文案**同时包含**：工序名（如 `点胶`）+ 「请先在 主数据维护 → 工序 中录入或导入」
   - 错误**按料号聚合**：同一销售料号下多道工序失败时只报一条，文案里列出全部失败工序名
   - `capacity` 无新增 `QUOTE_ASSEMBLY` 行

### AC-4 同名多条 · 取升序第一条 + 日志告警

1. 主数据造两条同名：`Z100=焊接`、`Z205=焊接`
2. 导入
3. 断言：
   - 导入**成功**（不是失败 —— 第四轮 N3 已把「拒绝」改为「取第一条」）
   - `capacity.process_no = 'Z100'`（`process_no` 升序最小），**不是** `Z205`
   - **后端日志**出现 `WARN`，内容含被选中的编号与**全部候选**（`Z100` / `Z205`）
4. 清理：删掉 `Z205=焊接` 这条，恢复单条状态

> 日志查看：Quarkus dev 控制台，或 `grep` 后端日志文件。**日志是本用例唯一的告警渠道**（界面上看不到，这是需求方 N5 的决定，不是缺陷）。

### AC-5 整单原子性 ⭐

在 AC-3 场景下（导入失败），重跑 P2 的计数 SQL：

- 断言：**每一张表的计数与 P2 基线逐字节一致**
- 特别确认 `material_bom_item` / `unit_price` / `material_master` 这些**其他 sheet** 的表也**没有任何新增** —— 证明整单回滚生效

> 这条替代了前三轮文档的「其余 sheet 照常落库」（已废止，与架构冲突）。

### AC-6 Q15 组装加工费年降

1. 在 Excel 的「组装加工费年降」sheet 造数据，「组装工序」列填 `焊接`
2. 导入
3. 断言：`unit_price` 中 `price_type='COMPONENT_REDUCTION'` 的 `operation_no` = `Z100`（真编号，不是 `焊接`）
4. 反向：把该列改成未登记工序 → 整单 `FAILED`
5. 边界：把该列**留空** → 应正常导入，`operation_no` 落 `NULL`（不报错 —— 该列本就允许为空）

### AC-6b Q15 双 current ⭐

导入后执行：

```sql
SELECT code, finished_material_no, operation_no, count(*)
  FROM unit_price
 WHERE price_type='COMPONENT_REDUCTION' AND is_current
 GROUP BY 1,2,3 HAVING count(*) > 1;
-- 期望：0 行
```

再执行一次完整导入（同一份 Excel），**重复上述查询仍须 0 行**。

> 机理：`operation_no` 在 Q15 的 groupKey 里，改值会建新组而不切老组。当前 `COMPONENT_REDUCTION` = 0 行故无老组，但必须实测锁死。

### AC-7 渲染 ⭐（人工确认）

1. 打开一张**当前库里真实存在**的报价单（`QT-20260726-0001~0008` 之一，需含组装加工费页签），或用 AC-1 导入后新建的报价单
2. 断言：
   - 组装页签「工序」列显示 **`焊接`**（工序名称），**不是** `Z100`
   - 自制加工费页签的工序列**不受影响**（回归）
3. 截图留证（组装页签 + 自制加工费页签各一张）

> 改 `zz_view` 后必须 `touch` java 文件重启 Quarkus 再验（进程级 SQL/列缓存）。

### AC-8 版本行为

1. AC-1 完成后记录 `capacity` 的 `calc_version`
2. **再导一次同一份 Excel**
3. 断言：
   - 首次重导（改动上线后第一次）：`calc_version` **升一版**，老版本 `is_current = false`（`process_no` 从 `焊接` 变 `Z100` 触发 `VERSION_TRIGGER`）—— **这是预期，不是 bug**
   - 第二次重导：`calc_version` **不再增长**（内容稳定）
4. 补充：改主数据里的工序名称（如 `焊接` → `焊接工序`）后重导 → `process_name` 原地更新，`calc_version` **不变**（`process_name` 是内容列不是触发列）

### AC-9 回归

```bash
cd cpq-backend && ./mvnw test -Dtest='*Quote*,*Capacity*,*Process*'
```

- 断言：全绿
- 核价侧不受影响：

```sql
SELECT DISTINCT process_no FROM capacity WHERE system_type='PRICING' OR resource_group_no='PRICING_DEFAULT';
-- 期望：仍是 Z008 / Z053 / Z490 等真编号，未被本次改动污染
```

### AC-10 文档

`docs/table/报价系统Excel导入落库方案.md` §14 / §15 已按新规则纠正（含两段匹配、Phase 1 拦截、`process_name` 落库）。

### AC-11 性能

1. 记录改动前后同一份 Excel 的导入端到端耗时（后端日志 `[v6import] QUOTE TOTAL elapsed=...ms`）
2. 断言：**增幅 < 5%**
3. 确认 Phase 1 未引入逐行查库：`process_master` 全表只 `listAll()` 一次

---

## 3. 交付要求

### 3.1 测试报告必含

| 项 | 要求 |
|---|---|
| 逐条 AC 结论 | PASS / FAIL，**FAIL 必须附实际输出**（SQL 结果、报错文本、日志片段），不许只写"不通过" |
| SQL 证据 | AC-1 / AC-3 / AC-5 / AC-6b / AC-8 / AC-9 的原始查询输出（贴完整结果，不是"符合预期"四个字） |
| 截图 | AC-7 组装页签 + 自制加工费页签各一张 |
| 日志片段 | AC-4 的 `WARN` 原文 |
| 性能数据 | AC-11 改动前后两个 `elapsed` 数值 |
| A/B 归因 | 任何 FAIL 都要附 master 上的同型对照结果 |

### 3.2 禁止事项

- ❌ 不许把「未导工序主数据时整单失败」报成 bug（那是 AC-3 预期）
- ❌ 不许用 BL-0078 的失效 E2E spec 的红叉当回归证据
- ❌ 不许在没重启 Quarkus 的情况下下结论（可能在测半成品）
- ❌ 不许清理 P1 造的工序测试数据
- ❌ 不许只写"已测试通过"而不附证据 —— **无证据的 PASS 视为未测**

### 3.3 发现 pre-existing 缺陷时

不要顺手改。做完 A/B 归因确认是 pre-existing 后，写进报告的「附带发现」章节，由技术总监决定是否登记 BACKLOG。
