# test · repair-0807 更正任务价格丢失与版本错乱

- 对应需求文档：`./需求文档.md`（AC-1a/1b/2~AC-18 / FR-1~FR-7 / D-1~D-9，验收唯一标准）
- 对应开发文档：`./backtask.md`（T0~T7）/ `./fronttask.md`（F1~F3）/ `./api.md`
- 分支：`fix/repair-0807-price-update-loss`
- 角色：`cpq-tester`
- 本文档状态：**仅设计用例，未执行**（后端/前端开发并行进行中）。"实际结果"列全部留空，执行阶段由测试工程师逐条回填并产出 `test-report.md`。

---

## 0. 说明

### 0.1 环境

| 项 | 值 |
|---|---|
| 库 | `10.177.152.12:5432/cpq_db_0724`（dev），凭据 `postgres/joii5231` |
| 后端 | `http://localhost:8081`（worktree 复用主工作区共享 dev server；`V384` 验证需 copy 到主仓触发，详见 backtask T0） |
| 前端 | `http://localhost:5174` |
| 鉴权 | `/price-adjust/reviews*`、`/price-adjust/jobs*` 端点要求 `PRICING_MANAGER` 或 `SYSTEM_ADMIN` 角色会话 |

#### 0.1.1 已知环境坑位（执行阶段必读，主线 2026-08-07 补）

1. **后端自测（`./mvnw test`）不要加 `-Dquarkus.flyway.validate-on-migrate=false`**。共享 test 库（`cpq_db`）里存在两个"已应用但 master 上没有"的迁移：`V382`（`task-0806` 未合并分支遗留）与 `V384`（本次 repair 的）。正确做法是把这两个 `.sql` 作为**未追踪副本**放进本 worktree 的 `cpq-backend/src/main/resources/db/migration/`，让 Flyway `validate` 自然通过——关掉校验会把"我的迁移有没有问题"这个信号一起关掉，不允许图省事这么做。副本已在 `/home/joii/project/cpq-repair-0807` 里就位，**执行阶段不要 `git add` 这两个文件**（它们是本地未追踪的兼容性副本，不属于本次改动范围）。
2. **`QuotePendingScopeOpenWhitelistTest.openCallSites_fileLevelWhitelist_exactMatch` 是已知恒红，非本次引入**。主线已做 A/B 实测（干净 master 与本修复分支上该方法名逐字一致地失败），根因是 `QuotationService.java:1631` 的**注释文本**里含 `QuotePendingScope.open(` 字样，被朴素字符串扫描误命中——与 repair-0807 无关，已登记 `BL-0155`。执行时看到它红，**如实记录为已知基线失败**即可，不得计入本次缺陷清单，也不得为了让它变绿去改动代码。
   - 主线 A/B 实测数据（可直接引用进 `test-report.md` 回归结论段）：
     ```
     B 干净 master              : Tests run 64, Failures 1, Errors 0, Skipped 0
     A fix/repair-0807-...      : Tests run 82, Failures 1, Errors 0, Skipped 0
     失败集合逐条一致；新增 18 个测试全绿
     ```

### 0.2 测试数据构造总原则

按主线要求，**不复用现网单据**（`QT-20260807-0127`/`0128`/`0140` 等，用户仍在用、随时可能被删）。所有用例自建测试单据，但复用已验证的**客户/料号/组件/版本链**这组静态配置：

| 项 | 值 |
|---|---|
| 客户 | `CUST-0002` |
| 料号 | `3120011203` |
| 价格承载组件 | `4a193e48-5ce0-4a6a-a36c-60aafadd9a56`（「材料成本」） |
| 三角色字段 | `elementCodeField=元素` / `elementPriceField=元素单价` / `elementCurrencyField=货币` |
| 元素 | `Ag`，版本链 `V26080701`（3000→2500）→ `V26080702`（2500→3000） |
| 数值基准 | `Ag=2500` 时产品小计 `18.00`；`Ag=3000` 时 `18.587137`；价格丢失（`Ag` 计 0）时 `15.109316` |

### 0.3 共享前置数据 SOP（D-1，供 A 组多条用例复用）

1. 确认 `material_price_version_ref` 当前指针：`SELECT version_id FROM material_price_version_ref WHERE customer_no='CUST-0002' AND material_no='3120011203'` 应指向 `V26080701`（`Ag=2500`）。若不是，先用管理端点/直接 SQL 复位到该起点，并记录复位前状态（不得污染其他并发测试）。
2. 用**真实"新增产品"流程**（非手工拼 `snapshot_rows`）为 `CUST-0002` 新建一张 `DRAFT` 报价单，产品行用料号 `3120011203`，保存草稿。
3. 断言起点：`SELECT li.subtotal FROM quotation_line_item li WHERE ... ` = `18.00`（±0.01，对齐 §0.2 基准），且 `quotation_line_component_data.snapshot_rows` 中 `Ag` 行 `元素单价=2500.000000`、`__priceLocked=true`、`__priceVersion='V26080701'`。
4. 记为该单 **`QID-D1`**，作为 A 组用例的公共起点。**每条用例各自复制一份新单**（避免用例间相互污染同一行 `row_version`），复制方式：对 `QID-D1` 执行 `POST /api/cpq/quotations/{id}/copy`，得到独立的 `QID-Dx`。

### 0.4 命名与优先级

- 用例编号 `TC-<组><序号>`，如 `TC-A1`。
- 优先级：P0（阻断，必须全过才可交付）/ P1（重要，缺陷需登记但不阻断）/ P2（增强，可延后）。
- 每条用例含：**对应 AC/FR**、**前置数据**、**步骤**、**期望结果**（可判定精确值/精确断言）、**实际结果**（留空）、**优先级**。

---

## 1. AC 覆盖对照表

| AC | 断言摘要 | 覆盖用例 |
|---|---|---|
| AC-1a | 不做保存动作，`snapshot_rows.driverRow`（driver 行）单价=目标版本价 且 `__priceVersion`=目标版本号 | TC-A1 |
| AC-1b | 同上单据，`row_data` 里 `_origin='manual'` 手动行同样带 `__priceLocked=true`+`__priceVersion`=目标版本号（FR-1 明文要求 S3a 与 S3b 都写，验收不许只查 `snapshot_rows`） | TC-B3 |
| AC-2 | `row_data` 该元素行存在单价+货币两键且=目标版本价/货币 | TC-A2 |
| AC-3 | `quote_card_values.resolvedRows` 单价非空；`li.subtotal` 与打开编辑页保存一次后逐字节相等 | TC-A3 |
| AC-4 | 详情页只读渲染目标版本价+`CNY`；产品小计=18.587137 | TC-A4 |
| AC-5 | 元素∈清单但本版无价 → 价格键+两个锁标记一并删除，前端可编辑；**三个写点（S3a driver行 / S3b手动行 / S4b非手动行）落点必须完全一致**（D-8 手动行、D-9 driver行） | TC-B1（S4b非手动行）、TC-B2（S3b手动行，D-8）、TC-B7（S3a driver行，D-9）、TC-B5（`Cd` 从无历史价对照） |
| AC-6 | 无 `quotation_view_structure` 的活单 → 自愈补建4份结构+价格更新（`row_version` **严格大于**升版前值，不约束增量为1），item=SUCCESS | TC-C1 |
| AC-7 | 补建后仍无价格承载组件 → SKIPPED，`errorMessage`区分两态，批次 `skipped_count≥1` 且 `PARTIAL` | TC-C2、TC-C3 |
| AC-8 | 屏6a `SKIPPED` 金色标签+原因+无重试按钮 | TC-C4 |
| AC-9 | 判断依据行显示具体值(≈18.59)，其余行「未试算」 | TC-D1、TC-D1b（真实健康路径对照） |
| AC-10 | 财务自检行出现，X 与合计对单价影响相等（差≤0.01） | TC-D2 |
| AC-11 | `Ag` 用量=0.001159(±1e-6)、影响=0.587137(±0.01)、合计=0.587137（不再是18.59） | TC-D3 |
| AC-12 | 多元素版本：Σ明细 vs 合计差≤0.01；若>0.01则WARN日志+页面仍取整体Δ | TC-D4（线性一致）、TC-D5（非线性触发WARN） |
| AC-13 | `detail()` 对 `quotation`/`quotation_line_item` **各发 SQL ≤2条**，且5→25活单数不变；**两门槛独立**——条数不随N变但绝对值>2仍判不通过（`≤2`是CLAUDE.md N+1硬规原文口径，优先级高于"不变"） | TC-E1 |
| AC-14 | `V384` success=t；`chk_mpuji_status`允许`SKIPPED`；`skipped_count`默认0 | TC-F1 |
| AC-15 | `#42`/`#29` 按新口径重跑；`#47` 升版侧同样成立 | TC-G1（#29）、TC-G2（#42）、TC-G3（#47升版侧） |
| AC-16 | `quotation-flow.spec.ts` 通过，`'加载中' final count=0` | TC-G4 |
| AC-17 | **幂等**：同一张单连续两次同目标版本升版（第二次走 `retryJobItem`），`snapshot_rows`/`row_data`/`quote_card_values`/`li.subtotal` 四者逐字节相等 | TC-B6 |
| AC-18 | **`SKIPPED` 不被批次重跑吞掉**：`POST /jobs/{jobId}/retry` 不重新执行 `SKIPPED` 项，状态/`updated_at`不变，批次仍 `PARTIAL`、`skipped_count`不变 | TC-C5 |

