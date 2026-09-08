-- task-260907 · B-1 + B-6 —— 报价侧 ds_quote_* 全表加客户维度 customer_no
--
-- 服务的 AC：AC-2（整组删除不得跨客户误删）· AC-3（报价侧全表 DDL 落地）
--
-- 用户裁决（2026-09-07）：「换表增加 +customer_no 维度，将所有报价侧的表都增加 customer_no 字段」
--                        「统一给罗克韦尔的客户号，测试数据不计较数据真实，重点是业务逻辑准确」
--
-- ⚠️ 本迁移必须与【轴模型改复合】同一批合并。只加列不扩轴的中间态最危险：
--    列已经在、值也在填，而 VersionedGroupWriter 的整组删除仍按单列轴走 ——
--    看起来一切正常，实际每次导入都在删别的客户的数据（不报错、不撞键、不留痕）。
--
-- 🚫 刻意【不写静态 ALTER TABLE 清单】，改用动态扫：
--    ① 13 张 ds_quote_*_record 已由 V420 带上该列，静态清单会撞「列已存在」；
--    ② ds_quote_customer_part 的 customer_no 是 Excel 业务列，本来就有；
--    ③ 后续还会有同范式新表加入，动态扫不会漏也不会撞。
--    2026-09-07 实测缺该列 28 张（15 主表 + 13 _history），共 199 行存量数据 —— 数字只作记账，不进判据。

-- ── ① 加列 + 回填 + 收紧 NOT NULL（列定义照抄 ds_quote_customer_part.customer_no 实查结果）
DO $$
DECLARE
    t   text;
    n   bigint;
    tot bigint := 0;
    cnt int := 0;
BEGIN
    FOR t IN
        SELECT ti.table_name
        FROM information_schema.tables ti
        WHERE ti.table_schema = 'public'
          AND ti.table_type = 'BASE TABLE'
          AND ti.table_name LIKE 'ds_quote_%'
          AND NOT EXISTS (
              SELECT 1 FROM information_schema.columns c
              WHERE c.table_schema = 'public'
                AND c.table_name = ti.table_name
                AND c.column_name = 'customer_no')
        ORDER BY ti.table_name
    LOOP
        EXECUTE format('ALTER TABLE public.%I ADD COLUMN customer_no varchar(20)', t);
        -- 存量统一回填罗克韦尔（用户裁决）。回填值不承担业务正确性，它只是让隔离逻辑
        -- 【能被真正验证】的载体：所有存量都是 CUST-0001 ⇒ 任何其它客户的查询都不该看到它们。
        EXECUTE format('UPDATE public.%I SET customer_no = ''CUST-0001'' WHERE customer_no IS NULL', t);
        GET DIAGNOSTICS n = ROW_COUNT;
        EXECUTE format('ALTER TABLE public.%I ALTER COLUMN customer_no SET NOT NULL', t);
        tot := tot + n;
        cnt := cnt + 1;
        RAISE NOTICE '[V423] % 加列并回填 % 行', t, n;
    END LOOP;
    RAISE NOTICE '[V423] 共 % 张表加列，回填 % 行', cnt, tot;
END $$;

-- ── ② B-6：物料表业务唯一索引扩成 (customer_no, material_no)
-- 原 uq_ds_quote_material(material_no) 是全局唯一 ⇒ 同一销售料号在两个客户下只能存在一行，
-- 与「同料号跨客户各自成组」的目标直接冲突。
-- 存量安全性：material_no 原本已全局唯一，回填后 customer_no 恒为 CUST-0001，
-- 故 (customer_no, material_no) 必然仍唯一，不会因重复而建索引失败。
--
-- 🚫 另两条唯一索引不动：
--    uq_ds_quote_customer_part(customer_no, customer_product_no) —— 已含客户；
--    uq_ds_quote_plating_scheme(scheme_no, scheme_version, item_seq) —— 与客户无关。
DROP INDEX IF EXISTS uq_ds_quote_material;
CREATE UNIQUE INDEX uq_ds_quote_material
    ON public.ds_quote_material USING btree (customer_no, material_no);

COMMENT ON COLUMN public.ds_quote_material.customer_no IS
    'task-260907：客户编号（= customer.code）。报价侧全表客户维度，与 material_no 共同构成唯一键。';
