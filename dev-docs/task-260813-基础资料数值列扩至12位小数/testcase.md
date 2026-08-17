# testcase — 基础资料数值列扩至 12 位小数

> 把 `test.md` 的 T-1~T-10 大纲落成可执行用例。对应 `需求文档.md` §7 AC-1~AC-12。
> **本轮只编写用例，不执行**（开发仍在进行中）。执行阶段单独安排。
> 编号规则：`TC-<AC编号>.<序号>`。P0/P1/P2 优先级沿用 `test.md` 末尾的分级。

---

## 0. 测试环境与前置数据

### 0.1 两个库，必须都验

| profile | 库 | 用途 |
|---|---|---|
| 默认（dev） | `10.177.152.12:5432/cpq_db_0724` | 手工验证 / 导入 / 维护页 / 截图（T-3/T-4/T-5/T-8/T-9） |
| `test` | `10.177.152.12:5432/cpq_db` | `mvnw test`（T-1/T-2 自动化用例、AC-1/AC-2） |

```bash
# dev 库
DEV_PSQL="PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724"
# 测试库
TEST_PSQL="PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db"
```

V386 必须在两个库都跑过才能宣布通过——只跑一个库会造成"另一个库看起来仍是回归"的假阳性/假阴性。

### 0.2 登录取会话（dev 库手工验证用）

```bash
COOKIE=/tmp/claude-1000/-home-joii-project-cpq/19b0c32c-805e-4663-995d-45c08d6d8d7d/scratchpad/cpq-cookie.txt
curl -s --noproxy '*' -c "$COOKIE" -X POST http://localhost:8081/api/cpq/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"Admin@2026"}'
# 期望 code=0，返回 role 含 SYSTEM_ADMIN（核价维护页读写、导入端点鉴权都靠这个 cookie）
```

后续所有 `curl` 一律带 `-b "$COOKIE" --noproxy '*'`（本机 shell 常设 `http_proxy`，访问 localhost 必须 `--noproxy '*'`，否则拿到代理的 502 而非真实响应——见 `CLAUDE.md`「两个坑」）。

### 0.3 测试专用标识符（避免污染既有数据，也避免互相冲突）

| 用途 | 值 | 说明 |
|---|---|---|
| 报价侧（QUOTE）测试客户 | `CUST-0002`（id `d615af78-03c1-45c6-b9f0-782a180bfe9c`，名称"测试客户"） | 已在库中存在，`material_bom_item` 已有该客户的历史数据（`3110520422` 系列），选它是为了同时具备"对照修复前基线"的样本 |
| 报价侧测试料号（新建，避免动 `3110520422` 存量数据） | `TEST0813-Q01` | 物料BOM sheet，走 `MaterialBomMergeHandler` |
| 核价侧（PRICING）测试销售料号 | `TEST0813-P01` | P06/P09/P10/P12 等 pricing handler 用 |
| 核价侧测试元素代码 | `TEST0813-E01` | P01 元素核价价格表用 |
| 核价侧测试材质料号 | `TEST0813-MP01` | P07 物料与元素BOM 用（`material_part_no`） |

> ⚠️ 用全新标识符是刻意的：`需求文档.md` §2.2 明确"不回填/不重算存量数据"，用新料号能把"扩列生效"与"存量数据未被误改写"两件事完全隔离验证，互不干扰。

### 0.4 12 位测试数值基准表（全篇复用，避免每条用例各编一套数）

⚠️ Excel 单元格是 IEEE-754 double（约 15~17 位有效数字）。以下取值**有效数字 ≤ 14 位**，确保在 double 里可精确往返，测的是系统而不是 Excel 精度上限。

| 场景 | 数值 | 有效数字 | 小数位 |
|---|---|---|---|
| 净重 / 毛重（family A） | `91.768628123457` | 14 | 12 |
| 用量类（family D，两位数整数部分） | `12.345678901234` | 14 | 12 |
| 含量占比（family B，0~100 之间） | `33.333333333333` | 14 | 12 |
| 单价类（family C，DecimalScale.at 路径） | `1.234567891234` | 13 | 12 |
| 单价类（family C，setScale 路径） | `2.500000000001` | 13 | 12（刻意用接近整数但非整数的值，防止误判为"补零假通过"） |
| 边界：13 位小数（超容量，验证 HALF_UP 截断行为） | `91.7686281234567` | 15 | 13 |
| 边界：负数 | `-15.123456789012` | 14 | 12 |
| 边界：零 | `0.000000000000` | — | 12（全零） |

---

## 1. AC-1 · 列类型断言（T-1）

### TC-AC1.1 · 85 列类型全量断言（P1）

**前置**：V386 已在目标库跑过（`flyway_schema_history` 里 `version='386'` 且 `success=t`）。

```sql
-- 期望：空集
SELECT table_name||'.'||column_name AS col,
       'numeric('||numeric_precision||','||numeric_scale||')' AS actual
FROM information_schema.columns
WHERE table_schema='public' AND data_type='numeric' AND numeric_scale IS NOT NULL
  AND numeric_scale < 12
  AND table_name IN ('material_bom_item','element_bom_item','unit_price','material_master',
                     'material_recipe_element','plating_scheme','plating_fee','production_energy',
                     'tooling_cost','capacity','auxiliary_energy','electricity_price','labor_rate',
                     'fee_config','exchange_rate','exchange_rate_v6','material_customer_map',
                     'element_daily_price','element_price','element_price_strategy',
                     'element_price_version_item','process_master','production_consumable',
                     'packaging_consumable')
ORDER BY 1;
```

**判定**：0 行返回 = 通过；非空则每一行都是漏改列，直接列清单打回。

**在两个库各跑一次**（`$DEV_PSQL` / `$TEST_PSQL`）。

### TC-AC1.2 · 逐列精确匹配（不只是"scale≥12"，还要 precision 对）（P1）

`需求文档.md` §3 表格里每一列都有精确目标（如 `n(26,12)` 不是随便一个 `n(x,12)`）。TC-AC1.1 只能抓"scale<12 漏改"，抓不出"precision 缩水"（比如某列错写成 `numeric(20,12)` 而不是需求要求的 `numeric(26,12)`，虽然 scale 达标但整数容量缩水，违反 G-3）。

```sql
-- 逐列核对，示例取 3 个代表列（完整清单见需求文档 §3，实测时应逐列跑全部 85 行）
SELECT table_name, column_name, numeric_precision, numeric_scale
FROM information_schema.columns
WHERE (table_name, column_name) IN (
  ('material_bom_item','net_weight'),      -- 期望 (26,12)
  ('material_recipe_element','default_pct'), -- 期望 (16,12)
  ('exchange_rate_v6','rate')               -- 期望 (22,12)
)
ORDER BY 1,2;
```

**判定**：`numeric_precision` 与需求文档 §3 表格逐行完全一致，不只是 scale。

**建议自动化方式**：把需求文档 §3 的 85 行表格导出为 CSV（列名/目标 precision/目标 scale），写一次性脚本跟 `information_schema.columns` 做全量 join diff，比人工挑 3 个代表列更可靠——**这一条建议纳入正式回归**，见 §12 覆盖缺口分析。

---

## 2. AC-2 · 实体↔DB 反射一致性（T-2）

### TC-AC2.1 · 新增反射测试类通过（P1）

**前置**：T2（JPA 实体同步）+ T6（反射测试代码）已完成，测试库（`cpq_db`）已跑过 V386。

```bash
cd cpq-backend
./mvnw test -Dtest=<反射测试类名，如 EntityColumnScaleConsistencyTest>
```

**判定**：0 failure。测试逻辑本身应覆盖：
1. 遍历 v6 实体（含非 v6 包的 `ElementDailyPrice`/`ElementPrice`/`ElementPriceStrategy`/`ElementPriceVersionItem`/`PlatingFee`/`MaterialRecipeElement`/`ExchangeRate`）的 `@Column(precision=, scale=)`
2. 与 `information_schema.columns` 对应表列比对
3. 不一致（`precision` 或 `scale` 任一项）即 fail 并打印列名

