-- ============================================================
-- V220: V6 基础数据 - 价格/费用/资源/设备/能耗/耗材/电价/工时/年降/电镀 (14 张)
-- 来源: docs/table/数据库表结构设计文档.md §7, §9, §11~§12, §14~§23
-- 依赖: V218 / V219
-- ============================================================

-- ============== 7. 费用表 fee_config ==========================
CREATE TABLE IF NOT EXISTS fee_config (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    system_type         VARCHAR(10)     NOT NULL,
    biz_type            VARCHAR(30)     NOT NULL,
    fee_no              VARCHAR(30)     NOT NULL,
    fee_name            VARCHAR(100)    NOT NULL,
    material_no         VARCHAR(20),
    customer_no         VARCHAR(20),
    region              VARCHAR(50),
    charge_basis        VARCHAR(20),
    value               DECIMAL(18,6),
    ratio               DECIMAL(10,4),
    currency            VARCHAR(10),
    unit                VARCHAR(20),
    effective_date      DATE,
    expire_date         DATE,
    pricing_version_no  VARCHAR(20),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    CONSTRAINT chk_fee_config_system_type CHECK (system_type IN ('QUOTE','PRICING')),
    CONSTRAINT chk_fee_config_biz_type
        CHECK (biz_type IN ('PROFIT','TAX','FREIGHT','CUSTOMS','INSURANCE','BANK','OTHER')),
    CONSTRAINT chk_fee_config_charge_basis
        CHECK (charge_basis IS NULL OR charge_basis IN ('RATE','FIXED','PER_UNIT','PER_KG'))
);

-- 注：PG unique index 不能用 date::TEXT (STABLE 非 IMMUTABLE)，改用 COALESCE 到哨兵日期
CREATE UNIQUE INDEX uq_fee_config
    ON fee_config(
        system_type, biz_type, fee_no,
        COALESCE(material_no, ''),
        COALESCE(customer_no, ''),
        COALESCE(region, ''),
        COALESCE(effective_date, DATE '1900-01-01'),
        COALESCE(pricing_version_no, '')
    );
CREATE INDEX idx_fee_config_lookup   ON fee_config(biz_type, system_type, effective_date DESC);
CREATE INDEX idx_fee_config_customer ON fee_config(customer_no);
CREATE INDEX idx_fee_config_material ON fee_config(material_no);

COMMENT ON TABLE fee_config IS 'V6 §7 商务/物流类费用配置（利润率/税率/运费/清关/保险/银行/其他）';

-- ============== 9. 电镀方案表 plating_scheme ==================
CREATE TABLE IF NOT EXISTS plating_scheme (
    id                    UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_no             VARCHAR(20)     NOT NULL,
    scheme_version        VARCHAR(20)     NOT NULL,
    seq_no                INTEGER         NOT NULL,
    plating_element       VARCHAR(20)     NOT NULL,
    plating_method        VARCHAR(30)     NOT NULL,
    surface_area          DECIMAL(18,6)   NOT NULL,
    plating_area          DECIMAL(18,6),
    plating_thickness     DECIMAL(18,6)   NOT NULL,
    plating_requirement   VARCHAR(200),
    density               DECIMAL(18,6),
    element_usage         DECIMAL(18,6)   NOT NULL,
    element_usage_unit    VARCHAR(20),
    effective_date        DATE,
    expire_date           DATE,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID
);

CREATE UNIQUE INDEX uq_plating_scheme ON plating_scheme(scheme_no, scheme_version, seq_no);
CREATE INDEX idx_plating_scheme_element ON plating_scheme(plating_element);

COMMENT ON TABLE plating_scheme IS 'V6 §9 电镀方案表（按方案号+版本+项次唯一）';

