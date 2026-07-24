# 测试用例 · 元素价格手工维护（update-0724）

> **权威依据**：同目录 `需求文档.md` §8 验收标准（16 项）+ §11 澄清 U0~U13；`api.md`（端点/字段/错误码）；`backtask.md`（后端实现约定）；`fronttask.md`（前端交互约定）。
> **格式沿用**：`dev-docs/task-0722-元素价格策略/testcase.md`（母任务用例风格：编号 / 关联验收项 / 前置条件 / 操作步骤 / 预期结果 / 验证方式）。
> **状态**：本文档仅做**用例设计**，尚未执行（前后端并行开发中，代码未就绪）。所有"实际结果""通过/失败"列留空，待技术总监审核通过、代码交付后进场实测再回填。
> **测试库**：`10.177.152.12:5432/cpq_db`（`postgres`/`joii5231`）；后端 `localhost:8081`；前端 `localhost:5174`；登录 `admin`/`Admin@2026`。
> **API 基准路径**：`/api/cpq/element-price`（单数命名空间），下表用例中的相对路径均省略该前缀。
> **编写人**：cpq-tester　**编写日期**：2026-07-23

---

## 0. 概述

### 0.1 覆盖范围（按需求任务拆解的 10 个功能组）

1. 新建价格（TC-CRT）
2. 修改价格（TC-UPD）
3. 删除价格（TC-DEL）
4. 取价链路 —— 手工价与导入价等价性（TC-FETCH）🔴 核心
5. 留痕（变更历史写入 + 事务原子性）（TC-LOG）
6. 变更历史 Tab（查询 / 筛选 / 只读）（TC-HIST）
7. v1 元素价格中心下线彻底性（TC-V1）
8. 权限（TC-PERM）
9. 无回归（TC-REG）
10. 列表操作规范（TC-UI）

用例总数：**106 条**（TC-CRT 17 / TC-UPD 12 / TC-DEL 9 / TC-FETCH 6 / TC-LOG 10 / TC-HIST 13 / TC-V1 13 / TC-PERM 9 / TC-REG 8 / TC-UI 9）。

### 0.2 风险点速览（🔴 标记的用例，详见文末"风险最高的测试点"）

1. **撞键静默覆盖**——新建时若查重与插入之间处理不当，理论竞态或误用 `ON CONFLICT DO UPDATE` 会让 409 语义退化为静默覆盖，原值被吃掉且无人知晓（TC-CRT-13）。
2. **键锁定名存实亡**——若 `UpdatePriceRequest` DTO 不慎声明了 `elementCode`/`sourceId`/`priceDate` 字段（哪怕只是"声明后不用"），Jackson 会把它们绑定上并可能被后续代码误用，键锁定就从"结构性不可能"退化为"人为约定"（TC-UPD-08/09）。
3. **取价链路"看似通但没测系数换算"**——只用 `factor=1/premium=0` 的策略验证，无法暴露"手工建价路径漏乘系数"这类 bug；本文档专门设计了 `factor=1.05/premium=2.00` 的判别数据（TC-FETCH-03/04）。
4. **事务边界漏写**——价格写入与日志写入若不在同一 `@Transactional` 方法内，任一环节失败都会导致"价改了没留痕"或"留痕了价没改"两种数据不一致状态之一，且两者都不会被常规接口测试发现（TC-LOG-07/08）。
5. **v1 下线不彻底、休眠代码复活**——`ElementPriceHint` 若代码残留但触发条件本次改动后变为真（`element_name` 列语义漂移），会在未来某天无预警显示跨源混取的错误价（TC-V1-06~09）。

### 0.3 测试数据总纲（先看这张表，再看 §1 的可执行 SQL）

| 用途 | 元素 | 价格源 | 客户 | 说明 |
|------|------|--------|------|------|
| 新建 / 修改 / 删除 / 撞键 / 留痕 / 历史 / UI 规范基础组 | `Sn`（锡，ACTIVE） | 新建测试专用源 `TEST-EDPL-0724-SRC`（ACTIVE） | 不涉及 | 与 §1.2 生产客户策略完全隔离，纯 CRUD |
| 源非 ACTIVE 拒绝 | `Sn` | 新建测试专用源 `TEST-EDPL-0724-SRC-DIS`（DISABLED） | 不涉及 | §1.1 |
| 元素非 ACTIVE 拒绝 | `TEST-EL-INACT`（现网已存在，INACTIVE） | 任意 ACTIVE 源 | 不涉及 | 无需新建，现网已有 |
| 取价链路 · LATEST 回退 | `Cu`（铜） | **生产源 `长江有色网`**（现网 CUST-1269 默认策略绑定的源） | `CUST-1269`（罗克韦尔，现网真实客户，已有默认策略） | §1.2/§1.3 |
| 取价链路 · AVG 均值重算 + 系数换算 + 手工/导入等价 | `Ag`（银） | 同上 `长江有色网` | `CUST-1269`，新建一条**测试专用例外**（AVG/30DAY/1.05/2.00） | §1.3 |

> ⚠️ `CUST-1269` 是共享库里的真实客户，`长江有色网` 是其正式配置的默认策略所用源。测试对该客户/该源写入的价格数据**日期区间刻意选在 2026-06 月**（与母任务 task-0722 testcase.md 的 2026-02~2026-04 测试窗口、以及当前系统日期 2026-07-23 均不重叠），降低与其他并发会话/正式业务撞车的概率；执行前仍需按 §1.0 重新核实无残留冲突数据。

### 0.4 前置口径确认（2026-07-23 SQL 直查，供设计参考；执行前需按当时库内实际值重新核实，共享库随时间变动）

| # | 事实 | 核实方式 |
|---|------|---------|
| ① | `CUST-1269` 已有默认策略：`source_id=ef2402a0-adcd-4c47-b97f-eab5100de0dc`（长江有色网）、`method=LATEST`、`factor=1.0000`、`premium=0.0000`；`CUST-1269` 对 `Ag` **无**任何例外配置 | `SELECT * FROM element_price_strategy WHERE customer_no='CUST-1269'` |
| ② | 该源下 `Cu`/`Ag` 当前**无**历史价格数据（干净，不会与新写入的测试数据混淆） | `SELECT * FROM element_daily_price WHERE source_id='ef2402a0...' AND element_name IN ('Cu','Ag')` → 0 行 |
| ③ | 元素 `TEST-EL-INACT` 现网已存在且 `status='INACTIVE'`，可直接复用做"元素非 ACTIVE"负例，无需新建 | `SELECT status FROM element WHERE element_code='TEST-EL-INACT'` |
| ④ | 现网仅有 2 个价格源（`百川盈孚`、`长江有色网`），均 `ACTIVE`；**无**现成的 `DISABLED` 源，需按 §1.1 新建一个 | `SELECT source_name, status FROM element_price_source` |
| ⑤ | `element_daily_price_log` 表**尚不存在**（`to_regclass('public.element_daily_price_log')` 为空），确认 B1 迁移未落地，本文档用例待代码交付后执行 | `SELECT to_regclass(...)` |
| ⑥ | 当前库内存量 v1 脏数据（`source_id IS NULL AND fetch_status='MANUAL'`）**恰有 1 行**，可直接用于 TC-REG 的"不迁移不清理"回归验证，无需额外构造 | `SELECT count(*) FROM element_daily_price WHERE source_id IS NULL AND fetch_status='MANUAL'` |
| ⑦ | 当前 Flyway 最大版本 `V358`，B1 迁移号需按实施时现取值 +1（不得预先写死） | `flyway_schema_history` |

