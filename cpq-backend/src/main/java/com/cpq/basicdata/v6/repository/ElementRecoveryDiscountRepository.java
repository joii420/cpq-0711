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
    public static String key(String materialNo, String componentNo) { return materialNo + DELIM + componentNo; }

    /** 一条更新（recovery_discount 可空——直接赋值，null 会覆盖为 NULL，与原逐行 SET 语义一致）。 */
    public record Update(String materialNo, String componentNo, BigDecimal recoveryDiscount) {}

    /** 原 Q05 逐行 UPDATE（逐字保留，等价基准），返回受影响行数。 */
    public int updateOne(String customerNo, String materialNo, String componentNo,
                         BigDecimal rd, UUID updatedBy) {
        return em.createNativeQuery(
                "UPDATE element_bom_item SET recovery_discount = :rd, updated_at = NOW(), updated_by = :u " +
                "WHERE system_type='QUOTE' AND customer_no=:c AND material_no=:m " +
                "  AND component_no=:cn AND is_current = TRUE")
            .setParameter("rd", rd).setParameter("u", updatedBy).setParameter("c", customerNo)
            .setParameter("m", materialNo).setParameter("cn", componentNo)
            .executeUpdate();
    }

    /**
     * 一次 tuple-IN GROUP BY 取每 (material_no, component_no) 键的 is_current 匹配行数。
     * = 各键逐行 {@link #updateOne} 会返回的 updated 计数（每行只属一个键 → 计数一致）。
     * 返回以 {@link #key} 编码的 Map；缺失键不在 Map 中（= 0 匹配）。按 1000 键分块。
     */
    public Map<String, Integer> countCurrentMatches(String customerNo, List<String[]> keys) {
        Map<String, Integer> out = new HashMap<>();
        if (keys == null || keys.isEmpty()) return out;
        final int CHUNK = 1000;
        for (int start = 0; start < keys.size(); start += CHUNK) {
            List<String[]> chunk = keys.subList(start, Math.min(start + CHUNK, keys.size()));
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) in.append(", ");
                in.append("(:m").append(i).append(", :n").append(i).append(")");
            }
            var q = em.createNativeQuery(
                    "SELECT material_no, component_no, count(*) FROM element_bom_item " +
                    "WHERE system_type='QUOTE' AND customer_no = :c AND is_current = TRUE " +
                    "  AND (material_no, component_no) IN (" + in + ") " +
                    "GROUP BY material_no, component_no")
                .setParameter("c", customerNo);
            for (int i = 0; i < chunk.size(); i++) {
                q.setParameter("m" + i, chunk.get(i)[0]);
                q.setParameter("n" + i, chunk.get(i)[1]);
            }
            @SuppressWarnings("unchecked")
            List<Object[]> rs = q.getResultList();
            for (Object[] r : rs) {
                out.put(key(String.valueOf(r[0]), String.valueOf(r[1])), ((Number) r[2]).intValue());
            }
        }
        return out;
    }

    /**
     * 批量更新（一条 {@code UPDATE ... FROM (VALUES ...)}）。前提：调用方已按 (material_no, component_no) 去重 +
     * 末值胜（与逐行 SET 的"后写覆盖"一致）。recovery_discount 用 {@code CAST(:r AS numeric)} 显式标注，
     * 防多行 VALUES 首行 NULL 致 PG 无法推断列类型（与 Q19 同款，但本处是 UPDATE…FROM(VALUES) 无目标列上下文，
     * 故 material_no/component_no 也一并 CAST）。按 1000 行分块。
     */
    public void batchUpdate(String customerNo, List<Update> updates, UUID updatedBy) {
        if (updates == null || updates.isEmpty()) return;
        final int CHUNK = 1000;
        for (int start = 0; start < updates.size(); start += CHUNK) {
            List<Update> chunk = updates.subList(start, Math.min(start + CHUNK, updates.size()));
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) vals.append(", ");
                vals.append("(CAST(:m").append(i).append(" AS varchar), CAST(:n").append(i)
                    .append(" AS varchar), CAST(:r").append(i).append(" AS numeric))");
            }
            String sql =
                "UPDATE element_bom_item AS ebi SET recovery_discount = v.rd, updated_at = NOW(), updated_by = :u " +
                "FROM (VALUES " + vals + ") AS v(material_no, component_no, rd) " +
                "WHERE ebi.system_type='QUOTE' AND ebi.customer_no = :c AND ebi.is_current = TRUE " +
                "  AND ebi.material_no = v.material_no AND ebi.component_no = v.component_no";
            var q = em.createNativeQuery(sql).setParameter("u", updatedBy).setParameter("c", customerNo);
            for (int i = 0; i < chunk.size(); i++) {
                q.setParameter("m" + i, chunk.get(i).materialNo());
                q.setParameter("n" + i, chunk.get(i).componentNo());
                q.setParameter("r" + i, chunk.get(i).recoveryDiscount());
            }
            q.executeUpdate();
        }
    }
}