**FR 专项补充覆盖**（AC 未逐条覆盖但 FR 有明文要求，见 §4 歧义说明）：FR-2 元素∉清单不碰（TC-B4）、FR-6 `usageQty`除零守卫（TC-D6）、FR-7 `impact()`批量化（TC-E2）、AP-52字面同源（TC-J1）、MAPPER精度（TC-J2）。异常/错误码（TC-H1~H3）、并发（TC-I1）、前端专项（TC-K1~K4）单列于对应分组，不在此表重复。

---

## 2. 组 A · 核心三层数据一致性（不做保存动作，AC-1~AC-4）

> 🔒 **本组所有用例的公共约束**：job 跑完之后**禁止**出现任何 `POST /draft`、`PUT quote-card-edit`、`GET /api/cpq/quotations/{id}` 编辑页数据接口调用——一旦触发过 `saveDraft` → `PriceReconciler` 归位，就测不出"当场即终态"这个核心诉求，只会测出归位机制本身（那是已经验证过的旧能力）。断言前只允许直接 `psql` 查库。

### TC-A1　driverRow 单价与版本徽标当场同版（AC-1）

| 字段 | 内容 |
|---|---|
| 对应 | AC-1 / FR-1（S3a） |
| 前置 | 复用 §0.3 D-1，复制得 `QID-A1` |
| 优先级 | P0 |

**步骤**
1. 记录升版前 `QID-A1` 的 `Ag` 行 `snapshot_rows.driverRow`：`元素单价=2500.000000`、`__priceVersion='V26080701'`。
2. `POST /api/cpq/price-adjust/reviews/approve` 通过料号 `3120011203`（目标版本 `V26080702`），`GET /jobs/{id}` 轮询至终态。
3. **不做任何保存/打开动作**，直接 `SELECT snapshot_rows FROM quotation_line_component_data WHERE line_item_id=<QID-A1的行id> AND component_id='4a193e48-5ce0-4a6a-a36c-60aafadd9a56'`。

**期望结果**
- `driverRow.元素单价 = 3000.000000`（精确值，`V26080702` 的新价）。
- `driverRow.__priceVersion = 'V26080702'`（与单价**同版**，不再一新一旧）。
- `driverRow.__priceLocked = true`。

**实际结果**：（留空）

---

### TC-A2　row_data 价格键存在且非缺键（AC-2）

| 字段 | 内容 |
|---|---|
| 对应 | AC-2 / FR-2（S4b） |
| 前置 | 复用 §0.3 D-1，复制得 `QID-A2` |
| 优先级 | P0 |

**步骤**
1. 升版前查 `QID-A2` 的 `row_data`，确认 `Ag` 行含 `元素单价`、`货币` 两键（正常态）。
2. 同 TC-A1 步骤2 执行升版。
3. 不做保存/打开，直接 `SELECT row_data FROM quotation_line_component_data WHERE ...`，取该元素行的 JSON。

**期望结果**
- 该行 **存在** `元素单价` 键，值 `= 3000.000000`。
- 该行 **存在** `货币` 键，值 `= 'CNY'`（或版本明细实际货币值，需与 `element_price_version_item.currency` 一致）。
- **反例断言**：不再出现"该元素行少了这两个键"的缺键结构（根因B的原始症状）。

**实际结果**：（留空）

---

### TC-A3　quote_card_values 单价非空 + li.subtotal 逐字节等于归位后值（AC-3）

| 字段 | 内容 |
|---|---|
| 对应 | AC-3 |
| 前置 | 复用 §0.3 D-1，复制得两份：`QID-A3a`（不打开）、`QID-A3b`（升版后打开编辑页保存一次） |
| 优先级 | P0 |

**步骤**
1. 对 `QID-A3a`、`QID-A3b` 两张单**同时**执行升版（同一个 job 批次内，同料号）。
2. `QID-A3a`：升版后不做任何操作，直接 `SELECT quote_card_values, subtotal FROM quotation_line_item WHERE id=<QID-A3a行id>`。
3. `QID-A3b`：升版后打开编辑页一次并保存（触发 `saveDraft` → `PriceReconciler`），保存完成后再 `SELECT quote_card_values, subtotal FROM quotation_line_item WHERE id=<QID-A3b行id>`。
4. 定位 `quote_card_values` 中 `componentId='4a193e48-...'` 的 tab 节点，取其 `resolvedRows` 数组里 `元素='Ag'` 的行（根节点是数组还是以 componentId 为键的对象，需先 `jsonb_pretty` 观察实际结构再定位，不同版本快照实现可能不同）。

**期望结果**
- `QID-A3a`（未保存）：`resolvedRows` 中 `Ag` 行 `元素单价` **非空**，值 `= 3000.000000`。
- `QID-A3a.subtotal` 与 `QID-A3b.subtotal`（打开保存后）**逐字节相等**（`= 18.587137`，精确到 6 位小数无偏差）——这是"当场即终态，不依赖后续保存自愈"的核心断言。
- **反例**：若 `QID-A3a.subtotal` 明显偏低（如 `15.109316` 附近）则说明该行仍处于"元素单价为空导致小计塌陷"的缺陷态，用例判失败。

**实际结果**：（留空）

---

### TC-A4　详情页只读渲染 + 产品小计终值（AC-4）

| 字段 | 内容 |
|---|---|
| 对应 | AC-4 |
| 前置 | 复用 §0.3 D-1，复制得 `QID-A4` |
| 优先级 | P0 |
| 层级 | SQL 断言 + UI/API 联合断言（`GET /api/cpq/quotations/{id}` 详情接口返回体 + 前端只读渲染） |

**步骤**
1. 对 `QID-A4` 执行升版（同 TC-A1）。
2. **仅此一次** `GET /api/cpq/quotations/{id}` 获取只读详情数据（该接口本身不算"保存"，但获取后不得再触发 `PUT`/`POST` 写操作）。
3. 打开该单详情页（`ReadonlyProductCard`），定位「材料成本」组件的 `Ag` 行。

**期望结果**
- 详情接口响应中该行 `元素单价 = 3000.000000`，`货币 = 'CNY'`（不是 `null`/`—`）。
- 前端渲染：单价列**只读文本节点**（非 `<input>`）+ 版本徽标，徽标文本含 `V26080702`。
- 产品小计（`quotation_line_item.subtotal` 或页面显示的行小计）**= 18.587137**（精确到 6 位小数）。

**实际结果**：（留空）

---

## 3. 组 B · 写入侧边界值（AC-5 死格 + 手动/非手动行 + 元素范围）

### TC-B1　无价元素死格修复：非手动行（S4b，AC-5 主场景）

| 字段 | 内容 |
|---|---|
| 对应 | AC-5 / FR-2（S4b 第三分支） |
| 前置 | 见下 |
| 优先级 | P0 |

**前置数据构造**（比照父任务 `#47` 手法，直接 SQL 构造，理由同款：机制生效后 UI 已锁只读，无法用界面产生"元素∈清单但无价"这种存量态）：
1. 在目标版本 `V26080702` 里新增一个元素 `Zn` 的版本明细，`current_price=NULL`、`no_price=true`、`inherited_from_previous=false`：
   ```sql
   INSERT INTO element_price_version_item (id, version_id, element_code, current_price, previous_price, no_price, inherited_from_previous, currency)
   VALUES (gen_random_uuid(), '<V26080702的id>', 'Zn', NULL, 24.17, true, false, 'CNY');
   ```
2. 确认客户元素清单 `customer_price_adjust_element` 含 `Zn`（`CUST-0002`），若不含先补一条。
3. 造一张单 `QID-B1`，其「材料成本」组件 `row_data` 里存在一个**非手动**（`_origin` 不等于 `'manual'`，即驱动行 autosave 快照）的 `Zn` 行，且该行当前带有陈旧手改单价（如 `24.17`）与 `__priceLocked=true`/`__priceVersion='V26080701'`（模拟"上一版曾锁过价"的存量态）。