### TC-AC2.2 · 两处已知漂移必须被测试捕获（P0 级信心用例——用来验证测试本身有效）

这条不是验证扩容功能，是验证**反射测试没有假阴性**（比如比对逻辑写错，永远返回 pass）。

**步骤**：
1. 临时把 `ProductionEnergy.java` 的 `unit_price` 字段 `@Column(precision=24, scale=12)` 手工改回 `precision=18, scale=6`（模拟漂移复发）
2. 跑 TC-AC2.1 的测试
3. **期望：测试必须 fail**，明确报出 `production_energy.unit_price` 不一致
4. 改回来，重新跑，**期望 pass**

**判定**：步骤 3 fail、步骤 4 pass，证明测试真的在比对而不是空转。

**若开发交付时未包含这条"故意注入漂移再验证测试能抓到"的验证**，反射测试本身的有效性是不可信的——建议列为 AC-2 的强制交付证据之一（`需求文档.md` 当前 AC-2 判定方式只写了"不一致即 fail"，没有要求"证明这条 fail 逻辑真的会触发"）。

---

## 3. AC-5 · 导入端到端保精度（T-3，⭐ P0 核心）

> 覆盖面要求（`test.md` 原文）：四族各挑至少 1 列，且必须覆盖 **两条 handler 路径**（`DecimalScale.at` 与 `setScale`）。
> 本节额外加入 **P06/P07 两个 pricing 侧 BOM handler**（见 §12 覆盖缺口分析——这两个 handler 完全没有 scale 归一，是 test.md 原大纲遗漏的路径）。

### TC-AC5.1 · 报价侧「物料BOM」导入（family A 重量 + family D 用量，`MaterialBomMergeHandler`）（P0）

**前置**：CUST-0002 存在（见 §0.3）。

**步骤**：
1. 构造 Excel，19-Sheet 报价基础数据模板，「物料BOM」sheet 填入一行：
   ```
   投入料号: TEST0813-Q01-C01（自制加工费件，走 ASSEMBLY 分支即可，具体类型不影响本用例目的）
   材料净重: 91.768628123457
   材料毛重: 12.345678901234
   组成用量: 12.345678901234
   底数: 1
   材质占比: （留空，仅 RECIPE 行有意义，本用例不测材质分支）
   ```
2. 调导入端点（异步，需轮询）：
   ```bash
   curl -s -b "$COOKIE" --noproxy '*' -X POST http://localhost:8081/api/cpq/basic-data-import/v6/quote \
     -F "customerId=d615af78-03c1-45c6-b9f0-782a180bfe9c" \
     -F "file=@/path/to/testcase-ac5-quote.xlsx"
   # 返回 { importRecordId, status: "PROCESSING" }
   ```
3. 轮询直到完成：
   ```bash
   curl -s -b "$COOKIE" --noproxy '*' http://localhost:8081/api/cpq/basic-data-import/v6/<importRecordId>
   # 期望最终 status = SUCCESS，failedRows = 0
   ```
4. 查库：
   ```sql
   SELECT net_weight, rough_weight, composition_qty,
          length(split_part(net_weight::text,'.',2)) AS net_decimals,
          length(split_part(composition_qty::text,'.',2)) AS qty_decimals
   FROM material_bom_item
   WHERE customer_no='CUST-0002' AND component_no LIKE 'TEST0813-Q01%' AND is_current=true;
   ```

**期望**：
- `net_decimals = 12`，`net_weight::text = '91.768628123457'`（逐位相等）
- `qty_decimals = 12`，`composition_qty::text = '12.345678901234'`

**判定**：不一致（比如又回到 6 位）= AC-5 未达成，且很可能是 T5（`MaterialBomMergeHandler` 补 `DecimalScale.at(v,12)`）与 T1（DDL）中的一个没生效。

### TC-AC5.2 · 核价侧「元素核价价格表」导入（family C，`DecimalScale.at` 路径，P01）（P0）

**步骤**：
1. 构造 24-Sheet 核价基础数据 Excel，「元素核价价格表」sheet：
   ```
   元素代码: TEST0813-E01
   核价单价: 1.234567891234
   回收折扣（%）: 5.123456789012
   币种: CNY
   计量单位: KG
   ```
2. 调导入端点（同步）：
   ```bash
   curl -s -b "$COOKIE" --noproxy '*' -X POST http://localhost:8081/api/cpq/basic-data-import/v6/pricing \
     -F "file=@/path/to/testcase-ac5-pricing-p01.xlsx"
   # 期望直接返回 status=SUCCESS（核价导入是同步的，不同于报价侧）
   ```
3. 查库：
   ```sql
   SELECT pricing_price, recovery_discount,
          length(split_part(pricing_price::text,'.',2)) AS price_decimals,
          length(split_part(recovery_discount::text,'.',2)) AS discount_decimals
   FROM unit_price
   WHERE system_type='PRICING' AND price_type='ELEMENT' AND code='TEST0813-E01';
   ```

**期望**：`price_decimals=12`，`pricing_price::text='1.234567891234'`；`discount_decimals=12`，`recovery_discount::text='5.123456789012'`。

### TC-AC5.3 · 核价侧「设备折旧成本」导入（family C，`setScale` 路径，P09）（P0）

**步骤**：
1. 「设备折旧成本」sheet：
   ```
   销售料号: TEST0813-P01   工序编号: OP001   工序名称: 测试工序
   折旧单价: 2.500000000001
   币种: CNY   计量单位: H   是否有效: 是
   ```
2. 导入（同 pricing 端点）。
3. 查库：
   ```sql
   SELECT unit_price, length(split_part(unit_price::text,'.',2)) AS decimals
   FROM production_energy
   WHERE material_no='TEST0813-P01' AND price_type='DEPRECIATION' AND process_no='OP001';
   ```

**期望**：`decimals=12`，值 `= 2.500000000001`（不是 `2.500000` 也不是被四舍五入成整数）。

### TC-AC5.4 · 核价侧「模具工装成本」导入（family C，`setScale` 路径，P12，原 scale=8→12，特别验证不是简单套用 6→12）（P0）

```
生产料号: TEST0813-P01-PROD  销售料号: TEST0813-P01
工序编号: OP001  项次: 1  模具台账/工装编号: TL01
单个模具/工装成本: 100.123456789012
寿命（次）: 10000
单循环产量: 5.123456789012
模具工装成本单价: 3.123456789012
币种: CNY  计量单位: PCS  是否有效: 是
```

```sql
SELECT tooling_unit_cost, cycle_output, tooling_unit_price,
       length(split_part(tooling_unit_price::text,'.',2)) AS price_decimals
FROM tooling_cost
WHERE material_no='TEST0813-P01' AND process_no='OP001' AND tooling_no='TL01';
```

**期望**：`price_decimals=12`。**这条格外重要**——`tooling_unit_price` 原 scale 是 8（不是像其余大多数列一样原为 6），如果开发把 T3 的 28 处字面量替换用了"全局搜索替换 6→12"这种粗暴手法而漏看了这处 8，这里会暴露（残留 8 位截断或替换出错）。

### TC-AC5.5 · 核价侧「物料BOM」导入（family A+D，P06MaterialBomHandler，⚠️ 缺口用例——见 §12）（P0，主线必须先确认此路径是否已同步归一）

**背景**：勘察发现 `P06MaterialBomHandler.java` 的 `CHILD_CONTENT` 相关字段（`composition_qty`/`base_qty`/`scrap_rate`/`fixed_scrap`/`defect_rate`）**完全没有 `DecimalScale.at` 或 `setScale` 调用**——直接 `row.getDecimal(...)` 进 content（第 96-101 行）。这与需求文档 §6.1 描述的 `MaterialBomMergeHandler` 缺陷是**同一形状但更严重**（后者至少调用方是"全精度未截断"，本任务修复目标是"截断成 12 位再比对"；P06 目前是"零归一"，即便 DB 列扩到 12 位，Excel 若给出超过 12 位的 double 噪声（如 `0.1+0.2` 类型的浮点残留），落库时会被 PG 静默截到 12 位，但**下次重导时"新解析全精度值"与"库里已截 12 位的 existing"仍会比对不相等**——具体是否复现取决于 P06 是否也需要同 T5 一样补 `DecimalScale.at(v,12)`。

