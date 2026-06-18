> ⛔ **部分内容已过时（2026-05-12）**：本文「V5 六步导入向导」相关章节已被 **`2026-05-12-import-v6-staging-design.md`（V6 staging 三步流程）** 取代。阅读导入相关设计请以新文档为准；本文其余章节仍可参考。

# CPQ 系统设计 v5.1 — TBD 决策与细化设计

> ⚠️ **部分章节已废弃（2026-05-12）**：本文档中关于"V5 六步导入向导"（上传 → UI2 基础差异 → UI1 客户冲突 → UI3 孤儿行 → 写入 → 完成）及"NO_BUMP = 覆盖当前版本"语义的设计已被 **`docs/superpowers/specs/2026-05-12-import-v6-staging-design.md`** 取代。请勿再参考本文档相关章节，新设计为 staging-based 三步流程（上传文件 → 版本确认 → 创建报价单），写入事务延迟到「创建报价单」按下时提交。
>
> **日期**: 2026-04-26
> **基于**: v5.0（2026-04-25-cpq-design-v5.md）
> **核心变更**: 22 个 TBD 项全部决策，补全详细设计
> **状态**: 设计完成，可进入实施阶段
> **配套**: v5.0 主架构文档保持有效，本文档为决策细化补充

---

## 目录

