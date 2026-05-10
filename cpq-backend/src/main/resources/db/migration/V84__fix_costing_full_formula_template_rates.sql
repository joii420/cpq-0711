-- V84: 修正「核价Excel视图模板（完整公式版）」的管理费/财务费比例
--
-- BUG: V83 把「来料其他费用」的比例(管理费 0.8%, 财务费 1.2%)写进了 H/I 两列的 FORMULA,
--      但用户 Excel 「汇总」行 74 里 F74=SUM(I74:J74,B74:D74)*L49/100 用的是
--      「成品其他费用」的比例: L48=0.6 (管理费), L49=0.5 (财务费),
--      所以正确的系数应该是 0.006 / 0.005 而不是 0.008 / 0.012。
--
-- 验证 (按 Excel 行 74 实测值, 加价基数 = 5071.26):
--   管理费 30.42758959  = 5071.26 × 0.006  ✓ (V83 写的 0.008 算出 40.57, 错)
--   财务费 25.35632466  = 5071.26 × 0.005  ✓ (V83 写的 0.012 算出 60.85, 错)
--   利润   253.5632466  = 5071.26 × 0.05   ✓ (V83 写的 0.05  正确)
--   税费   659.2644411  = 5071.26 × 0.13   ✓ (V83 写的 0.13  正确)
--
-- 修复方式: jsonb_set 精确替换 H/I 两列的 formula 字段,
--           不动其它 17 列, 不动列顺序, 不动 comparison_tag。
--
-- 同时更新 description, 把"0.8/1.2/5/13" 改成 "0.6/0.5/5/13"。

UPDATE costing_template
SET columns = jsonb_set(
                  jsonb_set(
                      columns,
                      -- H 列(index 7): 管理费
                      '{7,formula}',
                      '"=[G]*0.006"'::jsonb,
                      false
                  ),
                  -- I 列(index 8): 财务费
                  '{8,formula}',
                  '"=[G]*0.005"'::jsonb,
                  false
              ),
    description = '基于 data/template/核价系统计算公式和取值（示例）.xlsx 的全套计算公式构建。' ||
                  '6 个 VARIABLE 列读 v_costing_summary_full 视图（含 NULL 占位列）+ 1 个 mat_part.unit_weight + ' ||
                  '11 个 FORMULA 列在前端层算商务加价(管理/财务/利润/税)与总成本(CNY/KG, CNY/PCS, USD/KG, USD/PCS)。' ||
                  '加价比例(0.6%/0.5%/5%/13%, 即 Excel 行 48–51 「成品其他费用」)与核价汇率(0.138)当前以字面量写死, 后续接入数据源后把对应 FORMULA ' ||
                  '改成 [{BNF 路径}] 即可。详细 mapping 见 docs/templates/核价Excel模板-完整公式版-mapping.md。',
    updated_at = now()
WHERE name = '核价Excel视图模板（完整公式版）'
  AND status = 'DRAFT';
