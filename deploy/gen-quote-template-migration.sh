#!/usr/bin/env bash
# =====================================================================================
#  task-260907 · B-11 —— 把「在取数配置器 UI 里配好的报价模板」导出成【幂等的 Flyway 迁移】
#
#  为什么要有这个脚本（D-20「可重放」）：
#  AC-7 的判据是「一个临时空库，只跑 Flyway，就应当存在 ≥1 张 PUBLISHED 报价模板，
#  其组件全部 builder_version IS NOT NULL，template_component 计数 = 11」。
#  🚫 不许靠「我手工在 UI 里配过了」通过 —— `deploy/cpq-init.sql` 当初不能靠 Flyway
#  重放，根因正是「costing_bom_tree_config 等 UI 建的配置从未进迁移」。
#
#  用法：
#     ./gen-quote-template-migration.sh <TEMPLATE_UUID> <迁移版本号> [输出目录]
#  例：
#     ./gen-quote-template-migration.sh 99ff6aa4-…-4692b8 419
#
#  🚨 迁移版本号必须【实取共享库当前最大值 +1】，🚫 不许 ls 目录：
#     PGPASSWORD=… psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -tA \
#        -c "SELECT max(version::numeric) FROM flyway_schema_history WHERE version ~ '^[0-9]+$'"
#     （task-260902 实证：目录最大 V400 而共享库已到 V404，差 4 个号；
#       2026-09-07 本任务实测：目录最大 V417，共享库已到 V418 —— 同一现象又发生了一次。）
#
#  🚫 本脚本只【生成文件】，不执行任何写操作，也不把生成的迁移跑进任何库。
#     生成后由 Quarkus 启动时的 migrate-at-start 自动执行（不要手工 psql -f）。
#
#  ── 2026-09-07 两条口径更新（主线转达用户裁决）─────────────────────────────────
#  🔄 页签数 11 → 14 → 13（两次变更，以最后一次为准）：
#     · V418 落地后语义模型 13→17 节点、页签定义 13→16、去重锚点 12→14 ⇒ 一度定为 14
#     · D-34（2026-09-07）：`物料BOM` 单独推迟 —— 上游 `取数配置器补齐` 的 F-3（树契约边式产物）
#       代码未合 master（主线实查 SemanticCompiler.java 最新提交 32bc7a4b，全文 parent_no 命中 0），
#       现在配出的 BOM 树页签没有树契约、是坏的。其余 13 个是平铺页签，不需要树契约。
#     ⇒ **AC-7③ 当前期望值 = 13**；F-3 合并后补配第 14 个，另出增量迁移。
#     📌 本脚本的自检块用的是【源模板实际的 template_component 行数】，会自动适配；
#        这里写明是为了让人在生成后对得上 AC-7 的数字。
#
#  🚨 D-27 元素单价取数路径：「材质元素」页签的元素单价列必须走
#        f_customer_element_price(客户, 日期)
#     🚫 不许走 f_material_element_price。理由（主线实证 + 用户业务规则）：
#       · f_material_element_price 的 candidate_materials 只从 V6 的 material_bom_item
#         ∪ element_bom_item 取 ⇒ 新导入的 ds_ 料号【恒不在候选集】
#         （实测 CUST-0004 返回 21 个料号，ds_ 独有的 = 0）
#       · 其 realtime 分支本就是「候选料号 CROSS JOIN f_customer_element_price(...)」，
#         价与料号无关（105 行 = 21 料号 × 5 元素）
#       · 用户业务规则：「不会出现同一客户、同一元素、不同料号价格不同；
#         一张报价单中的元素价格是统一的」⇒ 料号维度对报价侧元素价本来就是冗余的
#     ⇒ 本脚本在生成的迁移里带一条【硬守卫】：模板里任何组件 SQL 一旦出现
#        f_material_element_price，迁移直接 RAISE EXCEPTION（见生成文件末尾自检块）。
#        漏绑/绑错的症状是「元素单价整列空」，与 repair-260830 的历史故障长得一模一样，
#        不拦住会白白浪费一轮误诊。
# =====================================================================================
set -euo pipefail

