# 添加产品 — 选配 v2 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不动现有报价单/模板/核价/Excel/公式业务流程的前提下,新增"按材质+工序+组合工艺"的选配链路,选配产品落基础数据物理表(mat_part/mat_bom/mat_process/mat_composite_process),并完成 Wizard Step1 改造让用户在创建报价单时显式选模板。

**Architecture:** 9 张 Flyway migrations 建字典 + 加列;后端 PartNoProvider 抽象 + FingerprintCalculator + ConfigureProductService 三件套;前端 ConfigureProductDrawer 6 步自适应抽屉 + Wizard Step1 复用 QuotationCreateForm。

**Tech Stack:** Quarkus 3.23.3 (RESTEasy Reactive + Hibernate Panache) / PostgreSQL 16 / Flyway / React 18 + Ant Design 5 / TypeScript

**配套设计稿:** `docs/superpowers/specs/2026-05-13-add-product-configure-design.md`

---

## Phase 0:执行约定

**每完成一个 Task 都必须**:
1. 运行 Task 内列出的"自检命令"
2. 命令通过 → 提交 commit(消息按 Task 给的模板)
3. 提交后才进下一 Task

**前端改动后强制自检**(对齐 CLAUDE.md):
- `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json` → 0 错误
- 改动的 .tsx 文件 `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/<相对路径>` → 200

**后端改动后强制自检**:
- `touch` 任一 .java 触发 Quarkus 重启,等 5-7 秒
- 改动的 endpoint `curl -s http://localhost:8081/<path>` 期望 200/401(不要 500)

**Flyway 迁移后强制自检**:
- `PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db -c "SELECT version, success FROM flyway_schema_history WHERE version='NN'"` → success=t
- **任何 schema DDL 操作完毕**:`touch ImplicitJoinRewriter.java` 重启 Quarkus,清 `tableColumnsCache` 进程缓存

---

## 计划分卷

本计划较长,按 Phase 分卷追加。本卷为 **Phase 1(数据库迁移 V164~V174,共 9 Task)**。后续 Phase 2~13 将作为后续追加章节写入本文件。

---

## Phase 1:数据库迁移(V164 ~ V174)

### Task 1: V164 — material_recipe + material_recipe_element 表

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V164__material_recipe_and_element.sql`

- [ ] **Step 1: 创建迁移文件**

```sql
-- V164__material_recipe_and_element.sql

CREATE TABLE IF NOT EXISTS material_recipe (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64)  NOT NULL UNIQUE,
    symbol          VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    spec_label      VARCHAR(64),
    recipe_type     VARCHAR(16)  NOT NULL,
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

COMMENT ON TABLE material_recipe IS '材质配方字典(选配抽屉 P2 材质库)';
COMMENT ON TABLE material_recipe_element IS '材质元素含量(每材质 2-3 元素)';
```

- [ ] **Step 2: 触发 Flyway 跑这条**

```bash
touch cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java
# 等 5-7 秒 Quarkus 重启
```

- [ ] **Step 3: 验证迁移成功**

```bash
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='162'"
```
Expected: `162 | t`

- [ ] **Step 4: 验证表存在**

```bash
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "\d material_recipe" \
  -c "\d material_recipe_element"
```
Expected: 两张表完整列出

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V164__material_recipe_and_element.sql
git commit -m "feat(configure): V164 material_recipe + material_recipe_element 字典表"
```

---

### Task 2: V165 — composite_process_def + mat_composite_process

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V165__composite_process_def_and_mat.sql`

- [ ] **Step 1: 创建迁移文件**

```sql
-- V165__composite_process_def_and_mat.sql

CREATE TABLE IF NOT EXISTS composite_process_def (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64)  NOT NULL UNIQUE,
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

CREATE TABLE IF NOT EXISTS mat_composite_process (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_hf_part_no     VARCHAR(64)  NOT NULL,
    def_code              VARCHAR(64)  NOT NULL REFERENCES composite_process_def(code),
    seq_no                INT          NOT NULL,
    participating_parts   JSONB        NOT NULL,
    param_values          JSONB        NOT NULL DEFAULT '{}',
    part_version          INT          NOT NULL DEFAULT 2000,
    is_current            BOOLEAN      NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            UUID,
    CONSTRAINT uq_mat_composite_process UNIQUE (parent_hf_part_no, seq_no, part_version)
);
CREATE INDEX idx_mat_composite_process_parent ON mat_composite_process(parent_hf_part_no, part_version);

COMMENT ON TABLE composite_process_def IS '组合工艺字典(铆接/焊接/钎焊等)';
COMMENT ON TABLE mat_composite_process IS '组合工艺实例(挂在父料号上)';
```

- [ ] **Step 2: 触发 Flyway**

```bash
touch cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java
```

- [ ] **Step 3: 验证**

```bash
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='163'"
```
Expected: `163 | t`

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V165__composite_process_def_and_mat.sql
git commit -m "feat(configure): V165 composite_process_def + mat_composite_process"
```

---

### Task 3: V167 — mat_part 加 3 列

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V167__alter_mat_part_add_configure_cols.sql`

- [ ] **Step 1: 先查现有 mat_part schema 确认无冲突列**

```bash
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db -c "\d mat_part"
```
Expected: 输出列清单,确认无 `material_recipe_id` / `product_type` / `config_fingerprint` 列

- [ ] **Step 2: 创建迁移**

```sql
-- V167__alter_mat_part_add_configure_cols.sql

