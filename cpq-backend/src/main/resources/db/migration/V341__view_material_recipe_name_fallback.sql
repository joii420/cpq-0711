-- task-0717 Task 3: 报价(QUOTE)侧组件SQL视图 —— 材质料号(RECIPE, 不登记 material_master)兜底品名。
--
-- 背景：报价 V6 导入的投入/材质料号已改 RECIPE 模型（component_no 存 Excel 原始码、不登记
-- material_master，品名靠材质库 material_recipe，material_recipe.code == 材质料号/投入料号）。
-- component_sql_view 表（运行时视图，非 Flyway 管理）里部分视图仍 left join material_master
-- 单一取名，对 RECIPE 材料返 NULL 致料件列空白。本迁移仅给这些视图的 material_master join 后
-- 追加一条 left join material_recipe 兜底，并把名列改成 COALESCE(mm.name, mr.name)；
-- 不动任何 where / characteristic 过滤，不改 declared_columns（列签名零变）。
--
-- 范围：报价(QUOTE)侧 6 视图族/10 行 + 核价(PRICING)侧 4 视图族/4 行，合计 14 行（用户已拍板核价侧一并做）：
--   1. ll_view    id=80371920-111f-4dce-a7e5-089e00494c13 (来料 tab)                     join=mbi.component_no
--   2. ll_view    id=c2baad79-c90f-41f3-90ef-fbb8a889a945 (来料固定加工费 tab)            join=mbi.component_no
--   3. qt_view    id=5a5d1ea1-91df-483d-b11f-635ee53185b9 (其他费用/来料其他费用支)        join=code
--   4. qt_view    id=eac40cd1-eb3b-4199-82d0-ae586b66fac0 (其他费用/来料其他费用支)        join=code
--   5. wgj_view   id=60a04c79-5e63-4c35-b159-958ebcb97b5e (外购件)                        join=code
--   6. wgj_view   id=87bde8c9-883a-4dee-b304-81a147418131 (外购件)                        join=code
--   7. zpj_view   id=2c3bf611-b881-440e-8f3f-990594069d0f (组成件BOM, QUOTE 支)           join=mbt.component_no
--   8. jg_view    id=af64c4c7-2ff8-41f4-9883-16c86a576f3c (自制加工费)                    join=code
--   9. jg_view    id=39cd5200-6dc2-4491-b8cb-c1de498cac23 (自制加工费)                    join=code
--  10. ys_view    id=a2d7430a-697e-415a-a96e-b6faf78fd9f2 (元素 tab)                      join=ebi.material_no
--  11. pj_view    id=86ae0bfe-1ee2-4812-8835-c00ad0e5975d (核价BOM)                       join=mbt.component_no, system_type=PRICING
--  12. wl_bom_view id=b8848318-06e5-4fb6-afe4-1d0f7a13a3a8 (核价BOM)                      join=mbt.component_no, system_type=PRICING
--  13. z2         id=56eb9801-dd35-49e3-a083-bf51233debcd (核价BOM)                       join=mbt.component_no, system_type=PRICING
--  14. zpj_view   id=fa7c18e3-cad6-4f52-9625-2a5e04558720 (组成件BOM, PRICING 支)         join=mbt.component_no, system_type=PRICING
--
-- zpj_view 有两份同名副本：QUOTE 支(#7, characteristic='ASSEMBLY')与 PRICING 支(#14, 无 characteristic
-- 列、system_type='PRICING')。两段 UPDATE 均以 id= 精确锁定，另加互斥的文本守卫防误伤
-- （#7 要求 ILIKE 含 characteristic='ASSEMBLY'；#14 要求 NOT ILIKE 含 characteristic）。
--
-- 每段幂等：WHERE id=... AND sql_template NOT ILIKE '%material_recipe%'。
-- 名列替换用最小 token `mm.material_name`（每行模板内出现且仅出现一次，recon 已逐一核对）。

-- 1) ll_view / 80371920 —— 来料 tab
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = mbi.component_no',
        'left join material_master mm on mm.material_no = mbi.component_no' || chr(10) ||
        'left join material_recipe mr on mr.code = mbi.component_no'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = '80371920-111f-4dce-a7e5-089e00494c13'
  AND sql_view_name = 'll_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 2) ll_view / c2baad79 —— 来料固定加工费 tab
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = mbi.component_no',
        'left join material_master mm on mm.material_no = mbi.component_no' || chr(10) ||
        'left join material_recipe mr on mr.code = mbi.component_no'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = 'c2baad79-c90f-41f3-90ef-fbb8a889a945'
  AND sql_view_name = 'll_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 3) qt_view / 5a5d1ea1 —— 其他费用（仅"来料其他费用" INCOMING_MATERIAL_OTHER 支受影响；
