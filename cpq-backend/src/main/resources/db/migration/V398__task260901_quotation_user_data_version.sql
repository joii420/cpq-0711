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
