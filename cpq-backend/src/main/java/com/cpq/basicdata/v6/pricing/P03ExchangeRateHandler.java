package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P03 汇率管理表 → exchange_rate_v6（V218 表）按 (base_currency, target_currency) 整组版本化。
 * <p>版本按 {base_currency, target_currency} 分组，忽略 Excel「汇率版本」列，由 {@link VersionedV6Writer}
 * 系统自增（首版 2000，任一内容列变化即整组升版）。详见 §5.1 A 组。
 * <p>content = rate, ref_rate, ref_fetch_rule, ref_source_url；无触发列子集（null → 退化为
 * contentColumns，任意内容列变化即升版）；无描述列。
 * <p><b>批量入口选型</b>：exchange_rate_v6 未登记 SYSTEM_TYPE_SCOPED（无 system_type 列），groupKey
 * 轴仅 (base_currency, target_currency)。批量入口 {@code writeVersionedGroups} 要求整批 groupKey
 * 至少一列跨组恒定作锁/加载前缀（如其余表恒有的 system_type）；汇率表同一批导入可能同时含多个基准币种
 * （如 CNY→USD、USD→JPY 等不同 base_currency 的行），不能保证 base_currency 在整批内恒定，强行凑批量
 * 入口有踩到 {@code IllegalStateException}（空前缀）的风险。表数据量小（币种对组合有限），故按组逐个
 * 调用单组入口 {@link VersionedV6Writer#writeVersionedGroup}——该入口不要求常量前缀，逐组自带
 * advisory lock 串行化，是最稳的选择。
 */
@ApplicationScoped
public class P03ExchangeRateHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "汇率管理表"; }

    private static final List<String> CONTENT =
        List.of("rate", "ref_rate", "ref_fetch_rule", "ref_source_url");

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        // 按 (base_currency, target_currency) 分组；同批多 Excel 行落同一组时 fold：
        // rate 取末值覆盖（EXCLUDED 语义），其余列末行非空优先、否则保留前值（COALESCE 语义）。
        LinkedHashMap<List<String>, Map<String, Object>> contentByPair = new LinkedHashMap<>();
        LinkedHashMap<List<String>, Map<String, Object>> groupKeyByPair = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String baseCurrency = row.getStr("基础货币");
                String targetCurrency = row.getStr("核价货币", "目标货币");
                BigDecimal rate = row.getDecimal("核价汇率", "汇率");
                if (baseCurrency == null || targetCurrency == null || rate == null) {
                    result.recordError(row.rowNo, "基础货币/核价货币/核价汇率", "必填项为空");
                    continue;
                }
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("rate", rate);
                content.put("ref_rate", row.getDecimal("参考汇率"));
                content.put("ref_fetch_rule", row.getStr("参考汇率数据抓取规则", "抓取规则"));
                content.put("ref_source_url", row.getStr("抓取网址"));

                List<String> pairKey = List.of(baseCurrency, targetCurrency);
                contentByPair.merge(pairKey, content, P03ExchangeRateHandler::fold);
                groupKeyByPair.computeIfAbsent(pairKey, k -> {
                    Map<String, Object> gk = new LinkedHashMap<>();
                    gk.put("base_currency", baseCurrency);
                    gk.put("target_currency", targetCurrency);
                    return gk;
                });
                result.successRows++;
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }

        for (Map.Entry<List<String>, Map<String, Object>> e : contentByPair.entrySet()) {
            Map<String, Object> groupKey = groupKeyByPair.get(e.getKey());
            try {
                writer.writeVersionedGroup(new VersionedGroupSpec(
                    "exchange_rate_v6", "version_no", groupKey, CONTENT, List.of(e.getValue()), null));
                result.recordWrite("exchange_rate_v6", 1);
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        }
        return result;
    }

    /** 同组多 Excel 行 fold：rate 用末行覆盖；其余列 COALESCE（末行非空优先，否则保留前值）。 */
    private static Map<String, Object> fold(Map<String, Object> prev, Map<String, Object> next) {
        Map<String, Object> out = new LinkedHashMap<>(next);
        for (String col : List.of("ref_rate", "ref_fetch_rule", "ref_source_url")) {
            if (out.get(col) == null) out.put(col, prev.get(col));
        }
        return out;
    }
}
