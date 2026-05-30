-- ============================================================
-- V276: 报价 Excel 导入"严格按方案落库"对齐 —— schema 侧改造
--
-- 背景：核查《报价系统Excel导入落库方案.md V3.0》发现以下方案字段在 V6 表中无对应列，
--       导致旧实现把它们折叠进 seq_no / 丢弃，无法"严格按方案"落到具名字段。
--       用户裁定：允许改库（真严格）。
--
--   §8 来料年降 / §15 组装加工费年降：年降顺序 → discount_order（unit_price 原无此列）
--   §13 组成件其他费用：           项次(要素) → item_seq（unit_price 原无此列）
--   §14 组装加工费：               项次 → capacity.seq_no（capacity 原无此列）
--   通用规则 D1：固定金额/比例"以 pricing_price 是否为空区分" —— pricing_price 原为 NOT NULL，
--       无法落空值；放开为可空，由 Handler 写 NULL 表达"非固定金额费用"。
--
-- 唯一键同步：年降顺序 / 要素项次属于业务维度，必须进 uq_unit_price，否则同 (code,
--       finished_material_no, cost_type, seq_no) 的多顺序/多要素行会互相覆盖。
-- ============================================================

-- ---- 1. unit_price 补具名列 ----
ALTER TABLE unit_price ADD COLUMN IF NOT EXISTS discount_order INTEGER;
ALTER TABLE unit_price ADD COLUMN IF NOT EXISTS item_seq        INTEGER;

COMMENT ON COLUMN unit_price.discount_order IS '年降顺序（§8 来料年降 / §15 组装年降）';
COMMENT ON COLUMN unit_price.item_seq        IS '要素项次（§13 组成件其他费用 项次(要素)）';

-- ---- 2. pricing_price 放开 NOT NULL（D1：以是否为空区分固定/比例费用） ----
ALTER TABLE unit_price ALTER COLUMN pricing_price DROP NOT NULL;

-- ---- 3. 重建 uq_unit_price，纳入 discount_order / item_seq（13 维） ----
-- ON CONFLICT 表达式列表必须与此处完全一致（见 UnitPriceWriter）。
DROP INDEX IF EXISTS uq_unit_price;

CREATE UNIQUE INDEX uq_unit_price ON unit_price(
    system_type,
    price_type,
    COALESCE(cost_type, ''),
    version_no,
    code,
    COALESCE(customer_no, ''),
    COALESCE(supplier_no, ''),
    COALESCE(finished_material_no, ''),
    COALESCE(operation_no, ''),
    COALESCE(seq_no, 0),
    COALESCE(discount_order, 0),
    COALESCE(item_seq, 0),
    COALESCE(effective_date, DATE '1900-01-01')
);

COMMENT ON INDEX uq_unit_price IS 'V6 unit_price 业务唯一键 (13 维，含 discount_order/item_seq)；ON CONFLICT 用同样表达式列表';

-- ---- 4. capacity 补 seq_no（§14 组装加工费 项次） ----
ALTER TABLE capacity ADD COLUMN IF NOT EXISTS seq_no INTEGER;
COMMENT ON COLUMN capacity.seq_no IS '项次（§14 组装加工费）';
