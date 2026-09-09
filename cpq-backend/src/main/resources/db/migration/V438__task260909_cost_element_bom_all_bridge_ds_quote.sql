-- ============================================================================
-- task-260909 · V6 老表退役 · 批次 2（B-7）
--   v_ds_cost_basic_element_bom_all / v_ds_cost_detail_element_bom_all
--   两张视图各两段 LEFT JOIN LATERAL（当前版 + _history 版，共 4 处）
--   的「生产料号 → 销售料号」桥，由 material_master 换成 ds_quote_material。
--
-- 🚨 与 CostAllVersionViewSelfCheck 的关系（backtask B-7 要求"同批"）：
--    本迁移**不增不减任何列**，sales_material_no 仍是唯一派生列，且已在
--    CostAllVersionViewSelfCheck.REGISTERED_DERIVED_COLUMNS 里按视图登记
--    （repair-260909 落的）。⇒ 守卫**无需改动**，"同批"在本次是空条件。
--    仍必须真重启验证（AC-7）：守卫只活在 @Observes StartupEvent 上，
--    事务内 CREATE OR REPLACE + ROLLBACK 碰不到启动期失败面。
--
-- 🚨 sales_material_no 的声明类型必须保持 character varying(20)
--    （原桥 material_master.material_no 就是 varchar(20)；ds_quote_material.material_no
--     是 varchar(128)）⇒ 显式 ::character varying(20)。
--    实测 ds_quote_material 无一行 material_no 长度 > 20，不产生截断。
--
-- ✅ 等价性实测（2026-09-09，cpq_db_0724）：两侧 (production_no, sales_material_no)
--    逐值相同 —— 唯一能桥到的 3120014539 两侧都得 S-3120014539；300013 / 300015 两侧都桥不到。
--
-- 🚫 本迁移不含任何 DROP。
-- ============================================================================

CREATE OR REPLACE VIEW v_ds_cost_basic_element_bom_all AS
 SELECT e.id,
    e.production_no,
    e.material_part_no,
    e.item_seq,
    e.element_code,
    e.content_pct,
    e.loss_rate,
    e.version_no,
    e.row_fingerprint,
    e.source,
    e.created_at,
    e.created_by,
    e.updated_at,
    e.updated_by,
    true AS is_current,
    mm.material_no AS sales_material_no
   FROM ds_cost_basic_element_bom e
     LEFT JOIN LATERAL ( SELECT m.material_no::character varying(20) AS material_no
           FROM ds_quote_material m
          WHERE m.production_no::text = e.production_no::text
          ORDER BY m.material_no
         LIMIT 1) mm ON true
UNION ALL
 SELECT h.id,
    h.production_no,
    h.material_part_no,
    h.item_seq,
    h.element_code,
    h.content_pct,
    h.loss_rate,
    h.version_no,
    h.row_fingerprint,
    h.source,
    h.created_at,
    h.created_by,
    h.updated_at,
    h.updated_by,
    false AS is_current,
    mm.material_no AS sales_material_no
   FROM ds_cost_basic_element_bom_history h
     LEFT JOIN LATERAL ( SELECT m.material_no::character varying(20) AS material_no
           FROM ds_quote_material m
          WHERE m.production_no::text = h.production_no::text
          ORDER BY m.material_no
         LIMIT 1) mm ON true;

CREATE OR REPLACE VIEW v_ds_cost_detail_element_bom_all AS
 SELECT e.id,
    e.production_no,
    e.material_part_no,
    e.item_seq,
    e.element_code,
    e.content_pct,
    e.loss_rate,
    e.version_no,
    e.row_fingerprint,
    e.source,
    e.created_at,
    e.created_by,
    e.updated_at,
    e.updated_by,
    true AS is_current,
    mm.material_no AS sales_material_no
   FROM ds_cost_detail_element_bom e
     LEFT JOIN LATERAL ( SELECT m.material_no::character varying(20) AS material_no
           FROM ds_quote_material m
          WHERE m.production_no::text = e.production_no::text
          ORDER BY m.material_no
         LIMIT 1) mm ON true
UNION ALL
 SELECT h.id,
    h.production_no,
    h.material_part_no,
    h.item_seq,
    h.element_code,
    h.content_pct,
    h.loss_rate,
    h.version_no,
    h.row_fingerprint,
    h.source,
    h.created_at,
    h.created_by,
    h.updated_at,
    h.updated_by,
    false AS is_current,
    mm.material_no AS sales_material_no
   FROM ds_cost_detail_element_bom_history h
     LEFT JOIN LATERAL ( SELECT m.material_no::character varying(20) AS material_no
           FROM ds_quote_material m
          WHERE m.production_no::text = h.production_no::text
          ORDER BY m.material_no
         LIMIT 1) mm ON true;

-- 收工断言：桥不许再引用 material_master
DO $$
DECLARE leftover int;
BEGIN
    SELECT count(*) INTO leftover
      FROM pg_class c
     WHERE c.relname IN ('v_ds_cost_basic_element_bom_all','v_ds_cost_detail_element_bom_all')
       AND pg_get_viewdef(c.oid, true) ~ '\mmaterial_master\M';
    IF leftover <> 0 THEN
        RAISE EXCEPTION 'B-7 未收敛：仍有 % 张 v_ds_cost_*_element_bom_all 引用 material_master', leftover;
    END IF;
END $$;