-- ============== 11. 产能表 capacity ===========================
CREATE TABLE IF NOT EXISTS capacity (
    id                       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no              VARCHAR(20)     NOT NULL,
    material_name            VARCHAR(100),
    specification            VARCHAR(100),
    dimension                VARCHAR(100),
    process_no               VARCHAR(20)     NOT NULL,
    process_name             VARCHAR(50),
    resource_group_no        VARCHAR(20)     NOT NULL,
    resource_group_name      VARCHAR(50),
    production_type          VARCHAR(20)     NOT NULL,
    fixed_lead_time          DECIMAL(18,6),
    variable_time            DECIMAL(18,6),
    variable_time_batch      DECIMAL(18,6),
    capacity_unit            VARCHAR(20),
    default_defect_rate      DECIMAL(10,4),
    cost_type                VARCHAR(20),
    fixed_cost               DECIMAL(18,6),
    cost_ratio               DECIMAL(10,4),
    annual_discount_factor   DECIMAL(10,4),
    calc_version             VARCHAR(20),
    is_effective             BOOLEAN,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by               UUID,
    updated_by               UUID,
    CONSTRAINT chk_capacity_production_type
        CHECK (production_type IN ('UNIT','BATCH','BATCH_FIXED'))
);

CREATE UNIQUE INDEX uq_capacity
    ON capacity(material_no, process_no, resource_group_no, COALESCE(calc_version, ''));
CREATE INDEX idx_capacity_process       ON capacity(process_no);
CREATE INDEX idx_capacity_resource_grp  ON capacity(resource_group_no);

COMMENT ON TABLE capacity IS 'V6 §11 产能表（料号+工序+资源群组+计算版本唯一）';

-- ============== 12. 零件单价表 unit_price =====================
CREATE TABLE IF NOT EXISTS unit_price (
    id                          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    system_type                 VARCHAR(10)     NOT NULL,
    price_type                  VARCHAR(20)     NOT NULL,
    version_no                  VARCHAR(20)     NOT NULL,
    code                        VARCHAR(30)     NOT NULL,
    name                        VARCHAR(100),
    specification               VARCHAR(100),
    dimension                   VARCHAR(100),
    finished_material_no        VARCHAR(20),
    operation_no                VARCHAR(20),
    cost_type                   VARCHAR(20),
    seq_no                      INTEGER,
    plating_scheme_no           VARCHAR(20),
    pricing_price               DECIMAL(18,6)   NOT NULL,
    cost_ratio                  DECIMAL(10,4),
    market_ref_price            DECIMAL(18,6),
    currency                    VARCHAR(10),
    unit                        VARCHAR(20),
    conversion_rate             DECIMAL(18,6),
    recovery_discount           DECIMAL(10,4),
    life_qty                    BIGINT,
    life_unit                   VARCHAR(20),
    supplier_no                 VARCHAR(20),
    supplier_name               VARCHAR(100),
    customer_no                 VARCHAR(20),
    customer_name               VARCHAR(100),
    data_type                   VARCHAR(20),
    source_url                  VARCHAR(500),
    source_name                 VARCHAR(100),
    fetch_rule                  VARCHAR(200),
    premium_fee                 DECIMAL(18,6),
    fetched_price               DECIMAL(18,6),
    fetch_time                  TIMESTAMPTZ,
    effective_date              DATE,
    expire_date                 DATE,
    base_value                  DECIMAL(18,6),
    is_fluctuate_with_material  BOOLEAN,
    material_increase_ratio     DECIMAL(10,4),
    material_fixed_increase     DECIMAL(18,6),
    defect_rate                 DECIMAL(10,4),
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by                  UUID,
    updated_by                  UUID,
    CONSTRAINT chk_unit_price_system_type CHECK (system_type IN ('QUOTE','PRICING')),
    CONSTRAINT chk_unit_price_type
        CHECK (price_type IN ('ELEMENT','MATERIAL','COMPONENT','PART','CONSUMABLE')),
    CONSTRAINT chk_unit_price_life_unit
        CHECK (life_unit IS NULL OR life_unit IN ('TIMES','HOURS','PCS','DAYS'))
);

CREATE UNIQUE INDEX uq_unit_price
    ON unit_price(
        system_type, price_type, version_no, code,
        COALESCE(customer_no, ''),
        COALESCE(supplier_no, ''),
        COALESCE(effective_date, DATE '1900-01-01')
    );
CREATE INDEX idx_unit_price_lookup   ON unit_price(price_type, code, currency);
CREATE INDEX idx_unit_price_customer ON unit_price(customer_no);
CREATE INDEX idx_unit_price_supplier ON unit_price(supplier_no);

