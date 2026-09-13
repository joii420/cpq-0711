-- ============================================================================
-- task-260911 · 报价数据本单私有落地 · B-1 —— `_record` 两段式归属（R-2 / AC-1 / AC-24）
--
-- 服务的 AC：
--   AC-1  带版本表导入只落 `_record`（建单之前还没有 quotation_id ⇒ 必须可空）
--   AC-24 未建单批次的 `_record` 也能按 import_batch_id 回收
--
-- 做两件事，对 13 张 `ds_quote_*_record` 逐表：
--   ① quotation_id  DROP NOT NULL        —— 导入落库时本单还不存在（岔路 2 裁决「丙」）
--   ② 新增 import_batch_id uuid + 索引    —— 对应 import_record.id，建单时回填成 quotation_id
--
-- 🚫 **刻意不建 FK 到 import_record**（backtask B-1 原文）：`_record` 是报价单私有数据，
--    不是 import_record 的从属实体；加 FK 会让「删导入记录」级联掉业务数据，
--    且与既有「_record 外键数 = 0」的设计（D-48 注释）一致。
-- 🚫 不动业务列、不动 base_version_no / base_row_fingerprint / origin_id / element_price。
--
-- ⚠️ 列清单**不是硬编出来的**：13 张表 = Registry 里 versioned=true 的 sheet 的 recordTable()。
--    新增带版本表时，本迁移不需要改 —— 那张表的 `_record` 由它自己的建表迁移按同一形状建。
--    🔑 `import_batch_id` 已同步加进 `SheetDef.RECORD_COLUMNS` 与
--       `DatasetSchemaSelfCheck` 的类型表，否则启动期 Registry↔DDL 自检会报
--       「多出未声明的列」并让服务起不来（实测该自检对多列同样硬拦）。
--
-- 幂等：全部用 IF EXISTS / IF NOT EXISTS，重复执行不报错。
-- ============================================================================

DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT c.table_name
          FROM information_schema.columns c
         WHERE c.table_schema = 'public'
           AND c.table_name LIKE 'ds\_quote\_%\_record'
           AND c.column_name = 'quotation_id'
         ORDER BY 1
    LOOP
        -- ① quotation_id 放开为可空
        EXECUTE format('ALTER TABLE %I ALTER COLUMN quotation_id DROP NOT NULL', t);
        -- ② 批次归属列 + 索引（回收未建单批次靠它，AC-24）
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS import_batch_id uuid', t);
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (import_batch_id)',
                       'idx_' || left(t, 55) || '_batch', t);
        EXECUTE format(
            'COMMENT ON COLUMN %I.import_batch_id IS %L', t,
            '导入批次 = import_record.id（task-260911 R-2）。导入落库时 quotation_id 尚为 NULL、'
            '本列有值；建单时同事务把本批次的行改挂到 quotation_id。刻意不建 FK。');
        EXECUTE format(
            'COMMENT ON COLUMN %I.quotation_id IS %L', t,
            '所属报价单；task-260911 起可空 —— 导入先落批次（import_batch_id），建单时才回填。');
    END LOOP;
END $$;
