-- task-260909 · B-4（AC-15 / AC-16）
-- 「从已有产品添加」抽屉收敛为单表 ds_quote_customer_part 后，读侧恒按 customer_no 过滤。
-- EXPLAIN 实测该表按 customer_no 过滤走全表 Seq Scan（当前 2678 行，0.68ms 尚可），
-- 但单客户已可返回 2662 行，且未来每个客户都是几千行量级 —— 提前建索引。
-- 幂等：IF NOT EXISTS。

CREATE INDEX IF NOT EXISTS idx_ds_quote_customer_part_customer_no
    ON ds_quote_customer_part (customer_no);