ALTER TABLE mat_part
    ADD COLUMN IF NOT EXISTS material_recipe_id   UUID         NULL REFERENCES material_recipe(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS product_type         VARCHAR(16)  NOT NULL DEFAULT 'SIMPLE',
    ADD COLUMN IF NOT EXISTS config_fingerprint   VARCHAR(64)  NULL;

ALTER TABLE mat_part DROP CONSTRAINT IF EXISTS chk_mat_part_product_type;
ALTER TABLE mat_part
    ADD CONSTRAINT chk_mat_part_product_type
        CHECK (product_type IN ('SIMPLE','COMPOSITE'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_mat_part_fingerprint ON mat_part(config_fingerprint)
    WHERE config_fingerprint IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_mat_part_recipe ON mat_part(material_recipe_id);
CREATE INDEX IF NOT EXISTS idx_mat_part_product_type ON mat_part(product_type);

COMMENT ON COLUMN mat_part.material_recipe_id IS '材质配方 FK(选配生成的料号会填;旧料号为 NULL)';
COMMENT ON COLUMN mat_part.product_type IS 'SIMPLE 独立 / COMPOSITE 组合(父料号)';
COMMENT ON COLUMN mat_part.config_fingerprint IS '配置指纹(F2):仅选配料号写;sha256 64 hex';
```

- [ ] **Step 3: 触发 + 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java
sleep 7
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='164'"
```
Expected: `164 | t`

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V167__alter_mat_part_add_configure_cols.sql
git commit -m "feat(configure): V167 mat_part 加 recipe_id/product_type/config_fingerprint 3 列"
```

---

### Task 4: V168 — mat_bom.bom_type 扩 ASSEMBLY

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V168__extend_mat_bom_bom_type_assembly.sql`

- [ ] **Step 1: 校核现有 schema**

```bash
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db -c "\d mat_bom"
```
观察:`bom_type` 现 CHECK 约束的允许值;`child_part_no` 列是否存在

- [ ] **Step 2: 创建迁移**

```sql
-- V168__extend_mat_bom_bom_type_assembly.sql

ALTER TABLE mat_bom DROP CONSTRAINT IF EXISTS chk_mat_bom_bom_type;
ALTER TABLE mat_bom
    ADD CONSTRAINT chk_mat_bom_bom_type
        CHECK (bom_type IN ('ELEMENT','INCOMING','OUTPUT','ASSEMBLY'));

ALTER TABLE mat_bom
    ADD COLUMN IF NOT EXISTS child_part_no VARCHAR(64) NULL;
CREATE INDEX IF NOT EXISTS idx_mat_bom_child_part_no ON mat_bom(child_part_no) WHERE child_part_no IS NOT NULL;

COMMENT ON CONSTRAINT chk_mat_bom_bom_type ON mat_bom IS 'ASSEMBLY: 组合产品父→子,child_part_no 表达子料号';
COMMENT ON COLUMN mat_bom.child_part_no IS 'ASSEMBLY 行:子配件 hf_part_no;其他类型为 NULL';
```

- [ ] **Step 3: 触发 + 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java
sleep 7
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='165'"
```
Expected: `165 | t`

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V168__extend_mat_bom_bom_type_assembly.sql
git commit -m "feat(configure): V168 mat_bom.bom_type 扩 ASSEMBLY + child_part_no 列"
```

---

### Task 5: V169 — quotation_line_item 加 2 列

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V169__alter_quotation_line_item_composite.sql`

- [ ] **Step 1: 创建迁移**

```sql
-- V169__alter_quotation_line_item_composite.sql

ALTER TABLE quotation_line_item
    ADD COLUMN IF NOT EXISTS parent_line_item_id  UUID         NULL REFERENCES quotation_line_item(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS composite_type       VARCHAR(16)  NOT NULL DEFAULT 'SIMPLE';

ALTER TABLE quotation_line_item DROP CONSTRAINT IF EXISTS chk_quotation_line_item_composite_type;
ALTER TABLE quotation_line_item
    ADD CONSTRAINT chk_quotation_line_item_composite_type
        CHECK (composite_type IN ('SIMPLE','COMPOSITE','PART'));

CREATE INDEX IF NOT EXISTS idx_quotation_line_item_parent ON quotation_line_item(parent_line_item_id);

COMMENT ON COLUMN quotation_line_item.parent_line_item_id IS '组合产品场景:子配件行→父行 id';
COMMENT ON COLUMN quotation_line_item.composite_type IS 'SIMPLE 独立 / COMPOSITE 组合父 / PART 组合子';
```

- [ ] **Step 2: 触发 + 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java
sleep 7
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='166'"
```
Expected: `166 | t`

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V169__alter_quotation_line_item_composite.sql
git commit -m "feat(configure): V169 quotation_line_item 加 parent_line_item_id/composite_type"
```

---

### Task 6: V171 — material_recipe seed 12 行 + 元素含量

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V171__seed_material_recipes.sql`

- [ ] **Step 1: 创建 seed 文件**

```sql
-- V171__seed_material_recipes.sql

INSERT INTO material_recipe (code, symbol, name, spec_label, recipe_type, sort_order) VALUES
  ('AgCu85',  'AgCu',   '银铜合金',   '85/15', 'locked',   10),
  ('AgCu90',  'AgCu',   '银铜合金',   '90/10', 'locked',   20),
  ('AgNi90',  'AgNi',   '银镍合金',   '90/10', 'editable', 30),
  ('AgNi95',  'AgNi',   '银镍合金',   '95/5',  'editable', 40),
  ('AgSnO2',  'AgSnO₂', '银氧化锡',   '88/12', 'partial',  50),
  ('AgSnO2b', 'AgSnO₂', '银氧化锡',   '85/15', 'partial',  60),
  ('AgCdO',   'AgCdO',  '银氧化镉',   '85/15', 'locked',   70),
  ('AgW60',   'AgW',    '银钨合金',   '60/40', 'editable', 80),
  ('AgW72',   'AgW',    '银钨合金',   '72/28', 'editable', 90),
  ('CuCr',    'CuCr',   '铜铬合金',   '99/1',  'partial',  100),
  ('AgPd',    'AgPd',   '银钯合金',   '70/30', 'locked',   110),
  ('AuAg',    'AuAg',   '金银合金',   '75/25', 'locked',   120)
ON CONFLICT (code) DO NOTHING;

-- locked 类:全锁定
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, is_locked, sort_order)
SELECT id, 'Ag', '银', 85.0, true, 1 FROM material_recipe WHERE code='AgCu85'
UNION ALL SELECT id, 'Cu', '铜', 15.0, true, 2 FROM material_recipe WHERE code='AgCu85'
UNION ALL SELECT id, 'Ag', '银', 90.0, true, 1 FROM material_recipe WHERE code='AgCu90'
UNION ALL SELECT id, 'Cu', '铜', 10.0, true, 2 FROM material_recipe WHERE code='AgCu90'
UNION ALL SELECT id, 'Ag', '银', 85.0, true, 1 FROM material_recipe WHERE code='AgCdO'
UNION ALL SELECT id, 'CdO','氧化镉', 15.0, true, 2 FROM material_recipe WHERE code='AgCdO'
UNION ALL SELECT id, 'Ag', '银', 70.0, true, 1 FROM material_recipe WHERE code='AgPd'
UNION ALL SELECT id, 'Pd', '钯', 30.0, true, 2 FROM material_recipe WHERE code='AgPd'
UNION ALL SELECT id, 'Au', '金', 75.0, true, 1 FROM material_recipe WHERE code='AuAg'
UNION ALL SELECT id, 'Ag', '银', 25.0, true, 2 FROM material_recipe WHERE code='AuAg';

-- editable 类:全可调
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order)
SELECT id, 'Ag', '银',  90.0, 85.0, 95.0, false, 1 FROM material_recipe WHERE code='AgNi90'
UNION ALL SELECT id, 'Ni', '镍', 10.0, 5.0,  15.0, false, 2 FROM material_recipe WHERE code='AgNi90'
UNION ALL SELECT id, 'Ag', '银',  95.0, 90.0, 98.0, false, 1 FROM material_recipe WHERE code='AgNi95'
UNION ALL SELECT id, 'Ni', '镍',  5.0,  2.0, 10.0, false, 2 FROM material_recipe WHERE code='AgNi95'
UNION ALL SELECT id, 'Ag', '银',  60.0, 50.0, 70.0, false, 1 FROM material_recipe WHERE code='AgW60'
UNION ALL SELECT id, 'W',  '钨',  40.0, 30.0, 50.0, false, 2 FROM material_recipe WHERE code='AgW60'
UNION ALL SELECT id, 'Ag', '银',  72.0, 65.0, 80.0, false, 1 FROM material_recipe WHERE code='AgW72'
UNION ALL SELECT id, 'W',  '钨',  28.0, 20.0, 35.0, false, 2 FROM material_recipe WHERE code='AgW72';

-- partial 类:Ag/Cu 锁定, 其他可调
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, is_locked, sort_order)
SELECT id, 'Ag', '银', 88.0, true, 1 FROM material_recipe WHERE code='AgSnO2';
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order)
SELECT id, 'SnO2', '氧化锡', 12.0,  8.0, 16.0, false, 2 FROM material_recipe WHERE code='AgSnO2'
UNION ALL SELECT id, 'In2O3','氧化铟', 0.5,  0.0,  1.5, false, 3 FROM material_recipe WHERE code='AgSnO2';

INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, is_locked, sort_order)
SELECT id, 'Ag', '银', 85.0, true, 1 FROM material_recipe WHERE code='AgSnO2b';
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order)
SELECT id, 'SnO2', '氧化锡', 15.0, 12.0, 18.0, false, 2 FROM material_recipe WHERE code='AgSnO2b'
UNION ALL SELECT id, 'In2O3','氧化铟', 0.0,  0.0,  1.0, false, 3 FROM material_recipe WHERE code='AgSnO2b';

INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, is_locked, sort_order)
SELECT id, 'Cu', '铜', 99.0, true, 1 FROM material_recipe WHERE code='CuCr';
INSERT INTO material_recipe_element (recipe_id, element_code, element_name, default_pct, min_pct, max_pct, is_locked, sort_order)
SELECT id, 'Cr', '铬', 0.8, 0.3, 1.5, false, 2 FROM material_recipe WHERE code='CuCr'
UNION ALL SELECT id, 'Zr', '锆', 0.2, 0.0, 0.5, false, 3 FROM material_recipe WHERE code='CuCr';
```

- [ ] **Step 2: 触发 + 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java
sleep 7
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='167'"
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT COUNT(*) FROM material_recipe; SELECT COUNT(*) FROM material_recipe_element;"
```
Expected: `167 | t`,recipe 12 行,element 28 行(粗略)

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V171__seed_material_recipes.sql
git commit -m "feat(configure): V171 seed 12 个材质 + 元素含量"
```

---

### Task 7: V172 — composite_process_def seed 6 行

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V172__seed_composite_process_def.sql`

- [ ] **Step 1: 创建文件**

```sql
-- V172__seed_composite_process_def.sql

INSERT INTO composite_process_def (code, name, icon, description, param_schema, sort_order) VALUES
  ('RIVET', '铆接', '🔩', '将两个配件通过铆钉压接固定',
   '[{"id":"pressure","label":"铆接压力","unit":"kN","type":"number","placeholder":"如 5.0"},
     {"id":"height","label":"铆钉高度","unit":"mm","type":"number","placeholder":"如 3.2"}]'::jsonb, 10),
  ('RESISTANCE_WELD', '电阻焊', '⚡', '通过电阻加热实现配件熔合',
   '[{"id":"current","label":"焊接电流","unit":"kA","type":"number","placeholder":"如 8.0"},
     {"id":"time","label":"焊接时间","unit":"ms","type":"number","placeholder":"如 80"}]'::jsonb, 20),
  ('LASER_WELD', '激光焊', '🔆', '使用激光束对配件进行精密焊接',
   '[{"id":"power","label":"激光功率","unit":"W","type":"number","placeholder":"如 200"},
     {"id":"speed","label":"焊接速度","unit":"mm/s","type":"number","placeholder":"如 50"}]'::jsonb, 30),
  ('BRAZING', '钎焊', '🔥', '使用钎料在低于母材熔点下连接配件',
   '[{"id":"temp","label":"钎焊温度","unit":"°C","type":"number","placeholder":"如 650"},
     {"id":"material","label":"钎料材质","unit":"","type":"text","placeholder":"如 银基钎料"}]'::jsonb, 40),
  ('ULTRASONIC_WELD', '超声波焊接', '〰️', '利用超声波振动将配件熔合',
   '[{"id":"amplitude","label":"振幅","unit":"μm","type":"number","placeholder":"如 30"},
     {"id":"weld_time","label":"焊接时间","unit":"ms","type":"number","placeholder":"如 500"}]'::jsonb, 50),
  ('PRESS_FIT', '压配合', '🗜️', '通过过盈配合将配件压入固定',
   '[{"id":"force","label":"压入力","unit":"kN","type":"number","placeholder":"如 12"},
     {"id":"fit","label":"配合公差","unit":"","type":"text","placeholder":"如 H7/r6"}]'::jsonb, 60)
ON CONFLICT (code) DO NOTHING;
```

- [ ] **Step 2: 触发 + 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java
sleep 7
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='168'"
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT code, name FROM composite_process_def ORDER BY sort_order"
```
Expected: 6 行

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V172__seed_composite_process_def.sql
git commit -m "feat(configure): V172 seed 6 个组合工艺字典"
```

---

### Task 8: V173 — process_default_cost + 全局变量注册

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V173__register_process_default_price_variable.sql`

- [ ] **Step 1: 校核现有 basic_data_config / global_variable_definition 表结构**

```bash
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "\d basic_data_config" -c "\d global_variable_definition"
```
确认 INSERT 字段名,**实施者必须**对照现有 schema 调整下面 INSERT 字段列表(本计划提供典型字段)

- [ ] **Step 2: 创建迁移**

```sql
-- V173__register_process_default_price_variable.sql

CREATE TABLE IF NOT EXISTS process_default_cost (
    process_code  VARCHAR(64) PRIMARY KEY,
    unit_price    DECIMAL(12,4) NOT NULL,
    currency      VARCHAR(8) NOT NULL DEFAULT 'CNY',
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO basic_data_config (id, name, sheet_name, physical_table, template_kind, status, created_at, updated_at)
VALUES (gen_random_uuid(), '工序默认单价', 'process_default_cost', 'process_default_cost', 'BOTH', 'ACTIVE', NOW(), NOW())
ON CONFLICT (sheet_name) DO NOTHING;

INSERT INTO global_variable_definition (id, code, label, sheet_name, key_field_names, value_field_name, status, created_at, updated_at)
VALUES (gen_random_uuid(), 'PROCESS_DEFAULT_PRICE', '工序默认单价', 'process_default_cost',
        ARRAY['process_code'], 'unit_price', 'ACTIVE', NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

INSERT INTO process_default_cost (process_code, unit_price) VALUES
  ('p1', 0.50), ('p2', 1.20), ('p3', 0.80), ('p4', 1.00), ('p5', 0.30),
  ('p6', 1.50), ('p7', 0.90), ('p8', 0.40), ('p9', 0.20)
ON CONFLICT (process_code) DO NOTHING;
```

- [ ] **Step 3: 触发 + 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java
sleep 7
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='169'"
```

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V173__register_process_default_price_variable.sql
git commit -m "feat(configure): V173 process_default_cost 表 + PROCESS_DEFAULT_PRICE 全局变量"
```

---

### Task 9: V174 — part_no_sequence 计数表

**Files:**
- Create: `cpq-backend/src/main/resources/db/migration/V174__part_no_sequence.sql`

- [ ] **Step 1: 创建迁移**

```sql
-- V174__part_no_sequence.sql

CREATE TABLE IF NOT EXISTS part_no_sequence (
    prefix   VARCHAR(32) PRIMARY KEY,
    next_val BIGINT      NOT NULL DEFAULT 1
);

INSERT INTO part_no_sequence (prefix, next_val) VALUES
  ('CFG-AgCu-', 1), ('CFG-AgNi-', 1), ('CFG-AgSnO₂-', 1),
  ('CFG-AgCdO-', 1), ('CFG-AgW-', 1), ('CFG-CuCr-', 1),
  ('CFG-AgPd-', 1), ('CFG-AuAg-', 1), ('CFG-COMBO-', 1)
ON CONFLICT (prefix) DO NOTHING;

COMMENT ON TABLE part_no_sequence IS '选配料号自增计数器(AutoAllocatePartNoProvider 使用)';
```

- [ ] **Step 2: 触发 + 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/formula/dataloader/ImplicitJoinRewriter.java
sleep 7
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version='170'; SELECT * FROM part_no_sequence"
```
Expected: `170 | t`,9 行

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/resources/db/migration/V174__part_no_sequence.sql
git commit -m "feat(configure): V174 part_no_sequence 计数表"
```

---

**Phase 1 完成检查点**:9 张 migration 全 `success=t`;基础表 + 字典 + seed + 序列全部就位。

---

## Phase 2:PartNoProvider 抽象 + V1 实现

### Task 10: PartNoProvider 接口 + PartNoContext

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/partno/PartNoProvider.java`
- Create: `cpq-backend/src/main/java/com/cpq/partno/PartNoContext.java`
- Create: `cpq-backend/src/main/java/com/cpq/partno/PartNoProvisionException.java`

- [ ] **Step 1: 写 PartNoContext**

```java
// cpq-backend/src/main/java/com/cpq/partno/PartNoContext.java
package com.cpq.partno;

import java.util.UUID;

public class PartNoContext {
    public String symbol;          // 'AgCu' / 'AgNi' / 'COMBO'
    public String productType;     // 'SIMPLE' / 'COMPOSITE'
    public UUID operatorId;        // 审计

    public PartNoContext() {}
    public PartNoContext(String symbol, String productType, UUID operatorId) {
        this.symbol = symbol;
        this.productType = productType;
        this.operatorId = operatorId;
    }
}
```

- [ ] **Step 2: 写 PartNoProvisionException**

```java
// cpq-backend/src/main/java/com/cpq/partno/PartNoProvisionException.java
package com.cpq.partno;

public class PartNoProvisionException extends RuntimeException {
    public PartNoProvisionException(String message) { super(message); }
    public PartNoProvisionException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 3: 写 PartNoProvider 接口**

```java
// cpq-backend/src/main/java/com/cpq/partno/PartNoProvider.java
package com.cpq.partno;

public interface PartNoProvider {
    /**
     * 生成新的 hf_part_no。
     * @param context 命名上下文(symbol / productType / operatorId)
     * @return 全局唯一 hf_part_no(如 "CFG-AgCu-000001")
     * @throws PartNoProvisionException 申请失败
     */
    String apply(PartNoContext context);
}
```

- [ ] **Step 4: 编译验证**

```bash
touch cpq-backend/src/main/java/com/cpq/partno/PartNoProvider.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 200(Quarkus 重启成功且无编译错误)

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/partno/
git commit -m "feat(configure): PartNoProvider 接口 + PartNoContext + 异常类型"
```

---

### Task 11: AutoAllocatePartNoProvider 实现

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/partno/AutoAllocatePartNoProvider.java`

- [ ] **Step 1: 实现**

```java
// cpq-backend/src/main/java/com/cpq/partno/AutoAllocatePartNoProvider.java
package com.cpq.partno;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigInteger;
import java.util.List;

/**
 * V1 实现:本地按 part_no_sequence 表自动分配 CFG-{symbol}-{6 位流水} 编号。
 * 切换到 ExternalApiPartNoProvider:application.properties 改 cpq.partno.provider=external
 */
@ApplicationScoped
@LookupIfProperty(name = "cpq.partno.provider", stringValue = "auto", lookupIfMissing = true)
public class AutoAllocatePartNoProvider implements PartNoProvider {

    @Inject EntityManager em;

    @Override
    @Transactional
    public String apply(PartNoContext ctx) {
        if (ctx == null || ctx.symbol == null || ctx.symbol.isBlank()) {
            throw new PartNoProvisionException("PartNoContext.symbol is required");
        }
        String prefix = "CFG-" + ctx.symbol + "-";
        long next = nextSequence(prefix);
        return String.format("%s%06d", prefix, next);
    }

    /** SELECT FOR UPDATE + UPDATE 实现并发安全的自增分配 */
    @SuppressWarnings("unchecked")
    private long nextSequence(String prefix) {
        // 1. 行锁取当前值
        List<Object[]> rows = em.createNativeQuery(
                "SELECT next_val FROM part_no_sequence WHERE prefix = :p FOR UPDATE")
            .setParameter("p", prefix)
            .getResultList();

        long curr;
        if (rows.isEmpty()) {
            // prefix 不存在 → INSERT 初始
            em.createNativeQuery(
                    "INSERT INTO part_no_sequence (prefix, next_val) VALUES (:p, 2) " +
                    "ON CONFLICT (prefix) DO NOTHING")
                .setParameter("p", prefix)
                .executeUpdate();
            return 1;
        } else {
            Object val = rows.get(0);
            curr = ((Number) val).longValue();
        }

        // 2. 自增
        em.createNativeQuery(
                "UPDATE part_no_sequence SET next_val = next_val + 1 WHERE prefix = :p")
            .setParameter("p", prefix)
            .executeUpdate();
        return curr;
    }
}
```

- [ ] **Step 2: 编译 + 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/partno/AutoAllocatePartNoProvider.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 200

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/partno/AutoAllocatePartNoProvider.java
git commit -m "feat(configure): AutoAllocatePartNoProvider V1 实现 (CFG-{symbol}-{流水})"
```

---

### Task 12: PartNoProvider 并发测试

**Files:**
- Create: `cpq-backend/src/test/java/com/cpq/partno/AutoAllocatePartNoProviderTest.java`

- [ ] **Step 1: 写并发测试(10 个线程同时申请 AgCu,期望全唯一)**

```java
// cpq-backend/src/test/java/com/cpq/partno/AutoAllocatePartNoProviderTest.java
package com.cpq.partno;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AutoAllocatePartNoProviderTest {

    @Inject AutoAllocatePartNoProvider provider;

    @Test
    void apply_returnsExpectedFormat() {
        String pn = provider.apply(new PartNoContext("AgCu", "SIMPLE", UUID.randomUUID()));
        assertTrue(pn.matches("CFG-AgCu-\\d{6}"), "format mismatch: " + pn);
    }

    @Test
    void apply_concurrent10Threads_allUnique() throws Exception {
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return provider.apply(new PartNoContext("AgNi", "SIMPLE", UUID.randomUUID()));
            }));
        }
        start.countDown();

        Set<String> seen = new HashSet<>();
        for (Future<String> f : futures) seen.add(f.get(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(n, seen.size(), "duplicates detected: " + seen);
    }

    @Test
    void apply_nullContext_throws() {
        assertThrows(PartNoProvisionException.class, () -> provider.apply(null));
    }

    @Test
    void apply_blankSymbol_throws() {
        assertThrows(PartNoProvisionException.class,
            () -> provider.apply(new PartNoContext("", "SIMPLE", UUID.randomUUID())));
    }
}
```

- [ ] **Step 2: 跑测试**

```bash
cd cpq-backend && ./mvnw test -Dtest=AutoAllocatePartNoProviderTest -q
```
Expected: BUILD SUCCESS,4 tests passed

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/test/java/com/cpq/partno/AutoAllocatePartNoProviderTest.java
git commit -m "test(configure): AutoAllocatePartNoProvider 并发 + 格式 + 异常 4 用例"
```

---

**Phase 2 完成检查点**:`AutoAllocatePartNoProvider` 可用,并发安全已验证。

---

## Phase 3:FingerprintCalculator + 字典 Entity/Service

### Task 13: FingerprintCalculator + 单元测试

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/configure/FingerprintCalculator.java`
- Create: `cpq-backend/src/test/java/com/cpq/configure/FingerprintCalculatorTest.java`

- [ ] **Step 1: 写计算器**

```java
// cpq-backend/src/main/java/com/cpq/configure/FingerprintCalculator.java
package com.cpq.configure;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class FingerprintCalculator {

    /** F2 指纹版本号(未来算法升级时切 v2) */
    public static final String VERSION = "v1";

    public static class ElementInput {
        public String elementCode;
        public BigDecimal pct;
        public ElementInput() {}
        public ElementInput(String elementCode, BigDecimal pct) {
            this.elementCode = elementCode;
            this.pct = pct;
        }
    }

    /** 独立产品指纹: 'v1|SIMPLE|recipe_code|element_sorted=...' */
    public String simpleFingerprint(String recipeCode, List<ElementInput> elements) {
        if (recipeCode == null || elements == null) {
            throw new IllegalArgumentException("recipeCode and elements required");
        }
        String sortedElems = elements.stream()
            .sorted(Comparator.comparing(e -> e.elementCode))
            .map(e -> e.elementCode + "=" + normalize(e.pct))
            .collect(Collectors.joining(","));
        String input = VERSION + "|SIMPLE|" + recipeCode + "|" + sortedElems;
        return sha256(input);
    }

    /** 组合产品指纹: 'v1|COMBO|子料号 sorted' */
    public String compositeFingerprint(List<String> childHfPartNos) {
        if (childHfPartNos == null || childHfPartNos.isEmpty()) {
            throw new IllegalArgumentException("childHfPartNos required");
        }
        String sorted = childHfPartNos.stream().sorted().collect(Collectors.joining(","));
        String input = VERSION + "|COMBO|" + sorted;
        return sha256(input);
    }

    /** BigDecimal 规范化: stripTrailingZeros 防 '12' vs '12.0' 误判 */
    private String normalize(BigDecimal val) {
        if (val == null) return "0";
        return val.stripTrailingZeros().toPlainString();
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 2: 写单元测试**

```java
// cpq-backend/src/test/java/com/cpq/configure/FingerprintCalculatorTest.java
package com.cpq.configure;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class FingerprintCalculatorTest {

    @Inject FingerprintCalculator calc;

    @Test
    void simpleFingerprint_isDeterministic() {
        var elems1 = List.of(
            new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90.0")),
            new FingerprintCalculator.ElementInput("Ni", new BigDecimal("10.0"))
        );
        var elems2 = List.of(
            new FingerprintCalculator.ElementInput("Ni", new BigDecimal("10.0")),
            new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90.0"))
        );
        assertEquals(calc.simpleFingerprint("AgNi90", elems1),
                     calc.simpleFingerprint("AgNi90", elems2),
                     "order should not matter (sorted internally)");
    }

    @Test
    void simpleFingerprint_normalizesTrailingZeros() {
        var a = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90")));
        var b = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90.0")));
        var c = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90.00")));
        String fpA = calc.simpleFingerprint("X", a);
        String fpB = calc.simpleFingerprint("X", b);
        String fpC = calc.simpleFingerprint("X", c);
        assertEquals(fpA, fpB);
        assertEquals(fpB, fpC);
    }

    @Test
    void simpleFingerprint_differentRecipe_differentHash() {
        var elems = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90")));
        assertNotEquals(
            calc.simpleFingerprint("AgNi90", elems),
            calc.simpleFingerprint("AgCu90", elems));
    }

    @Test
    void simpleFingerprint_differentPct_differentHash() {
        var a = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90")));
        var b = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("92")));
        assertNotEquals(calc.simpleFingerprint("AgNi90", a),
                        calc.simpleFingerprint("AgNi90", b));
    }

    @Test
    void compositeFingerprint_orderIndependent() {
        assertEquals(
            calc.compositeFingerprint(List.of("CFG-AgCu-000001", "CFG-AgNi-000003")),
            calc.compositeFingerprint(List.of("CFG-AgNi-000003", "CFG-AgCu-000001")));
    }

    @Test
    void compositeFingerprint_differentChildren_differentHash() {
        assertNotEquals(
            calc.compositeFingerprint(List.of("A", "B")),
            calc.compositeFingerprint(List.of("A", "C")));
    }

    @Test
    void simpleFingerprint_isExactlySha256Length() {
        var elems = List.of(new FingerprintCalculator.ElementInput("Ag", new BigDecimal("90")));
        assertEquals(64, calc.simpleFingerprint("X", elems).length());
    }
}
```

- [ ] **Step 3: 跑测试**

```bash
cd cpq-backend && ./mvnw test -Dtest=FingerprintCalculatorTest -q
```
Expected: BUILD SUCCESS,7 tests passed

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/FingerprintCalculator.java \
        cpq-backend/src/test/java/com/cpq/configure/FingerprintCalculatorTest.java
git commit -m "feat(configure): FingerprintCalculator + 7 单元测试 (F2 算法)"
```

---

### Task 14: MaterialRecipe + MaterialRecipeElement Entity(Panache)

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/configure/entity/MaterialRecipe.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/entity/MaterialRecipeElement.java`

- [ ] **Step 1: 写 MaterialRecipe**

```java
// cpq-backend/src/main/java/com/cpq/configure/entity/MaterialRecipe.java
package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "material_recipe")
public class MaterialRecipe extends PanacheEntityBase {
    @Id
    @GeneratedValue
    public UUID id;
    public String code;
    public String symbol;
    public String name;
    @Column(name = "spec_label")
    public String specLabel;
    @Column(name = "recipe_type")
    public String recipeType;
    @Column(name = "sort_order")
    public int sortOrder;
    public String status;
    @Column(name = "created_at")
    public OffsetDateTime createdAt;
    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;
    @Column(name = "created_by")
    public UUID createdBy;
    @Column(name = "updated_by")
    public UUID updatedBy;

    @OneToMany(mappedBy = "recipeId", fetch = FetchType.LAZY)
    public List<MaterialRecipeElement> elements;

    public static MaterialRecipe findByCodeOrThrow(String code) {
        MaterialRecipe r = find("code = ?1 AND status = 'ACTIVE'", code).firstResult();
        if (r == null) throw new IllegalArgumentException("材质未找到: " + code);
        return r;
    }
}
```

- [ ] **Step 2: 写 MaterialRecipeElement**

```java
// cpq-backend/src/main/java/com/cpq/configure/entity/MaterialRecipeElement.java
package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "material_recipe_element")
public class MaterialRecipeElement extends PanacheEntityBase {
    @Id
    @GeneratedValue
    public UUID id;
    @Column(name = "recipe_id")
    public UUID recipeId;
    @Column(name = "element_code")
    public String elementCode;
    @Column(name = "element_name")
    public String elementName;
    @Column(name = "default_pct")
    public BigDecimal defaultPct;
    @Column(name = "min_pct")
    public BigDecimal minPct;
    @Column(name = "max_pct")
    public BigDecimal maxPct;
    @Column(name = "is_locked")
    public boolean isLocked;
    @Column(name = "sort_order")
    public int sortOrder;
    @Column(name = "created_at")
    public OffsetDateTime createdAt;
}
```

- [ ] **Step 3: 编译验证**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/entity/MaterialRecipe.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 200

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/entity/
git commit -m "feat(configure): MaterialRecipe + MaterialRecipeElement Panache 实体"
```

---

### Task 15: CompositeProcessDef + MatCompositeProcess Entity

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/configure/entity/CompositeProcessDef.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/entity/MatCompositeProcess.java`

- [ ] **Step 1: CompositeProcessDef**

```java
// cpq-backend/src/main/java/com/cpq/configure/entity/CompositeProcessDef.java
package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "composite_process_def")
public class CompositeProcessDef extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;
    public String code;
    public String name;
    public String icon;
    public String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_schema", columnDefinition = "jsonb")
    public Object paramSchema;

    @Column(name = "sort_order")
    public int sortOrder;
    public String status;
    @Column(name = "created_at")
    public OffsetDateTime createdAt;

    public static CompositeProcessDef findByCodeOrThrow(String code) {
        CompositeProcessDef d = find("code = ?1 AND status = 'ACTIVE'", code).firstResult();
        if (d == null) throw new IllegalArgumentException("组合工艺未找到: " + code);
        return d;
    }
}
```

- [ ] **Step 2: MatCompositeProcess**

```java
// cpq-backend/src/main/java/com/cpq/configure/entity/MatCompositeProcess.java
package com.cpq.configure.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "mat_composite_process")
public class MatCompositeProcess extends PanacheEntityBase {
    @Id @GeneratedValue
    public UUID id;
    @Column(name = "parent_hf_part_no")
    public String parentHfPartNo;
    @Column(name = "def_code")
    public String defCode;
    @Column(name = "seq_no")
    public int seqNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "participating_parts", columnDefinition = "jsonb")
    public List<String> participatingParts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_values", columnDefinition = "jsonb")
    public Map<String, Object> paramValues;

    @Column(name = "part_version")
    public int partVersion = 2000;
    @Column(name = "is_current")
    public boolean isCurrent = true;
    @Column(name = "created_at")
    public OffsetDateTime createdAt;
    @Column(name = "created_by")
    public UUID createdBy;
}
```

- [ ] **Step 3: 编译验证**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/entity/CompositeProcessDef.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 200

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/entity/Composite*.java \
        cpq-backend/src/main/java/com/cpq/configure/entity/Mat*.java
git commit -m "feat(configure): CompositeProcessDef + MatCompositeProcess 实体(JSONB)"
```

