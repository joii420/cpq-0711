# 测试用例 · 元素单价维护与价格策略（task-0722）

> **权威依据**：`需求说明.md` §11（尤其 §11.21 验收要点 16 条 + §11.24 交付物对照矩阵）、`api.md`、`元素价格策略-原型图.html`（v9 定稿）。
> **状态**：本文档仅做**用例设计**，尚未执行（前后端并行开发中，代码未就绪）。所有「实际结果」「通过/失败」列留空，待技术总监审核通过后进场实测再回填。
> **测试库**：`10.177.152.12:5432/cpq_db`（`postgres`/`joii5231`）；后端 `localhost:8081`；前端 `localhost:5174`；登录 `admin`/`Admin@2026`。
> **编写人**：cpq-tester　**编写日期**：2026-07-22

---

## 0. 概述

### 0.1 覆盖范围
- 价格源管理 CRUD + 停用三态语义
- 价格导入（模板/部分成功/覆盖/校验）
- 价格表查询（明细 + 矩阵 + 导出）
- 元素侧增强（各源最新价 / 最后修改时间口径）
- 客户元素价格策略（默认 + 例外两层，`_GLOBAL_` 核价口径）
- **取价计算引擎正确性**（四种取值方式 / 滚动窗口边界 / 先乘后加 / 例外覆盖默认）— 本次测试重心
- 报价单 / 核价单取价集成（自动带出、可覆盖、留空兜底）
- **跨报价单串价专项**（🔴 最高风险）
- 策略试算、变更历史
- 权限矩阵、边界异常
- 反向用例（确认"明确不交付"清单确实没做）
- 无回归

### 0.2 风险点速览（详见文末"风险最高的测试点"）
1. `f_customer_element_price` 窗口/取值方式/先乘后加算错 —— 直接体现在报价金额上，业务无感知，最隐蔽
2. `:priceBaseDate` 缓存维度缺失 → 跨报价单串价（阻断级）
3. 停用价格源的第三条语义（存量策略仍能取价）最容易被误实现为"停用即全面失效"
4. 导入"部分成功"被误实现为整批回滚
5. `element.updated_at` 被价格导入误反写（污染主档语义）
6. 核价 `_GLOBAL_` 误加客户过滤（回归到已作废的 v1 裁决）

### 0.3 测试数据总纲（先看这张表，再看 §1 的可执行 SQL）

| 用途 | 客户 | 元素 | 价格源 | 说明 |
|------|------|------|--------|------|
| 取值方式/窗口/先乘后加正确性 | `CUST-1269`（罗克韦尔） | Al/Cu/Au/Pd/Ag/Ni/Fe/Zn/Cr/Mn/Be | `TEST-PS-0722` | §1.2 |
| 多源最新价 + 价格表筛选 | — | Sn | `TEST-PS-0722` / `TEST-PS-0722-B` / `DISABLE-TEST-SRC-0722` | §1.3 |
| 停用源三条语义 | `CUST-1269` | W（钨） | `DISABLE-TEST-SRC-0722` | §1.4 |
| 导入功能 | — | Ag/Cu/Ni/Sn/Al/Au/Fe/Zn/Cr + 1 个不存在符号 | `IMPORT-TEST-SRC-0722` | §1.5 |
| 核价 `_GLOBAL_` | `_GLOBAL_` | Ag/Cu/Ni | `TEST-PS-0722`（复用同一份日价） | §1.6 |
| 跨报价单串价专项 | `CUST-1269` | Cu | `TEST-PS-0722`（复用） | §1.7 |
| 无策略客户对照组 | `CUST-1292`（森萨塔） | — | — | 全程不建策略，验证留空 |

---

## 1. 测试数据预置（可直接执行的 SQL）

> 全部使用 `TEST-` / `IMPORT-TEST-` / `DISABLE-TEST-` 前缀命名，便于测试结束后按前缀批量清理（见 §1.8）。
> ⚠️ `element_price_strategy` / `element_price_strategy_log` 表本文书写时尚未建表（B1 未落地），以下策略相关 SQL 需等 V351/V352 迁移落库后才能执行；价格源 / 日价 SQL 现在即可执行（复用既有 V44 表）。

### 1.1 价格源

```sql
INSERT INTO element_price_source (source_name, source_url, source_type, description, status)
VALUES
  ('TEST-PS-0722',            'https://test.internal/0722-a', 'MANUAL', 'task-0722 测试专用主源，请勿用于生产', 'ACTIVE'),
  ('TEST-PS-0722-B',          'https://test.internal/0722-b', 'MANUAL', 'task-0722 测试专用副源（多源对比用）', 'ACTIVE'),
  ('IMPORT-TEST-SRC-0722',    'https://test.internal/0722-imp','MANUAL', 'task-0722 导入专用源', 'ACTIVE'),
  ('DISABLE-TEST-SRC-0722',   'https://test.internal/0722-dis','MANUAL', 'task-0722 停用语义测试源（后续会被停用）', 'ACTIVE')
ON CONFLICT ON CONSTRAINT uq_eps_name_url DO NOTHING;
```

### 1.2 核心计算数据集（`TEST-PS-0722`，基准日按场景分组，见下表推导过程）

```sql
INSERT INTO element_daily_price (element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status)
SELECT v.element_name, s.id, v.price_date::date, v.raw_price::numeric, 'CNY', '元/kg', 'MANUAL'
FROM (VALUES
  -- ===== Al：LATEST 正确性 + 基准日之后的数据必须被忽略 =====
  ('Al','2026-02-10','60.0000'),
  ('Al','2026-02-25','65.0000'),
  ('Al','2026-03-05','999.0000'),   -- 基准日 2026-02-28 之后，LATEST 必须忽略它

  -- ===== Cu / Au / Pd：同一份三点数据集，分别验证 AVG / MAX / MIN =====
  ('Cu','2026-02-01','70.0000'), ('Cu','2026-02-15','80.0000'), ('Cu','2026-02-28','90.0000'),
  ('Au','2026-02-01','70.0000'), ('Au','2026-02-15','80.0000'), ('Au','2026-02-28','90.0000'),
  ('Pd','2026-02-01','70.0000'), ('Pd','2026-02-15','80.0000'), ('Pd','2026-02-28','90.0000'),

  -- ===== Be：不配例外，走客户默认策略（AVG/30DAY/1.05/2.00），复用同一份三点数据集做交叉验证 =====
  ('Be','2026-02-01','70.0000'), ('Be','2026-02-15','80.0000'), ('Be','2026-02-28','90.0000'),

  -- ===== Ag：窗口闭区间【下边界】。基准日 2026-02-28，窗口 30 天 → win_from = 2026-01-29 =====
  ('Ag','2026-01-29','50.0000'),   -- 边界内，应计入
  ('Ag','2026-01-28','40.0000'),   -- 边界外一天，必须排除

  -- ===== Ni：窗口闭区间【上边界=基准日当天】。LATEST 必须能取到基准日当天的价 =====
  ('Ni','2026-02-27','195.0000'),
  ('Ni','2026-02-28','200.0000'),  -- 基准日当天，应被 LATEST 取到（非 195）

  -- ===== Fe：窗口单位=周。基准日 2026-03-15，2 周=14 天 → win_from = 2026-03-01 =====
  ('Fe','2026-02-28','10.0000'),   -- 边界外一天，必须排除
  ('Fe','2026-03-01','20.0000'),   -- 边界内
  ('Fe','2026-03-15','30.0000'),   -- 基准日当天

  -- ===== Zn：窗口单位=月（滚动区间，非自然日历月）。基准日 2026-04-30，3 个月 → win_from = 2026-01-30 =====
  ('Zn','2026-01-15','999.0000'),  -- 若误用"自然日历 3 个月(Feb~Apr 或 Jan~Mar)"会被错误纳入；正确滚动区间应排除
  ('Zn','2026-02-01','50.0000'),
  ('Zn','2026-04-25','55.0000'),

  -- ===== Cr：窗口单位=年（滚动区间）。基准日 2026-06-30，1 年 → win_from = 2025-06-30 =====
  ('Cr','2025-06-29','111.0000'),  -- 边界外一天，必须排除
  ('Cr','2025-07-01','222.0000'),  -- 边界内

  -- ===== Mn：先乘后加。100 × 1.05 + 2 = 107.00（若先加后乘会得 107.10，判定用）=====
  ('Mn','2026-02-28','100.0000')
) AS v(element_name, price_date, raw_price)
CROSS JOIN (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722') s;
```

**期望取值结果推导表**（拿到代码后用于人工复核 `f_customer_element_price` / API 输出）：

