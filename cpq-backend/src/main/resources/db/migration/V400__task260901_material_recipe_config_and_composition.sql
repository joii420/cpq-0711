-- V399__task260901_material_recipe_config_and_composition.sql
-- task-260901 · B-1 + B-2：材质模型三层化（材质 → 含量配置 → 元素行）+ 材质元素组成显式化 + 存量双向迁移。
--
-- 数据模型见 dev-docs/task-260901-材质管理模块定义规则更新/需求文档.md §4.5（闸门 A0 裁决：甲·独立配置表）。
--
-- 🚨 本文件<b>不含</b>红线 DDL。`DROP CONSTRAINT uq_recipe_element` 与 `DROP COLUMN recipe_id`
--    属 CLAUDE.md §3.2「契约销毁 / 数据销毁」，另置于
--    dev-docs/task-260901-材质管理模块定义规则更新/待批-V401__drop_legacy_recipe_element_constraints.sql，
--    待用户按红线三步走批准后由主线移入 db/migration/。
--
-- ⚠️ 过渡期约定（V401 未批准前）：material_recipe_element.recipe_id 保留但改为可空，
--    新写入的元素行只挂 config_id、不再写 recipe_id。旧唯一键 uq_recipe_element(recipe_id, element_code)
--    对 recipe_id IS NULL 的新行天然不生效（PG 唯一索引里 NULL 互不相等），
--    完整性由本迁移新建的 uq_config_element(config_id, element_code) 承担。

-- ─────────────────────────────────────────────────────────────
-- ⓪ 材质的元素组成（显式属性，D10）
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS material_recipe_composition (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipe_id    uuid        NOT NULL REFERENCES material_recipe(id) ON DELETE CASCADE,
  element_no   varchar(32) NOT NULL,      -- 权威元素链，指向 element.element_no
  element_code varchar(32) NOT NULL,      -- 符号快照，矩阵列头
  element_name varchar(64) NOT NULL,      -- 中文名快照
  sort_order   integer     NOT NULL DEFAULT 0,   -- 决定配置矩阵的列顺序
  created_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_mrcomp_recipe_element UNIQUE (recipe_id, element_no)
);
CREATE INDEX IF NOT EXISTS idx_mrcomp_recipe ON material_recipe_composition(recipe_id, sort_order);
COMMENT ON TABLE material_recipe_composition IS 'task-260901：材质的元素组成（M-0，配置矩阵的列权威来源；0 配置的材质照样有组成）';

