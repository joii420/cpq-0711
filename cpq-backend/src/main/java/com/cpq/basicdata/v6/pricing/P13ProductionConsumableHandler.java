package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.util.DecimalScale;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.*;

/** P13 生产耗材BOM → unit_price (PRICING/CONSUMABLE/耗材) 整组版本化。 */
@ApplicationScoped
public class P13ProductionConsumableHandler implements SheetHandler {
    @Inject VersionedV6Writer writer;
    @Inject jakarta.persistence.EntityManager em;
    @Override public String sheetName() { return "生产耗材BOM"; }
    private static final List<String> CONTENT = List.of("operation_no", "pricing_price", "currency", "unit");
    private static final List<String> DESCRIPTOR = List.of("production_no");
    private static String nz(String s) { return s == null ? "" : s; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());
        // code(销售料号) → operation_no(组内去重键,末值覆盖) → content row
        Map<String, LinkedHashMap<String, Map<String, Object>>> byCode = new LinkedHashMap<>();
        // 生产料号是「销售料号(code)级」属性；收集每个 code 的「首个非空」生产料号(② 同批同料号继承源)。
        Map<String, String> prodNoByCode = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            result.totalRows++;
            String code = row.getStr("销售料号", "宏丰料号");
            String operationNo = row.getStr("工序编号");
            if (code == null) {
                result.recordError(row.rowNo, "宏丰料号", "为空");
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("operation_no", operationNo);
            BigDecimal price = DecimalScale.at(row.getDecimal("耗材成本单价"), 6);
            c.put("pricing_price", price == null ? BigDecimal.ZERO : price);
            c.put("currency", row.getStr("币种"));
            c.put("unit", row.getStr("计量单位"));
            String prodNo = row.getStr("生产料号");
            if (prodNo != null && !prodNo.isBlank()) prodNoByCode.putIfAbsent(code, prodNo);
            c.put("production_no", prodNo);
            byCode.computeIfAbsent(code, k -> new LinkedHashMap<>()).put(nz(operationNo), c);
            result.successRows++;
        }

        // ④ 整组生产料号都空的 code → material_master(销售料号→生产料号 权威主档)兜底(批量一次查,禁 N+1)。
        List<String> needMaster = new ArrayList<>();
        for (String code : byCode.keySet()) if (prodNoByCode.get(code) == null) needMaster.add(code);
        if (!needMaster.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> mm = em.createNativeQuery(
                "SELECT material_no, production_no FROM material_master " +
                "WHERE material_no IN (:codes) AND production_no IS NOT NULL AND production_no <> ''")
                .setParameter("codes", needMaster).getResultList();
            for (Object[] r : mm)
                if (r[0] != null && r[1] != null) prodNoByCode.putIfAbsent(String.valueOf(r[0]), String.valueOf(r[1]));
        }

        // 回填每行空生产料号：① 文件本行非空 → 保留(用户输入优先)；否则 ② 组内首非空 / ④ material_master。
        // 仍为空(无同批兄弟、无主档)则原样留空，交由写入器 ③「继承上一版」兜底(见 VersionedV6Writer descriptor 继承)。
        for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> e : byCode.entrySet()) {
            String fallback = prodNoByCode.get(e.getKey());
            if (fallback == null) continue;
            for (Map<String, Object> c : e.getValue().values()) {
                Object v = c.get("production_no");
                if (v == null || String.valueOf(v).isBlank()) c.put("production_no", fallback);
            }
        }

        LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Map<String, Object>>> e : byCode.entrySet()) {
            Map<String, Object> gk = new LinkedHashMap<>();
            gk.put("system_type", "PRICING");
            gk.put("price_type", "CONSUMABLE");
            gk.put("cost_type", "耗材");
            gk.put("code", e.getKey());
            groups.put(gk, new ArrayList<>(e.getValue().values()));
        }
        try {
            writer.writeVersionedGroups("unit_price", "version_no", CONTENT, null, DESCRIPTOR, groups);
            for (List<Map<String, Object>> g : groups.values()) result.recordWrite("unit_price", g.size());
        } catch (Exception ex) {
            result.recordError(0, "_batch_", ex.getMessage());
        }
        return result;
    }
}