| 元素 | 策略(method/window/factor/premium) | 基准日 | 期望 `rawValue` | 期望 `finalPrice` | 验证点 |
|------|------|------|------|------|------|
| Al | LATEST / — / 1 / 0 | 2026-02-28 | 65.0000 | 65.0000 | 忽略基准日之后的 999 |
| Cu | AVG / 30 DAY / 1 / 0 | 2026-02-28 | 80.0000 | 80.0000 | (70+80+90)/3 |
| Au | MAX / 30 DAY / 1 / 0 | 2026-02-28 | 90.0000 | 90.0000 | max(70,80,90) |
| Pd | MIN / 30 DAY / 1 / 0 | 2026-02-28 | 70.0000 | 70.0000 | min(70,80,90) |
| Be | （走客户默认 AVG/30DAY/1.05/2.00） | 2026-02-28 | 80.0000 | **86.0000** | 80×1.05+2；与 Cu 的 raw 交叉核对算法一致性 |
| Ag | AVG / 30 DAY / 1 / 0 | 2026-02-28 | 50.0000（非 45） | 50.0000 | 排除 01-28，闭区间下边界 |
| Ni | LATEST / — / 1 / 0 | 2026-02-28 | 200.0000（非 195） | 200.0000 | 基准日当天含入 |
| Fe | AVG / 2 WEEK / 1 / 0 | 2026-03-15 | 25.0000（非 20） | 25.0000 | (20+30)/2，排除 02-28 |
| Zn | MAX / 3 MONTH / 1 / 0 | 2026-04-30 | 55.0000（非 999） | 55.0000 | 滚动区间非自然月 |
| Cr | MIN / 1 YEAR / 1 / 0 | 2026-06-30 | 222.0000（非 111） | 222.0000 | 滚动区间，排除边界外一天 |
| Mn | LATEST / — / 1.05 / 2.00 | 2026-02-28 | 100.0000 | **107.0000（非 107.10）** | 先乘后加 |

### 1.3 多源最新价数据集（Sn，用于 §4.1 各源最新价 + §3 价格表多源筛选）

```sql
INSERT INTO element_daily_price (element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status)
VALUES
  ('Sn', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'),          '2026-06-01', 145.0000, 'CNY', '元/kg', 'MANUAL'),
  ('Sn', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722-B'),        '2026-06-05', 146.5000, 'CNY', '元/kg', 'MANUAL'),
  ('Sn', (SELECT id FROM element_price_source WHERE source_name='DISABLE-TEST-SRC-0722'), '2026-05-20', 140.0000, 'CNY', '元/kg', 'MANUAL');
```

### 1.4 停用源三条语义数据集（W · 钨）

```sql
INSERT INTO element_daily_price (element_name, source_id, price_date, raw_price, currency, price_unit, fetch_status)
VALUES ('W', (SELECT id FROM element_price_source WHERE source_name='DISABLE-TEST-SRC-0722'), '2026-02-15', 88.0000, 'CNY', '元/kg', 'MANUAL');
-- 策略：CUST-1269 对 W 建例外 LATEST×1+0（等 element_price_strategy 表就绪后建，见 §1.6）
-- 之后再调用 POST /sources/{id}/status {"status":"DISABLED"} 停用该源，验证存量策略仍取价
```

### 1.5 导入测试文件内容（10 行，9 有效 + 1 无效）

| 行号 | 元素符号 | 单价 | 货币 | 计价单位 | 预期结果 |
|------|---------|------|------|---------|---------|
| 2 | Ag | 5820 | CNY | 元/kg | CREATED |
| 3 | Cu | 76.50 | （留空） | （留空） | CREATED，货币/单位自动填 CNY/元/kg |
| 4 | Ni | 132.80 | CNY | 元/kg | CREATED |
| 5 | Sn | 145.00 | CNY | 元/kg | CREATED |
| 6 | Al | 18.50 | CNY | 元/kg | CREATED |
| 7 | Au | 452.00 | CNY | 元/g | CREATED |
| 8 | Fe | 3.20 | CNY | 元/kg | CREATED |
| 9 | Zn | 21.50 | CNY | 元/kg | CREATED |
| 10 | Cr | 68.00 | CNY | 元/kg | CREATED |
| 11 | Auu（拼写错，不存在） | 100.00 | CNY | 元/kg | **FAILED**："元素符号「Auu」在元素管理中不存在" |

> 导入时选 **价格源 = `IMPORT-TEST-SRC-0722`**，**价格日期 = `2026-07-20`**（与 §1.2 的 2026-01~2026-06 测试区间完全隔离，不互相干扰）。
> 需要单独下载 `GET /import-template` 得到表头再手工填数据生成 `.xlsx`（也可用 openpyxl/Python 脚本按上表生成，测试执行时准备）。

**另需构造**（用于 §11.3.1 元素停用校验，B5 场景）：
```sql
-- 临时停用一个不常用元素，测试导入行命中"元素已停用"分支
UPDATE element SET status='DISABLED' WHERE element_code='Ir';
-- 用完立即恢复（§1.8 清理清单已列，勿忘）
```

### 1.6 `_GLOBAL_` 核价策略（等 B1/B2 落地后执行）

```sql
-- 默认策略：LATEST × 0.9 - 5（刻意与 CUST-1269 的报价侧配置不同，用来证明"报价核价可以不同"）
INSERT INTO element_price_strategy (customer_no, element_code, source_id, method, window_num, window_unit, factor, premium)
VALUES ('_GLOBAL_', NULL, (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'LATEST', NULL, NULL, 0.9000, -5.0000);

-- CUST-1269 客户级默认（§11.21#4 官方示例原样落地）
INSERT INTO element_price_strategy (customer_no, element_code, source_id, method, window_num, window_unit, factor, premium)
VALUES ('CUST-1269', NULL, (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'AVG', 30, 'DAY', 1.0500, 2.0000);

-- CUST-1269 元素级例外（对应 §1.2 期望表的 method/window/factor/premium 列，逐条建）
INSERT INTO element_price_strategy (customer_no, element_code, source_id, method, window_num, window_unit, factor, premium) VALUES
  ('CUST-1269','Al', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'LATEST', NULL, NULL, 1.0000, 0.0000),
  ('CUST-1269','Cu', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'AVG',    30, 'DAY',  1.0000, 0.0000),
  ('CUST-1269','Au', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'MAX',    30, 'DAY',  1.0000, 0.0000),
  ('CUST-1269','Pd', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'MIN',    30, 'DAY',  1.0000, 0.0000),
  ('CUST-1269','Ag', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'AVG',    30, 'DAY',  1.0000, 0.0000),
  ('CUST-1269','Ni', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'LATEST', NULL, NULL, 1.0000, 0.0000),
  ('CUST-1269','Fe', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'AVG',     2, 'WEEK', 1.0000, 0.0000),
  ('CUST-1269','Zn', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'MAX',     3, 'MONTH',1.0000, 0.0000),
  ('CUST-1269','Cr', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'MIN',     1, 'YEAR', 1.0000, 0.0000),
  ('CUST-1269','Mn', (SELECT id FROM element_price_source WHERE source_name='TEST-PS-0722'), 'LATEST', NULL, NULL, 1.0500, 2.0000);
-- 注意：Be 不建例外，用来验证"无例外时继承客户默认"

-- 停用源语义测试专用（W 钨）
INSERT INTO element_price_strategy (customer_no, element_code, source_id, method, window_num, window_unit, factor, premium)
VALUES ('CUST-1269','W', (SELECT id FROM element_price_source WHERE source_name='DISABLE-TEST-SRC-0722'), 'LATEST', NULL, NULL, 1.0000, 0.0000);
```

**`_GLOBAL_` 期望值**（与报价侧 §1.2 表对照，证明"可以不同"）：

| 元素 | `_GLOBAL_` rawValue(LATEST) | `_GLOBAL_` finalPrice(×0.9-5) | 报价侧(CUST-1269) finalPrice | 是否应不同 |
|------|------|------|------|------|
| Ag | 50.0000 | **40.0000** | 50.0000（AVG 例外） | ✅ 不同 |
| Cu | 90.0000 | **76.0000** | 80.0000（AVG 例外） | ✅ 不同 |
| Ni | 200.0000 | **175.0000** | 200.0000（LATEST 例外） | ✅ 不同 |

### 1.7 跨报价单串价专项数据（复用 §1.2 的 Cu 数据集，不重复插数据）

- Cu 在 `TEST-PS-0722` 的价格点：`02-01:70` / `02-15:80` / `02-28:90`
- 策略：`CUST-1269` 对 Cu 的例外 = `AVG / 30 DAY / ×1 / +0`（已在 §1.6 建好）
- 需要两张 **创建日期不同** 的报价单（同客户 `CUST-1269`，BOM 含 Cu 元素的产品/料号，如 `material_no=0363-2607000007`）：

| 报价单 | 强制 `created_at` | 窗口 | 窗口内 Cu 价格点 | 期望 Cu 单价 |
|--------|------|------|------|------|
| QT-A | `2026-02-05` | `[2026-01-06, 2026-02-05]` | 仅 `02-01:70` | **70.0000** |
| QT-B | `2026-02-28` | `[2026-01-29, 2026-02-28]` | `02-15:80`、`02-28:90` | **80.0000** |