---

### Task 16: MaterialRecipeService + Resource

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/configure/service/MaterialRecipeService.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/resource/MaterialRecipeResource.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/MaterialRecipeDTO.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/MaterialRecipeElementDTO.java`

- [ ] **Step 1: DTO**

```java
// MaterialRecipeDTO.java
package com.cpq.configure.dto;
import java.util.List;
import java.util.UUID;

public class MaterialRecipeDTO {
    public UUID id;
    public String code;
    public String symbol;
    public String name;
    public String specLabel;
    public String recipeType;
    public List<MaterialRecipeElementDTO> elements;  // 仅 detail 端点填充
}

// MaterialRecipeElementDTO.java
package com.cpq.configure.dto;
import java.math.BigDecimal;

public class MaterialRecipeElementDTO {
    public String elementCode;
    public String elementName;
    public BigDecimal defaultPct;
    public BigDecimal minPct;
    public BigDecimal maxPct;
    public boolean isLocked;
    public int sortOrder;
}
```

- [ ] **Step 2: Service**

```java
// MaterialRecipeService.java
package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeElementDTO;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeElement;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class MaterialRecipeService {

    public List<MaterialRecipeDTO> listActive() {
        return MaterialRecipe.<MaterialRecipe>find(
                "status = 'ACTIVE' ORDER BY sortOrder").list()
            .stream().map(this::toDTOLite).collect(Collectors.toList());
    }

    public MaterialRecipeDTO getDetail(UUID id) {
        MaterialRecipe r = MaterialRecipe.findById(id);
        if (r == null) throw new IllegalArgumentException("material_recipe 不存在: " + id);
        MaterialRecipeDTO dto = toDTOLite(r);
        dto.elements = MaterialRecipeElement.<MaterialRecipeElement>find(
                "recipeId = ?1 ORDER BY sortOrder", r.id).list()
            .stream().map(this::toElemDTO).collect(Collectors.toList());
        return dto;
    }

    private MaterialRecipeDTO toDTOLite(MaterialRecipe r) {
        MaterialRecipeDTO d = new MaterialRecipeDTO();
        d.id = r.id;
        d.code = r.code;
        d.symbol = r.symbol;
        d.name = r.name;
        d.specLabel = r.specLabel;
        d.recipeType = r.recipeType;
        return d;
    }

    private MaterialRecipeElementDTO toElemDTO(MaterialRecipeElement e) {
        MaterialRecipeElementDTO d = new MaterialRecipeElementDTO();
        d.elementCode = e.elementCode;
        d.elementName = e.elementName;
        d.defaultPct = e.defaultPct;
        d.minPct = e.minPct;
        d.maxPct = e.maxPct;
        d.isLocked = e.isLocked;
        d.sortOrder = e.sortOrder;
        return d;
    }
}
```

- [ ] **Step 3: Resource**

```java
// MaterialRecipeResource.java
package com.cpq.configure.resource;

import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.service.MaterialRecipeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;

@Path("/api/cpq/material-recipes")
@Produces(MediaType.APPLICATION_JSON)
public class MaterialRecipeResource {
    @Inject MaterialRecipeService service;

    @GET
    public List<MaterialRecipeDTO> list() {
        return service.listActive();
    }

    @GET
    @Path("/{id}")
    public MaterialRecipeDTO detail(@PathParam("id") UUID id) {
        return service.getDetail(id);
    }
}
```

- [ ] **Step 4: 验证端点**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/resource/MaterialRecipeResource.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/cpq/material-recipes
```
Expected: 401(auth 拦截,路由已注册)

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/
git commit -m "feat(configure): MaterialRecipeService + Resource + 2 DTO"
```

---

### Task 17: CompositeProcessService + Resource

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/configure/service/CompositeProcessService.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/resource/CompositeProcessResource.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/CompositeProcessDefDTO.java`

- [ ] **Step 1: DTO**

```java
// CompositeProcessDefDTO.java
package com.cpq.configure.dto;
import java.util.UUID;

public class CompositeProcessDefDTO {
    public UUID id;
    public String code;
    public String name;
    public String icon;
    public String description;
    public Object paramSchema;  // JSONB → 透传给前端
    public int sortOrder;
}
```

- [ ] **Step 2: Service + Resource**

```java
// CompositeProcessService.java
package com.cpq.configure.service;

import com.cpq.configure.dto.CompositeProcessDefDTO;
import com.cpq.configure.entity.CompositeProcessDef;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class CompositeProcessService {
    public List<CompositeProcessDefDTO> listActive() {
        return CompositeProcessDef.<CompositeProcessDef>find(
                "status = 'ACTIVE' ORDER BY sortOrder").list()
            .stream().map(d -> {
                CompositeProcessDefDTO dto = new CompositeProcessDefDTO();
                dto.id = d.id;
                dto.code = d.code;
                dto.name = d.name;
                dto.icon = d.icon;
                dto.description = d.description;
                dto.paramSchema = d.paramSchema;
                dto.sortOrder = d.sortOrder;
                return dto;
            }).collect(Collectors.toList());
    }
}
```

```java
// CompositeProcessResource.java
package com.cpq.configure.resource;

import com.cpq.configure.dto.CompositeProcessDefDTO;
import com.cpq.configure.service.CompositeProcessService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/cpq/composite-processes")
@Produces(MediaType.APPLICATION_JSON)
public class CompositeProcessResource {
    @Inject CompositeProcessService service;

    @GET
    public List<CompositeProcessDefDTO> list() { return service.listActive(); }
}
```

- [ ] **Step 3: 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/resource/CompositeProcessResource.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/cpq/composite-processes
```
Expected: 401

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/service/CompositeProcessService.java \
        cpq-backend/src/main/java/com/cpq/configure/resource/CompositeProcessResource.java \
        cpq-backend/src/main/java/com/cpq/configure/dto/CompositeProcessDefDTO.java
git commit -m "feat(configure): CompositeProcessService + Resource + DTO"
```

---

**Phase 3 完成检查点**:Fingerprint 算法已测试,材质 + 组合工艺两个字典端点 401 通。

---

## Phase 4:ConfigureProductService 核心 + DTO

### Task 18: ConfigureProductRequest + Response DTO

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/ConfigureProductRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/PartRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/ElementOverride.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/CompositeProcessRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/ConfigureProductResponse.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/LookupFingerprintRequest.java`
- Create: `cpq-backend/src/main/java/com/cpq/configure/dto/LookupFingerprintResponse.java`

- [ ] **Step 1: 写 DTO 文件**

```java
// ConfigureProductRequest.java
package com.cpq.configure.dto;
import java.util.List;

public class ConfigureProductRequest {
    public String productType;             // 'SIMPLE' | 'COMPOSITE'
    public List<PartRequest> parts;
    public List<CompositeProcessRequest> compositeProcesses;
}

// PartRequest.java
package com.cpq.configure.dto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class PartRequest {
    public String name;
    public String partMode;                // 'existing' | 'custom'
    public String existingHfPartNo;
    public String recipeCode;
    public List<ElementOverride> elements;
    public List<UUID> processIds;
    public BigDecimal unitWeightGrams;
}

// ElementOverride.java
package com.cpq.configure.dto;
import java.math.BigDecimal;
public class ElementOverride {
    public String elementCode;
    public BigDecimal pct;
}

// CompositeProcessRequest.java
package com.cpq.configure.dto;
import java.util.List;
import java.util.Map;
public class CompositeProcessRequest {
    public String defCode;
    public List<Integer> participatingPartIndexes;
    public Map<String, Object> params;
}

// ConfigureProductResponse.java
package com.cpq.configure.dto;
import java.util.List;
import java.util.Map;
public class ConfigureProductResponse {
    public List<Map<String, Object>> lineItems;    // 与 AddProductModal.onConfirm 同型(LineItemDTO)
    public boolean fingerprintMatched;
    public List<String> reusedHfPartNos;
}

// LookupFingerprintRequest.java
package com.cpq.configure.dto;
import java.util.List;
public class LookupFingerprintRequest {
    public String productType;             // 'SIMPLE' | 'COMPOSITE'
    public String recipeCode;               // SIMPLE 时必填
    public List<ElementOverride> elements;  // SIMPLE 时必填
    public List<String> childHfPartNos;     // COMPOSITE 时必填
}

// LookupFingerprintResponse.java
package com.cpq.configure.dto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
public class LookupFingerprintResponse {
    public boolean matched;
    public String hfPartNo;
    public Snapshot snapshot;