**步骤**
1. 升版前确认该行：`元素单价=24.17`、`__priceLocked=true`、`__priceVersion='V26080701'`。
2. 对 `QID-B1` 执行升版（目标 `V26080702`）。
3. 查询升版后该行 `row_data`。

**期望结果**
- 🔒 **价格键+货币键被删除**：`row_data` 该行**不存在** `元素单价`/`货币` 键（不是置 `null`，是键本身消失，与解析层"取不到值→undefined"语义一致）。
- 🔒 **锁标记同时被删除**：`__priceLocked`/`__priceVersion` **一并消失**（不是只删值留锁 = 死格）。
- 前端该格：`__priceLocked` 缺失 → 渲染为可编辑 `<input>`（需前端联调验证，本用例后端部分独立成立）。

**实际结果**：（留空）

---

### TC-B2　无价元素在手动行（S3b）上必须与非手动行同口径删值撤锁（D-8 已裁决）

| 字段 | 内容 |
|---|---|
| 对应 | AC-5 / D-8 / FR-2（三分支表，手动行=非手动行同口径） |
| 前置 | 见下 |
| 优先级 | **P0**（D-8 已裁决为确定性断言，非探测） |

**前置数据构造**：同 TC-B1，但改为**手动行**（`_origin='manual'`）：造一张单 `QID-B2`，「材料成本」组件 `row_data` 里存在一个 `_origin='manual'` 的 `Zn` 手动行，当前带陈旧手改单价 `24.17` + `__priceLocked=true`/`__priceVersion='V26080701'`。

**步骤**：同 TC-B1，对 `QID-B2` 执行升版。

**期望结果**
- D-8 裁定判据：`PriceReconciler.reconcileRows` 的 `row_data` 循环根本不区分 manual/non-manual，本 repair 的目标就是把这条口径原样补到升版侧。落点为**解读甲**：
- 🔒 `Zn` 手动行的**价格键 + 货币键 + `__priceLocked` + `__priceVersion` 四者全部删除**（与 TC-B1 非手动行断言完全一致，两类行不允许出现任何差异）。
- 🚨 若实现结果是"该行原样不动"（陈旧手改值 `24.17` + 陈旧锁标记 `V26080701` 继续保留）——即前面提到的解读乙——**直接判 FAIL**，不再作为"待裁决的探测性发现"处理，登记为缺陷退回开发。

**实际结果**：（留空）

---

### TC-B3　手动行也要写锁标记（FR-1 S3b，AC-1b）

| 字段 | 内容 |
|---|---|
| 对应 | AC-1b / FR-1（S3b） |
| 前置 | 造一张单 `QID-B3`，「材料成本」组件含一个手动行（`_origin='manual'`），元素 `Ag`，升版前无 `__priceLocked`/`__priceVersion`（模拟机制生效前的手动行原始态） |
| 优先级 | P0（AC-1b 正式验收标准） |

**步骤**
1. 升版前确认该手动行：`元素单价`为手填初始值、无 `__priceLocked`/`__priceVersion` 键。
2. 对 `QID-B3` 执行升版（目标 `V26080702`，`Ag` 新价 `3000`）。
3. 查询升版后该行 `row_data`。

**期望结果**
- 该手动行 `元素单价 = 3000.000000`。
- 该手动行 **新增** `__priceLocked = true`、`__priceVersion = 'V26080702'`（S3b 同样写标记，不再是"只有 driver 行才锁"）。

**实际结果**：（留空）

---

### TC-B4　元素∉清单：一个字节都不碰（FR-2 沿用现状分支的回归确认）

| 字段 | 内容 |
|---|---|
| 对应 | FR-2 第一分支 |
| 前置 | 造一张单 `QID-B4`，「材料成本」组件含一行元素 `301`（非有效元素编码，等价"不在清单"，复用父任务 `#35` 已验证样本手法） |
| 优先级 | P1 |

**步骤**
1. 记录该行完整 JSON 的 `md5`（`driverRow` 与 `row_data` 对应条目分别计算）。
2. 对 `QID-B4` 执行升版。
3. 重新计算 `md5` 对比。

**期望结果**
- 升版前后该行 **`md5` 完全相同**（整行一个字节都不变，包括不新增/不删除任何键）。

**实际结果**：（留空）

---

### TC-B5　从无历史价元素（对照组，`#47` 场景②在升版侧的独立验证）

| 字段 | 内容 |
|---|---|
| 对应 | AC-5 边界 / AC-15（`#47` 升版侧） |
| 前置 | 元素 `Cd`：从未在任何版本里有价，`element_price_version_item.no_price=true`、`inherited_from_previous=false`、`current_price=NULL`；造一张单 `QID-B5` 含 `Cd` 非手动行，升版前**无** `__priceLocked` |
| 优先级 | P1 |

**步骤**：对 `QID-B5` 执行升版（目标版本含 `Cd` 明细，但 `current_price=NULL`）。

**期望结果**
- 升版后该行 `元素单价`/`__priceLocked` 键**依旧不存在**（本就没有过，不因升版而凭空出现锁标记）。
- 前端该格：可编辑（销售能填进去，视同不在清单）。

**实际结果**：（留空）

---

### TC-B6　幂等：连续两次同目标版本升版，四份存储逐字节相等（AC-17）

| 字段 | 内容 |
|---|---|
| 对应 | AC-17（父任务 `#34`「两次升版结果漂移」同族防线；FR-2 由删键改覆盖后本应更强，须有证据） |
| 前置 | 复用 §0.3 D-1，复制得 `QID-B6` |
| 优先级 | P0 |

**步骤**
1. 对 `QID-B6` 执行第一次升版（目标 `V26080702`），job item 终态 `SUCCESS`。
2. 第一次升版完成后，**不做任何保存/编辑动作**，立即采集四份存储的完整内容：
   - `snapshot_rows`（`quotation_line_component_data`）
   - `row_data`（同表）
   - `quote_card_values`（`quotation_line_item`）
   - `li.subtotal`
   分别记录原始文本/md5（`snapshot_rows`/`row_data`/`quote_card_values` 用 `md5(col::text)`，`subtotal` 直接记数值）。
3. 对**同一个 job item** 调用 `POST /api/cpq/price-adjust/job-items/{itemId}/retry`（同目标版本 `V26080702`，第二次执行），等待终态。
4. 重新采集同一批四份存储的内容/md5，与步骤2逐一比对。

**期望结果**
- 🔒 `snapshot_rows` md5：第一次 = 第二次（逐字节相等，不因重复升版而产生"再覆盖一次导致字面漂移"，如 `171.368000` vs `171.368` 这类同构写点隐患，见 §6.3 精度硬约束）。
- 🔒 `row_data` md5：第一次 = 第二次。
- 🔒 `quote_card_values` md5：第一次 = 第二次（业务字段幂等；若实现里有 `updated_at` 之类元信息导致 md5 不同，需在报告里区分"业务字段幂等"与"元信息字段不幂等"，核心业务字段幂等是硬性要求，参照父任务 `#45` 的既有口径）。
- 🔒 `li.subtotal`：第一次 = 第二次（精确到 6 位小数）。
- 第二次 `retryJobItem` 响应本身应正常返回（非 409），`job_item.status` 仍为 `SUCCESS`。

**实际结果**：（留空）

---

### TC-B7　driver 行无价 → 四键全删且徽标不得被刷新（AC-5 的 driver 行侧，D-9）

| 字段 | 内容 |
|---|---|
| 对应 | AC-5 / D-9（S3a，与 TC-B1/S4b、TC-B2/S3b 合起来覆盖三个写点的完整对称性） |
| 前置 | 见下 |
| 优先级 | **P0** |
| 背景 | 主线代码评审 2026-08-07 发现的缺陷（已退回后端修，本用例是回归验证位）：首版把 `__priceLocked`/`__priceVersion` 写在 `if (ep.price != null)` **之外**，导致「元素∈本版明细但本版无价」时 driver 行价格键保留旧值、却被打上本次新版本徽标——"旧价穿新版徽标"，是根因A的镜像。且前端判据 `driverRow.__priceLocked ?? rawRow.__priceLocked` **driverRow 短路优先**，driver 行不撤锁会把 S4b 在 `row_data` 侧的撤锁整个抵消，导致 AC-5「前端该格可编辑」在 driver 行上根本不成立。 |