```sql
-- 报价单建好后，用 SQL 强制改 created_at 制造不同基准日（quotation 表无独立"单据日期"列，
-- 基准日 = created_at::date，见需求 §11.2；表上确认无 BEFORE UPDATE 触发器拦截该列）
UPDATE quotation SET created_at = '2026-02-05 10:00:00+08' WHERE quotation_number = '<QT-A 编号>';
UPDATE quotation SET created_at = '2026-02-28 10:00:00+08' WHERE quotation_number = '<QT-B 编号>';
```

### 1.8 测试数据清理（测试结束后执行，避免污染共享 DB）

```sql
DELETE FROM element_price_strategy_log WHERE customer_no IN ('_GLOBAL_','CUST-1269') AND changed_at > '2026-07-22';  -- 按实际测试时间窗收窄
DELETE FROM element_price_strategy WHERE customer_no = '_GLOBAL_'
   AND source_id IN (SELECT id FROM element_price_source WHERE source_name LIKE 'TEST-%' OR source_name LIKE '%TEST-SRC-0722');
DELETE FROM element_price_strategy WHERE customer_no = 'CUST-1269'
   AND element_code IN ('Al','Cu','Au','Pd','Ag','Ni','Fe','Zn','Cr','Mn','W');
DELETE FROM element_daily_price WHERE source_id IN (
  SELECT id FROM element_price_source WHERE source_name LIKE 'TEST-%' OR source_name LIKE 'IMPORT-TEST-%' OR source_name LIKE 'DISABLE-TEST-%'
);
DELETE FROM element_price_source WHERE source_name LIKE 'TEST-%' OR source_name LIKE 'IMPORT-TEST-%' OR source_name LIKE 'DISABLE-TEST-%';
UPDATE element SET status='ACTIVE' WHERE element_code='Ir';  -- 恢复临时停用的元素
-- 若为 TC-XCUST/TC-GLB 新建了专用测试报价单/核价单，一并按 quotation_number 精确删除
```

---

## 2. 用例列表

### 2.1 价格源管理（TC-SRC）—— 对应验收要点 #1

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-SRC-01 | #1 | 无 | 新建价格源，`sourceName="测试源A"`，`sourceUrl` 留空 | 创建成功，`sourceType` 后端固定写 `MANUAL`（前端未传该字段） | API+SQL：`SELECT source_type FROM element_price_source WHERE source_name='测试源A'` = `MANUAL` |
| TC-SRC-02 | #1 | 已存在"测试源A"+同 URL | 再建一个同名同 URL 的源 | `409`，"源名称 + 网址 已存在" | API |
| TC-SRC-03 | #1 | 无 | 名称相同但 URL 不同（或反之） | 创建成功（唯一键是名称+URL 组合，不是名称单独唯一） | API |
| TC-SRC-04 | #1 | 已有一个 ACTIVE 源 | 编辑该源改名，改后名称与另一 ACTIVE 源+URL 重复 | `409` | API |
| TC-SRC-05 | #1 | 有 ≥1 条 ACTIVE 源 | `POST /{id}/status {"status":"DISABLED"}` | 200，`status` 变 `DISABLED` | API+SQL |
| TC-SRC-06 | #1 | 承 TC-SRC-05 | `GET /sources?status=ACTIVE` | 返回列表**不含**该源 | API |
| TC-SRC-07 | #1 | 承 TC-SRC-05；该源下有历史价（§1.4 W 数据） | `GET /prices?sourceId=<该源>` 及 `GET /latest-by-source?elementCode=W` | 历史价**照常返回**，`sourceStatus=DISABLED` | API |
| TC-SRC-08 | #1（🔴 最容易做错） | 承 TC-SRC-05；`CUST-1269` 对 `W` 的例外策略引用该源（§1.6） | 停用后直接查 `f_customer_element_price('CUST-1269','2026-02-28')` 中 `element_code='W'` 一行 | **仍返回 88.0000**（存量策略不受源停用影响），不能因为源停用就消失或报错 | SQL 直查表函数 |
| TC-SRC-09 | #1 | 承 TC-SRC-05 | 在编辑抽屉里把状态改回 `ACTIVE` | 200，源重新出现在 ACTIVE 下拉里 | API |
| TC-SRC-10 | §11.13.1 | 任意源 | 检查 API/UI 是否存在物理删除入口 | **不存在** `DELETE /sources/{id}`；UI 无删除按钮 | API（404/405）+UI |
| TC-SRC-11 | §11.24-A | 无 | `GET /sources` 默认排序 | 启用优先 → `updated_at` 倒序 | API |
| TC-SRC-12 | UI | 无 | 打开价格源管理抽屉（720px），列表用 `SelectableTable` | 顶部工具栏 `[新建][编辑][停用]`，行内无操作按钮；「编辑」勾 2 行时 `enabledWhen` 返回原因字符串（非隐藏） | UI |
| TC-SRC-13 | UI | 已停用 1 条 | 工具栏勾选该停用行 + 1 启用行，点「停用」 | `enabledWhen` 应提示"仅启用状态可停用"（禁用，非隐藏） | UI |

### 2.2 价格导入（TC-IMP）—— 对应验收要点 #2 #16

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-IMP-01 | #2 | 无 | `GET /import-template` | 返回 xlsx，表头 4 列 `元素符号* / 单价* / 货币 / 计价单位`，含 1 行示例 + 1 行说明 | API |
| TC-IMP-02 | #2 | §1.5 文件（10 行） | 选源 `IMPORT-TEST-SRC-0722`，日期 `2026-07-20`，导入 | 回显 `新增 9 / 覆盖 0 / 失败 1` | API |
| TC-IMP-03 | #16 | 承 TC-IMP-02 | `SELECT count(*) FROM element_daily_price WHERE source_id=<导入源> AND price_date='2026-07-20'` | **= 9**（非整批回滚，非 0，非 10） | SQL |
| TC-IMP-04 | #2 | 承 TC-IMP-02 | 检查明细行第 11 行（Auu） | `result=FAILED`，`message="元素符号「Auu」在元素管理中不存在"` | API |
| TC-IMP-05 | #16 | 同参数（源+日期）再导一次原始 9 行有效数据（不含错行） | 重复导入 | 回显 `新增 0 / 覆盖 9 / 失败 0`；`SELECT count(*)` 仍为 9（无重复行） | API+SQL |
| TC-IMP-06 | §11.3.3 | 承 TC-IMP-05，把 Ag 单价从 5820 改成 5850 后再导 | 导入 | 明细行 `result=UPDATED`，`message="原值 5820.0000 → 5850.0000"` | API |
| TC-IMP-07 | #16 | 修正第 11 行 Auu → 合法符号（如 Cd） | 重新导入整份文件（此时 10 行全合法） | 回显 `新增 1 / 覆盖 9 / 失败 0`；总行数变 10（非 9 也非 11） | API+SQL |
| TC-IMP-08 | §11.3.1 | 先 `UPDATE element SET status='DISABLED' WHERE element_code='Ir'` | 导入一行 `元素符号=Ir` | 该行 `FAILED`，`message="元素「Ir」已停用，不可导入价格"` | API（记得测完恢复 Ir） |
| TC-IMP-09 | §11.3.1 | 无 | 导入一行单价 = 0 | `FAILED`，"单价必须大于 0" | API |
| TC-IMP-10 | §11.3.1 | 无 | 导入一行单价 = -5 | `FAILED` | API |
| TC-IMP-11 | §11.3.1 | 无 | 导入一行元素符号留空 | `FAILED`，"元素符号不能为空" | API |
| TC-IMP-12 | §11.3.1 | 无 | 导入一行货币/计价单位留空，单价合法 | **CREATED/UPDATED**（不是 FAILED），落库后货币=`CNY`、单位=`元/kg` | API+SQL |
| TC-IMP-13 | 整批级前置校验 | 无 | `sourceId` 传一个 `DISABLED` 源 | `400` | API |
| TC-IMP-14 | 整批级前置校验 | 无 | 上传空文件 / 非 xlsx 后缀但内容非法 | `400` | API |
| TC-IMP-15 | 整批级前置校验 | 无 | 上传 > 5MB 文件 | `400` | API |
| TC-IMP-16 | UI | 无 | 打开导入抽屉，选源下拉 | **只列 `status=ACTIVE`** 的源（`DISABLE-TEST-SRC-0722` 停用后不出现） | UI |
| TC-IMP-17 | UI | 承 TC-IMP-02 | 查看结果表 | 新增=绿 Tag，覆盖=橙 Tag，失败=红 Tag+整行浅红底 | UI |
| TC-IMP-18 | §11.14D#5 反向 | 承 TC-IMP-02 | 查看结果区/Footer | **无**"导出失败明细"按钮 | UI |

