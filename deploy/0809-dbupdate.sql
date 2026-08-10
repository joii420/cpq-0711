-- ============================================================
-- CPQ 内网数据库增量更新 · 2026-08-09
-- ------------------------------------------------------------
-- 适用对象: 已跑过 deploy/0804-dbupdate.sql(结构停在 Flyway V378)的内网库。
--           本脚本把**表结构**补齐到 V384。
--           若内网库是用 2026-08-09 之后版本的 deploy/cpq-init-empty-navicat.sql
--           新建的(基线已是 384), **不需要**跑本脚本。
--
-- 覆盖范围: Flyway V379~V384。其中:
--           · 结构变更(必跑): 建表 1 张 / 加列 8 个 / 改列默认值 1 处 / 改 CHECK 约束 1 处 /
--                             建索引 6 个 / 建唯一约束 1 个 / 建外键 1 个 —— 第 1~5 节
--           · 数据变更(3 处, 逐条在节内说明必要性与影响): 第 1 节存量停用、
--             第 3 节存量快照清空(⚠️ 影响最大, 必须与后端同版本上线)、第 6 节可选回填
--
-- 🚨 上线顺序(本次与 0804 不同, 务必先读):
--     第 3 节的 `UPDATE template SET components_snapshot = NULL` 会把所有已发布/已归档
--     模板置为"未冻结"态。**旧版后端读到 NULL 会当成"模板从未发布"**, 报价单渲染会异常。
--     因此本脚本(至少第 3 节)必须与 task-0806 后端新版本**同一个停机窗口**内完成:
--       ① 停旧后端 → ② 跑本脚本 → ③ 起新后端
--       → ④ 管理员对每个在用的已发布模板调 `POST /api/cpq/templates/{id}/freeze` 就地补冻
--     ⚠️ ④ **不是"重新发布"**: `publish()` 只收 DRAFT, 已发布模板没有"重新发布"这个操作
--        (createNewDraft→publish 只会产出新版本, 老版本永远冻不上)。补冻端点 freeze 不改
--        version/status/publishedAt, 只补快照, 且仅对零快照模板可用(否则 409),
--        鉴权 SALES_MANAGER/SYSTEM_ADMIN, 界面上无按钮, 需管理员调接口。
--     ④ 不做的话, 打开这些模板的报价单会返 HTTP 409 + data.code=TEMPLATE_NOT_FROZEN
--     (这是 task-0806 的既定设计, 不是故障 —— 详见
--      dev-docs/task-0806-模板发布全量冻结/需求文档.md D16~D20)。
--
-- 幂等性: 第 1、2、4、5、6 节可安全重复执行。
--         ⚠️ 第 3 节的建表用 CREATE TABLE IF NOT EXISTS 可重复; 但其中的
--            `UPDATE template SET components_snapshot = NULL` 重复执行会把**重新发布过的**
--            模板再次打回未冻结态。故第 3 节跑过一次后, 后续如需重跑请只跑建表部分。
--
-- 明确不含: 业务配置数据同步(同 2026-08-04 口径)。V379 依赖 component 的
--           element_code_field / element_price_field / element_currency_field 三列有值,
--           而这三列的配置回填(V370)本就不在 0804 范围内 —— 内网这三列全为 NULL,
--           故 V379 在内网是天然 no-op。仍以第 6 节列出, 供将来同步了组件配置后补跑。
--
-- 说明: 全文不使用 DO $$ 匿名块, 幂等靠 IF NOT EXISTS / DROP IF EXISTS 实现;
--       与 0804-dbupdate.sql 及建库脚本一致, 不写 COMMENT ON(注释无运行时语义)。
--
-- 执行前建议: 先备份库(至少备份 template、material_price_* 系列表)。
-- Navicat 导入: 右键库 -> 运行 SQL 文件 -> 选本文件 -> 勾选"遇到错误时停止"。
-- ============================================================


-- ============================================================
-- 执行前自检(先单独跑这几条, 确认目标库确实处于 V378 状态再执行下文)
-- ============================================================
-- 1) 确认基线/历史停在 378(应返回 378)
--    SELECT max(version::int) FROM flyway_schema_history WHERE success;
--
-- 2) 确认待建的表尚不存在(应返回 0)
--    SELECT count(*) FROM information_schema.tables
--     WHERE table_schema='public' AND table_name='template_component_snapshot';
--
-- 3) 确认待加的列尚不存在(应返回 0)
--    SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND (
--        (table_name='material_price_review'          AND column_name IN ('warn_code','warn_message','warn_diff'))
--     OR (table_name='material_price_update_job_item' AND column_name IN ('warn_code','warn_message'))
--     OR (table_name='material_price_update_job'      AND column_name = 'skipped_count')
--     OR (table_name='operation_log'                  AND column_name = 'details')
--     OR (table_name='price_adjust_settings'          AND column_name = 'subtotal_guard_enabled'));
--
-- 4) ⚠️ 关键: 看清第 3 节会影响多少张模板(这些模板跑完后都需要业务方重新发布)
--    SELECT status, count(*) FROM template
--     WHERE status IN ('PUBLISHED','ARCHIVED') AND components_snapshot IS NOT NULL
--     GROUP BY status;


