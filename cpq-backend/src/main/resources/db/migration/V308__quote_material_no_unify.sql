-- 报价料号统一 Spec 1 · 一次性 reset（用户决策：清空存量基础数据，功能落地后重导）
-- ① 清空存量（不分类/不收敛；核价侧也在重构，一并清）
DELETE FROM material_customer_map;

-- ② 加列
ALTER TABLE material_customer_map ADD COLUMN system_type   VARCHAR(20) NOT NULL DEFAULT 'QUOTE';
ALTER TABLE material_customer_map ALTER COLUMN system_type DROP DEFAULT;
ALTER TABLE material_customer_map ADD COLUMN production_no  VARCHAR(20);
ALTER TABLE material_customer_map ALTER COLUMN customer_product_no DROP NOT NULL;

-- ③ 索引：换掉旧唯一键
DROP INDEX IF EXISTS uq_material_customer_map;
CREATE UNIQUE INDEX uq_mcm_composite
  ON material_customer_map(system_type, material_no, customer_no, customer_product_no) NULLS NOT DISTINCT;
CREATE UNIQUE INDEX uq_mcm_quote_no
  ON material_customer_map(material_no) WHERE system_type='QUOTE';
CREATE UNIQUE INDEX uq_mcm_quote_cust_prod
  ON material_customer_map(system_type, customer_no, customer_product_no)
  WHERE system_type='QUOTE' AND customer_product_no IS NOT NULL;

-- ④ 发号表
CREATE TABLE quote_customer_code (
  customer_no VARCHAR(20) PRIMARY KEY,
  code        CHAR(4)     NOT NULL UNIQUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE SEQUENCE quote_customer_code_seq START 1;
CREATE TABLE quote_material_no_seq (
  customer_code CHAR(4) NOT NULL,
  year_month    CHAR(4) NOT NULL,
  last_serial   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (customer_code, year_month)
);