---

## 1. 测试数据预置（可直接执行的 SQL / API）

> 全部使用 `TEST-EDPL-0724-` 前缀命名新建对象，便于测试结束后按前缀批量清理（§1.6）。
> 涉及生产客户 `CUST-1269` / 生产源 `长江有色网` 的写入，仅追加**新数据行**（不修改/不删除任何既有正式数据），且严格限定在 §1.6 清理清单覆盖的日期/元素范围内。

### 1.0 执行前核实（每次进场测试都先跑一遍，防止共享库漂移）

```sql
-- 确认 CUST-1269 × Ag 当前无残留例外（若有说明是别的会话/未清理的历史测试遗留，需先协调清理或换元素）
SELECT * FROM element_price_strategy WHERE customer_no='CUST-1269' AND element_code='Ag';
-- 确认长江有色网源下 Cu/Ag 在 2026-06 无残留数据
SELECT * FROM element_daily_price
 WHERE source_id=(SELECT id FROM element_price_source WHERE source_name='长江有色网')
   AND element_name IN ('Cu','Ag') AND price_date BETWEEN '2026-05-01' AND '2026-07-01';
```

### 1.1 新建两个测试专用价格源

```sql
INSERT INTO element_price_source (source_name, source_url, source_type, description, status) VALUES
  ('TEST-EDPL-0724-SRC',     'https://test.internal/0724',     'MANUAL', 'update-0724 手工维护测试专用源（ACTIVE）', 'ACTIVE'),
  ('TEST-EDPL-0724-SRC-DIS', 'https://test.internal/0724-dis', 'MANUAL', 'update-0724 停用源语义测试专用（DISABLED）', 'DISABLED')
ON CONFLICT ON CONSTRAINT uq_eps_name_url DO NOTHING;
```

### 1.2 取价链路前置：为 `CUST-1269` 新建 `Ag` 例外（复用 task-0722 既有策略端点，非本次开发范围）

```
POST /api/cpq/element-price/strategies/exceptions
{
  "customerNo": "CUST-1269",
  "elementCode": "Ag",
  "sourceId": "ef2402a0-adcd-4c47-b97f-eab5100de0dc",  -- 长江有色网
  "method": "AVG",
  "windowNum": 30,
  "windowUnit": "DAY",
  "factor": 1.05,
  "premium": 2.00
}
```

> 系数取非平凡值（`1.05`/`2.00`），目的是让 TC-FETCH-03/04 能真正验证"含系数加价换算后的值"（验收 7 字面要求），而不是用 `1/0` 掩盖掉换算逻辑里的 bug。

### 1.3 取价链路的价格行 —— **通过本次新 API 建**（这正是被测对象，不预先用 SQL 插入）

具体调用步骤写在 §2.4 TC-FETCH 组的"操作步骤"列里，此处仅列出将要用到的键值规划表：

| 元素 | 源 | 日期 | 单价 | fetch_status | 建立方式 |
|------|------|------|------|------|------|
| Cu | 长江有色网 | 2026-06-01 | 70.0000 | MANUAL | 新 `POST /prices`（TC-FETCH-01） |
| Cu | 长江有色网 | 2026-06-15 | 75.0000 | MANUAL | 新 `POST /prices`（TC-FETCH-01） |
| Ag | 长江有色网 | 2026-06-01 | 50.0000 | MANUAL | 新 `POST /prices`（TC-FETCH-03） |
| Ag | 长江有色网 | 2026-06-10 | 55.0000 | **IMPORT**（模拟既有导入数据，直接 SQL 插入） | TC-FETCH-03 前置 SQL |
| Ag | 长江有色网 | 2026-06-20 | 60.0000 | MANUAL | 新 `POST /prices`（TC-FETCH-03） |

```sql
-- 模拟"一条已通过价格导入产生的行"，用于证明手工价与导入价在取价时完全等价
INSERT INTO element_daily_price (element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status)
VALUES ('Ag', 'ef2402a0-adcd-4c47-b97f-eab5100de0dc', '2026-06-10', 55.0000, 'CNY', '元/kg', 'IMPORT');
```

**期望取值结果推导表**（拿到代码后用于人工复核 `f_customer_element_price` 输出，函数返回列固定为 `element_code, unit_price, currency, price_unit`）：

| 场景 | 基准日 | 元素 | rawValue（人工推导，函数不直接输出） | 期望 `unit_price` | 验证点 |
|------|------|------|------|------|------|
| TC-FETCH-01（LATEST，建价） | 2026-06-20 | Cu | 75.0000（06-15 最新） | **75.0000** | 手工建的价被正确取到 |
| TC-FETCH-02（LATEST，删后回退） | 2026-06-20 | Cu | 70.0000（06-15 被删，回退到 06-01） | **70.0000** | 删除后按剩余数据重算 |
| TC-FETCH-03（AVG，建价+系数+等价） | 2026-06-25 | Ag | (50+55+60)/3=55.0000 | 55×1.05+2=**59.7500** | MANUAL+IMPORT 混合计入无差异；系数换算正确 |
| TC-FETCH-04（AVG，删后均值变） | 2026-06-25 | Ag | (50+55)/2=52.5000（60 那条被删） | 52.5×1.05+2=**57.1250** | 均值确实随删除变化 |

### 1.4 CRUD 基础组数据（TC-CRT/TC-UPD/TC-DEL/TC-LOG/TC-HIST/TC-UI 共用）

不预先插数据——**新建本身就是被测功能**，具体键值在各用例"操作步骤"里给出，统一使用：
- 元素：`Sn`（ACTIVE，与取价链路组的 Cu/Ag 完全不同，物理隔离）
- 源：`TEST-EDPL-0724-SRC`（§1.1 新建）
- 日期：`2026-07-01` 起顺延，各用例互不冲突

仅一处例外需要预先用 SQL 构造"一条身份为 IMPORT 的存量行"，用于 TC-UPD-02（改导入行→翻 MANUAL）：

```sql
INSERT INTO element_daily_price (element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status)
VALUES ('Sn', (SELECT id FROM element_price_source WHERE source_name='TEST-EDPL-0724-SRC'),
        '2026-07-05', 145.0000, 'CNY', '元/kg', 'IMPORT');
```

### 1.5 分页测试用数据（TC-HIST-13）

若 TC-CRT~TC-DEL 各组执行下来自然产生的历史记录数不足 20+ 条，可用循环脚本对 `Sn` 元素批量建 25 条不同日期的价格再逐条删除，制造 50 条日志（25 CREATE + 25 DELETE）用于分页验证；具体脚本执行阶段按需生成，此处不预先固化 SQL。

