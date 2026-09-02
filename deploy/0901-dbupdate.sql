-- ============================================================
-- CPQ 内网数据库增量更新 · 2026-09-01
-- ------------------------------------------------------------
-- 适用对象: 已跑过 deploy/0813-dbupdate.sql、表结构停在 Flyway V387 的内网库。
--           本脚本把表结构补齐到 V398(合并 V388~V398 共 11 个迁移, 一次跑到位)。
--           若数据库由 2026-09-01 之后版本的 deploy/cpq-init-empty-navicat.sql 新建
--           (基线已是 398), 不需要跑本脚本。
--
-- ⚠️ 补充(同日追加): 本脚本只覆盖到 V398。V399(sel_param_type 3 行种子补齐)另见
--    deploy/0901-dbupdate-2-sel-param-seed.sql —— 纯幂等 INSERT, 且更新后端重启后
--    Flyway 会自动补, 多数情况无需手工执行。缺那 3 行的症状是「选配模板管理 → 新建模板」
--    永远弹「参数池加载中，请稍候再试」。
--
-- 覆盖范围(11 个迁移, 按 Flyway 原始顺序原样收录, 未做任何逻辑改写):
--   V388 取数配置器·语义图落库 —— 新建 7 张表 + 种子数据 + component_sql_view 加 2 列
--        新表: semantic_node / semantic_node_column / semantic_edge / semantic_edge_key /
--              semantic_tab_view / semantic_tab_view_node / semantic_tab_view_column
--        种子: 23 节点 / 143 节点列 / 22 边 / 26 连接键 / 7 页签视图 / 16 视图节点 / 23 列角色
--        改表: component_sql_view 加 builder_config JSONB、builder_version INT(均 nullable,
--              存量 NULL = 手写模式, 行为逐字不变)
--   V389 修正 V388 种子的判别式错误(ELEMENT_BOM_ITEM / ELEMENT_RECOVERY 两行 discriminator 置 NULL)
--   V390 补回被误删的 E02 GRAIN 边
--   V391 清洗 component_sql_view.builder_config 里的 confirmedImpact 脏 key
--   V392 重置 V388 种子里写死的假 assert_status(19 条边 -> NA)
--   V393 补 LOOKUP_CUSTOMER_MAP 入边 + 2 个镜像列; semantic_edge 加 fallback_to_join_key 列
--   V394 补 SELF_PROCESS -> 材质库 的 COALESCE 兜底边
--   V395 补 材质元素 -> 来料固定加工费 的 GRAIN 边 + 页签挂载
--   V396 quotation_line_component_data 去重 + 加唯一约束 uq_qlcd_line_component
--   V397 f_material_element_price 三参重载(pending 感知取价) + 两参版改委托
--   V398 quotation 加 user_data_version integer NOT NULL DEFAULT 0(保存草稿乐观锁)
--
-- ============================================================
-- 🚨 执行前必看: 本脚本含一处 DELETE(V396), 不是纯 DDL
-- ------------------------------------------------------------
-- V396 会删除 quotation_line_component_data 里重复的 (line_item_id, component_id) 行,
-- 每组保留 tab_name 非空者(并列时保留 created_at 较早者)。这是为了让紧随其后的
-- ADD CONSTRAINT uq_qlcd_line_component 能建成 —— 有重复就建不上, 会整体回滚。
--
-- 命中面因库而异, 参考值: dev 库 cpq_db_0724 实测 39,268 行中恰 6 组重复(删 6 行),
-- 全部集中在单张单。**你的内网库数字必然不同, 必须先跑下面自检 4) 拿到自己的数字再决定。**
-- 若自检 4) 返回 0, 说明无重复, 该 DELETE 影响 0 行, 可直接执行。
-- 若返回较大数字(例如上百行), 先停下来看一眼被删的都是什么, 别盲跑。
--
-- 事务边界: 除最后的 Flyway 基线行外, 全部语句包在**单个** BEGIN/COMMIT 里 ——
--           任何一步失败都整体回滚, 库不留半成品; 修掉问题后可从头重跑。
--
-- Navicat 导入: 右键库 -> 运行 SQL 文件 -> 选本文件 -> 勾选"遇到错误时停止"。
-- ============================================================


-- ============================================================
-- 执行前自检(建议先单独运行, 逐条核对)
-- ------------------------------------------------------------
-- 1) 确认当前 Flyway 版本/基线(预期 387; 已是 398 表示无需执行)
--    SELECT max(version::int) FROM flyway_schema_history WHERE success;
--
-- 2) 确认 7 张 semantic_* 表尚不存在(预期 0 行)
--    SELECT table_name FROM information_schema.tables
--     WHERE table_schema='public' AND table_name LIKE 'semantic\_%';
--
-- 3) 确认 component_sql_view 尚无 builder_config 列(预期 0 行)
--    SELECT column_name FROM information_schema.columns
--     WHERE table_schema='public' AND table_name='component_sql_view'
--       AND column_name IN ('builder_config','builder_version');
--
-- 4) 🚨 V396 的 DELETE 命中面 —— 说不出这个数字就不要执行本脚本
--    -- 4a) 有几组重复、共几行会被删:
--    SELECT count(*) AS 将被删除的行数 FROM (
--      SELECT id, ROW_NUMBER() OVER (
--               PARTITION BY line_item_id, component_id
--               ORDER BY CASE WHEN tab_name IS NULL OR tab_name='' THEN 1 ELSE 0 END ASC,
--                        created_at ASC, id ASC) AS rn
--        FROM quotation_line_component_data) t
--     WHERE rn > 1;
--    -- 4b) 涉及哪些报价单(便于判断是否业务真单):
--    SELECT q.id AS 报价单id, q.name AS 单名, count(*) AS 重复行数
--      FROM quotation_line_component_data cd
--      JOIN quotation_line_item li ON li.id = cd.line_item_id
--      JOIN quotation q ON q.id = li.quotation_id
--     WHERE (cd.line_item_id, cd.component_id) IN (
--             SELECT line_item_id, component_id FROM quotation_line_component_data
--              GROUP BY line_item_id, component_id HAVING count(*) > 1)
--     GROUP BY q.id, q.name ORDER BY 3 DESC;
--
-- 5) 确认 quotation 尚无 user_data_version 列(预期 0 行)
--    SELECT column_name FROM information_schema.columns
--     WHERE table_schema='public' AND table_name='quotation' AND column_name='user_data_version';
--
-- 6) 确认 f_material_element_price 当前只有两参版(预期 1 行)
--    SELECT pg_get_function_identity_arguments(oid) FROM pg_proc WHERE proname='f_material_element_price';
-- ============================================================


BEGIN;


-- ==========================================================================
-- 第 1 节 · V388：取数配置器语义图 —— 7 张新表 + 种子 + component_sql_view 加 2 列
-- ==========================================================================


-- ------------------------------------------------------------------------
-- >>> V388__task260819_semantic_graph_schema_and_seed.sql
-- ------------------------------------------------------------------------
-- V388__task260819_semantic_graph_schema_and_seed.sql
-- 取数配置器·语义图落库（D-27~D-35）：7 张表 + 种子数据（17 SHEET + 5 LOOKUP + 1 FUNCTION / 22 条边(19 逻辑) / 7 页签视图行）
-- 数据来源：dev-docs/task-260819-取数配置器/原型图/语义图管理页/data.js（deriveModel() 实际输出）
--          + 原型-取数配置器.html 的 SHEETS/TABDEF/LIB（补列与角色明细）

-- ============ 表结构 ============

CREATE TABLE IF NOT EXISTS semantic_node (
    id               UUID PRIMARY KEY,
    node_key         VARCHAR(80)  NOT NULL,
    display_name     VARCHAR(200) NOT NULL,
    short_name       VARCHAR(40)  NOT NULL,      -- 别名生成用 Sheet 简称（D-13）；草案 DDL 未含，补充
    node_kind        VARCHAR(20)  NOT NULL,      -- SHEET | LOOKUP | FUNCTION
    physical_table   VARCHAR(120),
    scope            VARCHAR(20)  NOT NULL DEFAULT 'NONE',
    anchor_expr      VARCHAR(200),
    grain_columns    TEXT[]       NOT NULL DEFAULT '{}',
    fixed_predicate  TEXT,
    func_signature   TEXT,
    discriminator    TEXT,                       -- 判别式（AC-43 要求展示）；api.md 已声明，草案 DDL 未含，补充
    source_handler   VARCHAR(120),                -- 对账断言用（AC-36）；api.md 已声明，草案 DDL 未含，补充
    dialect          VARCHAR(20)  NOT NULL DEFAULT 'QUOTE',
    note             TEXT,
    created_by       VARCHAR(80), created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_by       VARCHAR(80), updated_at TIMESTAMP NOT NULL DEFAULT now(),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    UNIQUE (node_key, dialect)
);

CREATE TABLE IF NOT EXISTS semantic_node_column (
    id            UUID PRIMARY KEY,
    node_id       UUID NOT NULL REFERENCES semantic_node(id) ON DELETE CASCADE,
    db_column     VARCHAR(120) NOT NULL,
    display_name  VARCHAR(200) NOT NULL,
    data_type     VARCHAR(20)  NOT NULL,
    is_code       BOOLEAN NOT NULL DEFAULT false,
    roles         TEXT[]  NOT NULL DEFAULT '{}',
    sort_order    INT NOT NULL DEFAULT 0,
    created_by       VARCHAR(80), created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_by       VARCHAR(80), updated_at TIMESTAMP NOT NULL DEFAULT now(),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    UNIQUE (node_id, db_column)
);

CREATE TABLE IF NOT EXISTS semantic_edge (
    id             UUID PRIMARY KEY,
    from_node_id   UUID NOT NULL REFERENCES semantic_node(id),   -- RESTRICT（默认）：AC-54 要求库层拒删
    to_node_id     UUID NOT NULL REFERENCES semantic_node(id),
    edge_kind      VARCHAR(20) NOT NULL,             -- GRAIN | SUB | SAME | JOIN | LOOKUP | PRICE
    cardinality    VARCHAR(20) NOT NULL,             -- MANY_TO_ONE | ONE_TO_MANY
    fallback_order INT,
    coalesce_group VARCHAR(40),                      -- 多源 COALESCE 分组标识；api.md 已声明，草案 DDL 未含，补充
    assert_status  VARCHAR(10) NOT NULL DEFAULT 'NA', -- PASS|FAIL|THIN|NA；仅在 POST /validate 或写端点在线校验时更新，GET 不做实时探测（避免每次读图触发 O(边数) 查询）
    assert_sample_rows BIGINT,                       -- THIN 判据的样本行数
    note           TEXT,
    created_by       VARCHAR(80), created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_by       VARCHAR(80), updated_at TIMESTAMP NOT NULL DEFAULT now(),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    UNIQUE (from_node_id, to_node_id, edge_kind),
    CONSTRAINT chk_card CHECK (cardinality IN ('MANY_TO_ONE','ONE_TO_MANY'))
);
-- 修正草案 DDL 的错误约束：草案写「同 from 下唯一」，但同一 from 节点下可以有多个互不相关的
-- LOOKUP 分组各自从 fallback_order=0 起算（实测 E06/E08 同 from='物料与元素BOM' 均 fb=0，
-- 但分属不同分组：E06 属 ebi_name 组，E08 是独立的元素名查名，互不冲突）。
-- 故唯一性必须限定在「同一 coalesce_group 内」，而非「同一 from 节点下」。
CREATE UNIQUE INDEX IF NOT EXISTS uq_edge_fallback ON semantic_edge(from_node_id, coalesce_group, fallback_order)
    WHERE fallback_order IS NOT NULL AND coalesce_group IS NOT NULL AND status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS semantic_edge_key (
    id            UUID PRIMARY KEY,
    edge_id       UUID NOT NULL REFERENCES semantic_edge(id) ON DELETE CASCADE,
    left_column   VARCHAR(120) NOT NULL,
    right_column  VARCHAR(120) NOT NULL,
    seq           INT NOT NULL DEFAULT 0,
    UNIQUE (edge_id, seq)
);

CREATE TABLE IF NOT EXISTS semantic_tab_view (
    id             UUID PRIMARY KEY,
    tab_type       VARCHAR(40) NOT NULL,
    variant_key    VARCHAR(40) NOT NULL DEFAULT '',
    variant_label  VARCHAR(80),
    anchor_node_id UUID NOT NULL REFERENCES semantic_node(id),
    switches       TEXT[] NOT NULL DEFAULT '{}',
    dialect        VARCHAR(20) NOT NULL DEFAULT 'QUOTE',
    created_by       VARCHAR(80), created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_by       VARCHAR(80), updated_at TIMESTAMP NOT NULL DEFAULT now(),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    UNIQUE (tab_type, variant_key, dialect)
);

CREATE TABLE IF NOT EXISTS semantic_tab_view_node (
    id          UUID PRIMARY KEY,
    view_id     UUID NOT NULL REFERENCES semantic_tab_view(id) ON DELETE CASCADE,
    node_id     UUID NOT NULL REFERENCES semantic_node(id),
    role        VARCHAR(10) NOT NULL,
    add_dims    TEXT[] NOT NULL DEFAULT '{}',
    sort_order  INT NOT NULL DEFAULT 0,
    created_by       VARCHAR(80), created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_by       VARCHAR(80), updated_at TIMESTAMP NOT NULL DEFAULT now(),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    UNIQUE (view_id, node_id),
    CONSTRAINT chk_role CHECK (role IN ('MAIN','AUX'))
);

CREATE TABLE IF NOT EXISTS semantic_tab_view_column (
    id           UUID PRIMARY KEY,
    view_id      UUID NOT NULL REFERENCES semantic_tab_view(id) ON DELETE CASCADE,
    column_id    UUID NOT NULL REFERENCES semantic_node_column(id) ON DELETE CASCADE,
    roles        TEXT[] NOT NULL DEFAULT '{}',
    sort_order   INT NOT NULL DEFAULT 0,
    created_by       VARCHAR(80), created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_by       VARCHAR(80), updated_at TIMESTAMP NOT NULL DEFAULT now(),
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    UNIQUE (view_id, column_id)
);