**步骤**：
1. 「物料BOM」sheet（PRICING 侧模板）：
   ```
   生产料号: TEST0813-P01-PROD  销售料号: TEST0813-P01  项次: 1
   组成料号: TEST0813-COMP01  品名: 测试组成件  工序编号: OP001
   使用特性: 材料  组成用量: 12.345678901234  组成用量单位: KG
   底数: 1  材料损耗率（%）: 3.333333333333
   材料固定损耗量: 0.100000000001  不良率（%）: 1.234567890123
   计算类型: 材料
   ```
2. 导入。
3. 查库：
   ```sql
   SELECT composition_qty, scrap_rate, fixed_scrap, defect_rate,
          length(split_part(composition_qty::text,'.',2)) AS qty_decimals
   FROM material_bom_item
   WHERE material_no='TEST0813-P01' AND customer_no='_GLOBAL_' AND is_current=true;
   ```

**期望**：`qty_decimals=12`，值逐位相等。

**若此用例失败或行为存疑**：不要直接判定为"本任务遗留 bug"，**先向主线澄清**——需求文档 §3.4/§4 同步点 3 的 28 处清单里没有列出 P06/P07，不确定这是"评估过判定不需要改"还是"遗漏未评估"。见 §12。

### TC-AC5.6 · 核价侧「物料与元素BOM」导入（family B 含量，P07ElementBomHandler，⚠️ 同类缺口用例）（P0）

**背景**：同 TC-AC5.5，`P07ElementBomHandler.java` 第 67-68 行 `content`/`scrap_rate` 同样**零归一**直接进 content。且 `content` 是 §5.1 提到的**唯一被视图引用的列**（`v_composite_child_elements`），双重风险叠加。

**步骤**：
1. 「物料与元素BOM」sheet：
   ```
   销售料号: TEST0813-P01  材质料号: TEST0813-MP01  品名: 测试材质
   项次: 1  元素代码: TEST0813-E01
   组成含量（%）: 33.333333333333  损耗率（%）: 2.222222222222
   ```
2. 导入。
3. 查库：
   ```sql
   SELECT content, scrap_rate, length(split_part(content::text,'.',2)) AS content_decimals
   FROM element_bom_item
   WHERE material_no='TEST0813-P01' AND material_part_no='TEST0813-MP01' AND is_current=true;
   ```
4. **顺带验证视图透传**（AC-8 的另一面）：
   ```sql
   SELECT composition_pct FROM v_composite_child_elements
   WHERE child_hf_part_no='TEST0813-P01' AND customer_id IS NULL LIMIT 5;
   -- 视图条件较复杂（依赖 hf_part_no 非空），若本行不满足视图 WHERE 条件可能查不到，
   -- 该步骤可选，仅在有 hf_part_no 关联数据时执行
   ```

**期望**：`content_decimals=12`，`content::text='33.333333333333'`。

---

## 4. AC-6 · 维护页保存端到端保精度（T-4，⭐ P0 核心）

> 这是与 §3 完全独立的第二条写路径（`PricingSheetRegistry` → `PricingMaintenanceService`）。§3 全部通过也不能替代本节。

### TC-AC6.1 · PUT 保存 12 位小数 → GET 取回逐位相等（后端契约层，绕开前端）（P0）

**前置**：核价侧存在一个已导入的料号 + sheet（可复用 TC-AC5.3 导入的 `TEST0813-P01` / `DEPRECIATION` 组，或任选一个已有数据的 sheetKey）。

**步骤**：
1. 读取当前行（拿到当前版本 + row 结构）：
   ```bash
   curl -s -b "$COOKIE" --noproxy '*' \
     http://localhost:8081/api/cpq/pricing-basic-data/parts/TEST0813-P01/sheets/DEPRECIATION/rows
   ```
2. 基于返回结构，把 `unit_price` 字段改成 `"6.123456789012"`，`PUT` 回去：
   ```bash
   curl -s -b "$COOKIE" --noproxy '*' -X PUT \
     http://localhost:8081/api/cpq/pricing-basic-data/parts/TEST0813-P01/sheets/DEPRECIATION/rows \
     -H 'Content-Type: application/json' \
     -d '{"rows":[{"process_no":"OP001","unit_price":"6.123456789012","currency":"CNY","unit":"H"}]}'
   # 具体 body 结构以 PricingMaintenanceService 的 DTO 为准，字段名/嵌套层级需按实际接口调整
   ```
3. 再次 `GET` 同一路径：
   ```bash
   curl -s -b "$COOKIE" --noproxy '*' \
     http://localhost:8081/api/cpq/pricing-basic-data/parts/TEST0813-P01/sheets/DEPRECIATION/rows
   ```

**期望**：第 3 步返回的 `unit_price` 字符串为 `"6.123456789012"`（12 位，逐位相等），**不是** `"6.123457"`（6 位 HALF_UP 截断的旧行为）。

**判定标准**：字符串精确匹配。若返回 6 位截断值 = T4（`PricingSheetRegistry.scale()`）未同步生效。

### TC-AC6.2 · 数据库直查交叉验证（防止只信任 API 序列化层，实际列值仍被截断）（P0）

紧接 TC-AC6.1 第 2 步之后：

```sql
SELECT unit_price, length(split_part(unit_price::text,'.',2)) AS decimals
FROM production_energy
WHERE material_no='TEST0813-P01' AND price_type='DEPRECIATION' AND process_no='OP001';
```

**期望**：`decimals=12`，值与 TC-AC6.1 提交的 `6.123456789012` 一致。

**为什么这条不能省**：TC-AC6.1 只验证了 API 响应字符串，如果 `PricingMaintenanceService` 的写入路径本身仍按旧 scale 归一但**读取时又意外拼出了看起来对的字符串**（比如序列化 bug 掩盖了截断），仅测 API 会漏判。DB 直查是唯一权威来源。

### TC-AC6.3 · 前端渲染去尾零但**不截位**，回存值仍是 12 位（`fronttask.md` **F-A′** + AC-6 的前端侧，⭐ 最容易做错的坑）（P0）

> 🔄 **本用例期望值已于 2026-08-13 由技术总监更新**：前端方案由 F-A（`formatDisplayDecimal`，截 9 位）改为 **F-A′（`normalizeDecimalString`，纯去尾零、不截位）**，理由见 `fronttask.md` §3.1。
> **原因**：截 9 位后的文本就是受控输入框里用户下次编辑的起点，用户一旦在格内局部修改，刚扩到 12 位的精度会被静默改回 9 位——用观感牺牲本任务核心目标。且原方案定错了靶子：用户要消除的是「补零到 12 位」的噪声，不是有效位数。

**这是 `test.md` T-4 步骤 4 专门点名"最容易做错的地方"——若前端把格式化后的显示值写回 state 再提交，12 位就在前端丢了。**

**步骤（真机浏览器，非 curl）**：
1. 打开 `http://localhost:5174`，导航到「核价料号维护」→ 搜索 `TEST0813-P01` → 打开「折旧」tab
2. 观察 `折旧单价` 列的展示：
   - 值为 `6.123456789012`（真实 12 位有效数字）→ 期望显示 **`6.123456789012`（完整保留，不截位）**
   - 值为 `1.230000000000`（补零噪声）→ 期望显示 **`1.23`（尾随零被清除）**
   - **不应出现** `6.123456789`（截 9 位 = 误用了 `formatDisplayDecimal`，属实现错误）