COMMENT ON TABLE unit_price IS 'V6 §12 零件单价表（元素/材料/组成件/零件/耗材 5 类）';

-- ============== 14. 资源群组表 resource_group =================
CREATE TABLE IF NOT EXISTS resource_group (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    group_no        VARCHAR(20)     NOT NULL,
    group_name      VARCHAR(50)     NOT NULL,
    group_type      VARCHAR(30),
    seq_no          INTEGER,
    process_no      VARCHAR(20),
    process_name    VARCHAR(50),
    workshop        VARCHAR(50),
    equipment_id    VARCHAR(50),
    description     VARCHAR(200),
    effective_date  DATE,
    expire_date     DATE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT chk_resource_group_type
        CHECK (group_type IS NULL OR group_type IN ('MACHINE','PLATING','ASSEMBLY','TEST'))
);

CREATE UNIQUE INDEX uq_resource_group_no ON resource_group(group_no);
CREATE INDEX idx_resource_group_process  ON resource_group(process_no);
CREATE INDEX idx_resource_group_type     ON resource_group(group_type);

COMMENT ON TABLE resource_group IS 'V6 §14 资源群组（以设备为单位的资源编组）';

-- ============== 15. 设备表 equipment ==========================
CREATE TABLE IF NOT EXISTS equipment (
    id                       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    equipment_no             VARCHAR(30)     NOT NULL,
    equipment_name           VARCHAR(100)    NOT NULL,
    equipment_type           VARCHAR(50),
    resource_group_no        VARCHAR(20)     NOT NULL,
    resource_group_name      VARCHAR(50),
    workshop                 VARCHAR(50),
    original_amount          DECIMAL(18,2)   NOT NULL,
    residual_value           DECIMAL(18,2),
    depreciation_method      VARCHAR(30)     NOT NULL,
    depreciation_years       DECIMAL(10,2),
    annual_available_hours   DECIMAL(18,2)   NOT NULL,
    production_calendar      VARCHAR(50),
    purchase_date            DATE,
    annual_depreciation      DECIMAL(18,6),
    hourly_depreciation      DECIMAL(18,6),
    currency                 VARCHAR(10),
    status                   VARCHAR(20),
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by               UUID,
    updated_by               UUID,
    CONSTRAINT chk_equipment_depreciation_method
        CHECK (depreciation_method IN ('STRAIGHT_LINE','SUM_YEARS','DOUBLE_DECLINING','UNITS')),
    CONSTRAINT chk_equipment_status
        CHECK (status IS NULL OR status IN ('IN_USE','IDLE','SCRAPPED'))
);

CREATE UNIQUE INDEX uq_equipment_no ON equipment(equipment_no);
CREATE INDEX idx_equipment_group_status ON equipment(resource_group_no, status);

COMMENT ON TABLE equipment IS 'V6 §15 设备表（替代V2设备折旧成本表，年折旧+工时折旧）';

-- ============== 16. 生产设备能耗表 production_energy ==========
CREATE TABLE IF NOT EXISTS production_energy (
    id                       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no              VARCHAR(20)     NOT NULL,
    material_name            VARCHAR(100),
    specification            VARCHAR(100),
    dimension                VARCHAR(100),
    process_no               VARCHAR(20)     NOT NULL,
    process_name             VARCHAR(50),
    equipment_no             VARCHAR(30),
    batch_size               DECIMAL(18,6),
    round_step               DECIMAL(18,6),
    working_hours            DECIMAL(18,6),
    energy_unit_price        DECIMAL(18,6),
    depreciation_unit_price  DECIMAL(18,6),
    currency                 VARCHAR(10),
    unit                     VARCHAR(20),
    conversion_rate          DECIMAL(18,6),
    calc_version             VARCHAR(20),
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by               UUID,
    updated_by               UUID
);

CREATE UNIQUE INDEX uq_production_energy
    ON production_energy(
        material_no, process_no,
        COALESCE(equipment_no, ''),
        COALESCE(calc_version, '')
    );
CREATE INDEX idx_production_energy_process   ON production_energy(process_no);
CREATE INDEX idx_production_energy_equipment ON production_energy(equipment_no);

COMMENT ON TABLE production_energy IS 'V6 §16 生产设备能耗（料号+工序+设备+计算版本）';