CREATE INDEX IF NOT EXISTS idx_semantic_edge_from ON semantic_edge(from_node_id);
CREATE INDEX IF NOT EXISTS idx_semantic_edge_to ON semantic_edge(to_node_id);
CREATE INDEX IF NOT EXISTS idx_semantic_tvn_view ON semantic_tab_view_node(view_id);
CREATE INDEX IF NOT EXISTS idx_semantic_tvc_view ON semantic_tab_view_column(view_id);

-- ============ 种子数据：节点 ============
INSERT INTO semantic_node (id,node_key,display_name,short_name,node_kind,physical_table,scope,anchor_expr,grain_columns,fixed_predicate,func_signature,discriminator,source_handler,dialect,note,created_by) VALUES
('aa084d67-9972-5824-b64b-09430339cc5f','PRODUCT_MASTER','物料主档 / 单重','主档','SHEET','material_master','FULL','mm.material_no','{}',NULL,NULL,NULL,'Q18UnitWeightHandler','QUOTE',NULL,'seed'),
('616cbe9d-80c4-5137-a517-2b51500b2b75','CUSTOMER_MAP','客户料号与宏丰料号的关系','客户料号','SHEET','material_customer_map','NONE',NULL,'{}','customer_no = :customerCode',NULL,NULL,'Q02CustomerMapHandler','QUOTE',NULL,'seed'),
('6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','ELEMENT_BOM_ITEM','物料与元素BOM','元素BOM','SHEET','element_bom_item','FULL','ebi.material_no',ARRAY['material_part_no','component_no']::TEXT[],NULL,NULL,'characteristic = ''RECIPE''','Q04ElementBomHandler','QUOTE',NULL,'seed'),
('ab66377c-a275-58f3-b6bc-ab2510ad4631','ELEMENT_RECOVERY','元素回收折扣','回收折扣','SHEET','element_bom_item','FULL',NULL,ARRAY['material_part_no','component_no']::TEXT[],NULL,NULL,'characteristic = ''RECIPE''','Q05ElementRecoveryHandler','QUOTE','与「物料与元素BOM」同表同粒度（SAME 边），无独立连接键','seed'),
('7307ccc7-06b3-5e3c-9beb-82aef17f0a73','MATERIAL_BOM','物料BOM','物料BOM','SHEET','material_bom_item','FULL','mbi.material_no',ARRAY['component_no']::TEXT[],NULL,NULL,NULL,'MaterialBomMergeHandler','QUOTE','characteristic 由页签类型推导：RECIPE(材质元素边)/ASSEMBLY(零件边)/OUTSOURCED(外购件)，BOM 树不过滤','seed'),
('339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','SELF_PROCESS','自制加工费','自制加工','SHEET','unit_price','FULL','up.finished_material_no',ARRAY['code']::TEXT[],NULL,NULL,'price_type = ''PROCESS''','Q10SelfProcessFeeHandler','QUOTE',NULL,'seed'),
('71e481c2-8049-50f0-b3b7-b0d253f7ca8c','FINISHED_OTHER','成品其他费用','成品其他','SHEET','unit_price','FULL',NULL,ARRAY['cost_type']::TEXT[],NULL,NULL,'price_type = ''FINISHED_MATERIAL_OTHER''','Q11FinishedOtherFeeHandler','QUOTE','实测：4/4 行的值在「比例」，pricing_price 全空','seed'),
('3dc89f8b-bd2f-5436-8a7e-54876e96341e','COMPONENT_OTHER','组成件其他费用','组件其他','SHEET','unit_price','FULL',NULL,ARRAY['code','cost_type']::TEXT[],NULL,NULL,'price_type = ''COMPONENT_OTHER''','Q13ComponentOtherFeeHandler','QUOTE',NULL,'seed'),
('a4d7ef7a-6a44-5b04-a63e-e1f3579a66fc','PLATING_COST','电镀费用','电镀费','SHEET','unit_price','FULL',NULL,ARRAY['code','cost_type']::TEXT[],NULL,NULL,'price_type = ''PLATING''','Q17PlatingCostHandler','QUOTE',NULL,'seed'),
('acca66a6-1a1e-50b2-81e3-9b16b1015ad3','PLATING_SCHEME','电镀方案','电镀方案','SHEET','plating_scheme','NONE',NULL,'{}',NULL,NULL,NULL,'Q16PlatingSchemeHandler','QUOTE','孤儿 Sheet：现网数据完全孤立（hf_part_no 与 plating_scheme_no 双向全空），属导入侧问题（N-8）','seed'),
('d11dc5dd-3354-5076-9254-f8c5d0e4d8db','ASSEMBLY_FEE','组装加工费','组装加工','SHEET','capacity','NONE','ca.material_no',ARRAY['process_no']::TEXT[],NULL,NULL,NULL,'Q14AssemblyProcessFeeHandler','QUOTE','收窄：system_type=''QUOTE'' AND is_current（无 customer_no 维度）','seed'),
('546538fb-d097-5f89-b027-b3e786f75930','INCOMING_FIXED','来料固定加工费','来料加工','SHEET','unit_price','FULL','up.finished_material_no',ARRAY['code']::TEXT[],NULL,NULL,'price_type = ''INCOMING_MATERIAL_PROCESS''','Q06FixedProcessFeeHandler','QUOTE','实测：2/2 行的值在「基准值」，pricing_price 全空；对应现网 ll_view（13 个组件）','seed'),
('99fe2b0a-5e25-5c83-8708-4423a21bd7c1','INCOMING_RECYCLE','来料回收折扣','来料回收','SHEET','unit_price','FULL',NULL,ARRAY['code','finished_material_no']::TEXT[],NULL,NULL,'price_type = ''INCOMING_MATERIAL_RECYCLE''','Q09IncomingRecoveryHandler','QUOTE','THIN：全库仅 1 行数据，基数断言在此样本量下必然通过（D-32）；用于 BOM 树页签相关标量子查询','seed'),
('6a745bc9-f61a-5e97-a38c-e914d8475e2e','INCOMING_ANNUAL','来料年降','来料年降','SHEET','annual_discount','FULL',NULL,ARRAY['code','discount_order']::TEXT[],NULL,NULL,'discount_type = ''INCOMING_MATERIAL''','Q08IncomingAnnualDiscountHandler','QUOTE','孤儿 Sheet：按 N-7 移出本期范围，登记在图里但不挂任何页签','seed'),
('705d08e8-0869-5989-a4bc-8855b56d0d15','ASSEMBLY_ANNUAL','组装加工费年降','组装年降','SHEET','annual_discount','FULL',NULL,ARRAY['process_no','discount_order']::TEXT[],NULL,NULL,'discount_type = ''ASSEMBLY_PROCESS''','Q15AssemblyAnnualDiscountHandler','QUOTE','孤儿 Sheet：按 N-7 移出本期范围，登记在图里但不挂任何页签','seed'),
('96a33c62-4134-59de-8acb-f1306363c150','FINISHED_ANNUAL','年降系数','年降系数','SHEET','annual_discount','FULL',NULL,ARRAY['discount_order']::TEXT[],NULL,NULL,'discount_type = ''FINISHED''','Q19AnnualDiscountHandler','QUOTE','孤儿 Sheet：按 N-7 移出本期范围，登记在图里但不挂任何页签','seed'),
('45d8b491-1f36-5bbf-b310-f104e744d4f5','INCOMING_OTHER','来料其他费用','来料其他','SHEET','unit_price','FULL','up.finished_material_no',ARRAY['code','cost_type']::TEXT[],NULL,NULL,'price_type = ''INCOMING_MATERIAL_OTHER''','Q07IncomingOtherFeeHandler','QUOTE','实测：(code, finished_material_no) 6 行/4 键有 2 组重复，必须再带一维（要素）才唯一；对应现网 lqt_view（6 个组件）','seed'),
('d9e9192b-068f-523b-a4d9-3ec30ea500ab','LOOKUP_MATERIAL_MASTER','物料主档','主档','LOOKUP','material_master','NONE',NULL,'{}',NULL,NULL,NULL,NULL,'QUOTE',NULL,'seed'),
('3fa2be85-aa65-5f24-bfc6-8a772f30b3f3','LOOKUP_MATERIAL_RECIPE','材质库','材质库','LOOKUP','material_recipe','NONE',NULL,'{}',NULL,NULL,NULL,NULL,'QUOTE',NULL,'seed'),
('ce7cfb81-6f2b-5334-9e56-81ad1ed5e69d','LOOKUP_PROCESS_MASTER','工序库','工序库','LOOKUP','process_master','NONE',NULL,'{}',NULL,NULL,NULL,NULL,'QUOTE',NULL,'seed'),
('c45591e9-8f53-529e-b80f-22657967256b','LOOKUP_ELEMENT','元素库','元素库','LOOKUP','element','NONE',NULL,'{}',NULL,NULL,NULL,NULL,'QUOTE','= 元素符号 Ag/Cu，不是 element_no','seed'),
('73f1dd06-dd79-5c87-a2b3-7adcfe3412d0','LOOKUP_CUSTOMER_MAP','客户料号关系','客户料号关系','LOOKUP','material_customer_map','NONE',NULL,'{}','customer_no = :customerCode',NULL,NULL,NULL,'QUOTE','当前 19/22 条连接均未直连本节点（客户收窄改由「客户料号与宏丰料号的关系」SHEET 节点承担，见 AC-7）；按 AC-51① 的「5 张查名维表」口径登记保留','seed'),
('09d4b14c-3dfe-5618-adb1-a22ed10d43d8','FUNC_ELEMENT_PRICE','价格策略 f_material_element_price','价格策略','FUNCTION',NULL,'NONE',NULL,'{}',NULL,'f_material_element_price(:customerCode, :priceBaseDate)',NULL,NULL,'QUOTE','别名固定为 cep（AC-1 铁律）；不是 f_customer_element_price','seed');