### 2.3 价格表查询（TC-TBL）—— 对应验收要点 #3

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-TBL-01 | #3 | §1.3 Sn 三源数据 | `GET /prices?keyword=Sn` | 返回 3 行（3 个源各一条），含中文名"锡" | API |
| TC-TBL-02 | #3 | 同上 | `GET /prices?sourceId=<TEST-PS-0722>&keyword=Sn` | 只返回 1 行 | API |
| TC-TBL-03 | #3 | 同上 | `GET /prices?from=2026-06-01&to=2026-06-01` | 只返回 `price_date=2026-06-01` 的记录 | API |
| TC-TBL-04 | #3 | 无参数 | `GET /prices` | 默认最近 30 天，分页 `size=20` 生效 | API |
| TC-TBL-05 | #3 | 无 | `GET /prices/matrix`（不传 `sourceId`） | `400` | API |
| TC-TBL-06 | #3 | 传 `sourceId` | `from=2026-01-01&to=2026-04-02`（跨度 91 天） | `400`，"矩阵视图日期跨度最长 90 天，请收窄区间" | API |
| TC-TBL-07 | #3 | 传 `sourceId`，跨度=90 天整 | 边界值 90 天 | **200**（≤90 不报错，>90 才报错，需确认 off-by-one） | API |
| TC-TBL-08 | #3 | `sourceId=TEST-PS-0722`，`from/to` 覆盖 2026-02-01~03 | `GET /prices/matrix` | `dates`（升序）与每行 `prices` **等长对齐**；02-02/02-03 等无记录日期 → `prices[i]=null`（不是 0） | API |
| TC-TBL-09 | #3 | 同上 | UI 渲染矩阵表格 | `null` 渲染为「—」灰色，**不出现任何 0** | UI |
| TC-TBL-10 | #3 | 无 | `GET /prices/export`、`GET /prices/matrix/export`（带筛选参数） | 200，xlsx，内容=当前筛选全量（不分页截断） | API |
| TC-TBL-11 | §11.14D#5 反向 | 无 | 查看矩阵 Tab 筛选区 | 无"全部源"选项，`sourceId` 为**单选必填** | UI |

### 2.4 元素侧增强（TC-ELE）—— 对应验收要点 #14 #15

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-ELE-01 | #15 | §1.3 Sn 三源数据 | `GET /latest-by-source?elementCode=Sn` | 返回 3 行；`DISABLE-TEST-SRC-0722` 那行 `sourceStatus=DISABLED` 但**仍返回** | API |
| TC-ELE-02 | #15 | 停用 `DISABLE-TEST-SRC-0722`（若尚未停用） | 同上再查一次 | 停用后该行依旧存在，只是 `sourceStatus` 变化 | API |
| TC-ELE-03 | #15 | UI | 打开 Sn 编辑抽屉 | 「各源最新价格」区块 3 行；停用源整行置灰 + `<Tag>源已停用</Tag>` | UI |
| TC-ELE-04 | #15 | 选一个从未导过价的元素（如 `不锈钢`） | `GET /latest-by-source?elementCode=不锈钢` | 返回 `[]` | API |
| TC-ELE-05 | #15 | 同上 | UI 打开该元素抽屉 | 显示空态文案「该元素暂无任何价格记录，请通过『价格导入』录入」 | UI |
| TC-ELE-06 | #15 UI | 无 | 新建元素时（新建态） | **不显示**「各源最新价格」区块 | UI |
| TC-ELE-07 | #14 | 取某元素当前 `element.updated_at`（如 Cu） | 对 Cu 导入一条新价格 | 元素列表 `lastModifiedAt` 更新为导入时刻，该元素排到列表最前 | API+UI |
| TC-ELE-08 | #14（🔴 最容易做错） | 承 TC-ELE-07，记录导入**前** `element.updated_at` | 导入后再查 `SELECT updated_at FROM element WHERE element_code='Cu'` | **与导入前完全一致**，未被反写 | SQL 直查（前后对比） |
| TC-ELE-09 | #14 | 同一元素既改了中文名又导过价 | 比较 `element.updated_at` 与最新价格 `updated_at` | `lastModifiedAt = MAX(两者)`，取较大值 | API+SQL |
| TC-ELE-10 | §11.14B | 无 | 查看元素列表 | 「创建时间」「修改时间」两列已合并为一列「最后修改时间」，默认倒序 | UI |
| TC-ELE-11 | §11.22（BL-0069#5 闭合） | 无 | 触发导入抽屉 / 价格表元素筛选下拉可选元素范围 | 数据源来自 `element` 主表 ACTIVE 元素，**不是** `mat_bom`（已停写） | API/代码走查 |

### 2.5 价格策略配置（TC-STR）—— 对应验收要点 #4 #10 #12

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-STR-01 | #4 | 无 | `PUT /strategies/default` 建 `CUST-1269` 默认（源 A + AVG 30天 × 1.05 + 2） | 200，保存成功 | API |
| TC-STR-02 | #4 | 承 TC-STR-01 | `GET /strategies?customerNo=CUST-1269` | `default` 字段与保存值逐字段一致 | API |
| TC-STR-03 | #4 | 承 TC-STR-01 | 再次 `PUT /strategies/default` 改系数为 1.10 | 200，视为覆盖更新（非新建第二条）；`SELECT count(*) FROM element_price_strategy WHERE customer_no='CUST-1269' AND element_code IS NULL` **恒为 1** | API+SQL |
| TC-STR-04 | #4 | 承 TC-STR-01 | `POST /strategies/exceptions` 建 Ag 例外（源 A + LATEST × 1） | 200 | API |
| TC-STR-05 | #4 | 承 TC-STR-04 | 再次对 Ag `POST` 新建例外 | `409`，"该客户的元素「Ag」已存在例外配置" | API |
| TC-STR-06 | #4 | 承 TC-STR-04 | `GET /strategies?customerNo=CUST-1269` | `exceptions` 含 Ag 一条，字段与保存值一致 | API |
| TC-STR-07 | #4（例外优先） | 承 §1.6 完整数据 | 用 §1.2 期望值表逐条核对 Ag/Cu/... 的最终结果 | 与"期望取值结果推导表"完全一致（例外生效，非默认） | SQL 直查表函数 |
| TC-STR-08 | §11.7 联动 | UI | 「取值方式」选 `LATEST` | 窗口数值+单位两个控件 `disabled` 并清空；提交请求体**不含** `windowNum`/`windowUnit` | UI+抓包 |
| TC-STR-09 | §11.9 | 无 | `PUT` 一条 `method=LATEST` 但携带 `windowNum=30` | `400` | API |
| TC-STR-10 | §11.9 | 无 | `PUT` 一条 `method=AVG` 但不传 `windowNum`/`windowUnit` | `400` | API |
| TC-STR-11 | §5.2 校验 | 无 | `sourceId` 传一个 `DISABLED` 源 | `400` | API |
| TC-STR-12 | §5.2 校验 | 无 | `factor=0` 或 `factor=-1` | `400`（DB CHECK `factor>0` 兜底） | API |
| TC-STR-13 | §5.2 校验 | 无 | `premium=-10`（负数） | **200，允许**（premium 可为负） | API |
| TC-STR-14 | §5.2 校验 | 无 | `customerNo` 传一个不存在的客户编码（非 `_GLOBAL_`） | `400`（真实客户编码须校验存在于 `customer` 表） | API |
| TC-STR-15 | #10 | 无 | 定价策略页左侧客户列表 | 顶部固定「🌐 全局（核价成本口径）」项，与真实客户视觉区分（紫色底+分隔线） | UI |
| TC-STR-16 | #10 | 同上 | 在搜索框输入关键字过滤客户 | 全局项**始终在顶部**，不参与过滤，不参与分页 | UI |
| TC-STR-17 | #10 | 选中全局项 | 查看 Tab 区 | 「折扣策略」Tab **不显示**（条件渲染，非 disabled）；「元素价格策略」Tab 内容与选中真实客户时同构 | UI |
| TC-STR-18 | §5.1 | 未配任何策略的客户（如 `CUST-1292`） | `GET /strategies?customerNo=CUST-1292` | `default: null`，`exceptions: []` | API |
| TC-STR-19 | #4 元素例外表格 | 承 §1.6 | `PUT /strategies/exceptions/{id}` 修改 Ag 例外的系数 | 200，回读一致 | API |
| TC-STR-20 | #4 元素例外表格 | 承上 | `DELETE /strategies/exceptions/{id}` 删除 Ag 例外 | `204`；再查 `f_customer_element_price` 中 Ag 应回退为走客户默认值 | API+SQL |
| TC-STR-21 | #12 | 承 TC-STR-01 | 查看「客户级默认策略」卡片头部 | 显示 `最后变更 <时间> · <操作人姓名>` | UI |
| TC-STR-22 | #12 | 承 §1.6 例外数据 | 查看「元素级例外」表格 | 新增两列「最后变更时间」「变更用户」，与各自最新一条历史一致 | UI |
| TC-STR-23 | §11.11.4（🔒 强约束） | 无 | 检查 `element_price_strategy` 表 DDL | `customer_no` 为 `VARCHAR`，**无** `customer_id UUID` 外键列 | SQL：`\d element_price_strategy` |
| TC-STR-24 | §11.12 | UI | 默认策略表单「价格源」下拉 | 必填单选，非多选/非可空 | UI |