TPL_ID="${1:?用法: $0 <TEMPLATE_UUID> <迁移版本号> [输出目录]}"
VER="${2:?缺少迁移版本号（必须实取共享库 flyway_schema_history 最大值 + 1）}"
OUT_DIR="${3:-$(cd "$(dirname "$0")/.." && pwd)/cpq-backend/src/main/resources/db/migration}"

PGHOST="${PGHOST:-10.177.152.12}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-cpq_db_0724}"
export PGPASSWORD="${PGPASSWORD:-joii5231}"

OUT_FILE="${OUT_DIR}/V${VER}__task260907_quote_ds_template_seed.sql"

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -tA -v ON_ERROR_STOP=1 \
     -v tpl="$TPL_ID" <<'SQL' > "$OUT_FILE"
\pset footer off

-- ───────────────────────────────────────────────────────────────────────────────────
-- 幂等写法（本脚本的核心约定，改之前先读完这一段）
--
-- 1. component / template / template_component / component_sql_view 四张表
--    全部走 INSERT … ON CONFLICT (<真实唯一键>) DO NOTHING。
--    ⚠️ 各表能用的冲突目标【不一样】，实测（information_schema + pg_index）：
--       component            : PK(id) + UNIQUE(code)
--       component_sql_view   : PK(id) + UNIQUE(component_id, sql_view_name)
--       template             : 只有 PK(id)
--       template_component   : 只有 PK(id)          ← 没有 (template_id, component_id) 唯一键
--    ⇒ 后两张只能用 PK 做幂等 ⇒ 迁移里必须写【固定 UUID 字面量】，不许 gen_random_uuid()：
--       用随机 id 重放会插出重复行且不报错（template_component 会让同一个页签出现两次）。
-- 2. 迁移一律不带 created_by / customer_id 这类环境相关外键值 —— 空库里那些行不存在，
--    外键会直接让迁移失败。导出时统一置 NULL。
-- ───────────────────────────────────────────────────────────────────────────────────
SELECT '-- =====================================================================';
SELECT '-- task-260907 · S-5 报价模板种子（由 deploy/gen-quote-template-migration.sh 生成）';
SELECT '-- 源模板 id: ' || :'tpl';
SELECT '-- 生成时间: ' || now()::text;
SELECT '-- 🚫 手工编辑本文件前先想清楚：它已应用到共享库后，改名/改号/改内容都会让';
SELECT '--    所有人的服务启动失败（Flyway 按 checksum 对账）。';
SELECT '-- =====================================================================';
SELECT '';

-- ── 1. component（先于 component_sql_view 与 template_component）
SELECT format(
  'INSERT INTO component (id, directory_id, name, code, column_count, fields, formulas, status,'
  || ' created_at, updated_at, component_type, data_driver_path, row_key_fields, tree_config,'
  || ' bom_recursive_expand, excel_columns, tab_type, part_no_field, part_name_field, sort_field,'
  || ' element_code_field, element_price_field, element_currency_field)'
  || E'\nVALUES (%L::uuid, NULL, %L, %L, %s, %L::jsonb, %L::jsonb, %L, now(), now(), %L, %L,'
  || ' %L::jsonb, %L::jsonb, %L, %L::jsonb, %L, %L, %L, %L, %L, %L, %L)'
  || E'\nON CONFLICT (id) DO NOTHING;',
  c.id, c.name, c.code, c.column_count, c.fields, c.formulas, c.status,
  c.component_type, c.data_driver_path, c.row_key_fields, c.tree_config,
  c.bom_recursive_expand, c.excel_columns, c.tab_type, c.part_no_field, c.part_name_field,
  c.sort_field, c.element_code_field, c.element_price_field, c.element_currency_field)
FROM component c
WHERE c.id IN (SELECT component_id FROM template_component WHERE template_id = :'tpl'::uuid)
ORDER BY c.code;
SELECT '';

