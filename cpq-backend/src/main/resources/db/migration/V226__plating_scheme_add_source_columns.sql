-- ============================================================
-- V226: plating_scheme 补 source_url / source_name / fetch_rule 三列
-- 方案文档 §16 报价电镀方案比核价多出元素单价来源网站、抓取规则三列
-- V220 建表时漏；Q16 Handler INSERT 已含这 3 列
-- ============================================================

ALTER TABLE plating_scheme ADD COLUMN IF NOT EXISTS source_url  VARCHAR(500);
ALTER TABLE plating_scheme ADD COLUMN IF NOT EXISTS source_name VARCHAR(100);
ALTER TABLE plating_scheme ADD COLUMN IF NOT EXISTS fetch_rule  VARCHAR(200);