    public static class Snapshot {
        public BigDecimal unitWeightGrams;
        public List<Map<String, Object>> processes;          // [{processCode,name,seqNo}]
        public List<Map<String, Object>> compositeProcesses; // 组合产品才有
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/dto/ConfigureProductRequest.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 200

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/dto/
git commit -m "feat(configure): 7 个 DTO 文件 (ConfigureProductRequest/Response/Lookup/Part/Element/CompositeProcess)"
```

---

### Task 19: ConfigureProductService — lookup-fingerprint 端点逻辑

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java` (骨架 + lookup 部分)

- [ ] **Step 1: 写 Service 骨架(后续 Task 20-22 在此文件追加)**

```java
// cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java
package com.cpq.configure.service;

import com.cpq.configure.FingerprintCalculator;
import com.cpq.configure.FingerprintCalculator.ElementInput;
import com.cpq.configure.dto.*;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ConfigureProductService {

    @Inject EntityManager em;
    @Inject FingerprintCalculator fingerprintCalc;

    // ── lookup-fingerprint 端点 ──

    public LookupFingerprintResponse lookupFingerprint(LookupFingerprintRequest req) {
        if (req == null || req.productType == null) {
            throw new IllegalArgumentException("productType is required");
        }

        String fp;
        if ("SIMPLE".equals(req.productType)) {
            if (req.recipeCode == null || req.elements == null || req.elements.isEmpty()) {
                throw new IllegalArgumentException("SIMPLE: recipeCode + elements required");
            }
            List<ElementInput> elems = req.elements.stream()
                .map(e -> new ElementInput(e.elementCode, e.pct))
                .collect(Collectors.toList());
            fp = fingerprintCalc.simpleFingerprint(req.recipeCode, elems);
        } else if ("COMPOSITE".equals(req.productType)) {
            if (req.childHfPartNos == null || req.childHfPartNos.size() < 2) {
                throw new IllegalArgumentException("COMPOSITE: childHfPartNos size >= 2");
            }
            fp = fingerprintCalc.compositeFingerprint(req.childHfPartNos);
        } else {
            throw new IllegalArgumentException("Unknown productType: " + req.productType);
        }

        String hfPartNo = lookupHfByFingerprint(fp);
        LookupFingerprintResponse resp = new LookupFingerprintResponse();
        if (hfPartNo == null) {
            resp.matched = false;
            return resp;
        }
        resp.matched = true;
        resp.hfPartNo = hfPartNo;
        resp.snapshot = buildSnapshot(hfPartNo);
        return resp;
    }

    @SuppressWarnings("unchecked")
    String lookupHfByFingerprint(String fp) {
        List<Object> rows = em.createNativeQuery(
                "SELECT part_no FROM mat_part WHERE config_fingerprint = :fp")
            .setParameter("fp", fp)
            .getResultList();
        return rows.isEmpty() ? null : (String) rows.get(0);
    }

    @SuppressWarnings("unchecked")
    LookupFingerprintResponse.Snapshot buildSnapshot(String hfPartNo) {
        LookupFingerprintResponse.Snapshot s = new LookupFingerprintResponse.Snapshot();

        // unit_weight
        List<Object> w = em.createNativeQuery(
                "SELECT unit_weight FROM mat_part WHERE part_no = :p")
            .setParameter("p", hfPartNo).getResultList();
        s.unitWeightGrams = w.isEmpty() || w.get(0) == null ? null : new BigDecimal(w.get(0).toString());

        // processes
        List<Object[]> procs = em.createNativeQuery(
                "SELECT process_code, seq_no FROM mat_process " +
                "WHERE hf_part_no = :p AND is_current = true ORDER BY seq_no")
            .setParameter("p", hfPartNo).getResultList();
        s.processes = procs.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("processCode", row[0]);
            m.put("seqNo", row[1]);
            return m;
        }).collect(Collectors.toList());

        // composite processes(若为组合父级)
        List<Object[]> cprocs = em.createNativeQuery(
                "SELECT def_code, seq_no, participating_parts, param_values FROM mat_composite_process " +
                "WHERE parent_hf_part_no = :p AND is_current = true ORDER BY seq_no")
            .setParameter("p", hfPartNo).getResultList();
        s.compositeProcesses = cprocs.stream().map(row -> {
            Map<String, Object> m = new HashMap<>();
            m.put("defCode", row[0]);
            m.put("seqNo", row[1]);
            m.put("participatingParts", row[2]);
            m.put("paramValues", row[3]);
            return m;
        }).collect(Collectors.toList());

        return s;
    }

    // ── configure-product 端点 ── (在 Task 20-22 追加)
}
```

- [ ] **Step 2: 编译**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 200

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java
git commit -m "feat(configure): ConfigureProductService.lookupFingerprint (查指纹 + 构建 snapshot)"
```

---

### Task 20: ConfigureProductService — resolvePart (existing + custom)

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java` (追加 resolvePart 系列)

- [ ] **Step 1: 在 ConfigureProductService 内追加方法**

```java
// 在 ConfigureProductService 类内追加以下代码(放在 lookupFingerprint 下方)

@Inject com.cpq.partno.PartNoProvider partNoProvider;

/** 解析单个配件,返回 hf_part_no(命中复用 / 未命中新建) */
String resolvePart(PartRequest pr, UUID operatorId, List<String> reused) {
    if ("existing".equals(pr.partMode)) {
        // 已有路径:直接返,不动基础表
        if (pr.existingHfPartNo == null || pr.existingHfPartNo.isBlank()) {
            throw new IllegalArgumentException("existing 模式 existingHfPartNo 必填");
        }
        // 校验存在
        Object exists = em.createNativeQuery(
                "SELECT 1 FROM mat_part WHERE part_no = :p")
            .setParameter("p", pr.existingHfPartNo).getResultStream().findFirst().orElse(null);
        if (exists == null) {
            throw new IllegalArgumentException("料号不存在: " + pr.existingHfPartNo);
        }
        return pr.existingHfPartNo;
    }

    if (!"custom".equals(pr.partMode)) {
        throw new IllegalArgumentException("partMode must be 'existing' or 'custom': " + pr.partMode);
    }

    // ── custom 路径 ──
    validateCustomPart(pr);

    List<FingerprintCalculator.ElementInput> elems = pr.elements.stream()
        .map(e -> new FingerprintCalculator.ElementInput(e.elementCode, e.pct))
        .collect(Collectors.toList());
    String fp = fingerprintCalc.simpleFingerprint(pr.recipeCode, elems);

    String existing = lookupHfByFingerprint(fp);
    if (existing != null) {
        reused.add(existing);
        return existing;
    }

    // 未命中 → 新建
    com.cpq.configure.entity.MaterialRecipe recipe =
        com.cpq.configure.entity.MaterialRecipe.findByCodeOrThrow(pr.recipeCode);

    String hfPartNo = partNoProvider.apply(
        new com.cpq.partno.PartNoContext(recipe.symbol, "SIMPLE", operatorId));

    insertMatPart(hfPartNo, "SIMPLE", fp, pr.unitWeightGrams, recipe.id);
    insertElementBom(hfPartNo, pr.elements);
    if (pr.processIds != null && !pr.processIds.isEmpty()) {
        insertProcesses(hfPartNo, pr.processIds);
    }
    initPartVersionBaseline(hfPartNo);

    return hfPartNo;
}

void validateCustomPart(PartRequest pr) {
    if (pr.recipeCode == null) throw new IllegalArgumentException("custom 模式 recipeCode 必填");
    if (pr.elements == null || pr.elements.isEmpty()) {
        throw new IllegalArgumentException("custom 模式 elements 必填");
    }
    // 元素合校验
    java.math.BigDecimal sum = pr.elements.stream()
        .map(e -> e.pct == null ? java.math.BigDecimal.ZERO : e.pct)
        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    if (sum.subtract(new java.math.BigDecimal("100")).abs().compareTo(new java.math.BigDecimal("0.01")) > 0) {
        throw new IllegalArgumentException("元素含量之和必须 = 100,当前: " + sum);
    }

    // 校验 locked / range
    com.cpq.configure.entity.MaterialRecipe recipe =
        com.cpq.configure.entity.MaterialRecipe.findByCodeOrThrow(pr.recipeCode);
    List<com.cpq.configure.entity.MaterialRecipeElement> defs =
        com.cpq.configure.entity.MaterialRecipeElement.list("recipeId", recipe.id);
    Map<String, com.cpq.configure.entity.MaterialRecipeElement> defByCode = defs.stream()
        .collect(Collectors.toMap(e -> e.elementCode, e -> e));

    for (ElementOverride eo : pr.elements) {
        com.cpq.configure.entity.MaterialRecipeElement def = defByCode.get(eo.elementCode);
        if (def == null) throw new IllegalArgumentException("元素未在 recipe 定义: " + eo.elementCode);
        if (def.isLocked) {
            if (eo.pct.compareTo(def.defaultPct) != 0) {
                throw new IllegalArgumentException("元素已锁定,不可修改: " + eo.elementCode);
            }
        } else {
            if (eo.pct.compareTo(def.minPct) < 0 || eo.pct.compareTo(def.maxPct) > 0) {
                throw new IllegalArgumentException(
                    "元素含量超出范围 [" + def.minPct + ", " + def.maxPct + "]: " + eo.elementCode);
            }
        }
    }
}
```

- [ ] **Step 2: 在同一文件追加落库辅助方法**

```java
void insertMatPart(String hfPartNo, String productType, String fingerprint,
                   java.math.BigDecimal unitWeight, java.util.UUID materialRecipeId) {
    em.createNativeQuery(
            "INSERT INTO mat_part (part_no, product_type, config_fingerprint, " +
            "unit_weight, material_recipe_id, created_at, updated_at) " +
            "VALUES (:pn, :pt, :fp, :uw, :mri, NOW(), NOW()) " +
            "ON CONFLICT (config_fingerprint) DO NOTHING")
        .setParameter("pn", hfPartNo)
        .setParameter("pt", productType)
        .setParameter("fp", fingerprint)
        .setParameter("uw", unitWeight)
        .setParameter("mri", materialRecipeId)
        .executeUpdate();
}

void insertElementBom(String hfPartNo, List<ElementOverride> elements) {
    int seq = 1;
    for (ElementOverride eo : elements) {
        em.createNativeQuery(
                "INSERT INTO mat_bom (hf_part_no, bom_type, seq_no, element_name, " +
                "composition_pct, part_version, is_current, created_at) " +
                "VALUES (:p, 'ELEMENT', :sq, :en, :pct, 2000, true, NOW())")
            .setParameter("p", hfPartNo)
            .setParameter("sq", seq++)
            .setParameter("en", eo.elementCode)
            .setParameter("pct", eo.pct)
            .executeUpdate();
    }
}

void insertProcesses(String hfPartNo, List<java.util.UUID> processIds) {
    int seq = 1;
    for (java.util.UUID processId : processIds) {
        // 从 process 表查 code
        String code = (String) em.createNativeQuery(
                "SELECT code FROM process WHERE id = :id")
            .setParameter("id", processId).getSingleResult();
        em.createNativeQuery(
                "INSERT INTO mat_process (hf_part_no, seq_no, process_code, unit_price, " +
                "status, is_current, part_version, version, created_at) " +
                "VALUES (:p, :sq, :code, NULL, 'ACTIVE', true, 2000, 1, NOW())")
            .setParameter("p", hfPartNo)
            .setParameter("sq", seq++)
            .setParameter("code", code)
            .executeUpdate();
    }
}

void initPartVersionBaseline(String hfPartNo) {
    // 写 v2000 baseline 行(对齐 V156)
    em.createNativeQuery(
            "INSERT INTO mat_part_version_log (hf_part_no, version, action, fingerprint, created_at) " +
            "VALUES (:p, 2000, 'INITIAL', NULL, NOW()) " +
            "ON CONFLICT DO NOTHING")
        .setParameter("p", hfPartNo)
        .executeUpdate();
}
```

> **实施时注意**:`mat_part_version_log` / `mat_process` / `mat_bom` 实际表字段名以现 schema 为准。Step 2 列表为典型字段,实施者用 `\d <table>` 校核后微调。

- [ ] **Step 3: 编译**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 200

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java
git commit -m "feat(configure): ConfigureProductService.resolvePart (existing/custom 双路径) + 落库辅助"
```

---

### Task 21: ConfigureProductService — configure 主入口(组合产品父级 + LineItem)

**Files:**
- Modify: `cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java` (追加 configure 主入口)

- [ ] **Step 1: 追加主入口**

```java
// 在 ConfigureProductService 内追加

@jakarta.transaction.Transactional
public ConfigureProductResponse configure(java.util.UUID quotationId,
                                          ConfigureProductRequest req,
                                          java.util.UUID operatorId) {
    validateRequest(req);

    List<String> childHfPartNos = new java.util.ArrayList<>();
    List<String> reused = new java.util.ArrayList<>();

    // PASS 1: 解析所有配件
    for (PartRequest pr : req.parts) {
        childHfPartNos.add(resolvePart(pr, operatorId, reused));
    }

    // PASS 2: 组合产品父级
    String parentHfPartNo = null;
    if ("COMPOSITE".equals(req.productType)) {
        String fp = fingerprintCalc.compositeFingerprint(childHfPartNos);
        parentHfPartNo = lookupHfByFingerprint(fp);
        if (parentHfPartNo == null) {
            parentHfPartNo = partNoProvider.apply(
                new com.cpq.partno.PartNoContext("COMBO", "COMPOSITE", operatorId));
            insertMatPart(parentHfPartNo, "COMPOSITE", fp, null, null);
            insertAssemblyBom(parentHfPartNo, childHfPartNos);
            if (req.compositeProcesses != null && !req.compositeProcesses.isEmpty()) {
                insertCompositeProcesses(parentHfPartNo, req.compositeProcesses, childHfPartNos, operatorId);
            }
            initPartVersionBaseline(parentHfPartNo);
        } else {
            reused.add(parentHfPartNo);
        }
    }

    // PASS 3: line_items
    List<java.util.Map<String, Object>> lineItems =
        buildLineItems(quotationId, req, parentHfPartNo, childHfPartNos, operatorId);

    ConfigureProductResponse resp = new ConfigureProductResponse();
    resp.lineItems = lineItems;
    resp.fingerprintMatched = !reused.isEmpty();
    resp.reusedHfPartNos = reused;
    return resp;
}

void validateRequest(ConfigureProductRequest req) {
    if (req == null) throw new IllegalArgumentException("request body 必填");
    if (!"SIMPLE".equals(req.productType) && !"COMPOSITE".equals(req.productType))
        throw new IllegalArgumentException("productType must be SIMPLE or COMPOSITE");
    if (req.parts == null || req.parts.isEmpty())
        throw new IllegalArgumentException("parts 必填");
    if ("SIMPLE".equals(req.productType) && req.parts.size() != 1)
        throw new IllegalArgumentException("SIMPLE 时 parts.size = 1");
    if ("COMPOSITE".equals(req.productType)) {
        if (req.parts.size() < 2 || req.parts.size() > 8)
            throw new IllegalArgumentException("COMPOSITE 时 parts.size ∈ [2,8]");
        if (req.compositeProcesses != null) {
            for (CompositeProcessRequest cp : req.compositeProcesses) {
                if (cp.participatingPartIndexes == null || cp.participatingPartIndexes.size() < 2)
                    throw new IllegalArgumentException("组合工艺参与配件 < 2: " + cp.defCode);
            }
        }
    }
}

void insertAssemblyBom(String parentHfPartNo, List<String> childHfPartNos) {
    int seq = 1;
    for (String childPn : childHfPartNos) {
        em.createNativeQuery(
                "INSERT INTO mat_bom (hf_part_no, bom_type, seq_no, child_part_no, " +
                "composition_pct, part_version, is_current, created_at) " +
                "VALUES (:p, 'ASSEMBLY', :sq, :c, 100.00, 2000, true, NOW())")
            .setParameter("p", parentHfPartNo)
            .setParameter("sq", seq++)
            .setParameter("c", childPn)
            .executeUpdate();
    }
}

void insertCompositeProcesses(String parentHfPartNo,
                              List<CompositeProcessRequest> cps,
                              List<String> childHfPartNos,
                              java.util.UUID operatorId) {
    int seq = 1;
    com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
    for (CompositeProcessRequest cp : cps) {
        // 校验 def 存在
        com.cpq.configure.entity.CompositeProcessDef.findByCodeOrThrow(cp.defCode);
        // 把 participating 下标转 hf_part_no
        List<String> partsInvolved = cp.participatingPartIndexes.stream()
            .map(childHfPartNos::get)
            .collect(java.util.stream.Collectors.toList());
        try {
            em.createNativeQuery(
                    "INSERT INTO mat_composite_process " +
                    "(parent_hf_part_no, def_code, seq_no, participating_parts, " +
                    "param_values, part_version, is_current, created_at, created_by) " +
                    "VALUES (:p, :d, :sq, CAST(:pp AS jsonb), CAST(:pv AS jsonb), " +
                    "2000, true, NOW(), :op)")
                .setParameter("p", parentHfPartNo)
                .setParameter("d", cp.defCode)
                .setParameter("sq", seq++)
                .setParameter("pp", om.writeValueAsString(partsInvolved))
                .setParameter("pv", om.writeValueAsString(cp.params == null ? new java.util.HashMap<>() : cp.params))
                .setParameter("op", operatorId)
                .executeUpdate();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new RuntimeException("JSON 序列化失败", ex);
        }
    }
}

@SuppressWarnings("unchecked")
List<java.util.Map<String, Object>> buildLineItems(java.util.UUID quotationId,
                                                   ConfigureProductRequest req,
                                                   String parentHfPartNo,
                                                   List<String> childHfPartNos,
                                                   java.util.UUID operatorId) {
    List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();

    if ("SIMPLE".equals(req.productType)) {
        // 1 行 line_item
        String pn = childHfPartNos.get(0);
        java.util.UUID id = insertLineItem(quotationId, pn, null, "SIMPLE");
        out.add(buildLineItemDTO(id, pn, "SIMPLE", null));
        return out;
    }

    // COMPOSITE: 父 + N 子
    java.util.UUID parentId = insertLineItem(quotationId, parentHfPartNo, null, "COMPOSITE");
    out.add(buildLineItemDTO(parentId, parentHfPartNo, "COMPOSITE", null));

    for (String childPn : childHfPartNos) {
        java.util.UUID childId = insertLineItem(quotationId, childPn, parentId, "PART");
        out.add(buildLineItemDTO(childId, childPn, "PART", parentId));
    }
    return out;
}

java.util.UUID insertLineItem(java.util.UUID quotationId, String hfPartNo,
                              java.util.UUID parentLineItemId, String compositeType) {
    java.util.UUID id = java.util.UUID.randomUUID();
    em.createNativeQuery(
            "INSERT INTO quotation_line_item (id, quotation_id, product_part_no_snapshot, " +
            "parent_line_item_id, composite_type, quantity, created_at, updated_at) " +
            "VALUES (:id, :q, :pn, :pp, :ct, 1, NOW(), NOW())")
        .setParameter("id", id)
        .setParameter("q", quotationId)
        .setParameter("pn", hfPartNo)
        .setParameter("pp", parentLineItemId)
        .setParameter("ct", compositeType)
        .executeUpdate();
    return id;
}

java.util.Map<String, Object> buildLineItemDTO(java.util.UUID id, String hfPartNo,
                                                String compositeType, java.util.UUID parentId) {
    java.util.Map<String, Object> m = new java.util.HashMap<>();
    m.put("id", id);
    m.put("productPartNo", hfPartNo);
    m.put("compositeType", compositeType);
    m.put("parentLineItemId", parentId);
    m.put("quantity", 1);
    return m;
}
```

> **实施提示**:
> - `quotation_line_item` 字段名以现 schema 为准(典型字段:`id` / `quotation_id` / `product_part_no_snapshot` / `quantity`)
> - `buildLineItemDTO` 返回 Map 已足够前端 onConfirm,但生产实施可改为正式 LineItemDTO

- [ ] **Step 2: 编译**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 200

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/service/ConfigureProductService.java
git commit -m "feat(configure): ConfigureProductService.configure 主入口 + 组合产品父级 + line_item 父+子"
```

---

### Task 22: ConfigureProductResource — REST 端点

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/configure/resource/ConfigureProductResource.java`

- [ ] **Step 1: 写 Resource**

```java
// cpq-backend/src/main/java/com/cpq/configure/resource/ConfigureProductResource.java
package com.cpq.configure.resource;

import com.cpq.configure.dto.*;
import com.cpq.configure.service.ConfigureProductService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

@Path("/api/cpq/quotations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigureProductResource {

    @Inject ConfigureProductService service;
    @Inject SecurityIdentity identity;

    @POST
    @Path("/configure/lookup-fingerprint")
    public LookupFingerprintResponse lookupFingerprint(LookupFingerprintRequest req) {
        return service.lookupFingerprint(req);
    }

    @POST
    @Path("/{quotationId}/configure-product")
    public Response configureProduct(@PathParam("quotationId") UUID quotationId,
                                     ConfigureProductRequest req) {
        UUID operatorId = currentUserId();
        ConfigureProductResponse resp = service.configure(quotationId, req, operatorId);
        return Response.ok(resp).build();
    }

    /** 从 SecurityIdentity 取当前用户 id(实施时按现有项目惯例调整) */
    UUID currentUserId() {
        try {
            String s = identity.getPrincipal().getName();
            return UUID.fromString(s);
        } catch (Exception e) {
            // dev 容错
            return UUID.fromString("00000000-0000-0000-0000-000000000000");
        }
    }
}
```

- [ ] **Step 2: 验证端点**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/resource/ConfigureProductResource.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  -H "Content-Type: application/json" -d '{}' \
  http://localhost:8081/api/cpq/quotations/configure/lookup-fingerprint
curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  -H "Content-Type: application/json" -d '{}' \
  http://localhost:8081/api/cpq/quotations/00000000-0000-0000-0000-000000000000/configure-product
```
Expected: 两个都 401(auth 拦截,路由就绪)

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/resource/ConfigureProductResource.java
git commit -m "feat(configure): ConfigureProductResource (lookup-fingerprint + configure-product)"
```

---

### Task 23: PartSearchResource.searchForConfigure

**Files:**
- Create: `cpq-backend/src/main/java/com/cpq/configure/resource/ConfigureSearchResource.java`

- [ ] **Step 1: 写搜索端点**

```java
// cpq-backend/src/main/java/com/cpq/configure/resource/ConfigureSearchResource.java
package com.cpq.configure.resource;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.*;

@Path("/api/cpq/quotations/configure")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigureSearchResource {

    @Inject EntityManager em;

    @GET
    @Path("/search-parts")
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchParts(
            @QueryParam("q") String q,
            @QueryParam("size") @DefaultValue("50") int size) {

        if (q == null || q.isBlank()) return Collections.emptyList();
        int safeSize = Math.min(Math.max(size, 1), 200);
        String pattern = "%" + q.trim() + "%";

        List<Object[]> rows = em.createNativeQuery(
                "SELECT mp.part_no, mp.part_name, mp.specification, mp.size_info, mp.status_code, " +
                "       mr.id, mr.code, mr.symbol, mr.name, mr.spec_label, mr.recipe_type " +
                "FROM mat_part mp " +
                "LEFT JOIN material_recipe mr ON mr.id = mp.material_recipe_id " +
                "WHERE COALESCE(mp.status_code, 'Y') = 'Y' " +
                "  AND ( mp.part_no ILIKE :p OR mp.part_name ILIKE :p OR " +
                "        COALESCE(mp.specification,'') ILIKE :p OR " +
                "        COALESCE(mp.size_info,'') ILIKE :p OR " +
                "        COALESCE(mr.symbol,'') ILIKE :p OR " +
                "        COALESCE(mr.name,'') ILIKE :p ) " +
                "ORDER BY mp.part_no " +
                "LIMIT :s")
            .setParameter("p", pattern)
            .setParameter("s", safeSize)
            .getResultList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("hfPartNo", r[0]);
            m.put("partName", r[1]);
            m.put("specification", r[2]);
            m.put("sizeInfo", r[3]);
            m.put("statusCode", r[4]);
            m.put("recipeId", r[5]);
            m.put("recipeCode", r[6]);
            m.put("recipeSymbol", r[7]);
            m.put("recipeName", r[8]);
            m.put("recipeSpec", r[9]);
            m.put("recipeType", r[10]);
            out.add(m);
        }
        return out;
    }
}
```

- [ ] **Step 2: 验证**

```bash
touch cpq-backend/src/main/java/com/cpq/configure/resource/ConfigureSearchResource.java
sleep 7
curl -s -o /dev/null -w "%{http_code}\n" \
  "http://localhost:8081/api/cpq/quotations/configure/search-parts?q=AgCu&size=10"
```
Expected: 401

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/configure/resource/ConfigureSearchResource.java
git commit -m "feat(configure): ConfigureSearchResource (P1 料号搜索端点)"
```

---

**Phase 4 完成检查点**:后端 7 个文件 + 6 个 DTO + Service + 3 个 Resource 都注册;6 个端点全部 401。

---

## Phase 5:后端集成测试

### Task 24: ConfigureProductServiceTest — 8 个场景

**Files:**
- Create: `cpq-backend/src/test/java/com/cpq/configure/ConfigureProductServiceTest.java`

- [ ] **Step 1: 写集成测试(覆盖 spec §11.1 全部场景)**

```java
// cpq-backend/src/test/java/com/cpq/configure/ConfigureProductServiceTest.java
package com.cpq.configure;

import com.cpq.configure.dto.*;
import com.cpq.configure.service.ConfigureProductService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ConfigureProductServiceTest {

    @Inject ConfigureProductService service;
    @Inject EntityManager em;

    UUID newQuotationId() {
        // 创建测试用 quotation(简化:直接 INSERT 一个最小有效行)
        // 实施时按现有 quotation 表必填字段调整
        UUID id = UUID.randomUUID();
        // 占位:如有 quotation_service.createForTest 之类工具优先用
        return id;
    }

    UUID operatorId() { return UUID.randomUUID(); }

    // ── case 1: existing 路径(简单) ──
    @Test
    @Transactional
    void existing_returnsLineItem_noNewMatPart() {
        // 准备:确保 mat_part 至少有 1 个已知 part_no(seed 应有)
        String knownPn = (String) em.createNativeQuery(
                "SELECT part_no FROM mat_part LIMIT 1").getSingleResult();

        var req = new ConfigureProductRequest();
        req.productType = "SIMPLE";
        var pr = new PartRequest();
        pr.partMode = "existing";
        pr.existingHfPartNo = knownPn;
        pr.name = "测试";
        req.parts = List.of(pr);

        long before = countMatPart();
        var resp = service.configure(newQuotationId(), req, operatorId());

        assertEquals(1, resp.lineItems.size());
        assertEquals(before, countMatPart(), "existing 路径不应新增 mat_part");
    }

    // ── case 2: custom 未命中(新建) ──
    @Test
    @Transactional
    void custom_uncached_createsMatPartAndBom() {
        var req = makeSimpleCustom("AgNi90", List.of(
            elem("Ag", "90.0"), elem("Ni", "10.0")
        ), new BigDecimal("12.5"));

        long beforeMp = countMatPart();
        long beforeBom = countMatBom();
        long beforeProc = countMatProcess();

        var resp = service.configure(newQuotationId(), req, operatorId());
        assertEquals(1, resp.lineItems.size());
        assertFalse(resp.fingerprintMatched, "首次创建不应命中");
        assertEquals(beforeMp + 1, countMatPart(), "新增 1 行 mat_part");
        assertTrue(countMatBom() > beforeBom, "新增 mat_bom ELEMENT 行");
    }

    // ── case 3: custom 命中复用 ──
    @Test
    @Transactional
    void custom_cached_reusesHfPartNo() {
        // 第 1 次
        var req1 = makeSimpleCustom("AgNi90", List.of(
            elem("Ag", "92.0"), elem("Ni", "8.0")
        ), new BigDecimal("13.0"));
        var r1 = service.configure(newQuotationId(), req1, operatorId());
        String pn1 = (String) r1.lineItems.get(0).get("productPartNo");

        // 第 2 次:同配置
        var req2 = makeSimpleCustom("AgNi90", List.of(
            elem("Ag", "92.0"), elem("Ni", "8.0")
        ), new BigDecimal("99")); // 重量不参与指纹
        long before = countMatPart();
        var r2 = service.configure(newQuotationId(), req2, operatorId());

        assertEquals(before, countMatPart(), "命中不应新增 mat_part");
        assertEquals(pn1, r2.lineItems.get(0).get("productPartNo"));
        assertTrue(r2.fingerprintMatched);
        assertTrue(r2.reusedHfPartNos.contains(pn1));
    }

    // ── case 4: 元素含量和 ≠ 100 → 400 ──
    @Test
    void custom_sumNot100_throws() {
        var req = makeSimpleCustom("AgNi90", List.of(
            elem("Ag", "80.0"), elem("Ni", "10.0") // sum = 90 ≠ 100
        ), null);
        assertThrows(IllegalArgumentException.class,
            () -> service.configure(newQuotationId(), req, operatorId()));
    }

    // ── case 5: locked 元素被改 → 400 ──
    @Test
    void custom_lockedElementModified_throws() {
        var req = makeSimpleCustom("AgCu85", List.of(
            elem("Ag", "90.0"),   // AgCu85 是 locked, Ag 应为 85
            elem("Cu", "10.0")
        ), null);
        assertThrows(IllegalArgumentException.class,
            () -> service.configure(newQuotationId(), req, operatorId()));
    }

    // ── case 6: 组合产品全新 ──
    @Test
    @Transactional
    void composite_allNew_buildsParentAndChildrenAndAssemblyBom() {
        var p1 = makePartCustom("AgNi90", List.of(elem("Ag","90"), elem("Ni","10")), new BigDecimal("10"));
        p1.name = "配件1";
        var p2 = makePartCustom("AgCu90", List.of(elem("Ag","90"), elem("Cu","10")), new BigDecimal("11"));
        p2.name = "配件2";

        var req = new ConfigureProductRequest();
        req.productType = "COMPOSITE";
        req.parts = List.of(p1, p2);

        var cp = new CompositeProcessRequest();
        cp.defCode = "RIVET";
        cp.participatingPartIndexes = List.of(0, 1);
        cp.params = Map.of("pressure", 5.0, "height", 3.2);
        req.compositeProcesses = List.of(cp);

        long beforeMp = countMatPart();
        long beforeCp = countMatCompositeProcess();
        long beforeAsm = countMatBomAssembly();

        var resp = service.configure(newQuotationId(), req, operatorId());

        assertEquals(3, resp.lineItems.size(), "1 父 + 2 子 line_items");
        assertEquals(beforeMp + 3, countMatPart(), "3 个新 mat_part (1 父+2 子)");
        assertEquals(beforeAsm + 2, countMatBomAssembly(), "2 ASSEMBLY bom 行");
        assertEquals(beforeCp + 1, countMatCompositeProcess(), "1 组合工艺");
    }

    // ── case 7: 组合产品子复用 ──
    @Test
    @Transactional
    void composite_childrenReused_onlyParentCreated() {
        // 先单独建两个独立产品 → 各自落库
        var pn1 = createSimpleAndGetPn("AgNi90", List.of(elem("Ag","90"), elem("Ni","10")));
        var pn2 = createSimpleAndGetPn("AgCu90", List.of(elem("Ag","90"), elem("Cu","10")));

        // 再用同配置组装组合产品
        var p1 = makePartCustom("AgNi90", List.of(elem("Ag","90"), elem("Ni","10")), new BigDecimal("10"));
        var p2 = makePartCustom("AgCu90", List.of(elem("Ag","90"), elem("Cu","10")), new BigDecimal("11"));
        var req = new ConfigureProductRequest();
        req.productType = "COMPOSITE";
        req.parts = List.of(p1, p2);
        req.compositeProcesses = List.of();

        long beforeMp = countMatPart();
        var resp = service.configure(newQuotationId(), req, operatorId());

        // 仅父级新增
        assertEquals(beforeMp + 1, countMatPart(), "仅 1 个新 mat_part(父级)");
        assertTrue(resp.reusedHfPartNos.contains(pn1));
        assertTrue(resp.reusedHfPartNos.contains(pn2));
    }

    // ── case 8: 并发 10 个同配置 → 1 创建 + 9 复用 ──
    @Test
    void custom_concurrent10_onlyOneCreated() throws Exception {
        // 给个尚未存在的元素组合,确保第一个跑的是新建
        String uniqueMarker = "AgW72";  // editable, 用唯一 pct 组合避免和其他测试冲突
        var firstReq = makeSimpleCustom(uniqueMarker, List.of(
            elem("Ag", "73.45"), elem("W", "26.55")
        ), new BigDecimal("10"));

        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                var resp = service.configure(newQuotationId(), firstReq, operatorId());
                return (String) resp.lineItems.get(0).get("productPartNo");
            }));
        }
        start.countDown();

        Set<String> seen = new HashSet<>();
        for (Future<String> f : futures) seen.add(f.get(15, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(1, seen.size(), "10 个并发应产出同 1 个 part_no: " + seen);
    }

    // ── helpers ──
    PartRequest makePartCustom(String recipeCode, List<ElementOverride> elems, BigDecimal weight) {
        var p = new PartRequest();
        p.partMode = "custom";
        p.recipeCode = recipeCode;
        p.elements = elems;
        p.processIds = List.of();
        p.unitWeightGrams = weight;
        return p;
    }

    ConfigureProductRequest makeSimpleCustom(String recipeCode, List<ElementOverride> elems, BigDecimal weight) {
        var req = new ConfigureProductRequest();
        req.productType = "SIMPLE";
        req.parts = List.of(makePartCustom(recipeCode, elems, weight));
        return req;
    }

    ElementOverride elem(String code, String pct) {
        var e = new ElementOverride();
        e.elementCode = code;
        e.pct = new BigDecimal(pct);
        return e;
    }

    String createSimpleAndGetPn(String recipeCode, List<ElementOverride> elems) {
        var req = makeSimpleCustom(recipeCode, elems, new BigDecimal("10"));
        var resp = service.configure(newQuotationId(), req, operatorId());
        return (String) resp.lineItems.get(0).get("productPartNo");
    }

    long countMatPart() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM mat_part").getSingleResult()).longValue();
    }
    long countMatBom() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM mat_bom").getSingleResult()).longValue();
    }
    long countMatBomAssembly() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM mat_bom WHERE bom_type='ASSEMBLY'").getSingleResult()).longValue();
    }
    long countMatProcess() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM mat_process").getSingleResult()).longValue();
    }
    long countMatCompositeProcess() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM mat_composite_process").getSingleResult()).longValue();
    }
}
```

> **实施提示**:
> - `newQuotationId()` 占位返回随机 UUID — 实施者按现有 quotation 表必填字段补一个最小创建工具,或注入 `QuotationService.createForTest` 之类
> - 各 count 方法走的是当前事务读 — 若 @Transactional 隔离影响计数,改用嵌套事务或测试前 reset

- [ ] **Step 2: 跑测试**

```bash
cd cpq-backend && ./mvnw test -Dtest=ConfigureProductServiceTest -q
```
Expected: BUILD SUCCESS,8 tests passed

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/test/java/com/cpq/configure/ConfigureProductServiceTest.java
git commit -m "test(configure): ConfigureProductServiceTest 8 场景 (existing/未命中/命中/校验/组合/并发)"
```

---

**Phase 5 完成检查点**:后端 8 个测试全过,基础数据落库 + 复用 + 校验 + 并发 全部验证。Phase 6(前端服务层)在后续追加。

---

## Phase 6:前端服务层

### Task 25: configureProductService.ts

**Files:**
- Create: `cpq-frontend/src/services/configureProductService.ts`
- Create: `cpq-frontend/src/types/configure.ts`

- [ ] **Step 1: 写类型定义**

```typescript
// cpq-frontend/src/types/configure.ts

export type ProductType = 'SIMPLE' | 'COMPOSITE';
export type PartMode = 'existing' | 'custom';
export type CompositeType = 'SIMPLE' | 'COMPOSITE' | 'PART';

export interface ElementOverride {
  elementCode: string;
  pct: number;
}

export interface PartRequest {
  name: string;
  partMode: PartMode;
  existingHfPartNo?: string;
  recipeCode?: string;
  elements?: ElementOverride[];
  processIds?: string[];
  unitWeightGrams?: number;
}

export interface CompositeProcessRequest {
  defCode: string;
  participatingPartIndexes: number[];
  params: Record<string, any>;
}

export interface ConfigureProductRequest {
  productType: ProductType;
  parts: PartRequest[];
  compositeProcesses?: CompositeProcessRequest[];
}

export interface ConfigureProductResponse {
  lineItems: any[];   // 与 AddProductModal.onConfirm 同型,字段透传
  fingerprintMatched: boolean;
  reusedHfPartNos: string[];
}

export interface LookupFingerprintRequest {
  productType: ProductType;
  recipeCode?: string;
  elements?: ElementOverride[];
  childHfPartNos?: string[];
}

export interface LookupFingerprintResponse {
  matched: boolean;
  hfPartNo?: string;
  snapshot?: {
    unitWeightGrams?: number;
    processes: Array<{ processCode: string; seqNo: number; name?: string }>;
    compositeProcesses: Array<{ defCode: string; seqNo: number; participatingParts: string[]; paramValues: any }>;
  };
}

export interface SearchPartResult {
  hfPartNo: string;
  partName?: string;
  specification?: string;
  sizeInfo?: string;
  statusCode?: string;
  recipeId?: string;
  recipeCode?: string;
  recipeSymbol?: string;
  recipeName?: string;
  recipeSpec?: string;
  recipeType?: 'locked' | 'editable' | 'partial';
}
```

- [ ] **Step 2: 写服务**

```typescript
// cpq-frontend/src/services/configureProductService.ts
import api from './api';
import type {
  ConfigureProductRequest, ConfigureProductResponse,
  LookupFingerprintRequest, LookupFingerprintResponse,
  SearchPartResult,
} from '../types/configure';

export const configureProductService = {
  async searchParts(q: string, size = 50): Promise<SearchPartResult[]> {
    const res = await api.get('/quotations/configure/search-parts', { params: { q, size } });
    return res.data ?? [];
  },

  async lookupFingerprint(req: LookupFingerprintRequest): Promise<LookupFingerprintResponse> {
    const res = await api.post('/quotations/configure/lookup-fingerprint', req);
    return res.data;
  },

  async configureProduct(quotationId: string, req: ConfigureProductRequest): Promise<ConfigureProductResponse> {
    const res = await api.post(`/quotations/${quotationId}/configure-product`, req);
    return res.data;
  },
};
```

- [ ] **Step 3: tsc 自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
```
Expected: 0 错误

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/types/configure.ts cpq-frontend/src/services/configureProductService.ts
git commit -m "feat(configure): 前端 configure 类型 + configureProductService"
```

---

### Task 26: materialRecipeService.ts + compositeProcessService.ts

**Files:**
- Create: `cpq-frontend/src/services/materialRecipeService.ts`
- Create: `cpq-frontend/src/services/compositeProcessService.ts`

- [ ] **Step 1: materialRecipeService**

```typescript
// cpq-frontend/src/services/materialRecipeService.ts
import api from './api';

export interface MaterialRecipeLite {
  id: string;
  code: string;
  symbol: string;
  name: string;
  specLabel?: string;
  recipeType: 'locked' | 'editable' | 'partial';
}

export interface MaterialRecipeElement {
  elementCode: string;
  elementName: string;
  defaultPct: number;
  minPct?: number;
  maxPct?: number;
  isLocked: boolean;
  sortOrder: number;
}

export interface MaterialRecipeDetail extends MaterialRecipeLite {
  elements: MaterialRecipeElement[];
}

export const materialRecipeService = {
  async list(): Promise<MaterialRecipeLite[]> {
    const res = await api.get('/material-recipes');
    return res.data ?? [];
  },
  async detail(id: string): Promise<MaterialRecipeDetail> {
    const res = await api.get(`/material-recipes/${id}`);
    return res.data;
  },
};
```

- [ ] **Step 2: compositeProcessService**

```typescript
// cpq-frontend/src/services/compositeProcessService.ts
import api from './api';

export interface CompositeProcessParamDef {
  id: string;
  label: string;
  unit: string;
  type: 'number' | 'text';
  placeholder?: string;
}

export interface CompositeProcessDef {
  id: string;
  code: string;
  name: string;
  icon: string;
  description: string;
  paramSchema: CompositeProcessParamDef[];
  sortOrder: number;
}

export const compositeProcessService = {
  async list(): Promise<CompositeProcessDef[]> {
    const res = await api.get('/composite-processes');
    return res.data ?? [];
  },
};
```

- [ ] **Step 3: tsc 自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
```
Expected: 0 错误

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/services/materialRecipeService.ts \
        cpq-frontend/src/services/compositeProcessService.ts
git commit -m "feat(configure): materialRecipeService + compositeProcessService"
```

---

**Phase 6 完成检查点**:3 个前端服务 + 类型定义就绪,tsc 0 错误。

---

## Phase 7:前端 ConfigureProductDrawer + 6 个 step 组件

> **设计原则**:Drawer 主壳负责状态机 + 步骤切换 + 命中提示 Modal;每个 step 组件是受控组件,从 Drawer 拿当前 state,通过回调改 state。

### Task 27: ConfigureProductDrawer 主壳 + 状态机

**Files:**
- Create: `cpq-frontend/src/pages/quotation/ConfigureProductDrawer.tsx`

- [ ] **Step 1: 写主壳骨架**

```tsx
// cpq-frontend/src/pages/quotation/ConfigureProductDrawer.tsx
import React, { useState, useCallback } from 'react';
import { Drawer, Button, Steps, Modal, message } from 'antd';
import { configureProductService } from '../../services/configureProductService';
import type {
  ProductType, PartMode, PartRequest, CompositeProcessRequest,
  LookupFingerprintResponse,
} from '../../types/configure';
import Step0ProductType from './configure/Step0ProductType';
import Step1SearchPart from './configure/Step1SearchPart';
import Step2Material from './configure/Step2Material';
import Step3Process from './configure/Step3Process';
import Step4CompositeProcess from './configure/Step4CompositeProcess';
import Step5Summary from './configure/Step5Summary';

export interface PartState {
  name: string;
  partMode: PartMode | null;
  selectedHfPartNo: string | null;
  selectedRecipeCode: string | null;
  selectedRecipeSymbol: string | null;
  elementOverrides: { [code: string]: number };
  matLocked: boolean;
  processIds: string[];                // 选了哪些工序 id(按顺序)
  unitWeightGrams: number | null;
  // 命中复用时填(展示用)
  reusedFromExisting: { hfPartNo: string; snapshot: LookupFingerprintResponse['snapshot'] } | null;
}

export interface CompositeProcessAdded {
  defCode: string;
  participatingPartIndexes: number[];
  params: Record<string, any>;
}

interface Props {
  open: boolean;
  quotationId: string;
  onCancel: () => void;
  onConfirm: (lineItems: any[]) => void;
}

const ConfigureProductDrawer: React.FC<Props> = ({ open, quotationId, onCancel, onConfirm }) => {
  // ── state ──
  const [globalStep, setGlobalStep] = useState<0 | 1 | 2 | 3>(0);
  const [subStep, setSubStep] = useState<0 | 1 | 2>(0);
  const [productType, setProductType] = useState<ProductType>('SIMPLE');
  const [initPartCount, setInitPartCount] = useState(2);
  const [parts, setParts] = useState<PartState[]>([]);
  const [ci, setCi] = useState(0);
  const [addedCProcs, setAddedCProcs] = useState<CompositeProcessAdded[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const updateCurrentPart = useCallback((patch: Partial<PartState>) => {
    setParts(prev => prev.map((p, i) => i === ci ? { ...p, ...patch } : p));
  }, [ci]);

  const initParts = useCallback((type: ProductType, n: number) => {
    const count = type === 'COMPOSITE' ? n : 1;
    return Array.from({ length: count }, (_, i) => ({
      name: type === 'COMPOSITE' ? `配件 ${i + 1}` : '产品',
      partMode: null, selectedHfPartNo: null, selectedRecipeCode: null,
      selectedRecipeSymbol: null, elementOverrides: {}, matLocked: false,
      processIds: [], unitWeightGrams: null, reusedFromExisting: null,
    }) as PartState);
  }, []);

  const checkFingerprint = useCallback(async () => {
    const cur = parts[ci];
    if (cur.partMode !== 'custom' || !cur.selectedRecipeCode) return;
    const elements = Object.entries(cur.elementOverrides).map(([elementCode, pct]) => ({ elementCode, pct }));
    const resp = await configureProductService.lookupFingerprint({
      productType: 'SIMPLE',
      recipeCode: cur.selectedRecipeCode,
      elements,
    });
    if (resp.matched && resp.hfPartNo) {
      Modal.confirm({
        title: '⚠️ 已找到匹配料号',
        width: 500,
        content: (
          <div>
            <p>系统已存在配置完全相同的料号: <code>{resp.hfPartNo}</code></p>
            <p>将沿用以下属性:</p>
            <ul>
              <li>工序: {resp.snapshot?.processes.map(p => p.processCode).join(' → ') || '无'}</li>
              <li>单重: {resp.snapshot?.unitWeightGrams ?? '—'} g/件</li>
            </ul>
          </div>
        ),
        okText: '沿用 → 直接确认',
        cancelText: '返回修改材质',
        onOk: () => {
          updateCurrentPart({ reusedFromExisting: { hfPartNo: resp.hfPartNo!, snapshot: resp.snapshot } });
          // 跳过 P3,直接到 P5
          if (productType === 'COMPOSITE' && ci < parts.length - 1) {
            setCi(ci + 1); setSubStep(0);
          } else if (productType === 'COMPOSITE') {
            setGlobalStep(2);
          } else {
            setGlobalStep(3);
          }
        },
      });
      return true;
    }
    return false;
  }, [parts, ci, productType, updateCurrentPart]);

  const goNext = useCallback(async () => {
    if (globalStep === 0) {
      setParts(initParts(productType, initPartCount));
      setCi(0); setSubStep(0);
      setGlobalStep(1);
      return;
    }
    if (globalStep === 1) {
      if (subStep === 0) { setSubStep(1); return; }
      if (subStep === 1) {
        // P2 → P3 间查指纹
        const matched = await checkFingerprint();
        if (matched) return;  // 已跳到 P5
        setSubStep(2); return;
      }
      if (subStep === 2) {
        if (ci < parts.length - 1) { setCi(ci + 1); setSubStep(0); return; }
        if (productType === 'COMPOSITE') { setGlobalStep(2); return; }
        setGlobalStep(3); return;
      }
    }
    if (globalStep === 2) { setGlobalStep(3); return; }
    if (globalStep === 3) { await submitConfigure(); }
  }, [globalStep, subStep, productType, parts, ci, initPartCount, checkFingerprint, initParts]);

  const goPrev = useCallback(() => {
    if (globalStep === 3) {
      if (productType === 'COMPOSITE') setGlobalStep(2);
      else { setSubStep(2); setCi(0); setGlobalStep(1); }
      return;
    }
    if (globalStep === 2) {
      setCi(parts.length - 1); setSubStep(2); setGlobalStep(1); return;
    }
    if (globalStep === 1) {
      if (subStep > 0) { setSubStep((subStep - 1) as 0 | 1); return; }
      if (ci > 0) { setCi(ci - 1); setSubStep(2); return; }
      setGlobalStep(0);
    }
  }, [globalStep, subStep, ci, parts.length, productType]);

  const submitConfigure = async () => {
    setSubmitting(true);
    try {
      const partsReq: PartRequest[] = parts.map(p => ({
        name: p.name,
        partMode: p.partMode!,
        existingHfPartNo: p.partMode === 'existing' ? p.selectedHfPartNo! : undefined,
        recipeCode: p.partMode === 'custom' ? p.selectedRecipeCode! : undefined,
        elements: p.partMode === 'custom'
          ? Object.entries(p.elementOverrides).map(([elementCode, pct]) => ({ elementCode, pct }))
          : undefined,
        processIds: p.partMode === 'custom' && !p.reusedFromExisting ? p.processIds : undefined,
        unitWeightGrams: p.partMode === 'custom' && !p.reusedFromExisting ? p.unitWeightGrams ?? undefined : undefined,
      }));
      const compProcs: CompositeProcessRequest[] = addedCProcs.map(a => ({
        defCode: a.defCode,
        participatingPartIndexes: a.participatingPartIndexes,
        params: a.params,
      }));
      const resp = await configureProductService.configureProduct(quotationId, {
        productType, parts: partsReq, compositeProcesses: productType === 'COMPOSITE' ? compProcs : undefined,
      });
      if (resp.fingerprintMatched) message.success(`已复用 ${resp.reusedHfPartNos.length} 个料号`);
      else message.success('选配成功');
      onConfirm(resp.lineItems);
      // 关闭后清理
      resetState();
    } catch (e: any) {
      message.error(e?.response?.data?.message ?? '选配失败');
    } finally {
      setSubmitting(false);
    }
  };

  const resetState = () => {
    setGlobalStep(0); setSubStep(0); setProductType('SIMPLE');
    setInitPartCount(2); setParts([]); setCi(0); setAddedCProcs([]);
  };

  // ── render ──
  const stepLabels = productType === 'COMPOSITE'
    ? ['产品类型', `配件选配 ×${parts.length || initPartCount}`, '组合工艺', '完成选配']
    : ['产品类型', '料号匹配', '材质选配', '工序选择', '完成选配'];
  const activeIdx = productType === 'COMPOSITE'
    ? (globalStep === 0 ? 0 : globalStep === 1 ? 1 : globalStep === 2 ? 2 : 3)
    : (globalStep === 0 ? 0 : globalStep === 3 ? 4 : subStep + 1);

  return (
    <Drawer
      title="添加产品 — 选配"
      open={open}
      onClose={() => { resetState(); onCancel(); }}
      width={960}
      placement="right"
      maskClosable={false}
      keyboard={false}
      footer={
        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <Button onClick={() => { resetState(); onCancel(); }}>取消</Button>
          <div>
            {globalStep > 0 && <Button onClick={goPrev} style={{ marginRight: 8 }}>上一步</Button>}
            <Button type="primary" onClick={goNext} loading={submitting}>
              {globalStep === 3 ? '确认添加' : '下一步'}
            </Button>
          </div>
        </div>
      }
    >
      <Steps current={activeIdx} items={stepLabels.map(l => ({ title: l }))} style={{ marginBottom: 24 }} />

      {globalStep === 0 && (
        <Step0ProductType
          productType={productType}
          onChangeType={setProductType}
          initPartCount={initPartCount}
          onChangePartCount={setInitPartCount}
        />
      )}
      {globalStep === 1 && parts[ci] && (
        <>
          {subStep === 0 && <Step1SearchPart part={parts[ci]} onUpdate={updateCurrentPart} />}
          {subStep === 1 && <Step2Material part={parts[ci]} onUpdate={updateCurrentPart} />}
          {subStep === 2 && <Step3Process part={parts[ci]} onUpdate={updateCurrentPart} />}
        </>
      )}
      {globalStep === 2 && (
        <Step4CompositeProcess
          parts={parts}
          addedCProcs={addedCProcs}
          onChangeAdded={setAddedCProcs}
        />
      )}
      {globalStep === 3 && (
        <Step5Summary
          productType={productType}
          parts={parts}
          addedCProcs={addedCProcs}
          onUpdatePart={(idx, patch) => setParts(prev => prev.map((p, i) => i === idx ? { ...p, ...patch } : p))}
        />
      )}
    </Drawer>
  );
};

export default ConfigureProductDrawer;
```

- [ ] **Step 2: tsc 自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
```
Expected: 0 错误(此时会报 6 个 step 组件未实现 — 在 Task 28-33 补)。先继续 Task 28 再回头看。

> **注**:tsc 会报 step 组件缺失;创建 6 个空 step 占位符避免编译失败。每个 step 文件先放最小骨架,Task 28-33 再填内容。

- [ ] **Step 3: 创建 6 个 step 占位符(占位 export 让 tsc 通过)**

```bash
mkdir -p cpq-frontend/src/pages/quotation/configure
```

```tsx
// Step0ProductType.tsx 占位
import React from 'react';
const Step0ProductType: React.FC<any> = () => <div>Step0 placeholder</div>;
export default Step0ProductType;
```

同样写 Step1SearchPart / Step2Material / Step3Process / Step4CompositeProcess / Step5Summary 5 个占位符。

- [ ] **Step 4: tsc 通过**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
```
Expected: 0 错误

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/ConfigureProductDrawer.tsx \
        cpq-frontend/src/pages/quotation/configure/
git commit -m "feat(configure): ConfigureProductDrawer 主壳 + 状态机 + 6 step 占位符"
```

---

### Task 28: Step0ProductType — 独立/组合选择

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/configure/Step0ProductType.tsx`

- [ ] **Step 1: 实现**

```tsx
// cpq-frontend/src/pages/quotation/configure/Step0ProductType.tsx
import React from 'react';
import { Card, Radio, InputNumber, Space, Typography } from 'antd';
import type { ProductType } from '../../../types/configure';

interface Props {
  productType: ProductType;
  onChangeType: (t: ProductType) => void;
  initPartCount: number;
  onChangePartCount: (n: number) => void;
}

const Step0ProductType: React.FC<Props> = ({ productType, onChangeType, initPartCount, onChangePartCount }) => (
  <div>
    <Typography.Title level={5}>请选择产品类型</Typography.Title>
    <Radio.Group value={productType} onChange={(e) => onChangeType(e.target.value)} style={{ width: '100%' }}>
      <Space direction="vertical" style={{ width: '100%' }}>
        <Radio.Button value="SIMPLE" style={{ width: '100%', height: 80, padding: 16 }}>
          <div><span style={{ fontSize: 24 }}>🔩</span> 独立产品 — 单一零件,完成料号 → 材质 → 工序选配</div>
        </Radio.Button>
        <Radio.Button value="COMPOSITE" style={{ width: '100%', height: 80, padding: 16 }}>
          <div><span style={{ fontSize: 24 }}>🔧</span> 组合产品 — 多配件各自选配,再进行组合工艺(如铆接、焊接)</div>
        </Radio.Button>
      </Space>
    </Radio.Group>

    {productType === 'COMPOSITE' && (
      <Card size="small" style={{ marginTop: 16, background: '#fafafe' }}>
        <Space>
          <span>配件数量:</span>
          <InputNumber min={2} max={8} value={initPartCount} onChange={(v) => onChangePartCount(v ?? 2)} />
          <Typography.Text type="secondary">个配件(最少 2,最多 8)</Typography.Text>
        </Space>
      </Card>
    )}
  </div>
);

export default Step0ProductType;
```

- [ ] **Step 2: tsc + Vite 自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/configure/Step0ProductType.tsx
```
Expected: 0 错误,200

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/quotation/configure/Step0ProductType.tsx
git commit -m "feat(configure): Step0ProductType 独立/组合选择 + 配件数量"
```

---

### Task 29: Step1SearchPart — 料号搜索

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/configure/Step1SearchPart.tsx`

- [ ] **Step 1: 实现**

```tsx
// cpq-frontend/src/pages/quotation/configure/Step1SearchPart.tsx
import React, { useState, useEffect } from 'react';
import { Input, List, Tag, Card, Empty, Spin } from 'antd';
import { SearchOutlined, PlusCircleOutlined } from '@ant-design/icons';
import { configureProductService } from '../../../services/configureProductService';
import type { SearchPartResult } from '../../../types/configure';
import type { PartState } from '../ConfigureProductDrawer';

interface Props {
  part: PartState;
  onUpdate: (patch: Partial<PartState>) => void;
}

const Step1SearchPart: React.FC<Props> = ({ part, onUpdate }) => {
  const [q, setQ] = useState('');
  const [results, setResults] = useState<SearchPartResult[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!q.trim()) { setResults([]); return; }
    setLoading(true);
    const t = setTimeout(() => {
      configureProductService.searchParts(q, 50)
        .then(setResults)
        .finally(() => setLoading(false));
    }, 300);
    return () => clearTimeout(t);
  }, [q]);

  const selectExisting = (r: SearchPartResult) => {
    onUpdate({
      partMode: 'existing',
      selectedHfPartNo: r.hfPartNo,
      selectedRecipeCode: r.recipeCode ?? null,
      selectedRecipeSymbol: r.recipeSymbol ?? null,
      matLocked: true,
    });
  };

  const selectNone = () => {
    onUpdate({
      partMode: 'custom',
      selectedHfPartNo: null,
      selectedRecipeCode: null,
      selectedRecipeSymbol: null,
      matLocked: false,
    });
  };

  return (
    <div>
      <Input
        size="large"
        prefix={<SearchOutlined />}
        placeholder="输入料号、材质、规格或尺寸…"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        allowClear
      />
      <div style={{ marginTop: 8, color: '#888', fontSize: 12 }}>
        匹配 <b>{results.length}</b> 条结果
      </div>

      <Spin spinning={loading}>
        <List
          style={{ marginTop: 12, maxHeight: 360, overflow: 'auto' }}
          dataSource={results}
          locale={{ emptyText: q.trim() ? <Empty description="未找到匹配料号" /> : <div style={{ padding: 24, color: '#bbb' }}>输入关键词开始搜索</div> }}
          renderItem={(r) => (
            <List.Item
              onClick={() => selectExisting(r)}
              style={{
                cursor: 'pointer',
                padding: 12,
                background: part.selectedHfPartNo === r.hfPartNo ? '#f0effe' : undefined,
                border: '0.5px solid ' + (part.selectedHfPartNo === r.hfPartNo ? '#5c6bc0' : '#eee'),
                borderRadius: 8,
                marginBottom: 4,
              }}
            >
              <List.Item.Meta
                title={<b>{r.hfPartNo}</b>}
                description={
                  <div>
                    {r.recipeSymbol} {r.recipeName} · {r.specification} · {r.sizeInfo}{' '}
                    <Tag color={r.statusCode === 'Y' ? 'green' : 'red'}>{r.statusCode === 'Y' ? '在产' : '停产'}</Tag>
                  </div>
                }
              />
            </List.Item>
          )}
        />
      </Spin>

      <Card
        size="small"
        onClick={selectNone}
        style={{
          marginTop: 12,
          cursor: 'pointer',
          border: '1.5px dashed ' + (part.partMode === 'custom' ? '#5c6bc0' : '#c5cae9'),
          background: part.partMode === 'custom' ? '#f0effe' : undefined,
          color: '#5c6bc0',
        }}
      >
        <PlusCircleOutlined /> 无匹配料号,进入自定义材质选配
      </Card>
    </div>
  );
};

export default Step1SearchPart;
```

- [ ] **Step 2: 自检 + Commit**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/configure/Step1SearchPart.tsx
git add cpq-frontend/src/pages/quotation/configure/Step1SearchPart.tsx
git commit -m "feat(configure): Step1SearchPart 料号搜索 + '无匹配' 入口"
```
Expected: 0 错误,200

---

### Task 30: Step2Material — 材质 + 元素含量

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/configure/Step2Material.tsx`

- [ ] **Step 1: 实现**

```tsx
// cpq-frontend/src/pages/quotation/configure/Step2Material.tsx
import React, { useState, useEffect } from 'react';
import { Input, List, Tag, InputNumber, Alert, Spin, Empty } from 'antd';
import { SearchOutlined, LockOutlined } from '@ant-design/icons';
import { materialRecipeService, type MaterialRecipeLite, type MaterialRecipeDetail } from '../../../services/materialRecipeService';
import type { PartState } from '../ConfigureProductDrawer';

interface Props {
  part: PartState;
  onUpdate: (patch: Partial<PartState>) => void;
}

const typeBadge: Record<string, { label: string; color: string }> = {
  locked:   { label: '标准锁定', color: 'red' },
  editable: { label: '含量可调', color: 'green' },
  partial:  { label: '部分可调', color: 'orange' },
};

const Step2Material: React.FC<Props> = ({ part, onUpdate }) => {
  const [recipes, setRecipes] = useState<MaterialRecipeLite[]>([]);
  const [detail, setDetail] = useState<MaterialRecipeDetail | null>(null);
  const [q, setQ] = useState('');
  const [loading, setLoading] = useState(false);

  // 加载材质列表(自定义路径)
  useEffect(() => {
    if (!part.matLocked) {
      materialRecipeService.list().then(setRecipes);
    }
  }, [part.matLocked]);

  // 自动选中已锁定的料号材质
  useEffect(() => {
    if (part.matLocked && part.selectedRecipeCode) {
      const r = recipes.find(x => x.code === part.selectedRecipeCode);
      if (r) loadDetail(r.id);
    } else if (part.selectedRecipeCode) {
      const r = recipes.find(x => x.code === part.selectedRecipeCode);
      if (r) loadDetail(r.id);
    }
  }, [part.matLocked, part.selectedRecipeCode, recipes]);

  const loadDetail = async (id: string) => {
    setLoading(true);
    try {
      const d = await materialRecipeService.detail(id);
      setDetail(d);
      // 初始化 elementOverrides 为默认值
      if (!Object.keys(part.elementOverrides).length) {
        const ov: { [k: string]: number } = {};
        d.elements.forEach(e => { ov[e.elementCode] = Number(e.defaultPct); });
        onUpdate({ elementOverrides: ov });
      }
    } finally { setLoading(false); }
  };

  const selectRecipe = (r: MaterialRecipeLite) => {
    onUpdate({ selectedRecipeCode: r.code, selectedRecipeSymbol: r.symbol, elementOverrides: {} });
    loadDetail(r.id);
  };

  const setElem = (code: string, pct: number) => {
    onUpdate({ elementOverrides: { ...part.elementOverrides, [code]: pct } });
  };

  const filtered = recipes.filter(r =>
    !q.trim() || r.symbol.toLowerCase().includes(q.toLowerCase()) ||
    r.name.includes(q) || (r.specLabel ?? '').includes(q)
  );

  const sumPct = Object.values(part.elementOverrides).reduce((a, b) => a + (b || 0), 0);
  const sumOk = Math.abs(sumPct - 100) < 0.01;

  return (
    <div style={{ display: 'flex', gap: 16, height: 480 }}>
      {/* 左:材质库 */}
      <div style={{ width: 280, borderRight: '0.5px solid #eee', paddingRight: 12, display: 'flex', flexDirection: 'column' }}>
        {!part.matLocked && (
          <Input prefix={<SearchOutlined />} placeholder="化学式 / 名称…"
                 value={q} onChange={(e) => setQ(e.target.value)} style={{ marginBottom: 8 }} />
        )}
        <List
          dataSource={part.matLocked ? recipes.filter(r => r.code === part.selectedRecipeCode) : filtered}
          locale={{ emptyText: <Empty description="无匹配材质" /> }}
          renderItem={(r) => {
            const isSel = r.code === part.selectedRecipeCode;
            return (
              <List.Item
                onClick={() => !part.matLocked && selectRecipe(r)}
                style={{
                  cursor: part.matLocked ? 'not-allowed' : 'pointer',
                  background: isSel ? '#f0effe' : undefined,
                  padding: 8, borderRadius: 6,
                }}
              >
                <List.Item.Meta
                  title={<><b>{r.symbol}</b> {r.name}</>}
                  description={r.specLabel}
                />
                <Tag color={typeBadge[r.recipeType].color}>{typeBadge[r.recipeType].label}</Tag>
              </List.Item>
            );
          }}
        />
      </div>

      {/* 右:元素详情 */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <Spin spinning={loading}>
          {!detail ? (
            <Empty description="从左侧选择材质" />
          ) : (
            <>
              <h3>{detail.symbol} {detail.name}</h3>
              <div style={{ color: '#888', marginBottom: 12 }}>配比 {detail.specLabel}</div>

              {part.matLocked && (
                <Alert
                  icon={<LockOutlined />} type="warning" showIcon
                  message="料号已绑定该材质,元素含量锁定"
                  style={{ marginBottom: 12 }}
                />
              )}

              <List
                dataSource={detail.elements}
                renderItem={(e) => {
                  const canEdit = !part.matLocked && !e.isLocked;
                  const v = part.elementOverrides[e.elementCode] ?? Number(e.defaultPct);
                  return (
                    <List.Item>
                      <List.Item.Meta
                        avatar={<Tag color="purple">{e.elementCode}</Tag>}
                        title={<>{e.elementName}{canEdit && ` (${e.minPct}–${e.maxPct}%)`}</>}
                      />
                      {canEdit ? (
                        <InputNumber
                          value={v}
                          min={Number(e.minPct)}
                          max={Number(e.maxPct)}
                          step={0.1}
                          onChange={(n) => setElem(e.elementCode, n ?? 0)}
                          addonAfter="%"
                        />
                      ) : (
                        <span style={{ color: '#999' }}>{v}% <LockOutlined /></span>
                      )}
                    </List.Item>
                  );
                }}
              />

              {!part.matLocked && (
                <Alert
                  style={{ marginTop: 12 }}
                  type={sumOk ? 'success' : 'warning'}
                  showIcon
                  message={sumOk
                    ? `含量之和 ${sumPct.toFixed(1)}%,配比正确`
                    : `含量之和 ${sumPct.toFixed(1)}%,建议调整至 100`}
                />
              )}
            </>
          )}
        </Spin>
      </div>
    </div>
  );
};

export default Step2Material;
```

- [ ] **Step 2: 自检 + Commit**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/configure/Step2Material.tsx
git add cpq-frontend/src/pages/quotation/configure/Step2Material.tsx
git commit -m "feat(configure): Step2Material 材质 + 元素含量编辑(锁定/可调/部分可调)"
```
Expected: 0 错误,200

---

### Task 31: Step3Process — 工序选择

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/configure/Step3Process.tsx`

- [ ] **Step 1: 实现**

```tsx
// cpq-frontend/src/pages/quotation/configure/Step3Process.tsx
import React, { useState, useEffect } from 'react';
import { Input, List, Tag, Button, Empty } from 'antd';
import { SearchOutlined, PlusOutlined, CloseOutlined } from '@ant-design/icons';
import api from '../../../services/api';
import type { PartState } from '../ConfigureProductDrawer';

interface Process { id: string; code: string; name: string; categoryName?: string; }

interface Props {
  part: PartState;
  onUpdate: (patch: Partial<PartState>) => void;
}

const Step3Process: React.FC<Props> = ({ part, onUpdate }) => {
  const [allProcs, setAllProcs] = useState<Process[]>([]);
  const [q, setQ] = useState('');

  useEffect(() => {
    api.get('/processes', { params: { status: 'ACTIVE', size: 200 } })
      .then((res: any) => setAllProcs(res.data ?? []))
      .catch(() => setAllProcs([]));
  }, []);

  const filtered = allProcs.filter(p =>
    !q.trim() || p.name.includes(q) || (p.categoryName ?? '').includes(q)
  );

  const isAdded = (id: string) => part.processIds.includes(id);
  const toggle = (id: string) => {
    const next = isAdded(id) ? part.processIds.filter(x => x !== id) : [...part.processIds, id];
    onUpdate({ processIds: next });
  };

  const addedDetailed = part.processIds.map(id => allProcs.find(p => p.id === id)).filter(Boolean) as Process[];

  return (
    <div style={{ display: 'flex', gap: 16, height: 480 }}>
      {/* 左:工序库 */}
      <div style={{ width: 280, borderRight: '0.5px solid #eee', paddingRight: 12 }}>
        <Input prefix={<SearchOutlined />} placeholder="搜索工序…" value={q}
               onChange={(e) => setQ(e.target.value)} style={{ marginBottom: 8 }} />
        <List
          dataSource={filtered}
          locale={{ emptyText: <Empty description="无匹配工序" /> }}
          renderItem={(p) => (
            <List.Item style={{ background: isAdded(p.id) ? '#f0effe' : undefined, padding: 8, borderRadius: 6 }}>
              <List.Item.Meta
                title={p.name}
                description={<Tag>{p.categoryName ?? '—'}</Tag>}
              />
              <Button size="small" type={isAdded(p.id) ? 'default' : 'primary'} icon={<PlusOutlined />}
                      onClick={() => toggle(p.id)}>
                {isAdded(p.id) ? '已添加' : '添加'}
              </Button>
            </List.Item>
          )}
        />
      </div>

      {/* 右:已选 */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <h3>已选工序 ({addedDetailed.length})</h3>
        <div style={{ color: '#aaa', fontSize: 12, marginBottom: 12 }}>按添加顺序</div>
        <List
          dataSource={addedDetailed}
          locale={{ emptyText: <Empty description="从左侧添加工序" /> }}
          renderItem={(p, i) => (
            <List.Item style={{ background: '#f8f7ff', padding: 8, borderRadius: 6, marginBottom: 4 }}>
              <List.Item.Meta
                avatar={<Tag color="purple">{i + 1}</Tag>}
                title={p.name}
                description={<Tag>{p.categoryName ?? '—'}</Tag>}
              />
              <Button type="text" icon={<CloseOutlined />} onClick={() => toggle(p.id)} />
            </List.Item>
          )}
        />
      </div>
    </div>
  );
};

export default Step3Process;
```

- [ ] **Step 2: 自检 + Commit**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/configure/Step3Process.tsx
git add cpq-frontend/src/pages/quotation/configure/Step3Process.tsx
git commit -m "feat(configure): Step3Process 工序选择(双栏 + 顺序)"
```

---

### Task 32: Step4CompositeProcess — 组合工艺(参数表单 + 配件子集 chip)

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/configure/Step4CompositeProcess.tsx`

- [ ] **Step 1: 实现**

```tsx
// cpq-frontend/src/pages/quotation/configure/Step4CompositeProcess.tsx
import React, { useState, useEffect } from 'react';
import { Card, Button, Tag, Input, InputNumber, Empty, Tooltip } from 'antd';
import { compositeProcessService, type CompositeProcessDef } from '../../../services/compositeProcessService';
import type { PartState, CompositeProcessAdded } from '../ConfigureProductDrawer';

interface Props {
  parts: PartState[];
  addedCProcs: CompositeProcessAdded[];
  onChangeAdded: (next: CompositeProcessAdded[]) => void;
}

const Step4CompositeProcess: React.FC<Props> = ({ parts, addedCProcs, onChangeAdded }) => {
  const [defs, setDefs] = useState<CompositeProcessDef[]>([]);
  useEffect(() => { compositeProcessService.list().then(setDefs); }, []);

  const isAdded = (code: string) => addedCProcs.find(a => a.defCode === code) != null;
  const add = (def: CompositeProcessDef) => {
    if (isAdded(def.code)) return;
    const all = parts.map((_, i) => i);
    onChangeAdded([...addedCProcs, { defCode: def.code, participatingPartIndexes: all, params: {} }]);
  };
  const remove = (code: string) => onChangeAdded(addedCProcs.filter(a => a.defCode !== code));
  const togglePart = (defCode: string, partIdx: number) => {
    onChangeAdded(addedCProcs.map(a => {
      if (a.defCode !== defCode) return a;
      const has = a.participatingPartIndexes.includes(partIdx);
      const next = has ? a.participatingPartIndexes.filter(x => x !== partIdx) : [...a.participatingPartIndexes, partIdx];
      if (next.length < 2 && has) return a;  // 至少 2 个
      return { ...a, participatingPartIndexes: next };
    }));
  };
  const setParam = (defCode: string, key: string, val: any) => {
    onChangeAdded(addedCProcs.map(a => a.defCode === defCode ? { ...a, params: { ...a.params, [key]: val } } : a));
  };

  return (
    <div style={{ display: 'flex', gap: 16, height: 480 }}>
      {/* 左:工艺库 */}
      <div style={{ width: 280, borderRight: '0.5px solid #eee', paddingRight: 12, overflow: 'auto' }}>
        <h4>组合工艺库</h4>
        {defs.map(def => (
          <Card key={def.id} size="small"
                style={{ marginBottom: 8, cursor: isAdded(def.code) ? 'not-allowed' : 'pointer' }}
                onClick={() => add(def)}>
            <div><span style={{ fontSize: 18 }}>{def.icon}</span> <b>{def.name}</b></div>
            <div style={{ color: '#888', fontSize: 11 }}>{def.description}</div>
            {isAdded(def.code) && <Tag color="green">已添加</Tag>}
          </Card>
        ))}
      </div>

      {/* 右:已选组合工艺 */}
      <div style={{ flex: 1, overflow: 'auto' }}>
        <h3>已选组合工艺 ({addedCProcs.length})</h3>
        <div style={{ color: '#aaa', fontSize: 12, marginBottom: 12 }}>每项工艺需指定参与配件</div>
        {addedCProcs.length === 0 ? (
          <Empty description="从左侧选择组合工艺" />
        ) : addedCProcs.map(a => {
          const def = defs.find(d => d.code === a.defCode);
          if (!def) return null;
          return (
            <Card key={a.defCode} size="small" style={{ marginBottom: 12, background: '#f9f8ff' }}
                  title={<><span style={{ fontSize: 16 }}>{def.icon}</span> {def.name}</>}
                  extra={<Button type="text" danger onClick={() => remove(a.defCode)}>移除</Button>}>
              <div style={{ marginBottom: 8 }}>
                <div style={{ fontSize: 11, color: '#888' }}>参与配件(点击切换,至少 2 个)</div>
                <div style={{ marginTop: 6 }}>
                  {parts.map((p, pi) => {
                    const sel = a.participatingPartIndexes.includes(pi);
                    return (
                      <Tag.CheckableTag key={pi} checked={sel}
                                        onChange={() => togglePart(a.defCode, pi)}>
                        {p.name}
                      </Tag.CheckableTag>
                    );
                  })}
                </div>
              </div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {def.paramSchema.map(pm => (
                  <div key={pm.id} style={{ flex: 1, minWidth: 120 }}>
                    <div style={{ fontSize: 11, color: '#888', marginBottom: 4 }}>
                      {pm.label}{pm.unit ? ` (${pm.unit})` : ''}
                    </div>
                    {pm.type === 'number' ? (
                      <InputNumber style={{ width: '100%' }} placeholder={pm.placeholder}
                                   value={a.params[pm.id]}
                                   onChange={(v) => setParam(a.defCode, pm.id, v)} />
                    ) : (
                      <Input placeholder={pm.placeholder}
                             value={a.params[pm.id] ?? ''}
                             onChange={(e) => setParam(a.defCode, pm.id, e.target.value)} />
                    )}
                  </div>
                ))}
              </div>
            </Card>
          );
        })}
      </div>
    </div>
  );
};

export default Step4CompositeProcess;
```

- [ ] **Step 2: 自检 + Commit**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/configure/Step4CompositeProcess.tsx
git add cpq-frontend/src/pages/quotation/configure/Step4CompositeProcess.tsx
git commit -m "feat(configure): Step4CompositeProcess 组合工艺(参数表单 + 配件子集 chip)"
```

---

### Task 33: Step5Summary — 确认页(含可选单重输入)

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/configure/Step5Summary.tsx`

- [ ] **Step 1: 实现**

```tsx
// cpq-frontend/src/pages/quotation/configure/Step5Summary.tsx
import React from 'react';
import { Card, Tag, InputNumber, Descriptions, Alert } from 'antd';
import { CheckCircleFilled } from '@ant-design/icons';
import type { ProductType } from '../../../types/configure';
import type { PartState, CompositeProcessAdded } from '../ConfigureProductDrawer';

interface Props {
  productType: ProductType;
  parts: PartState[];
  addedCProcs: CompositeProcessAdded[];
  onUpdatePart: (idx: number, patch: Partial<PartState>) => void;
}

const Step5Summary: React.FC<Props> = ({ productType, parts, addedCProcs, onUpdatePart }) => (
  <div>
    <div style={{ textAlign: 'center', marginBottom: 20 }}>
      <CheckCircleFilled style={{ fontSize: 36, color: '#52c41a' }} />
      <h3 style={{ marginTop: 8 }}>选配完成</h3>
      <p style={{ color: '#888' }}>请核对以下信息,确认后点击「确认添加」</p>
    </div>

    <Card title="产品类型" size="small" style={{ marginBottom: 12 }}>
      <span style={{ fontSize: 18 }}>{productType === 'COMPOSITE' ? '🔧' : '🔩'}</span>
      <b style={{ marginLeft: 8 }}>{productType === 'COMPOSITE' ? '组合产品' : '独立产品'}</b>
      <span style={{ color: '#888', marginLeft: 12 }}>
        {productType === 'COMPOSITE' ? `共 ${parts.length} 个配件` : '单一零件'}
      </span>
    </Card>

    <Card title="配件明细" size="small" style={{ marginBottom: 12 }}>
      {parts.map((p, i) => {
        const reused = p.reusedFromExisting;
        return (
          <Card key={i} size="small" style={{ marginBottom: 8 }}
                title={<><Tag color="purple">{i + 1}</Tag> {p.name}</>}>
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="料号">
                {p.partMode === 'existing' ? (
                  <b style={{ color: '#5c6bc0' }}>{p.selectedHfPartNo}</b>
                ) : reused ? (
                  <><b style={{ color: '#5c6bc0' }}>{reused.hfPartNo}</b> <Tag color="green">复用现有</Tag></>
                ) : (
                  <Tag color="orange">自定义(将新建)</Tag>
                )}
              </Descriptions.Item>
              <Descriptions.Item label="材质">{p.selectedRecipeCode ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="工序">
                {reused
                  ? reused.snapshot?.processes.map(x => x.processCode).join(' → ') || '无'
                  : (p.processIds.length ? `${p.processIds.length} 项工序` : '无')
                }
              </Descriptions.Item>
              <Descriptions.Item label="单重">
                {p.partMode === 'existing' || reused ? (
                  // 只读:展示已有
                  <span>{reused?.snapshot?.unitWeightGrams ?? '—'} g/件 <Tag>只读</Tag></span>
                ) : (
                  // 自定义未命中:可填
                  <InputNumber
                    placeholder="g/件(可选)"
                    value={p.unitWeightGrams ?? undefined}
                    onChange={(v) => onUpdatePart(i, { unitWeightGrams: v ?? null })}
                    min={0}
                    step={0.1}
                  />
                )}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        );
      })}
    </Card>

    {productType === 'COMPOSITE' && addedCProcs.length > 0 && (
      <Card title="组合工艺" size="small">
        {addedCProcs.map((a, i) => (
          <div key={i} style={{ borderBottom: '0.5px solid #f0f0f0', padding: '6px 0' }}>
            <b>{a.defCode}</b>
            <span style={{ marginLeft: 8, color: '#888' }}>
              参与配件: {a.participatingPartIndexes.map(idx => parts[idx]?.name).join(' + ')}
            </span>
            <div style={{ fontSize: 11, color: '#aaa', marginTop: 2 }}>
              {Object.entries(a.params).map(([k, v]) => `${k}: ${v}`).join('; ') || '(未填参数)'}
            </div>
          </div>
        ))}
      </Card>
    )}
  </div>
);

export default Step5Summary;
```

- [ ] **Step 2: 自检 + Commit**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/configure/Step5Summary.tsx
git add cpq-frontend/src/pages/quotation/configure/Step5Summary.tsx
git commit -m "feat(configure): Step5Summary 确认页(命中只读/未命中填单重)"
```

---

**Phase 7 完成检查点**:ConfigureProductDrawer 主壳 + 6 个 step 组件全部就位,Drawer 内部交互完整。

---

## Phase 8:入口改造(Wizard Step1 + Step2 Dropdown)

### Task 34: QuotationStep2.tsx — 添加产品 Dropdown 入口

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` (改"添加产品"按钮)
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` (引入 ConfigureProductDrawer)

- [ ] **Step 1: 在 QuotationWizard.tsx 引入 ConfigureProductDrawer**

定位 QuotationWizard.tsx 中已有的 `<AddProductModal ... />` 位置(约 1011-1018 行),保留原 modal,在其后新增:

```tsx
import ConfigureProductDrawer from './ConfigureProductDrawer';

// 在 state 区追加
const [configureDrawerOpen, setConfigureDrawerOpen] = useState(false);

// 在 renderStep2 内,AddProductModal 之后,追加:
<ConfigureProductDrawer
  open={configureDrawerOpen}
  quotationId={quotationId || ''}
  onCancel={() => setConfigureDrawerOpen(false)}
  onConfirm={(lineItems) => {
    // lineItems 是 N+1 个对象(组合产品)或 1 个(独立产品)
    setLineItems(prev => [...prev, ...lineItems]);
    setConfigureDrawerOpen(false);
  }}
/>
```

同时在传 QuotationStep2 的 props 中增加 `onAddConfigured`:

```tsx
<QuotationStep2
  ...
  onAddProduct={() => setAddProductModalOpen(true)}
  onAddConfigured={() => setConfigureDrawerOpen(true)}  // 新增
  ...
/>
```

- [ ] **Step 2: 改 QuotationStep2.tsx 把"添加产品"改成 Dropdown**

定位 QuotationStep2.tsx 中现有的 `<Button>添加产品</Button>` 位置,改为:

```tsx
import { Dropdown } from 'antd';
import { DownOutlined, PlusOutlined, DatabaseOutlined, SettingOutlined } from '@ant-design/icons';

// 接 prop
interface QuotationStep2Props {
  // ...原有 props...
  onAddConfigured?: () => void;
}

// 替换原"添加产品"按钮:
<Dropdown menu={{ items: [
  { key: 'classic',   label: '从已有产品添加', icon: <DatabaseOutlined />, onClick: () => onAddProduct?.() },
  { key: 'configure', label: '选配添加',       icon: <SettingOutlined />,  onClick: () => onAddConfigured?.() },
]}}>
  <Button type="primary"><PlusOutlined /> 添加产品 <DownOutlined /></Button>
</Dropdown>
```

- [ ] **Step 3: 自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationWizard.tsx
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationStep2.tsx
```
Expected: 0 错误,两个 200

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx \
        cpq-frontend/src/pages/quotation/QuotationStep2.tsx
git commit -m "feat(configure): QuotationStep2 添加产品改 Dropdown(经典/选配),Wizard 引入 ConfigureProductDrawer"
```

---

### Task 35: Wizard Step1 改造 — 复用 QuotationCreateForm

**Files:**
- Modify: `cpq-frontend/src/pages/quotation/QuotationWizard.tsx` (Step1 表单升级)

- [ ] **Step 1: 在 Step1 客户选完后展示 QuotationCreateForm**

定位 `renderStep1()` 中 `<Form.Item name="customerId" ...>` 之后,但同 Card 之内,在客户字段后插入:

```tsx
import QuotationCreateForm from './QuotationCreateForm';

// state 新增
const [step1Valid, setStep1Valid] = useState(false);

// renderStep1 内,客户 Form.Item 后:
{selectedCustomer && (
  <QuotationCreateForm
    customerId={selectedCustomer.id}
    customerName={selectedCustomer.name}
    value={{
      name: form.getFieldValue('name') ?? '',
      categoryId: form.getFieldValue('categoryId'),
      customerTemplateId: form.getFieldValue('customerTemplateId'),
      costingTemplateId: form.getFieldValue('costingCardTemplateId'),
    }}
    onChange={(v) => form.setFieldsValue({
      name: v.name,
      categoryId: v.categoryId,
      customerTemplateId: v.customerTemplateId,
      costingCardTemplateId: v.costingTemplateId,
    })}
    onValidityChange={setStep1Valid}
  />
)}
```

- [ ] **Step 2: 把"下一步"按钮禁用直到 step1Valid**

定位 Wizard 底部的 Steps 切换按钮,把"下一步"按钮加 `disabled={currentStep === 0 && !step1Valid}`:

```tsx
<Button type="primary"
        onClick={next}
        disabled={currentStep === 0 && !step1Valid}>
  下一步
</Button>
```

- [ ] **Step 3: 移除老 Step1 中冗余字段(name/categoryId/customerTemplateId)**

QuotationCreateForm 内部已含 `name` 和 `categoryId` Form.Item — 老 Step1 中如果还有同名 Form.Item(`<Form.Item name="name">`),保留(QuotationCreateForm 与外层 form 共用同一 form instance 时不会冲突)或移除。**确保同一个字段只被一个组件渲染**避免双输入框。

> **实施时**:用 grep 看 renderStep1 中除"客户/项目/商机/类型/阶段/联系人"外是否有其他字段与 QuotationCreateForm 重复。

- [ ] **Step 4: 自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/quotation/QuotationWizard.tsx
```
Expected: 0 错误,200

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationWizard.tsx
git commit -m "feat(configure): Wizard Step1 复用 QuotationCreateForm(客户+分类+报价模板+核价模板)"
```

---

**Phase 8 完成检查点**:新建报价单 → 选客户后立即看到 4 字段表单(分类/报价模板/核价模板),必填后才能"下一步";Step2 [+ 添加产品] 拆 Dropdown 经典/选配。Phase 9(浏览器手测)在后续追加。

---

## Phase 9:浏览器手测 + 完成自检

### Task 36: 6 路径浏览器手测 + 自检报告

**Files:**
- 此 Task 只测试,无新文件;**完成后写自检结论到 RECORD.md**

- [ ] **Step 1: 前置数据准备**

```bash
# 启动前后端
cd cpq-frontend && npm run dev &
cd cpq-backend && ./mvnw quarkus:dev &

# 验证 Flyway 全部成功
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT version, success FROM flyway_schema_history WHERE version IN ('162','163','164','165','166','167','168','169','170') ORDER BY version"
```
Expected: 9 行全部 `success=t`

- [ ] **Step 2: 路径 1 — 独立产品 / 已有路径**

操作:
```
1. 登录 → 报价单列表 → 新建报价单
2. Step1: 选客户 X → 选产品分类 → 选报价模板 → "下一步"
3. Step2: 点 [+添加产品] → 下拉选"选配添加"
4. Drawer P0: 选"独立产品" → 下一步
5. P1: 搜索 "4NEG" → 列表出现现有料号 → 选第一条 → 下一步
6. P2: 材质区域显示锁定 banner,元素只读 → 下一步
7. P3: 工序锁定展示 → 下一步
8. P5: 看到现有料号 + 工序 + 单重(只读) → 确认添加
9. 报价单 Step2 列表出现 1 行新 line_item
```
✅ 通过

- [ ] **Step 3: 路径 2 — 独立产品 / 自定义未命中**

操作:
```
1. 同上,Drawer 抽屉 → P0 独立 → P1 搜索"AgNi92"找不到 → 选"无匹配料号,自定义"
2. P2: 选"银镍合金 90/10" (editable) → 改 Ag=92.0 Ni=8.0 → 下一步
   (此时 Drawer 后台调 lookup-fingerprint 返 matched=false,直接进 P3)
3. P3: 选 2 个工序(冲压 → 镀银) → 下一步
4. P5: 输入单重 12.5 g → 确认添加
5. 后端 DB 检查: mat_part 新增 CFG-AgNi-000001,mat_bom 2 行,mat_process 2 行
```

验证 DB:
```bash
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT part_no, product_type, unit_weight, config_fingerprint FROM mat_part WHERE part_no LIKE 'CFG-AgNi-%' ORDER BY part_no DESC LIMIT 3"
```
Expected: 含一行 `CFG-AgNi-000001 | SIMPLE | 12.5 | <64 hex>`

- [ ] **Step 4: 路径 3 — 独立产品 / 自定义命中复用**

操作:
```
1. 再次走路径 2 同配置: AgNi90 + Ag=92,Ni=8 (单重随便填,因不参与)
2. P2 → 下一步时,Drawer 弹出"已找到匹配料号 CFG-AgNi-000001"提示
3. 点"沿用 → 直接确认" → 跳到 P5,展示已有工序 + 12.5g(只读)
4. 确认添加 → 报价单出现 line_item,产品料号仍是 CFG-AgNi-000001
5. DB: mat_part 未新增 (count 不变)
```

验证:
```bash
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db \
  -c "SELECT COUNT(*) FROM mat_part WHERE part_no LIKE 'CFG-AgNi-%'"
```
Expected: 与路径 2 后的 count 相同(没新增)

- [ ] **Step 5: 路径 4 — 组合产品 / 全新**

操作:
```
1. Drawer → P0 选"组合产品" + 配件数量=2 → 下一步
2. P1(配件1): "无匹配" → P2 AgCu85 默认 → P3 冲压+镀银
3. P1(配件2): "无匹配" → P2 AgNi95 默认 → P3 烧结+镀银
4. P4 组合工艺: 添加"铆接",参与配件全选,填压力 5kN 高度 3.2mm → 下一步
5. P5: 看到 2 子卡片各填单重(10g / 11g) + 1 个铆接工艺 → 确认添加
6. DB: 父 CFG-COMBO-000001 + 2 个子 (CFG-AgCu/AgNi) + mat_bom ASSEMBLY 2 行 + mat_composite_process 1 行
```

验证:
```bash
PGPASSWORD=joii5231 psql -h localhost -p 5432 -U postgres -d cpq_db -c "
SELECT 'parts:' || COUNT(*) FROM mat_part WHERE part_no LIKE 'CFG-COMBO-%';
SELECT 'assembly:' || COUNT(*) FROM mat_bom WHERE bom_type='ASSEMBLY';
SELECT 'composite_proc:' || COUNT(*) FROM mat_composite_process;
"
```

- [ ] **Step 6: 路径 5 — 组合产品 / 子配件复用**

操作:
```
1. 复用路径 4 已存在的 2 个子料号:
   Drawer → 组合 + 2 配件 → 每个配件走"自定义" + 同 AgCu85/AgNi95 默认配置
2. P2 时两次都会命中已有指纹 → 提示沿用
3. 最后 P5 确认 → 仅新建一个 COMBO 父料号,子级全部复用
```

- [ ] **Step 7: 路径 6 — 组合产品 / 全复用**

操作:
```
1. 完全相同配置再走一次路径 4 → 父+子全部命中
2. 报价单出现 N+1 行 line_item,但 mat_part 没新行
```

- [ ] **Step 8: 写自检报告并提交**

```
TS 0 错误 ✅
所有改动 .tsx → Vite 200 ✅
9 张 Flyway V164~V174 success=t ✅
后端 8 测试 ConfigureProductServiceTest pass ✅
前端 Provider/Fingerprint 测试 pass ✅
6 路径浏览器手测全部走通 ✅
```

- [ ] **Step 9: 把开发记录写入 RECORD.md**

在 `docs/RECORD.md` **文件顶部**(第 6 行 `---` 之后)插入新条目:

```markdown
### [2026-05-13] 添加产品 — 选配 v2 全栈实施完成

**实施完成**: spec `docs/superpowers/specs/2026-05-13-add-product-configure-design.md` + plan `docs/superpowers/plans/2026-05-13-add-product-configure-implementation.md` 全部 9 Phase 36 Tasks。

**关键交付**:
- ✅ DB: V164~V174 共 9 张 migration(2 字典表 + 2 组合工艺表 + 3 列扩展 + 3 seed)
- ✅ 后端: PartNoProvider 抽象 + FingerprintCalculator + ConfigureProductService(6 端点) + 15 个测试
- ✅ 前端: ConfigureProductDrawer + 6 step 组件 + 3 service + Wizard Step1 改造 + Step2 Dropdown 入口
- ✅ 6 路径浏览器手测全部走通(独立已有/独立未命中/独立命中/组合全新/组合子复用/组合全复用)

**关键决策**:
- Q1 组合产品 = 父+子 mat_part + mat_bom.ASSEMBLY
- Q3 line_item 父+子 (parent_line_item_id + composite_type)
- Q4 组合工艺 = composite_process_def 字典 + mat_composite_process 实例(JSONB 参数)
- Q5 选配料号写 v2000 baseline,V160/V161 视图层零侵入
- Q6 F2 指纹:仅 recipe + 元素含量(组合则加子料号 sorted);单重/工序/组合工艺是料号 1:1 属性
- Q9 mat_process.unit_price=NULL,模板用全局变量 PROCESS_DEFAULT_PRICE 动态 key 取

**0 侵入承诺**: 现有报价单/模板/核价/Excel视图/公式/V6 导入 API 输入输出 字节级不变。
```

```bash
git add docs/RECORD.md
git commit -m "docs(record): 添加产品 — 选配 v2 全栈实施完成记录"
```

---

**Phase 9 完成 = 整个项目完成**:9 张迁移落库 + 12 个后端文件 + 15 个测试 + 12 个前端文件 + 2 个入口改造 + 6 路径手测 + RECORD.md 回写。所有 Phase 0 自检规范全程执行。

---

## 总览

| Phase | Tasks | 范围 |
|---|---|---|
| 1 | Task 1~9   | 9 张 Flyway 迁移(字典 + 加列 + seed) |
| 2 | Task 10~12 | PartNoProvider 抽象 + V1 实现 + 4 单元测试 |
| 3 | Task 13~17 | FingerprintCalculator + 4 Entity + 2 字典 Service/Resource |
| 4 | Task 18~23 | ConfigureProductService(lookup/resolve/configure/落库) + 2 Resource |
| 5 | Task 24    | ConfigureProductService 8 场景集成测试 |
| 6 | Task 25~26 | 3 个前端 service + 类型定义 |
| 7 | Task 27~33 | Drawer 主壳 + 6 个 step 组件 |
| 8 | Task 34~35 | Wizard Step1 改造 + Step2 Dropdown 入口 |
| 9 | Task 36    | 6 路径浏览器手测 + RECORD.md 回写 |

**共 36 个 Task,严格 TDD + 全 commit**。