### 2.6 取价计算核心引擎（TC-CALC）—— 🔴 本次测试重心，逐条对应"期望取值结果推导表"

> 验证方式统一为：**SQL 直查** `SELECT * FROM f_customer_element_price('CUST-1269','<基准日>') WHERE element_code='<元素>'`
> + **API 交叉验证** `POST /strategies/simulate {"customerNo":"CUST-1269","baseDate":"<基准日>"}`（不传 `draft`，走库中已存策略）。
> 两种验证方式的数值必须完全一致（表函数是试算的底层依赖之一）。

| 编号 | 验收要点 | 元素 | 基准日 | 期望 rawValue | 期望 finalPrice | 验证点 |
|------|------|------|------|------|------|------|
| TC-CALC-01 | #5 §11.7 | Al | 2026-02-28 | 65.0000 | 65.0000 | LATEST 正确性；忽略基准日之后的 999（防止把"全表最新"误当"截至基准日最新"） |
| TC-CALC-02 | #5 §11.7 | Cu | 2026-02-28 | 80.0000 | 80.0000 | AVG=(70+80+90)/3 |
| TC-CALC-03 | #5 §11.7 | Au | 2026-02-28 | 90.0000 | 90.0000 | MAX |
| TC-CALC-04 | #5 §11.7 | Pd | 2026-02-28 | 70.0000 | 70.0000 | MIN |
| TC-CALC-05 | §11.9（🔴 高风险） | Ag | 2026-02-28 | 50.0000 | 50.0000 | 窗口闭区间**下边界**：`win_from` 当天含入，前一天排除；若误算成 45.0000 即为区间开闭错误 |
| TC-CALC-06 | §11.9（🔴 高风险） | Ni | 2026-02-28 | 200.0000 | 200.0000 | 窗口闭区间**上边界**：基准日当天必须含入 LATEST 候选；若误算成 195.0000 即为 `<` 而非 `<=` 的边界错误 |
| TC-CALC-07 | §11.9 | Fe | 2026-03-15 | 25.0000 | 25.0000 | 窗口单位=周（2 周=14 天）；若误算成 20.0000 说明周单位换算错误或边界内漏算 03-15 |
| TC-CALC-08 | §11.9（🔴 高风险） | Zn | 2026-04-30 | 55.0000 | 55.0000 | 窗口单位=月，**滚动区间非自然日历月**；若误算成 999.0000 说明用了"过去 3 个完整自然月"口径 |
| TC-CALC-09 | §11.9 | Cr | 2026-06-30 | 222.0000 | 222.0000 | 窗口单位=年，滚动区间；若误算成 111.0000 说明边界外一天未排除 |
| TC-CALC-10 | §11.8（🔴 高风险） | Mn | 2026-02-28 | 100.0000 | **107.0000** | 先乘后加；若得 107.1000 说明算成了"先加后乘" |
| TC-CALC-11 | §11.1（例外优先） | Ag | 2026-02-28 | — | 50.0000 | 与"若无例外走默认"的假设值 `50×1.05+2=54.5000` 不同，证明确实读的是例外行而非默认行 |
| TC-CALC-12 | §11.1（无例外继承默认） | Be | 2026-02-28 | 80.0000 | **86.0000** | Be 未建任何例外，理应完全继承 `CUST-1269` 默认策略（AVG/30天/1.05/2.00）；raw 与 Cu 的 80.0000 一致（交叉验证算法一致性），最终价体现默认的系数/加价 |
| TC-CALC-13 | §11.15（不兜底，不返回） | — | 2026-02-28 | — | — | `f_customer_element_price('CUST-1292', '2026-02-28')`（无任何策略客户）返回**空结果集（0 行）**，不是返回带 `unit_price=NULL` 的行 | SQL |
| TC-CALC-14 | §11.5（窗口内无价，元素不出现） | 任选一个 CUST-1269 已配策略但窗口内故意留空的元素 | 任意早于所有价格数据的基准日（如 `2020-01-01`） | — | — | `f_customer_element_price('CUST-1269','2020-01-01')` 中 Al/Cu/... 均不出现（窗口内无数据），而非返回 `unit_price=NULL` 的行 | SQL |
| TC-CALC-15 | §1 硬约束#3 | 任意 | 任意 | — | — | 检查函数体 / 视图 SQL，确认**没有**任何 `COALESCE(unit_price, 0)` 兜底写法 | SQL：`pg_get_functiondef` |
| TC-CALC-16 | 精度 | 任意 | 任意 | — | — | 所有 `finalPrice` 保留 4 位小数（`ROUND(...,4)`），如 `86.0000` 而非 `86` 或 `86.00` | API |

### 2.7 核价 `_GLOBAL_` 口径（TC-GLB）—— 对应验收要点 #6

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-GLB-01 | #6（先测空态，须在 §1.6 `_GLOBAL_` 策略建立**之前**执行） | `_GLOBAL_` 尚无任何策略 | `f_customer_element_price('_GLOBAL_','2026-02-28')` | **0 行**（核价元素单价全空，不回退到任何客户策略） | SQL |
| TC-GLB-02 | #6 | 承 §1.6 建好 `_GLOBAL_` 策略后 | 同上再查一次 | 返回 Ag/Cu/Ni（及其余覆盖到的元素）多行 | SQL |
| TC-GLB-03 | #6（核心） | 承 TC-GLB-02 | 对照 §1.6"`_GLOBAL_` 期望值表" | Ag=40.0000／Cu=76.0000／Ni=175.0000，与报价侧 CUST-1269 的 50/80/200 **均不同** | SQL 双查对比 |
| TC-GLB-04 | #6 | COMP-0040 已接通（B11） | 打开一张 `CUST-1269` 的核价单，查看元素页签 | Ag/Cu/Ni 单价与 TC-GLB-03 的 `_GLOBAL_` 值一致 | UI |
| TC-GLB-05 | #6（换客户不变） | 需另建一张 `CUST-1290` 的核价单，**强制与上面同一基准日** | 查看该核价单元素页签同一元素（如 Cu） | 单价与 TC-GLB-04 中 `CUST-1269` 核价单的 Cu **逐位相同**（核价不受报价单归属客户影响） | UI+SQL |
| TC-GLB-06 | §单价字段配置规则 硬约束#6 | 无 | 检查 COMP-0040 对应 `component_sql_view.sql_template` | JOIN 传字面量 `'_GLOBAL_'`，**WHERE 里没有** `customer_no = :customerCode` 之类的客户过滤条件 | 代码/配置走查 |
| TC-GLB-07 | §11.20 数据模型佐证 | 无 | `SELECT system_type, customer_no, count(*) FROM element_bom_item GROUP BY 1,2` | `PRICING` 行 100% `customer_no='_GLOBAL_'`（与需求 §11.11.1 实测事实一致，佐证该设计前提未漂移） | SQL（回归性质，环境漂移预警） |

### 2.8 跨报价单串价专项（TC-XCUST）—— 🔴 最高风险，B3 §11.22 风险2

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-XCUST-01 | §11.22 风险2（阻断级） | 按 §1.7 建好 QT-A（`created_at=2026-02-05`）、QT-B（`created_at=2026-02-28`），同客户同料号 | 分别打开 QT-A、QT-B 的元素页签，读取 Cu 单价 | QT-A 的 Cu = **70.0000**，QT-B 的 Cu = **80.0000**；**两者必须不同** | UI+SQL |
| TC-XCUST-02 | 同上 | 承 TC-XCUST-01 | 先打开 QT-B（缓存写入），**再**打开 QT-A | QT-A 仍显示 70.0000，**不被 QT-B 的缓存结果污染**（验证缓存键含基准日维度，不是"后打开的覆盖先打开的"） | UI |
| TC-XCUST-03 | 同上 | 承 TC-XCUST-01 | 刷新 QT-A 页面 3 次 | 3 次均为 70.0000，稳定不漂移（无累加/无被别的请求串改） | UI |
| TC-XCUST-04 | 缓存维度自检（配合后端 PR 自检） | — | 走查 `ComponentDriverService` expandCache key 构造 / `DataLoader.resultCache` key 构造 | 二者均含**客户 + 基准日**两个维度，不是只有 `componentId`/`viewName` | 代码走查（对应 backtask B3 风险 2） |
| TC-XCUST-05 | 若 TC-XCUST-01 失败的兜底诊断 | 同上 | 若两单结果相同，进一步排查是 `priceBaseDate` 未注入（两单都退化成"今天"）还是缓存串键 | 用 `EXPLAIN` 或调试日志确认 `:priceBaseDate` 实际绑定值 | SQL/日志 |

