-- =====================================================================
-- V297: 报价系统 unit_price.price_type 细分化
-- ---------------------------------------------------------------------
-- 背景：报价系统 Excel 导入（system_type=QUOTE）原本 9 个费用 Sheet 都写大类
--   MATERIAL / COMPONENT，仅靠 cost_type 区分来源。现要求把 price_type 直接
--   改写为 9 个细分值以区分 Sheet 类型（大类彻底废弃）。cost_type 保持不动。
--
-- 对照（详见 docs/table/报价系统Excel导入落库方案.md +
--   docs/superpowers/specs/2026-06-08-quote-price-type-subdivide-design.md）：
--   来料固定加工费 → INCOMING_MATERIAL_PROCESS
--   来料其他费用   → INCOMING_MATERIAL_OTHER
--   来料年降       → INCOMING_MATERIAL_REDUCTION
--   来料回收折扣   → INCOMING_MATERIAL_RECYCLE
--   自制加工费     → PROCESS（含选配运行时 ConfigureProductService 写入）
--   成品其他费用   → FINISHED_MATERIAL_OTHER
--   组成件其他费用 → COMPONENT_OTHER
--   组装加工费年降 → COMPONENT_REDUCTION
--   电镀费用(2条)  → PLATING
--
-- 范围说明：本迁移只放开"写新值"的两个硬约束（列宽 + CHECK）。
--   - 旧 5 个值（ELEMENT/MATERIAL/COMPONENT/PART/CONSUMABLE）全部保留：
--     核价系统（system_type=PRICING）的 SQL 视图仍按 MATERIAL/COMPONENT 等取数，
--     报价侧 zcj_view（QUOTE+COMPONENT）按规划暂不动（接受断链，后续单独处理）。
--   - varchar(20)→varchar(40) 是同类型加长（binary coercible），PG 放行，
--     无需 DROP 任何引用 unit_price 的真实视图（已实测验证）。
-- =====================================================================

-- 1) 扩列宽：最长 INCOMING_MATERIAL_REDUCTION = 27 字符，原 VARCHAR(20) 不够
ALTER TABLE unit_price ALTER COLUMN price_type TYPE VARCHAR(40);

-- 2) 重建 CHECK：保留旧 5 个 + 新增 9 个报价细分值
ALTER TABLE unit_price DROP CONSTRAINT IF EXISTS chk_unit_price_type;
ALTER TABLE unit_price ADD CONSTRAINT chk_unit_price_type
    CHECK (price_type IN (
        -- 旧大类（核价 PRICING 视图 + 报价 zcj_view 仍在用，保留）
        'ELEMENT', 'MATERIAL', 'COMPONENT', 'PART', 'CONSUMABLE',
        -- 报价导入新细分值（来料系）
        'INCOMING_MATERIAL_PROCESS', 'INCOMING_MATERIAL_OTHER',
        'INCOMING_MATERIAL_REDUCTION', 'INCOMING_MATERIAL_RECYCLE',
        -- 报价导入新细分值（自制 / 成品 / 组成件 / 电镀）
        'PROCESS', 'FINISHED_MATERIAL_OTHER',
        'COMPONENT_OTHER', 'COMPONENT_REDUCTION', 'PLATING'
    ));
