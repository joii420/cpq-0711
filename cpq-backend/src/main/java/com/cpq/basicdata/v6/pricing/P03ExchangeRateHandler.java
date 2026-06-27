package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** P03 汇率管理表 → exchange_rate_v6 (V218 表)。 */
@ApplicationScoped
public class P03ExchangeRateHandler implements SheetHandler {

    @Inject EntityManager em;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "汇率管理表"; }

    private static final class Row {
        final String versionNo, baseCurrency, targetCurrency, refFetchRule, refSourceUrl;
        final BigDecimal rate, refRate;
        Row(String vn, String bc, String tc, BigDecimal r, BigDecimal rr, String rf, String ru) {
            versionNo = vn; baseCurrency = bc; targetCurrency = tc; rate = r; refRate = rr;
            refFetchRule = rf; refSourceUrl = ru;
        }
        static Row fold(Row p, Row n) {
            // rate=EXCLUDED 覆盖 → last；其余 COALESCE → last-non-null。key 三列两行相同取 n。
            return new Row(n.versionNo, n.baseCurrency, n.targetCurrency,
                n.rate,
                n.refRate != null ? n.refRate : p.refRate,
                n.refFetchRule != null ? n.refFetchRule : p.refFetchRule,
                n.refSourceUrl != null ? n.refSourceUrl : p.refSourceUrl);
        }
    }
    private static String nz(String s) { return s == null ? "" : s; }

    private void batchUpsert(List<Row> rows, UUID ub) {
        if (rows.isEmpty()) return;
        LinkedHashMap<List<String>, Row> dedup = new LinkedHashMap<>();
        for (Row r : rows) {
            dedup.merge(List.of(nz(r.versionNo), nz(r.baseCurrency), nz(r.targetCurrency)), r, Row::fold);
        }
        List<Row> folded = new ArrayList<>(dedup.values());
        final int CHUNK = 500;
        for (int off = 0; off < folded.size(); off += CHUNK) {
            List<Row> chunk = folded.subList(off, Math.min(off + CHUNK, folded.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(", ");
                int b = i * 7;
                vals.append("(:p").append(b).append(", :p").append(b + 1).append(", :p").append(b + 2)
                    .append(", :p").append(b + 3).append(", :p").append(b + 4).append(", :p").append(b + 5)
                    .append(", :p").append(b + 6).append(", NOW(), NOW(), :ub)");
            }
            jakarta.persistence.Query q = em.createNativeQuery(
                "INSERT INTO exchange_rate_v6 (version_no, base_currency, target_currency, rate, " +
                "  ref_rate, ref_fetch_rule, ref_source_url, created_at, updated_at, updated_by) VALUES " + vals +
                " ON CONFLICT (version_no, base_currency, target_currency) DO UPDATE SET " +
                "  rate = EXCLUDED.rate, " +
                "  ref_rate = COALESCE(EXCLUDED.ref_rate, exchange_rate_v6.ref_rate), " +
                "  ref_fetch_rule = COALESCE(EXCLUDED.ref_fetch_rule, exchange_rate_v6.ref_fetch_rule), " +
                "  ref_source_url = COALESCE(EXCLUDED.ref_source_url, exchange_rate_v6.ref_source_url), " +
                "  updated_at = NOW(), updated_by = EXCLUDED.updated_by");
            for (int i = 0; i < chunk.size(); i++) {
                Row r = chunk.get(i); int b = i * 7;
                q.setParameter("p" + b, r.versionNo);
                q.setParameter("p" + (b + 1), r.baseCurrency);
                q.setParameter("p" + (b + 2), r.targetCurrency);
                q.setParameter("p" + (b + 3), r.rate);
                q.setParameter("p" + (b + 4), r.refRate);
                q.setParameter("p" + (b + 5), r.refFetchRule);
                q.setParameter("p" + (b + 6), r.refSourceUrl);
            }
            q.setParameter("ub", ub);
            q.executeUpdate();
        }
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        if (setBased) {
            List<Row> acc = new ArrayList<>();
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
                    acc.add(new Row(versionNo, baseCurrency, targetCurrency, rate,
                        row.getDecimal("参考汇率"), row.getStr("参考汇率数据抓取规则", "抓取规则"),
                        row.getStr("抓取网址")));
                    result.successRows++;
                    result.recordWrite("exchange_rate_v6", 1);
                } catch (Exception e) {
                    result.recordError(row.rowNo, "_row_", e.getMessage());
                }
            }
            batchUpsert(acc, ctx.importedBy);
            return result;
        }
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
