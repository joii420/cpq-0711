package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** P10 生产设备能耗 → production_energy (price_type=ENERGY) 整组版本化。 */
@ApplicationScoped
public class P10ProductionEnergyHandler implements SheetHandler {

    @Inject VersionedV6Writer writer;

    @Override public String sheetName() { return "生产设备能耗"; }

    private static final List<String> CONTENT = List.of("process_no", "unit_price", "currency", "unit");
    private static final List<String> DESCRIPTOR = List.of("production_no");

    /**
     * tesk-0709 Task 11 E2E 修复（2026-07-11）：同 {@code P09EquipmentDepreciationHandler} 注释——
     * "生产能耗单价"列存在超出 production_energy.unit_price(numeric(18,6)) 精度的字面量
     * （如 1.1999999999999999E-3 / 1.4000000000000001E-7），解析时需按列 scale 舍入，避免同文件
     * 重导被误判"内容变化"而升版（违反§7.4"重导不升版"）。
     */
    private static BigDecimal roundToColumnScale(BigDecimal v) {
        return v == null ? null : v.setScale(6, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        Map<String, List<Map<String, Object>>> byMat = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("销售料号", "宏丰料号");
            String processNo = row.getStr("工序编号");
            if (materialNo == null || processNo == null) {
                result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("process_no", processNo);
            c.put("unit_price", roundToColumnScale(row.getDecimal("生产能耗单价")));
            c.put("currency", row.getStr("币种"));
            c.put("unit", row.getStr("计量单位"));
            c.put("production_no", row.getStr("生产料号"));   // 描述列
            byMat.computeIfAbsent(materialNo, k -> new ArrayList<>()).add(c);
            result.successRows++;
        }
        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byMat.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("material_no", e.getKey());
            gk.put("price_type", "ENERGY");
            groups.put(gk, e.getValue());
        }
        try {
            writer.writeVersionedGroups("production_energy", "calc_version", CONTENT, null, DESCRIPTOR, groups);
            for (List<Map<String, Object>> g : groups.values()) result.recordWrite("production_energy", g.size());
        } catch (Exception ex) {
            result.recordError(0, "_batch_", ex.getMessage());
        }
        return result;
    }
}
