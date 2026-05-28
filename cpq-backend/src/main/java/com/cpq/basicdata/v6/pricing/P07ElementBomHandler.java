package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * P07 物料与元素BOM (PRICING) → element_bom + element_bom_item。
 * <p>核价无版本管理需求，characteristic 用固定 "PRICING_V1"。
 * <p>写入均使用 INSERT ... ON CONFLICT DO UPDATE，杜绝 duplicate key 错误。
 * element_bom unique: (system_type, customer_no, material_no, characteristic)
 * element_bom_item unique: (system_type, customer_no, material_no, characteristic,
 *   COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))
 */
@ApplicationScoped
public class P07ElementBomHandler implements SheetHandler {

    public static final String CUSTOMER = "_GLOBAL_";
    public static final String CHARACTERISTIC = "PRICING_V1";

    @Inject EntityManager em;

    @Override public String sheetName() { return "物料与元素BOM"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Set<String> processed = new HashSet<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("物料料号", "宏丰料号");
                if (materialNo == null) { result.recordError(row.rowNo, "物料料号", "为空"); continue; }

                if (!processed.contains(materialNo)) {
                    // element_bom header upsert — ON CONFLICT (system_type, customer_no, material_no, characteristic)
                    em.createNativeQuery(
                        "INSERT INTO element_bom (system_type, customer_no, bom_type, material_no, " +
                        "  characteristic, created_at, updated_at, updated_by) " +
                        "VALUES ('PRICING', :c, 'MATERIAL', :m, :k, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (system_type, customer_no, material_no, characteristic) " +
                        "DO UPDATE SET updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                      .setParameter("c", CUSTOMER)
                      .setParameter("m", materialNo)
                      .setParameter("k", CHARACTERISTIC)
                      .setParameter("ub", ctx.importedBy)
                      .executeUpdate();
                    result.recordWrite("element_bom", 1);
                    processed.add(materialNo);
                }

                Integer seqNo = row.getInt("项次");
                String componentNo = row.getStr("元素代码");

                // element_bom_item upsert — ON CONFLICT (system_type, customer_no, material_no, characteristic,
                //   COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))
                em.createNativeQuery(
                    "INSERT INTO element_bom_item (system_type, customer_no, material_no, characteristic, " +
                    "  seq_no, component_no, content, scrap_rate, created_at, updated_at, updated_by) " +
                    "VALUES ('PRICING', :c, :m, :k, :sn, :cn, :ct, :sr, NOW(), NOW(), :ub) " +
                    "ON CONFLICT (system_type, customer_no, material_no, characteristic, " +
                    "  COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,'')) " +
                    "DO UPDATE SET " +
                    "  content    = COALESCE(EXCLUDED.content,    element_bom_item.content), " +
                    "  scrap_rate = COALESCE(EXCLUDED.scrap_rate, element_bom_item.scrap_rate), " +
                    "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                  .setParameter("c", CUSTOMER)
                  .setParameter("m", materialNo)
                  .setParameter("k", CHARACTERISTIC)
                  .setParameter("sn", seqNo)
                  .setParameter("cn", componentNo)
                  .setParameter("ct", row.getDecimal("组成含量"))
                  .setParameter("sr", row.getDecimal("损耗率"))
                  .setParameter("ub", ctx.importedBy)
                  .executeUpdate();
                result.successRows++;
                result.recordWrite("element_bom_item", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
