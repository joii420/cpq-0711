-- 待批-V401__drop_legacy_recipe_element_constraints.sql
-- task-260901 · B-3：元素行归属彻底切到配置层，清理旧「材质维度」契约。
--
-- 🚨🚨 本文件<b>不在</b> cpq-backend/src/main/resources/db/migration/ 下，因此 Flyway 不会执行它。
--      它含 CLAUDE.md §3.2 红线操作（契约销毁 + 数据销毁），子代理无批准权。
--      用户批准后，由主线 `git mv` 进 db/migration/ 并在移入前重新核对版本号（共享库的
--      Flyway 历史是并发移动靶；本文写作时 master 与两库最高版本是 V400（本任务的建表迁移，主线 2026-09-02 由 V399 改号让位给并发会话）。
--
-- ── 红线三步走所需材料 ─────────────────────────────────────────
-- ① 影响面（V400 已应用后，两个共享库实测；口径 = 「还有多少元素行没挂上配置」）：
--      SELECT count(*) FROM material_recipe_element WHERE config_id IS NULL;   -- 必须 = 0 才允许执行
--    另附：
--      SELECT count(*) FROM material_recipe_element;                            -- 受 DROP COLUMN 影响的行数
--      SELECT count(*) FROM material_recipe_element WHERE recipe_id IS NULL;    -- V400 之后新增的行（本就不写 recipe_id）
--
-- ② 可恢复性：**信息零丢失，可完整重建**。
--    recipe_id 的全部信息都能由 config_id 推导：
--      ALTER TABLE material_recipe_element ADD COLUMN recipe_id uuid;
--      UPDATE material_recipe_element mre SET recipe_id = c.recipe_id
--        FROM material_recipe_config c WHERE c.id = mre.config_id;
--    唯一键 uq_recipe_element(recipe_id, element_code) 同样可重建
--    （其唯一性由 V400 建立的 uq_config_element 在配置维度上更严格地承担；
--     ⚠️ 注意 uq_config_element 已在 V400 建好，本文件只做存在性兜底）。
--
-- ③ 用户明确批准本次（批准不跨操作、不跨会话）。
-- ────────────────────────────────────────────────────────────

-- 前置硬闸：残留未挂配置的元素行时直接失败，绝不带着脏数据往下走。
DO $$
DECLARE v_null_cfg bigint;
BEGIN
  SELECT count(*) INTO v_null_cfg FROM material_recipe_element WHERE config_id IS NULL;
  IF v_null_cfg <> 0 THEN
    RAISE EXCEPTION 'V401 前置校验失败：material_recipe_element.config_id 仍有 % 行为 NULL，禁止 DROP', v_null_cfg;
  END IF;
END $$;

-- 1) config_id 转必填
ALTER TABLE material_recipe_element ALTER COLUMN config_id SET NOT NULL;

-- 2) 唯一键存在性兜底（V400 已建；此处仅为「单独重放本文件」的场景兜底）
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_config_element') THEN
    ALTER TABLE material_recipe_element ADD CONSTRAINT uq_config_element UNIQUE (config_id, element_code);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_mre_config') THEN
    ALTER TABLE material_recipe_element ADD CONSTRAINT fk_mre_config FOREIGN KEY (config_id)
      REFERENCES material_recipe_config(id) ON DELETE CASCADE;
  END IF;
END $$;

-- 3) 🚨 红线：删除旧材质维度唯一键（契约销毁）
ALTER TABLE material_recipe_element DROP CONSTRAINT IF EXISTS uq_recipe_element;

-- 4) 🚨 红线：删除旧材质维度外键与列（数据销毁 —— 可由 ② 的脚本完整重建）
ALTER TABLE material_recipe_element DROP COLUMN IF EXISTS recipe_id;

COMMENT ON TABLE material_recipe_element IS
  'task-260901：元素行归属 = 含量配置（material_recipe_config）。材质维度经 config → recipe 两跳获得。';
