-- task-0813 (裁决补漏): element_price_strategy.factor 是定价乘数系数
-- (StrategyService: 原始价 * factor + premium, factor > 0 校验), 与 unit_price.cost_ratio /
-- capacity.cost_ratio 同族(族 B 含量·占比·比率), V386 §3.2 勘察遗漏了这一列, 本迁移补齐。
-- 目标 precision = 原 10 - 原 scale 4 + 12 = 18, 整数位 6 不变。
-- 与 V386 一样: 只 ALTER TYPE, 不重算、不截断历史值。

ALTER TABLE element_price_strategy
    ALTER COLUMN factor TYPE numeric(18,12);
