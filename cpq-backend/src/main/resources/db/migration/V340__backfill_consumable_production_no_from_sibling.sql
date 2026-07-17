-- V340__backfill_consumable_production_no_from_sibling.sql
-- 修复现有已导入的 生产耗材(unit_price price_type='CONSUMABLE') 里 production_no 为空的行
-- （配套 VersionedV6Writer 的 descriptor 升版继承改动：以后导入升版会自动从上一版继承，不再落空）。
--
-- 背景：P13 生产耗材导入 production_no 逐行直取文件「生产料号」cell；文件某行该格为空 → 落库空 →
--   核价单生产耗材页签显示「—」。production_no 是「销售料号(code)级」属性，同一 code 全行应一致。
-- 本迁移把空 production_no 从「同一 (system_type, code) 内非空的兄弟行」继承回来（首个非空，与 P06/
--   写入器继承同口径）。
--
-- 有意收窄到 price_type='CONSUMABLE'：其它 price_type(如 INCOMING_OTHER)存在 code 与 production_no
--   错配的历史脏数据(疑 task-0708 生产料号当 code 的污染)，不在本次范围、避免以错配值扩散。
-- 幂等：仅回填「本行空 且 同组有非空兄弟」的行；无匹配则整迁移 no-op(可安全重放)。

UPDATE unit_price up
   SET production_no = sib.production_no, updated_at = now()
FROM (
    SELECT DISTINCT ON (system_type, code) system_type, code, production_no
    FROM unit_price
    WHERE price_type = 'CONSUMABLE'
      AND production_no IS NOT NULL AND production_no <> ''
    ORDER BY system_type, code, production_no
) sib
WHERE up.price_type = 'CONSUMABLE'
  AND (up.production_no IS NULL OR up.production_no = '')
  AND up.system_type = sib.system_type
  AND up.code = sib.code;