### 2.9 报价单取价集成（TC-QT）—— 对应验收要点 #5 #7 #8

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-QT-01 | #5 | COMP-0029 已接通；`CUST-1269` 有策略 | 新建 `CUST-1269` 报价单，产品含 Cu 元素行 | 元素页签 Cu 行「单价」自动带出（与 SQL 表函数值一致），货币/计价单位一并带出 | UI+SQL 复核 |
| TC-QT-02 | #7 | 承 TC-QT-01 | 手工把 Cu 单价改成任意值，保存草稿 | 保存成功 | UI |
| TC-QT-03 | #7 | 承 TC-QT-02 | 关闭后重新打开该报价单 | Cu 单价仍是**改后的值**，**不被自动值回冲** | UI |
| TC-QT-04 | #8 | 客户对某元素配了策略，但故意让该基准日窗口内无价（如基准日 `2020-01-01`） | 打开该客户在该日期附近创建的报价单，查看该元素单价 | 单元格**为空**（非 0、非"加载中"、非报错），销售可手填 | UI |
| TC-QT-05 | #9 | `CUST-1292`（森萨塔，未配策略） | 新建其报价单，查看元素页签 | 所有单价列**均为空**，与改动前行为一致（无自动带出，无异常） | UI |
| TC-QT-06 | §11.10 | 有价格记录（含货币/单位） | 查看元素行的货币/计价单位列 | 随单价一并带出，**不做任何单位换算** | UI |
| TC-QT-07 | §11.10 | 计价单位与 BOM 侧不一致的场景（如有） | 观察是否有换算 | 无自动换算；若原型要求提示则检查提示文案是否出现（若已被裁决砍掉需对照最新 §11 文本确认） | UI |

### 2.10 策略试算（TC-SIM）—— 对应验收要点 #5 辅助验证 + §11.24 屏6

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-SIM-01 | §11.14C | 承 §1.6 已保存策略 | `POST /strategies/simulate {"customerNo":"CUST-1269","baseDate":"2026-02-28"}`（不传 `draft`） | 结果与"期望取值结果推导表"逐条一致 | API |
| TC-SIM-02 | §11.14C（核心价值） | 页面上把默认策略系数**改成 2.00 但不点保存** | 立即点「策略试算」，请求携带 `draft` | 试算结果按**草稿值**计算（Be 的 finalPrice 变为 `80×2.00+2=162.0000`），**不等于**库中已存的 86.0000 | UI+抓包 |
| TC-SIM-03 | §11.14C | 承 TC-SIM-02 | 不点保存，直接关闭抽屉刷新页面 | 库中策略**未被修改**（试算只读不落库） | SQL |
| TC-SIM-04 | §6 | 窗口内无价的元素（如 TC-QT-04 场景） | 试算该客户 | 该元素行 `hasPrice=false`，UI **整行标黄**，`最终单价` 显示橙色粗体「无价」 | API+UI |
| TC-SIM-05 | §6 | 无 | 试算响应中检查未被任何策略覆盖的元素（客户无默认时的其余元素） | **不出现**在结果数组里（不是 `hasPrice=false` 的行） | API |
| TC-SIM-06 | §11.14D#6 反向 | 无 | 查看试算结果区/Footer | **无**"导出试算结果"按钮，Footer 只有 `[关闭]` | UI |
| TC-SIM-07 | UI | 无 | 试算抽屉顶部 | `基准日` DatePicker 默认今天；下方提示"实际报价时 = 报价单创建日期" | UI |

### 2.11 策略变更历史（TC-HIS）—— 对应验收要点 #11 #12

| 编号 | 验收要点 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|------|
| TC-HIS-01 | #11（5 条写入路径-1） | 无 | 新建 `CUST-1269` 默认策略（TC-STR-01） | `element_price_strategy_log` 新增 1 条 `action=CREATE`，`changes=[]`，`snapshot` 为全量配置 | SQL |
| TC-HIS-02 | #11（5 条写入路径-2） | 承上 | 修改默认策略系数（TC-STR-03） | 新增 1 条 `action=UPDATE`，`changes` **只含** `factor` 一项（`oldValue/newValue`），不出现未变字段（如 `method`） | API/SQL |
| TC-HIS-03 | #11（5 条写入路径-3/4） | 无 | 新建 Ag 例外（TC-STR-04）、修改 Ag 例外（TC-STR-19） | 分别产生 `CREATE`、`UPDATE` 记录，`targetLabel="元素例外 · Ag 银"` | API |
| TC-HIS-04 | #11（5 条写入路径-5） | 承 TC-STR-20 | 删除 Ag 例外 | 新增 1 条 `action=DELETE`，`snapshot` = **删除前**完整配置，`changes=[]` | API+SQL |
| TC-HIS-05 | #11 | 承 §1.6 `_GLOBAL_` 策略写入 | 查 `GET /strategies/history?customerNo=_GLOBAL_` | 同样产生历史记录（`_GLOBAL_` 与真实客户完全同构） | API |
| TC-HIS-06 | #11 筛选 | 承以上多条历史 | `elementCode=__DEFAULT__` | 只返回客户级默认策略的变更记录 | API |
| TC-HIS-07 | #11 筛选 | 同上 | `elementCode=Ag` | 只返回 Ag 例外的变更记录 | API |
| TC-HIS-08 | #11 筛选 | 同上 | `from/to` 时间区间、`changedBy` 模糊匹配 | 分别正确过滤 | API |
| TC-HIS-09 | #12 | 承 TC-HIS-02 | 查看默认策略卡片头「最后变更」 | 时间/用户与 `history` 接口最新一条 `changedAt`/`changedByName` 完全一致 | UI+API 对比 |
| TC-HIS-10 | §11.24-D 反向（不做回滚） | 打开变更历史抽屉 | 检查每行/Footer | **无**任何"回滚到此版本"按钮或入口 | UI |
| TC-HIS-11 | §7 | 无 | 尝试构造对 `/strategies/history` 的 `POST`/`PUT`/`DELETE` 请求 | 不存在这些方法（404/405），历史接口**只读** | API |
| TC-HIS-12 | §11.14F.3 快照完整性 | 连续做 3 次修改（系数分别改 3 次） | 查看历史记录 | 每条 `UPDATE` 记录的 `snapshot` 都是**当次变更后的完整配置**（不是只存差异字段），`changes` 才是差异 | SQL：直查 `snapshot` JSONB 内容 |

### 2.12 权限矩阵（TC-PERM）—— 对应 api.md §9

| 编号 | 角色/凭据 | 操作 | 预期结果 |
|------|------|------|------|
| TC-PERM-01 | 无 `Authorization` | `GET /element-price/sources` | `401` |
| TC-PERM-02 | `alice`(SALES_REP) | `GET /element-price/sources` | `403`（§1~§4 读权限不含 SALES_REP，仅 SALES_MANAGER/PRICING_MANAGER/SYSTEM_ADMIN） |
| TC-PERM-03 | `alice`(SALES_REP) | `GET /element-price/strategies?customerNo=CUST-1269` | `200`（§5~§7 读权限含 SALES_REP） |
| TC-PERM-04 | `alice`(SALES_REP) | `PUT /element-price/strategies/default` | `200`（§5~§7 写权限含 SALES_REP，与 api.md §9 一致；若业务预期销售不该改策略需回头找 PM 澄清，见文末待澄清项） |
| TC-PERM-05 | `bob`(SALES_MANAGER) | `POST /element-price/sources`、`POST /element-price/import` | `200` |
| TC-PERM-06 | `test_finance_fd726739`(PRICING_MANAGER) | 全部端点 | `200` |
| TC-PERM-07 | `admin`(SYSTEM_ADMIN) | 全部端点 | `200` |
| TC-PERM-08 | 任意有效角色但 token 过期 | 任意端点 | `401` |

### 2.13 边界与异常（TC-ERR）—— 汇总性用例，避免与上文重复的仅在此补充遗漏点

