# 报价单"添加产品 — 选配"功能设计 (v2)

> **日期**: 2026-05-13
> **状态**: 设计已评审，准备实施
> **触发**: `docs/html/添加产品.html` 原型(2026-05-13 终版,含组合产品 + 组合工艺)
> **核心目标**: 在**不动现有报价单 / 模板 / 核价 / Excel 视图 / 公式 业务流程**前提下,新增"按材质 + 工序 + 组合工艺"的选配链路,**选配出的产品落基础数据物理表**(mat_part / mat_bom / mat_process / mat_composite_process)
> **本稿替代**: 2026-05-13 前一版稿(仅支持独立产品 + 5 步含选模板),已被覆盖

> **配套**:
> - 现有架构:`docs/superpowers/specs/2026-04-26-cpq-design-v5.1.md` §6.1 物理表(mat_part / mat_bom / mat_process)
> - 模板体系:`docs/Excel模板配置指南.md`、`docs/配置方法论.md`
> - V6 导入:`docs/superpowers/specs/2026-05-12-import-v6-staging-design.md`(本设计**不涉及**导入流程)
> - 视图修复:V160/V161 (`v_q_*_merged` / `v_c_*_merged` 暴露 part_version 列)

---

## 目录

1. [背景与边界](#1-背景与边界)
2. [核心决策清单(Q1–Q9)](#2-核心决策清单q1q9)
3. [业务流程(5 步抽屉 + 自适应分支)](#3-业务流程5-步抽屉--自适应分支)
4. [数据模型 — DDL](#4-数据模型--ddl)
5. [配置指纹匹配机制](#5-配置指纹匹配机制)
6. [PartNoProvider 抽象](#6-partnoprovider-抽象)
7. [API 设计](#7-api-设计)
8. [报价单创建流程改造(Wizard Step1)](#8-报价单创建流程改造wizard-step1)
9. [前端实现路径](#9-前端实现路径)
10. [反模式对照](#10-反模式对照)
11. [实施清单与验收](#11-实施清单与验收)
12. [已知限制与未来扩展](#12-已知限制与未来扩展)

---

## 1. 背景与边界

### 1.1 用户需求

销售在报价单第二步"添加产品"时,大量场景下客户给的不是已有料号,而是描述性的"想要 AgNi 90/10 触点,5×3×1.5mm,加冲压+烧结+镀银"或"想要两个配件铆接在一起做成一个组合产品"。当前系统只支持选**已存在的料号 + 已绑定工序**(`AddProductModal.tsx`),无法接住:

- **自由材质 + 自由工序**的独立产品选配
- **组合产品**(多配件 + 跨配件工艺如铆接 / 焊接)

### 1.2 不动的业务流程

| 模块 | 涉及表 / 文件 | 本设计的零侵入承诺 |
|---|---|---|
| 报价单主流程 | `quotation / quotation_line_item / *_snapshot` | 现有列 / saveDraft / 快照 / 审批 / 撤回 链路完全不动 |
| 模板与组件 | `template / component / template_components_snapshot` | 不加字段、不改 component_type 语义 |
| 核价单 | `costing_summary / costing_summary_result / costing_summary_override` | 不动;7 项 metric 计算入口保持 |
| Excel 视图 | `costing_template / v_costing_summary_full` | 不动;新建料号自动有 mat_bom + mat_process → 隐式 JOIN 自动收窄 |
| 公式引擎 | `FormulaEngine / ImplicitJoinRewriter / DataLoader / PathToSqlGenerator` | 不动;BNF 路径全部沿用 |
| V6 staging 导入 | `import_session / staging_*` | 不动;选配是独立于导入的运行时入口 |
| 版本化体系 | `mat_part_version_log / part_version` | 选配料号写 v2000 baseline,完全融入 |

### 1.3 走出抽屉时的产出

5 步走完后,前端构造**与现 `AddProductModal.onConfirm` 完全同型的 `LineItem`**(组合产品产出父+子 N+1 个 LineItem,父子用 parent_line_item_id 关联),后续 wizard 流程零感知差异。

---

## 2. 核心决策清单(Q1–Q9)

| # | 主题 | 决策 | 关键依据 |
|---|---|---|---|
| Q1 | 组合产品建模 | **A:父+子 mat_part + mat_bom.bom_type='ASSEMBLY'** | 满足"落基础数据物理表" + 子配件可独立复用 + Excel 视图天然取数 |
| Q2 | 选模板在哪选 | **C:复用 V6 `QuotationCreateForm` 改造 Wizard Step1** | 最少改动;核价模板留可选;一处 UI,两条入口共用 |
| Q3 | line_item 组合表达 | **Y1:1 父 + N 子两层(parent_line_item_id + composite_type)** | 与方案 A 天然对齐;list 用 antd Table expandable 渲染 |
| Q4 | 组合工艺建模 | **W1:双表(composite_process_def 字典 + mat_composite_process 实例)** | 与普通 process 语义解耦;参数 schema 正式建模可供公式引擎使用 |
| Q5 | part_version 处理 | **V1:选配料号写 v2000 baseline** | 让选配料号"长得像普通料号",V160/V161 视图层零侵入 |
| Q6 | hf_part_no 命名 | **N1:`CFG-{symbol}-{6 位流水}`(组合用 `CFG-COMBO-{流水}`)+ PartNoProvider 抽象** | 显式前缀利于审计;V2 可切换 ExternalApiPartNoProvider |
| Q6+ | 配置去重 | **F2 修正版:仅按材料指纹匹配(recipe + 元素含量;组合则加子料号 sorted)** | 单重/工序/组合工艺是料号 1:1 属性,不参与匹配,仅未命中时创建填写 |
| Q7 | 客户料号/图号 | **T1:抽屉不填,mat_customer_part_mapping 不写,line_item 行允许 inline 编辑** | 选配场景客户语境本就无客户图号 |
| Q8 | 单重 unit_weight | **U2:Step 5 加可选输入框,仅未命中指纹时显示** | 是料号属性;命中时只读展示 |
| Q9 | 工序单价 unit_price | **PR1:mat_process.unit_price=NULL,模板用全局变量 `PROCESS_DEFAULT_PRICE` 动态 key 取** | V109 模式,与现有体系融合 |

---

## 3. 业务流程(5 步抽屉 + 自适应分支)

### 3.1 流程图

```
新建报价单 (Wizard Step1: 客户+分类+报价模板+核价模板)
            ↓
报价单草稿创建 (含 customer_template_id / costing_card_template_id)
            ↓
Wizard Step2 [+ 添加产品] Dropdown:
   ├─ "从已有产品添加" → 现有 AddProductModal (不动)
   └─ "选配添加"       → ConfigureProductDrawer (本设计)
            ↓
┌─ P0 产品类型 ─────────────┐
│  独立 / 组合(2~8 配件)    │
└────────────┬──────────────┘
             ▼
[组合模式:循环每个配件,共享 P1~P3 逻辑]
             ▼
┌─ P1 料号搜索 ─────────────┐
│  匹配列表 + "无匹配,自定义"│
└──┬─────────────────────────┘
   │                                     ┌─ 自定义路径 P2 完成后算指纹 ─┐
   ▼                                     │                                │
┌─ 已有路径(matLocked) ─┐ ┌─ 自定义路径 ┐│  hash(recipe + 元素 sorted)     │
│ P2: 材质锁定,只读     │ │ P2: 自由选 ││  查 mat_part.config_fingerprint │
│ P3: 工序锁定,只读     │ │  + 调元素  │└┬─ 命中 → 跳 P3, 直接 P5 (沿用) │
│ 直接到 P5             │ │             │ │   提示: '匹配料号 CFG-xxx     │
└─────────────────────┘ └────┬────────┘ │   将沿用工序/单重'(可返 P2 改) │
                              ▼          │                                │
                       ┌─ P3 工序 ────┐  └─ 未命中 → 走 P3 → P5 (填新) ──┘
                       │ 选 N 个,有序 │
                       └──────┬───────┘
                              ▼
[独立: 全部配件走完 → P5]   [组合: 全部子配件走完 → P4 组合工艺 → P5]
                              ▼
┌─ P4 组合工艺(仅组合产品) ─┐
│ 6 工艺字典 + 参数 +       │
│ 选参与配件子集(≥2)        │
└──────────────┬────────────┘
               ▼
┌─ P5 确认 ─────────────────────────────┐
│ 命中已有料号: 只读展示(料号/材质/工序/│
│              单重/组合工艺)           │
│ 未命中(新料号): 显示 + 填可选单重    │
│ 点 [确认添加]                          │
└──────────────┬────────────────────────┘
               ▼
POST /quotations/{id}/configure-product
               ↓
[事务:命中→复用 part_no / 未命中→生成新 + 落 mat_part / mat_bom / mat_process / mat_composite_process(组合)]
               ↓
返 LineItem DTO(组合产品:父+子 N+1 行)
               ↓
前端 onConfirm(lineItem) 注入 wizard
```

### 3.2 三条路径的语义差异

| 字段 | A 已有料号 | B 自定义未命中(新建) | C 自定义命中(指纹复用) |
|---|---|---|---|
| 用户在 P1 操作 | 选具体料号行 | 选"无匹配料号" | 选"无匹配料号" |
| P2 材质 | 锁定(matLocked) | 自由选 + 调元素 | 自由选 + 调元素 |
| P2→P3 之间指纹查 | 不查 | 查,未命中继续 | 查,**命中提示** |
| P3 工序 | 锁定(展示已有) | 用户选 | 跳过(沿用已有) |
| P4 组合工艺(组合) | 锁定(展示已有) | 用户选 | 跳过(沿用已有) |
| P5 单重 | 只读 | 必填(可选,允许 NULL) | 只读 |
| mat_part 落新行 | 否 | **是** | 否 |
| mat_bom 落新行 | 否 | **是**(ELEMENT N 行;组合再加 ASSEMBLY N 行) | 否 |
| mat_process 落新行 | 否 | **是**(选了工序时) | 否 |
| mat_composite_process(组合) | 否 | **是**(选了组合工艺时) | 否 |
| mat_part_version_log | 否 | **是**(v2000 baseline) | 否 |
| quotation_line_item | 1 行(独立) / N+1 行(组合) | 同 | 同(料号是复用的) |

### 3.3 失败回滚策略

`POST /quotations/{id}/configure-product` 走单一 Quarkus 事务(主事务 `REQUIRED`)。任一步失败:

- PartNoProvider 失败 → 抛 400 含"hf_part_no 生成失败"提示;不写任何表
- 指纹查询失败(DB 故障) → 抛 500;不重试,前端 toast 提示重试
- INSERT 撞 UNIQUE(config_fingerprint) → ON CONFLICT DO NOTHING RETURNING + 二次 SELECT(并发兜底)
- mat_part / mat_bom / mat_process / mat_composite_process 任一行 INSERT 失败 → 整事务回滚
- quotation_line_item 父+子 INSERT 顺序:先父,拿到 id 后写子;任一失败回滚父

---

## 4. 数据模型 — DDL

### 4.1 新增表 `material_recipe`(材质字典)

```sql
-- V162__material_recipe_and_element.sql
CREATE TABLE IF NOT EXISTS material_recipe (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64)  NOT NULL UNIQUE,        -- 'AgCu85' / 'AgNi90'
    symbol          VARCHAR(32)  NOT NULL,               -- 'AgCu' / 'AgNi'(化学式)
    name            VARCHAR(128) NOT NULL,               -- '银铜合金'
    spec_label      VARCHAR(64),                         -- '85/15' / '90/10'
    recipe_type     VARCHAR(16)  NOT NULL,               -- locked / editable / partial
    sort_order      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT chk_material_recipe_type CHECK (recipe_type IN ('locked','editable','partial')),
    CONSTRAINT chk_material_recipe_status CHECK (status IN ('ACTIVE','INACTIVE'))
);

CREATE INDEX idx_material_recipe_status ON material_recipe(status, sort_order);
```

### 4.2 新增表 `material_recipe_element`

```sql
CREATE TABLE IF NOT EXISTS material_recipe_element (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    recipe_id       UUID         NOT NULL REFERENCES material_recipe(id) ON DELETE CASCADE,
    element_code    VARCHAR(32)  NOT NULL,
    element_name    VARCHAR(64)  NOT NULL,
    default_pct     DECIMAL(8,4) NOT NULL,
    min_pct         DECIMAL(8,4),
    max_pct         DECIMAL(8,4),
    is_locked       BOOLEAN      NOT NULL DEFAULT false,
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_recipe_element UNIQUE (recipe_id, element_code),
    CONSTRAINT chk_recipe_element_range CHECK (
        (is_locked = true AND min_pct IS NULL AND max_pct IS NULL)
        OR (is_locked = false AND min_pct IS NOT NULL AND max_pct IS NOT NULL AND min_pct <= max_pct)
    )
);
CREATE INDEX idx_recipe_element_recipe ON material_recipe_element(recipe_id, sort_order);
```

### 4.3 新增表 `composite_process_def`(组合工艺字典)

```sql
-- V163__composite_process_def_and_mat.sql
CREATE TABLE IF NOT EXISTS composite_process_def (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64)  NOT NULL UNIQUE,     -- 'RIVET' / 'RESISTANCE_WELD' / ...
    name            VARCHAR(128) NOT NULL,
    icon            VARCHAR(8),
    description     TEXT,
    param_schema    JSONB        NOT NULL DEFAULT '[]',
    sort_order      INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_composite_process_def_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX idx_composite_process_def_status ON composite_process_def(status, sort_order);
```

### 4.4 新增表 `mat_composite_process`(组合工艺实例)

```sql
CREATE TABLE IF NOT EXISTS mat_composite_process (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_hf_part_no     VARCHAR(64)  NOT NULL,
    def_code              VARCHAR(64)  NOT NULL REFERENCES composite_process_def(code),
    seq_no                INT          NOT NULL,
    participating_parts   JSONB        NOT NULL,         -- ['CFG-AgCu-000001', 'CFG-AgNi-000003']
    param_values          JSONB        NOT NULL DEFAULT '{}',
    part_version          INT          NOT NULL DEFAULT 2000,
    is_current            BOOLEAN      NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            UUID,
    CONSTRAINT uq_mat_composite_process UNIQUE (parent_hf_part_no, seq_no, part_version)
);
CREATE INDEX idx_mat_composite_process_parent ON mat_composite_process(parent_hf_part_no, part_version);
```

### 4.5 `mat_part` 加 3 列

```sql
-- V164__alter_mat_part_add_configure_cols.sql
ALTER TABLE mat_part
    ADD COLUMN material_recipe_id   UUID         NULL REFERENCES material_recipe(id) ON DELETE SET NULL,
    ADD COLUMN product_type         VARCHAR(16)  NOT NULL DEFAULT 'SIMPLE',
    ADD COLUMN config_fingerprint   VARCHAR(64)  NULL;

ALTER TABLE mat_part
    ADD CONSTRAINT chk_mat_part_product_type
        CHECK (product_type IN ('SIMPLE','COMPOSITE'));

CREATE UNIQUE INDEX uq_mat_part_fingerprint ON mat_part(config_fingerprint)
    WHERE config_fingerprint IS NOT NULL;

CREATE INDEX idx_mat_part_recipe ON mat_part(material_recipe_id);
CREATE INDEX idx_mat_part_product_type ON mat_part(product_type);
```

### 4.6 `mat_bom.bom_type` 扩 ASSEMBLY

```sql
-- V165__extend_mat_bom_bom_type_assembly.sql
ALTER TABLE mat_bom DROP CONSTRAINT IF EXISTS chk_mat_bom_bom_type;
ALTER TABLE mat_bom ADD CONSTRAINT chk_mat_bom_bom_type
    CHECK (bom_type IN ('ELEMENT','INCOMING','OUTPUT','ASSEMBLY'));
```

**说明**:`mat_bom` 现有 `child_part_no` 列(或类似业务键列)用于 ASSEMBLY 行表达"父→子"关系;若现有 schema 无此列,V165 内一并新增 nullable 列。实施前需校核实际 schema 决定。

### 4.7 `quotation_line_item` 加 2 列

```sql
-- V166__alter_quotation_line_item_composite.sql
ALTER TABLE quotation_line_item
    ADD COLUMN parent_line_item_id  UUID         NULL REFERENCES quotation_line_item(id) ON DELETE CASCADE,
    ADD COLUMN composite_type       VARCHAR(16)  NOT NULL DEFAULT 'SIMPLE';

ALTER TABLE quotation_line_item
    ADD CONSTRAINT chk_quotation_line_item_composite_type
        CHECK (composite_type IN ('SIMPLE','COMPOSITE','PART'));

CREATE INDEX idx_quotation_line_item_parent ON quotation_line_item(parent_line_item_id);
```

### 4.8 Seed 数据

```sql
-- V167__seed_material_recipes.sql (12 行 material_recipe + 元素含量)
INSERT INTO material_recipe (code, symbol, name, spec_label, recipe_type, sort_order) VALUES
  ('AgCu85',  'AgCu',  '银铜合金',   '85/15', 'locked',   10),
  ('AgCu90',  'AgCu',  '银铜合金',   '90/10', 'locked',   20),
  ('AgNi90',  'AgNi',  '银镍合金',   '90/10', 'editable', 30),
  ('AgNi95',  'AgNi',  '银镍合金',   '95/5',  'editable', 40),
  ('AgSnO2',  'AgSnO₂','银氧化锡',   '88/12', 'partial',  50),
  ('AgSnO2b', 'AgSnO₂','银氧化锡',   '85/15', 'partial',  60),
  ('AgCdO',   'AgCdO', '银氧化镉',   '85/15', 'locked',   70),
  ('AgW60',   'AgW',   '银钨合金',   '60/40', 'editable', 80),
  ('AgW72',   'AgW',   '银钨合金',   '72/28', 'editable', 90),
  ('CuCr',    'CuCr',  '铜铬合金',   '99/1',  'partial',  100),
  ('AgPd',    'AgPd',  '银钯合金',   '70/30', 'locked',   110),
  ('AuAg',    'AuAg',  '金银合金',   '75/25', 'locked',   120);
-- 元素含量 INSERT 略,实施时按 12 个 recipe 逐一展开(每 recipe 2~3 元素行)

-- V168__seed_composite_process_def.sql (6 行)
INSERT INTO composite_process_def (code, name, icon, description, param_schema, sort_order) VALUES
  ('RIVET', '铆接', '🔩', '将两个配件通过铆钉压接固定',
   '[{"id":"pressure","label":"铆接压力","unit":"kN","type":"number"},
     {"id":"height","label":"铆钉高度","unit":"mm","type":"number"}]', 10),
  ('RESISTANCE_WELD', '电阻焊', '⚡', '通过电阻加热实现配件熔合',
   '[{"id":"current","label":"焊接电流","unit":"kA","type":"number"},
     {"id":"time","label":"焊接时间","unit":"ms","type":"number"}]', 20),
  ('LASER_WELD', '激光焊', '🔆', '使用激光束对配件进行精密焊接',
   '[{"id":"power","label":"激光功率","unit":"W","type":"number"},
     {"id":"speed","label":"焊接速度","unit":"mm/s","type":"number"}]', 30),
  ('BRAZING', '钎焊', '🔥', '使用钎料在低于母材熔点下连接配件',
   '[{"id":"temp","label":"钎焊温度","unit":"°C","type":"number"},
     {"id":"material","label":"钎料材质","unit":"","type":"text"}]', 40),
  ('ULTRASONIC_WELD', '超声波焊接', '〰️', '利用超声波振动将配件熔合',
   '[{"id":"amplitude","label":"振幅","unit":"μm","type":"number"},
     {"id":"weld_time","label":"焊接时间","unit":"ms","type":"number"}]', 50),
  ('PRESS_FIT', '压配合', '🗜️', '通过过盈配合将配件压入固定',
   '[{"id":"force","label":"压入力","unit":"kN","type":"number"},
     {"id":"fit","label":"配合公差","unit":"","type":"text"}]', 60);

-- V169__register_process_default_price_variable.sql
CREATE TABLE IF NOT EXISTS process_default_cost (
    process_code  VARCHAR(64) PRIMARY KEY,
    unit_price    DECIMAL(12,4) NOT NULL,
    currency      VARCHAR(8) NOT NULL DEFAULT 'CNY',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO basic_data_config (id, name, sheet_name, physical_table, template_kind, status)
VALUES (gen_random_uuid(), '工序默认单价', 'process_default_cost', 'process_default_cost', 'BOTH', 'ACTIVE');

INSERT INTO global_variable_definition (code, label, sheet_name, key_field_names, value_field_name, status)
VALUES ('PROCESS_DEFAULT_PRICE', '工序默认单价', 'process_default_cost',
        ARRAY['process_code'], 'unit_price', 'ACTIVE');

INSERT INTO process_default_cost (process_code, unit_price) VALUES
  ('p1', 0.50), ('p2', 1.20), ('p3', 0.80), ('p4', 1.00), ('p5', 0.30),
  ('p6', 1.50), ('p7', 0.90), ('p8', 0.40), ('p9', 0.20);

-- V170__part_no_sequence.sql
CREATE TABLE IF NOT EXISTS part_no_sequence (
    prefix   VARCHAR(32) PRIMARY KEY,
    next_val BIGINT      NOT NULL DEFAULT 1
);

INSERT INTO part_no_sequence (prefix, next_val) VALUES
  ('CFG-AgCu-', 1), ('CFG-AgNi-', 1), ('CFG-AgSnO₂-', 1),
  ('CFG-AgCdO-', 1), ('CFG-AgW-', 1), ('CFG-CuCr-', 1),
  ('CFG-AgPd-', 1), ('CFG-AuAg-', 1), ('CFG-COMBO-', 1)
ON CONFLICT (prefix) DO NOTHING;
```

---

## 5. 配置指纹匹配机制

### 5.1 指纹算法(F2 修正版)

**独立产品**(`product_type='SIMPLE'`):

```
input = recipe_code + '|' + sortedElements
sortedElements = elements.sort(byElementCode).map(e => e.element_code + '=' + normalize(e.pct)).join(',')
normalize(pct) = BigDecimal(pct).stripTrailingZeros().toPlainString()
fingerprint = sha256(input).hex()
```

示例: `recipe=AgNi90, {Ag:90, Ni:10}` → `'AgNi90|Ag=90,Ni=10'` → sha256 (64 hex)

**组合产品**(`product_type='COMPOSITE'`):

```
// 注:子配件指纹已在前一步落库,各自有 hf_part_no
input = 'COMBO|' + childHfPartNos.sort().join(',')
fingerprint = sha256(input).hex()
```

### 5.2 匹配查询(并发安全)

```java
public String lookupOrCreate(String fingerprint, Supplier<MatPart> createFn) {
    String existing = MatPart.find("config_fingerprint", fingerprint).firstResult();
    if (existing != null) return existing.partNo;

    MatPart mp = createFn.get();
    int affected = em.createNativeQuery(
        "INSERT INTO mat_part (part_no, config_fingerprint, ...) VALUES (?, ?, ...) " +
        "ON CONFLICT (config_fingerprint) DO NOTHING"
    ).executeUpdate();
    if (affected == 0) {
        // 并发兜底:他人刚 INSERT 进了 → 再查
        return MatPart.find("config_fingerprint", fingerprint).firstResult().partNo;
    }
    return mp.partNo;
}
```

### 5.3 命中后的 UX

抽屉 P2 → P3 之间(自定义路径)实时调端点 `POST /configure/lookup-fingerprint`:

```json
// Request
{ "productType": "SIMPLE", "recipeCode": "AgNi90",
  "elements": [{"elementCode":"Ag","pct":92}, {"elementCode":"Ni","pct":8}] }

// Response - 命中
{ "matched": true, "hfPartNo": "CFG-AgNi-000007",
  "snapshot": {
    "unitWeightGrams": 12.5,
    "processes": [{"processCode":"p1","name":"冲压成型","seqNo":1}],
    "compositeProcesses": []
  }}

// Response - 未命中
{ "matched": false }
```

命中时前端 `Modal.confirm` 提示:
```
⚠️ 已找到匹配料号 CFG-AgNi-000007,将沿用其工序和单重:
  - 工序: 冲压成型 → 镀银 (2 项)
  - 单重: 12.5 g/件

[返回修改材质]  [沿用 → 直接确认]
```

---

## 6. PartNoProvider 抽象

### 6.1 接口

```java
package com.cpq.partno;

public interface PartNoProvider {
    String apply(PartNoContext context);
}

public class PartNoContext {
    public String symbol;          // 'AgCu' / 'AgNi' / 'COMBO'
    public String productType;     // 'SIMPLE' / 'COMPOSITE'
    public UUID operatorId;
}
```

### 6.2 V1 实现:本地自动分配

```java
@ApplicationScoped
@LookupIfProperty(name = "cpq.partno.provider", stringValue = "auto", lookupIfMissing = true)
public class AutoAllocatePartNoProvider implements PartNoProvider {
    @Override
    public String apply(PartNoContext ctx) {
        String prefix = "CFG-" + ctx.symbol + "-";
        long next = nextSequence(prefix);   // SELECT ... FOR UPDATE on part_no_sequence
        return String.format("%s%06d", prefix, next);
    }
}
```

### 6.3 V2 实现:外部 API

```java
@ApplicationScoped
@LookupIfProperty(name = "cpq.partno.provider", stringValue = "external")
public class ExternalApiPartNoProvider implements PartNoProvider {
    @ConfigProperty(name = "cpq.partno.external.url")        String url;
    @ConfigProperty(name = "cpq.partno.external.timeout-ms") int timeoutMs;

    @Override
    public String apply(PartNoContext ctx) {
        // POST {url} { symbol, productType, operatorId }
        // 期望返回 { "hf_part_no": "..." }
    }
}
```

切换:`application.properties` 改 `cpq.partno.provider=external`,业务零改动。

---

## 7. API 设计

### 7.1 端点总览

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET`  | `/api/cpq/quotations/configure/search-parts?q=<kw>&size=50` | P1 统一搜索 |
| `GET`  | `/api/cpq/material-recipes` | P2 自定义路径:材质列表 |
| `GET`  | `/api/cpq/material-recipes/{id}` | P2:单材质 + 元素含量 |
| `GET`  | `/api/cpq/composite-processes` | P4 组合工艺字典 |
| `POST` | `/api/cpq/quotations/configure/lookup-fingerprint` | P2 完成时指纹查 DB |
| `POST` | `/api/cpq/quotations/{quotationId}/configure-product` | P5 确认:一锅端落数据 + 返 LineItem |

### 7.2 `POST /quotations/{id}/configure-product`(核心)

**Request DTO**:

```java
public class ConfigureProductRequest {
    public String productType;             // 'SIMPLE' | 'COMPOSITE'
    public List<PartRequest> parts;        // SIMPLE 时 size=1; COMPOSITE 时 size>=2
    public List<CompositeProcessRequest> compositeProcesses;  // 仅 COMPOSITE
}

public class PartRequest {
    public String name;
    public String partMode;                // 'existing' | 'custom'
    public String existingHfPartNo;        // existing 时必填
    public String recipeCode;              // custom 时必填
    public List<ElementOverride> elements; // custom 时必填(全 recipe 元素)
    public List<UUID> processIds;          // 顺序数组(命中复用时忽略)
    public BigDecimal unitWeightGrams;     // 仅未命中指纹时填(命中复用时忽略)
}

public class ElementOverride {
    public String elementCode;
    public BigDecimal pct;
}

public class CompositeProcessRequest {
    public String defCode;
    public List<Integer> participatingPartIndexes;  // 引用 parts 数组下标
    public Map<String, Object> params;
}
```

**Response DTO**:

```java
public class ConfigureProductResponse {
    public List<LineItemDTO> lineItems;    // SIMPLE: 1 行;COMPOSITE: 1 父 + N 子
    public boolean fingerprintMatched;
    public List<String> reusedHfPartNos;
}
```

**后端流程**(`ConfigureProductService.configure`):

```java
@Transactional
public ConfigureProductResponse configure(UUID quotationId, ConfigureProductRequest req, UUID operatorId) {
    Quotation q = Quotation.findByIdOrThrow(quotationId);
    List<String> childHfPartNos = new ArrayList<>();
    List<String> reused = new ArrayList<>();

    // PASS 1: 解析每个配件
    for (PartRequest pr : req.parts) {
        childHfPartNos.add(resolvePart(pr, operatorId, reused));
    }

    // PASS 2: 组合产品父级
    String parentHfPartNo = null;
    if ("COMPOSITE".equals(req.productType)) {
        String fp = computeComboFingerprint(childHfPartNos);
        parentHfPartNo = lookupByFingerprint(fp);
        if (parentHfPartNo == null) {
            parentHfPartNo = partNoProvider.apply(new PartNoContext("COMBO", "COMPOSITE", operatorId));
            insertMatPart(parentHfPartNo, "COMPOSITE", fp, null);
            insertAssemblyBom(parentHfPartNo, childHfPartNos);
            insertCompositeProcesses(parentHfPartNo, req.compositeProcesses, childHfPartNos);
            initPartVersionBaseline(parentHfPartNo);
        } else {
            reused.add(parentHfPartNo);
        }
    }

    // PASS 3: quotation_line_item 父+子
    return buildResponse(quotationId, req, parentHfPartNo, childHfPartNos, reused);
}

private String resolvePart(PartRequest pr, UUID operatorId, List<String> reused) {
    if ("existing".equals(pr.partMode)) return pr.existingHfPartNo;

    validateElements(pr.recipeCode, pr.elements);
    String fp = computeSimpleFingerprint(pr.recipeCode, pr.elements);
    String hf = lookupByFingerprint(fp);
    if (hf != null) { reused.add(hf); return hf; }

    MaterialRecipe recipe = MaterialRecipe.findByCodeOrThrow(pr.recipeCode);
    hf = partNoProvider.apply(new PartNoContext(recipe.symbol, "SIMPLE", operatorId));
    insertMatPart(hf, "SIMPLE", fp, pr.unitWeightGrams);
    insertElementBom(hf, pr.elements);
    insertProcesses(hf, pr.processIds);
    initPartVersionBaseline(hf);
    return hf;
}
```

### 7.3 校验规则

| 校验 | 触发 | 错误码 |
|---|---|---|
| productType ∈ {SIMPLE, COMPOSITE} | 总是 | 400 |
| SIMPLE 时 parts.size = 1 | productType=SIMPLE | 400 |
| COMPOSITE 时 parts.size ∈ [2, 8] | productType=COMPOSITE | 400 |
| custom 时 elements 覆盖所有 recipe 元素 | partMode=custom | 400 |
| locked / partial 中 is_locked 元素 pct = defaultPct | partMode=custom | 400 |
| editable / partial 中可调元素 pct ∈ [min, max] | partMode=custom | 400 |
| custom 时 ∑elements.pct ∈ [99.99, 100.01] | partMode=custom | 400 |
| existing 时 existingHfPartNo 必须存在 | partMode=existing | 400 |
| 组合工艺 participatingPartIndexes 至少 2 个 | productType=COMPOSITE | 400 |
| 组合工艺 defCode 必须存在于字典 | productType=COMPOSITE | 400 |
| 组合工艺 params 覆盖 def.param_schema 必填字段 | productType=COMPOSITE | 400 |

---

## 8. 报价单创建流程改造(Wizard Step1)

### 8.1 现状

`cpq-frontend/src/pages/quotation/QuotationWizard.tsx` Step1 仅让选客户;`customerTemplateId` / `costingCardTemplateId` 是 state,UI 没字段(靠后端按"客户+分类"匹配)。

### 8.2 改造

复用 V6 已落地的 `cpq-frontend/src/pages/quotation/QuotationCreateForm.tsx`(4 字段:客户/分类/报价模板/核价模板),把 Step1 表单升级:

```tsx
<Card title="报价单基本信息">
  <Form.Item name="customerId" label="客户" required>
    <Select onSearch={searchCustomers} ... />
  </Form.Item>

  {selectedCustomer && (
    <QuotationCreateForm
      customerId={selectedCustomer.id}
      customerName={selectedCustomer.name}
      value={{
        name: form.getFieldValue('name'),
        categoryId: form.getFieldValue('categoryId'),
        customerTemplateId: form.getFieldValue('customerTemplateId'),
        costingTemplateId: form.getFieldValue('costingCardTemplateId'),
      }}
      onChange={(v) => form.setFieldsValue({
        name: v.name, categoryId: v.categoryId,
        customerTemplateId: v.customerTemplateId,
        costingCardTemplateId: v.costingTemplateId,
      })}
      onValidityChange={setStep1Valid}
    />
  )}

  {/* 联系人 / 项目 / 商机 保留 */}
</Card>
```

### 8.3 进 Step2 的条件

```
step1Valid = customer + categoryId + customerTemplateId 已选 (核价模板可选)
```

下一步按钮禁用直到 `step1Valid = true`,符合 SelectableTable / `enabledWhen` 规范。

### 8.4 Step2 添加产品入口拆 Dropdown

```tsx
<Dropdown menu={{ items: [
  { key: 'classic',   label: '从已有产品添加', icon: <DatabaseOutlined />, onClick: () => setAddProductModalOpen(true) },
  { key: 'configure', label: '选配添加',       icon: <SettingOutlined />,  onClick: () => setConfigureDrawerOpen(true) },
]}}>
  <Button type="primary"><PlusOutlined /> 添加产品 <DownOutlined /></Button>
</Dropdown>
```

---

## 9. 前端实现路径

### 9.1 文件清单

| 新建 / 改动 | 文件 | 用途 |
|---|---|---|
| 新建 | `cpq-frontend/src/pages/quotation/ConfigureProductDrawer.tsx` | 5 步抽屉总入口(宽 960) |
| 新建 | `cpq-frontend/src/pages/quotation/configure/Step0ProductType.tsx` | P0 独立/组合选择 |
| 新建 | `cpq-frontend/src/pages/quotation/configure/Step1SearchPart.tsx` | P1 料号搜索(配件循环复用) |
| 新建 | `cpq-frontend/src/pages/quotation/configure/Step2Material.tsx` | P2 材质 + 元素 |
| 新建 | `cpq-frontend/src/pages/quotation/configure/Step3Process.tsx` | P3 工序选择 |
| 新建 | `cpq-frontend/src/pages/quotation/configure/Step4CompositeProcess.tsx` | P4 组合工艺 |
| 新建 | `cpq-frontend/src/pages/quotation/configure/Step5Summary.tsx` | P5 确认(含可选单重) |
| 新建 | `cpq-frontend/src/services/configureProductService.ts` | 调 5 个新端点 |
| 新建 | `cpq-frontend/src/services/materialRecipeService.ts` | 材质字典服务 |
| 新建 | `cpq-frontend/src/services/compositeProcessService.ts` | 组合工艺字典服务 |
| 改动 | `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` | Step1 改造 |
| 改动 | `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | Dropdown 入口 |

### 9.2 状态机

```typescript
interface ConfigureDrawerState {
  globalStep: 0 | 1 | 2 | 3;          // 0=type, 1=partConfig循环, 2=comboProc, 3=done
  subStep: 0 | 1 | 2;                 // partConfig 内:0=pn, 1=mat, 2=proc
  productType: 'single' | 'combo';
  initPartCount: number;              // combo 模式 2-8
  parts: PartState[];
  ci: number;                         // current part index
  addedCProcs: CompositeProcessAdded[];
  fingerprintMatch: FingerprintMatchInfo | null;
}

interface PartState {
  name: string;
  partMode: 'existing' | 'custom' | null;
  selectedHfPartNo: string | null;
  selectedRecipe: MaterialRecipe | null;
  elementOverrides: { [elementCode: string]: number };
  matLocked: boolean;
  processes: Process[];
  unitWeightGrams: number | null;     // 仅未命中 + new 时填
  reusedFromExisting: { hfPartNo: string, snapshot: PartSnapshot } | null;
}
```

### 9.3 命中提示 Modal

```tsx
Modal.confirm({
  title: '⚠️ 已找到匹配料号',
  width: 500,
  content: (
    <div>
      <p>系统已存在配置完全相同的料号: <code>{hfPartNo}</code></p>
      <p>沿用以下属性:</p>
      <ul>
        <li>工序: {processes.map(p => p.name).join(' → ')}</li>
        <li>单重: {unitWeight} g/件</li>
      </ul>
    </div>
  ),
  okText: '沿用 → 直接确认',
  cancelText: '返回修改材质',
});
```

---

## 10. 反模式对照

按 `docs/反模式.md` AP-1 ~ AP-22 逐条检视:

| AP | 反模式 | 本设计的防护 |
|---|---|---|
| AP-1 | UUID 空串传后端 | DTO 中所有 UUID 字段 builder 层 `value \|\| null` |
| AP-2 | SaveRequest 丢字段 | `ConfigureProductResponse.lineItems` 与 `AddProductModal.onConfirm` 同型;集成测试覆盖 round-trip |
| AP-3 | 派生靠 lookup,源可能空 | mat_part / mat_bom / mat_process 在 configure 时已落实数据;绝不依赖运行时反查 recipe |
| AP-4 | autoSave 闭包陷阱 | Drawer 是一次性提交,无 autoSave |
| AP-5 | JSONB 当 String 透传 | composite_process.params 用 `Map<String,Object>` DTO,Jackson 处理 |
| AP-6 | 公式 cache key partNo 维度 | 选配料号有自己 hf_part_no,FormulaEngine 缓存按行隔离 |
| AP-7 | ImplicitJoinRewriter 注入审计列 | 不动 Rewriter,继续走 V112 黑名单 |
| AP-8 | 导入差异检测对 null 误报 | 不涉及 — 选配不走 Excel 导入 |
| AP-9 | 异步 enrich 整体覆盖 | onConfirm 完一次性 setLineItems |
| AP-10 | mutator 对象式 setState | Drawer 状态用 functional setState |
| AP-11 | 屏幕用运行时计算,save 只 dump 输入 | mat_bom / mat_process / mat_composite_process 都已落库;LineItem 走现有 snapshotRows |
| AP-12 | 懒资源 GET 硬抛 404 | 不涉及 |
| AP-13/14/15 | 客户级版本表三方对齐 | 选配落全局 mat_part + 半客户 mat_process,已对齐 schema |
| AP-16 | 模板派生 schema 刷新失踪 | 走现有 enrichComponentData,本设计不动 |
| AP-17 | 同名概念分布在两张独立表 | composite_process_def 与 process 明确分离 |
| AP-18 | dev hot-reload ≠ Flyway 重跑 | V162~V170 落库后必须 touch java 触发 Flyway |
| AP-19 | 1:1 FK 配错对象 | parent_line_item_id 自表关联,CASCADE |
| AP-20 | BNF 隐式 JOIN 失效 | mat_composite_process 含 parent_hf_part_no + part_version,与 V160/V161 视图模式对齐 |
| AP-21 | FORMULA 列字符串字面量 | 工序单价用全局变量 token,不写字面量 |
| AP-22 | 多行"(共N项)" | 选配料号 mat_bom / mat_process / mat_composite_process 按 hf_part_no + part_version 严格收窄 |

### 10.1 新增风险

**RISK-1:并发选配同配置 → 指纹撞 UNIQUE**
- 解法:`INSERT ... ON CONFLICT (config_fingerprint) DO NOTHING` + 二次 SELECT 兜底
- 测试:10 个并发线程提交同配置,期望 1 创建 + 9 复用

**RISK-2:用户撤回报价单 → 选配生成的 mat_part 是否清理**
- 保留(orphan-tolerant);已发布报价单引用了该 hf_part_no;管理员可走数据治理 PR 批量清理

**RISK-3:子配件被多个组合产品共用 → 删除子配件影响多个父级**
- `mat_bom (ASSEMBLY)` ON DELETE RESTRICT,子料号在有父级引用时不允许删除

**RISK-4:指纹算法未来变更 → 旧料号 fingerprint 陈旧**
- fingerprint 字符串加版本号前缀: `'v1|recipe=...|...'`,未来 v2 时两版共存

---

## 11. 实施清单与验收

### 11.1 后端清单

- [ ] Flyway V162 `material_recipe_and_element.sql`
- [ ] Flyway V163 `composite_process_def_and_mat.sql`
- [ ] Flyway V164 `alter_mat_part_add_configure_cols.sql`
- [ ] Flyway V165 `extend_mat_bom_bom_type_assembly.sql`
- [ ] Flyway V166 `alter_quotation_line_item_composite.sql`
- [ ] Flyway V167 `seed_material_recipes.sql`
- [ ] Flyway V168 `seed_composite_process_def.sql`
- [ ] Flyway V169 `register_process_default_price_variable.sql`
- [ ] Flyway V170 `part_no_sequence.sql`
- [ ] Entity:`MaterialRecipe` / `MaterialRecipeElement` / `CompositeProcessDef` / `MatCompositeProcess`
- [ ] DTO:8 个
- [ ] Service:`MaterialRecipeService` / `CompositeProcessService` / `ConfigureProductService` / `PartSearchService.searchForConfigure` / `FingerprintCalculator`
- [ ] Resource:`MaterialRecipeResource` / `CompositeProcessResource` / `ConfigureProductResource`
- [ ] Provider:`PartNoProvider` + `AutoAllocatePartNoProvider`
- [ ] 集成测试 `ConfigureProductServiceTest`:
  - existing 路径:返正确 LineItemDTO
  - custom 未命中:落 mat_part / mat_bom (N) / mat_process (M) / mat_part_version_log
  - custom 命中:复用 hf_part_no,不落任何新行
  - 元素和 ≠ 100 → 400
  - locked 元素 pct ≠ default → 400
  - 组合产品:子+父都落库,mat_composite_process N 行
  - 组合产品子配件复用:仅父级新建
  - 并发 10 个同配置:1 创建 + 9 复用
  - 组合工艺 participating < 2 → 400

### 11.2 前端清单

- [ ] `ConfigureProductDrawer.tsx` + 6 step 组件
- [ ] 3 个 service 文件
- [ ] `QuotationWizard.tsx` Step1 改造
- [ ] `QuotationStep2.tsx` Dropdown 入口
- [ ] tsc --noEmit 0 错 + 改动文件 Vite 200
- [ ] 浏览器手测 6 路径:
  1. 独立产品 / 已有路径
  2. 独立产品 / 自定义未命中
  3. 独立产品 / 自定义命中(再走 #2 同配置)
  4. 组合产品 / 全新
  5. 组合产品 / 子配件复用
  6. 组合产品 / 全复用

### 11.3 自检声明模板(对齐 CLAUDE.md)

```
TS 0 错误 ✅
ConfigureProductDrawer.tsx → Vite 200 ✅
后端 /api/cpq/quotations/.../configure-product → 200 / 400 ✅
Flyway V162~V170 success=t ✅
并发 10 个同 AgCu 选配 → 1 创建 + 9 复用 ✅
浏览器手测 6 路径完整走通 ✅
```

---

## 12. 已知限制与未来扩展

| # | 限制 | 计划 |
|---|---|---|
| 1 | 工序单价 process_default_cost seed 仅 9 行 | V1.1 加管理后台 CRUD 页 |
| 2 | 组合产品父级 unit_weight 留 NULL | V1.1 公式 `parent.unit_weight = ∑ child.unit_weight × qty` 自动计算 |
| 3 | 选配 hf_part_no 后续被 ERP 真编号替代 | V1.2 加 rename / merge 工具,三表级联更新 |
| 4 | 材质 / 元素含量字典管理 UI 缺失 | V1.1 在"配置中心"加 `/material-recipes` 列表页 |
| 5 | 组合工艺字典管理 UI 缺失 | 同上,`/composite-processes` 列表页 |
| 6 | 模板按 recipe / specification 智能推荐 | V2.0 — 本期仅"用户手选" |
| 7 | 选配料号可被其他报价单复用 | 当前可以;如果要禁用可加 `mat_part.is_configured` 过滤 |
| 8 | ExternalApi 返回 hf_part_no 与本地 fingerprint 不一致 | V2 加幂等性 |

---

## 附录 A — 与 v5.1 主架构的关系图

```
v5.1 主架构(报价单 + 模板 + 核价 + Excel + 公式)
            ▲
            │ 0 改动
            │
┌───────────┴─────────────────────────────────────────┐
│ 本设计新增:                                          │
│   2 张材质字典表 (material_recipe + element)         │
│   2 张组合工艺表 (composite_process_def + mat_*)     │
│   mat_part 加 3 列 (recipe_id, product_type, finger) │
│   mat_bom CHECK 扩 ASSEMBLY                          │
│   quotation_line_item 加 2 列 (parent / type)        │
│   PartNoProvider 抽象 + V1 自动 + V2 外部接口        │
│   ConfigureProductDrawer 5 步自适应抽屉              │
│   Wizard Step1 改造(复用 QuotationCreateForm)        │
└──────────────────────────────────────────────────────┘
```

**承诺**:本设计落地后,**现有任何报价单 / 模板 / 核价 / Excel视图 / 公式 / 导入 任意一个 API 的输入输出 字节级不变**。

---

## 附录 B — 维护

- 实施后,RECORD.md 追加:`[2026-05-13] 添加产品 — 选配 v2 | 9 张 Flyway + PartNoProvider + 5 步自适应抽屉 + Step1 改造 | 0 侵入业务流程`
- PRD-v3.md "添加产品"章节增补 5 步自适应流程描述 + 组合产品建模
- 反模式.md RISK-1/2/3/4(若实施后命中)→ AP-NN

---

## 附录 C — 决策日志

| 日期 | 决策 | 拍板 |
|---|---|---|
| 2026-05-13 | Q1 组合产品 = A 方案(父+子 mat_part + ASSEMBLY bom) | 用户 |
| 2026-05-13 | Q2 选模板 = C(复用 QuotationCreateForm 改造 Step1) | 用户 |
| 2026-05-13 | Q3 line_item 组合 = Y1(1 父 + N 子两层) | 用户 |
| 2026-05-13 | Q4 组合工艺 = W1(双表字典+实例) | 用户 |
| 2026-05-13 | Q5 part_version = V1(v2000 baseline) | 用户 |
| 2026-05-13 | Q6 命名 = N1 + PartNoProvider 抽象 | 用户 |
| 2026-05-13 | Q6+ 指纹 = F2 修正(仅材料层匹配) | 用户 |
| 2026-05-13 | Q7 客户料号 = T1(不填) | 用户 |
| 2026-05-13 | Q8 单重 = U2(Step5 可选) | 用户 |
| 2026-05-13 | Q9 工序单价 = PR1(NULL + 全局变量) | 用户 |