3. 点击进入编辑态，**不改动该值**，直接点"保存"
4. `PUT` 请求发出后，用浏览器 DevTools Network 面板检查请求体里 `unit_price` 字段的值
5. **补充步骤（F-A′ 专属）**：在该格内做**局部编辑**（如把末位 `2` 改成 `3`，不整段重打），保存后查库

**期望**：
- 步骤 2：补零被清除，但真实有效数字完整保留
- 步骤 4：请求体里的值仍是 `6.123456789012`（12 位，未被显示层污染）
- 步骤 5：库内值为 `6.123456789013` —— **12 位精度在局部编辑后依然保持**
  （这一步正是 F-A′ 相对 F-A 的关键差异：若仍用 F-A，此处会退化成 9 位）

**判定**：
- 步骤 4 若发现回存值被截 = 显示格式化没隔离在渲染边界，判 AC-6 前端侧不达标
- 步骤 5 若发现精度退化到 9 位 = F-A′ 未正确实施（很可能仍在用 `formatDisplayDecimal`）

**参考：`normalizeDecimalString` 已实测的输入输出**（技术总监用 worktree 内 `decimal.js` 实跑）：

```
"1.230000000000"  -> "1.23"              "91.768628123457" -> "91.768628123457"
"2200.000000"     -> "2200"              "0.000000000001"  -> "0.000000000001"
"-3.140000000000" -> "-3.14"             "0.000000"        -> "0"
```

### TC-AC6.4 · 科学计数法防护回归（`api.md` §5 明确要求）（P1）

**背景**：`fronttask.md` F-B 方案提到 `stripTrailingZeros()` 对整数值会产出科学计数法（`2200.000000` → `2.2E+3`），是 `BL-0126` 记录过的坑。虽然主线建议 F-A（前端处理，不碰后端 `scaledString`），但仍需回归确认后端序列化没有引入这个问题。

**步骤**：
1. 提交一个整数值（如折旧单价填 `100`，无小数）
2. `GET` 取回

**期望**：返回 `"100.000000000000"`（12 位补零的定标字符串），**不是** `"1E+2"` 或 `"1.0E2"` 科学计数法。

---

## 5. AC-7 · 重导不升版（T-5，⭐ P0 核心，验 R-2 + 缺陷 D-1）

### TC-AC7.1 · 修复前基线复现（先证明缺陷真实存在，再证明被修复）（P0）

**这条应在开发分支上（已应用 T1 的 DDL 但**尚未**应用 T5 的 handler 修复）跑一次，作为"缺陷真实存在"的证据，而不是空口say有问题。若开发是一次性交付、无法拿到"只有 DDL 没有 T5"的中间状态，此步骤可用"读代码确认 T5 之前 `MaterialBomMergeHandler` 无 `DecimalScale.at`"代替，但**必须在测试报告里显式声明用的是哪种证据**。**

**步骤**（若有中间状态可跑）：
1. 用 TC-AC5.1 的 12 位 Excel 导入报价侧「物料BOM」第一次，记录版本：
   ```sql
   SELECT DISTINCT bom_version FROM material_bom
   WHERE customer_no='CUST-0002' AND material_no LIKE 'TEST0813-Q01%';
   ```
2. **同一份文件**再导一次
3. 再查版本号

**期望（修复前基线）**：版本号**递增**（复现"每重导一次错误升版一次"的缺陷）。

### TC-AC7.2 · 修复后验证：报价侧「物料BOM」连导两次版本不变（`MaterialBomMergeHandler` + `DecimalScale.at(v,12)`）（P0）

**步骤**：
1. 记录导入前版本（新料号场景下即"无版本"或初版）
2. 用 TC-AC5.1 的 12 位 Excel 导入第一次，记录版本号 `V1`
3. **同一份文件**（byte-for-byte 同一个 xlsx，不要重新生成哪怕内容相同）再导一次
4. 记录版本号 `V2`

```sql
SELECT DISTINCT bom_version, updated_at FROM material_bom
WHERE customer_no='CUST-0002' AND material_no LIKE 'TEST0813-Q01%'
ORDER BY updated_at DESC;
```

**期望**：`V2 = V1`（版本号不变，`updated_at` 可能因写入操作而变但不产生新的 `bom_version` 值）。

**判定**：若版本号递增 = D-1 修复未生效或 R-2 判断有误，直接打回。

### TC-AC7.3 · ⚠️ 核价侧「物料BOM」（P06）连导两次是否升版（缺口用例，见 §12）（P0，需主线先澄清 P06 是否在本任务修复范围内）

**背景**：`P06MaterialBomHandler` 与 `MaterialBomMergeHandler` 是同源同形状但**不同文件**的两个 handler（前者服务核价 24-Sheet 导入，后者服务报价 19-Sheet 导入）。`需求文档.md` §6.1 与 `backtask.md` T5 都只提到 `MaterialBomMergeHandler`，**没有提及 P06**。P06 当前实现（第 96-101 行）同样是全精度直接进 content，无任何归一。

**步骤**：
1. 用 TC-AC5.5 的 Excel 导入核价侧「物料BOM」第一次，记录 `bom_version`
2. 同一份文件再导一次
3. 比对版本号

```sql
SELECT DISTINCT bom_version FROM material_bom
WHERE customer_no='_GLOBAL_' AND material_no='TEST0813-P01' AND system_type='PRICING';
```

**期望**：待定——**取决于主线是否认可 P06 需要同 T5 一并修**。若版本号递增，需判断：
- 若这是本任务范围内的遗漏 → 补 `DecimalScale.at(v,12)` 到 P06（比照 T5 对 `MaterialBomMergeHandler` 的改法）
- 若主线裁定本任务不管 P06（比如它有其他既有的归一机制我们未发现）→ 需要书面说明理由，且登记 BACKLOG（因为它是与本任务同源的已知缺陷模式）

**本用例不预设通过/失败标准，是发现性用例**——测试目的是把行为亮出来，交给主线判断是否在验收范围内。

### TC-AC7.4 · 同上，核价侧「物料与元素BOM」（P07）（P0，同样待澄清）

同 TC-AC7.3 手法，改用 TC-AC5.6 的 Excel，查询 `element_bom.characteristic`（版本列）：

```sql
SELECT DISTINCT characteristic FROM element_bom
WHERE customer_no='_GLOBAL_' AND material_no='TEST0813-P01' AND system_type='PRICING'
ORDER BY characteristic DESC;
```

**期望**：同 TC-AC7.3，发现性用例。

---

## 6. AC-10 · 存量数据未被改写（T-6）

### TC-AC10.1 · 迁移前后 stripTrailingZeros 逐值相等（P1）

**⚠️ 必须在 V386 跑之前采集基线**——这是本用例唯一的强约束，错过时机就无法补做。

**步骤**：
1. **V386 执行前**，dump 基线（选几张改动面最大的表）：
   ```sql
   \copy (SELECT material_no, seq_no, bom_version, net_weight::text, rough_weight::text, composition_qty::text, material_ratio::text FROM material_bom_item WHERE is_current = true ORDER BY 1,2,3) TO '/tmp/claude-.../scratchpad/ac10_material_bom_item_before.csv' CSV HEADER;
   \copy (SELECT customer_no, material_no, characteristic, seq_no, component_no, content::text, scrap_rate::text FROM element_bom_item WHERE is_current = true ORDER BY 1,2,3,4,5) TO '/tmp/claude-.../scratchpad/ac10_element_bom_item_before.csv' CSV HEADER;
   \copy (SELECT system_type, price_type, version_no, code, pricing_price::text, defect_rate::text FROM unit_price ORDER BY 1,2,3,4) TO '/tmp/claude-.../scratchpad/ac10_unit_price_before.csv' CSV HEADER;
   ```
2. 跑 V386（Quarkus dev 自动 Flyway，或测试库单独跑）
3. **V386 执行后**，同样的 SQL 再 dump 一次（`_after.csv`）
4. 用脚本比对：对每一行每一列，`stripTrailingZeros(before) == stripTrailingZeros(after)`

