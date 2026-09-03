package com.cpq.configure;

import com.cpq.configure.dto.CompositeProcessCandidateDTO;
import com.cpq.configure.service.CompositeProcessService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0712 B6 — 组合工艺候选收敛（架构决策 2-2A 定稿）单测。
 *
 * <p>验证 {@code GET /api/cpq/composite-processes}（{@link CompositeProcessService#listAssemblyCandidates()}）
 * 已从 {@code composite_process_def} 切到工序库 {@code process_master}，标识锚点
 * {@code code} = {@code process_master.process_no}。
 *
 * <h3>🚨 task-260902 · B-12：本类原来验的是一组不存在的数据</h3>
 * 原断言写死 {@code 4 行 + MRO-AS-0001..0004 + process_category='ASSEMBLY'}。实查 {@code cpq_db_0724}：
 * <ul>
 *   <li>{@code MRO-*} 那 26 条是 {@code V4} 带的通用示例（CNC加工/包装入库…），
 *       <b>本库从未灌入</b>（Flyway 基线晚于 V4，「种子随早期迁移交付」的表在本库一律为空）；</li>
 *   <li>{@code process_master} 现存 {@code Z100 焊接} / {@code Z101 铆接}，
 *       且 {@code process_category} 的实值是<b>中文「组装」</b>，不是英文 {@code ASSEMBLY}。</li>
 * </ul>
 * ⇒ 用户已确认工序是业务在「主数据维护 → 工序」页自维护的<b>开放主数据</b>，
 * 🚫 <b>不写迁移补种子</b>；改为「拿库里真实的组装类工序对账」。
 *
 * <p>📌 计数不写死（{@code fixture基线.md §6}）：共享库会漂移，断言改为
 * 「候选集合 == 库里组装类工序集合」这个<b>不变量</b>，而不是「恰好 4 条」。
 */
@QuarkusTest
class CompositeProcessServiceB6CandidatesTest {

    @Inject
    CompositeProcessService service;

    @Inject
    EntityManager em;

    @Test
    @SuppressWarnings("unchecked")
    void listAssemblyCandidates_returnsProcessMasterAssemblyRows_notCompositeProcessDef() {
        List<CompositeProcessCandidateDTO> candidates = service.listAssemblyCandidates();
        List<String> codes = candidates.stream().map(c -> c.code).sorted().toList();

        // ── 不变量：候选集合 == process_master 里「组装类」工序的 process_no 集合 ──
        List<String> expected = em.createNativeQuery(
                "SELECT process_no FROM process_master WHERE process_category IN ('ASSEMBLY', '组装') " +
                "ORDER BY process_no").getResultList();
        assertEquals(expected, codes,
            "候选 code 锚点应为 process_master.process_no（组装类），实际=" + codes + " 期望=" + expected);
        assertFalse(codes.isEmpty(),
            "前置不成立：process_master 里一条组装类工序都没有 —— " +
            "组合工艺整条路无法验证。请在「主数据维护 → 工序」维护至少一条分类为「组装」的工序。");

        // ── 现网 fixture 基线：Z100 焊接 / Z101 铆接（fixture基线.md §2）──
        assertTrue(codes.contains("Z100"), "fixture 基线：Z100 焊接 应在候选里，实际=" + codes);
        assertTrue(codes.contains("Z101"), "fixture 基线：Z101 铆接 应在候选里，实际=" + codes);

        // 候选 code 集合不应含旧 composite_process_def 的种子 code（RIVET/RESISTANCE_WELD 等），
        // 证明已解绑、不再从旧表取候选。
        assertFalse(codes.contains("RIVET"), "候选不应含 composite_process_def 的 RIVET（已解绑）");

        CompositeProcessCandidateDTO z100 = candidates.stream()
            .filter(c -> "Z100".equals(c.code)).findFirst().orElseThrow();
        assertEquals("焊接", z100.name, "name 应读自 process_master.process_name");
        // Z100/Z101 现网 standard_currency=CNY / standard_unit=PCS（DB 实查），候选 DTO 原样透传不兜底
        // （兜底发生在落库侧 insertCompositeProcessCapacityV6，不在候选查询层）。
        Object[] raw = (Object[]) em.createNativeQuery(
                "SELECT standard_currency, standard_unit, default_defect_rate " +
                "FROM process_master WHERE process_no = 'Z100'").getSingleResult();
        assertEquals(raw[0], z100.currency, "currency 应原样透传 process_master.standard_currency");
        assertEquals(raw[1], z100.unit, "unit 应原样透传 process_master.standard_unit");
        assertEquals(raw[2] == null ? null : raw[2].toString(),
                z100.defectRate == null ? null : z100.defectRate.toString(),
                "defectRate 应原样透传 process_master.default_defect_rate");
    }
}
