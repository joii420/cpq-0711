-- task-260907 · B-8 —— 元素价格策略闭环：语义图接入 f_customer_element_price
--
-- 服务的 AC：AC-8
-- 用户裁决（2026-09-07）：「加上，让元素价格策略闭环」
--
-- ============ 为什么要换 ============
-- 现有节点 FUNC_ELEMENT_PRICE 绑的是 f_material_element_price(销售料号 × 元素)。
-- 实测：f_material_element_price('CUST-0004') 返回 21 个料号，其中 ds_quote_* 独有的料号 = 0
--   ⇒ 对新表体系里的料号【恒返 0 行】，配置器配不出对新料号有效的元素单价列。
-- f_customer_element_price 才是价格策略函数本体：读 element_price_strategy（客户级默认 +
-- 客户×元素专属，专属优先）+ element_daily_price 行情，按 method(LATEST/AVG/MAX/MIN) 与时间窗
-- 算 raw × factor + premium。粒度 = 客户 × 元素，【料号不进运算】——
-- 与业务规则「一张报价单里的元素价格是统一的」一致。
--
-- ============ 🚦 「同一锚点两条 PRICE 边」的裁决：取代（选项 a），不共存 ============
-- 🚨 共存不可行，不是风格偏好，是【行为未定义】：
--    FieldTreeBuilder:272  snap.edgesFrom(anchor.id).filter(PRICE).findFirst()
--    SemanticCompiler:1315 同一形态
--    BuilderService:979    同一形态
--    三处都只取一条，取哪条取决于 edgesFrom 的列表顺序 —— 而 SemanticGraphLoader:55
--    是 SemanticEdge.listAll()，【没有 ORDER BY、也不按 status 过滤】。
--    ⇒ ① 选中的是哪条不确定；② 落选的那个节点因为不再等于 priceGroupNodeId，
--       会从 FieldTreeBuilder 的通用 tvns 循环里【再出一组】——
--       两组同名字段、viewColumn 与 isCore 不同，用户无从分辨该拖哪个，
--       从错的那块拖会让原子组语义失效。这正是 task-260907-取数配置器补齐 AC-30 的成因。
--
-- 🚫 因此也【不能靠 status='INACTIVE' 屏蔽老边】：实测 SemanticGraphLoader.loadSnapshot()
--    用的是 listAll()，快照【不按 status 过滤】，置 INACTIVE 的边照样进 edgesFrom。
--
-- ⇒ 采用「取代」：老节点 FUNC_ELEMENT_PRICE 原样保留（可能仍被别处引用），但
--   ① 那条 PRICE 边【改指向】新节点（不新增第二条）；
--   ② 它在 QUOTE/材质元素 上的 AUX 挂载也【改指向】新节点。
--   ②必须做 —— 否则老节点不再是 priceGroupNodeId，就会从通用循环里冒出第二组，
--   groupKind='PRICE' 的组数从 1 变 2，正是要避免的那个回归。
--
-- 基线（2026-09-07 实测 8081 = master 代码 + 共享库）：
--   GET /config/semantic-graph/field-tree?tabType=材质元素&dialect=QUOTE
--   → groups=2（MAIN:ELEMENT_BOM 12 列 + PRICE:FUNC_ELEMENT_PRICE 2 列），groupKind='PRICE' 组数 = 1
--   本迁移后期望：groups 仍为 2，PRICE 组数仍为 1，只是 groupKey 换成 FUNC_CUSTOMER_ELEMENT_PRICE 且列数 2→3。
--
-- id 采用确定性 uuid5（namespace = uuid5(URL, 'https://cpq/semantic-graph/task-260907/B-8')），与 V418 同风格。

-- ============ 1. 新节点 ============
INSERT INTO semantic_node
  (id,node_key,display_name,short_name,node_kind,physical_table,scope,anchor_expr,grain_columns,
   fixed_predicate,func_signature,discriminator,source_handler,dialect,note,created_by)