-- ============================================================
-- 第 1 节 · V380: 客户价格调整策略「默认关闭」+ 存量全部关闭
-- ------------------------------------------------------------
-- 业务方口径: 客户的价格调整策略默认为关闭状态, 且存量也全部关闭。
-- 两个连带后果(业务方拍板时已知):
--   1) 定时扫描不再为该客户生成价格版本(PriceAdjustScheduledScanService 只扫 enabled=true);
--      手动生成版本 / 现存 PENDING 版本的审核链路不看 enabled, 不受影响。
--   2) 已锁定的单价列会在该客户各单**下次 saveDraft 时**惰性解锁(只撤 __priceLocked /
--      __priceVersion 标记, 不改业务值)。不在本脚本发生。
-- 存量 UPDATE 只动 enabled + updated_at, 周期/范围/阈值等配置完整保留 —— 这是"停用"不是"重置"。
-- 内网若尚未配置过任何客户调价策略(表为空), 下面的 UPDATE 影响 0 行, 属正常。
-- 可重复执行。
-- ============================================================

ALTER TABLE public.customer_price_adjust_strategy ALTER COLUMN enabled SET DEFAULT false;

UPDATE public.customer_price_adjust_strategy
   SET enabled = false,
       updated_at = now()
 WHERE enabled = true;


-- ============================================================
-- 第 2 节 · V381: L3 口径守卫「拦截 -> 告警」的落点列 + 索引
-- ------------------------------------------------------------
-- 守卫不再阻断升版, 改为记 WARN 并落可查记录。刻意不新建独立告警表, 告警落在
-- 用户本来就在看的两行上: 预算预览走 material_price_review(屏 4)、
-- 批次执行走 material_price_update_job_item(屏 7)。
-- 不复用 error_code/error_message: 现有语义是"error_code 非空 = 非成功态",
-- 复用会产出 status=SUCCESS 却带 error_code 的行, 把屏 7 的"可重试"判定带偏。
-- material_price_update_job_item 不加 warn_diff —— 该表早有语义相同的 diff_value 列。
-- 纯加列 + 部分索引, 可重复执行。
-- ============================================================

ALTER TABLE public.material_price_review
    ADD COLUMN IF NOT EXISTS warn_code    varchar(50),
    ADD COLUMN IF NOT EXISTS warn_message text,
    ADD COLUMN IF NOT EXISTS warn_diff    numeric(20,6);

ALTER TABLE public.material_price_update_job_item
    ADD COLUMN IF NOT EXISTS warn_code    varchar(50),
    ADD COLUMN IF NOT EXISTS warn_message text;

CREATE INDEX IF NOT EXISTS idx_mpr_warn_code
    ON public.material_price_review (warn_code, updated_at DESC)
    WHERE warn_code IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_mpuji_warn_code
    ON public.material_price_update_job_item (warn_code, updated_at DESC)
    WHERE warn_code IS NOT NULL;


-- ============================================================
-- 第 3 节 · V382: 模板发布全量冻结(task-0806)
-- ------------------------------------------------------------
-- 🚨 本节含全脚本影响最大的一条 UPDATE, 见 3.3。执行前请确认已读文件头的「上线顺序」。
--
-- 背景: 组件管理保存组件时会无条件刷新所有模板快照, 绕过了"已发布模板不受后续组件改动
--       影响"的架构基线; 且 component 的 18 个渲染配置字段里有 6 个从未进过任何快照。
--       本表把已发布模板每个页签的完整生效配置一次性冻结下来。
--
-- 3.1 新表 template_component_snapshot(建表即空表, 不回填存量)
--     唯一键取 (template_id, template_component_id) 而非 (template_id, sort_order) ——
--     实测存在同一模板内两个页签 sort_order 相同的历史数据。
--     template_component_id / component_id 刻意不建外键: 快照与活表脱钩,
--     组件被停用或删除不该动摇已发布模板。只有 template_id 建外键(级联删)。
-- ============================================================

