package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.parser.SheetRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全局料号类型推断服务（update-0723 Task B2）。
 *
 * <p>新报价导入模板删除「组成件BOM」sheet，物料BOM 的「投入料号」列可能是材质 / 零件 / 外购件三态之一，
 * 且列本身不声明类型 —— 需要根据其它三个权威 sheet 的料号/名称集合反向匹配才能确定
 * （见需求说明 §11 U1）。本服务在 Phase 1 一次性构建 {@link TypeIndex}，供 Phase 1 校验器与
 * Phase 2 写入 handler（{@code MaterialBomMergeHandler} / Q06 / Q07 / Q09 / Q13）共享复用，
 * 避免重复解析 / 重复查库（U8 无 N+1 纪律）。
 *
 * <p><b>权威来源（U1）</b>：
 * <ul>
 *   <li>材质 RECIPE = 「物料与元素BOM」的 材质料号 / 材质料号名称</li>
 *   <li>零件 ASSEMBLY = 「自制加工费」的 投入料号 / 投入料号名称，<b>加上</b>「客户料号与宏丰料号的关系」/
 *       「成品其他费用」/「组装加工费」的 销售料号（主件，存储类型=零件）</li>
 *   <li>外购件 OUTSOURCED = 「组成件其他费用」的 组成件料号 / 组成件名称</li>
 * </ul>
 *
 * <p><b>判定顺序（U1 §3.3）</b>：① 命中以上权威 sheet 集（料号列或名称列命中其一即算，U4）
 * → ② 库内兜底 {@code material_recipe}（code/name 命中 → 材质）→ ③ 库内兜底 {@code material_master}
 * （material_no/material_name 命中 → 按 material_type 映射）→ ④ 都不命中 → 零件（默认兜底）。
 *
 * <p><b>类型冲突（U6 洞①）</b>：同一 token（料号值或名称值）若同时出现在 ≥2 个权威类型集，记为冲突
 * 错误，交 Phase 1 校验器汇总（不在此处抛异常，保持"全量收集不中断"语义）。
 */
@ApplicationScoped
public class PartTypeInferenceService {

    @Inject EntityManager em;

    public static final String RECIPE = "RECIPE";
    public static final String ASSEMBLY = "ASSEMBLY";
    public static final String OUTSOURCED = "OUTSOURCED";

    public enum Source { SHEET, RECIPE_DB, MASTER_DB, DEFAULT }

    public record InferResult(String characteristic, Source source) {}

    /** 权威 sheet 集合内一次 token 冲突（供 Phase 1 校验器转成 RowError）。 */
    public record ConflictError(String sheetName, int rowNo, String column, String message) {}

    /** token 在某类型桶内的首次出现位置（供冲突报错定位）。 */
    private record Occurrence(String sheetName, int rowNo, String column) {}

    /**
     * 构建好的类型索引：本次导入内三个权威 sheet 派生的 token 集 + 库内兜底缓存（一次性批量加载）。
     * 线程不安全（单次导入内单线程使用，与其它 SheetHandler 一致）。
     */
    public static final class TypeIndex {
        private final Map<String, Occurrence> recipeTokens = new LinkedHashMap<>();
        private final Map<String, Occurrence> assemblyTokens = new LinkedHashMap<>();
        private final Map<String, Occurrence> outsourcedTokens = new LinkedHashMap<>();
        private final List<ConflictError> conflicts = new ArrayList<>();

        /** material_recipe 库内兜底（status='ACTIVE' 全量拉入内存，表小，U8 一次查询）。 */
        private final Set<String> recipeCodeSet = new HashSet<>();
        private final Map<String, String> recipeNameToCode = new HashMap<>();

        /** material_master 库内兜底（全量拉入内存，本地表规模可控，U8 一次查询）。 */
        private final Map<String, String> masterTypeByNo = new HashMap<>();
        private final Map<String, String> masterTypeByName = new HashMap<>();

        /**
         * 批量级「名称→料号」种子（协调方 2026-07-23 R2 补充口径）：本次导入内，任一投入料号类
         * sheet 的某行若<b>同行</b>同时给出了料号与名称，记 name→code（首个非空胜）。
         * 供 {@link #seedBatchState} 灌进共享 {@link MaterialNoResolver.BatchState}，
         * 使"同一物理件在 A 表只给码、在 B 表只给名"时，B 表能直接复用 A 表行里揭示的码，
         * 而不是各 handler 各自查 {@code material_master} 正表（正表查不到本次导入刚 stage 的新码，
         * R1：pendingQuotationId 非空时注册走 {@code pending_material_master_staging}，不进正表）
         * 导致同一物理件被二次发号（重号）。
         */
        private final Map<String, String> nameToCodeSeed = new LinkedHashMap<>();

        private void seedNameCode(String rawNo, String rawName) {
            String no = trim(rawNo), name = trim(rawName);
            if (no == null || name == null) return;
            nameToCodeSeed.putIfAbsent(name, no);
        }

        /** 供只读查看/单测断言。 */
        public Map<String, String> nameToCodeSeed() { return nameToCodeSeed; }

