-- V401 (task-260902 · B-16): 客户产品编号 ↔ 销售料号映射表（多对一）
--
-- 背景（AC-12 / AC-12b）：选配第一步输入的「客户产品编号」需要与本次选配铸出/复用的销售料号
-- 建立映射，使「从产品库添加」能按编号找回选配产品。
--
-- 🚫 为什么不复用 material_customer_map（影响面调查结论，方案甲，用户裁决 2026-09-02）：
--   ① uq_mcm_quote_no UNIQUE(material_no) WHERE system_type='QUOTE' 同时是 upsertQuote 的
--      ON CONFLICT target —— DROP 掉语句直接报错，报价导入 Q02 全线挂；
--   ② 该索引末行的客户守卫是**跨客户串号检测**的载体（森萨塔事故防线）；
--   ③ 4 个组件 SQL 视图的 JOIN 不含 customer_product_no 维度 ⇒ 一料号两编号会返 2 行 → 重复渲染。
-- ⇒ 本迁移**纯新增表 + 新增索引**，不 DROP / 不改动任何既有对象。
--
-- 键设计：
--   UNIQUE (customer_no, customer_product_no) ⇒ 编号在同一客户下唯一（AC-2）；并发下由该索引
--     而非前置 SELECT 保证正确性（AC-24，后端捕获 23505 → 409 CUSTOMER_PRODUCT_NO_TAKEN）。
--   INDEX  (quote_part_no) ⇒ quote_part_no 不唯一，一个销售料号可对多个客户产品编号（AC-12b）。

CREATE TABLE IF NOT EXISTS sel_product_no (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_no           varchar(20)  NOT NULL,
    customer_product_no   varchar(100) NOT NULL,
    customer_product_name varchar(200),
    quote_part_no         varchar(50)  NOT NULL,
    quotation_id          uuid,
    created_at            timestamptz  NOT NULL DEFAULT NOW(),
    updated_at            timestamptz  NOT NULL DEFAULT NOW(),
    created_by            uuid,
    updated_by            uuid
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_spn_cust_prod
    ON sel_product_no (customer_no, customer_product_no);

CREATE INDEX IF NOT EXISTS idx_spn_part_no
    ON sel_product_no (quote_part_no);

COMMENT ON TABLE  sel_product_no IS
    'task-260902 B-16：客户产品编号 → 销售料号映射（多对一）。选配提交成功后写一行；'
    '「从产品库添加」列表 = material_customer_map(导入来的) UNION sel_product_no(选配来的)。';
COMMENT ON COLUMN sel_product_no.quote_part_no IS
    '销售料号（多对一的「一」侧）；复用命中同一料号时仍各写一行，故本列**不唯一**（AC-12b）。';