### 1.6 测试数据清理（测试结束后执行，避免污染共享 DB）

```sql
-- 1. 删除本次为 CUST-1269 新建的 Ag 例外
DELETE FROM element_price_strategy WHERE customer_no='CUST-1269' AND element_code='Ag';

-- 2. 删除挂在生产源"长江有色网"下、本次测试写入的 Cu/Ag 价格（严格限定日期，不误伤其他数据）
DELETE FROM element_daily_price
 WHERE source_id=(SELECT id FROM element_price_source WHERE source_name='长江有色网')
   AND element_name IN ('Cu','Ag')
   AND price_date BETWEEN '2026-06-01' AND '2026-06-25';

-- 3. 删除测试专用源下的全部数据（Sn 及其他 CRUD 组测试数据）
DELETE FROM element_daily_price
 WHERE source_id IN (SELECT id FROM element_price_source WHERE source_name LIKE 'TEST-EDPL-0724-%');

-- 4. 删除对应变更历史（价格身份已不存在，price_id 无 FK，可直接按冗余键三元组清理）
DELETE FROM element_daily_price_log
 WHERE (source_id IN (SELECT id FROM element_price_source WHERE source_name LIKE 'TEST-EDPL-0724-%'))
    OR (element_name IN ('Cu','Ag')
        AND source_id=(SELECT id FROM element_price_source WHERE source_name='长江有色网')
        AND price_date BETWEEN '2026-06-01' AND '2026-06-25');

-- 5. 删除两个测试专用价格源
DELETE FROM element_price_source WHERE source_name LIKE 'TEST-EDPL-0724-%';
```

> `TEST-EL-INACT` 是现网既有的元素测试夹具（非本次新建），测试过程中**不修改**其状态，无需清理/恢复。

---

## 2. 用例列表

### 2.1 新建价格（TC-CRT）—— 对应验收 1/2/6

| 编号 | 关联验收项 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-CRT-01 | #1 | §1.1 就绪 | `POST /prices` `{elementCode:"Sn",sourceId:<TEST-EDPL-0724-SRC>,priceDate:"2026-07-01",price:145.0000,currency:"CNY",priceUnit:"kg"}` | `201`，响应 `ElementPriceRowDTO` 含**非空** `id`，`fetchStatus="MANUAL"` | API |
| TC-CRT-02 | #1 | 承 TC-CRT-01 | `SELECT * FROM element_daily_price WHERE id='<新建id>'` | 各字段与请求体逐一对应；`manually_filled_by`/`created_by`/`updated_by` 均为当前会话用户 | SQL |
| TC-CRT-03 | §4.3 规则1 | 无 | `POST /prices` 不传 `sourceId`（或传 `null`） | `400` | API |
| TC-CRT-04 | §4.3 规则1 | 无 | `POST /prices` 不传 `priceDate` | `400` | API |
| TC-CRT-05 | #6 | 无 | `POST /prices` `price:0` | `400`，"单价必须大于 0"类文案 | API |
| TC-CRT-06 | #6 | 无 | `POST /prices` `price:-10` | `400` | API |
| TC-CRT-07 | §4.3 规则8 | `TEST-EL-INACT` 现网已 INACTIVE | `POST /prices` `elementCode:"TEST-EL-INACT"` | `400`，`"元素不存在或已停用: TEST-EL-INACT"` | API |
| TC-CRT-08 | §4.3 规则8 | 无 | `POST /prices` `elementCode:"Xx99"`（全库不存在的符号） | `400`，同上文案（不存在与已停用共用同一提示） | API |
| TC-CRT-09 | §4.3 规则7 | §1.1 就绪 | `POST /prices` `sourceId:<TEST-EDPL-0724-SRC-DIS>` | `400`，`"价格源不存在或已停用"` | API |
| TC-CRT-10 | §4.3 规则7 | 无 | `POST /prices` `sourceId:<全库不存在的随机 UUID>` | `400`，同上 | API |
| TC-CRT-11 | §4.4 | 无 | `POST /prices` `currency:""` | `400` | API |
| TC-CRT-12 | §4.4 | 无 | `POST /prices` `priceUnit:""` | `400` | API |
| TC-CRT-13 | §4.4 | 无 | `POST /prices` `priceDate:"2026/07/24"`（格式非法） | `400` | API |
| TC-CRT-14 | **#2 🔴 撞键** | 承 TC-CRT-01（已存在 `Sn`+`TEST-EDPL-0724-SRC`+`2026-07-01`） | 再次 `POST /prices` 相同三元组但 `price:999.0000` | `409`，`"该元素在该源该日期已存在价格，请改用编辑"`；**`SELECT raw_price FROM element_daily_price WHERE id='<TC-CRT-01 的 id>'` 仍为 145.0000（未被覆盖）** | API+SQL |
| TC-CRT-15 | **#2 🔴** | 承 TC-CRT-14 | 前端表现：`message.error` 显示 409 文案，`PriceEditDrawer` 抽屉**不关闭** | UI 状态未跳转，字段值保留待用户改日期 | UI |
| TC-CRT-16 | 实现风险点（非需求歧义） | 承 TC-CRT-14 场景 | 构造两个并发请求同时 `POST` 相同三元组（不同单价） | 恰好 1 个 `201`，另 1 个应转为 `409` 而非 `500`（依赖 DB 唯一索引 `uq_element_daily` 兜底，捕获 `PSQLException` `23505`） | API（并发脚本，低优先级/进阶） |
| TC-CRT-17 | U11 反向 | 无 | 检查是否存在"批量新建"入口（一次提交多行） | **不存在**；`POST /prices` 只接受单条请求体，无批量端点/无 Excel 式多行录入 UI | API+UI 走查 |

### 2.2 修改价格（TC-UPD）—— 对应验收 3/4/6