CREATE TABLE IF NOT EXISTS public.template_component_snapshot (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id             uuid NOT NULL REFERENCES public.template(id) ON DELETE CASCADE,
    template_component_id   uuid NOT NULL,
    component_id            uuid NOT NULL,
    sort_order              integer NOT NULL,
    tab_name                varchar(200),
    preset_rows             jsonb        NOT NULL DEFAULT '[]',
    formula_assignments     jsonb        NOT NULL DEFAULT '{}',
    component_name          varchar(200),
    component_code          varchar(100),
    component_type          varchar(20)  NOT NULL DEFAULT 'NORMAL',
    column_count            integer      NOT NULL DEFAULT 0,
    fields                  jsonb        NOT NULL DEFAULT '[]',
    formulas                jsonb        NOT NULL DEFAULT '[]',
    excel_columns           jsonb        NOT NULL DEFAULT '[]',
    data_driver_path        text,
    tree_config             jsonb,
    bom_recursive_expand    boolean      NOT NULL DEFAULT false,
    tab_type                varchar(30),
    part_no_field           varchar(100),
    part_name_field         varchar(100),
    row_key_fields          jsonb,
    sort_field              varchar(120),
    element_code_field      varchar(100),
    element_price_field     varchar(100),
    element_currency_field  varchar(100),
    frozen_at               timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tcs_template_tc UNIQUE (template_id, template_component_id)
);

CREATE INDEX IF NOT EXISTS idx_tcs_template         ON public.template_component_snapshot (template_id);
CREATE INDEX IF NOT EXISTS idx_tcs_component        ON public.template_component_snapshot (component_id);
CREATE INDEX IF NOT EXISTS idx_tcs_template_tabtype ON public.template_component_snapshot (template_id, tab_type);
CREATE INDEX IF NOT EXISTS idx_tcs_template_driver  ON public.template_component_snapshot (template_id)
                                                    WHERE data_driver_path IS NOT NULL AND data_driver_path <> '';

-- ------------------------------------------------------------
-- 3.2 operation_log 加 details jsonb(加法式, 不影响既有写入方)
--     结构化 diff(改前改后), 供 admin 后门审计用。
-- ------------------------------------------------------------

ALTER TABLE public.operation_log ADD COLUMN IF NOT EXISTS details jsonb;

-- ------------------------------------------------------------
-- 3.3 🚨 存量: 已发布/已归档模板的旧 components_snapshot jsonb 一并清空
-- ------------------------------------------------------------
-- 为什么必须清: 新表起步是 0 行(不回填存量), 但 template.components_snapshot 里还留着
--   改造前最后一次发布产出的旧 jsonb。渲染链路有一批读取点直接读它、不经新网关:
--   见到非空 jsonb 就当"已发布", 用它搭出页签骨架, 再向新表(0 行)要内容 ——
--   产出「HTTP 200 + N 个页签但 0 数据 0 字段定义」的空壳, 而"请重新发布"的提示信号被架空,
--   用户看不到任何异常, 只会以为系统坏了。清空后"新表 0 行 + 本列 NULL"是唯一诚实、
--   且能被后端识别为"未冻结"的状态。
--   选 NULL 而非 '[]': 全仓库十余处判据本就把 NULL 当"尚未冻结"(草稿模板即是如此);
--   '[]' 会被误读成"已发布但恰好 0 个页签", 更危险。
--
-- 影响与善后: 跑完后所有已发布模板都处于"未冻结"态, 打开其报价单会返 409
--   TEMPLATE_NOT_FROZEN。恢复办法是管理员对每个在用模板调
--   `POST /api/cpq/templates/{id}/freeze`(SALES_MANAGER/SYSTEM_ADMIN, 界面无按钮),
--   它就地补快照、不改 version/status/publishedAt, 仅对零快照模板可用。
--   ⚠️ 不要理解成"重新发布" —— publish() 只收 DRAFT, 已发布模板走 createNewDraft→publish
--   只会产出新版本, 老版本永远冻不上。
--   已归档模板同样用 freeze; 此外新版后端的 archive() 发现无快照时会自动补冻一份。
--
-- ⚠️ 只跑一次。重复执行会把已经重新发布过的模板再次打回未冻结态。
-- ============================================================

UPDATE public.template SET components_snapshot = NULL WHERE status IN ('PUBLISHED', 'ARCHIVED');


