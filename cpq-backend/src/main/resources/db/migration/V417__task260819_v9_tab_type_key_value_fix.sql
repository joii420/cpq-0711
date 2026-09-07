-- V417__task260819_v9_tab_type_key_value_fix.sql
-- 🤖 由 dev-docs/task-260819-取数配置器/scripts/gen_v9_semantic_seed.py 生成，🚫 不要手改。
-- task-260819 · v9 · B-54（需求文档 D-39，2026-08-21 裁决；用户 2026-09-05 批准本次修复）
--
-- 【改什么】semantic_tab_view.tab_type：'BOM 树' → 'BOM'，共 3 行（三个方言各 1 行）。
--
-- 【为什么】D-39 原文把两件东西分得很清楚：
--     · 存储值 = 'BOM'     —— component.tab_type / ComponentService.VALID_TAB_TYPES /
--                            semantic_tab_view.tab_type / builder_config.tabType，四处统一
--     · 显示名 = 「BOM 树」   —— Select 的 label、图谱页文案、文档正文
--   而 V413 种子把**显示名写进了键值列**。后果不是显示难看，是**存不进去**：
--     PUT /api/cpq/components/{id}/builder   tabType = 'BOM 树'
--       → 400 Invalid tabType: BOM 树. Must be one of: [费用类,外购件,零件,BOM,主件,材质元素]
--     ⇒ 用取数配置器配「BOM 树」页签的**新组件根本建不出来**。
--
-- 🚨 这是**同一缺陷的第二次**。D-39 的「起因」栏记的就是第一次：前端把 TAB_TYPES 写成
--   'BOM 树'，includes() 静默 miss，存量 tab_type='BOM' 的组件打开取数配置 Tab 被误初始化
--   成「主件」。D-39 就是为防它而写的裁决，然后种子这一侧犯了镜像版本的同一个错。
--   ⇒ 光有文档裁决拦不住第三次，故本次同时加**启动期自检**（B-56）
--     com.cpq.semanticgraph.service.SemanticGraphKeyValueSelfCheck：
--     semantic_tab_view.tab_type ⊄ ComponentService.VALID_TAB_TYPES 时**服务直接起不来**。
--
-- 【存量口径站在 'BOM' 一边】实测 component.tab_type 分布：
--   BOM=21 / 主件=36 / 材质元素=22 / 零件=15 / 外购件=15 / NULL=186；'BOM 树' **0 行**。
--   ⇒ 该改的是种子，不是 VALID_TAB_TYPES（D-39 明写「现网已有该值的数据，不可改」）。
--
-- 【命中面】3 行，可逆（反向 UPDATE 即还原）。🚫 不删行、🚫 不动 id ——
--   id 是 UUIDv5（键串里含冻结的 'BOM 树'），semantic_tab_view_node 有 FK 指着它。
-- 【明确不动的行】零件 / 外购件 / 主件 / 材质元素 / 费用类 一律不碰：它们两边逐字一致，
--   且现网 30 个存量组件靠「零件 / 外购件」两行打开配置页（task-260904 S-4 划的边界）。

-- ① 落地前守卫：目标值不能已被占用 —— 唯一键是 (tab_type, variant_key, dialect)，
--    改 tab_type 就是在动唯一键，撞了要给出说得清的报错，而不是一句裸 23505。
DO $$
DECLARE clash int;
BEGIN
  SELECT count(*) INTO clash FROM semantic_tab_view v
   WHERE v.tab_type = 'BOM'
     AND EXISTS (SELECT 1 FROM semantic_tab_view s
                  WHERE s.tab_type = 'BOM 树' AND s.variant_key = v.variant_key
                    AND s.dialect = v.dialect);
  IF clash > 0 THEN
    RAISE EXCEPTION 'V417 撞唯一键 (tab_type,variant_key,dialect)：已有 % 行 tab_type=BOM 与待改行的 (variant_key,dialect) 重合。🚫 不要强改，先人工核对这些行是谁写的。', clash;
  END IF;
END $$;

-- ② 按主键逐条更新（3 个确定性 UUIDv5，非无 WHERE 的全表 UPDATE）
UPDATE semantic_tab_view
   SET tab_type = 'BOM', updated_by = 'seed', updated_at = now()
 WHERE tab_type = 'BOM 树'
   AND id IN (
     'd771adae-74ba-5a92-9403-1bc0a436bd6f', -- QUOTE.MATERIAL_BOM
     '2ed644ce-833b-5220-88bb-6aa57d26183a', -- COST_BASIC.MATERIAL_BOM
     '919bdd88-8ee1-5240-992f-fb4d45e31a82' -- COST_DETAIL.MATERIAL_BOM
   );

-- ③ 落地守卫：断言**状态**而不是变化量（RECORD「断言状态而非变化量」）——
--    对不上就报错中止，🚫 不允许静默改 0 行。
DO $$
DECLARE fixed int; leftover int; domain_now text;
BEGIN
  -- ③-a 点名的那 3 行，现在必须都是 'BOM'
  SELECT count(*) INTO fixed FROM semantic_tab_view
   WHERE tab_type = 'BOM' AND id IN (
     'd771adae-74ba-5a92-9403-1bc0a436bd6f', -- QUOTE.MATERIAL_BOM
     '2ed644ce-833b-5220-88bb-6aa57d26183a', -- COST_BASIC.MATERIAL_BOM
     '919bdd88-8ee1-5240-992f-fb4d45e31a82' -- COST_DETAIL.MATERIAL_BOM
   );
  -- ③-b 全表不允许再有 'BOM 树' 残留（含本次没点名的行，防漏网）
  SELECT count(*) INTO leftover FROM semantic_tab_view WHERE tab_type = 'BOM 树';
  IF fixed <> 3 OR leftover <> 0 THEN
    SELECT string_agg(DISTINCT tab_type, ' | ' ORDER BY tab_type) INTO domain_now
      FROM semantic_tab_view;
    RAISE EXCEPTION 'V417 未落全：期望 3 行 tab_type=BOM / 0 行 BOM 树 残留，实得 % / %。当前 tab_type 值域=[%]', fixed, leftover, domain_now;
  END IF;
  RAISE NOTICE 'V417 ✅ semantic_tab_view.tab_type：% 行改为 BOM，全表无 BOM 树 残留', fixed;
END $$;