| 编号 | 关联验收项 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-UPD-01 | 通用 | 承 TC-CRT-01（MANUAL 行） | `PUT /prices/{id}` `{price:150.0000,currency:"CNY",priceUnit:"kg"}` | `200`，响应 `price=150.0000`；`fetch_status` 仍为 `MANUAL` | API+SQL |
| TC-UPD-02 | **#3 🔴 核心** | 承 §1.4（一条 `fetch_status='IMPORT'` 的 `Sn@2026-07-05` 行） | `PUT /prices/{id}` 改单价为 `160.0000` | `200`；**`SELECT fetch_status, raw_price FROM element_daily_price WHERE id='<该行>'` → `fetch_status='MANUAL'`，`raw_price=160.0000`**（无条件翻转，含原本 IMPORT 的行） | SQL 直查 |
| TC-UPD-03 | #6 | 承 TC-UPD-01 | `PUT` `price:0` | `400` | API |
| TC-UPD-04 | #6 | 同上 | `PUT` `price:-5` | `400` | API |
| TC-UPD-05 | §4.4 | 同上 | `PUT` `currency:""` | `400` | API |
| TC-UPD-06 | §4.4 | 同上 | `PUT` `priceUnit:""` | `400` | API |
| TC-UPD-07 | 通用 | 无 | `PUT /prices/{随机不存在的UUID}` | `404`，`"价格记录不存在: {id}"` | API |
| TC-UPD-08 | **#4 🔴 键锁定** | 承 TC-UPD-01 | 构造请求体强行携带 `elementCode:"Cu",sourceId:<其他源>,priceDate:"2020-01-01"` 连同合法的 `price/currency/priceUnit` | `200`（不因未知字段报错）；响应 DTO 与 `SELECT` 直查该行的 `elementCode/sourceId/priceDate` **均为原值**（`Sn`/`TEST-EDPL-0724-SRC`/`2026-07-01`），未被篡改 | API+SQL |
| TC-UPD-09 | **#4 🔴** | 代码走查 | 检查 `UpdatePriceRequest.java` 源码 | **不存在** `elementCode`/`sourceId`/`priceDate` 三个字段声明（不是"声明后忽略"，是根本不存在） | 代码走查 |
| TC-UPD-10 | 通用 | 承 TC-UPD-01 | 对比修改前后 `updated_at`/`updated_by` | `updated_at` 变新，`updated_by` 为当前会话用户 | SQL |
| TC-UPD-11 | #4（UI） | 承 TC-CRT-01 | 打开该行的编辑抽屉 | `元素`/`价格源`/`价格日期` 三个字段渲染为**置灰只读**控件，无法交互修改；`单价`/`货币`/`计价单位` 可编辑 | UI |
| TC-UPD-12 | Jackson 实现风险 | 同 TC-UPD-08 场景 | 观察 Quarkus 是否因 `FAIL_ON_UNKNOWN_PROPERTIES` 而对多余字段抛 `400`（而非静默忽略） | 若出现 `400 Unrecognized field`，与 api.md §2 预期矛盾，判定为**实现缺陷**（应静默丢弃不报错） | API（响应体检查，非通过/失败判定用的字段值，而是判定是否报错） |

### 2.3 删除价格（TC-DEL）—— 对应验收 5

| 编号 | 关联验收项 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-DEL-01 | #5 | 承 TC-CRT-01（MANUAL 行） | `DELETE /prices/{id}` | `204`；`GET /prices?keyword=Sn` 列表**不再出现**该行；`SELECT * FROM element_daily_price WHERE id='<id>'` → 0 行 | API+SQL |
| TC-DEL-02 | 通用 | 承 §1.4（IMPORT 行） | `DELETE /prices/{id}` | `204`（删除不区分 `fetch_status`） | API |
| TC-DEL-03 | 通用 | 无 | `DELETE /prices/{随机不存在的UUID}` | `404` | API |
| TC-DEL-04 | 幂等性负面 | 承 TC-DEL-01 | 对已删除的同一 `id` 再次 `DELETE` | `404`（非 `204`，非幂等静默成功） | API |
| TC-DEL-05 | #5（UI） | 新建至少 1 条待删记录 | 明细 Tab 勾选 1 行 → 点「删除」 | Modal 弹出，列出所选 1 项（格式 `{elementCode} · {sourceName} · {priceDate}`），二次确认；点「取消」后该行仍存在 | UI |
| TC-DEL-06 | #5（UI） | 新建至少 2 条待删记录 | 勾选多行 → 删除 → Modal 确认 | 逐行调用 `DELETE`（`runBatch`），全部成功后 `message.success` 显示"已删除 N 条"，列表刷新后均消失 | UI |
| TC-DEL-07 | 批量部分失败 🔴 | 新建 3 条，其中 1 条提前手工物理删除（模拟并发） | 勾选该 3 行 → 删除 | `runBatch` 聚合：`2` 条成功 `1` 条失败（`404`）；`message.error` 列出失败明细（哪一条+原因）；已成功的 2 条**不回滚**，列表刷新后只剩失败的那 1 条（若其实已不存在则也不再出现） | UI |
| TC-DEL-08 | #9 交叉 | 承 TC-DEL-01 | 检查 `element_daily_price_log` | 新增 1 条 `action='DELETE'` 记录（交叉验证见 TC-LOG-03） | SQL |
| TC-DEL-09 | U10/U11#3 反向 | 承任意删除操作 | 检查删除前的确认 Modal / 删除后的提示 | **无**"该价格已被 N 张报价单引用"之类的引用计数提示（U10/U11 明确不做） | UI 走查 |

### 2.4 取价链路 —— 手工价与导入价等价性（TC-FETCH）🔴 本次核心验收组

> 验证方式统一为：**SQL 直查** `SELECT * FROM f_customer_element_price('CUST-1269','<基准日>') WHERE element_code='<元素>'`（函数固定返回 `element_code, unit_price, currency, price_unit` 四列，无独立的 rawValue 列，中间值需人工按 §1.3 推导表核对）。

| 编号 | 关联验收项 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-FETCH-01 | **#7 🔴** | §0.4①②确认干净；`CUST-1269` 默认策略就绪 | 通过新 `POST /prices` 建 `Cu@长江有色网@2026-06-01=70.0000`、`Cu@长江有色网@2026-06-15=75.0000`（均 MANUAL） | 均 `201` | API |
| TC-FETCH-02 | **#7 🔴 核心** | 承 TC-FETCH-01 | `SELECT * FROM f_customer_element_price('CUST-1269','2026-06-20')` 中 `Cu` 行 | `unit_price=75.0000`（取到刚手工建的最新价，证明手工价能被取价引擎正确取到，等价于导入价） | SQL |
| TC-FETCH-03 | **#8 🔴 核心** | 承 TC-FETCH-02 | `DELETE` 掉 `Cu@2026-06-15`（id） → 再次执行同一 SQL | `unit_price` 回退为 **70.0000**（LATEST 口径按剩余数据重算，`06-01` 那条） | API+SQL |
| TC-FETCH-04 | **#7 🔴（系数换算 + 手工/导入等价）** | 承 §1.2（`CUST-1269`×`Ag` 例外 AVG/30DAY/1.05/2.00 已建）；承 §1.3 表（`Ag@06-01`=50 MANUAL 走新 API 建，`Ag@06-10`=55 **IMPORT** 直接 SQL 插入模拟既有导入数据，`Ag@06-20`=60 MANUAL 走新 API 建） | `SELECT * FROM f_customer_element_price('CUST-1269','2026-06-25')` 中 `Ag` 行 | `unit_price=59.7500`（=AVG(50,55,60)=55×1.05+2）；**MANUAL 与 IMPORT 两种 `fetch_status` 的行被同等计入均值，无优先级/过滤差异**，证明手工价与导入价完全等价 | SQL |
| TC-FETCH-05 | **#8 🔴 核心** | 承 TC-FETCH-04 | `DELETE` 掉 `Ag@2026-06-20`（60.0000，走新 `DELETE` API） → 再次执行同一 SQL | `unit_price` 变为 **57.1250**（=AVG(50,55)=52.5×1.05+2，均值确实随删除变化） | API+SQL |
| TC-FETCH-06 | #4.3 规则9 佐证 | 代码走查 | 检视 `f_customer_element_price` 函数体（技术总监已核实 U0①） | 确认 SQL 中**没有**任何 `fetch_status` 相关 `WHERE` 条件，交叉印证 TC-FETCH-04 的等价性结果并非巧合而是必然 | 代码/`pg_get_functiondef` 走查 |