```bash
# 简化版对比思路（实测时建议写小脚本用 Decimal 比较，而不是纯文本 diff，
# 因为 91.768628 vs 91.768628000000 文本不同但数值相等，是"预期的补零"而非"错误"）
python3 -c "
from decimal import Decimal
import csv
with open('ac10_material_bom_item_before.csv') as f1, open('ac10_material_bom_item_after.csv') as f2:
    before = list(csv.DictReader(f1))
    after = list(csv.DictReader(f2))
    assert len(before) == len(after), f'row count changed: {len(before)} vs {len(after)}'
    for b, a in zip(before, after):
        for col in ['net_weight','rough_weight','composition_qty','material_ratio']:
            bv = Decimal(b[col]) if b[col] else None
            av = Decimal(a[col]) if a[col] else None
            assert bv == av, f'{b[\"material_no\"]}/{b[\"seq_no\"]}/{col}: {bv} != {av}'
print('AC-10 PASS: all values equal after stripTrailingZeros-equivalent comparison')
"
```

**期望**：脚本无 assert 失败，行数不变。

**判定**：任何一个 `assert` 失败即 AC-10 不达标——说明 V386 不是纯 `ALTER TYPE`，可能夹带了非预期的 `UPDATE`。

### TC-AC10.2 · 行数不变（迁移不应增删行）（P1）

```sql
-- 迁移前后各跑一次，对比总数
SELECT count(*) FROM material_bom_item;
SELECT count(*) FROM element_bom_item;
SELECT count(*) FROM unit_price;
```

**期望**：迁移前后行数完全一致。

---

## 7. AC-8 · 视图重建等价（T-7）

### TC-AC8.1 · `pg_get_viewdef` 前后逐字节 diff（P1）

```sql
-- 迁移前
\copy (SELECT pg_get_viewdef('v_composite_child_elements'::regclass, true)) TO '/tmp/.../viewdef_before.txt'
-- 迁移后（DROP→ALTER→CREATE 之后）
\copy (SELECT pg_get_viewdef('v_composite_child_elements'::regclass, true)) TO '/tmp/.../viewdef_after.txt'
```

```bash
diff /tmp/.../viewdef_before.txt /tmp/.../viewdef_after.txt
```

**期望**：**唯一允许的差异**是 `content` 列（别名 `composition_pct`）关联的隐式类型标注（如果 PG 在 viewdef 里体现列类型的话——多数情况下 `pg_get_viewdef` 不显式标注来源列类型，此时应完全无 diff）。任何列名、JOIN 条件、WHERE 子句的变化都判失败。

### TC-AC8.2 · 其余 2 个依赖视图未受影响（P1，`需求文档.md` §5.1 提到 3 个视图但只有 1 个引用了目标列）

```sql
SELECT pg_get_viewdef('v_composite_child_materials'::regclass, true);
SELECT pg_get_viewdef('v_composite_child_processes'::regclass, true);
```

**期望**：这两个视图定义在迁移前后**完全不变**（它们引用的是非数值列，不该被本次 DDL 触碰）。

### TC-AC8.3 · DDL 后重启 Quarkus 的缓存自愈验证（`CLAUDE.md`「视图 DROP CASCADE 后必须重启」）（P0 级——不验会导致隐蔽的生产事故）

**背景**：`ImplicitJoinRewriter.tableColumnsCache` 是进程级缓存，视图消失瞬间若有请求命中会缓存空集，导致 BNF 路径谓词不注入，UI 出现"首值（共 N 项）"错乱。

**步骤**：
1. V386 迁移执行完（含 DROP→CREATE `v_composite_child_elements`）后，**先不重启**，立即调用一个含 BNF 路径、引用该视图的端点（找一个实际消费 `v_composite_child_elements` 的报价/核价端点）
2. 观察返回值是否正常（单值）还是异常（数组/"共N项"）
3. `touch` 一个 java 文件触发 Quarkus 重启
4. 重启后再调用同一端点

**期望**：步骤 4 必须返回正常单值。若步骤 2 已经异常（说明进程缓存已被污染），**验证重启后能自愈**——这是本条用例的核心目的，不是"永不出现异常"，而是"出现异常后重启必须能修复"。

**判定**：步骤 4 若仍异常 = 缓存自愈机制失效，是严重回归，即使 T1/T5 全部正确也不能放行。

---

## 8. AC-9 · 显示位数未变（T-8）

### TC-AC9.1 · 常量断言（P1）

```bash
grep -n "DISPLAY_SCALE" cpq-backend/src/main/java/**/PrecisionPolicy.java
grep -n "DISPLAY_SCALE" cpq-frontend/src/**/precision.ts
```

**期望**：两处均仍为 `9`，本任务 diff 中**不应出现**对这两个常量的改动（`git diff` 里搜不到这两行的修改）。

### TC-AC9.2 · 三视图截图对比（迁移前 vs 迁移后）（P1）

- [ ] 报价单编辑页：任选一个含 DECIMAL 字段的组件，截图对比前后显示位数
- [ ] 报价单详情页：同上
- [ ] 核价单渲染页：同上

**期望**：三处显示位数（最多 9 位去尾零）与迁移前完全一致，**唯一允许的变化**是核价维护页从"6 位补零"变为"最多 9 位去尾零"（这是修正不是回归，见 §4 TC-AC6.3）。

---

## 9. R-4 · 下游计算影响面（T-9）

### TC-R4.1 · 已提交/已冻结单据总价逐字节不变（P0 级——这是"不破坏现网数据"的红线）

**步骤**：
1. 迁移前，找一张状态非 `DRAFT` 的已提交报价单（`SUBMITTED`/`APPROVED` 等），记录其快照总价：
   ```sql
   SELECT id, status, total_amount::text FROM quotation WHERE id='<已提交单据ID>';
   ```
2. 跑完 V386 + 全部代码改动
3. 重新查询同一单据（不要打开重新计算，只查库/查详情接口）：
   ```sql
   SELECT id, status, total_amount::text FROM quotation WHERE id='<同一ID>';
   ```
4. 详情页 API 拉取，比对总价字符串

**期望**：`total_amount` 逐字节不变。**任何变化都是严重回归**——已冻结单据的值来自快照，不应受基础资料列 scale 变化影响。

### TC-R4.2 · DRAFT 单重新打开的总价漂移幅度记录（P1）

**步骤**：
1. 迁移前，找一张 `DRAFT` 单，记录当前总价
2. 跑完迁移
3. 重新打开该 `DRAFT` 单（触发重算）
4. 记录新总价，计算差值

**期望**：差值量级应在 `1e-6` 附近（12 位 vs 6 位精度差异的合理范围），**不应出现显著跳变**（比如差值 > 0.01 或占比 > 0.1%）。

**判定**：若跳变显著，**停下来查根因**——可能是另一处隐藏截断被本次改动意外放大，而不是"扩容生效的正常代价"。

---

## 10. AC-3/AC-4 · Handler 与 Registry 同步核对（grep 证据类用例）

### TC-AC3.1 · 28 处 handler 字面量清单核对（P1）

```bash
cd cpq-backend
grep -rn "DecimalScale.at(.*, [0-9]" src/main/java/com/cpq/basicdata/v6/
grep -rn "setScale([0-9]" src/main/java/com/cpq/basicdata/v6/
```

**期望**：输出的每一处数值参数均为 `12`（`需求文档.md` §3.5 排除清单里的列除外——理论上排除清单里的列不出现在这批 handler 里，若出现需人工判断是否属于误伤）。

**判定**：任何残留 `6`/`4`/`8` 且不在排除清单内 = 漏改。**必须逐条对照 `backtask.md` T3 的 14 行清单**，不能只看数量。

### TC-AC4.1 · 16 处 Registry `.scale()` 核对（P1）

```bash
grep -n "\.scale(" cpq-backend/src/main/java/com/cpq/basicdata/v6/maintenance/PricingSheetRegistry.java
```

**期望**：全部 16 处第二参数为 `12`。