        /**
         * 把本次导入内收集到的「名称→料号」种子灌入共享 {@link MaterialNoResolver.BatchState}
         * （{@code nameToNo} 为包内可见字段，本类与 {@link MaterialNoResolver} 同包，直接访问）。
         * 已有值不覆盖（putIfAbsent，尊重调用方可能已经手工设置的值）。
         */
        public void seedBatchState(MaterialNoResolver.BatchState state) {
            for (Map.Entry<String, String> e : nameToCodeSeed.entrySet()) {
                state.nameToNo.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        /** 供 buildIndex 内部填充权威 sheet token，同时做冲突检测。 */
        private void addToken(String type, String rawToken, String sheetName, int rowNo, String column) {
            if (rawToken == null) return;
            String token = rawToken.strip();
            if (token.isEmpty()) return;
            Map<String, Occurrence> bucket = bucketFor(type);
            if (bucket.containsKey(token)) return;   // 同类型内重复 token（同料号多行出现）不重复记

            List<String> hits = new ArrayList<>();   // 已建好的、命中该 token 的其它类型（用于冲突消息）
            for (String otherType : new String[]{RECIPE, ASSEMBLY, OUTSOURCED}) {
                if (otherType.equals(type)) continue;
                Occurrence o = bucketFor(otherType).get(token);
                if (o != null) hits.add(labelFor(otherType) + "(" + o.sheetName() + ")");
            }
            bucket.put(token, new Occurrence(sheetName, rowNo, column));
            if (!hits.isEmpty()) {
                StringBuilder sb = new StringBuilder("料号/名称「").append(token).append("」同时命中 ");
                sb.append(String.join(" 与 ", hits));
                sb.append(" 与 ").append(labelFor(type)).append("(").append(sheetName).append(")，类型冲突");
                conflicts.add(new ConflictError(sheetName, rowNo, column, sb.toString()));
            }
        }

        private Map<String, Occurrence> bucketFor(String type) {
            return switch (type) {
                case RECIPE -> recipeTokens;
                case ASSEMBLY -> assemblyTokens;
                case OUTSOURCED -> outsourcedTokens;
                default -> throw new IllegalArgumentException("未知类型: " + type);
            };
        }

        private static String labelFor(String type) {
            return switch (type) {
                case RECIPE -> "材质";
                case ASSEMBLY -> "零件";
                case OUTSOURCED -> "外购件";
                default -> type;
            };
        }

        public List<ConflictError> conflicts() { return conflicts; }

        /**
         * 判定顺序：① 权威 sheet 集（料号或名称命中其一即算，U4）→ ② material_recipe 库内兜底
         * → ③ material_master 库内兜底 → ④ 默认零件 ASSEMBLY。
         */
        public InferResult infer(String rawNo, String rawName) {
            String no = trim(rawNo), name = trim(rawName);

            if (no != null) {
                if (recipeTokens.containsKey(no)) return new InferResult(RECIPE, Source.SHEET);
                if (assemblyTokens.containsKey(no)) return new InferResult(ASSEMBLY, Source.SHEET);
                if (outsourcedTokens.containsKey(no)) return new InferResult(OUTSOURCED, Source.SHEET);
            }
            if (name != null) {
                if (recipeTokens.containsKey(name)) return new InferResult(RECIPE, Source.SHEET);
                if (assemblyTokens.containsKey(name)) return new InferResult(ASSEMBLY, Source.SHEET);
                if (outsourcedTokens.containsKey(name)) return new InferResult(OUTSOURCED, Source.SHEET);
            }

            if (no != null && recipeCodeSet.contains(no)) return new InferResult(RECIPE, Source.RECIPE_DB);
            if (name != null && recipeNameToCode.containsKey(name)) return new InferResult(RECIPE, Source.RECIPE_DB);

            if (no != null && masterTypeByNo.containsKey(no)) return new InferResult(mapMasterType(masterTypeByNo.get(no)), Source.MASTER_DB);
            if (name != null && masterTypeByName.containsKey(name)) return new InferResult(mapMasterType(masterTypeByName.get(name)), Source.MASTER_DB);

            return new InferResult(ASSEMBLY, Source.DEFAULT);
        }

        /**
         * 材质 RECIPE 类型的最终落库码：有码则校验其存在于 material_recipe（不存在返回 null，
         * 调用方应报「未找到材质」，U2）；仅有名称则按名查 code（查无同样返回 null）。
         */
        public String resolveRecipeCode(String rawNo, String rawName) {
            String no = trim(rawNo);
            if (no != null) return recipeCodeSet.contains(no) ? no : null;
            String name = trim(rawName);
            if (name != null) return recipeNameToCode.get(name);
            return null;
        }

        private static String mapMasterType(String materialType) {
            if ("零件".equals(materialType)) return ASSEMBLY;
            if ("外购件".equals(materialType)) return OUTSOURCED;
            return ASSEMBLY;   // 旧值「组成件」/ 未知 / null 统一兜底零件（U1 §3.3-3）
        }

        private static String trim(String s) {
            if (s == null) return null;
            String t = s.strip();
            return t.isEmpty() ? null : t;
        }
    }

    /**
     * 构建本次导入的类型索引。入参为已解析的全 sheet（sheetName → rows），缺失的 sheet 传空 list 即可。
     * 内部只对 material_recipe / material_master 各发一次全表查询（U8 无 N+1）。
     */
    public TypeIndex buildIndex(Map<String, List<SheetRow>> sheetsByName) {
        TypeIndex idx = new TypeIndex();

        addAuthoritative(idx, RECIPE, sheetsByName.getOrDefault("物料与元素BOM", List.of()),
            "物料与元素BOM", "材质料号", "材质料号名称");

        addAuthoritative(idx, ASSEMBLY, sheetsByName.getOrDefault("自制加工费", List.of()),
            "自制加工费", "投入料号", "投入料号名称");
        addAuthoritativeNoOnly(idx, ASSEMBLY, sheetsByName.getOrDefault("客户料号与宏丰料号的关系", List.of()),
            "客户料号与宏丰料号的关系", "销售料号");
        addAuthoritativeNoOnly(idx, ASSEMBLY, sheetsByName.getOrDefault("成品其他费用", List.of()),
            "成品其他费用", "销售料号");
        addAuthoritativeNoOnly(idx, ASSEMBLY, sheetsByName.getOrDefault("组装加工费", List.of()),
            "组装加工费", "销售料号");

        addAuthoritative(idx, OUTSOURCED, sheetsByName.getOrDefault("组成件其他费用", List.of()),
            "组成件其他费用", "组成件料号", "组成件名称");

        // R2 补充口径：批量级名称→料号种子，覆盖全部投入料号类 sheet（不限于三个权威 sheet）。
        addNameCodeSeed(idx, sheetsByName.getOrDefault("物料BOM", List.of()), "投入料号", "投入料号名称");
        addNameCodeSeed(idx, sheetsByName.getOrDefault("自制加工费", List.of()), "投入料号", "投入料号名称");
        addNameCodeSeed(idx, sheetsByName.getOrDefault("组成件其他费用", List.of()), "组成件料号", "组成件名称");
        addNameCodeSeed(idx, sheetsByName.getOrDefault("来料固定加工费", List.of()), "投入料号", "投入料号名称");
        addNameCodeSeed(idx, sheetsByName.getOrDefault("来料其他费用", List.of()), "投入料号", "投入料号名称");
        addNameCodeSeed(idx, sheetsByName.getOrDefault("来料回收折扣", List.of()), "投入料号", "投入料号名称");

        loadRecipeFallback(idx);
        loadMasterFallback(idx);
        return idx;
    }

    private void addNameCodeSeed(TypeIndex idx, List<SheetRow> rows, String noColumn, String nameColumn) {
        for (SheetRow row : rows) {
            idx.seedNameCode(row.exact(noColumn), row.exact(nameColumn));
        }
    }

    private void addAuthoritative(TypeIndex idx, String type, List<SheetRow> rows,
                                  String sheetName, String noColumn, String nameColumn) {
        for (SheetRow row : rows) {
            idx.addToken(type, row.exact(noColumn), sheetName, row.rowNo, noColumn);
            idx.addToken(type, row.exact(nameColumn), sheetName, row.rowNo, nameColumn);
        }
    }

    private void addAuthoritativeNoOnly(TypeIndex idx, String type, List<SheetRow> rows,
                                        String sheetName, String noColumn) {
        for (SheetRow row : rows) {
            String no = row.getStr(noColumn, "宏丰料号");
            idx.addToken(type, no, sheetName, row.rowNo, noColumn);
        }
    }

    /** material_recipe 库内兜底：一次性 SELECT ACTIVE 全量（表小，见 U8）。 */
    @SuppressWarnings("unchecked")
    private void loadRecipeFallback(TypeIndex idx) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT code, name FROM material_recipe WHERE status = 'ACTIVE'").getResultList();
        for (Object[] r : rows) {
            String code = (String) r[0];
            String name = (String) r[1];
            if (code != null && !code.isBlank()) idx.recipeCodeSet.add(code.strip());
            if (name != null && !name.isBlank() && code != null && !code.isBlank()) {
                idx.recipeNameToCode.putIfAbsent(name.strip(), code.strip());
            }
        }
    }

    /** material_master 库内兜底：一次性 SELECT 全量（本地表规模可控，见 U8）。 */
    @SuppressWarnings("unchecked")
    private void loadMasterFallback(TypeIndex idx) {
        List<Object[]> rows = em.createNativeQuery(
            "SELECT material_no, material_name, material_type FROM material_master").getResultList();
        for (Object[] r : rows) {
            String no = (String) r[0];
            String name = (String) r[1];
            String type = (String) r[2];
            if (no != null && !no.isBlank()) idx.masterTypeByNo.putIfAbsent(no.strip(), type);
            if (name != null && !name.isBlank()) idx.masterTypeByName.putIfAbsent(name.strip(), type);
        }
    }
}
