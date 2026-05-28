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
 * P06 物料BOM (PRICING) → material_bom (system=PRICING, bom_type=MATERIAL) + material_bom_item。
 * <p>customer_no 用 "_GLOBAL_" 哨兵（核价 BOM 全局共享，方案文档未给 customer_no 列）。
 * <p>写入均使用 INSERT ... ON CONFLICT DO UPDATE，杜绝 duplicate key 错误。
 * material_bom unique: (system_type, customer_no, material_no, bom_version, COALESCE(characteristic,''))
 * material_bom_item unique: (system_type, customer_no, material_no, COALESCE(characteristic,''),
 *   COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))
 */
@ApplicationScoped
public class P06MaterialBomHandler implements SheetHandler {

    public static final String PRICING_CUSTOMER = "_GLOBAL_";

    @Inject EntityManager em;

    @Override public String sheetName() { return "物料BOM"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Set<String> processed = new HashSet<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
                if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }

                if (!processed.contains(materialNo)) {
                    // material_bom header upsert
                    // unique index: uq_material_bom_v6 (system_type, customer_no, material_no, bom_version, COALESCE(characteristic,''))
                    em.createNativeQuery(
                        "INSERT INTO material_bom (system_type, customer_no, bom_type, bom_version, " +
                        "  material_no, created_at, updated_at, updated_by) " +
                        "VALUES ('PRICING', :c, 'MATERIAL', 'V1', :m, NOW(), NOW(), :ub) " +
                        "ON CONFLICT (system_type, customer_no, material_no, bom_version, COALESCE(characteristic,'')) " +
                        "DO UPDATE SET updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                      .setParameter("c", PRICING_CUSTOMER)
                      .setParameter("m", materialNo)
                      .setParameter("ub", ctx.importedBy)
                      .executeUpdate();
                    result.recordWrite("material_bom", 1);
                    processed.add(materialNo);
                }

                Integer seqNo = row.getInt("项次");
                String componentNo = row.getStr("组成料号", "组件料号");

                // material_bom_item upsert
                // unique index: uq_material_bom_item (system_type, customer_no, material_no,
                //   COALESCE(characteristic,''), COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))
                em.createNativeQuery(
                    "INSERT INTO material_bom_item (system_type, customer_no, material_no, " +
                    "  seq_no, component_no, operation_no, component_usage_type, " +
                    "  composition_qty, issue_unit, base_qty, scrap_rate, fixed_scrap, " +
                    "  defect_rate, calc_type, created_at, updated_at, updated_by) " +
                    "VALUES ('PRICING', :c, :m, :sn, :cn, :on, :cut, " +
                    "  :cq, :iu, :bq, :sr, :fs, :dr, :ct, NOW(), NOW(), :ub) " +
                    "ON CONFLICT (system_type, customer_no, material_no, COALESCE(characteristic,''), " +
                    "  COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,'')) " +
                    "DO UPDATE SET " +
                    "  operation_no           = COALESCE(EXCLUDED.operation_no,           material_bom_item.operation_no), " +
                    "  component_usage_type   = COALESCE(EXCLUDED.component_usage_type,   material_bom_item.component_usage_type), " +
                    "  composition_qty        = COALESCE(EXCLUDED.composition_qty,        material_bom_item.composition_qty), " +
                    "  issue_unit             = COALESCE(EXCLUDED.issue_unit,             material_bom_item.issue_unit), " +
                    "  base_qty               = COALESCE(EXCLUDED.base_qty,               material_bom_item.base_qty), " +
                    "  scrap_rate             = COALESCE(EXCLUDED.scrap_rate,             material_bom_item.scrap_rate), " +
                    "  fixed_scrap            = COALESCE(EXCLUDED.fixed_scrap,            material_bom_item.fixed_scrap), " +
                    "  defect_rate            = COALESCE(EXCLUDED.defect_rate,            material_bom_item.defect_rate), " +
                    "  calc_type              = COALESCE(EXCLUDED.calc_type,              material_bom_item.calc_type), " +
                    "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                  .setParameter("c", PRICING_CUSTOMER)
                  .setParameter("m", materialNo)
                  .setParameter("sn", seqNo)
                  .setParameter("cn", componentNo)
                  .setParameter("on", row.getStr("工序编号"))
                  .setParameter("cut", row.getStr("使用特性"))
                  .setParameter("cq", row.getDecimal("组成用量"))
                  .setParameter("iu", row.getStr("组成用量单位"))
                  .setParameter("bq", row.getDecimal("底数"))
                  .setParameter("sr", row.getDecimal("材料损耗率", "损耗率"))
                  .setParameter("fs", row.getDecimal("材料固定损耗量", "固定损耗"))
                  .setParameter("dr", row.getDecimal("不良率"))
                  .setParameter("ct", row.getStr("计算类型"))
                  .setParameter("ub", ctx.importedBy)
                  .executeUpdate();
                result.successRows++;
                result.recordWrite("material_bom_item", 1);
            } catch (Exception e) {
                result.recordError(row.rowNo, "_row_", e.getMessage());
            }
        }
        return result;
    }
}