-- ============================================================
-- 第 4 节 · V383: S0 口径守卫总开关(默认关闭)
-- ------------------------------------------------------------
-- 守卫每项要重算整卡做比对, 实测占升版耗时约 14%, 而 123 次里只响过 1 次;
-- 方向3 已把它从"拦截"改成"告警"后更趋于恒等。故改可配开关, 默认 false = 跳过,
-- 保留业务方随时打开的能力。服务层每次读库, 改完即时生效, 不需要重启。
-- 表是单行系统参数表(id=1), 加列后该行自动取默认值 false, 无需额外 UPDATE。
-- 纯加列, 可重复执行。
-- ============================================================

ALTER TABLE public.price_adjust_settings
    ADD COLUMN IF NOT EXISTS subtotal_guard_enabled boolean NOT NULL DEFAULT false;


-- ============================================================
-- 第 5 节 · V384: SKIPPED 成为 job item 独立终态
-- ------------------------------------------------------------
-- 原先被静默并入 SUCCESS, 掩盖了"该单一个字节都没被更新"这一事实
-- (实测 32/32 全成功的批次里确有单据被静默跳过)。
-- 新 CHECK 是旧 CHECK 的超集(仅多 'SKIPPED'), 存量数据必然通过。
-- 不回填存量: 历史被静默跳过的 item 现在记的是 SUCCESS, 不重跑无从判定。
-- DROP ... IF EXISTS 后重建, 可重复执行。
-- ============================================================

ALTER TABLE public.material_price_update_job_item DROP CONSTRAINT IF EXISTS chk_mpuji_status;
ALTER TABLE public.material_price_update_job_item ADD CONSTRAINT chk_mpuji_status
  CHECK (status::text = ANY (ARRAY[
    'WAITING','RUNNING','SUCCESS','FAILED','CONFLICT','STALE','SKIPPED'
  ]::text[]));

ALTER TABLE public.material_price_update_job
  ADD COLUMN IF NOT EXISTS skipped_count integer NOT NULL DEFAULT 0;


-- ============================================================
-- 第 6 节 · V379(可选): 存量 quotation_view_structure 回填元素角色字段
-- ------------------------------------------------------------
-- ⚠️ 内网当前跑它是 no-op, 可以跳过。原因: 它的数据来源是 component 表的
--    element_code_field / element_price_field / element_currency_field 三列, 而这三列的
--    配置回填(V370)属于业务配置同步, 不在 0804 / 本脚本范围内 —— 内网这三列全是 NULL,
--    WHERE 子句一行都匹配不到。等将来同步了组件配置, 再回来补跑本节即可(幂等)。
--
-- 作用: 价格锁定徽标依赖冻结结构里的三个角色 key, 而 quotation_view_structure 是
--       "创建即冻、永不重建"的 —— 功能上线前建的单结构里根本没有这三个 key,
--       导致老单的编辑页/详情页不出徽标。补的是"当时就该有、只因功能未上线而缺失"的字段,
--       不是修改业务数据, 冻结语义不破。
-- 三条安全保证: ① 只补缺失不覆盖(合并写成 patch || tab, tab 在右, 同名 key 以现有值为准);
--               ② 除三个角色 key 外一律不动(jsonb_set 只替换 '{tabs}' 路径);
--               ③ 页签顺序不变(WITH ORDINALITY + jsonb_agg ... ORDER BY)。
-- 范围: 仅 QUOTE_CARD。COSTING_CARD 有同样缺口但有意不含, 等业务方就"核价侧是否也要
--       徽标"拍板后另行处理。
-- 可重复执行。
-- ============================================================

UPDATE quotation_view_structure s
SET structure = jsonb_set(s.structure, '{tabs}', rebuilt.new_tabs)
FROM (
    SELECT s2.id,
           jsonb_agg(
               CASE
                   WHEN c.element_price_field IS NOT NULL
                       THEN jsonb_strip_nulls(jsonb_build_object(
                                'elementCodeField',     c.element_code_field,
                                'elementPriceField',    c.element_price_field,
                                'elementCurrencyField', c.element_currency_field
                            )) || t.tab
                   ELSE t.tab
               END
               ORDER BY t.ord
           ) AS new_tabs
    FROM quotation_view_structure s2
    CROSS JOIN LATERAL jsonb_array_elements(s2.structure -> 'tabs') WITH ORDINALITY AS t(tab, ord)
    -- c.id::text = ... 而不是 (...)::uuid = c.id: componentId 可能是空串/脏值,
    -- 反向 cast 会在整表上抛 invalid input syntax for type uuid, text 比较则安全跳过
    LEFT JOIN component c ON c.id::text = t.tab ->> 'componentId'
    WHERE s2.view_kind = 'QUOTE_CARD'
      AND jsonb_typeof(s2.structure -> 'tabs') = 'array'
    GROUP BY s2.id
) rebuilt
WHERE s.id = rebuilt.id
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(s.structure -> 'tabs') AS t2(tab)
      JOIN component c2 ON c2.id::text = t2.tab ->> 'componentId'
      WHERE c2.element_price_field IS NOT NULL
        AND NOT (t2.tab ? 'elementPriceField')
  );


