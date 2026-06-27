package com.cpq.basicdata.v6.pricing;

import com.cpq.basicdata.v6.parser.ImportContext;
import com.cpq.basicdata.v6.parser.SheetHandler;
import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P08 产能 (PRICING) → capacity 整组版本化（对齐 Q14）+ labor_rate 版本同步。
 *
 * <p>capacity：按 material_no 聚合工序行整组写，calc_version 系统生成（2000 起，忽略 Excel 计算版本）；
 * 升版触发列 = process_no（工序集合变化才升版）；resource_group_no='PRICING_DEFAULT'，system_type='PRICING'。
 * <p>labor_rate：version_no 用 capacity 返回的系统版本号，保持与产能同版本。
 */
@ApplicationScoped
public class P08CapacityHandler implements SheetHandler {

    public static final String RESOURCE_GROUP = "PRICING_DEFAULT";

    @Inject VersionedV6Writer writer;
    @Inject EntityManager em;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    @Override public String sheetName() { return "产能"; }

    private static final List<String> CONTENT = List.of(
        "process_no", "production_type", "is_effective");
    private static final List<String> VERSION_TRIGGER = List.of("process_no");

    /** 暂存每料号的 labor_rate 行（capacity 升版后再按版本号写）。 */
    private record LaborRow(String processNo, BigDecimal rate, String currency, String unit) {}

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public SheetImportResult handle(List<SheetRow> rows, ImportContext ctx) {
        SheetImportResult result = new SheetImportResult(sheetName());

        Map<String, List<Map<String, Object>>> capByMat = new LinkedHashMap<>();
        Map<String, List<LaborRow>> laborByMat = new LinkedHashMap<>();

        for (SheetRow row : rows) {
            result.totalRows++;
            String materialNo = row.getStr("宏丰料号");
            String processNo = row.getStr("工序编号");
            if (materialNo == null || processNo == null) {
                result.recordError(row.rowNo, "宏丰料号/工序编号", "必填项为空");
                continue;
            }
            Boolean isEffective = row.getBool("是否有效");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("process_no", processNo);
            c.put("production_type", "BATCH_FIXED");
            c.put("is_effective", isEffective == null ? Boolean.TRUE : isEffective);
            capByMat.computeIfAbsent(materialNo, k -> new ArrayList<>()).add(c);

            BigDecimal laborRate = row.getDecimal("人工标准单价");
            if (laborRate != null) {
                laborByMat.computeIfAbsent(materialNo, k -> new ArrayList<>())
                          .add(new LaborRow(processNo, laborRate, row.getStr("币种"), row.getStr("计量单位")));
            }
            result.successRows++;
        }

        if (setBased) {
            // 集合化：capacity 整批写 + labor_rate 逐行 upsert → 多值 upsert。
            // 1) 按料号构造 groupKey（与旧路径逐字一致），保留 gkByMat 以便 labor_rate 用返回版本号回填。
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            Map<String, Map<String, Object>> gkByMat = new LinkedHashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : capByMat.entrySet()) {
                String materialNo = e.getKey();
                Map<String, Object> gk = new LinkedHashMap<>();
                gk.put("system_type", "PRICING");
                gk.put("material_no", materialNo);
                gk.put("resource_group_no", RESOURCE_GROUP);
                gkByMat.put(materialNo, gk);
                groups.put(gk, e.getValue());
            }
            try {
                Map<Map<String, Object>, String> vers = writer.writeVersionedGroups(
                    "capacity", "calc_version", CONTENT, VERSION_TRIGGER, groups);
                for (List<Map<String, Object>> groupRows : groups.values())
                    result.recordWrite("capacity", groupRows.size());

                // 2) 累积 labor_rate：version_no 用该料号 capacity 返回版本；按冲突键去重。
                // 单条 INSERT ... ON CONFLICT 不能两次命中同一冲突目标行（cardinality_violation），
                // 故先按冲突键 (version_no, process_no, COALESCE(material_no,''), COALESCE(labor_grade,''))
                // 在内存折叠：standard_labor_rate 取最后一行（EXCLUDED 胜），currency/unit = COALESCE(后非空, 前)，
                // 与旧逐行顺序 upsert 的末态等价。labor_grade 本 handler 不写 → 键里恒为 ''。
                LinkedHashMap<List<Object>, Map<String, Object>> laborByKey = new LinkedHashMap<>();
                int laborAttempts = 0;
                for (Map.Entry<String, List<LaborRow>> e : laborByMat.entrySet()) {
                    String materialNo = e.getKey();
                    String version = vers.get(gkByMat.get(materialNo));
                    if (version == null) continue; // 料号必有 capacity 版本，此为防御性跳过
                    for (LaborRow lr : e.getValue()) {
                        laborAttempts++;
                        List<Object> ck = Arrays.asList(
                            version, lr.processNo(), materialNo == null ? "" : materialNo, "");
                        Map<String, Object> prev = laborByKey.get(ck);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("version_no", version);
                        r.put("material_no", materialNo);
                        r.put("process_no", lr.processNo());
                        r.put("standard_labor_rate", lr.rate());                 // 后写覆盖
                        r.put("currency", lr.currency() != null ? lr.currency()
                            : (prev == null ? null : prev.get("currency")));     // COALESCE(后非空, 前)
                        r.put("unit", lr.unit() != null ? lr.unit()
                            : (prev == null ? null : prev.get("unit")));
                        r.put("updated_by", ctx.importedBy);
                        laborByKey.put(ck, r);
                    }
                }
                // recordWrite 计数对齐旧路径（每个 LaborRow 计 1 次 upsert）
                if (laborAttempts > 0) result.recordWrite("labor_rate", laborAttempts);

                // 3) 分块多值 upsert（每行 7 个绑定参数，500 行/块远低于参数上限）。
                List<Map<String, Object>> laborRows = new ArrayList<>(laborByKey.values());
                final int CHUNK = 500;
                for (int off = 0; off < laborRows.size(); off += CHUNK) {
                    List<Map<String, Object>> chunk =
                        laborRows.subList(off, Math.min(off + CHUNK, laborRows.size()));
                    StringBuilder sb = new StringBuilder(
                        "INSERT INTO labor_rate (version_no, material_no, process_no, standard_labor_rate, " +
                        "  currency, unit, created_at, updated_at, updated_by) VALUES ");
                    for (int j = 0; j < chunk.size(); j++) {
                        if (j > 0) sb.append(", ");
                        sb.append("(:vn").append(j).append(", :m").append(j).append(", :p").append(j)
                          .append(", :r").append(j).append(", :c").append(j).append(", :u").append(j)
                          .append(", NOW(), NOW(), :ub").append(j).append(")");
                    }
                    sb.append(" ON CONFLICT (version_no, process_no, COALESCE(material_no,''), COALESCE(labor_grade,'')) ")
                      .append("DO UPDATE SET standard_labor_rate = EXCLUDED.standard_labor_rate, ")
                      .append("  currency = COALESCE(EXCLUDED.currency, labor_rate.currency), ")
                      .append("  unit = COALESCE(EXCLUDED.unit, labor_rate.unit), ")
                      .append("  updated_at = NOW(), updated_by = EXCLUDED.updated_by");
                    var q = em.createNativeQuery(sb.toString());
                    for (int j = 0; j < chunk.size(); j++) {
                        Map<String, Object> r = chunk.get(j);
                        q.setParameter("vn" + j, r.get("version_no"));
                        q.setParameter("m" + j, r.get("material_no"));
                        q.setParameter("p" + j, r.get("process_no"));
                        q.setParameter("r" + j, r.get("standard_labor_rate"));
                        q.setParameter("c" + j, r.get("currency"));
                        q.setParameter("u" + j, r.get("unit"));
                        q.setParameter("ub" + j, r.get("updated_by"));
                    }
                    q.executeUpdate();
                }
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<String, List<Map<String, Object>>> e : capByMat.entrySet()) {
                String materialNo = e.getKey();
                try {
                    Map<String, Object> gk = new LinkedHashMap<>();
                    gk.put("system_type", "PRICING");
                    gk.put("material_no", materialNo);
                    gk.put("resource_group_no", RESOURCE_GROUP);
                    String version = writer.writeVersionedGroup(new VersionedGroupSpec(
                        "capacity", "calc_version", gk, CONTENT, e.getValue(), VERSION_TRIGGER));
                    result.recordWrite("capacity", e.getValue().size());

                    for (LaborRow lr : laborByMat.getOrDefault(materialNo, List.of())) {
                        em.createNativeQuery(
                                "INSERT INTO labor_rate (version_no, material_no, process_no, standard_labor_rate, " +
                                "  currency, unit, created_at, updated_at, updated_by) " +
                                "VALUES (:vn, :m, :p, :r, :c, :u, NOW(), NOW(), :ub) " +
                                "ON CONFLICT (version_no, process_no, COALESCE(material_no,''), COALESCE(labor_grade,'')) " +
                                "DO UPDATE SET standard_labor_rate = EXCLUDED.standard_labor_rate, " +
                                "  currency = COALESCE(EXCLUDED.currency, labor_rate.currency), " +
                                "  unit = COALESCE(EXCLUDED.unit, labor_rate.unit), " +
                                "  updated_at = NOW(), updated_by = EXCLUDED.updated_by")
                            .setParameter("vn", version)
                            .setParameter("m", materialNo)
                            .setParameter("p", lr.processNo())
                            .setParameter("r", lr.rate())
                            .setParameter("c", lr.currency())
                            .setParameter("u", lr.unit())
                            .setParameter("ub", ctx.importedBy)
                            .executeUpdate();
                        result.recordWrite("labor_rate", 1);
                    }
                } catch (Exception ex) {
                    result.recordError(0, "_group_", "material_no=" + materialNo + ": " + ex.getMessage());
                }
            }
        }
        return result;
    }
}
