package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.repository.MaterialMasterRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Q03 物料BOM → material_bom（主） + material_bom_item（子） + material_master（同步 upsert）。
 * <p>system_type=QUOTE, bom_type=MATERIAL, bom_version=V1 (默认), characteristic=空。
 * <p>子表写入策略：INSERT ON CONFLICT (uq_material_bom_item) DO UPDATE — 幂等，不依赖 DELETE 步骤。
 * <p>主表写入策略：INSERT ON CONFLICT (uq_material_bom_v6) DO UPDATE — 消除 findOne-then-persist 假 upsert。
 */
@ApplicationScoped
public class Q03MaterialBomHandler implements SheetHandler {

    @Inject EntityManager em;
    @Inject MaterialMasterRepository materialMasterRepo;

    @Override public String sheetName() { return "物料BOM"; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        Set<String> processedMains = new HashSet<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            try {
                String materialNo = row.getStr("宏丰料号");
                if (materialNo == null) { result.recordError(row.rowNo, "宏丰料号", "为空"); continue; }
                String componentUsageType = row.getStr("产出料号类型");

                // 第一次见此料号：material_master upsert + material_bom 主表 upsert
                if (!processedMains.contains(materialNo)) {
                    materialMasterRepo.upsertByMaterialNo(materialNo, null, null, null, null,
                        componentUsageType, null, null, null, ctx.importedBy);
                    result.recordWrite("material_master", 1);

                    // 主表：INSERT ON CONFLICT (uq_material_bom_v6) DO UPDATE
                    // uq_material_bom_v6 = (system_type, customer_no, material_no, bom_version, COALESCE(characteristic,''))
                    em.createNativeQuery(
                        "INSERT INTO material_bom " +
                        "  (id, system_type, customer_no, bom_type, bom_version, material_no, " +
                        "   characteristic, updated_at, updated_by) " +
                        "VALUES (gen_random_uuid(), 'QUOTE', :c, 'MATERIAL', 'V1', :m, " +
                        "        NULL, NOW(), :u) " +
                        "ON CONFLICT (system_type, customer_no, material_no, bom_version, " +
                        "             COALESCE(characteristic,'')) " +
                        "DO UPDATE SET updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                      .setParameter("c", ctx.customerNo)
                      .setParameter("m", materialNo)
                      .setParameter("u", ctx.importedBy)
                      .executeUpdate();
                    result.recordWrite("material_bom", 1);
                    processedMains.add(materialNo);
                }

                // 子表：INSERT ON CONFLICT (uq_material_bom_item) DO UPDATE
                // uq_material_bom_item = (system_type, customer_no, material_no,
                //   COALESCE(characteristic,''), COALESCE(seq_no,0),
                //   COALESCE(component_no,''), COALESCE(part_no,''))
                em.createNativeQuery(
                    "INSERT INTO material_bom_item " +
                    "  (id, system_type, customer_no, material_no, characteristic, seq_no, " +
                    "   component_no, component_usage_type, composition_qty, base_qty, " +
                    "   issue_unit, scrap_rate, defect_rate, updated_at, updated_by) " +
                    "VALUES (gen_random_uuid(), 'QUOTE', :c, :m, NULL, :seq, " +
                    "        :comp, :usage, :compQty, :baseQty, " +
                    "        :unit, :scrap, :defect, NOW(), :u) " +
                    "ON CONFLICT (system_type, customer_no, material_no, " +
                    "             COALESCE(characteristic,''), COALESCE(seq_no,0), " +
                    "             COALESCE(component_no,''), COALESCE(part_no,'')) " +
                    "DO UPDATE SET " +
                    "  component_usage_type = EXCLUDED.component_usage_type, " +
                    "  composition_qty      = EXCLUDED.composition_qty, " +
                    "  base_qty             = EXCLUDED.base_qty, " +
                    "  issue_unit           = EXCLUDED.issue_unit, " +
                    "  scrap_rate           = EXCLUDED.scrap_rate, " +
                    "  defect_rate          = EXCLUDED.defect_rate, " +
                    "  updated_at           = NOW(), " +
                    "  updated_by           = EXCLUDED.updated_by")
                  .setParameter("c", ctx.customerNo)
                  .setParameter("m", materialNo)
                  .setParameter("seq", row.getInt("项次"))
                  .setParameter("comp", row.getStr("投入料号"))
                  .setParameter("usage", componentUsageType)
                  .setParameter("compQty", row.getDecimal("材料毛重", "毛重"))
                  .setParameter("baseQty", row.getDecimal("材料净重", "净重"))
                  .setParameter("unit", row.getStr("重量单位"))
                  .setParameter("scrap", row.getDecimal("损耗率"))
                  .setParameter("defect", row.getDecimal("不良率"))
                  .setParameter("u", ctx.importedBy)
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
