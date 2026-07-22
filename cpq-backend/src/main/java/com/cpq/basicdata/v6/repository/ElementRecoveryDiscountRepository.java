package com.cpq.basicdata.v6.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Q05 元素回收折扣 → element_bom_item.recovery_discount 更新（按 2 键 material_no+component_no、is_current=TRUE）。
 *
 * <p>{@link #updateOne} 为原 Q05 逐行 SQL 的逐字提取（等价护栏基准）。P2-Q05 用
 * {@link #countCurrentMatches}（一次 tuple-IN GROUP BY 取每键匹配行数，供 handler 复原"逐行 updated 计数 +
 * updated==0 未匹配错误"语义）+ {@link #batchUpdate}（一条 {@code UPDATE ... FROM (VALUES ...)}）替代逐行 N 次往返。
 */
@ApplicationScoped
public class ElementRecoveryDiscountRepository {

    @Inject EntityManager em;

    public static final String DELIM = "";
    private static String nz(String s) { return s == null ? "" : s; }
    /** 3 键(material_no, material_part_no, component_no)编码; material_part_no 空归一 ''(与唯一键 COALESCE 一致)。 */
    public static String key(String materialNo, String materialPartNo, String componentNo) {
        return nz(materialNo) + DELIM + nz(materialPartNo) + DELIM + nz(componentNo);
    }

    /** 一条更新（recovery_discount 可空——直接赋值，null 会覆盖为 NULL，与原逐行 SET 语义一致）。 */
    public record Update(String materialNo, String materialPartNo, String componentNo, BigDecimal recoveryDiscount) {}

    /** 原 Q05 逐行 UPDATE（逐字保留，等价基准），返回受影响行数。 */
    public int updateOne(String customerNo, String materialNo, String materialPartNo, String componentNo,
                         BigDecimal rd, UUID updatedBy) {
        return em.createNativeQuery(
                "UPDATE element_bom_item SET recovery_discount = :rd, updated_at = NOW(), updated_by = :u " +
                "WHERE system_type='QUOTE' AND customer_no=:c AND material_no=:m " +
                "  AND COALESCE(material_part_no,'') = COALESCE(:mp,'') " +
                "  AND component_no=:cn AND is_current = TRUE")
            .setParameter("rd", rd).setParameter("u", updatedBy).setParameter("c", customerNo)
            .setParameter("m", materialNo).setParameter("mp", materialPartNo).setParameter("cn", componentNo)
            .executeUpdate();
    }

    /**
     * 一次 tuple-IN GROUP BY 取每 (material_no, component_no) 键的 is_current 匹配行数。
     * = 各键逐行 {@link #updateOne} 会返回的 updated 计数（每行只属一个键 → 计数一致）。
     * 返回以 {@link #key} 编码的 Map；缺失键不在 Map 中（= 0 匹配）。按 1000 键分块。
     * @deprecated task-0721 B2：不带 pendingQuotationId 的旧签名——Q04（同批 element_bom_item 创建者）
     * 一旦落 pending 模式，本方法按 is_current=TRUE 匹配不到 Q04 本次刚写的 pending 行（is_current=false），
     * 请用 {@link #countCurrentMatches(String, List, UUID)}。
     */
    public Map<String, Integer> countCurrentMatches(String customerNo, List<String[]> keys) {
        return countCurrentMatches(customerNo, keys, null);
    }

    /**
     * task-0721 B2：pending 归属重载 —— {@code pendingQuotationId} 非 null 时匹配范围扩大为
     * "本单可见集合"，且**遮蔽**同 (material_no, material_part_no) 装配组内已被本单 pending 影子取代的
     * 官方 current 行（与 B3 视图改写"遮蔽"同一原理）：
     * <ul>
     *   <li>本单自己的 pending 行（pending_quotation_id=:pq）——恒匹配。</li>
     *   <li>官方 current 行（is_current=TRUE AND pending_quotation_id IS NULL）——**仅当**同一
     *       (material_no, material_part_no) 组合下**不存在**本单 pending 行时才匹配。</li>
     * </ul>
     * 原因：Q04 pending 模式对"有差异的装配组"是整组重写（该 material_no+material_part_no 下所有
     * component 行一起落新 pending 行，不 flip 官方行）——若不遮蔽，同一 (material_no, component_no)
     * 键会同时匹配官方行 + pending 行两条，{@link #batchUpdate} 会连带误改全局可见的官方行
     * （破坏他单隔离）。若该组本次没有差异（Q04 判定跳过，未建 pending 行），则官方行仍是唯一代表，
     * 正常匹配更新（与 pending 模式引入前行为一致——注：Q05 目前是唯一未经 VersionedV6Writer 版本化组
     * 校验直接 UPDATE 的表，此边界情形官方行会被直接改写，属已知限制，见类头 TODO）。
     */
    public Map<String, Integer> countCurrentMatches(String customerNo, List<String[]> keys, UUID pendingQuotationId) {
        Map<String, Integer> out = new HashMap<>();
        if (keys == null || keys.isEmpty()) return out;
        String visWhere = visibilityWhere(pendingQuotationId);
        final int CHUNK = 1000;
        for (int start = 0; start < keys.size(); start += CHUNK) {
            List<String[]> chunk = keys.subList(start, Math.min(start + CHUNK, keys.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(", ");
                vals.append("(CAST(:m").append(i).append(" AS varchar), CAST(:p").append(i)
                    .append(" AS varchar), CAST(:n").append(i).append(" AS varchar))");
            }
            var q = em.createNativeQuery(
                    "SELECT ebi.material_no, COALESCE(ebi.material_part_no,''), ebi.component_no, count(*) " +
                    "FROM element_bom_item ebi " +
                    "JOIN (VALUES " + vals + ") AS v(material_no, material_part_no, component_no) " +
                    "  ON ebi.material_no = v.material_no " +
                    " AND COALESCE(ebi.material_part_no,'') = COALESCE(v.material_part_no,'') " +
                    " AND ebi.component_no = v.component_no " +
                    "WHERE ebi.system_type='QUOTE' AND ebi.customer_no = :c AND " + visWhere + " " +
                    "GROUP BY ebi.material_no, COALESCE(ebi.material_part_no,''), ebi.component_no")
                .setParameter("c", customerNo);
            if (pendingQuotationId != null) q.setParameter("pq", pendingQuotationId);
            for (int i = 0; i < chunk.size(); i++) {
                q.setParameter("m" + i, chunk.get(i)[0]);
                q.setParameter("p" + i, chunk.get(i)[1]);
                q.setParameter("n" + i, chunk.get(i)[2]);
            }
            @SuppressWarnings("unchecked")
            List<Object[]> rs = q.getResultList();
            for (Object[] r : rs) {
                out.put(key(String.valueOf(r[0]), String.valueOf(r[1]), String.valueOf(r[2])), ((Number) r[3]).intValue());
            }
        }
        return out;
    }

    /**
     * 批量更新（一条 {@code UPDATE ... FROM (VALUES ...)}）。前提：调用方已按 (material_no, component_no) 去重 +
     * 末值胜（与逐行 SET 的"后写覆盖"一致）。recovery_discount 用 {@code CAST(:r AS numeric)} 显式标注，
     * 防多行 VALUES 首行 NULL 致 PG 无法推断列类型（与 Q19 同款，但本处是 UPDATE…FROM(VALUES) 无目标列上下文，
     * 故 material_no/component_no 也一并 CAST）。按 1000 行分块。
     * @deprecated task-0721 B2：请用 {@link #batchUpdate(String, List, UUID, UUID)}（见该方法遮蔽说明）。
     */
    public void batchUpdate(String customerNo, List<Update> updates, UUID updatedBy) {
        batchUpdate(customerNo, updates, updatedBy, null);
    }

    /** task-0721 B2：pending 归属重载，可见性/遮蔽规则同 {@link #countCurrentMatches(String, List, UUID)}。 */
    public void batchUpdate(String customerNo, List<Update> updates, UUID updatedBy, UUID pendingQuotationId) {
        if (updates == null || updates.isEmpty()) return;
        String visWhere = visibilityWhere(pendingQuotationId);
        final int CHUNK = 1000;
        for (int start = 0; start < updates.size(); start += CHUNK) {
            List<Update> chunk = updates.subList(start, Math.min(start + CHUNK, updates.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(", ");
                vals.append("(CAST(:m").append(i).append(" AS varchar), CAST(:p").append(i)
                    .append(" AS varchar), CAST(:n").append(i).append(" AS varchar), CAST(:r").append(i)
                    .append(" AS numeric))");
            }
            String sql =
                "UPDATE element_bom_item AS ebi SET recovery_discount = v.rd, updated_at = NOW(), updated_by = :u " +
                "FROM (VALUES " + vals + ") AS v(material_no, material_part_no, component_no, rd) " +
                "WHERE ebi.system_type='QUOTE' AND ebi.customer_no = :c AND " + visWhere + " " +
                "  AND ebi.material_no = v.material_no " +
                "  AND COALESCE(ebi.material_part_no,'') = COALESCE(v.material_part_no,'') " +
                "  AND ebi.component_no = v.component_no";
            var q = em.createNativeQuery(sql).setParameter("u", updatedBy).setParameter("c", customerNo);
            if (pendingQuotationId != null) q.setParameter("pq", pendingQuotationId);
            for (int i = 0; i < chunk.size(); i++) {
                q.setParameter("m" + i, chunk.get(i).materialNo());
                q.setParameter("p" + i, chunk.get(i).materialPartNo());
                q.setParameter("n" + i, chunk.get(i).componentNo());
                q.setParameter("r" + i, chunk.get(i).recoveryDiscount());
            }
            q.executeUpdate();
        }
    }

    /**
     * 可见性/遮蔽 WHERE 片段（"ebi" 别名固定，见两个调用方）。pendingQuotationId=null 时退化为旧行为
     * {@code ebi.is_current = TRUE}（不带 pending 上下文的调用方，如既有单测，零回归）。
     */
    private static String visibilityWhere(UUID pendingQuotationId) {
        if (pendingQuotationId == null) return "ebi.is_current = TRUE";
        return "(ebi.pending_quotation_id = :pq OR " +
               " (ebi.is_current = TRUE AND ebi.pending_quotation_id IS NULL AND NOT EXISTS (" +
               "   SELECT 1 FROM element_bom_item p2 " +
               "   WHERE p2.system_type = ebi.system_type AND p2.customer_no = ebi.customer_no " +
               "     AND p2.material_no = ebi.material_no " +
               "     AND COALESCE(p2.material_part_no,'') = COALESCE(ebi.material_part_no,'') " +
               "     AND p2.pending_quotation_id = :pq)))";
    }
}