### 2.5 留痕（变更历史写入 + 事务原子性）（TC-LOG）—— 对应验收 9/11

| 编号 | 关联验收项 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-LOG-01 | #9 | 新建一条价（如 TC-CRT-01） | `SELECT * FROM element_daily_price_log WHERE price_id='<新建id>'` | 新增 **1** 条，`action='CREATE'`；`element_name`/`source_id`/`price_date` 与主表一致；`snapshot` 含 `price`/`currency`/`priceUnit`/`fetchStatus='MANUAL'`；`changed_by`/`changed_by_name` 为当前用户 | SQL |
| TC-LOG-02 | #9 | 修改一条价（如 TC-UPD-01） | 同上查询 | 新增 **1** 条 `action='UPDATE'`，`snapshot` 为**变更后**的完整值（非变更前） | SQL |
| TC-LOG-03 | **#9 🔴** | 删除一条价（如 TC-DEL-01） | `SELECT * FROM element_daily_price_log WHERE price_id='<已删除的id>'` | 新增 **1** 条 `action='DELETE'`；**`snapshot` 为删除前的完整值**（该行此刻在 `element_daily_price` 中已不存在，只能从日志 `snapshot` 还原）；`price_id` 仍冗余记录原 id，查询**不因原行已删除而报错**（无 FK） | SQL |
| TC-LOG-04 | #10（changes 只列变化字段） | 承 TC-UPD-01（只改了 `price`，`currency`/`priceUnit` 未变） | `GET /prices/history` 对应记录 | `changes` 数组**只含 1 项**（`field="price"`），不出现 `currency`/`priceUnit` | API |
| TC-LOG-05 | 键三元组冗余存储 | 承 TC-LOG-03 | 查看该 `DELETE` 日志行 | `element_name`/`source_id`/`price_date` 三字段完整（即使原表行已物理删除，仍可据此定位是哪一条价被删了） | SQL |
| TC-LOG-06 | 表设计约束 | 无 | `\d element_daily_price_log` | `price_id` 列**无** `FOREIGN KEY` 约束（否则会阻止 `DELETE` 或级联删除日志，两者都违背留痕目的） | SQL DDL 走查 |
| TC-LOG-07 | **#11 🔴 事务原子性（正向）** | 构造历史写入失败场景（如临时给 `snapshot` 塞入触发某约束的超长/非法值，或在 `writeLog` 内注入异常做白盒测试） | 执行一次 `PUT`/`POST`/`DELETE` | **价格表该行值不变**（若是 UPDATE/DELETE，原值原样保留；若是 CREATE，该行**未被插入**）；`element_daily_price_log` **无**对应新记录；整个操作应向调用方返回 `5xx` 或明确错误，而不是"部分成功" | SQL 前后对比+API 响应 |
| TC-LOG-08 | **#11 🔴 反向诊断** | 同上 | 若观察到价格值已变但 `element_daily_price_log` 无对应新记录（或反之：日志有记录但价格值未变） | 该状态**不允许出现**——一旦出现即判定事务原子性失败（价格写入与日志写入未在同一事务内） | SQL 前后对比 |
| TC-LOG-09 | 操作人正确性 | 以 `bob`(SALES_MANAGER) 身份操作 | 新建/修改/删除各一次 | 对应日志的 `changed_by` = `bob` 的 `id`，`changed_by_name` = `bob` 的姓名 | SQL |
| TC-LOG-10 | 索引验证 | 无 | `\d element_daily_price_log` | 存在 `idx_edpl_target (element_name, COALESCE(source_id::text,''), price_date, changed_at DESC)` 与 `idx_edpl_time (changed_at DESC)` 两个索引 | SQL DDL 走查 |

### 2.6 变更历史 Tab（TC-HIST）—— 对应验收 10

| 编号 | 关联验收项 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-HIST-01 | #10 | 已有若干历史记录（TC-CRT/TC-UPD/TC-DEL 执行后） | `GET /prices/history?page=0&size=20` | `200`，`PageResult<PriceHistoryDTO>` 结构（`content`/`page`/`size`/`totalElements`） | API |
| TC-HIST-02 | #10 | 同上 | `GET /prices/history?sourceId=<TEST-EDPL-0724-SRC>` | 只返回该源相关记录（不含 `长江有色网` 源的 Cu/Ag 记录） | API |
| TC-HIST-03 | #10 | 同上 | `GET /prices/history?keyword=Sn` | 只返回元素符号或中文名（"锡"）匹配 `Sn` 的记录 | API |
| TC-HIST-04 | #10（changed_at 非 price_date） | 构造一条 `priceDate=2020-01-01` 的价格（走 `POST /prices`，`changed_at` 即为**当前执行时刻**） | `GET /prices/history?from=<今天>&to=<今天>` | 该记录**出现**在结果里（因为 `changed_at` 是今天），证明过滤的是**变更时间**而非价格日期 | API |
| TC-HIST-05 | #10 边界 | 同上 | `from=to=<某天>` | 含当天 `00:00~23:59:59` 的记录都被含入（`changed_at < to+1day`，不用 `<=to`），不漏掉当天较晚时刻的记录 | API |
| TC-HIST-06 | #10（changes 只列变化字段） | 承 TC-LOG-04 | `GET /prices/history` 对应 `UPDATE` 记录 | `changes` 长度 = 1，与 TC-LOG-04 交叉一致 | API |
| TC-HIST-07 | #10 | 承 TC-CRT-01（CREATE）+ TC-DEL-01（DELETE） | 查看对应记录 | `CREATE`/`DELETE` 记录 `changes=[]`；`snapshot` 非空可用于全量展示 | API |
| TC-HIST-08 | #9 交叉 | 承 TC-DEL-01 | 查看 `DELETE` 记录的 `snapshot` | 与 TC-LOG-03 一致（删除前完整值） | API |
| TC-HIST-09 | #10 | 任意记录 | 查看 `targetLabel` | 格式 `{元素符号} {中文名} · {源名} · {价格日期}`（如 `Sn 锡 · TEST-EDPL-0724-SRC · 2026-07-01`） | API |
| TC-HIST-10 | 只读 | 无 | 尝试 `POST`/`PUT`/`DELETE` `/prices/history` | 均不存在（`404`/`405`），接口只读 | API |
| TC-HIST-11 | U11#2 反向 | 打开「变更历史」Tab | 查看整个 Tab | **无**任何"回滚到此版本"按钮/入口，纯只读展示 | UI 走查 |
| TC-HIST-12 | U6（复用筛选） | 打开「变更历史」Tab | 对比筛选控件与明细 Tab | 三个控件（源/日期区间/元素）复用同一套组件；日期标签文案为**"变更时间"**而非"日期区间"（区分与明细 Tab 的语义差异） | UI |
| TC-HIST-13 | 分页 | 构造 ≥25 条历史记录（§1.5） | `GET /prices/history`（默认）及 `size=200`/`size=300` | 默认 `size=20` 生效分页正确；`size=300`（超上限 200）按 §4 裁决**截断为 200**（返回 ≤200 条 + HTTP `200`，非 `400`） | API |
| TC-HIST-14 | **B5 前序窗口外 🔴（技术总监补测）** | 同一价格身份构造两条日志：先 `POST` 建价（CREATE，`changed_at=T1`），隔一会再 `PUT` 改单价（UPDATE，`changed_at=T2`） | `GET /prices/history?from=<T1 与 T2 之间某刻>&to=<今天>` 使 CREATE 落在筛选窗口外、UPDATE 落在窗口内 | UPDATE 记录的 `changes` **正确算出**（`price` 旧→新），而非因前序 CREATE 的 snapshot 被 `changed_at` 过滤掉而误判为"首条"→`changes` 算成空/全量。验证 backtask B5 第二步"故意不带 `changed_at` 过滤取完整时间线"确实生效 | API |

