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

/** P04 核价版本 → material_version_mgmt。 */
@ApplicationScoped
public class P04PricingVersionHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "核价版本"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
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