**前置数据构造**：造一张单 `QID-B7`，「材料成本」的某个 **driver 行**（非手动行，走 `snapshot_rows.driverRow`）元素 ∈ 本版明细但该元素本版 `current_price IS NULL`（`noPrice` 元素，如复用 TC-B1 的 `Zn` 或另建一个）；升版前该行带**旧价**（如上一版价 `2500.000000`）+ `__priceLocked=true` + `__priceVersion='V26080701'`。

**步骤**
1. 确认升版前 `driverRow`：价格键 = 旧价、`__priceLocked=true`、`__priceVersion='V26080701'`。
2. 对 `QID-B7` 执行升版（目标 `V26080702`）。
3. **不做任何保存动作**，直接 `SELECT snapshot_rows FROM quotation_line_component_data WHERE line_item_id=<QID-B7行id> AND component_id='4a193e48-...'`，取该元素的 `driverRow`。

**期望结果**
1. 🔒 `driverRow` 的 **价格键、货币键、`__priceLocked`、`__priceVersion` 四者全部不存在**（与 TC-B1/TC-B2 断言的键集合完全一致，三写点对称）。
2. 🚨 **专项反例断言（本条最容易漏测的一步）**：即使实现有缺陷只删对了锁标记，也要单独验证 —— `driverRow.__priceVersion` **不得等于** `'V26080702'`（新版本号）。光断言"锁没了"测不出"值被刷成新版徽标"这种更隐蔽的半吊子实现；这条必须独立于第1条存在，不能被第1条"键不存在"覆盖掉（若键还在但值被错误刷新，第1条会失败，但反过来"键已被删除"不代表"没有别的代码路径把版本号写回别处"，两条都要断言）。
3. 🔒 该行价格键**不得残留上一版旧价**（`2500.000000`）——防止实现走了"半吊子修复"：只把锁标记摘了，但价格键既没删也没刷新，旧价还在，此时前端会显示一个"可编辑但预填了旧价"的格子，同样是错误状态。
4. 前端该格：`__priceLocked` 缺失 → 渲染为可编辑 `<input>`（与 TC-B1 前端断言同款，需前端联调验证，本用例后端部分独立成立）。

**实际结果**：（留空）

---

## 4. 组 C · 缺冻结结构自愈与 SKIPPED 终态（AC-6~AC-8）

### TC-C1　缺 quotation_view_structure 的活单自愈补建（AC-6）

| 字段 | 内容 |
|---|---|
| 对应 | AC-6 / FR-3 |
| 前置 | 见下 |
| 优先级 | P0 |

**前置数据构造**：
1. 用正常"新增产品"流程为 `CUST-0002` 建一张 `DRAFT` 单 `QID-C1`，料号 `3120011203`，保存草稿（此时正常应生成4份 `quotation_view_structure`）。
2. `DELETE FROM quotation_view_structure WHERE quotation_id='<QID-C1>'`，人为制造"复制单未保存过"的等效缺失态（不追究 BL-0150 的真实触发路径，只测 FR-3 自愈本身，理由见 §4 歧义3）。
3. 确认 `row_version`：`SELECT row_version FROM quotation_line_component_data WHERE line_item_id=<QID-C1行id> AND component_id='4a193e48-...'` 记为 `RV0`。

**步骤**
1. `SELECT count(*) FROM quotation_view_structure WHERE quotation_id='<QID-C1>'` → 应为 `0`（前置确认）。
2. 对 `QID-C1` 执行升版。
3. 升版后重新查询 `quotation_view_structure` 计数、`row_version`、`Ag` 单价。

**期望结果**
- `quotation_view_structure` 记录数 `= 4`（`QUOTE_CARD`/`QUOTE_EXCEL`/`COSTING_CARD`/`COSTING_EXCEL` 各一份，已自愈补建）。
- `row_version` **严格大于** `RV0`（价格确实被更新过；一个 line item 可能命中多个价格承载组件、各发一次 `UPDATE`，故不约束增量恰好为 `+1`——AC-6 已采纳此口径）。
- `Ag` 单价 `= 3000.000000`。
- `SELECT status FROM material_price_update_job_item WHERE quotation_id='<QID-C1>' AND material_no='3120011203'` `= 'SUCCESS'`（不是 `SKIPPED`）。

**实际结果**：（留空）

---

### TC-C2　补建后仍无价格承载组件 → SKIPPED（AC-7 情形①）

| 字段 | 内容 |
|---|---|
| 对应 | AC-7 / FR-3 第二分支 |
| 前置 | 见下 |
| 优先级 | P0 |

**前置数据构造**（构造成本较高，见 §4 歧义5）：
1. 准备一个**非价格承载**组件（`component.element_code_field`/`element_price_field` 均为空/NULL）——若现有模板系列没有现成的、专为本用例新建一个测试用组件（复用组件管理正常建组件流程，不接三角色字段）。
2. 用该组件所在模板新建一个测试专用料号（如 `M-SKIPPED-TEST`），纳入 `CUST-0002` 的 `customer_price_adjust_material` 调价范围，元素清单含 `Ag`（保证 job 会真正处理这条料号）。
3. 建一张单 `QID-C2`，产品行 = `M-SKIPPED-TEST`，保存草稿生成正常4份结构。
4. `DELETE FROM quotation_view_structure WHERE quotation_id='<QID-C2>'`（制造缺失态，同 TC-C1）。

**步骤**
1. 对 `QID-C2` 执行升版。
2. 查询 `material_price_update_job_item`、`quotation_view_structure`。

**期望结果**
- `quotation_view_structure` 记录数 `= 4`（FR-3 第一步"先补建"确实执行了）。
- 但补建后仍定位不到价格承载组件 → `item.status = 'SKIPPED'`（不是 `SUCCESS`、不是 `FAILED`）。
- `item.errorMessage = '冻结结构已补建，但仍无接价格策略的组件（三角色字段未配齐），无可升版内容'`（与 backtask T3 文案逐字一致）。
- `item.errorCode` 为 `NULL`（api.md §2.2：SKIPPED 不占用 errorCode）。
- 批次：`SELECT status, skipped_count FROM material_price_update_job WHERE id=<job_id>`：若该批次同时还有其它成功项，`status='PARTIAL'`；若该批次只此一项，`status` 亦不应为 `SUCCESS`（对照 §3.2 判定表：`success==0 && skipped==0` 才是 `FAILED`，此处 `skipped>0` 故落 `PARTIAL`）。`skipped_count ≥ 1`。

**实际结果**：（留空）

---

### TC-C3　补建失败 → SKIPPED（AC-7 情形②，两态区分）

| 字段 | 内容 |
|---|---|
| 对应 | AC-7 / FR-3 第一分支（`rebuilt=false`） |
| 前置 | 需要 `ensureStructure` 抛异常的可控构造，见下 |
| 优先级 | P1（若无法可靠构造异常，允许降级为代码走查，见 §4 歧义6） |

**前置数据构造思路**：`ensureStructure` 失败通常源于模板/客户配置缺失或数据不一致（如 `customer_template_id` 指向一个已被删除/损坏的模板）。构造：建一张单后，直接 `UPDATE quotation SET customer_template_id = gen_random_uuid()` 指向一个不存在的模板 ID，再清空其 `quotation_view_structure`，触发升版。

**步骤**：同 TC-C2，对该单执行升版。

**期望结果**
- `item.status = 'SKIPPED'`。
- `item.errorMessage = '冻结结构缺失且补建失败，无法定位价格承载组件'`（与 TC-C2 的文案**不同**，验证 backtask T3 "两态区分"的要求）。

**实际结果**：（留空）

**⚠️ 降级路径**：若上述构造未能稳定触发 `ensureStructure` 抛异常（该方法内部可能有更宽松的兜底吸收掉这个错误），本用例允许改为**代码走查**：确认 `upgrade()` 中 `catch (Exception e)` 分支与 `rebuilt=false` → `message` 赋值逻辑存在且与 `rebuilt=true` 分支的 message 不同（对照 backtask T3 代码块），作为等效证据记入报告，并如实注明"真实异常路径未覆盖，以走查证据替代"。

---

### TC-C4　屏6a SKIPPED 行渲染（AC-8）

| 字段 | 内容 |
|---|---|
| 对应 | AC-8 / F2-1、F2-2 |
| 前置 | 复用 TC-C2 产生的 job（含至少一条 `SKIPPED` item） |
| 优先级 | P0 |
| 层级 | 前端 UI（Playwright 或手测截图） |

**步骤**
1. 打开「更新执行进度」抽屉（`JobProgressDrawer`），定位该 `SKIPPED` 行。
2. 检查标签颜色、文案、操作列。