### 2.7 v1 元素价格中心下线彻底性（TC-V1）—— 对应验收 12/13

| 编号 | 关联验收项 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-V1-01 | #12 | 无 | 浏览器直接访问路由 `/element-price-center` | 不可达（404 页面 / 重定向到首页，具体表现待实测确认） | UI |
| TC-V1-02 | #13 | 无 | `GET /api/cpq/element-prices/reference` | `404` | API |
| TC-V1-03 | #13 | 无 | `GET /api/cpq/element-prices/history` | `404` | API |
| TC-V1-04 | #13 | 无 | `POST /api/cpq/element-prices/manual` | `404` | API |
| TC-V1-05 | #13 | 无 | `GET /api/cpq/element-prices/available-elements` | `404` | API |
| TC-V1-06 | **#12 🔴** | 无 | `/usr/bin/grep -a -rn "ElementPriceCenterPage" cpq-frontend/src` | **0 命中** | grep（必须用 `/usr/bin/grep -a`，本机 `grep` 是 `ugrep` 会误判二进制返空） |
| TC-V1-07 | **#12 🔴** | 无 | `/usr/bin/grep -a -rn "ManualPriceEntryDrawer" cpq-frontend/src` | **0 命中** | grep |
| TC-V1-08 | **#12 🔴（最容易漏，休眠代码风险最高）** | 无 | `/usr/bin/grep -a -rn "ElementPriceHint" cpq-frontend/src` | **0 命中**（含 `QuotationStep2.tsx` 的 `import`/判定分支/渲染分支三处均已摘除） | grep |
| TC-V1-09 | #12 | 无 | `/usr/bin/grep -a -rn "element-price-center" cpq-frontend/src` | **0 命中**（路由项 + 对应 import 均已删除） | grep |
| TC-V1-10 | **精确区分单复数 🔴** | 无 | `/usr/bin/grep -a -rn "element-prices" cpq-backend/src cpq-frontend/src`（复数） | **0 命中** | grep |
| TC-V1-11 | **精确区分单复数 🔴（防误伤）** | 无 | 对比改动前后：`GET /element-price/latest-by-source`、`GET /element-price/prices`、`GET /element-price/prices/matrix`、`POST /element-price/import`、`GET /element-price/import-template`（单数，均为活端点） | 均**未被误删**——除新增鉴权/参数校验导致的 400/401 外，不应出现 404（下线操作未波及单数命名空间） | API 对比 |
| TC-V1-12 | #12 | 无 | `elementPriceService.ts` 检查 | `getReference`/`listHistory`/`upsertManual`/`listAvailableElements` 四个方法已删除；若文件内容为空则整个文件已删除 | grep+代码走查 |
| TC-V1-13 | §6（不迁移不清理） | §0.4⑥（现网已有 1 行 v1 脏数据 `source_id IS NULL AND fetch_status='MANUAL'`） | 对比改动前后 `SELECT count(*) FROM element_daily_price WHERE source_id IS NULL AND fetch_status='MANUAL'` | 数量**不变**（未被本次改动物理清理或迁移） | SQL 前后对比 |

### 2.8 权限（TC-PERM）—— 对应需求 §7、api.md §0.3

| 编号 | 角色/凭据 | 操作 | 预期结果 |
|------|------|------|------|
| TC-PERM-01 | 无 `Authorization` | `POST /prices` | `401` |
| TC-PERM-02 | `alice`(SALES_REP) | `POST /prices` | `403`（与 task-0722 策略端点行为**形成对照**：策略端点 SALES_REP 有写权限，本次 4 个新端点**未继承**该权限组，是独立的类级 `@RoleAllowed`） |
| TC-PERM-03 | `alice`(SALES_REP) | `PUT /prices/{id}` | `403` |
| TC-PERM-04 | `alice`(SALES_REP) | `DELETE /prices/{id}` | `403` |
| TC-PERM-05 | `alice`(SALES_REP) | `GET /prices/history` | `403` |
| TC-PERM-06 | `bob`(SALES_MANAGER) | 4 个新端点全部 | `201`/`200`/`204`/`200`（正常放行） |
| TC-PERM-07 | `test_finance_fd726739`(PRICING_MANAGER) | 4 个新端点全部 | 同上，正常放行 |
| TC-PERM-08 | `admin`(SYSTEM_ADMIN) | 4 个新端点全部 | 同上，正常放行 |
| TC-PERM-09 | 代码走查 | — | 对比 `PriceTableResource` 类级 `@RoleAllowed` 与 `PriceImportResource` 类级 `@RoleAllowed` | 字符串**逐字一致**（`SALES_MANAGER`/`PRICING_MANAGER`/`SYSTEM_ADMIN`）；4 个新端点方法级**无**独立 `@RoleAllowed` 覆盖（确认"不新增权限点"，直接继承类级） |

### 2.9 无回归（TC-REG）—— 对应验收 16 + 一般回归纪律