-- ============================================================
-- 第 7 节 · Flyway 基线上调(仅当该库将来会连 Quarkus 时才需要)
-- ------------------------------------------------------------
-- ⚠️ 这是 0804-dbupdate.sql 遗留的坑, 本次一并补上:
--    增量脚本只改结构、不动 flyway_schema_history, 于是"结构已到 V384、基线行还写着
--    362 或 378"。纯 Navicat 运维的内网库无所谓; 但只要哪天这个库连上 Quarkus,
--    Flyway 就会重放 V363~V384, 撞上 V368/V382 的 CREATE TABLE(无 IF NOT EXISTS)、
--    V377 的 RENAME COLUMN、V384 的 DROP CONSTRAINT 而**启动失败**。
--    把基线行推到 384 即可让 Flyway 跳过这段历史。
--
-- 本节放在最后一节, 是因为极少数内网库可能根本没有 flyway_schema_history 表 ——
-- 那样这条会报 relation does not exist。**报错就跳过, 不影响前 6 节已生效的成果。**
--
-- 只改 BASELINE 那一行且只在其低于 384 时改, 可重复执行。
-- ============================================================

UPDATE public.flyway_schema_history
   SET version = '384',
       description = '<< Flyway Baseline >>',
       script = '<< Flyway Baseline >>'
 WHERE type = 'BASELINE'
   AND version IS NOT NULL
   AND version::int < 384;


-- ============================================================
-- 执行后自检(逐条核对期望值)
-- ============================================================
-- 1) 新表存在且为空(期望 1 / 0)
--    SELECT count(*) FROM information_schema.tables
--     WHERE table_schema='public' AND table_name='template_component_snapshot';
--    SELECT count(*) FROM template_component_snapshot;
--
-- 2) 8 个新列全部到位(期望 8)
--    SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND (
--        (table_name='material_price_review'          AND column_name IN ('warn_code','warn_message','warn_diff'))
--     OR (table_name='material_price_update_job_item' AND column_name IN ('warn_code','warn_message'))
--     OR (table_name='material_price_update_job'      AND column_name = 'skipped_count')
--     OR (table_name='operation_log'                  AND column_name = 'details')
--     OR (table_name='price_adjust_settings'          AND column_name = 'subtotal_guard_enabled'));
--
-- 3) 6 个新索引 + 1 个唯一约束(期望 6)
--    SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname IN
--    ('idx_mpr_warn_code','idx_mpuji_warn_code','idx_tcs_template','idx_tcs_component',
--     'idx_tcs_template_tabtype','idx_tcs_template_driver');
--
-- 4) CHECK 约束已含 SKIPPED(期望返回的定义里出现 'SKIPPED')
--    SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='chk_mpuji_status';
--
-- 5) 调价策略默认值与存量(期望 false / 0)
--    SELECT column_default FROM information_schema.columns
--     WHERE table_name='customer_price_adjust_strategy' AND column_name='enabled';
--    SELECT count(*) FROM customer_price_adjust_strategy WHERE enabled;
--
-- 6) 守卫开关默认关闭(期望 1 行, f)
--    SELECT id, subtotal_guard_enabled FROM price_adjust_settings;
--
-- 7) 🚨 模板已全部置为未冻结(期望 0) —— 接下来请管理员逐个调 POST /templates/{id}/freeze 补冻
--    SELECT count(*) FROM template
--     WHERE status IN ('PUBLISHED','ARCHIVED') AND components_snapshot IS NOT NULL;
--    补冻进度可查(期望最终每个在用模板都有行):
--    SELECT t.id, t.name, t.status, count(s.id) AS 快照页签数
--      FROM template t LEFT JOIN template_component_snapshot s ON s.template_id = t.id
--     WHERE t.status IN ('PUBLISHED','ARCHIVED') GROUP BY 1,2,3 ORDER BY 4, 2;
--
-- 8) Flyway 基线已推到 384(期望 384; 若第 7 节因无该表而跳过, 本条也查不到, 属正常)
--    SELECT version FROM flyway_schema_history WHERE type='BASELINE';
-- ============================================================