--    "成品其他费用" FINISHED_MATERIAL_OTHER 支无 material_master join，不涉及）
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = code ',
        'left join material_master mm on mm.material_no = code ' || chr(10) ||
        'left join material_recipe mr on mr.code = code'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = '5a5d1ea1-91df-483d-b11f-635ee53185b9'
  AND sql_view_name = 'qt_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 4) qt_view / eac40cd1 —— 同上（副本）
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = code ',
        'left join material_master mm on mm.material_no = code ' || chr(10) ||
        'left join material_recipe mr on mr.code = code'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = 'eac40cd1-eb3b-4199-82d0-ae586b66fac0'
  AND sql_view_name = 'qt_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 5) wgj_view / 60a04c79 —— 外购件
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = code ',
        'left join material_master mm on mm.material_no = code ' || chr(10) ||
        'left join material_recipe mr on mr.code = code'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = '60a04c79-5e63-4c35-b159-958ebcb97b5e'
  AND sql_view_name = 'wgj_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 6) wgj_view / 87bde8c9 —— 同上（副本）
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = code ',
        'left join material_master mm on mm.material_no = code ' || chr(10) ||
        'left join material_recipe mr on mr.code = code'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = '87bde8c9-883a-4dee-b304-81a147418131'
  AND sql_view_name = 'wgj_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 7) zpj_view / 2c3bf611 —— 组成件BOM，仅 QUOTE 支（characteristic='ASSEMBLY'）；
--    PRICING 支 id=fa7c18e3-... 本次不动。
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = mbt.component_no',
        'left join material_master mm on mm.material_no = mbt.component_no' || chr(10) ||
        'left join material_recipe mr on mr.code = mbt.component_no'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = '2c3bf611-b881-440e-8f3f-990594069d0f'
  AND sql_view_name = 'zpj_view'
  AND sql_template ILIKE '%characteristic = ''ASSEMBLY''%'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 8) jg_view / af64c4c7 —— 自制加工费
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = code ',
        'left join material_master mm on mm.material_no = code ' || chr(10) ||
        'left join material_recipe mr on mr.code = code'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = 'af64c4c7-2ff8-41f4-9883-16c86a576f3c'
  AND sql_view_name = 'jg_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 9) jg_view / 39cd5200 —— 同上（副本）
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = code ',
        'left join material_master mm on mm.material_no = code ' || chr(10) ||
        'left join material_recipe mr on mr.code = code'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = '39cd5200-6dc2-4491-b8cb-c1de498cac23'
  AND sql_view_name = 'jg_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 10) ys_view / a2d7430a —— 元素 tab。注意模板内有两处 material_master join：
--     子查询内 `LEFT JOIN material_master mm ON mm.material_no = mbt.component_no`（大写，
--     作用域仅限子查询，未被最终 SELECT 引用）与外层
--     `left join material_master mm on mm.material_no = ebi.material_no`（小写，供最终
--     `mm.material_name` 列使用）。用小写 `left join` 精确匹配外层，不动子查询内那处。
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = ebi.material_no',
        'left join material_master mm on mm.material_no = ebi.material_no' || chr(10) ||
        'left join material_recipe mr on mr.code = ebi.material_no'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = 'a2d7430a-697e-415a-a96e-b6faf78fd9f2'
  AND sql_view_name = 'ys_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 11) pj_view / 86ae0bfe —— 核价BOM（system_type=PRICING, customer_no=_GLOBAL_）
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = mbt.component_no',
        'left join material_master mm on mm.material_no = mbt.component_no' || chr(10) ||
        'left join material_recipe mr on mr.code = mbt.component_no'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = '86ae0bfe-1ee2-4812-8835-c00ad0e5975d'
  AND sql_view_name = 'pj_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 12) wl_bom_view / b8848318 —— 核价BOM
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = mbt.component_no',
        'left join material_master mm on mm.material_no = mbt.component_no' || chr(10) ||
        'left join material_recipe mr on mr.code = mbt.component_no'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = 'b8848318-06e5-4fb6-afe4-1d0f7a13a3a8'
  AND sql_view_name = 'wl_bom_view'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 13) z2 / 56eb9801 —— 核价BOM
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = mbt.component_no',
        'left join material_master mm on mm.material_no = mbt.component_no' || chr(10) ||
        'left join material_recipe mr on mr.code = mbt.component_no'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = '56eb9801-dd35-49e3-a083-bf51233debcd'
  AND sql_view_name = 'z2'
  AND sql_template NOT ILIKE '%material_recipe%';

-- 14) zpj_view / fa7c18e3 —— 组成件BOM，仅 PRICING 支（无 characteristic 列，system_type='PRICING'）；
--     QUOTE 支 id=2c3bf611-...（见 #7）已单独处理，两段互斥守卫防误伤。
UPDATE component_sql_view
SET sql_template = replace(
      replace(sql_template,
        'left join material_master mm on mm.material_no = mbt.component_no',
        'left join material_master mm on mm.material_no = mbt.component_no' || chr(10) ||
        'left join material_recipe mr on mr.code = mbt.component_no'),
      'mm.material_name',
      'coalesce(mm.material_name, mr.name)')
WHERE id = 'fa7c18e3-cad6-4f52-9625-2a5e04558720'
  AND sql_view_name = 'zpj_view'
  AND sql_template NOT ILIKE '%characteristic%'
  AND sql_template NOT ILIKE '%material_recipe%';