-- ============== 17. 辅助设备能耗表 auxiliary_energy ============
CREATE TABLE IF NOT EXISTS auxiliary_energy (
    id                            UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no                   VARCHAR(20)     NOT NULL,
    material_name                 VARCHAR(100),
    specification                 VARCHAR(100),
    dimension                     VARCHAR(100),
    process_no                    VARCHAR(20)     NOT NULL,
    process_name                  VARCHAR(50),
    amortize_basis                VARCHAR(20),
    working_hours                 DECIMAL(18,6),
    total_hours                   DECIMAL(18,6),
    non_production_energy_price   DECIMAL(18,6),
    currency                      VARCHAR(10),
    unit                          VARCHAR(20),
    conversion_rate               DECIMAL(18,6),
    calc_version                  VARCHAR(20),
    created_at                    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by                    UUID,
    updated_by                    UUID,
    CONSTRAINT chk_auxiliary_energy_amortize
        CHECK (amortize_basis IS NULL OR amortize_basis IN ('HOURS','QTY'))
);

CREATE UNIQUE INDEX uq_auxiliary_energy
    ON auxiliary_energy(material_no, process_no, COALESCE(calc_version, ''));
CREATE INDEX idx_auxiliary_energy_process ON auxiliary_energy(process_no);

COMMENT ON TABLE auxiliary_energy IS 'V6 §17 辅助设备能耗（按工时/数量摊销至料号工序）';

-- ============== 18. 模具工装成本表 tooling_cost ===============
CREATE TABLE IF NOT EXISTS tooling_cost (
    id                    UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no           VARCHAR(20)     NOT NULL,
    material_name         VARCHAR(100),
    specification         VARCHAR(100),
    dimension             VARCHAR(100),
    process_no            VARCHAR(20)     NOT NULL,
    process_name          VARCHAR(50),
    seq_no                INTEGER         NOT NULL,
    tooling_no            VARCHAR(30)     NOT NULL,
    tooling_unit_cost     DECIMAL(18,6),
    tool_life             BIGINT,
    cycle_output          DECIMAL(18,6),
    tooling_unit_price    DECIMAL(18,8)   NOT NULL,
    currency              VARCHAR(10),
    unit                  VARCHAR(20),
    is_effective          BOOLEAN,
    conversion_rate       DECIMAL(18,6),
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID
);

CREATE UNIQUE INDEX uq_tooling_cost
    ON tooling_cost(material_no, process_no, seq_no, tooling_no);
CREATE INDEX idx_tooling_cost_process ON tooling_cost(process_no, tooling_no);

COMMENT ON TABLE tooling_cost IS 'V6 §18 模具工装成本（料号+工序+项次+模具号唯一）';

-- ============== 19. 生产耗材表 production_consumable ==========
CREATE TABLE IF NOT EXISTS production_consumable (
    id                    UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no           VARCHAR(20)     NOT NULL,
    material_name         VARCHAR(100),
    specification         VARCHAR(100),
    dimension             VARCHAR(100),
    process_no            VARCHAR(20)     NOT NULL,
    process_name          VARCHAR(50),
    resource_group_no     VARCHAR(20)     NOT NULL,
    seq_no                INTEGER         NOT NULL,
    consumable_no         VARCHAR(30)     NOT NULL,
    consumable_name       VARCHAR(100),
    usage_qty             DECIMAL(18,6),
    life_qty              BIGINT,
    life_unit             VARCHAR(20),
    usage_unit            VARCHAR(20),
    consumable_version    VARCHAR(20),
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID,
    CONSTRAINT chk_production_consumable_life_unit
        CHECK (life_unit IS NULL OR life_unit IN ('TIMES','PCS','HOURS'))
);

CREATE UNIQUE INDEX uq_production_consumable
    ON production_consumable(material_no, process_no, resource_group_no, seq_no, consumable_no);
CREATE INDEX idx_production_consumable_process ON production_consumable(process_no, consumable_no);

COMMENT ON TABLE production_consumable IS 'V6 §19 生产耗材（料号+工序+资源群组+项次+耗材料号）';

