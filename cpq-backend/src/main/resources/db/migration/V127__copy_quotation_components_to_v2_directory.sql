-- V127: 把"报价模板组件"目录下所有组件复制一份到新目录"报价模板组件V2"
--
-- 用途: 在不影响现有报价模板组件的前提下,提供一个 V2 副本用于演进 / 试验.
--
-- 实现:
--   1. 新建目录「报价模板组件V2」(若已存在则跳过)
--   2. 把原目录下所有 ACTIVE 组件 INSERT 一份到新目录, code 加 `-V2` 后缀保唯一
--   3. fields / formulas / data_driver_path / component_type / column_count 全部原样复制
--
-- 注意:
--   - 现有 PUBLISHED 模板仍指向 V1 组件, 不受影响.
--   - 若 V2 组件需要被模板引用, 后续单独迁移建模板.

DO $$
DECLARE
    v_src_dir UUID := 'c1d2e3f4-0001-4001-8001-000000000001'::uuid;  -- V116 创建的「报价模板组件」
    v_v2_dir  UUID;
    v_existing_v2 UUID;
    v_count_src INT;
    v_count_v2  INT;
    v_skip      INT;
BEGIN
    -- ── 1. 防御: 源目录必须存在 ──────────────────────────────────────────
    IF NOT EXISTS (SELECT 1 FROM component_directory WHERE id = v_src_dir) THEN
        RAISE EXCEPTION 'V127: 源目录「报价模板组件」(%) 不存在, 终止', v_src_dir;
    END IF;

    -- ── 2. 找/建 V2 目录 ─────────────────────────────────────────────────
    SELECT id INTO v_existing_v2 FROM component_directory WHERE name = '报价模板组件V2';
    IF v_existing_v2 IS NULL THEN
        v_v2_dir := gen_random_uuid();
        INSERT INTO component_directory (id, parent_id, name, sort_order, created_at)
        VALUES (v_v2_dir, NULL, '报价模板组件V2', 81, now());
        RAISE NOTICE 'V127: 已新建目录「报价模板组件V2」 id=%', v_v2_dir;
    ELSE
        v_v2_dir := v_existing_v2;
        RAISE NOTICE 'V127: 目录「报价模板组件V2」已存在, id=%, 复用并跳过已存在组件', v_v2_dir;
    END IF;

    -- ── 3. 复制组件 ── 跳过已经存在 V2 副本(code 后缀 -V2)的, 防重复 ──
    INSERT INTO component (
        id, directory_id, name, code, column_count, fields, formulas, status,
        component_type, data_driver_path, created_at, updated_at
    )
    SELECT
        gen_random_uuid(),
        v_v2_dir,
        c.name,                          -- 名称不变
        c.code || '-V2',                 -- code 加后缀保唯一
        c.column_count,
        c.fields,
        c.formulas,
        c.status,
        c.component_type,
        c.data_driver_path,
        now(), now()
    FROM component c
    WHERE c.directory_id = v_src_dir
      AND c.status = 'ACTIVE'
      AND NOT EXISTS (
            SELECT 1 FROM component c2
             WHERE c2.code = c.code || '-V2'
      );

    -- ── 4. 验证报告 ──────────────────────────────────────────────────────
    SELECT COUNT(*) INTO v_count_src FROM component WHERE directory_id = v_src_dir AND status = 'ACTIVE';
    SELECT COUNT(*) INTO v_count_v2  FROM component WHERE directory_id = v_v2_dir;
    v_skip := v_count_src - v_count_v2;
    RAISE NOTICE 'V127 完成 ─────────────────────────────────';
    RAISE NOTICE '  源目录「报价模板组件」组件数 = %', v_count_src;
    RAISE NOTICE '  新目录「报价模板组件V2」组件数 = %', v_count_v2;
    IF v_skip <> 0 THEN
        RAISE NOTICE '  (差额 % 项可能因 V2 副本已存在被跳过)', v_skip;
    END IF;
END $$;