| 编号 | 验收要点 | 操作步骤 | 预期结果 |
|------|------|------|------|
| TC-ERR-01 | 需求 §11.9 边界 | 矩阵接口跨度恰好 91 天（TC-TBL-06 的镜像） | `400` |
| TC-ERR-02 | api.md §2.2 | 导入 `sourceId` 传一个**不存在**的 UUID | `400` 或 `404`（需在实测阶段确认具体状态码，文档未明确区分，见文末待澄清项） |
| TC-ERR-03 | api.md §5.2 | `PUT /strategies/default` 请求体 `customerNo` 与实际不匹配业务预期（如传大小写不同的 `cust-1269`） | 需确认是否大小写敏感（若敏感则查不到客户 → 400；若不敏感需统一大小写），见待澄清项 |
| TC-ERR-04 | api.md §1.2 | 新建价格源 `sourceName` 超 128 字符 | `400` 或截断，需实测确认 |
| TC-ERR-05 | §5.3 | 例外接口 `elementCode` 传一个已停用的元素 | 是否允许？文档未明确"例外可否针对已停用元素配置"，需实测+待澄清（见文末） |
| TC-ERR-06 | 通用 | 所有写接口对 `null`/空字符串 body 的处理 | 应统一返回 `400` 而非 `500` |
| TC-ERR-07 | §5.1 | `customerNo` 传空字符串 | `400` |
| TC-ERR-08 | §3.2 | 矩阵接口 `sourceId` 传一个存在但**已停用**的源 | 需确认是否允许查看历史矩阵（§11.13.1 语义2：历史数据应照常可查），预期 **200 正常返回**而非因源停用被拒 |
| TC-ERR-09 | 并发 | 两个请求同时 `POST /strategies/exceptions` 针对同一客户同一元素 | 唯一键兜底，其中一个应 `409`，不应产生两条例外记录（脏读） |
| TC-ERR-10 | 并发 | 同一策略被两个用户同时修改（默认策略 `PUT`） | 后写入覆盖先写入（Upsert 语义，无乐观锁字段可用时的预期行为），确认两次都各自正确写入历史 |

### 2.14 反向用例（TC-NEG）—— 确认"明确不交付"清单确实没做，对应验收要点 #13 + §11.24-D

| 编号 | 对应不做项 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|------|
| TC-NEG-01 | 报价单"策略"角标 | 打开任意报价单元素页签 | 单价单元格**无**任何角标/图标/Popover 入口 | UI 走查 |
| TC-NEG-02 | 报价单"已改"标记 | 手改单价后 | 单元格**无**"已改"标记，**无**"恢复策略价"按钮 | UI 走查 |
| TC-NEG-03 | 无价黄底 | TC-QT-04 场景（窗口内无价） | 该单元格**就是普通空输入框**，**无**黄底背景色 | UI 走查（DOM/CSS class 检查） |
| TC-NEG-04 | 页签未取价红点 | 报价单卡片 Tabs | 「元素」Tab 标题**无**数字红点 | UI 走查 |
| TC-NEG-05 | 元素抽屉"距今天数"标记 | 打开任意元素编辑抽屉 | 各源最新价格表格**无**"N天前"字样 | UI 走查 |
| TC-NEG-06 | 元素抽屉"查看历史"按钮 | 同上 | 抽屉内**无**"查看历史"入口 | UI 走查 |
| TC-NEG-07 | 最后修改时间"变更类型·操作人"小字 | 元素列表 | 「最后修改时间」列下方**无**额外小字 | UI 走查 |
| TC-NEG-08 | 价格源"价格条数"列 | 价格源管理列表 | 列表列中**无**"价格条数"这一列 | UI 走查 |
| TC-NEG-09 | 导入"导出失败明细" | 导入结果区 | **无**该按钮（明细表格本身保留） | UI 走查（同 TC-IMP-18） |
| TC-NEG-10 | 试算"导出试算结果" | 试算结果区 | **无**该按钮（同 TC-SIM-06） | UI 走查 |
| TC-NEG-11 | 策略历史"回滚"入口 | 变更历史抽屉 | **无**任何回滚按钮/入口（同 TC-HIS-10） | UI 走查 |
| TC-NEG-12 | #13 前端零改动核验（🔴 核心） | — | `git diff --stat <base>...<feature-branch>` 圈定文件清单：`QuotationStep2.tsx` / `QuotationWizard.tsx` / `ReadonlyProductCard.tsx` / `useDriverExpansions.ts` / `usePathFormulaCache.ts` / `BulkImportPartsDrawer.tsx` / `component/types.ts` / `component/FieldConfigTable.tsx` | 以上文件**均不在 diff 列表中**（diff 为空）；另跑一次宽口径确认：`git diff --stat <base>...<feature> -- '*.tsx' | grep -iE 'quotation\|readonly\|driverexpansion\|pathformulacache\|fieldconfig'` 应无匹配 | `git diff --stat` |
| TC-NEG-13 | §1 硬约束#8（无输出元数据列） | 检查 COMP-0029/COMP-0040 视图 SQL | 视图**只输出** `单价/货币/计价单位` 三列，**没有** `__price_meta__*`/`__price_miss__*` 之类的辅助列 | 代码/配置走查 |
| TC-NEG-14 | AP-44 不触发 | 检查 COMP-0029/COMP-0040 字段配置 | `field_type` 分别维持 `INPUT_NUMBER`/`INPUT_TEXT`（报价侧）与 `BASIC_DATA`（核价侧），**未发生任何类型变更** | API：`GET /components/{id}` |

### 2.15 无回归（TC-REG）—— 对应验收要点 #9

| 编号 | 操作步骤 | 预期结果 | 验证方式 |
|------|------|------|------|
| TC-REG-01 | `CUST-1292`（未配策略）报价单其余页签（投料/回料/加工/表面处理/包装/费用/小计） | 渲染与改动前逐位一致，无异常 | UI 对比截图 |
| TC-REG-02 | 其余 3 个元素组件副本（`COMP-0029__imp1`/`COMP-0020__imp1`/`COMP-0020__imp1__imp1`）所在的客户模板 | 单价列仍为纯手填（未被本次改动接通），行为与改动前一致 | UI |
| TC-REG-03 | 跑 `quotation-flow.spec.ts` | 与干净 master 基线对比（当前基线已知 3 个失败，见 `task0712-update071501-category-axis` 记录），**新增失败数=0** | Playwright，A/B 同型对比 |
| TC-REG-04 | 未使用 `:priceBaseDate` 占位符的既有视图（挑 2~3 个非元素类组件） | 卡片值与改动前逐字节一致 | UI/API 对比 |
| TC-REG-05 | 核价单其余页签（物料BOM/工序/费用/汇总） | 渲染与改动前一致，`COST_ELEMENT` 全局变量数据仍保留在库（未被删除，回滚退路） | SQL：`SELECT count(*) FROM global_variable_value WHERE var_code='COST_ELEMENT'` 前后不变 |

---

## 3. 验收要点映射表（16 条 → 用例编号，逐条无遗漏）

| # | 验收要点摘要 | 覆盖用例 |
|---|------|------|
| 1 | 源管理：新建/编辑/停用/停用后不可选用/历史照常显示 | TC-SRC-01~13 |
| 2 | 导入：模板下载→3元素→成功回显；重导覆盖 | TC-IMP-01,02,05,06,16,17 |
| 3 | 价格表：明细筛选、矩阵行列/超期提示、导出内容一致 | TC-TBL-01~11 |
| 4 | 策略：默认+例外保存回读一致；例外优先 | TC-STR-01~24、TC-CALC-11 |
| 5 | 报价单取价（核心） | TC-CALC-01~16、TC-QT-01、TC-SIM-01 |
| 6 | 核价单取价：`_GLOBAL_`，与报价侧可不同，未配全空，不受客户影响 | TC-GLB-01~07 |
| 7 | 可覆盖：改后保存重开仍是改后值 | TC-QT-02,03 |
| 8 | 留空兜底：窗口内无价显示空非0非"加载中" | TC-QT-04、TC-CALC-14 |
| 9 | 无回归 | TC-REG-01~05、TC-QT-05、TC-XCUST-04 |
| 10 | `_GLOBAL_` 配置入口：客户列表顶部固定项/不参与搜索分页/隐藏折扣策略 Tab | TC-STR-15~17 |
| 11 | 变更历史：三种 action、筛选、只读 | TC-HIS-01~12 |
| 12 | 最后变更信息显示（默认策略卡片头 + 例外表两列） | TC-STR-21,22、TC-HIS-09 |
| 13 | 前端零改动核验 | TC-NEG-12 |
| 14 | 最后修改时间口径（导入算修改但不反写主档） | TC-ELE-07,08,09,10 |
| 15 | 元素抽屉各源最新价（3源3行/停用置灰/空态） | TC-ELE-01~06 |
| 16 | 导入部分成功（9成功入库非整批回滚，重导不重复） | TC-IMP-02,03,04,05,07 |

**补充覆盖**（非 §11.21 直接编号，但属于本轮测试重心）：
- 🔴 跨报价单串价专项 → TC-XCUST-01~05
- 权限矩阵（api.md §9） → TC-PERM-01~08
- 边界/异常状态码 → TC-ERR-01~10
- §11.24-D "明确不交付"反向核验 → TC-NEG-01~14

---

## 4. §11.24 交付物对照矩阵覆盖情况