**期望结果**
- 状态标签：`color='gold'`，文案「已跳过」（不是灰色 `default`，不是绿色）。
- 原因列显示后端 `errorMessage` 原文（TC-C2 的文案），**前端未重写文案**。
- 该行**不出现**「重试」按钮（`FAILED`/`CONFLICT` 才有）。
- 汇总区出现「已跳过 N」（N≥1）；`job.status='PARTIAL'` 时 `Progress` 组件 `status='normal'`（不是 `'exception'`）。
- 🔧 **契约字段名核对**：前端渲染该数字取自 `GET /jobs/{jobId}` 响应体的 `skipped` 字段（**不是** `skippedCount`——api.md 2026-08-07 已更正，wire 上 5 个兄弟字段 `total`/`success`/`failed`/`conflict`/`stale` 均无 `Count` 后缀，`skipped` 同款风格；`skippedCount` 是实体侧字段名，不出现在 JSON 里）。若前端类型/取值仍写成 `job.skippedCount`，判契约不一致，登记缺陷。

**实际结果**：（留空）

---

### TC-C5　`SKIPPED` 不被批次重跑吞掉（AC-18）

| 字段 | 内容 |
|---|---|
| 对应 | AC-18 |
| 前置 | 复用 TC-C2 产生的 job（含至少一条 `SKIPPED` item，同批次内应还有其它 `SUCCESS` 项以体现 `PARTIAL`） |
| 优先级 | P1 |

**步骤**
1. 记录该 job 当前汇总：`status`（应为 `PARTIAL`）、`skipped`（wire字段，≥1）、该 `SKIPPED` item 的 `status`/`updated_at`。
2. `POST /api/cpq/price-adjust/jobs/{jobId}/retry`（批次级重试，非单项重试），等待处理完成。
3. 重新查询该 job 汇总与该 `SKIPPED` item 的 `status`/`updated_at`。

**期望结果**
- 🔒 该 `SKIPPED` item **未被重新执行**：`status` 仍为 `SKIPPED`，`updated_at` **与步骤1完全相同**（未发生任何写操作——判据是 `loadWaitingItems` 只捞 `status in (WAITING, CONFLICT)`，`SKIPPED` 天然被过滤掉）。
- 批次汇总：`status` 仍为 `PARTIAL`（不因批次重试而变成 `SUCCESS`）。
- `skipped` 字段值**不变**（重试前后相同）。
- 若批次内还有其它 `WAITING`/`CONFLICT` 项，那些项应正常被本次 `retry` 处理（本用例只断言 `SKIPPED` 项被排除在外，不断言其它项的处理结果）。

**实际结果**：（留空）

---

## 5. 组 D · 审核抽屉调整后小计与元素影响（AC-9~AC-12）

### TC-D1　三态渲染：判断依据行有值，其余「未试算」（AC-9）

| 字段 | 内容 |
|---|---|
| 对应 | AC-9 / FR-5 / F3-1 |
| 前置 | 一个版本至少涉及 2 张活单（1 张为判断依据单），复用 §0.3 D-1 场景生成的版本、另建第二张单 `QID-D1b`（同料号、非判断依据） |
| 优先级 | P0 |

**步骤**
1. `GET /api/cpq/price-adjust/reviews/{reviewId}`（该料号审核记录）。
2. 打开审核抽屉「三、下钻」表。

**期望结果**
- 响应体 `quotations` 数组：`isBasis=true` 的那一行 `adjustedComputed=true`，`quoteSubtotalAdjusted` 为具体数值（约 `18.587137`，允许与真实构造的行数据存在合理偏差，但必须是**非 null 的数字**）。
- 其余（`isBasis=false`）行：`adjustedComputed=false`，`quoteSubtotalAdjusted=null`。
- 前端渲染：判断依据行显示具体数字；其余行显示「未试算」文案（`Tooltip` 提示"仅对判断依据单试算，其余单据仅作参考"），**不是** `—`。

**实际结果**：（留空）

---

### TC-D1b　健康路径对照：真实 review 恰好一行 `isBasis=true` 且试算成功（AC-9 健康态佐证）

| 字段 | 内容 |
|---|---|
| 对应 | AC-9（与 TC-H1 的悬空 basis 场景对照，证明"降级为留白"逻辑没有把健康路径一起带偏） |
| 前置 | 复用现网真实样本 `reviewId=b95ad65d-...`（basis = `QT-20260806-0122`，`DRAFT`，已确认在下钻列表内）——**只读，不改该 review 任何字段** |
| 优先级 | P1 |

**步骤**：`GET /api/cpq/price-adjust/reviews/b95ad65d-...`。

**期望结果**
- 响应体 `quotations` 数组中**恰好一行** `isBasis=true`。
- 该行 `adjustedComputed=true` 且 `quoteSubtotalAdjusted` **非 null**（具体数值以该单真实数据为准，不强求等于 §0.2 的 `18.587137` 基准，因为这是另一张现网单）。
- 其余行 `isBasis=false`，`adjustedComputed=false`，`quoteSubtotalAdjusted=null`。

**实际结果**：（留空）

---

### TC-D2　财务自检行出现且数值对齐（AC-10）

| 字段 | 内容 |
|---|---|
| 对应 | AC-10 / F3-2 |
| 前置 | 同 TC-D1 |
| 优先级 | P1 |

**步骤**：打开审核抽屉，检查「财务自检：调整后报价 − 现报价 = X，对得上 ✓」是否渲染，并与「合计对单价影响」比对。

**期望结果**
- 该行**出现**（此前因 `quoteSubtotalAdjusted` 恒 `null` 从未渲染过，是本次修复的直接验证点）。
- `X = quoteSubtotalAdjusted − quoteSubtotalCurrent`（判断依据行），与响应体 `elementImpactTotal` 差 `≤ 0.01`。

**实际结果**：（留空）

---

### TC-D3　单元素明细：用量与影响精确值（AC-11）

| 字段 | 内容 |
|---|---|
| 对应 | AC-11 / FR-6 |
| 前置 | 同 TC-D1（单元素版本，仅 `Ag`） |
| 优先级 | P0 |

**步骤**：`GET /reviews/{reviewId}`，取 `elementChanges` 数组中 `elementCode='Ag'` 的条目。

**期望结果**
- `usageQty = 0.001159`（±1e-6）。
- `unitPriceImpact = 0.587137`（±0.01）。
- `elementImpactTotal = 0.587137`（±0.01，**不再是** `18.59` —— 这是根因分析里明确指出的口径错误，必须断言"不等于旧错误值"这一反例）。

**实际结果**：（留空）

---

### TC-D4　多元素版本：Σ明细=合计（线性场景，AC-12 正例）

| 字段 | 内容 |
|---|---|
| 对应 | AC-12 / D-5 / D-6 |
| 前置 | 构造一个同时含 `Ag`+`Cu` 两元素变价的版本，材料成本组件里两元素各自独立求和参与小计（无阶梯/条件公式），料号需另建（如复用 `#31` 已验证的父子件结构，或新构造一个含2元素 driver 行的简单料号） |
| 优先级 | P1 |

**步骤**：对该多元素版本触发 `detail()`，取 `elementChanges` 全部条目与 `elementImpactTotal`。

**期望结果**
- `Σ(elementChanges[].unitPriceImpact)` 与 `elementImpactTotal` 差 `≤ 0.01`。
- 页面「合计对单价影响」取的是 `elementImpactTotal`（整体 Δ），不是 Σ 明细（数值上两者接近但取值来源不同，需通过日志/走查确认取值路径是 `elementImpactTotal` 字段本身）。

**实际结果**：（留空）

---

### TC-D5　多元素版本：Σ明细≠合计（非线性场景，触发 WARN，AC-12 反例）

| 字段 | 内容 |
|---|---|
| 对应 | AC-12 / D-6 |
| 前置 | 构造一个价格对元素存在**非线性关系**的场景（如公式含阶梯定价 `IF(元素单价>2000, ..., ...)` 或存在其它元素间耦合的条件公式），至少2元素同时变价 |
| 优先级 | P2（构造成本高，若现网/测试模板无此类非线性公式样本，允许降级为走查 D-5/D-6 代码分支存在性 + 单测覆盖，见 §4 歧义7） |

**步骤**：触发 `detail()`，观察后端日志。

**期望结果**
- `Σ明细` 与 `elementImpactTotal` 差 `> 0.01`。
- 后端日志出现 `[price-adjust-review] review=... 元素影响明细合计 ... 与整体 Δ ... 不等（卡片对价格非线性）` 的 `WARN` 记录。
- 页面「合计对单价影响」数值**仍取** `elementImpactTotal`（不因不等而改用 Σ明细，不阻断、不改数）。