| 编号 | 关联验收项 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|
| TC-REG-01 | **#16 🔴** | 跑 `quotation-flow.spec.ts`，与干净 `master` 做 **A/B 同型对比**（先在干净 master 跑一次记录基线，再在本分支跑，逐条比对失败用例名） | **新增失败数 = 0**（母分支已知恒 3 失败，见记忆 `task0712-update071501-category-axis`，不得误归因也不得掩盖新增失败）；同时 `'加载中' final count = 0` | Playwright，A/B 同型对比 |
| TC-REG-02 | U9 | 打开任意元素编辑抽屉的「各源最新价格」区块 | 仍为**只读**（无编辑入口），仅空态文案从"请通过『价格导入』录入"改为指向元素价格表可手工维护 | UI |
| TC-REG-03 | 回归 | 对比改动前后 `GET /element-price/latest-by-source?elementCode=Sn` | 行为/返回结构与 task-0722 交付时一致，未受本次改动影响 | API 对比 |
| TC-REG-04 | 回归 | 走一次完整的「价格导入」流程（task-0722 既有功能，`POST /element-price/import`） | 功能正常，覆盖语义（重导覆盖）不受本次改动影响 | API |
| TC-REG-05 | 回归 | 打开报价单 Step2 其余非元素相关 Tab（投料/回料/加工等） | 渲染与改动前逐位一致，无异常（本次仅摘除 `ElementPriceHint` 死分支，不动其余渲染逻辑） | UI 对比截图 |
| TC-REG-06 | 回归 | 使用 `CUST-1269` 打开一张既有报价单，查看其元素页签取价 | 单价仍按 task-0722 原有链路正常取到（本次改动未修改 `f_customer_element_price`），行为与改动前一致 | UI+SQL |
| TC-REG-07 | §6 待观察 | §0.4⑥（现网 1 行 v1 脏数据，`price_date` 需确认是否落入默认 30 天窗口） | `GET /prices`（不传 `from/to`，默认最近 30 天）检查该脏数据行是否出现在明细列表 | 若出现，需回头核对 §6"不展示于新入口"的实际落地口径（详见文末待澄清项 1） | API |
| TC-REG-08 | 代码走查 | `git diff --stat <base>...<feature-branch>` | 圈定实际改动文件清单，确认**仅** `QuotationStep2.tsx` 一处协议级文件被动（且只删除死分支），未意外波及 `useDriverExpansions.ts`/`usePathFormulaCache.ts`/`ReadonlyProductCard.tsx` 等其他协议级文件 | `git diff --stat` |

### 2.10 列表操作规范（TC-UI）—— 对应 `docs/列表操作规范.md` 强制项

| 编号 | 关联验收项 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-UI-01 | 规范强制 | 无 | 打开明细 Tab | 顶部工具栏 `[＋新建][✎编辑][🗑删除]` 三个按钮；**行内无任何操作按钮** | UI |
| TC-UI-02 | 规范强制 🔴 | 不勾选任何行 | 查看「编辑」「删除」按钮状态 | **禁用态但可见**（非隐藏），hover 显示原因：编辑→"编辑一次只能选一行"，删除→"请先勾选要删除的价格" | UI |
| TC-UI-03 | 规范强制 | 勾选 2 行 | 查看「编辑」按钮 | 禁用，hover 显示"编辑一次只能选一行" | UI |
| TC-UI-04 | 规范强制 | 勾选 1 行 | 查看「编辑」按钮 | 启用，点击打开 `PriceEditDrawer` 编辑态（键字段置灰） | UI |
| TC-UI-05 | 规范强制 | 勾选 ≥1 行 | 查看「删除」按钮 | 启用，点击弹出 Modal 列出所选项（格式 `{elementCode} · {sourceName} · {priceDate}`）二次确认 | UI |
| TC-UI-06 | 规范强制 | 无 | 「＋新建」按钮 | `enabledWhen` 恒为 `true`，不依赖选中行，未选中任何行时也可点击 | UI |
| TC-UI-07 | §13 缺口2 修复验证 | 构造 >1 页数据（size 较小或数据量较大） | `rowKey` 已改为 `id`（非组合键）；勾选第 1 页某行 → 翻到第 2 页再翻回 | 该行**仍勾选**（跨页保留选中不因 `id` 唯一而串行/丢失） | UI |
| TC-UI-08 | §13 缺口2 | 打开明细 Tab | 查看新增的「数据来源」列 | `fetchStatus='MANUAL'` → 蓝色「手工」Tag；`='IMPORT'` → 灰色「导入」Tag；其余值原样展示 | UI |
| TC-UI-09 | 危险动作确认 | 勾选≥1行点删除，Modal 弹出后点「取消」 | 无任何行被删除，列表不变 | UI |

---

## 3. 验收标准映射表（§8 16 条 → 用例编号，逐条无遗漏）

| # | 验收要点摘要 | 覆盖用例 |
|---|------|------|
| 1 | 新建一条价 → 列表出现，`fetch_status='MANUAL'` | TC-CRT-01/02 |
| 2 | 同键再建 → `409`，不覆盖原值 | TC-CRT-14/15 |
| 3 | 改导入行单价 → SQL 直查 `fetch_status='MANUAL'` | TC-UPD-02 |
| 4 | 编辑抽屉键字段置灰；强传键字段被忽略 | TC-UPD-08/09/11 |
| 5 | 删除 → Modal 二次确认 → 删除成功 | TC-DEL-01/05/06 |
| 6 | 单价 0/负数拒绝 | TC-CRT-05/06、TC-UPD-03/04 |
| 7 | 手工新建的价被 `f_customer_element_price` 取到（含系数换算） | TC-FETCH-01/02/04 |
| 8 | 删除窗口内一条 → 按剩余重算 | TC-FETCH-03/05 |
| 9 | 三种动作各产生 1 条日志；DELETE 的 snapshot 为删除前值 | TC-LOG-01/02/03 |
| 10 | 变更历史 Tab 按源/日期区间/元素筛选；UPDATE 只列变化字段 | TC-HIST-02/03/04/06 |
| 11 | 事务原子性 | TC-LOG-07/08 |
| 12 | 路由不可达 + grep 0 残留 | TC-V1-01/06/07/08/09 |
| 13 | 下线 4 个 v1 端点返回 404 | TC-V1-02/03/04/05 |
| 14 | 前端 `tsc` 0 错误、Vite 200（工程自检，非功能用例） | 见 §5 说明，不重复设计用例 |
| 15 | 后端 Quarkus 重启无异常、Flyway success（工程自检） | 见 §5 说明，不重复设计用例 |
| 16 | E2E 强制项，A/B 同型对比新增失败 = 0 | TC-REG-01 |

**补充覆盖**（非 §8 直接编号，但属本轮测试重心）：
- 权限矩阵（§7、api.md §0.3） → TC-PERM-01~09
- 列表操作规范（`docs/列表操作规范.md`） → TC-UI-01~09
- U10/U11 明确不做清单反向验证 → TC-DEL-09、TC-CRT-17、TC-HIST-11
- v1 单/复数命名空间精确区分（防误伤活端点） → TC-V1-10/11

---

## 4. 待澄清项 —— 技术总监已裁决（2026-07-23）

> 以下 2 项为测试工程师提出的需求歧义，技术总监审核测试用例时一并裁决，作为验收口径（进技术总监亲验后端的核对清单）。

1. **§6"不展示于新入口"的落地方式** —— 【裁决：显式过滤，不靠巧合】
   `PriceTableService.listDetail`（明细 Tab 查询）应加 `WHERE edp.source_id IS NOT NULL`，让存量 v1 脏数据（`source_id IS NULL` 的 MANUAL 行）**结构性不出现**在新入口，而非依赖"其 `price_date` 恰好落在默认窗口之外"的巧合。理由：需求 §6 明确要求"不展示"，且一条没有源的价出现在明细列表会让用户困惑。
   → **验收核对**：TC-REG-07 必须验证该脏数据行**不出现**在 `GET /prices`（即使不传 `from/to`、即使其 `price_date` 落入窗口）；若后端 `listDetail` 未加此过滤，判为**未达标，要求后端补**。