### TC-AC3/4.2 · 交叉比对：T3 与 T4 是否真的是"同一份 scale 的两份副本"且都改了（P0，防止"改了一边漏另一边"的静默失效——本任务反复强调的核心风险）

对照表（示例，实测时应覆盖 `PricingSheetRegistry.java` 全部 16 处）：

| 列 | Handler（T3）改动后 | Registry（T4）改动后 | 一致？ |
|---|---|---|---|
| `pricing_price`（P01/P02/CONSUMABLE/PACKAGING/...多组共用） | 应为 12 | 应为 12 | — |
| `defect_rate` | 应为 12 | 应为 12 | — |
| `cost_ratio` | 应为 12 | 应为 12 | — |
| `composition_qty` | 应为 12 | 应为 12 | — |
| `standard_labor_rate` | 应为 12 | 应为 12 | — |
| `tooling_unit_price`（原 8） | 应为 12 | 应为 12 | — |

**判定**：任何一行"Handler 改了但 Registry 没改"或反过来 = 静默失效复发，直接判 AC-3/AC-4 不达标。

---

## 11. AC-11 · N+1 自检 & AC-12 · 部署脚本

### TC-AC11.1 · N+1 自检声明核验（P2）

**判定**：交付文档中必须出现类似
> `N+1 自检：本次改动 0 处新增循环；T5 的 handler 改动在既有行循环内，仅做内存 setScale，无查库 ✅`
的声明。测试工程师核对：本任务改动是 DDL + 常量替换为主，**理论上不涉及查库循环**，若发现改动引入了循环内查库（比如 T2 的反射测试如果写成"逐个实体单独查一次 information_schema"而不是一次性拉全表再内存比对），应打回。

```sql
-- 反射测试若走一次性查询而非逐表循环，SQL 条数应恒定，可用如下方式辅助验证（若测试内部有日志）
```

### TC-AC12.1 · 部署脚本可跑性（P2）

```bash
# cpq-init-empty-navicat.sql 应包含 V386 的全部列定义（可直接跑一次空库验证）
psql -h <临时空库> -U postgres -d <临时库名> -f deploy/cpq-init-empty-navicat.sql
# 期望：无报错，flyway_schema_history 或等效标记体现到 V386

# 0813-dbupdate.sql 应可在"已是 V385"状态的库上跑
psql -h <已是V385状态的临时库> -U postgres -d <临时库名> -f deploy/0813-dbupdate.sql
# 期望：无报错，列类型变为目标类型
```

**期望**：两个脚本都能跑通，且跑完后的 schema 与 Flyway 迁移到 V386 的结果一致（用 TC-AC1.1 的 SQL 在两条路径产出的库上各跑一次，结果应相同）。

---

## 12. 回归：既有测试与 E2E（T-10）

### TC-T10.1 · 后端全量单测（P2，必须在 worktree 的 `cpq-backend/` 里跑）

```bash
cd /home/joii/project/cpq/.claude/worktrees/task-0813-scale12/cpq-backend
./mvnw test
```

**期望**：0 failure（除已知的、与本任务无关的历史失败——若有需在测试报告里逐条列出并附对照基线证明"非本次引入"）。

### TC-T10.2 · 前端类型检查（P2）

```bash
cd /home/joii/project/cpq/.claude/worktrees/task-0813-scale12/cpq-frontend
npx tsc --noEmit -p tsconfig.json
```

**期望**：0 错误。

