package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** P04 核价版本 → material_version_mgmt。 */
@ApplicationScoped
public class P04PricingVersionHandler implements SheetHandler {

    @Inject EntityManager em;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "核价版本"; }

    private static final class Row {
        final String materialNo, pricingVersionNo, pricingVersionName,
                elementPriceVersion, materialPriceVersion, exchangeRateVersion;
        final Integer seqNo;
        final Boolean isEffective;
        Row(String m, Integer s, String pvn, String pvm, String epv, String mpv, String erv, Boolean ie) {
            materialNo = m; seqNo = s; pricingVersionNo = pvn; pricingVersionName = pvm;
            elementPriceVersion = epv; materialPriceVersion = mpv; exchangeRateVersion = erv; isEffective = ie;
        }
        static Row fold(Row p, Row n) {
            // is_effective=EXCLUDED 覆盖 → last(非空 Boolean)；其余 COALESCE → last-non-null。
            return new Row(n.materialNo, n.seqNo, n.pricingVersionNo,
                n.pricingVersionName != null ? n.pricingVersionName : p.pricingVersionName,
                n.elementPriceVersion != null ? n.elementPriceVersion : p.elementPriceVersion,
                n.materialPriceVersion != null ? n.materialPriceVersion : p.materialPriceVersion,
                n.exchangeRateVersion != null ? n.exchangeRateVersion : p.exchangeRateVersion,
                n.isEffective);
        }
    }
    private static String nz(String s) { return s == null ? "" : s; }

    private void batchUpsert(List<Row> rows, UUID ub) {
        if (rows.isEmpty()) return;
        // 冲突键 (material_no, COALESCE(customer_no,''), seq_no, pricing_version_no)；
        // customer_no 未插入恒 NULL → ''；折叠键用同一规范化。
        LinkedHashMap<List<String>, Row> dedup = new LinkedHashMap<>();
        for (Row r : rows) {
            dedup.merge(List.of(nz(r.materialNo), "", String.valueOf(r.seqNo), nz(r.pricingVersionNo)), r, Row::fold);
        }
        List<Row> folded = new ArrayList<>(dedup.values());
        final int CHUNK = 500;
        for (int off = 0; off < folded.size(); off += CHUNK) {
            List<Row> chunk = folded.subList(off, Math.min(off + CHUNK, folded.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(", ");
                int b = i * 8;
                vals.append("(:p").append(b).append(", :p").append(b + 1).append(", :p").append(b + 2)
                    .append(", :p").append(b + 3).append(", :p").append(b + 4).append(", :p").append(b + 5)
                    .append(", :p").append(b + 6).append(", :p").append(b + 7).append(", NOW(), NOW(), :ub)");
            }
            jakarta.persistence.Query q = em.createNativeQuery(
                "INSERT INTO material_version_mgmt (material_no, seq_no, pricing_version_no, " +
                "  pricing_version_name, element_price_version, material_price_version, " +
                "  exchange_rate_version, is_effective, created_at, updated_at, updated_by) VALUES " + vals +
                " ON CONFLICT (material_no, COALESCE(customer_no,''), seq_no, pricing_version_no) DO UPDATE SET " +
                "  pricing_version_name = COALESCE(EXCLUDED.pricing_version_name, material_version_mgmt.pricing_version_name), " +
                "  element_price_version = COALESCE(EXCLUDED.element_price_version, material_version_mgmt.element_price_version), " +
                "  material_price_version = COALESCE(EXCLUDED.material_price_version, material_version_mgmt.material_price_version), " +
                "  exchange_rate_version = COALESCE(EXCLUDED.exchange_rate_version, material_version_mgmt.exchange_rate_version), " +
                "  is_effective = EXCLUDED.is_effective, " +
                "  updated_at = NOW(), updated_by = EXCLUDED.updated_by");
            for (int i = 0; i < chunk.size(); i++) {
                Row r = chunk.get(i); int b = i * 8;
                q.setParameter("p" + b, r.materialNo);
                q.setParameter("p" + (b + 1), r.seqNo);
                q.setParameter("p" + (b + 2), r.pricingVersionNo);
                q.setParameter("p" + (b + 3), r.pricingVersionName);
                q.setParameter("p" + (b + 4), r.elementPriceVersion);
                q.setParameter("p" + (b + 5), r.materialPriceVersion);
                q.setParameter("p" + (b + 6), r.exchangeRateVersion);
                q.setParameter("p" + (b + 7), r.isEffective);
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
                    String materialNo = row.getStr("宏丰料号");
                    Integer seqNo = row.getInt("项次");
                    String pricingVersionNo = row.getStr("核价版本编号");
                    if (materialNo == null || seqNo == null || pricingVersionNo == null) {
                        result.recordError(row.rowNo, "宏丰料号/项次/核价版本编号", "必填项为空");
                        continue;
                    }
                    Boolean isEffective = row.getBool("是否生效");
                    acc.add(new Row(materialNo, seqNo, pricingVersionNo,
                        row.getStr("核价版本名称"), row.getStr("元素价格版本"),
                        row.getStr("材料价格版本"), row.getStr("汇率价格版本"),
                        isEffective == null ? Boolean.TRUE : isEffective));
                    result.successRows++;
                    result.recordWrite("material_version_mgmt", 1);
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
                String materialNo = row.getStr("宏丰料号");
                Integer seqNo = row.getInt("项次");
                String pricingVersionNo = row.getStr("核价版本编号");
                if (materialNo == null || seqNo == null || pricingVersionNo == null) {
                    result.recordError(row.rowNo, "宏丰料号/项次/核价版本编号", "必填项为空");
                    continue;
                }
                Boolean isEffective = row.getBool("是否生效");

                em.createNativeQuery(
                        "INSERT INTO material_version_mgmt (material_no, seq_no, pricing_version_no, " +
                        "  pricing_version_name, element_price_version, material_price_version, " +
                        "  exchange_rate_version, is_effective, created_at, updated_at, updated_by) " +
                        "VALUES (:m, :s, :pvn, :pvm, :epv, :mpv, :erv, :ie, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (material_no, COALESCE(customer_no,''), seq_no, pricing_version_no) DO UPDATE SET " +
                        "  pricing_version_name = COALESCE(EXCLUDED.pricing_version_name, material_version_mgmt.pricing_version_name), " +
                        "  element_price_version = COALESCE(EXCLUDED.element_price_version, material_version_mgmt.element_price_version), " +
                        "  material_price_version = COALESCE(EXCLUDED.material_price_version, material_version_mgmt.material_price_version), " +
                        "  exchange_rate_version = COALESCE(EXCLUDED.exchange_rate_version, material_version_mgmt.exchange_rate_version), " +
                        "  is_effective = EXCLUDED.is_effective, " +
                        "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                    .setParameter("m", materialNo)
                    .setParameter("s", seqNo)
                    .setParameter("pvn", pricingVersionNo)
                    .setParameter("pvm", row.getStr("核价版本名称"))
                    .setParameter("epv", row.getStr("元素价格版本"))
                    .setParameter("mpv", row.getStr("材料价格版本"))
                    .setParameter("erv", row.getStr("汇率价格版本"))
                    .setParameter("ie", isEffective == null ? Boolean.TRUE : isEffective)
                    .setParameter("ub", ctx.importedBy)
                    .executeUpdate();
                result.successRows++;
                result.recordWrite("material_version_mgmt", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