-- ============== 20. 包装耗材表 packaging_consumable ===========
CREATE TABLE IF NOT EXISTS packaging_consumable (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    material_no         VARCHAR(20)     NOT NULL,
    material_name       VARCHAR(100),
    specification       VARCHAR(100),
    dimension           VARCHAR(100),
    seq_no              INTEGER         NOT NULL,
    consumable_no       VARCHAR(30)     NOT NULL,
    consumable_name     VARCHAR(100),
    usage_qty           DECIMAL(18,6)   NOT NULL,
    usage_unit          VARCHAR(20),
    packaging_level     VARCHAR(20),
    packaging_version   VARCHAR(20),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    CONSTRAINT chk_packaging_consumable_level
        CHECK (packaging_level IS NULL OR packaging_level IN ('INNER','MIDDLE','OUTER','PALLET'))
);

CREATE UNIQUE INDEX uq_packaging_consumable
    ON packaging_consumable(material_no, seq_no, consumable_no);
CREATE INDEX idx_packaging_consumable_consumable ON packaging_consumable(consumable_no);

COMMENT ON TABLE packaging_consumable IS 'V6 §20 包装耗材（成品料号+项次+包装耗材唯一）';

-- ============== 21. 电价表 electricity_price ==================
CREATE TABLE IF NOT EXISTS electricity_price (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    region          VARCHAR(50)     NOT NULL,
    voltage_level   VARCHAR(20),
    price_type      VARCHAR(20)     NOT NULL,
    time_range      VARCHAR(50),
    price           DECIMAL(18,6)   NOT NULL,
    unit            VARCHAR(20),
    effective_date  DATE            NOT NULL,
    expire_date     DATE,
    version_no      VARCHAR(20),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID
);

CREATE UNIQUE INDEX uq_electricity_price
    ON electricity_price(
        region,
        COALESCE(voltage_level, ''),
        price_type,
        effective_date,
        COALESCE(version_no, '')
    );
CREATE INDEX idx_electricity_price_lookup ON electricity_price(region, effective_date DESC);

COMMENT ON TABLE electricity_price IS 'V6 §21 电价表（地区+电压等级+峰平谷+生效日期+版本）';

-- ============== 22. 工时单价表 labor_rate =====================
CREATE TABLE IF NOT EXISTS labor_rate (
    id                    UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    version_no            VARCHAR(20)     NOT NULL,
    material_no           VARCHAR(20),
    process_no            VARCHAR(20)     NOT NULL,
    process_name          VARCHAR(50),
    labor_grade           VARCHAR(30),
    standard_labor_rate   DECIMAL(18,6)   NOT NULL,
    currency              VARCHAR(10),
    unit                  VARCHAR(20),
    effective_date        DATE,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID
);

CREATE UNIQUE INDEX uq_labor_rate
    ON labor_rate(
        version_no, process_no,
        COALESCE(material_no, ''),
        COALESCE(labor_grade, '')
    );
CREATE INDEX idx_labor_rate_process ON labor_rate(process_no, version_no);

COMMENT ON TABLE labor_rate IS 'V6 §22 工时单价表（版本+料号+工序+工种唯一）';

-- ============== 23. 年降系数表 annual_discount ================
CREATE TABLE IF NOT EXISTS annual_discount (
    id                       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    biz_type                 VARCHAR(20)     NOT NULL,
    material_no              VARCHAR(20)     NOT NULL,
    discount_strategy        VARCHAR(50)     NOT NULL,
    discount_base            DECIMAL(18,6),
    discount_order           INTEGER         NOT NULL,
    discount_ratio           DECIMAL(10,4),
    fixed_discount_value     DECIMAL(18,6),
    currency                 VARCHAR(10),
    unit                     VARCHAR(20),
    discount_times           INTEGER,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by               UUID,
    updated_by               UUID,
    CONSTRAINT chk_annual_discount_biz_type
        CHECK (biz_type IN ('INCOMING','ASSEMBLY','FINISHED'))
);

CREATE UNIQUE INDEX uq_annual_discount
    ON annual_discount(biz_type, material_no, discount_strategy, discount_order);
CREATE INDEX idx_annual_discount_material ON annual_discount(material_no, biz_type);

COMMENT ON TABLE annual_discount IS 'V6 §23 年降系数表（业务类型+料号+策略+顺序唯一）';