-- ============ 种子数据：节点列 ============
INSERT INTO semantic_node_column (id,node_id,db_column,display_name,data_type,is_code,roles,sort_order,created_by) VALUES
('36127c91-513c-5e65-8eff-a6fbcde6e410','aa084d67-9972-5824-b64b-09430339cc5f','material_no','销售料号','TEXT',TRUE,ARRAY['PART_NO','ROW_KEY']::TEXT[],0,'seed'),
('c789b55b-fd65-5a9f-9fe3-acd9ee226771','aa084d67-9972-5824-b64b-09430339cc5f','material_name','物料名称','TEXT',FALSE,ARRAY['PART_NAME']::TEXT[],1,'seed'),
('37a47c89-0c79-59f8-ae47-0e612bb5bb36','aa084d67-9972-5824-b64b-09430339cc5f','specification','规格','TEXT',FALSE,'{}',2,'seed'),
('e20c3270-81c8-5618-bf22-49b4d6a30d94','aa084d67-9972-5824-b64b-09430339cc5f','dimension','尺寸','TEXT',FALSE,'{}',3,'seed'),
('d1fcc8f5-ea01-5060-bed9-081c5676a306','aa084d67-9972-5824-b64b-09430339cc5f','standard_unit','标准单位','TEXT',FALSE,'{}',4,'seed'),
('d53ce7b8-9748-5077-8d2b-dfb4f6b02eb8','aa084d67-9972-5824-b64b-09430339cc5f','unit_weight','单重','NUMBER',FALSE,'{}',5,'seed'),
('9d2c167b-bd2b-507e-9aae-d5ee8364fa35','aa084d67-9972-5824-b64b-09430339cc5f','material_type','物料类型','TEXT',FALSE,'{}',6,'seed'),
('cbc89f92-6b6b-50bb-80a4-a07c6ef6d180','616cbe9d-80c4-5137-a517-2b51500b2b75','material_no','料号','TEXT',TRUE,'{}',0,'seed'),
('45605504-bbcc-5b0d-bc30-075cd3a07f1d','616cbe9d-80c4-5137-a517-2b51500b2b75','customer_material_name','客户料号名称','TEXT',FALSE,ARRAY['PART_NAME']::TEXT[],1,'seed'),
('316fe5e5-4ae0-5386-aeb3-99b3bb1e1f37','616cbe9d-80c4-5137-a517-2b51500b2b75','customer_product_no','客户产品编号','TEXT',FALSE,'{}',2,'seed'),
('8b523062-4175-566d-970d-a3b486ae305d','616cbe9d-80c4-5137-a517-2b51500b2b75','customer_drawing_no','客户图号','TEXT',FALSE,'{}',3,'seed'),
('3f20ffb5-6d9d-583c-a59f-989d267ef944','616cbe9d-80c4-5137-a517-2b51500b2b75','quote_currency','报价货币','TEXT',FALSE,'{}',4,'seed'),
('46840fcf-2ea4-5d72-8f9d-3b98f50cdad6','616cbe9d-80c4-5137-a517-2b51500b2b75','base_currency','基准货币','TEXT',FALSE,'{}',5,'seed'),
('e0cfa015-cddb-5d15-b66f-4acd0241523c','616cbe9d-80c4-5137-a517-2b51500b2b75','exchange_rate','汇率','NUMBER',FALSE,'{}',6,'seed'),
('4ffb50a8-5e24-5bca-b3bb-1326b646d5de','616cbe9d-80c4-5137-a517-2b51500b2b75','payment_method','付款方式','TEXT',FALSE,'{}',7,'seed'),
('5437a896-1b5f-54ac-a2f4-695dcdc97355','616cbe9d-80c4-5137-a517-2b51500b2b75','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],8,'seed'),
('258a0191-8db6-591d-a3d9-cbdd96189f01','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','material_no','归属料号','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],0,'seed'),
('425e6ef5-ac0a-57b1-b8a6-67f34d439420','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','material_part_no','材质料号','TEXT',TRUE,ARRAY['PART_NO','ROW_KEY']::TEXT[],1,'seed'),
('23614d7b-4c0b-544e-8357-05b6862deca4','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','component_no','元素','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],2,'seed'),
('3742737b-cb8f-5573-8856-1a46cd582a64','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],3,'seed'),
('0ca2adb3-d85c-5c70-9153-dd87311c7c7f','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','content','组成含量','NUMBER',FALSE,'{}',4,'seed'),
('19bdc243-4f82-50f3-b6e6-a82a3b030fa3','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','scrap_rate','损耗率','NUMBER',FALSE,'{}',5,'seed'),
('81a76b8c-5e65-5f83-82a7-4c3a5a4010c7','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','composition_qty','毛用量','NUMBER',FALSE,'{}',6,'seed'),
('23baa9fa-5272-5602-b646-e9f27b888cf9','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','issue_unit','毛用量单位','TEXT',FALSE,'{}',7,'seed'),
('358c03da-f87f-5f60-b3b5-52c5cac2126a','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','base_qty','净用量','NUMBER',FALSE,'{}',8,'seed'),
('7468cc22-2cb8-58bb-b9bb-a0abcc312d6a','ab66377c-a275-58f3-b6bc-ab2510ad4631','recovery_discount','回收折扣','NUMBER',FALSE,'{}',0,'seed'),
('0da8b84c-0366-57c4-9ad8-098cb1bdfb25','ab66377c-a275-58f3-b6bc-ab2510ad4631','recovery_currency','回收币种','TEXT',FALSE,'{}',1,'seed'),
('9dd87aa0-0eb9-5709-8425-2c9ec0828cb9','ab66377c-a275-58f3-b6bc-ab2510ad4631','recovery_unit','回收单位','TEXT',FALSE,'{}',2,'seed'),
('c5e58925-2424-592f-ad23-96c256e9f870','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','material_no','归属料号','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],0,'seed'),
('b53a2d6a-e62b-59c0-b364-592fc2058de5','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','component_no','组成件料号','TEXT',TRUE,ARRAY['PART_NO','ROW_KEY']::TEXT[],1,'seed'),
('84877843-5331-5ff8-8bc2-70cd12db7d1e','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','operation_no','工序号','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],2,'seed'),
('6acca680-ef04-5fd4-9c0d-d4863a80d496','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],3,'seed'),
('9daae3d2-0e58-524d-aad9-4264122258b2','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','composition_qty','组成数量','NUMBER',FALSE,'{}',4,'seed'),
('da0e871a-95a2-5b36-8b25-18cfef8d705b','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','issue_unit','组成单位','TEXT',FALSE,'{}',5,'seed'),
('b85aa7c9-ba54-5eef-a8a4-46086c070d89','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','rough_weight','材料毛重','NUMBER',FALSE,'{}',6,'seed'),
('dc4fe6d5-135e-5363-bc01-9cdc2cb58d39','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','net_weight','材料净重','NUMBER',FALSE,'{}',7,'seed'),
('969e3809-c534-5076-bc3d-aa3ff3db9574','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','weight_unit','重量单位','TEXT',FALSE,'{}',8,'seed'),
('5c40f113-cec1-5863-8a89-02bc1bc7e928','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','scrap_rate','损耗率','NUMBER',FALSE,'{}',9,'seed'),
('f07a69ce-5a6f-59c9-9eef-f7706c882604','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','defect_rate','不良率','NUMBER',FALSE,'{}',10,'seed'),
('7bcbb17c-ffce-506e-9210-8e91d766862f','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','component_usage_type','产出料号类型','TEXT',FALSE,'{}',11,'seed'),
('48ddf797-7c2a-5ac4-9a35-05d6d84ae322','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','code','零件料号','TEXT',TRUE,ARRAY['PART_NO','ROW_KEY']::TEXT[],0,'seed'),
('12c8d185-5027-5312-b91e-516c0ce13c65','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','operation_no','工序号','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],1,'seed'),
('6f4f9626-2d97-5731-bf16-aa194d5413f2','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','seq_no','项次（一级）','NUMBER',FALSE,ARRAY['SORT']::TEXT[],2,'seed'),
('87e67663-6945-5fe4-8188-7517a1986dc0','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','pricing_price','值','MONEY',FALSE,'{}',3,'seed'),
('1bd92639-37d7-542e-a7ac-afc2f1d8331c','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','cost_ratio','比例','NUMBER',FALSE,'{}',4,'seed'),
('1f763f73-844c-5e1c-a6d6-5f721fa4777a','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','currency','货币','TEXT',FALSE,'{}',5,'seed'),
('167283cc-9ea0-5d20-b430-693ea52a2fd9','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','unit','计价单位','TEXT',FALSE,'{}',6,'seed'),
('5a9a5309-c533-56ee-9886-ccaa7ac874dc','71e481c2-8049-50f0-b3b7-b0d253f7ca8c','code','成品料号','TEXT',TRUE,'{}',0,'seed'),
('aab8b6d0-add2-51f7-a18b-ceacdee0205e','71e481c2-8049-50f0-b3b7-b0d253f7ca8c','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],1,'seed'),
('f5bd1440-1a8f-50fb-b320-a4c8532532bc','71e481c2-8049-50f0-b3b7-b0d253f7ca8c','cost_type','要素','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],2,'seed'),
('4a77d873-2127-556c-94c6-6d5a042598a7','71e481c2-8049-50f0-b3b7-b0d253f7ca8c','pricing_price','值','MONEY',FALSE,'{}',3,'seed'),
('c305412c-7f55-5a4b-beff-bac35af039d6','71e481c2-8049-50f0-b3b7-b0d253f7ca8c','cost_ratio','比例','NUMBER',FALSE,'{}',4,'seed'),
('3dfb0137-9a97-5cc7-bca3-09afc2a69cdb','71e481c2-8049-50f0-b3b7-b0d253f7ca8c','currency','货币','TEXT',FALSE,'{}',5,'seed'),
('bf43a68a-f54e-5323-aebc-8b43292d67cc','71e481c2-8049-50f0-b3b7-b0d253f7ca8c','unit','计价单位','TEXT',FALSE,'{}',6,'seed'),
('210017b4-7813-5b77-8c5e-cf7ee126f2c1','3dc89f8b-bd2f-5436-8a7e-54876e96341e','code','组成件料号','TEXT',TRUE,'{}',0,'seed'),
('3c5548ba-f74e-58e3-9b5a-aa81be37a36c','3dc89f8b-bd2f-5436-8a7e-54876e96341e','finished_material_no','归属成品料号','TEXT',TRUE,'{}',1,'seed'),
('13619cab-8063-5155-b325-8827adbda535','3dc89f8b-bd2f-5436-8a7e-54876e96341e','item_seq','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],2,'seed'),
('837b2999-2bf2-5cdf-890b-5bff15c5a594','3dc89f8b-bd2f-5436-8a7e-54876e96341e','cost_type','要素','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],3,'seed'),
('06eddf60-eb67-5ba7-aff8-f20735435c80','3dc89f8b-bd2f-5436-8a7e-54876e96341e','pricing_price','值','MONEY',FALSE,'{}',4,'seed'),
('3e2eb047-48b5-5bb8-b99b-c983c32a9758','3dc89f8b-bd2f-5436-8a7e-54876e96341e','currency','货币','TEXT',FALSE,'{}',5,'seed'),
('eb13e3fa-4de7-57c2-99f2-4309be99925e','3dc89f8b-bd2f-5436-8a7e-54876e96341e','unit','计价单位','TEXT',FALSE,'{}',6,'seed'),
('db7b6982-da94-5d0b-905d-e5d9fe407105','a4d7ef7a-6a44-5b04-a63e-e1f3579a66fc','code','零件料号','TEXT',TRUE,'{}',0,'seed'),
('67e981a6-0787-54e7-848c-5a9108a2f4f7','a4d7ef7a-6a44-5b04-a63e-e1f3579a66fc','cost_type','要素','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],1,'seed'),
('a2a40326-0674-5f67-b66f-bbabfe26c6d3','a4d7ef7a-6a44-5b04-a63e-e1f3579a66fc','pricing_price','值','MONEY',FALSE,'{}',2,'seed'),
('8f14e674-9bf5-5fe9-8902-ee46a8110709','a4d7ef7a-6a44-5b04-a63e-e1f3579a66fc','currency','货币','TEXT',FALSE,'{}',3,'seed'),
('e6878f3a-7e74-5b02-abfb-a09a770df566','a4d7ef7a-6a44-5b04-a63e-e1f3579a66fc','unit','计价单位','TEXT',FALSE,'{}',4,'seed'),
('e1bcc481-9c83-5f6d-a467-c2d673416912','a4d7ef7a-6a44-5b04-a63e-e1f3579a66fc','defect_rate','损耗','NUMBER',FALSE,'{}',5,'seed'),
('757778c1-88f6-59a8-847a-644526b7b8df','acca66a6-1a1e-50b2-81e3-9b16b1015ad3','plating_scheme_no','电镀方案号','TEXT',TRUE,'{}',0,'seed'),
('269c043f-58c5-5609-af7a-fb660d566238','acca66a6-1a1e-50b2-81e3-9b16b1015ad3','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],1,'seed'),
('33927666-78ce-5027-b0ee-be471857f532','acca66a6-1a1e-50b2-81e3-9b16b1015ad3','plating_element','电镀元素','TEXT',FALSE,'{}',2,'seed'),
('f52a3a92-a938-532b-9e62-d02bad51e5cb','acca66a6-1a1e-50b2-81e3-9b16b1015ad3','plating_method','电镀方式','TEXT',FALSE,'{}',3,'seed'),
('800f1399-2bea-53b9-9f4c-859022e48872','acca66a6-1a1e-50b2-81e3-9b16b1015ad3','plating_thickness','镀层厚度','NUMBER',FALSE,'{}',4,'seed'),
('0556162b-d7a3-50e5-83bf-8edd229e9f06','acca66a6-1a1e-50b2-81e3-9b16b1015ad3','plating_area','电镀面积','NUMBER',FALSE,'{}',5,'seed'),
('1b0a51d6-8697-5e82-96a5-c0b37be0c084','acca66a6-1a1e-50b2-81e3-9b16b1015ad3','surface_area','表面积','NUMBER',FALSE,'{}',6,'seed'),
('9de117db-507f-5241-9040-3ee57f667e4e','acca66a6-1a1e-50b2-81e3-9b16b1015ad3','element_usage','元素用量','NUMBER',FALSE,'{}',7,'seed'),
('6cbc0337-acbd-54c9-b557-0ca2acb06b9f','acca66a6-1a1e-50b2-81e3-9b16b1015ad3','plating_requirement','电镀要求','TEXT',FALSE,'{}',8,'seed'),
('f25e7e2c-c60f-5916-99c9-89862a2a0c13','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','material_no','成品料号','TEXT',TRUE,'{}',0,'seed'),
('6fc2e38d-5aad-5008-a3e3-f6e0550431cb','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','process_no','工序号','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],1,'seed'),
('54bb0f42-cc32-5e47-8ad7-a834fee8fa5e','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],2,'seed'),
('7b7f6d93-4a0a-5029-a400-05f6b8e0fdf0','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','fixed_cost','组装加工费','MONEY',FALSE,'{}',3,'seed'),
('f6d365ed-1e3a-5b48-8970-a2b19a4fb97e','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','capacity_unit','计价单位','TEXT',FALSE,'{}',4,'seed'),
('664425d4-26af-52f5-9811-36c6fded33eb','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','currency','货币','TEXT',FALSE,'{}',5,'seed'),
('d518fcee-138d-5144-8568-9c233341e26c','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','default_defect_rate','拒收率','NUMBER',FALSE,'{}',6,'seed'),
('1511d801-6d08-564b-ab4f-aa4464da1376','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','process_name','工序名','TEXT',FALSE,ARRAY['ROW_KEY']::TEXT[],7,'seed'),
('706ee002-ce3a-5c57-8d75-1cd502e47914','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','production_type','生产类型','TEXT',FALSE,'{}',8,'seed'),
('8fcdf3a8-9663-523c-83de-49acdef4124c','546538fb-d097-5f89-b027-b3e786f75930','code','投入料号','TEXT',TRUE,ARRAY['PART_NO','ROW_KEY']::TEXT[],0,'seed'),
('736b7aec-0563-51c2-8a6e-74cb1c50ea3c','546538fb-d097-5f89-b027-b3e786f75930','operation_no','工序号','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],1,'seed'),
('b673d069-6c11-50f3-90bd-cceace563a48','546538fb-d097-5f89-b027-b3e786f75930','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],2,'seed'),
('ec0f82d8-c7b2-509d-9757-8f0e5822216b','546538fb-d097-5f89-b027-b3e786f75930','cost_type','要素','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],3,'seed'),
('cbb725f1-f7c6-54af-b169-a4ae4a9055f5','546538fb-d097-5f89-b027-b3e786f75930','base_value','基准值','MONEY',FALSE,'{}',4,'seed'),
('b10d0fa7-e206-5441-a0a0-620aa622d165','546538fb-d097-5f89-b027-b3e786f75930','cost_ratio','比例','NUMBER',FALSE,'{}',5,'seed'),
('15018717-3e57-57d5-bc27-b971070d0a49','546538fb-d097-5f89-b027-b3e786f75930','currency','货币','TEXT',FALSE,'{}',6,'seed'),
('369e3b59-9dcd-55bb-8433-f08b00f74eb8','546538fb-d097-5f89-b027-b3e786f75930','unit','计价单位','TEXT',FALSE,'{}',7,'seed'),
('2e260079-177c-50a8-8482-ea9e8ecb16d2','546538fb-d097-5f89-b027-b3e786f75930','is_fluctuate_with_material','是否随材料价格波动','TEXT',FALSE,'{}',8,'seed'),
('109d2815-3137-58df-8f39-a3572df263fa','546538fb-d097-5f89-b027-b3e786f75930','material_increase_ratio','材料结算涨幅比例','NUMBER',FALSE,'{}',9,'seed'),
('8ce3ec7d-f01d-545f-968c-b8f266ff3c3e','546538fb-d097-5f89-b027-b3e786f75930','material_fixed_increase','材料固定的涨幅值','MONEY',FALSE,'{}',10,'seed'),
('8170b788-e172-53c4-b661-d187d9a44b62','99fe2b0a-5e25-5c83-8708-4423a21bd7c1','code','投入料号','TEXT',TRUE,'{}',0,'seed'),
('7e6c5a60-41e2-551d-9d1c-15cb3a7b00f7','99fe2b0a-5e25-5c83-8708-4423a21bd7c1','finished_material_no','直接父件','TEXT',TRUE,'{}',1,'seed'),
('c964a445-1f1e-578d-948e-da2f8aa19718','99fe2b0a-5e25-5c83-8708-4423a21bd7c1','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],2,'seed'),
('ad543700-a2fe-5865-a2c0-f6162a5b5daa','99fe2b0a-5e25-5c83-8708-4423a21bd7c1','cost_ratio','回收折扣%','NUMBER',FALSE,'{}',3,'seed'),
('93e8bf83-7b60-5e23-ae13-fe2abf9bbc61','99fe2b0a-5e25-5c83-8708-4423a21bd7c1','pricing_price','值','MONEY',FALSE,'{}',4,'seed'),
('275632d8-7ab8-5384-adb0-8e5290a57d7b','99fe2b0a-5e25-5c83-8708-4423a21bd7c1','currency','货币','TEXT',FALSE,'{}',5,'seed'),
('6d0bf9e2-edb3-5758-8580-2470da251c60','99fe2b0a-5e25-5c83-8708-4423a21bd7c1','unit','计价单位','TEXT',FALSE,'{}',6,'seed'),
('24880657-c812-5895-aa61-c29c188e0953','6a745bc9-f61a-5e97-a38c-e914d8475e2e','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],0,'seed'),
('89b2c9a1-86d3-5148-8706-0f6ec104deaa','6a745bc9-f61a-5e97-a38c-e914d8475e2e','discount_order','年降顺序','NUMBER',TRUE,'{}',1,'seed'),
('ae9ef636-7ee4-5324-a168-0b59596ecb55','6a745bc9-f61a-5e97-a38c-e914d8475e2e','discount_ratio','年降比例','NUMBER',FALSE,'{}',2,'seed'),
('848ef8a9-1860-51f1-b20a-90ad55789bed','6a745bc9-f61a-5e97-a38c-e914d8475e2e','discount_times','年降次数','NUMBER',FALSE,'{}',3,'seed'),
('1fb7039a-066c-5978-9804-0d8e3ccb50f0','6a745bc9-f61a-5e97-a38c-e914d8475e2e','fixed_discount_value','固定年降值','MONEY',FALSE,'{}',4,'seed'),
('a7dd7def-955e-557b-9a56-59e9a0d75ec3','6a745bc9-f61a-5e97-a38c-e914d8475e2e','currency','货币','TEXT',FALSE,'{}',5,'seed'),
('7cb2a675-3683-5ecc-bc42-f3eb1ede4780','6a745bc9-f61a-5e97-a38c-e914d8475e2e','unit','计价单位','TEXT',FALSE,'{}',6,'seed'),
('111ca028-7963-5209-bb02-c23af126a2e6','705d08e8-0869-5989-a4bc-8855b56d0d15','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],0,'seed'),
('6961a330-5d21-5acf-badd-68c1a12ec0d6','705d08e8-0869-5989-a4bc-8855b56d0d15','discount_order','年降顺序','NUMBER',TRUE,'{}',1,'seed'),
('ecd1db0b-f010-56b3-8f07-ccf0faee6da1','705d08e8-0869-5989-a4bc-8855b56d0d15','discount_ratio','年降比例','NUMBER',FALSE,'{}',2,'seed'),
('b19cc50b-3bb1-576d-9145-6be73513bbf2','705d08e8-0869-5989-a4bc-8855b56d0d15','discount_times','年降次数','NUMBER',FALSE,'{}',3,'seed'),
('3e3b6f04-3a61-51dd-8092-8037a79fa539','705d08e8-0869-5989-a4bc-8855b56d0d15','fixed_discount_value','固定年降值','MONEY',FALSE,'{}',4,'seed'),
('0a4d0595-1dae-5974-afe9-e5ac18b7343b','705d08e8-0869-5989-a4bc-8855b56d0d15','currency','货币','TEXT',FALSE,'{}',5,'seed'),
('e8351dac-7f22-512e-a694-254284a8ab87','705d08e8-0869-5989-a4bc-8855b56d0d15','unit','计价单位','TEXT',FALSE,'{}',6,'seed'),
('c67b2126-525f-59b2-b65e-b277ce01ac39','96a33c62-4134-59de-8acb-f1306363c150','seq_no','项次','NUMBER',FALSE,ARRAY['SORT']::TEXT[],0,'seed'),
('ae85a60e-11b8-5a64-9660-cc0df1aa68b8','96a33c62-4134-59de-8acb-f1306363c150','discount_order','年降顺序','NUMBER',TRUE,'{}',1,'seed'),
('f2336c4a-3b9f-5da6-b7db-c61752061050','96a33c62-4134-59de-8acb-f1306363c150','discount_ratio','年降比例','NUMBER',FALSE,'{}',2,'seed'),
('06cbb3ff-2581-510b-9b54-3d684afbaf9c','96a33c62-4134-59de-8acb-f1306363c150','discount_times','年降次数','NUMBER',FALSE,'{}',3,'seed'),
('677b7f72-7dd3-56b8-a7a0-ec1006d6d569','96a33c62-4134-59de-8acb-f1306363c150','fixed_discount_value','固定年降值','MONEY',FALSE,'{}',4,'seed'),
('7957b092-e981-5b79-b385-7ff1cbdbd797','96a33c62-4134-59de-8acb-f1306363c150','currency','货币','TEXT',FALSE,'{}',5,'seed'),
('c4aa9e61-2899-5897-8c83-4bad07327bfe','96a33c62-4134-59de-8acb-f1306363c150','unit','计价单位','TEXT',FALSE,'{}',6,'seed'),
('7c65a24e-3931-5602-9ce7-ed10f3a47cb6','45d8b491-1f36-5bbf-b310-f104e744d4f5','code','投入料号','TEXT',TRUE,ARRAY['PART_NO','ROW_KEY']::TEXT[],0,'seed'),
('49406b83-fa57-56f6-a734-58bc1b44f2cd','45d8b491-1f36-5bbf-b310-f104e744d4f5','seq_no','项次（一级）','NUMBER',FALSE,ARRAY['SORT']::TEXT[],1,'seed'),
('5c37f754-c051-58dc-a39a-b132a103903d','45d8b491-1f36-5bbf-b310-f104e744d4f5','cost_type','要素','TEXT',TRUE,ARRAY['ROW_KEY']::TEXT[],2,'seed'),
('d2512279-2ad6-5e06-bc8e-54c3535fdf0b','45d8b491-1f36-5bbf-b310-f104e744d4f5','pricing_price','值','MONEY',FALSE,'{}',3,'seed'),
('200c60d8-d6e1-5b93-92a8-0520b02e5db1','45d8b491-1f36-5bbf-b310-f104e744d4f5','cost_ratio','比例','NUMBER',FALSE,'{}',4,'seed'),
('03aed6a4-4a0f-5a01-891b-f162bcf7edc4','45d8b491-1f36-5bbf-b310-f104e744d4f5','currency','货币','TEXT',FALSE,'{}',5,'seed'),
('d0735f4c-340f-5f08-941c-3df7dc510358','45d8b491-1f36-5bbf-b310-f104e744d4f5','unit','计价单位','TEXT',FALSE,'{}',6,'seed'),
('e3036069-0452-5600-8abe-6e4c814837cc','d9e9192b-068f-523b-a4d9-3ec30ea500ab','material_no','物料料号','TEXT',TRUE,'{}',0,'seed'),
('7672aaa3-fb7c-5056-8d39-9b8448f0e19d','d9e9192b-068f-523b-a4d9-3ec30ea500ab','material_name','物料名称','TEXT',FALSE,ARRAY['PART_NAME']::TEXT[],1,'seed'),
('e76fc04e-2a20-5e00-a6cd-b5b97d787263','3fa2be85-aa65-5f24-bfc6-8a772f30b3f3','code','材质代码','TEXT',TRUE,'{}',0,'seed'),
('fa70008a-ef75-5cde-8a65-ea68bab77c87','3fa2be85-aa65-5f24-bfc6-8a772f30b3f3','name','材质名称','TEXT',FALSE,ARRAY['PART_NAME']::TEXT[],1,'seed'),
('8874ea6b-b1a3-5370-9754-bb3e0fe703e0','ce7cfb81-6f2b-5334-9e56-81ad1ed5e69d','process_no','工序号','TEXT',TRUE,'{}',0,'seed'),
('ecdd8794-ad25-5bfd-b5d4-5ba3af825355','ce7cfb81-6f2b-5334-9e56-81ad1ed5e69d','process_name','工序名','TEXT',FALSE,ARRAY['ROW_KEY']::TEXT[],1,'seed'),
('7c0cc7e4-7c93-528b-b672-7b4a1c1a631d','c45591e9-8f53-529e-b80f-22657967256b','element_code','元素符号','TEXT',TRUE,'{}',0,'seed'),
('08981be6-f3af-50d3-baf7-622f10d5cbbe','c45591e9-8f53-529e-b80f-22657967256b','element_name','元素名称','TEXT',FALSE,ARRAY['ROW_KEY']::TEXT[],1,'seed'),
('d4fc5231-bced-5a49-9cbb-24c2f5fcedd4','73f1dd06-dd79-5c87-a2b3-7adcfe3412d0','material_no','料号','TEXT',TRUE,'{}',0,'seed'),
('f3990a89-717a-59c2-a9d9-030399071aaf','73f1dd06-dd79-5c87-a2b3-7adcfe3412d0','customer_material_name','客户料号名称','TEXT',FALSE,ARRAY['PART_NAME']::TEXT[],1,'seed'),
('f100e280-a298-5f18-94ce-a144a242af5e','09d4b14c-3dfe-5618-adb1-a22ed10d43d8','unit_price','元素单价','MONEY',FALSE,'{}',0,'seed'),
('1aaa2bba-1e09-565c-8e25-4c303f7642e9','09d4b14c-3dfe-5618-adb1-a22ed10d43d8','currency','货币','TEXT',FALSE,'{}',1,'seed');

-- ============ 种子数据：边 ============
INSERT INTO semantic_edge (id,from_node_id,to_node_id,edge_kind,cardinality,fallback_order,coalesce_group,assert_status,assert_sample_rows,note,created_by) VALUES
('b67fe927-ae72-5795-b944-691d0c794420','aa084d67-9972-5824-b64b-09430339cc5f','616cbe9d-80c4-5137-a517-2b51500b2b75','JOIN','MANY_TO_ONE',NULL,NULL,'PASS',NULL,'收窄：mcm.customer_no = :customerCode（客户维度收窄）','seed'),
('e7fb81ad-61d2-553b-8ffb-49a965db80b6','aa084d67-9972-5824-b64b-09430339cc5f','71e481c2-8049-50f0-b3b7-b0d253f7ca8c','GRAIN','ONE_TO_MANY',NULL,NULL,'NA',NULL,NULL,'seed'),
('0b7e9ac0-6df1-537f-b5a7-4139f9841e82','aa084d67-9972-5824-b64b-09430339cc5f','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','GRAIN','ONE_TO_MANY',NULL,NULL,'NA',NULL,NULL,'seed'),
('fe1ec0c2-f011-592e-b040-df84c0c76f10','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','ab66377c-a275-58f3-b6bc-ab2510ad4631','SAME','MANY_TO_ONE',NULL,NULL,'PASS',NULL,'同表同粒度，无连接键','seed'),
('e58ef8c1-68ec-5a1c-b213-b4265a8c40f1','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','SUB','MANY_TO_ONE',NULL,NULL,'PASS',NULL,'相关标量子查询，非 LEFT JOIN；characteristic=RECIPE 边','seed'),
('8b59a665-58dc-5ea0-9342-e01b491ef7cb','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','3fa2be85-aa65-5f24-bfc6-8a772f30b3f3','LOOKUP','MANY_TO_ONE',0,'ebi_name','PASS',NULL,NULL,'seed'),
('9dbb189b-acf0-58c5-957c-da6f6732aa82','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','d9e9192b-068f-523b-a4d9-3ec30ea500ab','LOOKUP','MANY_TO_ONE',1,'ebi_name','PASS',NULL,NULL,'seed'),
('c66f52be-6860-55ed-9913-9efe8ddd1e6e','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','c45591e9-8f53-529e-b80f-22657967256b','LOOKUP','MANY_TO_ONE',0,NULL,'PASS',NULL,NULL,'seed'),
('e8502423-fb83-5af1-a2f3-f6eeb6fc1462','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','09d4b14c-3dfe-5618-adb1-a22ed10d43d8','PRICE','MANY_TO_ONE',NULL,NULL,'PASS',NULL,'双条件 JOIN；cep.material_no 必须与 hf_part_no 表达式逐字一致（AC-1⑤）','seed'),
('7bf7a949-d4f1-58e0-976c-5579022221d7','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','a4d7ef7a-6a44-5b04-a63e-e1f3579a66fc','SUB','MANY_TO_ONE',NULL,NULL,'PASS',NULL,'附加谓词 pl.price_type=''PLATING''','seed'),
('e9034a58-ee53-52c0-bed8-7fdf40e213a9','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','SUB','MANY_TO_ONE',NULL,NULL,'PASS',NULL,'characteristic=ASSEMBLY 边','seed'),
('6f5509b4-2739-56d9-b546-778cbb6017a2','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','d9e9192b-068f-523b-a4d9-3ec30ea500ab','LOOKUP','MANY_TO_ONE',0,NULL,'PASS',NULL,NULL,'seed'),
('e8f70040-5d6c-5394-a9f7-e17ad58541ca','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','ce7cfb81-6f2b-5334-9e56-81ad1ed5e69d','LOOKUP','MANY_TO_ONE',0,NULL,'PASS',NULL,NULL,'seed'),
('c5c92eee-bf7d-57b9-9ba0-40e69cdcfa85','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','3dc89f8b-bd2f-5436-8a7e-54876e96341e','SUB','MANY_TO_ONE',NULL,NULL,'PASS',NULL,'附加谓词 co.price_type=''COMPONENT_OTHER''','seed'),
('777b091b-91de-5b0c-b0a9-14071a119d6b','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','d9e9192b-068f-523b-a4d9-3ec30ea500ab','LOOKUP','MANY_TO_ONE',0,NULL,'PASS',NULL,'外购件与 BOM 树两页签共用','seed'),
('4e40e2d9-e1b6-5f91-a53b-cc2c9c5ca6f5','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','ce7cfb81-6f2b-5334-9e56-81ad1ed5e69d','LOOKUP','MANY_TO_ONE',0,NULL,'PASS',NULL,NULL,'seed'),
('35545ae1-a771-560f-aadd-a43046c160d2','546538fb-d097-5f89-b027-b3e786f75930','d9e9192b-068f-523b-a4d9-3ec30ea500ab','LOOKUP','MANY_TO_ONE',0,'ll_name','PASS',4,NULL,'seed'),
('cfb155f4-dd5b-5514-be10-a2a5c326ef7a','546538fb-d097-5f89-b027-b3e786f75930','3fa2be85-aa65-5f24-bfc6-8a772f30b3f3','LOOKUP','MANY_TO_ONE',1,'ll_name','PASS',4,'src=ll_view 实测确认','seed'),
('583aa9e5-a9f6-5a7a-b580-d1e93efc0bcd','546538fb-d097-5f89-b027-b3e786f75930','ce7cfb81-6f2b-5334-9e56-81ad1ed5e69d','LOOKUP','MANY_TO_ONE',0,NULL,'PASS',4,'src=ll_view 实测确认；仅「来料固定加工费」variant 有此列，lqt_view 无','seed'),
('2a9aad2d-5692-5e73-a647-37c48b436a3a','45d8b491-1f36-5bbf-b310-f104e744d4f5','d9e9192b-068f-523b-a4d9-3ec30ea500ab','LOOKUP','MANY_TO_ONE',0,'lqt_name','PASS',NULL,'src=lqt_view 实测确认','seed'),
('b8a895bb-c2a1-58f5-911e-894ccdabcdc1','45d8b491-1f36-5bbf-b310-f104e744d4f5','3fa2be85-aa65-5f24-bfc6-8a772f30b3f3','LOOKUP','MANY_TO_ONE',1,'lqt_name','PASS',NULL,'src=lqt_view 实测确认','seed'),
('6355d369-7719-568b-8017-72091d93e8c8','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','99fe2b0a-5e25-5c83-8708-4423a21bd7c1','SUB','MANY_TO_ONE',NULL,NULL,'THIN',1,'src=bom_view 实测确认；THIN（全库仅 1 行，D-32 已知假阴性盲区）；三层以上 BOM 孙件行取不到值（现网既有取舍）','seed');

-- ============ 种子数据：边的连接键 ============
INSERT INTO semantic_edge_key (id,edge_id,left_column,right_column,seq) VALUES
('c63a7c2b-5971-5db5-b237-f2f9e7269ba3','b67fe927-ae72-5795-b944-691d0c794420','material_no','material_no',0),
('85153d24-fe55-5620-84e1-62feecee730b','e7fb81ad-61d2-553b-8ffb-49a965db80b6','material_no','code',0),
('7cc28f43-b28b-5952-9ee7-d24422298011','0b7e9ac0-6df1-537f-b5a7-4139f9841e82','material_no','material_no',0),
('dfdf22f8-b031-5153-8e68-306896c033ea','e58ef8c1-68ec-5a1c-b213-b4265a8c40f1','material_no','material_no',0),
('a214388f-27d6-5a21-8516-f093c43fd2be','e58ef8c1-68ec-5a1c-b213-b4265a8c40f1','material_part_no','component_no',1),
('668b97b4-889a-5085-b47f-64b16bdf85b7','8b59a665-58dc-5ea0-9342-e01b491ef7cb','material_part_no','code',0),
('8c2fbdd7-b1c2-5ab8-b8f5-af4173c7af2c','9dbb189b-acf0-58c5-957c-da6f6732aa82','material_part_no','material_no',0),
('06181181-ee4c-5995-b52f-e19fec1724a0','c66f52be-6860-55ed-9913-9efe8ddd1e6e','component_no','element_code',0),
('0c7d4624-c196-5d8a-9e05-d48567bec516','e8502423-fb83-5af1-a2f3-f6eeb6fc1462','component_no','element_code',0),
('8faec084-cca6-58d7-abc0-0acce3109f68','e8502423-fb83-5af1-a2f3-f6eeb6fc1462','material_no','material_no',1),
('984464a7-2b17-5d3d-97ba-5773ddc3ba8f','7bf7a949-d4f1-58e0-976c-5579022221d7','code','code',0),
('bb08da21-6416-58e4-b1bf-2ea55cf20aa7','e9034a58-ee53-52c0-bed8-7fdf40e213a9','finished_material_no','material_no',0),
('2f780864-9e3a-52f4-ac44-ffdfb759b667','e9034a58-ee53-52c0-bed8-7fdf40e213a9','code','component_no',1),
('b4b1e2b0-53b2-5a14-8d25-3c2560a3d6ca','6f5509b4-2739-56d9-b546-778cbb6017a2','code','material_no',0),
('8479de47-1bad-59e4-962e-99f8957d0447','e8f70040-5d6c-5394-a9f7-e17ad58541ca','operation_no','process_no',0),
('b6e914ce-02e5-5ba9-bfde-9673876cba3b','c5c92eee-bf7d-57b9-9ba0-40e69cdcfa85','material_no','finished_material_no',0),
('6aaeed73-8ae3-5816-9711-164a53ccb2f4','c5c92eee-bf7d-57b9-9ba0-40e69cdcfa85','component_no','code',1),
('cf4fc3e4-cf97-5ce9-bab4-778662da5b31','777b091b-91de-5b0c-b0a9-14071a119d6b','component_no','material_no',0),
('bf4ee885-95ce-54bd-b4bd-a20b8448ef74','4e40e2d9-e1b6-5f91-a53b-cc2c9c5ca6f5','operation_no','process_no',0),
('c432ee19-41df-5d32-8093-ea4e70fa7b26','35545ae1-a771-560f-aadd-a43046c160d2','code','material_no',0),
('fa32f00c-8804-525c-9bdb-24fc3b47fce2','cfb155f4-dd5b-5514-be10-a2a5c326ef7a','code','code',0),
('0a0a2e5a-3936-5a64-a8e6-b8fc33fcf35a','583aa9e5-a9f6-5a7a-b580-d1e93efc0bcd','operation_no','process_no',0),
('b4277efd-7d63-5b00-af7d-f89123c293a1','2a9aad2d-5692-5e73-a647-37c48b436a3a','code','material_no',0),
('8d9987c1-b1b0-5f4a-ada6-7317d052003d','b8a895bb-c2a1-58f5-911e-894ccdabcdc1','code','code',0),
('de85e4a4-779d-5d9f-bda7-d80fe831afd8','6355d369-7719-568b-8017-72091d93e8c8','component_no','code',0),
('78c7d2d3-b7a6-50dc-8250-21a05676286a','6355d369-7719-568b-8017-72091d93e8c8','material_no','finished_material_no',1);

-- ============ 种子数据：页签视图 ============
INSERT INTO semantic_tab_view (id,tab_type,variant_key,variant_label,anchor_node_id,switches,dialect,created_by) VALUES
('ecf9683b-b8da-50c2-816c-9c9b9587def8','主件','',NULL,'aa084d67-9972-5824-b64b-09430339cc5f','{}','QUOTE','seed'),
('d3bac52f-64fe-5439-8078-1328e1516375','材质元素','',NULL,'6bfd2a0c-0cda-5c99-acd0-a14aefe10b46',ARRAY['CLOSURE']::TEXT[],'QUOTE','seed'),
('27209cf2-a305-5eb6-af87-cc2581448917','零件','',NULL,'339b23d8-0ae8-5c0b-912b-b0a8a521d1ee',ARRAY['CLOSURE']::TEXT[],'QUOTE','seed'),
('3f29e1a9-a990-51e4-a97e-9b2a0c455841','外购件','',NULL,'7307ccc7-06b3-5e3c-9beb-82aef17f0a73',ARRAY['CLOSURE']::TEXT[],'QUOTE','seed'),
('c808e618-b6f2-5bb0-974d-fc563c6417e4','费用类','INCOMING_FIXED','来料固定加工费','546538fb-d097-5f89-b027-b3e786f75930',ARRAY['CLOSURE']::TEXT[],'QUOTE','seed'),
('d5d7917f-2270-5184-a049-677135e30023','费用类','INCOMING_OTHER','来料其他费用','45d8b491-1f36-5bbf-b310-f104e744d4f5',ARRAY['CLOSURE']::TEXT[],'QUOTE','seed'),
('e522602c-938a-5e69-b4fb-ab8fc0312a99','BOM 树','',NULL,'7307ccc7-06b3-5e3c-9beb-82aef17f0a73','{}','QUOTE','seed');

-- ============ 种子数据：页签可用节点（主源/附属源） ============
INSERT INTO semantic_tab_view_node (id,view_id,node_id,role,add_dims,sort_order,created_by) VALUES
('2190d22a-6352-59b9-a74f-686cf0b15fff','ecf9683b-b8da-50c2-816c-9c9b9587def8','aa084d67-9972-5824-b64b-09430339cc5f','MAIN','{}',0,'seed'),
('b4dcc434-2cf7-504d-830c-b21397a48621','ecf9683b-b8da-50c2-816c-9c9b9587def8','616cbe9d-80c4-5137-a517-2b51500b2b75','AUX','{}',1,'seed'),
('1973983f-fafd-5066-abfc-006b79a0bc69','ecf9683b-b8da-50c2-816c-9c9b9587def8','71e481c2-8049-50f0-b3b7-b0d253f7ca8c','AUX','{}',2,'seed'),
('20ab4c0d-6179-5ebe-b2b9-5986d2cdbd4f','ecf9683b-b8da-50c2-816c-9c9b9587def8','d11dc5dd-3354-5076-9254-f8c5d0e4d8db','AUX','{}',3,'seed'),
('cb2b87bd-fea4-554c-befa-35f2c946dff6','d3bac52f-64fe-5439-8078-1328e1516375','6bfd2a0c-0cda-5c99-acd0-a14aefe10b46','MAIN','{}',0,'seed'),
('5d82c85e-aa53-51f3-ad50-8b6e323f90a8','d3bac52f-64fe-5439-8078-1328e1516375','ab66377c-a275-58f3-b6bc-ab2510ad4631','AUX','{}',1,'seed'),
('3f14a367-27a8-582b-a0db-38a765f13828','d3bac52f-64fe-5439-8078-1328e1516375','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','AUX','{}',2,'seed'),
('51bcda61-7350-5e3a-8052-27dcd9fa5767','27209cf2-a305-5eb6-af87-cc2581448917','339b23d8-0ae8-5c0b-912b-b0a8a521d1ee','MAIN','{}',0,'seed'),
('99b2776a-7843-5e50-b550-a6233b3df049','27209cf2-a305-5eb6-af87-cc2581448917','a4d7ef7a-6a44-5b04-a63e-e1f3579a66fc','AUX',ARRAY['费用类型']::TEXT[],1,'seed'),
('35954f6b-f162-53d5-bd91-c0c77ff174e1','27209cf2-a305-5eb6-af87-cc2581448917','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','AUX','{}',2,'seed'),
('f30b5fd9-025d-5e23-b4a3-b21b93c3fe4c','3f29e1a9-a990-51e4-a97e-9b2a0c455841','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','MAIN','{}',0,'seed'),
('e282fd8a-9097-5ddf-9500-3fc576e9a315','3f29e1a9-a990-51e4-a97e-9b2a0c455841','3dc89f8b-bd2f-5436-8a7e-54876e96341e','AUX',ARRAY['要素']::TEXT[],1,'seed'),
('8c5116bb-ee59-509e-81ec-7bb4218414fe','c808e618-b6f2-5bb0-974d-fc563c6417e4','546538fb-d097-5f89-b027-b3e786f75930','MAIN','{}',0,'seed'),
('e24db5a1-e038-519c-9250-796a74942550','d5d7917f-2270-5184-a049-677135e30023','45d8b491-1f36-5bbf-b310-f104e744d4f5','MAIN','{}',0,'seed'),
('8421dae5-572a-5029-bcd0-975733b6624e','e522602c-938a-5e69-b4fb-ab8fc0312a99','7307ccc7-06b3-5e3c-9beb-82aef17f0a73','MAIN','{}',0,'seed'),
('834c8450-cb77-5b82-a315-26a4c72f6d90','e522602c-938a-5e69-b4fb-ab8fc0312a99','99fe2b0a-5e25-5c83-8708-4423a21bd7c1','AUX','{}',1,'seed');

-- ============ 种子数据：页签级列角色覆盖（D-35） ============
INSERT INTO semantic_tab_view_column (id,view_id,column_id,roles,sort_order,created_by) VALUES
('afebc781-9753-52cb-b7de-20310069b372','ecf9683b-b8da-50c2-816c-9c9b9587def8','36127c91-513c-5e65-8eff-a6fbcde6e410',ARRAY['PART_NO','ROW_KEY']::TEXT[],0,'seed'),
('4dcba019-d2fa-501a-9d6b-786c1469edd2','ecf9683b-b8da-50c2-816c-9c9b9587def8','c789b55b-fd65-5a9f-9fe3-acd9ee226771',ARRAY['PART_NAME']::TEXT[],1,'seed'),
('049caecb-0bea-5bed-8716-c27e3a18ebfa','d3bac52f-64fe-5439-8078-1328e1516375','425e6ef5-ac0a-57b1-b8a6-67f34d439420',ARRAY['PART_NO','ROW_KEY']::TEXT[],2,'seed'),
('78db6e38-8187-5a19-bb88-20f751a526a2','d3bac52f-64fe-5439-8078-1328e1516375','23614d7b-4c0b-544e-8357-05b6862deca4',ARRAY['ROW_KEY']::TEXT[],3,'seed'),
('9147c236-401b-5dfa-86b8-2e3f7628f111','d3bac52f-64fe-5439-8078-1328e1516375','258a0191-8db6-591d-a3d9-cbdd96189f01',ARRAY['ROW_KEY']::TEXT[],4,'seed'),
('cc256f9c-e3df-546a-b0d0-6049a897d00e','d3bac52f-64fe-5439-8078-1328e1516375','fa70008a-ef75-5cde-8a65-ea68bab77c87',ARRAY['PART_NAME','ROW_KEY']::TEXT[],5,'seed'),
('00d95d3e-fadd-5480-88c3-45d14e80a4b5','d3bac52f-64fe-5439-8078-1328e1516375','7672aaa3-fb7c-5056-8d39-9b8448f0e19d',ARRAY['PART_NAME','ROW_KEY']::TEXT[],6,'seed'),
('6ea92dbe-2032-5512-87aa-58ead7e4eb36','d3bac52f-64fe-5439-8078-1328e1516375','08981be6-f3af-50d3-baf7-622f10d5cbbe',ARRAY['ROW_KEY']::TEXT[],7,'seed'),
('620e14e2-2698-55f1-bffd-08e45fa64848','27209cf2-a305-5eb6-af87-cc2581448917','48ddf797-7c2a-5ac4-9a35-05d6d84ae322',ARRAY['PART_NO','ROW_KEY']::TEXT[],8,'seed'),
('cff084e4-e5a0-54f3-90c6-a11e7bc32f01','27209cf2-a305-5eb6-af87-cc2581448917','12c8d185-5027-5312-b91e-516c0ce13c65',ARRAY['ROW_KEY']::TEXT[],9,'seed'),
('f2530960-46f9-573f-a34e-591e893a868c','27209cf2-a305-5eb6-af87-cc2581448917','7672aaa3-fb7c-5056-8d39-9b8448f0e19d',ARRAY['PART_NAME','ROW_KEY']::TEXT[],10,'seed'),
('2597c2b0-1dc8-50ca-9945-3c6401361e46','27209cf2-a305-5eb6-af87-cc2581448917','ecdd8794-ad25-5bfd-b5d4-5ba3af825355',ARRAY['ROW_KEY']::TEXT[],11,'seed'),
('1b85debe-713d-5b1c-ad9a-6c2f8acad66c','3f29e1a9-a990-51e4-a97e-9b2a0c455841','b53a2d6a-e62b-59c0-b364-592fc2058de5',ARRAY['PART_NO','ROW_KEY']::TEXT[],12,'seed'),
('7e7b6156-75bb-5333-9fa9-77c32f0f1af4','3f29e1a9-a990-51e4-a97e-9b2a0c455841','c5e58925-2424-592f-ad23-96c256e9f870',ARRAY['ROW_KEY']::TEXT[],13,'seed'),
('dfa9e902-24b8-574c-a470-a8aa2fa83033','3f29e1a9-a990-51e4-a97e-9b2a0c455841','7672aaa3-fb7c-5056-8d39-9b8448f0e19d',ARRAY['PART_NAME','ROW_KEY']::TEXT[],14,'seed'),
('ed6fdeb4-1402-5700-b38e-a0acf18f06a1','c808e618-b6f2-5bb0-974d-fc563c6417e4','8fcdf3a8-9663-523c-83de-49acdef4124c',ARRAY['PART_NO','ROW_KEY']::TEXT[],15,'seed'),
('c69b1030-a918-52db-a48b-e0b96168f5ef','c808e618-b6f2-5bb0-974d-fc563c6417e4','736b7aec-0563-51c2-8a6e-74cb1c50ea3c',ARRAY['ROW_KEY']::TEXT[],16,'seed'),
('b89f91ac-5f79-5ae6-8c82-10d7ec78222a','c808e618-b6f2-5bb0-974d-fc563c6417e4','7672aaa3-fb7c-5056-8d39-9b8448f0e19d',ARRAY['PART_NAME','ROW_KEY']::TEXT[],17,'seed'),
('718fd71d-0c73-5da7-86ef-8ab58b6a1754','c808e618-b6f2-5bb0-974d-fc563c6417e4','ecdd8794-ad25-5bfd-b5d4-5ba3af825355',ARRAY['ROW_KEY']::TEXT[],18,'seed'),
('ee50e127-be2f-5d37-aac3-f86c5a6ad9b3','d5d7917f-2270-5184-a049-677135e30023','7c65a24e-3931-5602-9ce7-ed10f3a47cb6',ARRAY['PART_NO','ROW_KEY']::TEXT[],19,'seed'),
('820663ca-5cc2-5c1f-9012-521678a4cfb9','d5d7917f-2270-5184-a049-677135e30023','7672aaa3-fb7c-5056-8d39-9b8448f0e19d',ARRAY['PART_NAME','ROW_KEY']::TEXT[],20,'seed'),
('b780577f-bc0c-57a2-bee6-42d559f74f1d','e522602c-938a-5e69-b4fb-ab8fc0312a99','b53a2d6a-e62b-59c0-b364-592fc2058de5',ARRAY['PART_NO','ROW_KEY']::TEXT[],21,'seed'),
('b6d74c59-4e46-5e2e-88f0-c96a64e34732','e522602c-938a-5e69-b4fb-ab8fc0312a99','7672aaa3-fb7c-5056-8d39-9b8448f0e19d',ARRAY['PART_NAME','ROW_KEY']::TEXT[],22,'seed');

-- ============ B-14: component_sql_view 加 builder_config / builder_version（均 nullable） ============
-- 存量 builder_config IS NULL = 手写模式，行为逐字不变（AC-32）。
ALTER TABLE component_sql_view ADD COLUMN IF NOT EXISTS builder_config JSONB;
ALTER TABLE component_sql_view ADD COLUMN IF NOT EXISTS builder_version INT;


-- ==========================================================================
-- 第 2 节 · V389~V395：语义图种子修正（7 笔）+ semantic_edge 加 1 列
-- ==========================================================================


-- ------------------------------------------------------------------------
-- >>> V389__task260819_fix_element_bom_bogus_discriminator.sql
-- ------------------------------------------------------------------------
-- V389__task260819_fix_element_bom_bogus_discriminator.sql
-- 修正 V388 种子数据的判别式声明错误（本轮 B-5 编译器实测发现，backtask.md 回报）。
--
-- 根因：V388 给 ELEMENT_BOM_ITEM / ELEMENT_RECOVERY 两个节点声明了
-- discriminator = "characteristic = 'RECIPE'"，但 element_bom_item.characteristic 实际存的是
-- 一列与 material_bom_item.characteristic（真正的 RECIPE/ASSEMBLY/OUTSOURCED 三态枚举，
-- task-0720 落地）完全无关的数字编码（实测取值如 2001/2049/2050…，从未出现字符串 'RECIPE'）。
-- 现网手写基准视图 mc_view（COMP-0027 使用）对 element_bom_item 也从未加过 characteristic 过滤，
-- 只有 system_type/is_current/customer_no 三件套——与本次实测结论一致。
--
-- 症状：任何"材质元素"页签配置一旦真正预览/执行，WHERE ebi.characteristic = 'RECIPE' 恒为假，
-- 返回 0 行（AC-26 预期 2 行/闭包后 4 行，实测在去掉这条判别式后精确复现）。
-- 影响面：仅 semantic_node 表 2 行（ELEMENT_BOM_ITEM / ELEMENT_RECOVERY），discriminator 列。

UPDATE semantic_node
   SET discriminator = NULL, updated_at = now()
 WHERE node_key IN ('ELEMENT_BOM_ITEM', 'ELEMENT_RECOVERY')
   AND dialect = 'QUOTE';

-- ------------------------------------------------------------------------
-- >>> V390__task260819_restore_e02_grain_edge.sql
-- ------------------------------------------------------------------------
-- V390__task260819_restore_e02_grain_edge.sql
-- 补回被误删的 E02 GRAIN 边（主线 D-40 裁决 + 根因确认）。
--
-- 根因：本轮验 AC-15（GRAIN 成品其他费用分支）时，backend-engineer 用
-- PRODUCT_MASTER→FINISHED_OTHER 这对节点做 AC-52 边基数攻击测试，测完调
-- DELETE /edges/by-nodes 清理——该端点按 (from_node_id, to_node_id) 全删，不区分
-- edge_kind、不区分"种子边"还是"测试临时边"，把种子里本来就有的 E02（GRAIN）一并删了。
-- Flyway checksum 验证不出这类问题（迁移文件本身没变，是运行时业务数据被删）。
--
-- 主线已裁决（D-40）：① 本迁移补回该行；② DELETE /edges/by-nodes 端点本身随后下线。
--
-- 数据取自 V388 原始声明（保留原 id，逐字对齐 AC-51"种子迁移与原声明逐项等值"）：
--   from = 物料主档/单重 PRODUCT_MASTER (aa084d67-9972-5824-b64b-09430339cc5f)
--   to   = 成品其他费用 FINISHED_OTHER (71e481c2-8049-50f0-b3b7-b0d253f7ca8c)
--   edge_kind = GRAIN, cardinality = ONE_TO_MANY, fallback_order = NULL, coalesce_group = NULL
--   连接键：left=material_no(锚点自身列) / right=code(目标列)，seq=0 —— fo.code = mm.material_no

INSERT INTO semantic_edge
  (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group,
   assert_status, assert_sample_rows, note, created_by)
VALUES
  ('e7fb81ad-61d2-553b-8ffb-49a965db80b6',
   'aa084d67-9972-5824-b64b-09430339cc5f',
   '71e481c2-8049-50f0-b3b7-b0d253f7ca8c',
   'GRAIN', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL, NULL, 'seed')
ON CONFLICT (from_node_id, to_node_id, edge_kind) DO NOTHING;

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES
  ('85153d24-fe55-5620-84e1-62feecee730b',
   'e7fb81ad-61d2-553b-8ffb-49a965db80b6',
   'material_no', 'code', 0)
ON CONFLICT (edge_id, seq) DO NOTHING;

-- ------------------------------------------------------------------------
-- >>> V391__task260819_clean_builder_config_confirmedimpact.sql
-- ------------------------------------------------------------------------
-- V391__task260819_clean_builder_config_confirmedimpact.sql
-- 清洗 D-42 扁平化副作用造成的脏数据（主线裁决，2026-08-21）。
--
-- 根因：PUT /builder 保存时把 SaveRequest（BuilderConfig 子类，多带一个 confirmedImpact 字段）
-- 整个序列化进 component_sql_view.builder_config JSONB 列；下次 GET / 按纯 BuilderConfig 反
-- 序列化会撞 "Unrecognized field confirmedImpact" 500——任何"保存后不转手写、直接刷新页面"的
-- 真实用户路径都会炸。应用层已修（BuilderService.save 落库前剥离该字段），本迁移清洗存量脏行。
--
-- 影响面：dev 库 cpq_db_0724 当前 0 行受影响（尚无人跑过 PUT /builder），test 库 cpq_db 实测
-- 12 行 builder_config 非空且全部含 confirmedImpact——用 jsonb `-` 操作符原地删 key，不影响其余字段。

UPDATE component_sql_view
   SET builder_config = builder_config - 'confirmedImpact'
 WHERE builder_config IS NOT NULL
   AND builder_config ? 'confirmedImpact';

-- ------------------------------------------------------------------------
-- >>> V392__task260819_reset_fake_assert_status.sql
-- ------------------------------------------------------------------------
-- V392__task260819_reset_fake_assert_status.sql
-- 清掉 V388 种子里写死的假 assert_status（主线裁决 D-44，2026-08-21）。
--
-- 根因：V388 给 19 条边直接硬编码 assert_status='PASS'，但 SemanticGraphValidator 当时没有任何
-- 回写路径——PASS 只是种子文本，不代表任何校验真的跑过。实测证据：4 条 MANY_TO_ONE 边 PASS 但
-- assert_sample_rows 为空（断言压根没跑）；3 条 assert_sample_rows=4（<30）却是 PASS，违反 D-32
-- 的 THIN 判据。
--
-- 本迁移把 assert_status 全部重置为 'NA'（= 未校验，语义等价于"NULL"——列本身是 NOT NULL
-- DEFAULT 'NA'，不能存字面 SQL NULL）、assert_sample_rows 清空，不留假绿。真实值由：
--   ① POST/PUT /edges 保存时自动重算并写回（SemanticGraphService#recomputeAssertStatus）
--   ② 管理员随时可调 POST /config/semantic-graph/revalidate 全量重算
-- 两条路径产出，不再由种子文本伪装。

UPDATE semantic_edge
   SET assert_status = 'NA', assert_sample_rows = NULL, updated_at = now()
 WHERE status = 'ACTIVE';

-- ------------------------------------------------------------------------
-- >>> V393__task260819_customer_map_lookup_edge_and_fallback_switch.sql
-- ------------------------------------------------------------------------
-- V393__task260819_customer_map_lookup_edge_and_fallback_switch.sql
-- D-45②③（主线裁决，2026-08-21，AC-38 golden 首跑抓出的两个种子/schema 缺口）。

-- ============ ② 补边：物料主档/单重 → LOOKUP_CUSTOMER_MAP（AC-38 主件 golden 3 列配不出）============
-- 根因：LOOKUP_CUSTOMER_MAP 节点存在、fixed_predicate 齐备，但入边数为 0——没有任何边连过去，
-- 编译期任何寻址到它的列都报 COMPILE_PATH_NOT_FOUND。对照基准 cp_view 实际 SQL：
--   LEFT JOIN material_customer_map mcm ON mcm.material_no = mm.material_no AND mcm.customer_no = :customerCode
-- 连接键：material_no（锚点自身列） = material_no（目标列），LOOKUP 边，MANY_TO_ONE。
-- （不用 \gset：Flyway 走 JDBC 直接执行 SQL 文本，不经过 psql，元命令不可用——固定字面量 UUID。）

INSERT INTO semantic_edge
  (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group,
   assert_status, assert_sample_rows, note, created_by)
VALUES
  ('a4e7dd72-cd7a-463e-abdc-944c0c700f3d',
   'aa084d67-9972-5824-b64b-09430339cc5f',   -- PRODUCT_MASTER
   '73f1dd06-dd79-5c87-a2b3-7adcfe3412d0',   -- LOOKUP_CUSTOMER_MAP
   'LOOKUP', 'MANY_TO_ONE', NULL, NULL, 'NA', NULL,
   'D-45②：补种子遗漏——对照 cp_view 实际 SQL（LEFT JOIN material_customer_map mcm ON mcm.material_no=mm.material_no AND mcm.customer_no=:customerCode）',
   'seed');

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES ('ff3b4bae-ecfe-4a2e-af59-c9a87782aed3', 'a4e7dd72-cd7a-463e-abdc-944c0c700f3d', 'material_no', 'material_no', 0);

-- LOOKUP_CUSTOMER_MAP 当时只登记了 2 列（material_no / customer_material_name），
-- 但基准 cp_view 还用了 customer_product_no（客户产品编号）与 exchange_rate（汇率）——
-- 这两列在 CUSTOMER_MAP（SHEET 节点，同一张物理表）上已登记，此处补齐镜像，
-- 否则光补边还是配不出这两列（COMPILE_COLUMN_NOT_FOUND）。
INSERT INTO semantic_node_column (id, node_id, db_column, display_name, data_type, is_code, roles, sort_order, created_by)
VALUES
  ('2b6e6a1a-2f7a-4e4a-9a1a-9c9a1a2b6e6a', '73f1dd06-dd79-5c87-a2b3-7adcfe3412d0', 'customer_product_no', '客户产品编号', 'TEXT', FALSE, '{}', 2, 'seed'),
  ('3c7f7b2b-3a8b-4f5b-8b2b-8d8b2b3c7f7b', '73f1dd06-dd79-5c87-a2b3-7adcfe3412d0', 'exchange_rate', '汇率', 'NUMBER', FALSE, '{}', 3, 'seed');

-- ============ ③ fallback_to_join_key 开关（AC-38 零件 golden 工序列缺兜底）============
-- 基准 jg_view / ll_view 都用 COALESCE(pm.process_name, up.operation_no)——查名查不到时退回
-- 连接键左列（原始工序代码），且连接键左列恰好就是 operation_no。wg_view/mc_view 的同类查名
-- （COALESCE(mr.name, mm2.material_name)）则没有这个退回——是否退回是每条边的业务选择，
-- 所以做成开关而非默认行为。

ALTER TABLE semantic_edge ADD COLUMN IF NOT EXISTS fallback_to_join_key BOOLEAN NOT NULL DEFAULT false;

-- 只给真正需要的两条 →工序库(LOOKUP_PROCESS_MASTER) 边置 true（对照各自基准 SQL 实测确认）：
--   SELF_PROCESS(自制加工/零件)   → jg_view: COALESCE(pm.process_name, up.operation_no) ✅
--   INCOMING_FIXED(来料固定加工费) → ll_view: COALESCE(pm.process_name, up.operation_no) ✅
--   MATERIAL_BOM(外购件/BOM树)     → wg_view: 全文不引用 process_master，不适用 ❌ 保持 false
UPDATE semantic_edge SET fallback_to_join_key = true
 WHERE id IN (
   'e8f70040-5d6c-5394-a9f7-e17ad58541ca',  -- SELF_PROCESS -> LOOKUP_PROCESS_MASTER
   '583aa9e5-a9f6-5a7a-b580-d1e93efc0bcd'   -- INCOMING_FIXED -> LOOKUP_PROCESS_MASTER
 );

-- ------------------------------------------------------------------------
-- >>> V394__task260819_self_process_material_recipe_coalesce.sql
-- ------------------------------------------------------------------------
-- V394__task260819_self_process_material_recipe_coalesce.sql
-- 零件「料件」列缺材质库回退（主线裁决，2026-08-21，AC-38 golden 复验第二轮抓出）。
--
-- 基准 jg_view：COALESCE(mm.material_name, mr.name) AS _料件（物料主档 + 材质库双源）。
-- 配置器产物：只有 mm.material_name（单源）——根因是 SELF_PROCESS 的 LOOKUP 边缺一条
-- →材质库(LOOKUP_MATERIAL_RECIPE)，且现有 →物料主档 边没有 coalesce_group，编译器的多源
-- COALESCE 展开逻辑靠 coalesce_group 分组，没有组就只会走单源分支。
--
-- 对照材质元素那组（ebi_name 组：→材质库 fb=0、→物料主档 fb=1）：这里顺序相反——
-- jg_view 是 COALESCE(mm.material_name, mr.name)，物料主档在前 fb=0，材质库在后 fb=1，
-- 不能照抄材质元素的顺序。

-- ① 补边：SELF_PROCESS → LOOKUP_MATERIAL_RECIPE
--    连接键对照 jg_view 实际 SQL：LEFT JOIN material_recipe mr ON mr.code = up.code
INSERT INTO semantic_edge
  (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group,
   assert_status, assert_sample_rows, note, created_by)
VALUES
  ('b14346b2-62df-46f9-8be9-1b12ea12553c',
   '339b23d8-0ae8-5c0b-912b-b0a8a521d1ee',   -- SELF_PROCESS
   '3fa2be85-aa65-5f24-bfc6-8a772f30b3f3',   -- LOOKUP_MATERIAL_RECIPE
   'LOOKUP', 'MANY_TO_ONE', 1, 'jg_name', 'NA', NULL,
   'D-45 复验②：补种子遗漏——对照 jg_view 实际 SQL（LEFT JOIN material_recipe mr ON mr.code=up.code），'
   '与→物料主档边同组 jg_name，fallback_order=1（物料主档 fb=0 在前，材质库 fb=1 兜底在后，顺序对照 '
   'COALESCE(mm.material_name, mr.name)，与材质元素 ebi_name 组的顺序相反，不可颠倒）',
   'seed');

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES ('53503a9e-f60a-4674-b299-f2e599359d62', 'b14346b2-62df-46f9-8be9-1b12ea12553c', 'code', 'code', 0);

-- ② 给已有的 SELF_PROCESS → LOOKUP_MATERIAL_MASTER 边补 coalesce_group='jg_name'，
--    fallback_order 已是 0（物料主档在前），不用改。
-- ⚠️ 只改这一条边的 coalesce_group，绝不动 →工序库(LOOKUP_PROCESS_MASTER) 那条边的
--    fallback_order/coalesce_group——它是独立查名列，不属于本 COALESCE 组，这正是
--    V388 时期 uq_edge_fallback 索引范围写错踩过的坑（教训见 V388 注释）。
UPDATE semantic_edge
   SET coalesce_group = 'jg_name'
 WHERE id = '6f5509b4-2739-56d9-b546-778cbb6017a2';  -- SELF_PROCESS -> LOOKUP_MATERIAL_MASTER

-- ------------------------------------------------------------------------
-- >>> V395__task260819_element_bom_to_incoming_fixed_grain_edge.sql
-- ------------------------------------------------------------------------
-- V395__task260819_element_bom_to_incoming_fixed_grain_edge.sql
-- D-68（主线裁决，2026-08-25，AC-19 校验落地的前置种子缺口）。

-- ============ 补边：ELEMENT_BOM_ITEM（材质元素锚点）→ INCOMING_FIXED（来料固定加工费）============
-- 根因：材质元素 tab_view（d3bac52f-64fe-5439-8078-1328e1516375）只挂了 ELEMENT_RECOVERY/
-- MATERIAL_BOM 两个 AUX 节点（V388:365/366），INCOMING_FIXED 从未作为该页签可选的附属源，
-- 锚点也没有到它的声明边——AC-19「材质元素页签拖入附属源『来料加工费』」编译期直接
-- COMPILE_PATH_NOT_FOUND（"锚点「物料与元素BOM」没有到「来料固定加工费」的声明边"）。
--
-- 边类型选 GRAIN（而非 LOOKUP）：INCOMING_FIXED 是一张独立展开维度的表（grain_columns=['code']，
-- 对照 PRODUCT_MASTER→ASSEMBLY_FEE / PRODUCT_MASTER→FINISHED_OTHER 同款 GRAIN 边），
-- 不是查名用的标量 LOOKUP——一个「归属料号」下可能有多条来料固定加工费行（不同投入料号/工序/要素）。
--
-- 连接键：ebi.material_no（元素BOM「归属料号」列）＝ up.finished_material_no（INCOMING_FIXED
-- 物理列，unit_price 表实测存在，但未在 semantic_node_column 里登记——GRAIN/LOOKUP 边的连接键
-- 是编译器直接拼物理列名，不经 semantic_node_column 校验，与既有 GRAIN 边写法一致）。
--
-- （不用 \gset：Flyway 走 JDBC 直接执行 SQL 文本，元命令不可用——固定字面量 UUID。）

INSERT INTO semantic_edge
  (id, from_node_id, to_node_id, edge_kind, cardinality, fallback_order, coalesce_group,
   assert_status, assert_sample_rows, note, created_by)
VALUES
  ('c1a9e2b4-7f3d-5a6e-9c8b-2d4e6f8a0b1c',
   '6bfd2a0c-0cda-5c99-acd0-a14aefe10b46',   -- ELEMENT_BOM_ITEM（材质元素锚点）
   '546538fb-d097-5f89-b027-b3e786f75930',   -- INCOMING_FIXED（来料固定加工费）
   'GRAIN', 'ONE_TO_MANY', NULL, NULL, 'NA', NULL,
   'D-68：材质元素页签补挂附属源『来料加工费』——AC-19 校验需要先能编译通过，才谈得上校验小计阻断',
   'seed');

INSERT INTO semantic_edge_key (id, edge_id, left_column, right_column, seq)
VALUES ('d2b0f3c5-8e4f-5b7f-0d9c-3e5f7a9b1c2d', 'c1a9e2b4-7f3d-5a6e-9c8b-2d4e6f8a0b1c', 'material_no', 'finished_material_no', 0);

-- ============ 补挂：材质元素 tab_view 的可选节点（AUX，用户在字段面板真能拖到）============
INSERT INTO semantic_tab_view_node (id, view_id, node_id, role, add_dims, sort_order, created_by)
VALUES
  ('e3c1a4d6-9f5a-5c8a-1e0d-4f6a8b0c2d3e', 'd3bac52f-64fe-5439-8078-1328e1516375',
   '546538fb-d097-5f89-b027-b3e786f75930', 'AUX', '{}', 3, 'seed');


-- ==========================================================================
-- 第 3 节 · V396：quotation_line_component_data 去重 + 唯一约束  ⚠️ 含 DELETE，先看前置自检 4)
-- ==========================================================================


-- ------------------------------------------------------------------------
-- >>> V396__repair260829_dedupe_qlcd_add_unique_constraint.sql
-- ------------------------------------------------------------------------
-- repair-260829 B-7：saveDraft 批量路径曾静默重复写入 quotation_line_component_data
-- （问题说明.md ④4.9/⑤B-7——同一批数据被写两次，相隔 14 秒，后写的那批 tab_name/sort_order 缺失）。
-- 立项期实测（cpq_db_0724，2026-08-29）：全库 39,268 行中恰 6 组 (line_item_id, component_id)
-- 重复，全部集中在单张单 QT-20260807-0145 的 lineItem 76c33527-12a7-4610-a5ae-6d3fc83d9187。
--
-- ① 清理存量重复：每组保留「tab_name 非空」的那条；若组内多条皆非空（或皆为空），退化为保留
--    created_at 较早者。用窗口函数一次性通用处理，不写死具体 id/lineItem（其它环境如另有同类
--    脏数据，同一逻辑同样适用；本库实测该 DELETE 恰命中 6 行，已用 SELECT 预演见 test-report.md）。
WITH ranked AS (
    SELECT id,
           line_item_id,
           component_id,
           count(*) OVER (PARTITION BY line_item_id, component_id) AS grp_cnt,
           ROW_NUMBER() OVER (
               PARTITION BY line_item_id, component_id
               ORDER BY
                   CASE WHEN tab_name IS NULL OR tab_name = '' THEN 1 ELSE 0 END ASC,
                   created_at ASC,
                   id ASC
           ) AS rn
    FROM quotation_line_component_data
)
DELETE FROM quotation_line_component_data
WHERE id IN (SELECT id FROM ranked WHERE grp_cnt > 1 AND rn > 1);

-- ② 加唯一约束，防止同类重复写入再次静默发生（此后同 (line_item_id, component_id) 二次写入会
--    直接 DB 报错，而不是静默多一行——repair-260829 B-6 UPSERT 路径依赖本约束保证「结构判定」
--    不会因误判自己撞自己，见 QuotationService#payloadComponentIdSet 的调用方 AC-23）。
ALTER TABLE quotation_line_component_data
    ADD CONSTRAINT uq_qlcd_line_component UNIQUE (line_item_id, component_id);


-- ==========================================================================
-- 第 4 节 · V397：f_material_element_price 三参重载（pending 感知取价）
-- ==========================================================================


-- ------------------------------------------------------------------------
-- >>> V397__repair260830_material_element_price_pending_visibility.sql
-- ------------------------------------------------------------------------
-- =====================================================================================
-- repair-260830 元素单价 pending 不可见 · B-1
--
-- 根因（问题说明 §4.1）：f_material_element_price 是数据库函数，QuotePendingRewriter 的
--   文本改写够不到它内部；而该函数内部又自己读了一遍 material_bom_item / element_bom_item
--   并写死 is_current = true —— 于是同一次查询里同一张表被读了两遍，
--   外层那遍看得见 pending 影子行，函数里那遍看不见 ⇒ 候选料号集为空 ⇒ 元素单价 NULL。
--
-- 修法（方案乙，2026-08-30 用户 A0 裁决）：
--   ① 新增三参重载 (text, date, uuid)：candidate_materials 的两处过滤放开为
--      (is_current = true OR pending_quotation_id = p_pending_quotation_id)
--   ② 旧两参版 CREATE OR REPLACE 为委托调用（签名/返回列逐字不变）
--
-- 🔑 用重载而非改签名：无需 DROP FUNCTION，完整避开 CLAUDE.md §3.2「契约销毁」红线。
--    老调用方（8 个用 f_customer_element_price 的组件、PriceReconciler、
--    PriceAdjustVersionGenerationService）一行不用改。
--
-- 🔑 p_pending_quotation_id 为 NULL 时 `pending_quotation_id = NULL` 恒为 NULL，
--    在 WHERE 里等价 false ⇒ 函数自动退化为纯 is_current。
--    这正是核价侧（p_pq 恒 NULL）与冻结态需要的行为 —— E-4/E-5/E-6 零回归的技术依据。
--
-- 🔑 `is_current = true OR …` 的前半边不可删（E-8/AC-9）：删掉后核价侧候选 16→0、
--    老客户 CUST-0001/0002 候选→0、本单核价转正后单价再次变空（已实测）。
--
-- ⚠️ realtime 分支内部的 f_customer_element_price(p_customer_no, p_base_date) 保持两参不变 ——
--    它不读 BOM 表，与本 BUG 无关（问题说明 §4.5）。
-- =====================================================================================

-- ---------------------------------------------------------------------------
-- ① 三参重载：函数体逐字照抄 V369 两参版，仅 candidate_materials 两处过滤放开
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION f_material_element_price(
    p_customer_no          text,
    p_base_date            date,
    p_pending_quotation_id uuid
)
RETURNS TABLE(
    material_no  varchar,
    element_code varchar,
    unit_price   numeric,
    currency     varchar,
    price_unit   varchar
)
LANGUAGE sql
STABLE
AS $$
WITH pointers AS (              -- 该客户已升版的料号 -> 当前指针指向的版本
    SELECT material_no, version_id
      FROM material_price_version_ref
     WHERE customer_no = p_customer_no
),
versioned AS (                  -- 有指针的料号：直接读版本明细（权威快照，不重算）。
                                 -- current_price IS NULL 的行（该元素本期彻底无价且从无历史价）
                                 -- 不出现在这里，下面 realtime 分支会按元素级兜底补上（§11.3.2.1 第三行）。
    SELECT p.material_no, i.element_code, i.current_price AS unit_price,
           i.currency, i.price_unit
      FROM pointers p
      JOIN element_price_version_item i ON i.version_id = p.version_id
     WHERE i.current_price IS NOT NULL
),
candidate_materials AS (        -- 候选 material_no 全集：覆盖真实客户（报价侧 BOM/元素挂真实
                                 -- customer_no）与 '_GLOBAL_'（核价侧 BOM/元素主档全局共享，
                                 -- 客户维度只体现在元素价格策略上，不体现在 BOM 结构上）。
                                 -- repair-260830：is_current 半边管正式行（核价侧 / 老客户 / 已转正），
                                 -- pending_quotation_id 半边管本单 pending 影子行，两个分支各管一半、
                                 -- 缺一不可，与 QuotePendingRewriter 的表改写口径对齐。
    SELECT material_no FROM material_bom_item
     WHERE customer_no IN (p_customer_no, '_GLOBAL_')
       AND (is_current = true OR pending_quotation_id = p_pending_quotation_id)
    UNION
    SELECT material_no FROM element_bom_item
     WHERE customer_no IN (p_customer_no, '_GLOBAL_')
       AND (is_current = true OR pending_quotation_id = p_pending_quotation_id)
),
realtime AS (                   -- fallback：候选料号 × 全部实时算价元素（不改 f_customer_element_price 签名）
    SELECT cm.material_no, f.element_code, f.unit_price, f.currency, f.price_unit
      FROM candidate_materials cm
      CROSS JOIN f_customer_element_price(p_customer_no, p_base_date) f
)
SELECT v.material_no, v.element_code, v.unit_price, v.currency, v.price_unit
  FROM versioned v
UNION ALL
SELECT r.material_no, r.element_code, r.unit_price, r.currency, r.price_unit
  FROM realtime r
  LEFT JOIN versioned v2
    ON v2.material_no = r.material_no AND v2.element_code = r.element_code
 WHERE v2.material_no IS NULL;   -- 该 (料号,元素) 已由版本明细给出的不重复给实时价
$$;

COMMENT ON FUNCTION f_material_element_price(text, date, uuid) IS
  'repair-260830：料号×元素级取价（pending 感知版）。第三参 = 当前报价单 id，'
  '用于让 candidate_materials 同时看到本单 pending 影子行；传 NULL 时自动退化为纯 is_current（核价侧/冻结态）。';

-- ---------------------------------------------------------------------------
-- ② 两参版改为委托（签名与返回列逐字不变，老调用方零改动）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION f_material_element_price(
    p_customer_no text,
    p_base_date   date
)
RETURNS TABLE(
    material_no  varchar,
    element_code varchar,
    unit_price   numeric,
    currency     varchar,
    price_unit   varchar
)
LANGUAGE sql
STABLE
AS $$
    SELECT * FROM f_material_element_price(p_customer_no, p_base_date, NULL::uuid);
$$;

COMMENT ON FUNCTION f_material_element_price(text, date) IS
  'repair-260830：保留原签名，委托三参重载并传 NULL（行为与 V369 版逐字一致）。';


-- ==========================================================================
-- 第 5 节 · V398：quotation 加 user_data_version（保存草稿乐观锁）
-- ==========================================================================


-- ------------------------------------------------------------------------
-- >>> V398__task260901_quotation_user_data_version.sql
-- ------------------------------------------------------------------------
-- task-260901 B-3a：报价单「用户数据版本号」——保存草稿的乐观并发基线。
--
-- 背景（QuotationLineItem 类注释里记录的 4/4 复现事故）：
--   t0 后台 warm 加载 li（subtotal=37.330516）→ 开始算卡片值（0.5~1.7s）
--   t1 saveDraft 写入用户新编辑（subtotal→38.246716）并 commit
--   t2 warm commit → 整行 UPDATE 把 t0 的内存快照写回 → 用户的编辑被静默冲掉
-- @DynamicUpdate 只能缓解「不同列」的互相覆盖，同一列仍是后写覆盖先写。
-- 本列让前端携带基线版本号，服务端在悲观锁内比对，不一致直接 409 让用户刷新（用户裁决：强制刷新）。
--
-- ⚠️ 不要与 quotation_line_item / quotation_line_component_data 上已有的 row_version 混淆：
--    那是 V368 (task-0729 price-adjust) 的原生 SQL 乐观锁列，Hibernate 不映射、无触发器，
--    由 PriceReconciler 自己带 `WHERE row_version = :seen` 使用。两者互不相干，
--    也不要把任何一个改成 JPA @Version —— 会让 price-adjust 的既有写点全部失效。
--
-- 语义（api.md §4，前后端都必须遵守）：
--   递增：PUT /draft（本次有实际写入时）、PUT /quote-card-edit
--   ❌ 绝不递增：ensureCardValues / ensureExcelValues / snapshotQuotation /
--                CreateQuotationMaterializer 建单物化四步 / priceReconcile
--                —— 它们写的是系统自算的派生数据，用户什么都没做。若它们递增，
--                   用户打开页面等后台重算跑完就必然撞 409 →「保存→重算→必冲突→刷新」死循环。
--
-- 存量行取默认值 0；前端首次 GET 拿到 0 作为基线，语义正确（谁都还没改过）。
ALTER TABLE quotation ADD COLUMN IF NOT EXISTS user_data_version integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN quotation.user_data_version IS
  'task-260901：用户数据版本号（saveDraft/quote-card-edit 递增；派生数据写入绝不递增）。'
  '前端保存时携带 baseVersion，不匹配 → 409 STALE_VERSION。';


COMMIT;


-- ============================================================
-- Flyway 基线上调(在事务外, 单独执行)
-- ------------------------------------------------------------
-- 本脚本以手工方式完成了 V388~V398 的表结构变更。若目标库的 flyway_schema_history 里
-- 有 BASELINE 行, 把它上调到 398, 后续 Quarkus 启动时才不会重复执行这些迁移。
-- 若库中没有 flyway_schema_history 表, 本节报错时跳过, 上面的正文不受影响。
-- ============================================================

UPDATE public.flyway_schema_history
   SET version = '398',
       description = '<< Flyway Baseline >>',
       script = '<< Flyway Baseline >>'
 WHERE type = 'BASELINE'
   AND version IS NOT NULL
   AND version::int < 398;


-- ============================================================
-- 执行后自检(逐条核对期望值)
-- ------------------------------------------------------------
-- 1) 7 张新表都在(期望 7 行)
--    SELECT table_name FROM information_schema.tables
--     WHERE table_schema='public' AND table_name LIKE 'semantic\_%' ORDER BY 1;
--
-- 2) 种子行数(期望值 = dev 库 cpq_db_0724 实测终态, 逐项相等):
--    SELECT 'semantic_node' t, count(*) FROM semantic_node
--    UNION ALL SELECT 'semantic_node_column', count(*) FROM semantic_node_column
--    UNION ALL SELECT 'semantic_edge', count(*) FROM semantic_edge
--    UNION ALL SELECT 'semantic_edge_key', count(*) FROM semantic_edge_key
--    UNION ALL SELECT 'semantic_tab_view', count(*) FROM semantic_tab_view
--    UNION ALL SELECT 'semantic_tab_view_node', count(*) FROM semantic_tab_view_node
--    UNION ALL SELECT 'semantic_tab_view_column', count(*) FROM semantic_tab_view_column;
--    -- 期望: 23 / 145 / 25 / 29 / 7 / 17 / 23
--    -- (node_column 143+2=145, edge 22+1+1+1=25, edge_key 26+1+1+1=29, tab_view_node 16+1=17
--    --  —— V388 种子经 V390/V393/V394/V395 增补后的终态)
--
-- 3) V389/V392 的修正确实生效(期望均为 0 行)
--    SELECT count(*) FROM semantic_node
--     WHERE node_key IN ('ELEMENT_BOM_ITEM','ELEMENT_RECOVERY') AND discriminator IS NOT NULL;
--    SELECT count(*) FROM semantic_edge WHERE status='ACTIVE' AND assert_status <> 'NA';
--    ⚠️ 这里期望 0 是**刚跑完脚本时**的正确状态: V392 把 19 条边的 assert_status 全部重置为
--       'NA'(=未校验), 真值由两条运行时路径产出 —— ① 保存边时自动重算; ② 管理员调
--       POST /config/semantic-graph/revalidate 全量重算。所以内网库跑完本脚本后看到满屏
--       'NA' 是**对的**, 不是漏跑; 开发库 cpq_db_0724 显示 PASS/THIN 只是因为那边已经
--       revalidate 过。已实测: 两库 269 行种子在抹掉 assert_status 后**逐字段零差异**。
--
-- 4) V393 的新列在, 且恰 2 条边为 true
--    SELECT count(*) FROM semantic_edge WHERE fallback_to_join_key = true;   -- 期望 2
--
-- 5) V396 唯一约束已建成, 且无残留重复(期望 1 行 / 0 行)
--    SELECT conname FROM pg_constraint WHERE conname='uq_qlcd_line_component';
--    SELECT count(*) FROM (SELECT line_item_id, component_id FROM quotation_line_component_data
--                           GROUP BY 1,2 HAVING count(*)>1) t;
--
-- 6) V397 两个重载都在(期望 2 行: "text, date" 与 "text, date, uuid")
--    SELECT pg_get_function_identity_arguments(oid) FROM pg_proc
--     WHERE proname='f_material_element_price' ORDER BY 1;
--
-- 7) V398 新列在, 且存量行全为 0
--    SELECT column_name, data_type, column_default, is_nullable FROM information_schema.columns
--     WHERE table_name='quotation' AND column_name='user_data_version';
--    -- 期望: user_data_version | integer | 0 | NO
--    SELECT count(*) FROM quotation WHERE user_data_version <> 0;   -- 期望 0
--
-- 8) Flyway 基线(有 BASELINE 行时期望 398; 无该行则 0 行)
--    SELECT version FROM flyway_schema_history WHERE type='BASELINE';
-- ============================================================
