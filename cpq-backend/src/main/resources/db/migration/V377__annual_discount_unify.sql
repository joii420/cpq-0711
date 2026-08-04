-- =====================================================================
-- V377: repair-0804 年降三 Sheet 落库统一（annual_discount 单表化）
-- ---------------------------------------------------------------------
-- 背景：「来料年降」「组装加工费年降」落 unit_price（price_type=
--   INCOMING_MATERIAL_REDUCTION / COMPONENT_REDUCTION），「年降系数」落
--   annual_discount 且无版本化/无 pending 隔离/无客户维度。三者业务同构，
--   本次收敛到单表 annual_discount，用 discount_type 区分。
--
-- 前提（2026-08-03 实测）：annual_discount 0 行；unit_price 两个退役
--   price_type 共 1 行；三条路径均无任何读取方（SQL 视图/组件/Java 皆 0）。
--   故无数据迁移，直接改结构 + 清退役存量。
--
-- 详见 docs/superpowers/specs/2026-08-03-annual-discount-unify-design.md
-- =====================================================================

-- ---- 1) 判别列：biz_type → discount_type，取值细化为三个 Sheet ----
ALTER TABLE annual_discount DROP CONSTRAINT IF EXISTS chk_annual_discount_biz_type;
ALTER TABLE annual_discount RENAME COLUMN biz_type TO discount_type;
ALTER TABLE annual_discount ALTER COLUMN discount_type TYPE VARCHAR(30);

-- 防御性映射：设计前提"annual_discount 0 行"实测针对 dev 库 cpq_db_0724 成立；
-- 共享测试库 cpq_db 存在历史测试夹具残留的旧枚举值(INCOMING/ASSEMBLY)，与新
-- 枚举值语义等价，做等价改名而非删除，避免下面新增 CHECK 因存量行报错，
-- 也让本迁移对任何环境的同类残留都健壮。
UPDATE annual_discount SET discount_type = 'INCOMING_MATERIAL' WHERE discount_type = 'INCOMING';
UPDATE annual_discount SET discount_type = 'ASSEMBLY_PROCESS'  WHERE discount_type = 'ASSEMBLY';

ALTER TABLE annual_discount ADD CONSTRAINT chk_annual_discount_type
    CHECK (discount_type IN ('INCOMING_MATERIAL', 'ASSEMBLY_PROCESS', 'FINISHED'));

COMMENT ON COLUMN annual_discount.discount_type IS
    'INCOMING_MATERIAL=来料年降 / ASSEMBLY_PROCESS=组装加工费年降 / FINISHED=年降系数(整单级)';

-- ---- 2) 新增列 ----
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS system_type VARCHAR(10) NOT NULL DEFAULT 'QUOTE';
ALTER TABLE annual_discount ALTER COLUMN system_type DROP DEFAULT;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS customer_no VARCHAR(20);
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS target_no   VARCHAR(30);
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS seq_no      INTEGER;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS version_no  VARCHAR(20) NOT NULL DEFAULT '2000';
ALTER TABLE annual_discount ALTER COLUMN version_no DROP DEFAULT;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS is_current  BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS pending_quotation_id UUID;
ALTER TABLE annual_discount ADD COLUMN IF NOT EXISTS pending_supersedes   UUID[];

COMMENT ON COLUMN annual_discount.target_no IS
    '年降挂载目标，语义由 discount_type 决定：INCOMING_MATERIAL=材质料号 / ASSEMBLY_PROCESS=工序编号 / FINISHED=NULL。名称不冗余存，由视图 JOIN material_recipe / process_master 取';

-- ---- 3) 删除无写入方无读取方的列 ----
ALTER TABLE annual_discount DROP COLUMN IF EXISTS discount_strategy;  -- 被 discount_type 取代
ALTER TABLE annual_discount DROP COLUMN IF EXISTS discount_base;      -- 建表至今从未写入

-- ---- 4) 唯一键重建（口径同 uq_unit_price：groupKey ∪ 版本列 ∪ 组内行区分列） ----
DROP INDEX IF EXISTS uq_annual_discount;
CREATE UNIQUE INDEX uq_annual_discount ON annual_discount(
    system_type,
    discount_type,
    material_no,
    COALESCE(customer_no, ''),
    COALESCE(target_no, ''),
    version_no,
    COALESCE(discount_order, 0)
);
COMMENT ON INDEX uq_annual_discount IS
    'V6 annual_discount 业务唯一键（7 维）；discount_order 为组内行区分列，其余为 groupKey + 版本列';

-- ---- 5) pending 部分索引（对齐 V349 给 7 张版本化表建的那批） ----
CREATE INDEX IF NOT EXISTS ix_annual_discount_pending
    ON annual_discount(pending_quotation_id) WHERE pending_quotation_id IS NOT NULL;

-- ---- 6) 清除 unit_price 中两个退役 price_type 的存量 ----
-- CHECK 约束 chk_unit_price_type 里保留这两个枚举值不动（动 CHECK 收益为零）
DELETE FROM unit_price WHERE price_type IN ('INCOMING_MATERIAL_REDUCTION', 'COMPONENT_REDUCTION');
