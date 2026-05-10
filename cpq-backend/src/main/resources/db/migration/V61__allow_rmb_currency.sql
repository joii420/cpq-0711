-- V61: 把 RMB 加入 allowed_currencies(国内通用记法,与 CNY 同义)
-- 配合 V60 的元数据修复,消除 BV-15 "RMB 不在允许列表" 的误报。
-- v2 可考虑在 BV-15 校验前做 RMB→CNY 归一化,v1 简化:扩展字典即可。

UPDATE system_config
SET config_value = '["USD","CNY","EUR","HKD","JPY","RMB"]',
    default_value = '["USD","CNY","EUR","HKD","JPY","RMB"]',
    updated_at = NOW()
WHERE config_key = 'validation.allowed_currencies';