**实际结果**：（留空）

---

### TC-D6　usageQty 除零/空值守卫（FR-6 边界）

| 字段 | 内容 |
|---|---|
| 对应 | FR-6（`usageQty` 计算） |
| 前置 | 构造 `currentPrice = previousPrice`（涨跌幅为0，理论上不该进入调价流程，但需验证分母为0时的兜底） 或 `previousPrice=null`（该元素首次有价） |
| 优先级 | P1 |

**步骤**：触发 `detail()`，检查该元素的 `usageQty`。

**期望结果**
- `usageQty = null`（前端显示 `—`）。
- 🚨 **不得返回 `0`**（`0` 会被误读成"用量为0"这个错误的业务含义，而不是"算不出来"）。

**实际结果**：（留空）

---

## 6. 组 E · 性能与 N+1（AC-13）

### TC-E1　`detail()` N+1 批量化 + SQL 条数与活单数无关（AC-13）

| 字段 | 内容 |
|---|---|
| 对应 | AC-13 / FR-7 |
| 前置 | 构造两组：一组料号涉及 5 张活单，另一组同料号（或另一料号）涉及 25 张活单 | 
| 优先级 | P0 |

**步骤**
1. 开启 SQL 日志 / `[perf]` 日志采集（后端已按 backtask T6 要求打印 `[perf] review-detail N=<活单数> sql=<条数>`）。
2. 分别对 5 活单料号、25 活单料号触发 `GET /reviews/{reviewId}`。
3. 对比日志中的 `sql=` 值。

**期望结果**
- 两次日志 `sql=` 数值**相同**（与 N 无关，硬指标"每张表最多2条SQL"）。
- `quotation`/`quotation_line_item` 各自 SQL 条数 `≤ 2`。
- 人工代码走查确认 `detail()`/`impact()` 循环体内无 `findById` 逐条查库（对照 backtask T6 代码块）。

**实际结果**：（留空）

---

### TC-E2　`impact()` 批量化（FR-7 附带覆盖，非 AC 直接列出但 backtask T6 明确要求）

| 字段 | 内容 |
|---|---|
| 对应 | FR-7 |
| 前置 | 构造多个 review（≥3 个不同料号的待审核记录） |
| 优先级 | P1 |

**步骤**：`POST /reviews/impact` 传入多个 `reviewIds`，观察 SQL 条数（不随 reviewIds 数量线性增长）。

**期望结果**：所有 review 的 `findAllActiveLines` 结果先汇总再一次批量查，SQL 条数为常数，不随 `reviewIds.size()` 增长。

**实际结果**：（留空）

---

### TC-E3　`detail()` 耗时基准（单元素 ≤3s / 三元素 ≤8s）

| 字段 | 内容 |
|---|---|
| 对应 | §4 性能约定 / api.md §1.4 |
| 前置 | TC-D3（单元素）、TC-D4/D5（三元素或以上，构造3元素版本专测本条） |
| 优先级 | P1 |

**步骤**：`curl -w '%{time_total}'` 或前端 Network 面板计时，分别测单元素版本、三元素版本的 `GET /reviews/{reviewId}` 耗时。

**期望结果**
- 单元素版本：耗时 `≤ 3s`。
- 三元素版本：耗时 `≤ 8s`。
- 超出：**如实记录实测值，不得砍逐元素试算凑指标**（backtask §4 明文），提请裁决。

**实际结果**：（留空）

---

## 7. 组 F · Flyway 迁移（AC-14）

### TC-F1　V384 迁移生效性

| 字段 | 内容 |
|---|---|
| 对应 | AC-14 |
| 优先级 | P0 |

**步骤 + 期望结果（逐条 SQL 断言）**
1. `SELECT version, success FROM flyway_schema_history WHERE version='384'` → `success = t`。
2. `SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='chk_mpuji_status'` → 定义中包含 `'SKIPPED'`。
3. `SELECT column_name, column_default FROM information_schema.columns WHERE table_name='material_price_update_job' AND column_name='skipped_count'` → 存在，`column_default` 含 `0`。
4. 插入一条 `status='SKIPPED'` 的 `material_price_update_job_item` 测试行（事务内插入后回滚，不留痕）验证约束确实放行。

**实际结果**：（留空）

---

## 8. 组 G · 回归（AC-15 / AC-16）

### TC-G1　`#29` 新口径重跑：手工值按列清除（升版=覆盖，不再是删键）

| 字段 | 内容 |
|---|---|
| 对应 | AC-15（`#29`） |
| 前置 | 复刻父任务 `#29` 前置：料号 A 某行手改「元素单价」与「毛重」两字段，直接 SQL 构造存量手改态 |
| 优先级 | P0 |
| ⚠️ 口径变更提示 | 原 `#29` 期望"单价被删除的键"或类似语义已由本次 FR-2 改为"覆盖为本版价"——**这是刻意的口径变更，不是回归失败**，报告里需明确标注"新口径通过 ≠ 旧口径失败" |

**步骤**：同父任务 `#29`（见 `dev-docs/task-0729-.../testcases.md:786-808`），造手改值 `元素单价=9999`、`毛重=88.8`，通过审核升版。

**期望结果（按新口径）**
- 🔒 单价字段 **= 本版价**（`3000.000000`，不是 `9999`）——手改值被覆盖清除，且是"写新值"而非"删键"（`row_data` 该键**依然存在**，只是值变了，区别于 TC-B1 的"删键"场景——两者的分野是"元素∈明细且解出价=覆盖" vs "元素∈明细但解不出价=删除"）。
- 🔒 毛重字段 **仍 = `88.8`**（未被误伤，与父任务原断言一致，未变）。

**实际结果**：（留空）

---

### TC-G2　`#42` 新口径重跑：单价列只读态（E2E，无需"打开保存"前置）

| 字段 | 内容 |
|---|---|
| 对应 | AC-15（`#42`） |
| 前置 | 复刻父任务 `#42` 全部前置场景（driver行/手动行/清单外行/指针为空料号），见 `testcases.md:1141-1167` |
| 优先级 | P0 |
| 层级 | E2E(Playwright) |
| ⚠️ 口径变更提示 | 父任务原用例可能隐含"先走一遍归位（如保存）才能看到正确徽标"的执行顺序；本次修复后**升版当场即正确**，无需额外保存步骤即可复测原 4 条断言 |

**步骤 + 期望结果**：逐字复用父任务 `#42` 的 4 条断言（driver 行只读文本+徽标 / 手动行同样只读 / 清单外行可编辑 / 详情页同步显示徽标 / 指针为空仍只读），**唯一变化**是本次执行升版后**不再需要**"打开编辑页保存一次"这个中间步骤，直接进入断言即应全部成立。

**实际结果**：（留空）

---

### TC-G3　`#47` 升版侧同样成立（区别于原归位侧验证）

| 字段 | 内容 |
|---|---|
| 对应 | AC-15（`#47`），与 TC-B1/TC-B5 同源但角度不同：本条专测"走 `upgrade()` 升版链路"而非"走 `PriceReconciler` 归位链路" |
| 前置 | 复用 TC-B1（`Ni`/`Zn` 型：本期无价有上一版价）+ TC-B5（`Cd` 型：从无历史价） |
| 优先级 | P0 |

**期望结果**：与父任务 `#47` 原断言逐条一致（`no_price`/`inherited_from_previous` 标记正确、有上一版价的元素只读非空、从无历史价的元素可编辑），**且**该料号照常升版完成（`material_price_review.status` 正常走完，不因某元素无价而卡住整体流程）。

**实际结果**：（留空）

---

### TC-G4　`quotation-flow.spec.ts` E2E 回归（AC-16）

| 字段 | 内容 |
|---|---|
| 对应 | AC-16 |
| 优先级 | P0 |
| 触发原因 | 本次改了 `CardSnapshotService.ensureStructure` 的调用时机（FR-3）与卡片值内容（FR-2），按 CLAUDE.md「修改后强制自检」第5条需跑一次协议级回归 |

**步骤**
```bash
cd cpq-frontend
Remove-Item e2e\screenshots\qf-*.png -ErrorAction SilentlyContinue   # 或等价 rm -f
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```

**期望结果**
- 所有 test `passed`。
- 日志出现 `'加载中' final count = 0`。
- 全部 Tab（现为 8 个）`'加载中'=0`。
- 参照 `cpq-e2e-quotation-flow-test-data` 历史记忆：干净 master 下该 spec 可能有已知的 3 处夹具漂移失败（与产品分类相关，非本次改动引入）——若出现，需 A/B 对照本分支 vs 干净 master 是否同样失败，同型失败判定为"非回归"，如实记录不得掩盖。