-- ── 2. component_sql_view（含 builder_config + builder_version —— AC-7② 的判据就是它非空）
SELECT format(
  'INSERT INTO component_sql_view (id, component_id, sql_view_name, sql_template, declared_columns,'
  || ' required_variables, scope, status, description, created_by, created_at, updated_at,'
  || ' builder_config, builder_version)'
  || E'\nVALUES (%L::uuid, %L::uuid, %L, %L, %L::jsonb, %L::text[], %L, %L, %L, NULL, now(), now(),'
  || ' %L::jsonb, %s)'
  || E'\nON CONFLICT (id) DO NOTHING;',
  v.id, v.component_id, v.sql_view_name, v.sql_template, v.declared_columns,
  v.required_variables, v.scope, v.status, v.description,
  v.builder_config, coalesce(v.builder_version::text, 'NULL'))
FROM component_sql_view v
WHERE v.component_id IN (SELECT component_id FROM template_component WHERE template_id = :'tpl'::uuid)
ORDER BY v.sql_view_name;
SELECT '';

-- ── 3. template
--    customer_id / category_id / created_by 一律置 NULL：空库里那些行不存在，带上去外键必炸。
SELECT format(
  'INSERT INTO template (id, template_series_id, name, version, category, description, usage_note,'
  || ' product_attributes, subtotal_formula, components_snapshot, status, created_by, published_at,'
  || ' created_at, updated_at, excel_view_config, customer_id, category_id, template_kind, formulas,'
  || ' is_default, referenced_variables, sql_views_snapshot, template_sql_views_snapshot)'
  || E'\nVALUES (%L::uuid, %L::uuid, %L, %L, %L, %L, %L, %L::jsonb, %L::jsonb, %L::jsonb, %L, NULL,'
  || ' now(), now(), now(), %L::jsonb, NULL, NULL, %L, %L::jsonb, %L, %L::jsonb, %L::jsonb, %L::jsonb)'
  || E'\nON CONFLICT (id) DO NOTHING;',
  t.id, t.template_series_id, t.name, t.version, t.category, t.description, t.usage_note,
  t.product_attributes, t.subtotal_formula, t.components_snapshot, t.status,
  t.excel_view_config, t.template_kind, t.formulas, t.is_default,
  t.referenced_variables, t.sql_views_snapshot, t.template_sql_views_snapshot)
FROM template t WHERE t.id = :'tpl'::uuid;
SELECT '';

-- ── 4. template_component（固定 id，🚫 不用 gen_random_uuid()，理由见文件头第 1 条）
SELECT format(
  'INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, created_at,'
  || ' preset_rows, formula_assignments, data_driver_path_override, fields_override)'
  || E'\nVALUES (%L::uuid, %L::uuid, %L::uuid, %L, %s, now(), %L::jsonb, %L::jsonb, %L, %L::jsonb)'
  || E'\nON CONFLICT (id) DO NOTHING;',
  tc.id, tc.template_id, tc.component_id, tc.tab_name, coalesce(tc.sort_order::text,'NULL'),
  tc.preset_rows, tc.formula_assignments, tc.data_driver_path_override, tc.fields_override)
FROM template_component tc WHERE tc.template_id = :'tpl'::uuid
ORDER BY tc.sort_order NULLS LAST;
SELECT '';