-- ─────────────────────────────────────────────────────────────
-- ① 含量配置
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS material_recipe_config (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  recipe_id  uuid        NOT NULL REFERENCES material_recipe(id) ON DELETE CASCADE,
  config_no  varchar(80) NOT NULL,                    -- '00006-01'
  seq        integer     NOT NULL,                    -- 发号水位（含 INACTIVE），保证编号不回收
  status     varchar(16) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE / INACTIVE
  remark     varchar(255),
  sort_order integer     NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  created_by uuid,
  updated_by uuid,
  CONSTRAINT uq_mrc_config_no  UNIQUE (config_no),
  CONSTRAINT uq_mrc_recipe_seq UNIQUE (recipe_id, seq),
  CONSTRAINT chk_mrc_status    CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX IF NOT EXISTS idx_mrc_recipe ON material_recipe_config(recipe_id, seq);
COMMENT ON TABLE material_recipe_config IS 'task-260901：材质的含量配置（M-1 发号 / M-2 软删不回收编号）';

-- ─────────────────────────────────────────────────────────────
-- ② 材质加「是否支持自定义含量」开关（M-5，默认关）
-- ─────────────────────────────────────────────────────────────
ALTER TABLE material_recipe ADD COLUMN IF NOT EXISTS allow_custom_content boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN material_recipe.allow_custom_content IS 'task-260901：选配是否允许自定义含量（M-5，优先于元素级 is_locked）';

-- ─────────────────────────────────────────────────────────────
-- ③ 元素行改挂配置（本阶段可空；SET NOT NULL 在待批 V401）
-- ─────────────────────────────────────────────────────────────
ALTER TABLE material_recipe_element ADD COLUMN IF NOT EXISTS config_id uuid;

-- ─────────────────────────────────────────────────────────────
-- ④ 存量双向迁移（B-2）
--   ①每条有元素行的材质建一条 <code>-01 配置  ②元素行回填 config_id  ③推导元素组成
-- ─────────────────────────────────────────────────────────────
INSERT INTO material_recipe_config (recipe_id, config_no, seq, status, sort_order, created_at, updated_at)
SELECT mr.id, mr.code || '-01', 1, 'ACTIVE', 0, now(), now()
  FROM material_recipe mr
 WHERE EXISTS (SELECT 1 FROM material_recipe_element e WHERE e.recipe_id = mr.id)
ON CONFLICT (config_no) DO NOTHING;

UPDATE material_recipe_element mre
   SET config_id = c.id
  FROM material_recipe_config c
 WHERE c.recipe_id = mre.recipe_id
   AND c.seq = 1
   AND mre.config_id IS NULL;

-- 元素组成由 621 行元素行推导：element_no 优先取元素行自身的权威链，
-- 其次按符号反查 element 主表，最后退化为符号本身（脏行 element_code='10004' 走这条，
-- 它本就是把元素编号填进了符号列；本次不做脏数据清理，见 §2.2）。
INSERT INTO material_recipe_composition (recipe_id, element_no, element_code, element_name, sort_order, created_at)
SELECT mre.recipe_id,
       COALESCE(mre.element_no, ec.element_no, mre.element_code),
       mre.element_code,
       mre.element_name,
       mre.sort_order,
       now()
  FROM material_recipe_element mre
  LEFT JOIN element ec ON ec.element_code = mre.element_code
 WHERE mre.recipe_id IS NOT NULL
ON CONFLICT (recipe_id, element_no) DO NOTHING;

-- ─────────────────────────────────────────────────────────────
-- ⑤ 迁移断言（B-2：任一不成立即 RAISE EXCEPTION，不静默放过）
--   ⚠️ 断言写成「与元素行总数相等」的语义式，而不是硬编码 621 ——
--      dev 库 cpq_db_0724 是 621 行，test 库 cpq_db 是 632 行，硬编码会让测试库迁移必挂。
-- ─────────────────────────────────────────────────────────────
DO $$
DECLARE
  v_null_cfg     bigint;
  v_elem_rows    bigint;
  v_comp_rows    bigint;
  v_orphan_elem  bigint;
BEGIN
  -- 断言 1：所有元素行都已挂到配置上
  SELECT count(*) INTO v_null_cfg FROM material_recipe_element WHERE config_id IS NULL;
  IF v_null_cfg <> 0 THEN
    RAISE EXCEPTION 'V399 迁移断言 1 失败：material_recipe_element.config_id 仍有 % 行为 NULL', v_null_cfg;
  END IF;

  -- 断言 2：元素组成行数 = 元素行数（uq_recipe_element 保证 (recipe_id, element_code) 唯一，
  --         故一一对应；dev 库该值 = 621）
  SELECT count(*) INTO v_elem_rows FROM material_recipe_element;
  SELECT count(*) INTO v_comp_rows FROM material_recipe_composition;
  IF v_comp_rows <> v_elem_rows THEN
    RAISE EXCEPTION 'V399 迁移断言 2 失败：material_recipe_composition=% 行，material_recipe_element=% 行，应相等',
      v_comp_rows, v_elem_rows;
  END IF;

  -- 断言 3：不存在「元素行的 element_code 不在本材质元素组成里」的行
  SELECT count(*) INTO v_orphan_elem
    FROM material_recipe_element mre
   WHERE NOT EXISTS (
     SELECT 1 FROM material_recipe_composition mc
      WHERE mc.recipe_id = mre.recipe_id AND mc.element_code = mre.element_code);
  IF v_orphan_elem <> 0 THEN
    RAISE EXCEPTION 'V399 迁移断言 3 失败：有 % 行元素的 element_code 不在本材质的元素组成里', v_orphan_elem;
  END IF;

  RAISE NOTICE 'V399 迁移断言全部通过：config_id NULL=0，composition=% 行，element=% 行', v_comp_rows, v_elem_rows;
END $$;

-- ─────────────────────────────────────────────────────────────
-- ⑥ 新完整性约束 + 过渡期放开旧非空
-- ─────────────────────────────────────────────────────────────
-- 新写入的元素行只挂 config_id，不再写 recipe_id（V401 批准后该列整体删除）。
ALTER TABLE material_recipe_element ALTER COLUMN recipe_id DROP NOT NULL;

ALTER TABLE material_recipe_element
  ADD CONSTRAINT uq_config_element UNIQUE (config_id, element_code);
ALTER TABLE material_recipe_element
  ADD CONSTRAINT fk_mre_config FOREIGN KEY (config_id)
  REFERENCES material_recipe_config(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_mre_config ON material_recipe_element(config_id);

COMMENT ON COLUMN material_recipe_element.config_id IS 'task-260901：元素行归属由材质下沉到含量配置（M-0）';
COMMENT ON COLUMN material_recipe_element.recipe_id IS 'task-260901 过渡期遗留列：新行不再写入，待 V401 批准后删除';