**实际结果**：（留空）

---

## 9. 组 H · 异常与错误码

### TC-H1　判断依据单缺失/被删 → 200 + 全部行 `adjustedComputed=false`（不报错）

| 字段 | 内容 |
|---|---|
| 对应 | api.md §1.3 / §3 边界场景 |
| 前置 | **现网真实悬空样本，无需构造**（见下） |
| 优先级 | P1 |

**🔒 只读，不改这两条 review 的任何字段——用户还在用这个库。**

**前置数据（2026-08-07 主线已在 dev 库 `cpq_db_0724` 查实，直接复用）**：`material_price_review.basis_quotation_id` **无 FK 约束**（表上只有 `version_id`/`previous_version_id` 两个 FK），报价单一删就留下悬空指针，`Quotation.findById` 直接返 `null`。现网已存在两条这样的真实记录（料号 `3120011203`，`basis_in_drilldown=false`）：
- `reviewId = a6a2e769-555e-44a9-a398-adae62ad8059`（basis 原指向 `QT-20260807-0140`，即用户实测后自己删掉的那张单，本 repair §3 根因分析引用的正是这条链路）
- `reviewId = 6ef33476-8f7e-4654-a788-859aba7456da`（备用样本，若前一条状态被其它操作改变可切换）

**步骤**：直接 `GET /api/cpq/price-adjust/reviews/a6a2e769-555e-44a9-a398-adae62ad8059`。

**期望结果**
- HTTP `200`（不是 500）。
- 响应体全部 `quotations[].adjustedComputed = false`。
- `elementChanges[].usageQty` 与 `elementChanges[].unitPriceImpact` 全部 `null`。
- `elementImpactTotal = 0`。
- 页面不报错，财务自检行不显示。
- 后端日志有 `WARN` 记录（api.md §1.3 "后端落 WARN 日志"）。

**实际结果**：（留空）

**⚠️ 降级备选**：若执行阶段该条 review 已被其它操作清理/状态变化导致不再满足"悬空 basis"前提，先切换到 `6ef33476-8f7e-4654-a788-859aba7456da`；若两条真实样本都不可用，才退回等效构造方案：`UPDATE material_price_review SET basis_quotation_id = <一个不存在的随机UUID> WHERE id=<reviewId>`（仅作为最后手段，需在报告里注明"真实样本不可用，改用等效构造"）。

---

### TC-H2　`reviewId` 不存在 → 404

| 字段 | 内容 |
|---|---|
| 对应 | api.md §1.3 |
| 优先级 | P1 |

**步骤**：`GET /api/cpq/price-adjust/reviews/00000000-0000-0000-0000-000000000000`。

**期望结果**：HTTP `404`，body `{"code":404,"message":"review 不存在: 00000000-0000-0000-0000-000000000000"}`（或等价格式，核心是 code=404 + message 含该 id）。

**实际结果**：（留空）

---

### TC-H3　dryRun 内部失败 → 降级 200，不 500

| 字段 | 内容 |
|---|---|
| 对应 | api.md §1.3「dryRun 失败必须降级为 200 + 留白，不得 500」 |
| 前置 | 构造一个判断依据单存在、但其产品行数据已损坏（如 `customer_template_id` 指向已删除模板，或该行缺少必需的核价数据）导致 `BomTreeRenderService.render()` 内部抛异常 |
| 优先级 | P1 |

**步骤**：对该损坏单触发 `GET /reviews/{reviewId}`。

**期望结果**：HTTP `200`（不是 500），该判断依据行 `adjustedComputed=false`（降级留白），后端日志记录异常但不向前端暴露堆栈。

**实际结果**：（留空）

---

## 10. 组 I · 并发（乐观锁）

### TC-I1　`row_version` 冲突 → job item `CONFLICT`，可重试

| 字段 | 内容 |
|---|---|
| 对应 | §3 事务边界与并发（backtask） | 
| 优先级 | P1 |
| 方法 | 沿用父任务 `#16` 已验证的**确定性 SQL 级验证**手法（禁止用 `updated_at` 断言，只认 `row_version`；真实竞态需要延迟测试钩子，若无则以下述SQL验证作为等效证据） |

**步骤**
1. 造一张单 `QID-I1`，记录目标行 `row_version = V0`。
2. 直接执行升版链路的写回语句（照抄 `upgradeComponentRows` 现有 UPDATE 结构），故意代入**过期的 `V0`**（模拟"job 读到 `V0` 时刻，此刻数据库实际已经是 `V0+1`"）：
   ```sql
   -- 先真实让该行 row_version 前进一格（模拟别的操作已抢先提交）
   UPDATE quotation_line_component_data SET row_version = row_version + 1
     WHERE line_item_id=<lid> AND component_id='4a193e48-...';
   -- 再模拟 job 用旧版本号 V0 写回
   UPDATE quotation_line_component_data
      SET snapshot_rows = '<任意JSON>', row_version = row_version + 1
    WHERE line_item_id=<lid> AND component_id='4a193e48-...' AND row_version = V0;
   ```
3. 断言第二条 UPDATE 受影响行数 `= 0`。
4. 走完整审核流程触发真实升版 job（此时行版本已被步骤2意外推进，天然造成冲突条件），查询该 `job_item.status`。

**期望结果**
- 步骤3：`UPDATE 0`（乐观锁生效）。
- 步骤4：`job_item.status = 'CONFLICT'`（不是 `FAILED`）。
- `POST /api/cpq/price-adjust/job-items/{itemId}/retry` 返回非 409（可正常受理重试），重试后若无新冲突应转为 `SUCCESS`。

**实际结果**：（留空）

---

## 11. 组 J · 字面口径与精度（AP-52 / MAPPER 硬约束）

### TC-J1　`__priceVersion` 字面与 PriceReconciler 同源

| 字段 | 内容 |
|---|---|
| 对应 | §8 已知坑位 AP-52 |
| 优先级 | P1 |

**步骤**：对比 TC-A1（升版写入）与父任务已验证的归位（`PriceReconciler`）写入同一元素、同一目标版本时，`__priceVersion` 的字面值。

**期望结果**：两者**逐字符相同**（均为 `element_price_version.version_no`，如 `V26080702`），不出现"升版侧写 `versionId.toString()`、归位侧写 `versionNo`"这类分叉。指针为空走实时价的场景两侧均写 `"实时"`（不新造第二套字面）。

**实际结果**：（留空）

---

### TC-J2　MAPPER 精度：不出现科学计数法/精度丢失

| 字段 | 内容 |
|---|---|
| 对应 | §6.3 精度 |
| 优先级 | P1 |

**步骤**：TC-A1 升版后，直接查看 `driverRow.元素单价` 的 jsonb 原始文本表示（`SELECT snapshot_rows::text` 而非解析后的数值）。

**期望结果**
- 原始 jsonb 文本中该数值为 `3000.000000`（或至少是 plain 数字表示），**不是** `3E+3` 或 `3000.0` 这种截断/科学计数法形式。
- 若目标版本价带更多小数位（如 `100.000000123456`），验证不因 double 转换丢精度。

**实际结果**：（留空）

---

## 12. 组 K · 前端专项

### TC-K1　TS 编译 0 错误（F1）

| 字段 | 内容 |
|---|---|
| 优先级 | P0 |

**步骤**：`cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`

**期望结果**：0 错误，重点确认 `Record<JobItemStatus, …>` 类型的 `ITEM_STATUS_TAG` 已补全 `SKIPPED`（若漏补应在此步骤直接编译失败，属预期中的"好事"）。

**实际结果**：（留空）

---

### TC-K2　改动文件 Vite 可访问性（F1~F3）

| 字段 | 内容 |
|---|---|
| 优先级 | P0 |

**步骤**：
```bash
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/pricing/price-adjust-jobs/JobProgressDrawer.tsx
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/pages/pricing/price-adjust-review/ReviewDetailDrawer.tsx
curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' http://localhost:5174/src/types/price-adjust.ts
```

**期望结果**：三者均 `200`。

**实际结果**：（留空）

---

### TC-K3　`usageQty` 精度不被 `fmt()` 吃掉（F3-3）

| 字段 | 内容 |
|---|---|
| 对应 | fronttask F3-3 |
| 优先级 | P1 |

**步骤**：TC-D3 场景下检查「该料号用量」列的实际渲染文本。

**期望结果**：显示 `0.001159`（6 位小数，裸输出），**不是** `0.00`（若套用了固定2位小数的 `fmt()` 会显示成后者，等于把数据显示丢了——这是 fronttask 明确警告过的坑）。

