-- V76: 核价系统料号级数据 (Phase B)
--
-- 16 个料号级 sheet 合并为 8 张表（业务结构相似的合并 + 独立的不动），保持"无冗余"语义：
--   1. costing_part_process_cost  ← 工序级单价（人工/折旧/关联能耗/共享能耗/耗材/材料加工费/半品加工费/后道加工）8 sheet 合一，用 cost_type 鉴别
--   2. costing_part_tooling_cost  ← 模具工装（独立，含模具台号 / 工艺次数 / 可循环次数）
--   3. costing_part_material_bom  ← 材料 BOM
--   4. costing_part_element_bom   ← 元素 BOM
--   5. costing_part_quality_check ← 检验（进料/半品 用 stage 鉴别）
--   6. costing_part_plating       ← 电镀
--   7. costing_part_design_cost   ← 设计成本
--   8. costing_part_weight        ← 重量
--
-- 公共约束：所有表 hf_part_no NOT NULL；统一 created_at / updated_at；带 is_active 软删；带 ref_calc_version 引用全局计算版本号。
-- FK 不指向 mat_part（核价数据可能领先于主数据；保持松耦合）。

-- ─── 1. 工序级单价（8 sheet 合一，cost_type 鉴别） ──────────────────────
CREATE TABLE costing_part_process_cost (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hf_part_no         VARCHAR(100) NOT NULL,
    process_no         VARCHAR(50)  NOT NULL,         -- 工序号 Z053 等
    process_name       VARCHAR(200),                  -- 工序名称
    cost_type          VARCHAR(30)  NOT NULL,         -- 8 种鉴别值
    unit_price         NUMERIC(18,6) NOT NULL,        -- 单价（折旧 / 能耗值的精度高用 6 位）
    currency           VARCHAR(10)  NOT NULL DEFAULT 'CNY',
    unit               VARCHAR(20)  NOT NULL DEFAULT 'KG',  -- KG / PCS
    ref_calc_version   VARCHAR(50),                   -- "取得的计算版本"
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    notes              TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_process_cost_type CHECK (cost_type IN (
        'LABOR',           -- 人工成本
        'DEPRECIATION',    -- 设备折旧
        'ENERGY_DEDICATED',-- 关联设备能耗
        'ENERGY_SHARED',   -- 共享设备能耗
        'CONSUMABLE',      -- 耗材包装材料
        'MATERIAL_PROC',   -- 材料加工费
        'SEMI_FINISHED_PROC', -- 半品加工费&组装费
        'POST_PROC'        -- 后道加工成本
    )),
    -- 唯一性：同料号 × 同工序 × 同 cost_type 仅一条
    CONSTRAINT uq_process_cost UNIQUE (hf_part_no, process_no, cost_type)
);
CREATE INDEX idx_process_cost_part   ON costing_part_process_cost (hf_part_no);
CREATE INDEX idx_process_cost_type   ON costing_part_process_cost (cost_type);

-- ─── 2. 模具工装成本 ─────────────────────────────────────────────────
CREATE TABLE costing_part_tooling_cost (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hf_part_no         VARCHAR(100) NOT NULL,
    process_no         VARCHAR(50)  NOT NULL,
    process_name       VARCHAR(200),
    seq_no             INT          NOT NULL,         -- 序号(同料号同工序可多套模具)
    tooling_no         VARCHAR(100),                  -- 模具台号 / 工装编号
    tooling_unit_cost  NUMERIC(18,4) NOT NULL,        -- 单套模具/工装成本 I
    process_count      INT,                           -- 工艺次数 J
    cycle_count        INT,                           -- 可循环次数 K
    unit_price         NUMERIC(18,6),                 -- L = I/J/K（应用层算 + 落库；查询直读）
    currency           VARCHAR(10)  NOT NULL DEFAULT 'CNY',
    unit               VARCHAR(20)  NOT NULL DEFAULT 'PCS',
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    notes              TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tooling UNIQUE (hf_part_no, process_no, seq_no)
);
CREATE INDEX idx_tooling_part ON costing_part_tooling_cost (hf_part_no);

-- ─── 3. 材料 BOM ────────────────────────────────────────────────────
CREATE TABLE costing_part_material_bom (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hf_part_no          VARCHAR(100) NOT NULL,
    seq_no              INT          NOT NULL,         -- 主表序号 10/20/30
    input_material_no   VARCHAR(100),                  -- 输入料号
    process_no          VARCHAR(50),                   -- 工序号
    process_name        VARCHAR(200),                  -- 工序名称
    input_qty           NUMERIC(18,6),                 -- 输入数量
    input_unit          VARCHAR(20),                   -- 输入数量单位
    output_qty          NUMERIC(18,6),                 -- 产出
    output_unit         VARCHAR(20),                   -- 产出单位
    output_loss_rate    NUMERIC(8,4),                  -- 产出损耗率%
    fixed_loss_qty      NUMERIC(18,6),                 -- 材料固定损耗量
    loss_rate           NUMERIC(8,4),                  -- 损耗率%
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    notes               TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_material_bom UNIQUE (hf_part_no, seq_no)
);
CREATE INDEX idx_material_bom_part ON costing_part_material_bom (hf_part_no);

