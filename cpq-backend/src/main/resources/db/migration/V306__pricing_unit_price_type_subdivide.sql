-- =====================================================================
-- V306: 核价系统 unit_price.price_type 细分化
-- ---------------------------------------------------------------------
-- 背景：核价导入（system_type=PRICING）原本 10 个费用 Sheet 都写大类 MATERIAL，
--   仅靠 cost_type 区分来源（其中 4 个动态 cost_type Sheet 无法靠固定值定位）。
--   本次把超载的 MATERIAL 桶按 Sheet 细分为 7 个新值，写入端废弃 MATERIAL；
--   ELEMENT(P01) / CONSUMABLE(P13) 已与 Sheet 1:1，保留不动。
--
--   设计：docs/superpowers/specs/2026-06-30-pricing-unit-price-source-enum-design.md
--   报价侧先例：V297（同范式：改写 price_type，cost_type 不动）
--
-- 范围说明：
--   - 仅放开"写新值"的 CHECK 约束。列 price_type 已是 VARCHAR(40)（V297 扩过），
--     最长新值 INCOMING_PROCESS=16 字符，无需再扩列宽。
--   - uq_unit_price 结构不含 price_type 的具体值，细分后唯一性只会更细，不新增冲突。
--   - 旧大类（ELEMENT/MATERIAL/COMPONENT/PART/CONSUMABLE）+ 报价侧 V297 的 9 个值
--     全部保留：存量行 / 报价 zcj_view / 其它仍可能引用。
--   - 存量数据不处理（不写回填脚本）；视图 sql_template 重写另行处理。
-- =====================================================================

ALTER TABLE unit_price DROP CONSTRAINT IF EXISTS chk_unit_price_type;
ALTER TABLE unit_price ADD CONSTRAINT chk_unit_price_type
    CHECK (price_type IN (
        -- 旧大类（存量 / 报价 zcj_view / 其它仍用，保留）
        'ELEMENT', 'MATERIAL', 'COMPONENT', 'PART', 'CONSUMABLE',
        -- 报价侧 V297 细分值（保留）
        'INCOMING_MATERIAL_PROCESS', 'INCOMING_MATERIAL_OTHER',
        'INCOMING_MATERIAL_REDUCTION', 'INCOMING_MATERIAL_RECYCLE',
        'PROCESS', 'FINISHED_MATERIAL_OTHER',
        'COMPONENT_OTHER', 'COMPONENT_REDUCTION', 'PLATING',
        -- 核价侧本次新增细分值（7 个）
        'MATERIAL_PRICE', 'PACKAGING', 'INCOMING_PROCESS', 'INCOMING_OTHER',
        'SELF_PROCESS', 'FINISHED_OTHER', 'OUTSOURCE_PROCESS'
    ));