### TC-T10.3 · 改动文件 Vite 可达性（P2）

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/master-data/part-costing/EditableSheetTable.tsx
```

**期望**：200。

### TC-T10.4 · E2E 替代方案确认（P2）

按 `test.md` 原文，本任务未触及 AP-44/AP-37 类协议文件，E2E 非强制。但需确认：
- [ ] 若 E2E 夹具（`BL-0158`）当前可用，跑一次 `quotation-flow.spec.ts` 作为回归网
- [ ] 若不可用，`test-report.md` 必须显式写明替代理由（三视图截图 + 单测全绿 + 本文档的实测精度证据）

---

## 13. 边界值 / 异常输入专项测试（本节是对 `test.md` 大纲的补充，覆盖需求文档 §3.6 提到但未被 test.md 覆盖的边界）

### TC-BD.1 · 13 位小数输入（超过目标容量）应被 HALF_UP 截断到 12 位，不报错（P1）

**步骤**：用 §0.4 的 `91.7686281234567`（13 位小数）填入 `net_weight`，导入。

```sql
SELECT net_weight::text FROM material_bom_item WHERE material_no='TEST0813-Q01-BD1' AND is_current=true;
```

**期望**：`91.768628123457`（第 13 位 `7` 按 HALF_UP 进位到第 12 位，`...456|7` → `...457`）。**不应报错、不应导入失败**——`需求文档.md` §6.1 明确"Excel 若有 13 位仍会复发（虚假升版）"，说明 13 位输入是预期会出现的场景，必须能正常处理（截断），只是重导时仍可能触发升版（见 TC-BD.1b）。

### TC-BD.1b · 紧接 TC-BD.1，验证 13 位输入的重导升版行为（⭐ **P0**，与 AC-7 的"12 位不升版"边界互补）

> ✅ **技术总监已裁决（2026-08-13）**：**13 位输入连导两次，版本号必须不变。**
>
> **理由**：归一动作的全部意义就是让 content 的口径与 DB 存储值的口径**恒一致**。`DecimalScale.at(v, 12)` 之后，无论 Excel 给 12 位还是 13 位还是 20 位，进入 `CHILD_CONTENT` 的值恒为 12 位；库里 load 回来的 existing 也是 12 位；`norm()` 比对恒相等 → **不升版**。若观测到升版，就是归一缺失或未覆盖全部路径，**判为缺陷，不是预期行为**。
>
> 这同时澄清了 `需求文档.md` §6.1 那句"Excel 若有 13 位仍会复发"的歧义——**该表述描述的是"不做归一"时的情形**，不是"做了归一之后仍应复发"。归一到位后 13 位输入同样稳定。

> 🔺 **本用例由 P1 提升为 P0。** 依据 §14.3 的结论再往下推一层：
>
> §14.3 说"AC-5 测不出 handler 缺归一，只有 AC-7 能"——方向正确，但**还不够精确**。实际上在 **12 位输入**下，缺归一的 handler 走 AC-7 **也照样通过**：content 是 12 位全精度，existing 也是 12 位，`stripTrailingZeros` 后相等，不升版。
>
> **只有输入超过 12 位（即本用例）才能真正区分**：
> - 归一到位 → content 恒 12 位 = existing → **不升版** ✅
> - 零归一 → content 13 位 ≠ existing 12 位 → **升版** ❌ 暴露缺陷
>
> 所以「**验证同步点 3 真的生效了**」这件事，最终落在本用例身上，而不是 AC-5、也不是 12 位输入的 AC-7。**精简 P0 时本用例不可砍。**

**步骤**：用 TC-BD.1 那份含 13 位小数的 Excel 再导一次。

**期望**：`bom_version` 等版本列**与第一次导入后完全一致**，导入结果状态为 `UNCHANGED`。

**判定**：升版 = 该 handler 的归一缺失或未覆盖全路径，判缺陷。请同时记录是哪张表/哪个 handler，便于定位。

**覆盖建议**：本用例应对**本期修的 BOM 四件套 + P12** 各跑一次——
`quote/MaterialBomMergeHandler`、`pricing/P06MaterialBomHandler`、`pricing/P07ElementBomHandler`、`quote/Q04ElementBomHandler`、`pricing/P12ToolingCostHandler`。

### TC-BD.2 · 负数（P1）

```sql
-- 用 §0.4 的 -15.123456789012 填入某个允许负数的列（如 recovery_discount / change_rate 之类可能有负值语义的列）
-- 需先确认哪些目标列在业务上允许负数——不是所有列都该测负数（比如 usage_qty 用量语义上不该为负）
```

**建议**：先过一遍 §3 的 86 列，标出"业务语义允许负数"的子集（如折价类、变动率类），只在这些列上测负数；其余列测负数应验证**拒绝或标记异常**而非静默接受（若当前系统对此本就没有校验，属于既有行为，不在本任务范围内，但测试应记录现状）。

### TC-BD.3 · 零值（P1）

```
净重 = 0.000000000000
```

**期望**：落库为 `0.000000000000`（12 位全零），**不是** `NULL`（`0` 和 `NULL` 语义不同，导入 parser 不应把 `0` 误判成"未填"）。

### TC-BD.4 · NULL / 空单元格（P1）

Excel 单元格留空。

**期望**：落库为 `NULL`，不应因扩列产生 `0.000000000000` 之类的"假默认值"。

### TC-BD.5 · 整数部分占位大导致有效小数位受 double 限制（验证测试数据本身设计正确，间接验证系统未引入新问题）（P2）

```
净重 = 123456789012.345678  （整数部分 12 位 + 小数 6 位，总有效数字 18 位，超出 double 精确表示范围）
```

**预期**：这条**不是**测系统 bug，是确认"整数部分越大可用小数位越少"这个 Excel 层面的物理限制在系统里如实反映（即系统不会凭空"修复" Excel 已经丢失的精度，也不应该在此基础上进一步丢精度）。

**判定**：观察落库值与"该 double 实际能精确表示的值"是否一致（不是与用户输入的字面量比较，因为字面量本身在 Excel 里就已经失真）。此用例主要用于**排除误报**——如果后续某条 12 位用例意外只拿到更少的位数，先检查是不是踩了这条边界（整数部分过大），而不是误判为系统 bug。

### TC-BD.6 · `element_bom_item.content` 视图透传边界（该列的特殊性——唯一被视图引用的目标列）（P1）

已在 TC-AC5.6 步骤 4 覆盖基本场景，此处补充：

**步骤**：用 12 位小数导入 `content` 列后，同时验证：
1. `element_bom_item.content` 本身：12 位
2. 视图 `v_composite_child_elements.composition_pct`：**应同样透传 12 位**（视图只是 `SELECT ebi.content AS composition_pct`，无额外 `ROUND`/`::numeric(x,y)` 转换，理论上应完全透传）

```sql
SELECT ebi.content::text AS raw, v.composition_pct::text AS via_view
FROM element_bom_item ebi
JOIN v_composite_child_elements v ON v.child_hf_part_no = ebi.material_no
WHERE ebi.material_no = 'TEST0813-P01' AND ebi.material_part_no='TEST0813-MP01'
LIMIT 1;
```

**期望**：`raw = via_view`，逐位相等。

---

## 14. 覆盖缺口分析（本节是主线审核重点）

对照 `test.md` 现有大纲（T-1~T-10）与 `需求文档.md` AC-1~AC-12，逐一核对后发现以下缺口：

### 14.1 【发现，非文档遗漏但实质性】P06/P07 两个 pricing 侧 handler 完全没有 scale 归一，且不在任何清单里

这是本轮编写用例时通过读代码（而非只读文档）发现的，**不是** `test.md` / `backtask.md` 文档层面的疏漏描述问题，而是**代码勘察本身可能有遗漏**：

- `需求文档.md` §4 同步点 3 的清单（`P01/P02/P08/P09/P10/P11/P12/P13/P14/P15/P18/P22/P23`、`FinishedOtherMergeHandler`、`IncomingOtherMergeHandler`，约 28 处）**不包含 `P06MaterialBomHandler` / `P07ElementBomHandler`**
- `需求文档.md` §6.1 的缺陷 D-1 只点名了 `MaterialBomMergeHandler`（报价侧，`v6/quote` 包），**没有提到 `P06MaterialBomHandler`（核价侧，`v6/pricing` 包）**——两者是不同文件，服务不同的导入入口（报价 19-Sheet vs 核价 24-Sheet），但写入**同一张表**（`material_bom_item`）
- 实测读码（本文档 §3 TC-AC5.5/5.6、§5 TC-AC7.3/7.4 已给出具体验证步骤）：`P06MaterialBomHandler.java:96-101` 与 `P07ElementBomHandler.java:67-68` 的 content 字段拼装**没有任何** `DecimalScale.at` 或 `setScale` 调用，是比 D-1 更彻底的"零归一"

**影响面**：
- `material_bom_item.composition_qty/base_qty/scrap_rate/fixed_scrap/defect_rate`（family B/D 的多个列）
- `element_bom_item.content/scrap_rate`（family B，且 `content` 是唯一视图引用列）

**建议主线澄清的问题**：
1. P06/P07 是否本就该在 T3（28 处 handler 同步）清单内，只是勘察时漏数了？还是勘察时判断"这两个不需要归一"（比如它们走了另一条我未发现的归一路径，比如 `VersionedV6Writer` 内部统一处理）？
2. 若确认是遗漏，是否应该像 T5 对 `MaterialBomMergeHandler` 一样，也给 P06/P07 补 `DecimalScale.at(v,12)`？
3. 若本期不修，是否需要登记 BACKLOG（因为它与 D-1 同源、同风险模式，"扩列后不归一"在这两个 handler 上同样成立）？

我已经把验证步骤写成 TC-AC5.5/5.6（导入端到端）与 TC-AC7.3/7.4（重导升版行为）**发现性用例**——不预设通过/失败，先把行为暴露出来再交给主线判断是否在 AC-7 验收范围内。

### 14.2 test.md 对 AC 的覆盖核对

| AC | test.md 是否覆盖 | 本文档补充 |
|---|---|---|
| AC-1 | T-1 覆盖，但只断言 `scale<12`，**未断言 precision 是否精确匹配**（可能漏抓"scale 对但 precision 缩水"的情况） | TC-AC1.2 补精确匹配 + 建议做全量脚本 diff 而非人工挑 3 列 |
| AC-2 | T-2 覆盖，但**没有"验证测试本身有效"的反向用例**（万一比对逻辑写反，永远 pass，谁都不会发现） | TC-AC2.2 补"故意注入漂移→必须 fail"的验证 |
| AC-3 | T-10 交付清单里提到"grep 输出附在 PR 里逐条对照"，但 test.md 正文没有专门章节 | TC-AC3.1/AC3.4.2 补齐，且强调 T3/T4 交叉比对（不能分开看） |
| AC-4 | 同上 | 同上 |
| AC-5 | T-3 覆盖思路对，但**只给了 1 个具体例子（材料净重）**，"四族各挑 1 列 + 两条路径"是文字要求，没有落成可执行步骤 | TC-AC5.1~5.6 六个具体用例，含 Excel 内容、curl 命令、SQL |
| AC-6 | T-4 覆盖思路对且点出了"最容易做错的地方"，但同样只是文字描述，没有给出实际 payload/请求 | TC-AC6.1~6.4 四个具体用例 |
| AC-7 | T-5 覆盖了"报价侧同文件重导"，但**完全没提核价侧 P06/P07**（见 §14.1） | TC-AC7.1~7.4，含缺口用例 |
| AC-8 | T-7 覆盖了视图 diff，**但没有单独验证另外 2 个未受影响视图确实没被误改**，也没有覆盖"重启前查询会暴露异常"这个中间态 | TC-AC8.2（另 2 个视图不变）+ TC-AC8.3（重启前/后对照，不只是"重启了"） |
| AC-9 | T-8 覆盖 | TC-AC9.1/9.2，无实质补充 |
| AC-10 | T-6 覆盖思路完整（迁移前 dump 基线），**但没给出比对脚本**——"stripTrailingZeros 后逐值相等"停留在描述层面，没人告诉执行者怎么自动比对，容易变成人工目测漏判 | TC-AC10.1 给出可执行的 Python 比对脚本 |
| AC-11 | T-10 有 N+1 自检段落，覆盖到位 | 无补充 |
| AC-12 | T-7（backtask）提到但 test.md 无独立验证步骤 | TC-AC12.1 |

### 14.3 四个同步点的验证是否都是"直接"的，还是有的是"间接推断"

逐一核查：

| 同步点 | test.md 验证方式 | 是否直接 |
|---|---|---|
| 1（DB 列类型） | T-1 直接查 `information_schema` | ✅ 直接 |
| 2（JPA 实体） | T-2 反射比对 | ✅ 直接（但如 §14.2 所述，缺"测试有效性"的自证） |
| 3（导入 handler） | T-3 端到端导入后查库 | ✅ 直接，但**只验证了"最终库内值是 12 位"，没有验证"是通过归一到 12 位实现的，而不是恰好没被截断"**——如果某个 handler 本来就没有任何 scale 处理（如 P06/P07），12 位 Excel 导入后即便 DB 列已扩容到位，落库值也会"恰好"是 12 位（因为 DB 层不再截断），**这会掩盖"handler 层缺归一"这个问题，测试会误判 T-3 通过**。真正会暴露这个问题的是**重导不升版**（AC-7），因为归一缺失只在"多次解析同一份数据比对是否一致"时才会露馅 | ⚠️ **间接**——见下方详述 |
| 4（Registry 镜像） | T-4 走维护页保存后查库 | ✅ 直接 |

**14.3 的核心发现**：**同步点 3 的"导入端到端保精度"（AC-5）测试无法单独证明 handler 层做了归一**——只要 DB 列扩到 12 位，即便 handler 完全不做 `DecimalScale.at`，12 位 Excel 导入后查库看到的也是 12 位（因为没人再截断它）。**AC-5 测的是"DB 容量够不够"，AC-7（重导不升版）测的才是"handler 有没有做归一"**。

这解释了为什么 §14.1 发现的 P06/P07 缺口不会被 test.md 原有的 T-3 抓到——**T-3 天然测不出"缺归一"这类问题，只有 T-5（AC-7）能测出来**。这是本次审阅里最重要的一条结论：**AC-5 和 AC-7 不是同一件事的两个角度，AC-7 是 AC-5 测不到的盲区的唯一补丁**，两者都是 P0 不是巧合，而是必要——少了 AC-7，"handler 到底有没有做归一"这件事就是纯靠代码审查/人工承诺，没有可执行证据。

**建议**：交付时若因为时间原因要精简 P0 用例，**AC-7（含 P06/P07 的发现性用例）不能砍**，这是唯一能验证"四个同步点都真的生效了"而不是"DB 列扩容凑巧掩盖了 handler 层遗漏"的手段。

### 14.4 D-1（虚假升版）与 D-2（实体漂移）的用例覆盖

- D-1：TC-AC7.1（基线复现）+ TC-AC7.2（修复验证）✅ 覆盖，且额外发现 P06/P07 的同形态问题（TC-AC7.3/7.4）
- D-2：TC-AC2.2 的第一步"临时改回 `production_energy.unit_price` 为 `(18,6)`"间接覆盖了"这个漂移点确实被反射测试捕获"；但**没有专门验证 `material_bom_item.net_weight/.rough_weight` 那处漂移**（`需求文档.md` §6.2 提到两处，TC-AC2.2 示例只写了第一处）

**补充建议**：TC-AC2.2 的"注入漂移"验证应对 §6.2 提到的**两处漂移都各做一次**，而不是只做 `production_energy.unit_price` 那一处（已在 TC-AC2.2 正文提及但值得在此强调：交付证据里应看到两处都验证过）。

### 14.5 边界情况覆盖（原大纲缺失，本文档 §13 补充）

`test.md` 原大纲完全没有边界值章节。已在 §13 补充：13 位超容量输入的截断行为（TC-BD.1/1b）、负数（TC-BD.2）、零值（TC-BD.3）、NULL（TC-BD.4）、double 精度物理上限对测试数据设计的影响（TC-BD.5）、`content` 列视图透传（TC-BD.6）。

其中 **TC-BD.1b（13 位输入的重导升版行为）是本次新识别的、最值得关注的一条边界**：`需求文档.md` §6.1 原文只说"13 位仍会复发"，但没有明确这是"必然复发"还是"只要归一动作到位就不该复发"。建议闸门确认阶段一并向主线澄清此点的预期行为，避免执行阶段出现"到底该不该升版"的判定分歧。

---

## 15. 用例清单汇总

| 优先级 | 用例数 | 覆盖 AC |
|---|---|---|
| P0 | TC-AC5.1~5.6（6）+ TC-AC6.1~6.3（3）+ TC-AC7.1~7.4（4）+ TC-AC8.3（1）+ TC-R4.1（1）+ TC-AC3/4.2（1）= **16** | AC-5、AC-6、AC-7、AC-8（部分）、R-4（部分） |
| P1 | TC-AC1.1/1.2（2）+ TC-AC2.1/2.2（2）+ TC-AC6.4（1）+ TC-AC8.1/8.2（2）+ TC-AC9.1/9.2（2）+ TC-AC10.1/10.2（2）+ TC-AC3.1/4.1（2）+ TC-R4.2（1）+ TC-BD.1/1b/2/3/4/6（6）= **20** | AC-1、AC-2、AC-3、AC-4、AC-8（部分）、AC-9、AC-10、R-4（部分）、边界 |
| P2 | TC-AC11.1、TC-AC12.1、TC-T10.1~10.4（4）、TC-BD.5 = **7** | AC-11、AC-12、回归、边界 |

**合计 43 条用例**（不含 §0 环境准备类步骤）。

---

## 16. 诚实评估：哪些 AC 难以验证或需要主线澄清

1. **AC-7（重导不升版）在 P06/P07 上的预期行为不明确**（§14.1/14.4）——这两个 handler 不在需求文档任何清单里，我无法判断"连导两次是否应该不升版"是不是本任务的验收范围。已写成发现性用例（TC-AC7.3/7.4），**需要主线在闸门确认阶段明确表态**：这是本期修复范围、下期 BACKLOG、还是不算 bug（如果我漏看了某个已有的归一路径）。

2. **13 位超容量输入的重导行为（TC-BD.1b）预期不明确**——需求文档 §6.1 原文"13 位仍会复发"读起来像是在描述"这是已知局限，本任务不解决"，但从 `DecimalScale.at` 的实现（HALF_UP 归一到固定 scale）看，13 位输入归一后应该是稳定值，理论上不该复发升版。这句话到底是"客观限制"还是"未修复的已知缺陷"，我拿不准，已在用例里注明。

3. **TC-AC8.3（DDL 后重启缓存自愈）依赖能找到一个真实消费 `v_composite_child_elements` 的端点**——我没有在本轮里去定位具体端点（只做了代码勘察和用例设计，没有执行），执行阶段需要先花时间定位这个端点，如果找不到直接消费该视图的端点（都是间接通过 SQL 视图模板引用），这条用例的可执行性会打折扣，需要执行阶段的测试工程师根据实际代码结构调整。

4. **AC-6 的维护页前端用例（TC-AC6.3）依赖真机浏览器操作**，无法用纯 curl 验证"显示格式化是否污染回存值"这个前端状态管理层面的问题——这条必须留到执行阶段用 Playwright 或人工浏览器操作完成，本轮只能给出操作步骤，不能提前判定结果。

5. **TC-AC10.1 的迁移前基线采集是一次性窗口**——如果执行阶段 V386 已经在某个共享库（尤其 dev 库）上跑过且没人采集基线，这条用例会永久失去执行条件，只能退化成"抽查现有数据看起来合理"这种弱验证。**这一点需要提醒主线：V386 一旦落地到 dev 库，AC-10 的强证据窗口就关闭了，请确保执行测试的时间点在 V386 落库之前完成基线采集**。

---

## 17. dev-docs 索引声明

本轮仅新增 `testcase.md` 一个文件到既有任务目录 `dev-docs/task-260813-基础资料数值列扩至12位小数/`，未新建任务目录、未改变任务状态（任务仍在开发中，尚未合并）。

dev-docs 索引：无变化（未新建目录，未改状态）