1. [决策总览](#1-决策总览)
2. [业务流程类详细设计](#2-业务流程类详细设计)
3. [技术实施类详细设计](#3-技术实施类详细设计)
4. [UI 设计类详细设计](#4-ui-设计类详细设计)
5. [配置类详细设计](#5-配置类详细设计)
6. [数据模型补充](#6-数据模型补充)
7. [实施优先级](#7-实施优先级)
8. [对 v5.0 的修订](#8-对-v50-的修订)

---

## 1. 决策总览

### 1.1 业务流程类（5/5）

| 编号 | 主题 | 决策 |
|------|------|------|
| BIZ-1 | 报价单复制版本引用 | 暂不开放复制功能；未来按"场景区分 + 跟随最新"实现 |
| BIZ-2 | 跨客户料号差异化 | 基础资料全局（含电镀方案）+ 用户确认覆盖；客户差异由 mat_process/mat_fee/plating_fee 体现 |
| BIZ-3 | 导入失败回滚事务边界 | 单一大事务（全有全无） |
| BIZ-4 | 大批量导入性能 | 上限 2000 行 + 流式解析 + 批量查询 + 虚拟滚动 |
| BIZ-5 | 业务校验规则清单 | 采纳 BV-01 ~ BV-32 完整清单作为 v1 |

### 1.2 技术实施类（7/7）

| 编号 | 主题 | 决策 |
|------|------|------|
| TECH-1 | 变量路径解析器 BNF | 大小写敏感、中文 Sheet 名、允许嵌套、支持 IN/LIKE |
| TECH-2 | 公式引擎扩展函数清单 | 7 大类函数；不自动类型转换；ERROR 单元格红色 |
| TECH-3 | HTML 抓取实现 | v1 不抓取，三表保留结构；销售在报价单内手填元素单价 |
| TECH-4 | Flyway 扩列事务边界 | 运行时 ALTER + 元数据双写 + Flyway migration 生成 + DDL 全局锁 |
| TECH-5 | 产品级悲观锁 | 自适应粒度（料号级 + >100 降客户级），DB 表存储，5 分钟超时 |
| TECH-6 | 解析缓存与批量查询 | Caffeine + DataLoader + 嵌套优化 + 缓存预热 |
| TECH-7 | 多级别事务嵌套 | 主事务 REQUIRED + 审计 REQUIRES_NEW |

### 1.3 UI 设计类（9/9）

| 编号 | 主题 | 决策 |
|------|------|------|
| UI-1 | 字段级冲突处理 | 抽屉 1200px + 按"料号×表"分组折叠 |
| UI-2 | 基础资料差异确认 | 折叠面板 960px + 内联 diff（>5 字段折两列）+ 备注必填 |
| UI-3 | 元素价格中心 | v1 简化版，复用 element_daily_price (MANUAL) |
| UI-4 | 主数据维护页面 | 客户分组列表 + 全局表概览，复用导入历史菜单 |
| UI-5 | 版本对比工具 | 抽屉 1200px + 双列字段级 diff + 跨表 Tab |
| UI-6 | 历史版本管理 | 按料号分组 + 表格/JSON 切换 + 双重只读保护 |
| UI-7 | 变更日志中心 | 时序列表 + 字段悬停明细 + Excel/CSV 导出 |
| UI-8 | 报价单数据来源 Tab | DRAFT 显示 + UI-5 对比 + 抽屉详情 + 完整元数据导出 |
| UI-9 | 字段级追溯 Popover | 悬停 ⓘ + Popover/抽屉切换 + SUBMITTED 视觉区分 |

### 1.4 配置类（3/3）

| 编号 | 主题 | 决策 |
|------|------|------|
| CONF-1 | 系统配置清单 | 核心校验规则硬编码；阈值/超时/保留期/字典白名单/业务参数/cron 统一存 system_config 表 |
| CONF-2 | 字段重要性标记机制 | 3 级 (CRITICAL/IMPORTANT/NORMAL) + 内置硬编码 + 扩展存表 + 双维度 |
| CONF-3 | fetch_rule_definition 模式 | 完整 JSON Schema 采纳（v1 schema 入代码 / v2 物理表写入数据） |

---

## 2. 业务流程类详细设计

### 2.1 BIZ-1 报价单复制（v1 不开放）

**TBD 阶段评估的四个方案**（仅作历史记录）：

| 方案 | 描述 | 取舍 |
|------|------|------|
| A | 始终跟随最新版本（清空 referenced_versions/snapshot） | 弱化"复制"语义 |
| B | 保留原版本引用（DRAFT 但锁定原版本） | 与 DRAFT 跟随机制冲突 |
| C | 用户在复制对话框中选择 A / B | 增加用户决策负担 |
| D | 场景区分：同客户跟随最新；跨客户强制最新；不允许保留原版本 | 与 DRAFT/SUBMITTED 双轨机制最一致 ✓ |

**v1 决策**：暂不开放复制功能（避免设计上未充分验证）。
- 报价单管理列表**不展示"复制"按钮**
- 后端 API **不实现** `POST /api/cpq/quotations/{id}/copy`

**v2+ 实施方向**（按上述方案 D）：
- 同客户 + 同料号集 → 默认跟随最新（重新报价场景）
- 跨客户复制 → 强制跟随新客户的最新（避免引用错位）
- SUBMITTED 单复制时显示提示横幅：「数据已基于最新版本更新，原版本仅可在历史报价单中查看」
- 不允许"保留原版本"，以维持 DRAFT 跟随机制的一致性

### 2.2 BIZ-2 跨客户料号差异化

**核心原则**：基础资料是物料的本质属性（全局唯一），客户差异通过客户资料层体现。

| 维度 | 处理方式 |
|------|---------|
| 同物料同 BOM 给不同客户 | 基础资料保持全局；客户报价差异 → mat_process/mat_fee/plating_fee（已带版本） |
| 同物料但 BOM 不同（极少） | 建独立料号（如 3120012574-A、3120012574-B） |
| 电镀方案差异 | 归基础资料（全局）；导入冲突时走"用户确认覆盖"流程（即 UI-2 基础资料差异确认，详见本文档 §4.0 / §4.2） |
| 客户料号映射 | mat_customer_part_mapping 已含 customer_id，自然支持 |

### 2.3 BIZ-3 导入失败回滚

**事务策略**（与 TECH-7 配合）：
```
@Transactional(REQUIRED) executeImport(...) {
  validateAll();           // 前置校验，失败抛 ValidationException
  for each Sheet:
    upsertOrCreateVersion();
    writeChangeLog();
  writeImportRecord();
  // 任何阶段抛异常 → 整体回滚
}

// 独立事务（REQUIRES_NEW）
releaseProductLock();      // 主事务结束后必须释放
sendNotification();        // 失败不影响主流程
writeOperationLog();
```

### 2.4 BIZ-4 大批量导入性能

**性能优化措施**：

| 优化点 | 实现 |
|-------|------|
| Excel 解析 | Apache POI SAX (`XSSFReader`)，避免 DOM 内存爆炸 |
| 差异检测 | 按 `(customer_id, hf_part_no IN (...))` 批量查询当前版本 |
| 冲突 UI | `react-window` 虚拟滚动，仅渲染可视行 |
| 产品悲观锁 | 一次 INSERT 含所有料号（DB 批量插入） |
| 导入预览 | 异步任务 + 进度条（2000 行预计 < 10s） |

**硬上限**：单次 2000 行；超过则前端校验拒绝并提示"请拆分导入"。

### 2.5 BIZ-5 业务校验规则清单（BV-01 ~ BV-32）

> 见原 TBD 讨论中的完整表格。本节固化为 v1 实施清单。

> **命名约定**：每条校验项用「字段中文名（表名.英文字段）」明确指向，避免"不良率"这类同名字段在不同表/语义下被 QA 误判。

#### 基础资料层
| 编号 | 校验项 | 涉及字段 | 级别 | 默认阈值 |
|------|-------|---------|------|---------|
| BV-01 | 元素 BOM 含量合计 = 100% | `mat_bom.composition_pct` (bom_type=ELEMENT) | 警告 | ±1% 容差 |
| BV-02 | 单重 > 0 | `mat_part.unit_weight` | 阻塞 | - |
| BV-03 | 来料 BOM 损耗率/不良率范围 | `mat_bom.loss_rate` / `mat_bom.defect_rate` (bom_type=INCOMING) | 警告 | [0%, 50%] |
| BV-04 | 来料 BOM 净用量 ≤ 毛用量 | `mat_bom.net_qty` ≤ `mat_bom.gross_qty` (bom_type=INCOMING) | 阻塞 | - |
| BV-05 | 电镀方案的镀层厚度 > 0 | `plating_plan.coating_thickness` | 阻塞 | - |
| BV-06 | 客户料号映射唯一 | `mat_customer_part_mapping.(customer_id, customer_product_no)` | 阻塞 | - |

#### 客户资料层
| 编号 | 校验项 | 涉及字段 | 级别 | 默认阈值 |
|------|-------|---------|------|---------|
| BV-10 | 组成件单价 > 0 | `mat_process.unit_price` | 警告 | - |
| BV-11 | 组成件数量 > 0 | `mat_process.quantity` | 警告 | - |
| BV-12 | 组成件 BOM 序号唯一 | `mat_process.(customer_id, hf_part_no, version, seq_no, sub_seq_no)` | 阻塞 | - |
| BV-13 | 涨价比例范围 | `mat_fee.settlement_rise_ratio` (fee_type=INCOMING_FIXED) | 警告 | [-50%, 100%] |
| BV-14 | 组装报废率/电镀不良率范围 | `mat_fee.reject_rate` (fee_type=ASSEMBLY_PROCESS) / `plating_fee.defect_rate` | 警告 | [0%, 30%] |
| BV-15 | 货币代码合法 | `currency` (mat_process / mat_fee / plating_fee) | 阻塞 | 白名单 USD/CNY/EUR/... |
| BV-16 | 单位代码合法 | `price_unit` / `quantity_unit` (mat_process / mat_fee / plating_fee) | 阻塞 | 白名单 KG/PCS/M/... |
| BV-17 | 引用的电镀方案存在 | `plating_fee.(plating_plan_code, plan_version)` → `plating_plan.(plan_code, version)` | 阻塞 | - |
| BV-18 | 引用的组成件料号存在 | `mat_process.component_part_no` → `mat_part.part_no`（含本次导入新建） | 阻塞 | - |

#### 元素价格层（v1 element_price 表无数据，本节 v2 启用）
| 编号 | 校验项 | 涉及字段 | 级别 | 默认阈值 |
|------|-------|---------|------|---------|
| BV-20 | source_id / fetch_rule_id 必须存在 | `element_price.source_id` / `element_price.fetch_rule_id` | 阻塞（v2） | - |
| BV-21 | 升水价 ≥ 0 | `element_price.premium_price` | 警告 | - |
| BV-22 | 元素名在 mat_bom (ELEMENT) 中存在或为 'ALL' | `element_price.element_name` ↔ `mat_bom.element_name` | 警告 | - |

#### 跨表校验
| 编号 | 校验项 | 涉及字段 | 级别 | 默认阈值 |
|------|-------|---------|------|---------|
| BV-30 | 报价用 Excel 中所有费用引用的料号必须有对应基础资料 BOM | `mat_fee.hf_part_no` / `plating_fee.hf_part_no` ↔ `mat_bom.hf_part_no` | 阻塞 | - |
| BV-31 | 客户资料的客户必须与导入选择的客户一致 | `mat_process.customer_id` / `mat_fee.customer_id` / `plating_fee.customer_id` ↔ 导入选择 | 阻塞 | - |
| BV-32 | 同客户同料号在客户资料中无悬空引用 | 跨 mat_process / mat_fee / plating_fee 行间引用一致性 | 警告 | - |

---

## 3. 技术实施类详细设计

### 3.1 TECH-1 变量路径 BNF 语法

```bnf
variable_path     ::= "{" path_expr "}"
path_expr         ::= table_ref ( "." field_ref )?
table_ref         ::= identifier ( "[" filter_expr "]" )?
filter_expr       ::= filter_term ( ( "AND" | "," ) filter_term )*
filter_term       ::= identifier op operand
op                ::= "=" | "!=" | ">" | "<" | ">=" | "<=" | "IN" | "LIKE"
operand           ::= literal | variable_ref | path_expr     -- 允许嵌套
literal           ::= string_literal | number_literal | boolean_literal | array_literal
array_literal     ::= "[" literal ( "," literal )* "]"        -- IN 操作符使用
string_literal    ::= "'" any_char_except_quote* "'"
number_literal    ::= [0-9]+ ( "." [0-9]+ )?
boolean_literal   ::= "true" | "false"
variable_ref      ::= "$" identifier
field_ref         ::= identifier
identifier        ::= ( chinese_char | letter ) ( chinese_char | letter | digit | "_" | "(" | ")" | "%" )*
```

**核心约定**：
- **大小写敏感**（中文不受影响；英文字段严格匹配）
- 表引用使用**中文 Sheet 名**（如 `元素BOM`），通过 `BasicDataConfig` 元数据映射到物理表
- 允许嵌套子查询（如 `{表A[字段=表B[条件].字段].结果}`）
- 支持 `IN` / `LIKE` 操作符
- 嵌套深度上限 v1 限定为 **3 层**（避免性能问题）

**解析示例**：

```
{元素BOM[元素='Ag'].组成含量(%)}
→ SELECT composition_pct FROM mat_bom
  WHERE bom_type='ELEMENT' AND element_name='Ag' AND hf_part_no=:p

{组成件BOM[组成件料号 IN ['C001','C002','C003']].单价}
→ SELECT unit_price FROM mat_process
  WHERE component_part_no IN ('C001','C002','C003') AND ...

{组成件BOM[组成件料号=元素BOM[元素='Ag'].input_material_no].单价}
→ 嵌套查询，先解析内层 → 外层用结果作为 filter
```

### 3.2 TECH-2 公式引擎扩展函数清单

#### 函数清单

| 类别 | 函数 | 语义 |
|------|------|------|
| **路径** | `LOOKUP(table, conditions, field)` | 单值查询，无结果返 NULL，多结果取首行 |
| | `LOOKUP_OR(table, conditions, field, default)` | 同上，无结果返 default |
| | `EXISTS(table, conditions)` | 存在性检查 |
| **聚合** | `SUM(table, conditions, field)` | 求和，空集返 **0** |
| | `AVG(table, conditions, field)` | 平均，空集返 **NULL** |
| | `MAX(table, conditions, field)` | 最大，空集返 NULL |
| | `MIN(table, conditions, field)` | 最小，空集返 NULL |
| | `COUNT(table, conditions)` | 行数，含 NULL |
| | `COUNT_DISTINCT(table, conditions, field)` | 去重计数 |
| **条件** | `IF(cond, t, f)` | 三元 |
| | `IFNULL(v, fallback)` | COALESCE 等价 |
| | `CASE(cond1, v1, ..., default)` | 多分支 |
| | `SWITCH(expr, k1, v1, ..., default)` | 等值匹配 |
| **数学** | `ROUND(v, decimals)` | 四舍五入 |
| | `CEIL(v)` / `FLOOR(v)` / `ABS(v)` / `POW(b, e)` | 数学基础 |
| | `MIN_OF(a, b, c)` / `MAX_OF(a, b, c)` | 多值最值 |
| **字符串** | `CONCAT(a, b, c)` | 拼接 |
| | `TO_NUMBER(s)` / `TO_STRING(v)` | 显式类型转换（失败返 NULL） |
| **货币** | `EXCHANGE(amount, from, to, date?)` | 汇率换算（查 exchange_rate） |
| | `TAX_INCLUDED(price, customer_id)` | 含税价 |
| | `TAX_EXCLUDED(price, customer_id)` | 不含税价 |
| **元素价格** | `ELEMENT_PRICE(element, customer_id?, date?)` | 取实际单价（v2 走 fetch_rule + element_price + daily_price；v1 不可用，由销售在报价单内手填） |
| | `PREMIUM_PRICE(element, customer_id)` | 仅升水价（v2 启用，v1 不可用） |

#### 类型与错误处理

- **不自动类型转换**：算术运算遇到字符串直接报错（用户须显式 `TO_NUMBER`）
- **错误处理**：单元格运行时错误 → 单元格标记 ERROR + 红色边框；公式整体不中断
- **报价单整体**：含 ERROR 单元格的报价单可保存为 DRAFT，**提交前必须清零所有 ERROR**

### 3.3 TECH-3 元素单价 v1 策略（不抓取）

**v1 涉及三个独立概念**（彼此不可混用）：

| 概念 | v1 存储位置 | 写入路径 | 读取路径 |
|------|------------|---------|---------|
| **报价单实际单价**（用于报价计算） | `QuotationLineComponentData.row_data` JSONB | 销售在报价生成器中手动填写 | 公式引擎从 row_data 读取；不读 element_price/daily_price 任何表 |
| **管理员参考价**（仅作填价时的提示，不参与计算） | `element_daily_price`（`fetch_status=MANUAL`，`manually_filled_by` 标记录入人）| 系统管理员在元素价格中心页面 [+ 录入参考价] 按钮录入 | 销售填报价单元素单价时，前端在输入框旁展示最近的参考价 |
| **历史报价快照** | `Quotation.referenced_versions.element_actual_prices` JSONB | 报价单 SUBMITTED 时由系统计算并写入 | 历史报价单详情、数据来源 Tab 只读访问 |

**v1 各表实际状态**：

| 表 | v1 状态 |
|----|--------|
| `element_price_source` | 表结构保留，**无数据写入** |
| `element_price_fetch_rule` | 表结构保留，**无数据写入** |
| `element_price` | 表结构保留，**v1 不写入任何记录**（v1 销售无需为客户预设取价规则） |
| `element_daily_price` | 表结构保留，**仅写入 `fetch_status=MANUAL` 的管理员参考价行**（自动抓取的 SUCCESS/FAILED 行 v1 无） |
| `QuotationLineComponentData.row_data` | **v1 元素实际单价的唯一来源** |

**v2 升级路径**：
1. 启用 `element_price_source` 配置 + 抓取定时任务（写入 `element_daily_price` 的 SUCCESS 行）
2. 启用 `element_price` 客户级配置（含 `fetch_rule_id` 关联）
3. 报价时优先走自动取价（`ELEMENT_PRICE` 函数），失败才走手动填写
4. v1 的手动填写数据无需迁移（保留在 row_data，历史数据不动）
5. v1 的管理员参考价（element_daily_price MANUAL 行）继续保留，与新抓取数据共存

### 3.4 TECH-4 Flyway 扩列实现

**实现流程**：

```
1. 管理员在「主数据中心 → 数据结构管理」点击 [+ 添加列]
   表单：表名、列名、数据类型（白名单）、注释
   
2. 后端校验:
   - 列名规则: ^[a-z][a-z0-9_]{0,62}$
   - 类型白名单: VARCHAR(n) / DECIMAL(p,s) / INT / BOOLEAN / DATE / TIMESTAMP / JSONB
   - 不允许 NOT NULL（避免大表写默认值）
   - 不允许 ADD COLUMN 同名（即使物理删除后）
   
3. 获取 ddl_operation_lock（pg_advisory_lock 全局锁，5 分钟超时）
   
4. 双写:
   BEGIN;
     INSERT INTO BasicDataAttribute (table_name, field_name, data_type, ...)
       VALUES (...);
   COMMIT;
   
   ALTER TABLE {table_name} ADD COLUMN {field_name} {data_type};
   -- DDL 自动提交
   
5. 写 Flyway migration 文件:
   db/migration/V{timestamp}__add_{table}_{column}.sql
   
6. 失败回滚:
   元数据写完 + ALTER 失败 → DELETE FROM BasicDataAttribute WHERE id=...
   ALTER 成功 + 元数据写失败（极小概率） → 告警系统管理员，需人工修复
   
7. 释放 ddl_operation_lock
```

**新增表**：

```
ddl_operation_lock {
  lock_key    VARCHAR(64) PK   -- 'global'
  locked_by   UUID FK → User
  locked_at   TIMESTAMP
  expires_at  TIMESTAMP
}
```

### 3.5 TECH-5 产品级悲观锁实现

**新增表**：

```
product_import_lock {
  id            UUID PK
  customer_id   UUID FK → Customer
  part_no       VARCHAR(64) (nullable)   -- NULL = 客户级锁
  locked_by     UUID FK → User
  import_record_id UUID FK (nullable)
  locked_at     TIMESTAMP
  last_heartbeat_at TIMESTAMP
  expires_at    TIMESTAMP                  -- locked_at + 5min，每次心跳更新
  status        ENUM [ACTIVE, RELEASED, EXPIRED]
}

唯一约束: (customer_id, part_no) WHERE status='ACTIVE'  -- 部分唯一索引
索引: (locked_by, status), (expires_at, status)
```

**锁获取流程**：

```
acquireLocks(customer_id, part_no_list, user_id):
  if part_no_list.size() > 100:
    -- 降级为客户级锁
    INSERT product_import_lock (customer_id, part_no=NULL, ...)
  else:
    INSERT product_import_lock (customer_id, part_no, ...) FOR EACH
  
  冲突时:
    返回错误："料号 X 正在被 [user] 导入（开始于 N 分钟前），请稍后重试"
```

**心跳与超时**：

```
前端: 每 30 秒 POST /api/cpq/import/locks/{id}/heartbeat
  → 更新 last_heartbeat_at + expires_at = now() + 5min

后端定时任务: @Scheduled(every="60s")
  UPDATE product_import_lock SET status='EXPIRED'
  WHERE status='ACTIVE' AND expires_at < NOW();

锁释放（事务结束）:
  REQUIRES_NEW 事务中 UPDATE status='RELEASED' (与 TECH-7 配合)
  finally 块兜底
```

#### TECH-4 ↔ TECH-5 两锁协议（新增）

**互斥规则**：DDL 操作（扩列）与产品导入是互斥关系，二者不可并发。

| 当前持有 | 后到请求 | 处理 |
|---------|---------|------|
| 任意活跃 product_import_lock | 请求 ddl_operation_lock | **拒绝** + 提示"存在 N 个进行中的导入，请待其完成或被释放后重试" |
| 活跃 ddl_operation_lock | 请求 product_import_lock | **拒绝** + 提示"系统正在执行结构变更，请稍后重试（约 N 秒）" |
| 活跃 ddl_operation_lock | 请求 ddl_operation_lock | **拒绝** + 提示"已有 DDL 操作正在进行" |

**优先级**：导入业务为常态、扩列为低频运维 → 默认偏向"已开始的导入优先完成"。系统管理员可在「锁监控」页面强制释放卡死的导入锁后再做扩列。

**实现要点**：

```sql
-- ddl_operation_lock 设计：单行（lock_key='global'）+ UPSERT 模式

acquireDdlLock(operator_user_id):
  BEGIN;
    -- 1. 检查是否有活跃的导入锁
    PERFORM 1 FROM product_import_lock
      WHERE status='ACTIVE' AND expires_at > NOW()
      LIMIT 1
      FOR UPDATE SKIP LOCKED;
    IF FOUND THEN RAISE '存在进行中的导入'; END IF;

    -- 2. UPSERT 全局 DDL 锁行（覆盖已过期的旧行）
    INSERT INTO ddl_operation_lock (lock_key, locked_by, locked_at, expires_at)
      VALUES ('global', :operator_user_id, NOW(), NOW() + INTERVAL '5 minutes')
    ON CONFLICT (lock_key) DO UPDATE
      SET locked_by  = EXCLUDED.locked_by,
          locked_at  = EXCLUDED.locked_at,
          expires_at = EXCLUDED.expires_at
      WHERE ddl_operation_lock.expires_at < NOW();   -- 仅旧行已过期才允许覆盖
    -- 若 ON CONFLICT 未发生更新（旧行还在有效期内），插入返回 0 行 → 视为冲突拒绝
    IF NOT FOUND THEN RAISE '已有 DDL 操作正在进行'; END IF;
  COMMIT;

acquireProductImportLock(customer_id, part_no_list, user_id):
  BEGIN;
    -- 1. 检查 DDL 锁是否活跃
    PERFORM 1 FROM ddl_operation_lock
      WHERE lock_key='global' AND expires_at > NOW()
      FOR UPDATE;
    IF FOUND THEN RAISE '系统正在执行结构变更'; END IF;

    -- 2. 插入 product_import_lock（自适应粒度由 §3.5 规则决定）
    INSERT INTO product_import_lock (...)
      VALUES (...);
  COMMIT;

releaseDdlLock(operator_user_id):
  -- 显式释放：将 expires_at 置为过去，让下次 acquire 通过 ON CONFLICT 覆盖
  UPDATE ddl_operation_lock
    SET expires_at = NOW() - INTERVAL '1 second'
    WHERE lock_key='global' AND locked_by = :operator_user_id;
```

**ddl_operation_lock 行清理策略**：
- **不物理删除**，行始终存在（PK lock_key='global' 单行）
- 已释放/过期 → `expires_at < NOW()`，下次 `acquireDdlLock` 通过 `ON CONFLICT DO UPDATE` 覆盖即可
- 这样无需独立清理任务，状态完全由 `expires_at` 驱动

> 备注：PostgreSQL 11+ 简单 `ADD COLUMN`（不带 DEFAULT 或带常量 DEFAULT）实质为元数据操作不锁数据行，但保守互斥能避免并发期间元数据缓存不一致风险，且扩列频率极低，互斥代价可接受。

**管理员强制释放**：「系统管理 → 锁监控」页面，可强制 RELEASED。

### 3.6 TECH-6 解析缓存与批量查询

**Caffeine 缓存层**：

```
@Singleton
public class FormulaCache {
  private final Cache<String, AST> astCache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterAccess(Duration.ofHours(2))
    .recordStats()
    .build();
    
  private final Cache<String, SqlTemplate> sqlCache = ...;
  private final Cache<String, ColumnMetadata> metadataCache = ...;
}
```

**DataLoader 模式**：

```java
public class VariablePathDataLoader {
  private final Map<String, List<Pair<Filter, CompletableFuture<?>>>> pendingLoads;
  
  public CompletableFuture<Object> load(String path, Filter filter) {
    // 注册到 pending
    return registerAndScheduleBatch(path, filter);
  }
  
  // tick 边界批量执行
  private void executeBatch() {
    for ((path, requests) in pendingLoads) {
      // 合并所有 filter 为单一 IN 查询
      List<Filter> filters = requests.map(Pair::getFirst);
      Map<Filter, Object> results = batchQuery(path, filters);
      // 分发到各 future
      for ((filter, future) in requests) {
        future.complete(results.get(filter));
      }
    }
  }
}
```

**嵌套支持**：DataLoader 多 tick 自然支持，外层 tick 完成后触发内层 tick。

**缓存预热**：
```
@PostConstruct + 模板发布事件 →
  for each 模板列公式:
    parseAst(formula) → 写入 astCache
    compileSql(ast) → 写入 sqlCache
```

### 3.7 TECH-7 多级别事务嵌套

**事务声明矩阵**：

| 操作 | 传播策略 | 隔离级别 |
|------|---------|---------|
| 导入主流程 (executeImport) | REQUIRED | READ_COMMITTED |
| 业务数据写入 (UPSERT/版本迭代) | REQUIRED（加入主事务） | - |
| change_log 写入 | REQUIRED（加入主事务） | - |
| import_record 写入 | REQUIRED（加入主事务） | - |
| **OperationLog 写入** | REQUIRES_NEW | READ_COMMITTED |
| **Notification 发送** | REQUIRES_NEW | READ_COMMITTED |
| **product_import_lock 释放** | REQUIRES_NEW | READ_COMMITTED |
| **lock 心跳更新** | REQUIRES_NEW | READ_COMMITTED |

**保证机制**：
- 锁释放：REQUIRES_NEW + finally 块双保险
- 独立事务失败 → 写错误日志 + 关键失败上报告警
- v1 不引入领域事件机制

---

## 4. UI 设计类详细设计

> 详见各 TBD 原始讨论中的布局示意图。本节列要点。

### 4.0 UI-1 与 UI-2 触发条件与执行顺序（关键澄清）

UI-1 和 UI-2 是**导入流程步骤 5**（v5.0 §8.2）的两个**独立子流程**，按数据分类触发：

| 维度 | UI-1 字段级冲突处理 | UI-2 基础资料差异确认 |
|------|------------------|---------------------|
| 适用数据 | **客户资料**（mat_process / mat_fee / plating_fee） | **基础资料**（mat_part / mat_bom / plating_plan / mat_customer_part_mapping） |
| 触发条件 | 任一料号的当前版本字段值 ≠ 导入版本字段值 | 任一记录有 CREATE / UPDATE 差异 |
| 决策粒度 | 字段级（逐字段保留/采用） | 整体级（全部确认 或 取消） |
| 是否产生新版本 | 是（NEW_VERSION） | 否（UPSERT 覆盖） |
| 中途可取消 | 可"保存草稿"中断后恢复 | 仅"取消"放弃整批 |
| 备注 | 可选（按料号备注） | 必填（无版本回滚的唯一审计线索） |

**同一次导入可能两者都触发**（典型场景：客户既送了基础资料更新，也送了客户报价资料）。执行顺序：

```
导入步骤 5 — 用户处理冲突:
  if 基础资料有差异:
    打开 UI-2 (960px Drawer) → 用户整体确认 / 取消
    用户取消 → 整次导入终止
    用户确认 → 写入待提交队列（不立即写库），继续

  if 客户资料有冲突:
    打开 UI-1 (1200px Drawer) → 用户字段级决策
    用户中途离开 → 自动保存草稿（500ms 防抖）+ 锁继续持有，可恢复
    用户取消 → 整次导入终止（释放锁、回收 UI-2 队列）
    用户确认（所有字段决策完成）→ 进入步骤 6 事务执行

  if 仅其中一类有差异:
    仅打开对应抽屉

  if 两类均无差异:
    跳过步骤 5，直接进入步骤 6
```

> BIZ-2（电镀方案差异）走 **UI-2**（plating_plan 是基础资料），不走 UI-1。

### 4.1 UI-1 字段级冲突处理

- **触发**: 客户资料表（mat_process / mat_fee / plating_fee）字段级差异（详见 §4.0）
- **抽屉宽度**: 1200px
- **分组**: 按"料号 × 表"折叠（默认展开有冲突的）
- **重要性**: ⭐⭐ CRITICAL / ⭐ IMPORTANT / NORMAL，CRITICAL 顶部排序
- **批量层级**: 全局 / 节级（料号+表）/ 单字段
- **默认值**: "采用导入"
- **草稿保存**: 自动（500ms 防抖）+ 手动按钮
- **存储**: `import_record.conflict_resolution_state JSONB`

### 4.2 UI-2 基础资料差异确认

- **触发**: 基础资料表（mat_part / mat_bom / plating_plan / mat_customer_part_mapping）整体差异（详见 §4.0）
- **抽屉宽度**: 960px
- **布局**: 按表分组折叠面板，有变化默认展开
- **diff 形式**: ≤5 字段内联（"旧 → 新"），>5 字段折叠为左右两列
- **备注**: 必填（基础资料无版本回滚的唯一审计线索）
- **行为**: 仅"全部确认 / 取消"，不允许部分排除

### 4.3 UI-3 元素价格中心（v1 简化版）

- **页面定位**: 元素清单 + 历史报价单使用记录 + 管理员参考价
- **数据源**:
  - 元素清单：基于 mat_bom (ELEMENT) 聚合
  - 历史使用：`QuotationLineComponentData.row_data` 中的元素价格记录
  - 参考价：复用 `element_daily_price` (fetch_status=MANUAL, manually_filled_by)
- **报价单内提示**:
  ```
  Ag 单价: [____] ¥/g
    📊 参考价: 406.00 (王经理 04-25)        ← 管理员参考价
    📊 本客户最近: 405.50 (04-23)            ← 本客户优先
    📊 跨客户最近: 403.20 (罗格朗 04-22)     ← 补充
    📊 30天平均: 402.30
  ```
- **趋势图**: 数据少时不强制展示，标注"基于已有报价单数据"

### 4.4 UI-4 主数据维护页面

- **首页布局**:
  - 客户分组列表（料号数 / 当前版本 / 完整度 / 最近更新）
  - 全局基础资料状态（mat_part / mat_bom / plating_plan / mat_customer_part_mapping）
  - 最近导入记录（10 条）
- **完整度指标 v1**: 按表数量计算（7 张表覆盖比例）；v2 升级为业务字段完整度
- **新客户首次导入**: 引导先在「客户管理」录入客户，再回主数据中心导入
- **导入历史**: 复用「报价中心 → 导入历史」，按 `import_type` 筛选

### 4.5 UI-5 版本对比工具

- **抽屉宽度**: 1200px
- **布局**: 左右双列对比 + 字段级 diff（数值变化显示百分比）
- **默认对比**: 当前版本 vs 上一版本
- **跨表**: 顶部 Tab 切换 mat_process / mat_fee / plating_fee
- **导出**: Excel/PDF，用户选择"纯差异 / 完整快照"
- **被以下入口调用**:
  - UI-6 历史版本管理 → [对比]
  - UI-7 变更日志中心 → [对比]
  - UI-8 数据来源 Tab → [对比最新版本]

### 4.6 UI-6 历史版本管理

- **筛选**: 客户 / 表 / 料号 / 版本范围 / 状态 / 时间
- **默认视图**: 按料号分组（销售经理常用视角）
- **版本详情**: 表格视图 + 可切换 JSON 模式
- **批量操作**: v1 仅单个版本（软删除是敏感操作）
- **只读保护**:
  - 前端：disabled 编辑入口（历史版本零编辑按钮）
  - 后端：API 拒绝写入历史版本（HTTP 403）
- **强保护**:
  - 软删除按钮 disabled + tooltip 当被报价单引用
  - 仅系统管理员可见 [恢复] 按钮（status=DELETED → ACTIVE）

### 4.7 UI-7 变更日志中心

- **筛选**: 表 / 记录 / 用户 / 类型 / 时间 / 导入 / 客户
- **默认视图**: 时序列表
- **视图切换**: 时序 / 按导入分组 / 按记录分组
- **变更摘要**: 固定字段数（"5 字段"）+ 鼠标悬停显示关键字段名
- **导出**: Excel + CSV 二选一
- **保留期**: 5 年（system_config 可调），超期自动清理；被 SUBMITTED 报价单引用的强制保留

### 4.8 UI-8 报价单数据来源 Tab

- **DRAFT 状态**: 显示 Tab + "当前跟随最新版本"提示 + 列 is_current 版本号
- **SUBMITTED 状态**: 显示完整快照 + 引用版本
- **板块**:
  1. 状态总览（快照时间、完整度）
  2. 基础资料快照（含跳转 `查看快照详情` 抽屉）
  3. 客户资料引用版本（含 `跳转该版本` `对比最新` 按钮）
  4. 元素单价（v1 标注"销售手动填写"）
  5. 审计信息（提交/审批时间、引用导入记录）
- **导出**: 快照数据 + 完整元数据（版本号、时间、人员）

### 4.9 UI-9 字段级追溯 Popover

- **触发**: 鼠标悬停显示 ⓘ → 点击弹出 Popover（轻量），可"展开详情"切换为抽屉
- **类型分支**:
  1. 直接引用字段值
  2. 公式计算结果（默认收起明细）
  3. 手动填写值（含填写时的参考价）
  4. 基础资料快照值（含 ⚠ 当前最新值变更提示）
- **SUBMITTED 视觉区分**: 不同背景色 + "快照于 X 时间"注释
- **复制完整链路**: Popover 底部按钮，便于审计沟通

---

## 5. 配置类详细设计

### 5.1 CONF-1 系统配置

**职责划分**：

| 类型 | 存储位置 | 举例 |
|------|---------|------|
| **核心校验规则** | 代码硬编码（不允许运行时修改） | "净用量必须 ≤ 毛用量"、"业务键唯一性"、"必填字段非空"等结构性规则 |
| **可调配置项**（统一存于 system_config 表） | DB 表 | 阈值（损耗率上限）、超时（锁超时秒数）、保留期（变更日志年数）、字典白名单（货币/单位）、业务参数（毛利率门槛）、定时表达式（cron） |

system_config 表覆盖 5 个类别：`validation` / `import` / `retention` / `element_price` / `business`。常量白名单（如 `validation.allowed_currencies`、`validation.allowed_units`）也归入 `validation` 类别（理由：白名单需要业务侧调整能力，不应硬编码导致每次新增币种都发版）。

**新增表**:

```
system_config {
  config_key       VARCHAR(128) PK
  config_value     TEXT
  default_value    TEXT
  data_type        ENUM [STRING, NUMBER, BOOLEAN, JSON]
  category         VARCHAR(32)        -- validation/import/retention/element_price/business
  description      TEXT
  modifiable_by    VARCHAR(32)        -- 角色限定: SYSTEM_ADMIN, SALES_MANAGER, etc.
  updated_by       UUID FK → User
  updated_at       TIMESTAMP
}

索引: (category)
```

**初始配置项**（v1）:

```sql
-- 通用校验
INSERT INTO system_config VALUES
  ('validation.completeness_threshold', '0.8', '0.8', 'NUMBER', 'validation', '元素价格抓取数据完整度阈值（v2启用）', 'SYSTEM_ADMIN'),
  ('validation.composition_tolerance', '0.01', '0.01', 'NUMBER', 'validation', '元素 BOM 含量合计容差', 'SYSTEM_ADMIN'),
  ('validation.loss_rate_max', '0.5', '0.5', 'NUMBER', 'validation', '损耗率上限', 'SYSTEM_ADMIN'),
  ('validation.defect_rate_max', '0.3', '0.3', 'NUMBER', 'validation', '不良率上限', 'SYSTEM_ADMIN'),
  ('validation.assembly_reject_rate_max', '0.3', '0.3', 'NUMBER', 'validation', '组装报废率上限', 'SYSTEM_ADMIN'),
  ('validation.price_rise_min', '-0.5', '-0.5', 'NUMBER', 'validation', '涨价比例下限', 'SYSTEM_ADMIN'),
  ('validation.price_rise_max', '1.0', '1.0', 'NUMBER', 'validation', '涨价比例上限', 'SYSTEM_ADMIN'),
  ('validation.import_max_rows', '2000', '2000', 'NUMBER', 'validation', '单次导入硬上限', 'SYSTEM_ADMIN'),
  ('validation.allowed_currencies', '["USD","CNY","EUR","HKD","JPY"]', '...', 'JSON', 'validation', '允许货币代码', 'SYSTEM_ADMIN'),
  ('validation.allowed_units', '["KG","G","PCS","M","CM","MM"]', '...', 'JSON', 'validation', '允许单位代码', 'SYSTEM_ADMIN');

-- 性能/超时
INSERT INTO system_config VALUES
  ('import.product_lock_timeout_seconds', '300', '300', 'NUMBER', 'import', '产品悲观锁总超时', 'SYSTEM_ADMIN'),
  ('import.product_lock_heartbeat_seconds', '30', '30', 'NUMBER', 'import', '锁心跳间隔', 'SYSTEM_ADMIN'),
  ('import.product_lock_downgrade_threshold', '100', '100', 'NUMBER', 'import', '锁降级阈值', 'SYSTEM_ADMIN'),
  ('import.preview_response_timeout_seconds', '30', '30', 'NUMBER', 'import', '预览响应超时', 'SYSTEM_ADMIN'),
  ('import.draft_save_debounce_ms', '500', '500', 'NUMBER', 'import', '草稿保存防抖', 'SYSTEM_ADMIN'),
  ('import.ddl_lock_timeout_seconds', '300', '300', 'NUMBER', 'import', 'DDL 全局锁超时', 'SYSTEM_ADMIN');

-- 保留期
INSERT INTO system_config VALUES
  ('retention.change_log_years', '5', '5', 'NUMBER', 'retention', '变更日志保留年数', 'SYSTEM_ADMIN'),
  ('retention.original_excel_months', '12', '12', 'NUMBER', 'retention', '原始 Excel 保留月数', 'SYSTEM_ADMIN'),
  ('retention.element_daily_price_years', '0', '0', 'NUMBER', 'retention', '元素每日价格保留年数（0=永久）', 'SYSTEM_ADMIN');

-- 元素价格（v2 启用）
INSERT INTO system_config VALUES
  ('element_price.fetch_cron', '0 0 8 * * ?', '0 0 8 * * ?', 'STRING', 'element_price', '抓取定时任务cron', 'SYSTEM_ADMIN'),
  ('element_price.fetch_timeout_seconds', '30', '30', 'NUMBER', 'element_price', '抓取单源超时', 'SYSTEM_ADMIN'),
  ('element_price.fetch_retry_count', '3', '3', 'NUMBER', 'element_price', '抓取重试次数', 'SYSTEM_ADMIN'),
  ('element_price.fetch_alert_consecutive_failures', '3', '3', 'NUMBER', 'element_price', '连续失败告警阈值', 'SYSTEM_ADMIN');

-- 业务参数
INSERT INTO system_config VALUES
  ('business.gross_margin_warning_min', '0.15', '0.15', 'NUMBER', 'business', '毛利率警告阈值', 'SALES_MANAGER'),
  ('business.gross_margin_block_min', '0.05', '0.05', 'NUMBER', 'business', '毛利率阻止提交阈值', 'SALES_MANAGER');
```

**配置变更**: 直接生效（操作日志记录），不需双人确认。

**UI 入口**: 「系统管理 → 系统配置」+ 各业务模块快捷入口（如导入页面顶部"调整阈值"链接跳转过去）。

### 5.2 CONF-2 字段重要性

**扩展 BasicDataAttribute 表**:

```sql
ALTER TABLE BasicDataAttribute
  ADD COLUMN importance_level VARCHAR(16) DEFAULT 'NORMAL',
  ADD COLUMN affects_calculation BOOLEAN DEFAULT false;
```

**3 级体系**:
- **CRITICAL** ⭐⭐ 直接影响金额/计算
- **IMPORTANT** ⭐ 影响业务逻辑
- **NORMAL** 一般字段

**双维度**:
- `importance_level`: 影响 UI 排序与批量快捷
- `affects_calculation`: 影响公式引擎缓存失效（变更时强制重新计算）

**系统内置字段重要性清单（v1）**:

```yaml
mat_part:
  unit_weight: { importance: CRITICAL, affects_calc: true }
  part_no: { importance: CRITICAL, affects_calc: true }
  part_name: { importance: NORMAL, affects_calc: false }
  category_id: { importance: IMPORTANT, affects_calc: false }
  specification: { importance: NORMAL, affects_calc: false }

mat_bom:
  gross_qty: { importance: CRITICAL, affects_calc: true }
  net_qty: { importance: CRITICAL, affects_calc: true }
  loss_rate: { importance: CRITICAL, affects_calc: true }
  defect_rate: { importance: CRITICAL, affects_calc: true }
  composition_pct: { importance: CRITICAL, affects_calc: true }
  element_name: { importance: IMPORTANT, affects_calc: true }
  input_material_no: { importance: IMPORTANT, affects_calc: false }

mat_process:
  quantity: { importance: CRITICAL, affects_calc: true }
  unit_price: { importance: CRITICAL, affects_calc: true }
  freight: { importance: CRITICAL, affects_calc: true }
  currency: { importance: IMPORTANT, affects_calc: true }
  price_unit: { importance: IMPORTANT, affects_calc: true }
  component_part_no: { importance: IMPORTANT, affects_calc: false }
  supplier_name: { importance: NORMAL, affects_calc: false }
  process_code: { importance: NORMAL, affects_calc: false }

mat_fee:
  fee_value: { importance: CRITICAL, affects_calc: true }
  fee_ratio: { importance: CRITICAL, affects_calc: true }
  currency: { importance: IMPORTANT, affects_calc: true }
  fixed_rise_value: { importance: CRITICAL, affects_calc: true }
  settlement_rise_ratio: { importance: CRITICAL, affects_calc: true }
  reject_rate: { importance: CRITICAL, affects_calc: true }
  dim_input_material_no: { importance: IMPORTANT, affects_calc: false }

plating_fee:
  plating_process_fee: { importance: CRITICAL, affects_calc: true }
  plating_material_fee: { importance: CRITICAL, affects_calc: true }
  defect_rate: { importance: CRITICAL, affects_calc: true }
  currency: { importance: IMPORTANT, affects_calc: true }
  plating_plan_code: { importance: IMPORTANT, affects_calc: false }
  plan_version: { importance: IMPORTANT, affects_calc: false }

element_price:                                                   # v1 不写数据，v2 启用
  premium_price: { importance: CRITICAL, affects_calc: true }
  currency: { importance: IMPORTANT, affects_calc: true }
  element_name: { importance: IMPORTANT, affects_calc: true }
  source_id: { importance: NORMAL, affects_calc: false }
  fetch_rule_id: { importance: NORMAL, affects_calc: false }
```

**配置 UI**: 「系统管理 → 系统配置 → 字段重要性」，按表分组的字段列表，调整需二次确认。

### 5.3 CONF-3 fetch_rule_definition JSON Schema

**v1 / v2 状态澄清**：v1 在**代码侧**保留以下完整 schema 定义（含校验逻辑、常量枚举），但 `element_price_fetch_rule` 物理表 **v1 不写入任何数据行**（与 §3.3 一致）。v2 启用抓取功能时，由系统管理员通过「配置中心 → 元素价格规则与来源」录入第一条规则，此时表才有数据。

**完整 schema**（v1 schema 入代码 / v2 表中开始有数据）:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["type", "period_type"],
  "properties": {
    "type": {
      "type": "string",
      "enum": ["AVERAGE","AVERAGE_HIGH","AVERAGE_LOW","AVERAGE_OPEN","AVERAGE_CLOSE","MAX","MIN","MEDIAN","REAL_TIME","SPECIFIC_DATE"]
    },
    "period_type": {
      "type": "string",
      "enum": ["PREVIOUS_DAY","PREVIOUS_WEEK","PREVIOUS_MONTH","PREVIOUS_QUARTER","TRAILING_N_DAYS","TRAILING_N_WEEKS","FIXED_RANGE","TODAY","SPECIFIC"]
    },
    "trailing_n":     { "type": "integer", "minimum": 1, "maximum": 365 },
    "fixed_start":    { "type": "string", "format": "date" },
    "fixed_end":      { "type": "string", "format": "date" },
    "specific_date":  { "type": "string", "format": "date" },
    "use": {
      "type": "string",
      "enum": ["raw_price","open_price","close_price","high_price","low_price"],
      "default": "close_price"
    },
    "completeness_threshold": { "type": "number", "minimum": 0, "maximum": 1, "default": 0.8 },
    "exclude_weekends":       { "type": "boolean", "default": true },
    "exclude_holidays":       { "type": "boolean", "default": true },
    "outlier_handling": {
      "type": "object",
      "properties": {
        "method":    { "type": "string", "enum": ["NONE","IQR","STDDEV","PERCENTILE"] },
        "threshold": { "type": "number" }
      }
    },
    "fallback_strategy": {
      "type": "string",
      "enum": ["FAIL","USE_PREVIOUS","USE_REFERENCE_PRICE","MANUAL_INPUT"],
      "default": "MANUAL_INPUT"
    }
  }
}
```

**业务逻辑校验**:
- `period_type=TRAILING_N_DAYS` → `trailing_n` 必填
- `period_type=FIXED_RANGE` → `fixed_start` + `fixed_end` 必填
- `period_type=SPECIFIC` → `specific_date` 必填
- `type=REAL_TIME` → `period_type` 应为 TODAY
- `type=AVERAGE_HIGH/LOW/OPEN/CLOSE` → `use` 字段对应

**校验位置**: 前端实时（schema 校验）+ 后端兜底（保存时再校验）。

---

## 6. 数据模型补充

基于上述决策，v5.0 数据模型新增/调整以下表：

### 6.1 新增表

| 表名 | 用途 | 来源 |
|------|------|------|
| `system_config` | 系统配置 | CONF-1 |
| `product_import_lock` | 产品级悲观锁 | TECH-5 |
| `ddl_operation_lock` | DDL 全局锁 | TECH-4 |

### 6.2 字段补充

| 表 | 字段 | 用途 | 来源 |
|----|------|------|------|
| `BasicDataAttribute` | `importance_level VARCHAR(16) DEFAULT 'NORMAL'` | 字段重要性 | CONF-2 |
| `BasicDataAttribute` | `affects_calculation BOOLEAN DEFAULT false` | 计算影响 | CONF-2 |

> 说明：v1 元素单价**不在** `element_price` 表新增字段。报价单实际单价存于 `QuotationLineComponentData.row_data`；管理员参考价存于 `element_daily_price (fetch_status=MANUAL)`。详见 §3.3。

### 6.3 字段调整

| 表 | 字段 | 调整 | 来源 |
|----|------|------|------|
| `element_price` | `source_id` / `fetch_rule_id` | 改为 nullable（v2 启用配置时的过渡状态可允许其中一项为空） | TECH-3 |

---

## 7. 实施优先级

按依赖与价值排序：

### Phase 1 — 主数据基础设施（先决）
1. 14 张物理表 Flyway migration（含 v5.1 调整）
2. system_config / product_import_lock / ddl_operation_lock 表
3. BasicDataConfig + BasicDataAttribute 元数据（含字段重要性）
4. 变量路径解析器（TECH-1 BNF）
5. **TECH-6 第 1 部分**：Caffeine 缓存层（astCache / sqlCache / metadataCache 三层进程内缓存 + 模板发布预热）

> TECH-6 拆分到 Phase 1 / Phase 4 上线：Phase 1 先做缓存基础设施（无 DataLoader 时朴素调用也能跑通模板发布预热），Phase 4 再叠加 DataLoader 批量查询合并。

### Phase 2 — 导入流程
> ❌ [本节（V5 六步导入向导：上传 → UI2 基础差异 → UI1 客户冲突 → UI3 孤儿行 → 写入 → 完成）已被 2026-05-12-import-v6-staging-design.md 取代]

6. Excel 解析（POI SAX）+ 业务校验（BV-01 ~ BV-32）
7. 产品级悲观锁实现（TECH-5）
8. 主数据维护页面（UI-4）
9. 基础资料差异确认 UI（UI-2）
10. 字段级冲突处理 UI（UI-1）
11. 导入事务流程（TECH-7）

### Phase 3 — 版本与日志
12. 客户资料版本机制（NEW_VERSION 触发）
13. basic_data_change_log 写入逻辑
14. 历史版本管理页面（UI-6）
15. 版本对比工具（UI-5）
16. 变更日志中心（UI-7）

### Phase 4 — 报价生成（基于 v5.0 沿用部分）

> v5.0 章节 11 / 12 / 13 明确"沿用 v4.0"四视图体系、模板架构、审批流程；v5.0 已将这些内容整合为当前架构基线。本 Phase 在 v5.0 基线上叠加 v5.1 的细化项。

17. 报价生成器（v5.0 §11 四视图体系 + §13 审批流程）+ DRAFT 漂移检测（v5.0 §6.6）
18. 公式引擎（TECH-2）+ DataLoader 批量查询（TECH-6 第 2 部分）
19. 元素单价手填（TECH-3 v1 路径，写入 `QuotationLineComponentData.row_data`）
20. **元素价格中心 v1 简化版（UI-3）— 必须随第 19 项同周期上线**
    - 销售填价时需展示"管理员参考价"提示，依赖 UI-3 的"录入参考价"入口提供数据
    - 此项原列在 Phase 5，因依赖关系前移
21. 报价单提交 + 快照机制（v5.0 §10）
22. 数据来源 Tab（UI-8）+ 字段级追溯（UI-9）

### Phase 5 — 配置与运维
23. 系统配置中心（CONF-1）
24. 字段重要性配置（CONF-2）
25. Flyway 扩列管理界面（TECH-4）
26. 锁监控页面

### v2 规划
- 元素价格自动抓取与 ELEMENT_PRICE/PREMIUM_PRICE 函数启用（TECH-3 + CONF-3）
- 报价单复制功能（按 §2.1 方案 D 实施）
- 业务字段完整度指标（UI-4）
- 字段重要性的 v2 调整

---

## 8. 对 v5.0 的修订

以下是相对 v5.0 文档需要更新的章节（v5.0 仍是主架构文档，修订项汇总在此）：

| v5.0 章节 | 修订内容 | 来源 |
|----------|---------|------|
| 5.2.4 element_price | 仅将 `source_id` / `fetch_rule_id` 改为 nullable（v2 启用过渡）；**不**新增 `unit_price` 字段 | TECH-3 |
| 5.3 元素价格子系统 | 标注"v1 三表保留结构无数据；element_daily_price 仅写 MANUAL 行" | TECH-3 |
| 5.4 basic_data_change_log | 无变更（保留 5 年由 system_config 控制） | CONF-1 |
| 7. 元素价格子系统 | 整章补充"v1 三概念分离路径"小节（实际单价/参考价/快照），见 v5.1 §3.3 | TECH-3 |
| 8.2 统一导入流程 | 补充事务边界（与 TECH-7 对齐） | BIZ-3 / TECH-7 |
| 14.2 校验配置性 | 补充完整 system_config 配置项清单 | CONF-1 |
| 15.1 菜单结构 | 「系统管理」下补充"系统配置"和"锁监控"子菜单 | CONF-1 / TECH-5 |
| 17. 非功能需求 | 调整"单次导入支持最大 500 → 2000 行" | BIZ-4 |
| 18. TBD 清单 | 全部移除（已闭合，迁移至 v5.1） | - |

---

## 附录 A：关键术语补充

| 术语 | v5.1 新增/明确 |
|------|--------------|
| 字段重要性 | CRITICAL / IMPORTANT / NORMAL 三级，存于 BasicDataAttribute |
| 计算影响 | affects_calculation 字段，标记字段变更是否触发公式重算 |
| DDL 全局锁 | ddl_operation_lock，确保扩列操作串行 |
| 产品级悲观锁 | product_import_lock，自适应粒度（料号/客户级） |
| DataLoader 模式 | 公式引擎批量查询合并机制 |
| 解析缓存 | Caffeine 进程内缓存 AST/SQL/元数据 |
| 系统配置 | system_config 表，分类存储阈值与运行时参数 |
