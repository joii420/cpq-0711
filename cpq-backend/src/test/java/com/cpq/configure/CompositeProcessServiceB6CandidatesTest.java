package com.cpq.configure;

import com.cpq.configure.dto.CompositeProcessCandidateDTO;
import com.cpq.configure.service.CompositeProcessService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0712 B6 — 组合工艺候选收敛（架构决策 2-2A 定稿）单测。
 *
 * <p>验证 {@code GET /api/cpq/composite-processes}（{@link CompositeProcessService#listAssemblyCandidates()}）
 * 已从 {@code composite_process_def} 切到工序库 {@code process_master WHERE process_category='ASSEMBLY'}，
 * 标识锚点 {@code code} = {@code process_master.process_no}（现网实值 4 行，无需种子迁移）。
 */
@QuarkusTest
class CompositeProcessServiceB6CandidatesTest {

    @Inject
    CompositeProcessService service;

    @Test
    void listAssemblyCandidates_returnsProcessMasterAssemblyRows_notCompositeProcessDef() {
        List<CompositeProcessCandidateDTO> candidates = service.listAssemblyCandidates();

        assertEquals(4, candidates.size(),
            "process_master(process_category='ASSEMBLY') 现网实值 4 行（总装配/部件装配/螺栓连接/焊接装配）");

        List<String> codes = candidates.stream().map(c -> c.code).sorted().toList();
        assertEquals(List.of("MRO-AS-0001", "MRO-AS-0002", "MRO-AS-0003", "MRO-AS-0004"), codes,
            "候选 code 锚点应为 process_master.process_no，而非 composite_process_def.code（如 RIVET）");

        // 候选 code 集合不应含旧 composite_process_def 的种子 code（RIVET/RESISTANCE_WELD 等），
        // 证明已解绑、不再从旧表取候选。
        assertFalse(codes.contains("RIVET"), "候选不应含 composite_process_def 的 RIVET（已解绑）");

        CompositeProcessCandidateDTO first = candidates.stream()
            .filter(c -> "MRO-AS-0001".equals(c.code)).findFirst().orElseThrow();
        assertEquals("总装配", first.name, "name 应读自 process_master.process_name");
        // ASSEMBLY 现网 4 行 currency/unit/defectRate 均空（DB 实查），候选 DTO 原样透传不兜底
        // （兜底发生在落库侧 insertCompositeProcessCapacityV6，不在候选查询层）。
        assertNull(first.currency);
        assertNull(first.unit);
        assertNull(first.defectRate);
    }
}
