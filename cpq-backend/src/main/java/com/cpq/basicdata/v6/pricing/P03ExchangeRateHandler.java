package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

/** P03 汇率管理表 → exchange_rate_v6 (V218 表)。 */
@ApplicationScoped
public class P03ExchangeRateHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "汇率管理表"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String versionNo = row.getStr("汇率版本");
                String baseCurrency = row.getStr("基础货币");
                String targetCurrency = row.getStr("核价货币", "目标货币");
                java.math.BigDecimal rate = row.getDecimal("核价汇率", "汇率");
                if (versionNo == null || baseCurrency == null || targetCurrency == null || rate == null) {
                    result.recordError(row.rowNo, "汇率版本/基础货币/核价货币/核价汇率", "必填项为空");
                    continue;
                }
                em.createNativeQuery(
                        "INSERT INTO exchange_rate_v6 (version_no, base_currency, target_currency, rate, " +
                        "  ref_rate, ref_fetch_rule, ref_source_url, created_at, updated_at, updated_by) " +
                        "VALUES (:vn, :bc, :tc, :r, :rr, :rf, :ru, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (version_no, base_currency, target_currency) DO UPDATE SET " +
                        "  rate = EXCLUDED.rate, " +
                        "  ref_rate = COALESCE(EXCLUDED.ref_rate, exchange_rate_v6.ref_rate), " +
                        "  ref_fetch_rule = COALESCE(EXCLUDED.ref_fetch_rule, exchange_rate_v6.ref_fetch_rule), " +
                        "  ref_source_url = COALESCE(EXCLUDED.ref_source_url, exchange_rate_v6.ref_source_url), " +
                        "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                    .setParameter("vn", versionNo)
                    .setParameter("bc", baseCurrency)
                    .setParameter("tc", targetCurrency)
                    .setParameter("r", rate)
                    .setParameter("rr", row.getDecimal("参考汇率"))
                    .setParameter("rf", row.getStr("参考汇率数据抓取规则", "抓取规则"))
                    .setParameter("ru", row.getStr("抓取网址"))
                    .setParameter("ub", ctx.importedBy)
                    .executeUpdate();
                result.successRows++;
                result.recordWrite("exchange_rate_v6", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
