-- V283: composite_child_materials_mirror 启用 characteristic IS NULL 过滤（配合 B1 MATERIAL 组）
--
-- 背景：原模板把 `AND asy.characteristic IS NULL` 注释掉 → 对 COMBO 返回所有 characteristic 的行
--   （含 ASSEMBLY 子配件行）。B1（ConfigureProductService.writeCombomaterialBomV6）给 COMBO 新增
--   characteristic=NULL 的「各子件材质自指」行后，若不启用该过滤，材质 Tab 会同时返回 NULL 行 + ASSEMBLY
--   行 = 每子件重复（2N 行）。
-- 启用后：材质 Tab 只看 characteristic=NULL 行（COMBO=各子件材质名；SIMPLE=自身材质自指行，兼容不变）；
--   子配件 Tab 走独立视图 $zcj_bom（characteristic=ASSEMBLY），不受影响。
-- 机制：把被注释的锚点 `--   AND asy.characteristic IS NULL` 改为生效 `  AND asy.characteristic IS NULL`。
-- 部署：落 db/migration/ 后 touch java 触发 Quarkus 重启 → Flyway + BnfTableMetaSyncer 重新同步（清缓存）。

UPDATE component_sql_view
SET sql_template = regexp_replace(sql_template,
                     '--\s*AND\s+asy\.characteristic\s+IS\s+NULL',
                     '  AND asy.characteristic IS NULL', 'g'),
    updated_at = NOW()
WHERE sql_view_name = 'composite_child_materials_mirror';
