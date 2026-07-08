-- V312__industry_dictionary.sql
-- 行业字典 + 客户所属行业结构化（选配模板方案 Plan 1）
-- 旧 customer.industry 自由文本列保留作过渡，本迁移不删（spec §3.1）。

CREATE TABLE industry (
    id          UUID PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version     INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

ALTER TABLE customer ADD COLUMN IF NOT EXISTS industry_code VARCHAR(50);

-- 存量回填：每个非空 distinct 自由文本行业 → 建一条字典（code=name=去空白文本）
INSERT INTO industry (id, code, name)
SELECT gen_random_uuid(), TRIM(industry), TRIM(industry)
FROM (SELECT DISTINCT TRIM(industry) AS industry FROM customer
      WHERE industry IS NOT NULL AND TRIM(industry) <> '') t
ON CONFLICT (code) DO NOTHING;

UPDATE customer
SET industry_code = TRIM(industry)
WHERE industry IS NOT NULL AND TRIM(industry) <> '';