-- ─── 4. 元素 BOM ────────────────────────────────────────────────────
CREATE TABLE costing_part_element_bom (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    input_material_no   VARCHAR(100) NOT NULL,         -- 输入料号
    seq_no              INT          NOT NULL,
    element_code        VARCHAR(50)  NOT NULL,         -- 元素代码 Ag/C 等
    composition_pct     NUMERIC(8,4) NOT NULL,         -- 组成含量%
    loss_rate           NUMERIC(8,4),                  -- 损耗率%
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    notes               TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_element_bom UNIQUE (input_material_no, seq_no, element_code)
);
CREATE INDEX idx_element_bom_input ON costing_part_element_bom (input_material_no);

-- ─── 5. 质量检验（进料 + 半品 合一，stage 鉴别） ─────────────────────
CREATE TABLE costing_part_quality_check (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hf_part_no          VARCHAR(100) NOT NULL,
    stage               VARCHAR(20)  NOT NULL,         -- INCOMING / SEMI_FINISHED
    primary_seq_no      INT,                           -- 一级序号(进料检验时用)
    seq_no              INT          NOT NULL,
    requirement_code    VARCHAR(100),                  -- 要件编号
    requirement_desc    TEXT,                          -- 要件描述
    scrap_rate          NUMERIC(8,4),                  -- 报废率%
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    notes               TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_qc_stage CHECK (stage IN ('INCOMING', 'SEMI_FINISHED')),
    CONSTRAINT uq_qc UNIQUE (hf_part_no, stage, primary_seq_no, seq_no)
);
CREATE INDEX idx_qc_part ON costing_part_quality_check (hf_part_no, stage);

-- ─── 6. 成品电镀 ────────────────────────────────────────────────────
CREATE TABLE costing_part_plating (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plating_no          VARCHAR(100) NOT NULL,         -- 电镀编号 A0001
    version_number      VARCHAR(50)  NOT NULL,         -- 版本（电镀本身的版本号）
    seq_no              INT          NOT NULL,
    element_attr        VARCHAR(100),                  -- 镀层元素属性
    plating_area_cm2    NUMERIC(18,6),                 -- 电镀面积 cm²
    layer_thickness_um  NUMERIC(18,6),                 -- 镀层厚度 μm
    requirement         TEXT,                          -- 镀层要求
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_plating UNIQUE (plating_no, version_number, seq_no)
);
CREATE INDEX idx_plating_no ON costing_part_plating (plating_no);

-- ─── 7. 设计成本 ────────────────────────────────────────────────────
CREATE TABLE costing_part_design_cost (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hf_part_no          VARCHAR(100) NOT NULL,
    design_drawing_no   VARCHAR(100),                  -- 设计图编号
    version_number      VARCHAR(50),                   -- 版本
    design_proc_fee     NUMERIC(18,4),                 -- 设计加工费
    design_material_fee NUMERIC(18,4),                 -- 设计材料费
    currency            VARCHAR(10)  NOT NULL DEFAULT 'CNY',
    unit                VARCHAR(20)  NOT NULL DEFAULT 'KG',
    loss_rate           NUMERIC(8,4),                  -- 损耗率%
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    notes               TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_design_cost UNIQUE (hf_part_no, design_drawing_no, version_number)
);
CREATE INDEX idx_design_cost_part ON costing_part_design_cost (hf_part_no);

-- ─── 8. 重量 ────────────────────────────────────────────────────────
CREATE TABLE costing_part_weight (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    hf_part_no          VARCHAR(100) NOT NULL UNIQUE,  -- 一料号一重量
    weight_g_per_pcs    NUMERIC(18,6) NOT NULL,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    notes               TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 表注释
COMMENT ON TABLE costing_part_process_cost  IS '工序级单价（8 种成本类型合一）';
COMMENT ON TABLE costing_part_tooling_cost  IS '模具工装成本（含工艺次数、可循环次数）';
COMMENT ON TABLE costing_part_material_bom  IS '材料 BOM（产出 + 损耗）';
COMMENT ON TABLE costing_part_element_bom   IS '元素 BOM（组成含量 + 损耗）';
COMMENT ON TABLE costing_part_quality_check IS '质量检验（进料 + 半品 合一）';
COMMENT ON TABLE costing_part_plating       IS '成品电镀';
COMMENT ON TABLE costing_part_design_cost   IS '设计成本';
COMMENT ON TABLE costing_part_weight        IS '料号重量（g/pcs）';