2. **`GET /prices/history` 的 `size` 上限 200 越界处理** —— 【裁决：截断为 200，不报 400】
   传 `size>200` 时后端取 `min(size,200)`（分页保护，截断比拒绝友好）。
   → **验收核对**：TC-HIST-13 验证 `size=300` 返回 ≤200 条且 HTTP `200`，不是 `400`。

3. **（技术总监补测）Jackson `FAIL_ON_UNKNOWN_PROPERTIES` 实际行为** —— 见 §5#1 + TC-UPD-08/12。
   api.md 断言"多传键字段被 Jackson 直接丢弃"依赖 Quarkus 默认关闭该 feature。技术总监亲验后端时**必查** `application.properties` 无 `quarkus.jackson.fail-on-unknown-properties=true`，并实测 TC-UPD-08 返 `200` 而非 `400 Unrecognized field`。

---

## 5. 容易被漏测的细节点清单（供技术总监审核参考）

1. **Jackson `FAIL_ON_UNKNOWN_PROPERTIES` 假设未经工程实测验证**（TC-UPD-08/12）——api.md/backtask.md 断言"`UpdatePriceRequest` 请求体多传键字段会被 Jackson 直接丢弃"，这个假设建立在"Quarkus REST Jackson 默认关闭该 feature"上，但本文档走查未在 `application.properties` 中找到显式配置。若实际抛出 `400 Unrecognized field`，会让 TC-UPD-08 直接失败且现象具有迷惑性（看起来像"键锁定校验过严"，实际是 JSON 反序列化配置问题），必须优先验证。
2. **撞键的"先查后插"竞态窗口**（TC-CRT-16）——api.md 明确要求兜底捕获 `PSQLException 23505` 转 409，但这是最容易被开发者遗漏的"防御性代码"（正常路径测试永远测不出来，只有并发脚本能测出来），且一旦漏写，并发场景下会直接抛 500 而非预期的 409。
3. **`writeLog` 与价格写入的事务边界到底包多大**（TC-LOG-07/08）——三个方法（create/update/delete）各自 `@Transactional`，但如果 `writeLog` 被误写成新开一个 `REQUIRES_NEW` 事务（哪怕只是为了"日志失败不影响主流程"这种看似合理的防御性设计），就会从根本上违反验收 11 的"同事务"要求，且这个错误在功能测试里完全测不出来（两边都能各自成功写入），只有专门构造失败场景才能揪出来。
4. **`PriceTableResource` 新端点是否被漏加了方法级 `@RoleAllowed`**（TC-PERM-09）——如果开发图省事直接复制了 `StrategyResource` 的写法（包含额外方法级注解覆盖类级权限），会导致 SALES_REP 意外获得写权限，与"不新增权限点"的裁决矛盾，且功能测试（用高权限账号）测不出来，必须专门用低权限账号交叉验证。
5. **`element_daily_price_log` 建表在 `element_daily_price` 现网已有大量存量数据的情况下的迁移顺序**——虽然 B1 只是建一张新表不涉及存量数据迁移，但要确认迁移脚本不会因为"表已存在"之类的幂等性问题在共享库反复执行时报错（共享库有多个并发 worktree，Flyway 版本号是移动靶）。
6. **`GET /prices/history` 的候选集收敛 SQL（backtask B5）两步查询的正确性**——第二步"取完整时间线"故意不带 `changed_at` 过滤，如果实现时漏掉这个"故意"、直接在两步都加了时间过滤，会导致某条 `UPDATE` 记录因为找不到窗口外的前序 snapshot 而 `changes` 算错（把"没找到前序"误判为"这是第一条记录"，从而把 `changes` 算成空而不是正确的字段级 diff）。这类 bug 用 TC-HIST-04（`changed_at` 边界）能部分覆盖，但更精确的验证需要专门构造"前序记录在筛选窗口外、当前记录在窗口内"的场景（本文档 TC-HIST 组未单独设计这一条，建议实测阶段补充）。
7. **`ElementPriceRowDTO` 的 SELECT 列位移风险**（backtask B3 明确警告过）——`id`/`fetchStatus` 两列必须追加在现有 SELECT **末尾**，若开发在中间插入会导致后续所有列整体错位且**不报编译错**（`mapDetailRow` 是按下标取 `Object[]`），必须在 TC-CRT-01 验证响应字段时同时抽查其余 9 个既有字段（`elementCode`/`elementName`/`priceDate`/`sourceName`/`sourceStatus`/`price`/`currency`/`priceUnit`/`operatorName`/`updatedAt`）是否仍然对应正确，而不是只看新加的两个字段。
8. **v1 下线的"先前端后后端"配对顺序**（fronttask F5 明确要求）——如果实际合并顺序反了（先删后端端点再删前端入口），会在两次部署之间出现"按钮还在但端点已 404"的短暂中间态；虽然功能测试很难覆盖"部署时序"这种问题，但至少应在 PR 描述里确认两侧改动是否在同一次合并中一起生效。

---

## 6. 风险最高、最该重点测试的点（Top 5）

1. **🔴 取价链路系数换算正确性（TC-FETCH-04/05）**——如果只用 `factor=1/premium=0` 的策略验证"手工价能被取到"，完全无法暴露"手工建价路径漏乘系数/漏加加价"这类 bug（因为 1×x+0=x，怎么算都对）。本文档专门用 `1.05/2.00` 的非平凡系数设计判别数据，是本轮测试最不能省的部分。
2. **🔴 撞键 409 是拒绝而不是静默覆盖（TC-CRT-14）**——如果实现时图省事复用了导入侧 `PriceImportRowWriter` 的 `INSERT ... ON CONFLICT DO UPDATE` 语义，会让原本该报错的重复新建变成静默覆盖，用户完全不会意识到自己的数据被覆盖了，且这类 bug 在功能测试里"看起来一切正常"（请求确实成功了），必须专门 SQL 直查原值确认未变。
3. **🔴 键锁定的"结构性不可能" vs "运行时忽略"（TC-UPD-08/09）**——api.md 明确要求 `UpdatePriceRequest` DTO **根本不声明**键字段，而不是"声明了但代码里不用"。如果开发偷懒声明了这三个字段只是没在 `update` 方法里用它们，未来任何一次重构都可能不小心把它们接上，键锁定就从编译期保证退化为"人肉记得别用"，必须做代码走查而非只测行为。
4. **🔴 事务原子性的"看似都做对了但边界包错"（TC-LOG-07/08）**——价格写入和日志写入各自都能正常工作，只有在"一方失败"时才能暴露事务边界问题，而这种场景在常规功能测试的 happy path 里永远不会触发，必须专门构造失败注入。
5. **🔴 `ElementPriceHint` 休眠代码复活风险（TC-V1-08）**——这是唯一一类"当前测试通过≠未来安全"的风险：如果代码没有被彻底删除（只是被绕过/永久 false 分支），一旦未来 `MANUAL` 行大量增加、且有人无意中给某个组件加了名为 `element_name` 的字段，这段代码会突然生效并显示跨源混取的错误价格，且没有任何测试能提前预警——唯一的防线就是本次交付时确认它被物理删除而非条件性禁用。