VALUES
('2b04d31b-0418-5c14-bf21-5758d729842b','FUNC_CUSTOMER_ELEMENT_PRICE','价格策略 f_customer_element_price','价格策略',
 'FUNCTION',NULL,'NONE',NULL,'{}',NULL,'f_customer_element_price(:customerCode, :priceBaseDate)',NULL,NULL,'QUOTE',
 'task-260907 B-8（AC-8）：元素价格策略闭环。粒度 = 客户 × 元素，料号不进运算 —— 故 PRICE 边只有 element_code 一个连接键（老边的 material_no 键已随本迁移删除）。别名仍固定为 cep（AC-1 铁律，SemanticCompiler.PRICE_FUNC_ALIAS）。只挂 QUOTE 方言：核价侧锚点是生产料号，语义对不上，不硬接。⚠️ QuotePendingRewriter 刻意不给本函数补 :pq（它不读任何 BOM 表，见该类 :142），所以没有三参重载也不会被补参打断。',
 'seed');

-- ============ 2. 节点列（3 列） ============
-- 🚨 price_unit 必须声明：老节点只声明了 unit_price/currency 两列，但函数实际返回 4 列且
--    price_unit 有值（实测 CUST-0004 全部为 'kg'）。少声明一列 = 配置器里永远拖不到计价单位。
INSERT INTO semantic_node_column (id,node_id,db_column,display_name,data_type,is_code,roles,sort_order,created_by) VALUES
('7ec68b53-9de0-5afe-9331-ea6ecfaee8c7','2b04d31b-0418-5c14-bf21-5758d729842b','unit_price','元素单价','MONEY',FALSE,'{}',0,'seed'),
('638636df-5629-5c28-a726-cdafc6033135','2b04d31b-0418-5c14-bf21-5758d729842b','currency','货币','TEXT',FALSE,'{}',1,'seed'),
('7d439864-e986-5956-bf30-dda52329a544','2b04d31b-0418-5c14-bf21-5758d729842b','price_unit','计价单位','TEXT',FALSE,'{}',2,'seed');

-- ============ 3. PRICE 边改指向新节点（唯一一条，不新增） ============
-- 老边：ELEMENT_BOM(QUOTE) --PRICE/MANY_TO_ONE--> FUNC_ELEMENT_PRICE，id 保持不变。
UPDATE semantic_edge
SET to_node_id = '2b04d31b-0418-5c14-bf21-5758d729842b',
    note = 'task-260907 B-8：改指向 f_customer_element_price。单条件 JOIN（cep.element_code = 锚点元素列）—— 新函数粒度是客户 × 元素，没有 material_no 列，保留料号键会生成 cep.material_no 而直接报 SQL 错。',
    updated_by = 'seed',
    updated_at = now()
WHERE id = '5b1bfc30-551b-511c-bb2b-42becb609a06';

-- 删掉料号连接键（seq=1）。影响面：本条 DELETE 命中 1 行（同 WHERE 的 SELECT count(*) 实测 = 1）。
-- 必须删：新函数的返回类型里没有 material_no 列，留着会让编译产物含 cep.material_no。
DELETE FROM semantic_edge_key
WHERE edge_id = '5b1bfc30-551b-511c-bb2b-42becb609a06'
  AND left_column = 'material_no';

-- ============ 4. QUOTE/材质元素 上的 AUX 挂载改指向新节点 ============
-- 🚨 漏了这一步，groupKind='PRICE' 的组数会从 1 变 2（见文件头的裁决说明）。
UPDATE semantic_tab_view_node
SET node_id = '2b04d31b-0418-5c14-bf21-5758d729842b',
    updated_by = 'seed',
    updated_at = now()
WHERE id = '38921742-f391-53b9-8d10-0326bf7cc189';

-- ============ 5. 落地后应成立的不变量（供人工复核；🚫 不在此处断言，迁移不做业务校验） ============
--   ① ELEMENT_BOM(QUOTE) 出边里 edge_kind='PRICE' 的条数 = 1
--   ② 该边的 semantic_edge_key 条数 = 1（element_code → element_code, seq=0）
--   ③ 材质元素/QUOTE 的 semantic_tab_view_node 仍是 2 行（ELEMENT_BOM MAIN + 价格策略 AUX）
--   ④ FUNC_ELEMENT_PRICE 节点仍在，但既无 PRICE 边、也无任何 tab_view 挂载