### A. 主数据侧（10 行）
全部覆盖：屏1入口按钮→TC-SRC-12；lastModifiedAt列→TC-ELE-07~10；元素抽屉各源最新价→TC-ELE-01~06；价格源CRUD+停用语义→TC-SRC-01~10；价格导入全部子项→TC-IMP-01~18；价格表明细/矩阵→TC-TBL-01~11。

### B. 定价侧（10 行）
全部覆盖：新Tab→TC-STR-15~17；默认策略5项字段→TC-STR-01,08,24；LATEST时窗口灰置→TC-STR-08；元素例外增删改→TC-STR-04~06,19,20；最后变更信息→TC-STR-21,22；变更历史抽屉→TC-HIS-01~12；策略试算→TC-SIM-01~07。

### C. 取价链路（9 行）
全部覆盖：`element_price_strategy`表结构→TC-STR-23；`element_price_strategy_log`→TC-HIS-01~12；`f_customer_element_price`表函数→TC-CALC-01~16；`:priceBaseDate`注入→TC-XCUST-01~05；缓存维度→TC-XCUST-04；COMP-0029接通→TC-QT-01；COMP-0040接通→TC-GLB-04~06；`listAvailableElements`改读`element`主表→TC-ELE-11。

### D. 明确不交付（反向核验）
全部覆盖：TC-NEG-01~14（角标/已改/黄底/红点/距今天数/查看历史/变更类型小字/价格条数列/导出失败明细/导出试算结果/回滚入口/元数据列/AP-44不触发）。

---

## 5. 待澄清项（文档定义不够清楚，无法写出确定预期，需 PM/技术总监补充）

1. **"元素级例外逐字段覆盖默认"的实际语义边界**（§11.1 + B2 表函数 COALESCE 逻辑 vs api.md §5.3 `StrategyUpsertRequest` 契约）
   - `StrategyUpsertRequest` 要求 `sourceId`/`method` **必填**，`factor`/`premium` 缺省时服务端**固定填 1/0**（而非"继承客户默认策略的对应字段"）。也就是说例外行一旦存在，其 `source_id`/`method` 必然是自己的值，`factor`/`premium` 未传时是硬编码默认值 1/0，**不是**"继承默认策略当时配的 factor/premium"。
   - 那么 B2 SQL 里 `COALESCE(x.factor, d.factor)` 这类逐字段 COALESCE 在什么场景下真正会用到 `d`（默认）的值？依当前 API 契约，`x.factor`/`x.premium` 由 DB 列 `DEFAULT 1`/`DEFAULT 0` 保证永不为 NULL，COALESCE 恒定短路到 `x` 侧，**永远不会读到默认策略的 factor/premium**。唯一会读到默认值的字段是 `window_num`/`window_unit`——但只在例外 `method=LATEST` 时它们本就是 NULL 且 `win` CTE 会强制忽略（`CASE WHEN method='LATEST' THEN NULL`），属无害"假泄漏"。
   - **需要 PM 确认**：§11.1"逐字段覆盖"的表述，实际落地后是否等价于"例外要么整行生效（用自己的全部字段），要么整行不生效（走默认）"？如果业务方原意确实是"例外只想覆盖系数，取值方式仍想跟随默认联动变化"，当前设计**做不到**（例外一旦建立，其 method/source/factor/premium 就与默认策略脱钩，默认策略后续再怎么改都不会影响该例外）。建议在需求里明确写一句消歧语句。已设计 TC-CALC-11/12 验证当前实现的真实行为，但预期值是我基于代码逻辑推导，非需求文档直接给出的数字，需要 PM 确认这就是期望行为。

2. **原型 HTML 底部"原型定稿确认清单"表格与正文自相矛盾**（`元素价格策略-原型图.html` 行 775~798）
   - 该总结表格屏7/屏8 行仍写着"三态角标：策略 / 已改 / 无价"「与报价同值」，与同一份文件正文屏7（行680~730，已改为零角标/零改动/核价核价侧可不同）**直接矛盾**，也与需求说明.md §11.14D 第6轮"追加砍掉黄底"+§11.11 第5轮"核价可以不同"的最终裁决矛盾。
   - 该总结表格明显是原型 v7/v8 阶段遗留、v9 定稿时正文已更新但底部汇总表忘了同步。**建议 PM 确认后请前端/文档维护方顺手改掉这张表**，避免后续新人看这张"确认清单"当权威（尤其它自称"确认清单"，天然容易被当成最后一道把关）。本文档测试用例已按需求说明.md §11（真正权威口径）+屏7/8正文设计，**不受该表格误导**，特此记录供 PM 参考。

3. **边界状态码未完全明确**（TC-ERR-02~05 涉及）
   - `sourceId` 传不存在 UUID：`400` 还是 `404`？api.md 未区分"格式非法"与"引用不存在"两种 400 场景。
   - `sourceName` 超长（>128）、`customerNo` 大小写是否敏感、例外能否指向已停用元素——均未在 api.md/需求说明.md 中给出明确规则，只能留待实测阶段观察实际实现行为，若与常识预期不符需回头找 PM 定规则（而非测试工程师自行拍板"应该是什么"）。

4. **矩阵接口"跨度 90 天"是否为 off-by-one 边界**
   - "最长 90 天"—— 90 天整算不算超限？99天呢从哪天开始算超限？TC-TBL-07 按"≤90 合法，>90 非法"设计，但 api.md 原文只说"**> 90 天返 400**"，字面已经清楚（跟我的假设一致），此项经复核**不算真正的澄清缺口**，已在文档内自行消解，仅记录复核过程。

5. **TC-PERM-04（SALES_REP 可写策略）是否符合业务直觉**
   - api.md §9 明确 §5~§7（策略/试算/历史）写权限包含 `SALES_REP`，即销售代表本人可以修改自己客户的价格策略（含系数、加价）。这与"价格策略直接决定报价金额"的敏感性（§11.14F.1 原文）似乎存在张力——一般"定价权"该收在 PRICING_MANAGER/SALES_MANAGER，销售代表通常只应用策略、不应配置策略。这不是本文档能替业务方拍板的问题，故仅按 api.md 字面设计 TC-PERM-04 为"预期 200"，但请 PM 复核这是否真是业务本意，而非"沿用现有页面权限"这条通用规则套用后的意外结果。

---

## 6. 风险最高、最该重点测试的点（Top 5）

1. **🔴 跨报价单串价（TC-XCUST-01~05）**——阻断级缺陷候选。`:priceBaseDate` 是全新引入的求值维度，一旦任何一层缓存（`ComponentDriverService.expandCache` / `DataLoader.resultCache` / 前端 `driverExpansionKey`）漏加这个维度，A 报价单会读到 B 报价单缓存的价格，且**不会报错、不会有任何异常提示**——纯粹是"数字对不上但看起来一切正常"，属于最难在事后发现、最容易被漏测直接带上线的一类 bug。
2. **🔴 窗口滚动区间边界正确性（TC-CALC-05/06/08/09）**——`f_customer_element_price` 的 `BETWEEN win_from AND p_base_date` 闭区间语义 + 月/年"滚动非自然日历"语义，任何一个 `<`/`<=` 写反或用了 `date_trunc` 类的自然月函数，都会导致金额算错但**编译不报错、UI 渲染不报错**，纯粹是数字比正确值多算/少算一两条记录，人工肉眼很难发现。本文档专门设计了"边界内一条 vs 边界外一天一条"的判别数据集（Ag/Ni/Fe/Zn/Cr）来放大这类错误。
3. **🔴 先乘后加（TC-CALC-10）**——`100×1.05+2` vs `(100+2)×1.05` 只差 0.10，如果测试者用整数或"整数×小数"的懒数据（如系数=1、加价=0）验证，这个 bug 完全测不出来；必须用本文档设计的"系数≠1 且加价≠0"的判别数据（Mn：100/1.05/2→107.00 而非 107.10）才能揪出来。
4. **停用价格源第三条语义（TC-SRC-08）**——"存量策略仍能取到价"是三条停用语义里最反直觉的一条（前两条"不可再选用"符合直觉，容易实现对；第三条要求表函数**不过滤源状态**，如果开发者顺手在 `f_customer_element_price` 里加一个看似合理的 `AND s.status='ACTIVE'` 防御性判断，会导致停用一个源后一批客户的报价单突然全部无价，且这个改动本身"看起来是对的"（防御性编程直觉），review 时也容易被放过。
5. **导入"部分成功"的事务边界（TC-IMP-02/03/16）**——需求反复强调"逐行独立、失败不阻断"，但这与很多人对"批量导入"的默认心智模型（要么全成功要么全回滚，保证数据一致性）相反，如果开发者按更"稳妥"的默认心智模型实现成整批事务回滚，功能表现上不会报错，只会在"含1行错误的10行文件"场景下表现为诡异的"全部失败"或者"部分失败但成功行也没真正写库"，需要专门用 SQL 直查 `count(*)` 而非只看 API 回显数字来验证（回显数字本身可能是编造/计算出来的，不代表真的落库了）。