-- ── 5. 自检（迁移自己带，跑完立刻暴露「导出漏了 / 绑错了」而不是等到渲染时才发现）
--
-- 🚫 三条守卫【一次列全再抛】，不 fail-fast：
--    第一版写成三个独立 IF+RAISE，结果第一条报错就把后两条挡住了（实测：用罗克韦尔模板1
--    生成时，builder_version 守卫先炸，D-27 元素单价守卫压根没机会跑）。
--    与 Phase 1 校验「错误一次列全」同一纪律 —— 改一次重放一次太贵。
SELECT E'DO $$\nDECLARE\n  n int;\n  problems text := '''';\nBEGIN'
    -- ① template_component 计数（导出是否漏了页签）
    || E'\n  SELECT count(*) INTO n FROM template_component WHERE template_id = ' || quote_literal(:'tpl') || '::uuid;'
    || E'\n  IF n <> ' || (SELECT count(*) FROM template_component WHERE template_id = :'tpl'::uuid) || E' THEN'
    || E'\n    problems := problems || format(''[1] template_component 期望 %s，实得 %s; '', '
    || (SELECT count(*) FROM template_component WHERE template_id = :'tpl'::uuid) || E', n);'
    || E'\n  END IF;'
    -- ② AC-7②：组件视图必须全部由取数配置器生成
    || E'\n  SELECT count(*) INTO n FROM component_sql_view v'
    || E'\n   WHERE v.component_id IN (SELECT component_id FROM template_component WHERE template_id = '
    || quote_literal(:'tpl') || E'::uuid)'
    || E'\n     AND v.builder_version IS NULL;'
    || E'\n  IF n > 0 THEN'
    || E'\n    problems := problems || format(''[2] %s 个组件视图 builder_version 为空（AC-7② 要求全部由取数配置器生成）; '', n);'
    || E'\n  END IF;'
    -- ③ D-27：报价侧元素单价必须走 f_customer_element_price
    || E'\n  SELECT count(*) INTO n FROM component_sql_view v'
    || E'\n   WHERE v.component_id IN (SELECT component_id FROM template_component WHERE template_id = '
    || quote_literal(:'tpl') || E'::uuid)'
    || E'\n     AND v.sql_template ILIKE ''%f_material_element_price%'';'
    || E'\n  IF n > 0 THEN'
    || E'\n    problems := problems || format(''[3] %s 个组件视图仍在用 f_material_element_price，D-27 要求走 f_customer_element_price'
    || E'（前者的候选料号只从 V6 的 material_bom_item ∪ element_bom_item 取，新导入的 ds_ 料号恒不在候选集，症状是「元素单价整列空」）; '', n);'
    || E'\n  END IF;'
    || E'\n  IF problems <> '''' THEN'
    || E'\n    RAISE EXCEPTION ''task-260907 模板种子自检失败：%'', problems;'
    || E'\n  END IF;'
    -- 🚩 已知缺口（测试代理指出、主线采纳，暂不加守卫）：
    --    上面三条里的 [3] 只校验了【否定命题】「不得引用 f_material_element_price」。
    --    当组件【一个价格函数都不用】时（当前正是如此），这条守卫照样通过 ——
    --    判据：**守卫只写否定式，会在「功能整个缺失」时静默放行。**
    --    ⇒ 需要补一条肯定式守卫：「材质元素页签必须绑元素单价（走 f_customer_element_price）」。
    --    🚫 现在还不能加：元素单价列本身在等上游补语义节点（AC-8 阻塞中）。
    --       此刻加会让迁移直接 RAISE、所有人的服务起不来。节点到位后再加。
    || E'\n  -- TODO(AC-8 解封后补): 加肯定式守卫「材质元素页签必须绑 f_customer_element_price」——'
    || E'\n  --   现有 [3] 只拦「用错函数」，拦不住「两个函数都没用」。'
    || E'\nEND $$;';
SQL

echo "✅ 已生成: $OUT_FILE"
echo
echo "接下来必须做的三件事（🚫 不要跳）："
echo "  1. 打开文件肉眼过一遍：确认没有把 created_by / customer_id / category_id 之类的环境相关外键带出去"
echo "  2. 让 Quarkus 启动时自动跑 Flyway（🚫 不要手工 psql -f，会导致 checksum 对账不符）"
echo "  3. 按 AC-7 在【临时空库】上验：createdb 新库 → 只跑 Flyway → 查 template_component 计数"
echo "     🚫 不许在共享库 cpq_db_0724 上验，更不许为了验它去清库"
echo "     AC-7③ 期望：template_component = 13（D-34：物料BOM 推迟到上游 F-3 合并后补配）"
