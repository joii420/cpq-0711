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

/** P11 辅助设备能耗 → auxiliary_energy。 */
@ApplicationScoped
public class P11AuxiliaryEnergyHandler implements SheetHandler {

    @Inject EntityManager em;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "辅助设备能耗"; }

    private static final class Row {
        final String materialNo, processNo, currency, unit, calcVersion, productionNo;
        final BigDecimal nonProdPrice;
        Row(String m, String p, BigDecimal np, String c, String u, String cv, String pn) {
            materialNo = m; processNo = p; nonProdPrice = np; currency = c; unit = u; calcVersion = cv; productionNo = pn;
        }
        static Row fold(Row p, Row n) {
            // non_production_energy_price=EXCLUDED 覆盖 → last(含 null)；currency/unit/production_no COALESCE → last-non-null。
            return new Row(n.materialNo, n.processNo, n.nonProdPrice,
                n.currency != null ? n.currency : p.currency,
                n.unit != null ? n.unit : p.unit,
                n.calcVersion,
                n.productionNo != null ? n.productionNo : p.productionNo);
        }
    }
    private static String nz(String s) { return s == null ? "" : s; }

    private void batchUpsert(List<Row> rows, UUID ub) {
        if (rows.isEmpty()) return;
        LinkedHashMap<List<String>, Row> dedup = new LinkedHashMap<>();
        for (Row r : rows) {
            dedup.merge(List.of(nz(r.materialNo), nz(r.processNo), nz(r.calcVersion)), r, Row::fold);
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
                    .append(", :p").append(b + 6)
                    .append(", NOW(), NOW(), :ub)");
            }
            jakarta.persistence.Query q = em.createNativeQuery(
                "INSERT INTO auxiliary_energy (material_no, process_no, non_production_energy_price, " +
                "  currency, unit, calc_version, production_no, created_at, updated_at, updated_by) VALUES " + vals +
                " ON CONFLICT (material_no, process_no, COALESCE(calc_version,'')) " +
                "DO UPDATE SET non_production_energy_price = EXCLUDED.non_production_energy_price, " +
                "  currency = COALESCE(EXCLUDED.currency, auxiliary_energy.currency), " +
                "  unit = COALESCE(EXCLUDED.unit, auxiliary_energy.unit), " +
                "  production_no = COALESCE(EXCLUDED.production_no, auxiliary_energy.production_no), " +
                "  updated_at = NOW(), updated_by = EXCLUDED.updated_by");
            for (int i = 0; i < chunk.size(); i++) {
                Row r = chunk.get(i); int b = i * 7;
                q.setParameter("p" + b, r.materialNo);
                q.setParameter("p" + (b + 1), r.processNo);
                q.setParameter("p" + (b + 2), r.nonProdPrice);
                q.setParameter("p" + (b + 3), r.currency);
                q.setParameter("p" + (b + 4), r.unit);
                q.setParameter("p" + (b + 5), r.calcVersion);
                q.setParameter("p" + (b + 6), r.productionNo);
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
                    String materialNo = row.getStr("销售料号", "宏丰料号");
                    String processNo = row.getStr("工序编号");
                    if (materialNo == null || processNo == null) {
                        result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                        continue;
                    }
                    acc.add(new Row(materialNo, processNo, row.getDecimal("非生产能耗单价"),
                        row.getStr("币种"), row.getStr("计量单位"), row.getStr("取用的计算版本", "计算版本"),
                        row.getStr("生产料号")));
                    result.successRows++;
                    result.recordWrite("auxiliary_energy", 1);
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
                String materialNo = row.getStr("销售料号", "宏丰料号");
                String processNo = row.getStr("工序编号");
                if (materialNo == null || processNo == null) {
                    result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                    continue;
                }
                em.createNativeQuery(
                        "INSERT INTO auxiliary_energy (material_no, process_no, non_production_energy_price, " +
                        "  currency, unit, calc_version, production_no, created_at, updated_at, updated_by) " +
                        "VALUES (:m, :p, :np, :c, :u, :cv, :pn, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (material_no, process_no, COALESCE(calc_version,'')) " +
                        "DO UPDATE SET non_production_energy_price = EXCLUDED.non_production_energy_price, " +
                        "  currency = COALESCE(EXCLUDED.currency, auxiliary_energy.currency), " +
                        "  unit = COALESCE(EXCLUDED.unit, auxiliary_energy.unit), " +
                        "  production_no = COALESCE(EXCLUDED.production_no, auxiliary_energy.production_no), " +
                        "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                    .setParameter("m", materialNo)
                    .setParameter("p", processNo)
                    .setParameter("np", row.getDecimal("非生产能耗单价"))
                    .setParameter("c", row.getStr("币种"))
                    .setParameter("u", row.getStr("计量单位"))
                    .setParameter("cv", row.getStr("取用的计算版本", "计算版本"))
                    .setParameter("pn", row.getStr("生产料号"))
                    .setParameter("ub", ctx.importedBy)
                    .executeUpdate();
                result.successRows++;
                result.recordWrite("auxiliary_energy", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
