-- task-0717 Task 3 追加修复：V341 给 qt_view/wgj_view/jg_view 注入的
-- `left join material_recipe mr on mr.code = code` 里，右侧裸列名 `code` 在加了
-- `material_recipe`（同样有 code 列）之后与 `unit_price up` 的 `up.code` 产生二义，
-- 实测报 "column reference "code" is ambiguous"（原模板 `mm.material_no = code` 在只有
-- unit_price 一张表带 code 列时不受影响，加入 material_recipe 后才暴露）。
--
-- 修复：把右侧 `code` 显式限定为 `up.code`（这 3 个视图族的 material_master/material_recipe
-- join 上游都是 `from unit_price up`，别名固定为 up，recon 已核对）。仅改这一处，不再动
-- material_master join / COALESCE 名列（V341 那部分是对的，已实测非 qt/wgj/jg 分支的其余
-- 8 行渲染正确非空）。
--
-- 影响行（均已在 V341 里被写坏，本迁移逐条纠正）：
--   qt_view  id=5a5d1ea1-91df-483d-b11f-635ee53185b9
--   qt_view  id=eac40cd1-eb3b-4199-82d0-ae586b66fac0
--   wgj_view id=60a04c79-5e63-4c35-b159-958ebcb97b5e
--   wgj_view id=87bde8c9-883a-4dee-b304-81a147418131
--   jg_view  id=af64c4c7-2ff8-41f4-9883-16c86a576f3c
--   jg_view  id=39cd5200-6dc2-4491-b8cb-c1de498cac23
--
-- 幂等：WHERE sql_template LIKE 含旧的二义写法才替换；已修过的（不含该串）不会被误改。

UPDATE component_sql_view
SET sql_template = replace(sql_template,
      'left join material_recipe mr on mr.code = code',
      'left join material_recipe mr on mr.code = up.code')
WHERE id IN (
  '5a5d1ea1-91df-483d-b11f-635ee53185b9',
  'eac40cd1-eb3b-4199-82d0-ae586b66fac0',
  '60a04c79-5e63-4c35-b159-958ebcb97b5e',
  '87bde8c9-883a-4dee-b304-81a147418131',
  'af64c4c7-2ff8-41f4-9883-16c86a576f3c',
  '39cd5200-6dc2-4491-b8cb-c1de498cac23'
)
AND sql_template LIKE '%left join material_recipe mr on mr.code = code%';
