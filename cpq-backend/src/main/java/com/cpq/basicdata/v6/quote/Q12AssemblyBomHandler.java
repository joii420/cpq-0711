package com.cpq.basicdata.v6.quote;

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
 * Q12 组成件BOM → material_bom (bom_type=ASSEMBLY) + material_bom_item。
 * <p>与 Q03 物料BOM 共用 material_bom* 表，按 bom_type 区分。
 * <p>主表写入策略：INSERT ON CONFLICT (uq_material_bom_v6) DO UPDATE — 消除 findOne-then-persist 假 upsert。
 * <p>子表写入策略：INSERT ON CONFLICT (uq_material_bom_item) DO UPDATE — 幂等，不依赖 DELETE 步骤。
 *   characteristic='ASSEMBLY' 与 Q03 (characteristic=null) 在唯一索引中物理隔离。
 */
@ApplicationScoped
public class Q12AssemblyBomHandler implements SheetHandler {

    @Inject EntityManager em;

    @Override public String sheetName() { return "组成件BOM"; }

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
                    // 主表：INSERT ON CONFLICT (uq_material_bom_v6) DO UPDATE
                    // uq_material_bom_v6 = (system_type, customer_no, material_no, bom_version, COALESCE(characteristic,''))
                    em.createNativeQuery(
                        "INSERT INTO material_bom " +
                        "  (id, system_type, customer_no, bom_type, bom_version, material_no, " +
                        "   characteristic, updated_at, updated_by) " +
                        "VALUES (gen_random_uuid(), 'QUOTE', :c, 'ASSEMBLY', 'V1', :m, " +
                        "        'ASSEMBLY', NOW(), :u) " +
                        "ON CONFLICT (system_type, customer_no, material_no, bom_version, " +
                        "             COALESCE(characteristic,'')) " +
                        "DO UPDATE SET updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                      .setParameter("c", ctx.customerNo)
                      .setParameter("m", materialNo)
                      .setParameter("u", ctx.importedBy)
                      .executeUpdate();
                    result.recordWrite("material_bom", 1);
                    processed.add(materialNo);
                }

                // 子表：INSERT ON CONFLICT (uq_material_bom_item) DO UPDATE
                // uq_material_bom_item = (system_type, customer_no, material_no,
                //   COALESCE(characteristic,''), COALESCE(seq_no,0),
                //   COALESCE(component_no,''), COALESCE(part_no,''))
                // 注意：item_seq 存入 item_seq 列，operation_no 存入 operation_no 列
                //       ON CONFLICT 键里无这两列，故同一 (materialNo, seqNo, componentNo) 会直接覆盖
                em.createNativeQuery(
                    "INSERT INTO material_bom_item " +
                    "  (id, system_type, customer_no, material_no, characteristic, seq_no, " +
                    "   operation_no, item_seq, component_no, composition_qty, issue_unit, " +
                    "   updated_at, updated_by) " +
                    "VALUES (gen_random_uuid(), 'QUOTE', :c, :m, 'ASSEMBLY', :seq, " +
                    "        :opNo, :itemSeq, :comp, :compQty, :unit, NOW(), :u) " +
                    "ON CONFLICT (system_type, customer_no, material_no, " +
                    "             COALESCE(characteristic,''), COALESCE(seq_no,0), " +
                    "             COALESCE(component_no,''), COALESCE(part_no,'')) " +
                    "DO UPDATE SET " +
                    "  operation_no    = EXCLUDED.operation_no, " +
                    "  item_seq        = EXCLUDED.item_seq, " +
                    "  composition_qty = EXCLUDED.composition_qty, " +
                    "  issue_unit      = EXCLUDED.issue_unit, " +
                    "  updated_at      = NOW(), " +
                    "  updated_by      = EXCLUDED.updated_by")
                  .setParameter("c", ctx.customerNo)
                  .setParameter("m", materialNo)
                  .setParameter("seq", row.getInt("项次（一级）", "项次"))
                  .setParameter("opNo", row.getStr("工序编号"))
                  .setParameter("itemSeq", row.getInt("项次（二级）"))
                  .setParameter("comp", row.getStr("组成件料号"))
                  .setParameter("compQty", row.getDecimal("组成数量"))
                  .setParameter("unit", row.getStr("组成单位"))
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
