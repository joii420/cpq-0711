-- V290: 核价版本化表 system_type 维度补齐 + 存量非数字版本洗到 2000。
-- 设计：docs/superpowers/specs/2026-06-03-核价导入升版逻辑对齐报价-design.md §4.1/§4.4
-- 幂等：ADD COLUMN IF NOT EXISTS / DROP INDEX IF EXISTS / 约束加前先 DROP。

-- ============ 1. capacity 加 system_type ============
ALTER TABLE capacity ADD COLUMN IF NOT EXISTS system_type VARCHAR(10) NOT NULL DEFAULT 'QUOTE';
ALTER TABLE capacity DROP CONSTRAINT IF EXISTS chk_capacity_system_type;
ALTER TABLE capacity ADD CONSTRAINT chk_capacity_system_type
    CHECK (system_type IN ('QUOTE','PRICING','BOTH'));

-- ============ 2. plating_scheme 加 system_type ============
ALTER TABLE plating_scheme ADD COLUMN IF NOT EXISTS system_type VARCHAR(10) NOT NULL DEFAULT 'QUOTE';
ALTER TABLE plating_scheme DROP CONSTRAINT IF EXISTS chk_plating_scheme_system_type;
ALTER TABLE plating_scheme ADD CONSTRAINT chk_plating_scheme_system_type
    CHECK (system_type IN ('QUOTE','PRICING','BOTH'));

-- ============ 3. 回填 system_type（启发式：按 resource_group_no / 版本数字性判定来源）============
-- capacity：P08 写 resource_group_no='PRICING_DEFAULT'；Q14 写 'QUOTE_ASSEMBLY'。以 resource_group_no 为准。
UPDATE capacity SET system_type = 'PRICING' WHERE resource_group_no = 'PRICING_DEFAULT';
UPDATE capacity SET system_type = 'QUOTE'   WHERE resource_group_no = 'QUOTE_ASSEMBLY';
-- plating_scheme：Q16 系统生成数字 scheme_version；P21 写 Excel「版本」（多为非数字）。
UPDATE plating_scheme SET system_type = 'PRICING' WHERE scheme_version !~ '^[0-9]+$';
UPDATE plating_scheme SET system_type = 'QUOTE'   WHERE scheme_version ~ '^[0-9]+$';

-- ============ 4. 改唯一键（含 system_type）============
DROP INDEX IF EXISTS uq_capacity;
CREATE UNIQUE INDEX uq_capacity ON capacity
    (system_type, material_no, process_no, resource_group_no, COALESCE(calc_version, ''));

DROP INDEX IF EXISTS uq_plating_scheme;
CREATE UNIQUE INDEX uq_plating_scheme ON plating_scheme
    (system_type, scheme_no, scheme_version, seq_no);

-- ============ 5. 存量非数字版本洗到 2000（只动非数字，避免 uq 冲突）============
UPDATE material_bom
   SET bom_version = '2000'
 WHERE system_type = 'PRICING' AND bom_version !~ '^[0-9]+$';
UPDATE element_bom
   SET characteristic = '2000'
 WHERE system_type = 'PRICING' AND characteristic !~ '^[0-9]+$';
UPDATE element_bom_item
   SET characteristic = '2000'
 WHERE system_type = 'PRICING' AND characteristic !~ '^[0-9]+$';
UPDATE capacity
   SET calc_version = '2000'
 WHERE system_type = 'PRICING' AND (calc_version IS NULL OR calc_version !~ '^[0-9]+$');
UPDATE plating_scheme
   SET scheme_version = '2000'
 WHERE system_type = 'PRICING' AND scheme_version !~ '^[0-9]+$';

-- ============ 6. is_current 单一性：旧逻辑单版本 → 全 TRUE 已正确，无需翻转。 ============

-- ============ 7. 护栏②：回填完成后 DROP DEFAULT，使 INSERT 漏 system_type 列即 NOT-NULL 报错 ============
ALTER TABLE capacity        ALTER COLUMN system_type DROP DEFAULT;
ALTER TABLE plating_scheme  ALTER COLUMN system_type DROP DEFAULT;