**实际结果**：（留空）

---

### TC-K4　降级安全：旧结构响应不崩

| 字段 | 内容 |
|---|---|
| 对应 | fronttask §3 边界与空态 |
| 优先级 | P2 |

**步骤**：模拟后端返回缺失 `adjustedComputed` 字段的响应（可用浏览器 DevTools mock 或临时后端旧快照回放），观察前端渲染。

**期望结果**：`!r.adjustedComputed` 求值为 `true`（`undefined` 视为 falsy）→ 显示「未试算」，页面不崩、不报错；`skipped`（wire 字段名，2026-08-07 api.md 更正后不再是 `skippedCount`）缺失时汇总区不显示该项、不显示 `undefined` 字样。

**实际结果**：（留空）

---

## 13. 用例统计

| 分组 | 用例数 | P0 | P1 | P2 |
|---|---|---|---|---|
| A 核心三层数据一致性 | 4 | 4 | 0 | 0 |
| B 写入侧边界值 | 7 | 5 | 2 | 0 |
| C 结构自愈与SKIPPED | 5 | 3 | 2 | 0 |
| D 审核抽屉小计与影响 | 7 | 2 | 4 | 1 |
| E 性能/N+1 | 3 | 1 | 2 | 0 |
| F Flyway迁移 | 1 | 1 | 0 | 0 |
| G 回归 | 4 | 4 | 0 | 0 |
| H 异常/错误码 | 3 | 0 | 3 | 0 |
| I 并发 | 1 | 0 | 1 | 0 |
| J 字面口径/精度 | 2 | 0 | 2 | 0 |
| K 前端专项 | 4 | 2 | 1 | 1 |
| **合计** | **41** | **22** | **17** | **2** |

> 累计修订轨迹：初稿 37 条 → 第一轮补 `TC-B6`/AC-17、`TC-C5`/AC-18、`TC-D1b`/AC-9 健康路径对照 + `TC-B2` 升级为 P0（37→40）→ 第二轮补 `TC-B7`/AC-5·D-9（driver 行无价四键全删，主线代码评审新发现缺陷的回归验证位）（40→41）。

---

## 14. 需求文档歧义/覆盖空白清单（供主线裁决，非缺陷）

1. ✅ **已裁决（D-8，2026-08-07）**：~~AC-5 未区分手动行/非手动行~~。原提法：AC-5 原文只描述"元素∈本版明细但版本明细无价"这一通用场景，对应实现层面其实是 S4b（非手动行）的第三分支，FR-2 初稿全文没有规定手动行（S3b）在 `ep.price==null` 时该怎么办。**裁决结果**：手动行与非手动行完全同口径（删值+撤锁），判据是 `PriceReconciler.reconcileRows` 本就不区分 manual/non-manual。需求文档已加 D-8、FR-2 改为两类行共用的三分支表、AC-5 补了"落点必须完全一致"一句。测试用例侧：`TC-B2` 已从"探测性、不预设通过"改为**确定性 P0 断言**（若实现为"原样不动"直接判 FAIL），不再是待裁决项。

2. ✅ **已裁决（AC 拆分，2026-08-07）**：~~AC-1 只字面提及 `snapshot_rows`，未提及 `row_data` 手动行的锁标记~~。**裁决结果**：AC-1 已正式拆成 `AC-1a`（driver 行，对应 `TC-A1`）/ `AC-1b`（手动行锁标记，对应 `TC-B3`，已从"FR专项补充覆盖"提升为正式 AC 对应用例）。§1 AC 覆盖对照表已同步更新，不再需要靠"用例设计弥补 AC 空白"这种非正式手段。

3. ✅ **已裁决（AC-6 口径采纳，2026-08-07）**：~~AC-6 的"row_version 递增"没有定义具体递增量~~。**裁决结果**：需求文档采纳本清单提出的宽松口径，AC-6 正式改写为"`row_version` **严格大于**升版前值，不约束增量为 1"（理由：一个 line item 可能挂多个价格承载组件，各发一次 `UPDATE`）。`TC-C1` 已同步措辞，不再是待确认项。

4. **TC-C1 的"缺 quotation_view_structure"用直接 `DELETE` 构造，而非复现 BL-0150 真实触发路径**：需求文档 §3 根因C的现网样本（`QT-20260807-0128`）是"由复制单未保存过"这个真实链路产生的；本 repair 明确"不改复制侧"（只在消费侧升版链路自愈）。测试用直接 `DELETE FROM quotation_view_structure` 构造等效前置态，只验证 FR-3 自愈逻辑本身是否正确，**不验证** BL-0150 真实触发路径本身（那本就是另一个未合并的 backlog 项）。如实记录，避免"测过了复制单缺失结构的真实场景"这个误判。

5. **AC-7 情形①（补建后仍无价格承载组件）的构造成本明显偏高**：需要新建一个没有配置三角色字段的组件+模板+专用测试料号，且要保证该料号真的落进 `customer_price_adjust_material` 调价范围才会被 job 处理到。若执行阶段发现测试库现成数据里已有类似"未配三角色字段但绑定了调价范围内料号"的组件/模板组合，应优先复用而非新建，可显著降低构造成本；若两条路都走不通，需要向 PM/架构确认现网是否真的存在这类数据形态（即"料号在调价范围但产品模板压根没接价格策略"是否是一个应该存在的合法业务态，还是理论上不该出现的脏配置）。

6. **TC-C3（补建失败）与 TC-D5（非线性触发WARN）可能无法稳定构造**：两者都依赖"让内部方法抛出特定异常/产出特定非线性结果"这种白盒级别的行为，黑盒集成测试很难保证稳定复现。已在用例里各自标注了降级为代码走查的备选方案，但如实说明：**这两条用例的黑盒执行结果如果是"未能复现"，不代表功能有缺陷**，需要结合 backtask T7 单元测试的对应用例（S4b三分支、`usageQty`除零守卫等）交叉验证。

7. **AC-12"多元素版本"在现网/测试数据里较难找到真正存在非线性公式的料号**：D-5/D-6 的设计初衷是防"卡片对价格非线性"时算错，但若测试环境找不到真实非线性公式样本，TC-D5 的"反例"验证会退化成走查而非真跑通。建议若时间允许，由架构或后端指定一个已知带阶梯/条件公式的组件配置作为专用测试样本，否则该分支的正确性只能靠代码走查+单测背书，集成层面留有空白。

8. **AC-13"5→25活单数不变"与 backtask"每张表≤2条SQL"是两个独立门槛**：AC 原文只强调"数量不变"，backtask 额外要求绝对值 `≤2`。TC-E1 把两者都写成断言项，但如果两者出现分歧（例如条数确实不随N变化，但绝对值是3条不是2），应以**backtask的硬指标（≤2）优先**，因为它更贴近 CLAUDE.md 的 N+1 强制规范原文，需要执行阶段注意区分"通过AC但不通过硬指标"这种部分通过的情况，不能笼统打勾。

9. ✅ **已解除（2026-08-07，主线在 dev 库查实）**：~~判断依据单"被删除"只能靠篡改 `basis_quotation_id` 等效构造~~。**现网已有真实悬空样本，无需构造**：`material_price_review.basis_quotation_id` 本就无 FK 约束，`reviewId=a6a2e769-...`/`6ef33476-...`（均为料号 `3120011203`）的 basis 已因用户真实删除 `QT-20260807-0140` 而悬空。`TC-H1` 已改为直接读这两条真实 review，等效构造降级为最后备选。同时按建议在 `TC-D1b` 补了健康路径对照（真实 review `b95ad65d-...` 恰好一行 `isBasis=true` 且试算成功），证明"悬空 basis 降级为留白"这条逻辑没有把正常路径一起带偏。

---

## 15. 执行阶段提醒（写给下一轮执行的测试工程师）

- 本文档产出时后端/前端开发仍在进行，**所有 SQL/接口路径/字段名均基于当前 pre-fix 代码走查所得**（`MaterialVersionUpgradeService`/`PriceReconciler`/`PriceAdjustReviewService` 源码 + `V368`/`V278` migration схема），执行前请先确认对应文件改动是否已落地、字段名/端点是否有变化。
- A 组用例务必先跑（P0 核心），且严格遵守"不做保存动作"的前置约束，一旦手滑触发了 `saveDraft`，该条用例作废需重新造数复测，不能用"反正后来对了"搪塞。
- 执行完毕产出 `test-report.md`，按 `dev-docs/任务平台规则.md` §3.6 标准格式：执行环境 → 用例汇总 → 逐条结果 → 缺陷清单 → 回归结论 → AC 逐条达成对照表 → 证据。
