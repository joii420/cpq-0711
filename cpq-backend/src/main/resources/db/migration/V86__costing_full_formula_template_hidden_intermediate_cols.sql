-- V86: 给「核价Excel视图模板（完整公式版）」的中间值列打 hidden=true
--
-- 背景: V85 把 4 个加价比例 + 核价汇率改为 BNF 引用, 列数从 19 增加到 23。
--       但与 Excel 「汇总」 sheet (16 个对外列) 相比多出了 7 个中间值列, 给用户看反而臃肿:
--          G  加价基数        FORMULA   =[B]+[C]+[D]+[E]+[F]
--          H  管理费比例      VARIABLE  mat_fee[...].fee_ratio
--          J  财务费比例      VARIABLE  mat_fee[...].fee_ratio
--          L  利润比例        VARIABLE  mat_fee[...].fee_ratio
--          N  税费比例        VARIABLE  mat_fee[...].fee_ratio
--          Q  单重(g/pcs)     VARIABLE  mat_part.unit_weight
--          S  核价汇率        VARIABLE  v_costing_exchange_rate.costing_rate
--
-- 解决方案: CostingTemplateColumn 新增 hidden?: boolean 字段。
--           hidden=true 的列「仍参与 FORMULA 求值链路」, 但不在 LinkedExcelView 的 tableColumns 里。
--           前端 LinkedExcelView.tsx 的 visibleColumns 过滤会按 col.hidden 排除。
--
-- 本迁移给上述 7 列打 hidden=true, 让对外展示仅剩 16 列 (与 Excel 「汇总」完全对齐):
--   A 宏丰料号 / B 材料成本 / C 材料损耗成本 / D 加工费 / E 电镀成本 / F 其他外加工成本 /
--   I 管理费 / K 财务费 / M 利润 / O 税费 /
--   P 总成本(CNY/KG) / R 总成本(CNY/PCS) / T 总成本(USD/KG) / U 总成本(USD/PCS) /
--   V 报价币种 / W 计量单位
--
-- 用 jsonb_set 逐列打标, 不动 source_type / formula / variable_path

UPDATE costing_template
SET columns = (
        SELECT jsonb_agg(
            CASE
                WHEN col->>'col_key' IN ('G','H','J','L','N','Q','S')
                    THEN col || '{"hidden":true}'::jsonb
                ELSE col
            END
            ORDER BY ordinality
        )
        FROM jsonb_array_elements(columns) WITH ORDINALITY AS t(col, ordinality)
    ),
    description = description || E'\n[V86] G/H/J/L/N/Q/S 共 7 个中间值列已隐藏 (hidden=true), 对外展示 16 列与 Excel 「汇总」对齐。',
    updated_at = now()
WHERE name = '核价Excel视图模板（完整公式版）';
