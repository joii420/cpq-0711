package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.ImportCommitResult;
import com.cpq.component.dto.ImportPreviewResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * task-0805 · 测试用例.md §5.7 —— {@code Item.id} 缺失显式暴露（AC-7）。
 *
 * <p>夹具：{@code bundle-01.json}（bug2-重算.json）克隆，把其中 {@code code=COMP-0033}
 * 的 {@code Item.id} 置为 null。{@code COMP-0032}「物料」的 {@code cross_tab_ref.source}
 * 原本就等于 {@code COMP-0033} 的原始 id（{@code 6206da55-5a6e-42f1-ac59-6f04a007d4d6}，
 * 已用 Python 核实，见测试用例.md §4.3），因此这是「同批次内因 id 缺失导致断链」的真实语义场景。
 *
 * <p>§7 Q2 已裁决：bundle 内存在任一 {@code Item.id==null} → 全部悬空引用判
 * {@code BUNDLE_MISSING_ITEM_ID}。本类覆盖 I-AC7-01~03。
 */
@QuarkusTest
class Task0805CrossRefIssueTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String MISSING_TARGET_ORIGINAL_UUID = "6206da55-5a6e-42f1-ac59-6f04a007d4d6";

    @Inject
    ComponentImportService importService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private UUID dirId;

    @BeforeEach
    void setupDirectory() throws Exception {
        dirId = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery(
                "INSERT INTO component_directory(id, name, sort_order, created_at) VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dirId)
                .setParameter("name", "T0805-AC7-" + dirId.toString().substring(0, 8))
                .executeUpdate();
        utx.commit();
    }

    @AfterEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN " +
                "(SELECT id FROM component WHERE directory_id = :dir)")
                .setParameter("dir", dirId).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                .setParameter("dir", dirId).executeUpdate();
        em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                .setParameter("id", dirId).executeUpdate();
        utx.commit();
    }

    private ComponentExportBundle loadFixtureWithNulledItemId() throws Exception {
        ComponentExportBundle bundle;
        try (var in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("fixtures/bundles/bundle-01.json")) {
            bundle = M.readValue(in, ComponentExportBundle.class);
        }
        boolean found = false;
        for (ComponentExportBundle.Item it : bundle.components) {
            if ("COMP-0033".equals(it.code)) {
                assertEquals(MISSING_TARGET_ORIGINAL_UUID, it.id,
                    "前置条件：COMP-0033 的原始 id 应等于 COMP-0032 cross_tab_ref 引用的那个 UUID（§4.3 已用 Python 核实）");
                it.id = null; // 模拟老 bundle 该条目缺 id
                found = true;
            }
        }
        assertTrue(found, "夹具应含 code=COMP-0033 的组件");
        return bundle;
    }

    // ── I-AC7-01：crossRefIssues 非空，点名 COMP-0032，reason=BUNDLE_MISSING_ITEM_ID ──

    @Test
    @DisplayName("I-AC7-01: preview() crossRefIssues 含 COMP-0032 引用悬空 UUID，reason=BUNDLE_MISSING_ITEM_ID")
    void preview_exposesCrossRefIssue_withCorrectReason() throws Exception {
        ComponentExportBundle bundle = loadFixtureWithNulledItemId();

        ImportPreviewResult result = importService.preview(dirId, bundle, "RENAME");

        assertTrue(result.crossRefIssues != null && !result.crossRefIssues.isEmpty(), "crossRefIssues 不应为空");

        boolean found = false;
        for (ImportPreviewResult.CrossRefIssue issue : result.crossRefIssues) {
            if ("COMP-0032".equals(issue.componentCode) && MISSING_TARGET_ORIGINAL_UUID.equals(issue.ref)) {
                assertEquals("BUNDLE_MISSING_ITEM_ID", issue.reason,
                    "bundle 内存在 Item.id==null(COMP-0033) → 应判 BUNDLE_MISSING_ITEM_ID（§7 Q2 裁决）");
                assertEquals("UUID", issue.refType);
                found = true;
            }
        }
        assertTrue(found, "应有一条 componentCode=COMP-0032 且 ref=" + MISSING_TARGET_ORIGINAL_UUID + " 的 crossRefIssue");
    }

    // ── I-AC7-02：warnings 点名（不再只落日志）───────────────────────────────

    @Test
    @DisplayName("I-AC7-02: preview() warnings 含跨组件引用无法重映射的高层提示（详情点名落在 crossRefIssues，非 warnings 字符串本身）")
    void preview_warningsFlagsCrossRefIssue_detailIsInCrossRefIssuesArray() throws Exception {
        ComponentExportBundle bundle = loadFixtureWithNulledItemId();

        ImportPreviewResult result = importService.preview(dirId, bundle, "RENAME");

        // 实现计划 §2.2 冻结契约给出的 warnings 示例就是计数型文案（"⚠ 2 处跨组件引用无法重映射…"），
        // 不要求逐条点名组件——逐条点名的责任在 crossRefIssues[]（I-AC7-01 已验证其 componentCode 字段）。
        // 本用例断言 warnings 确实抬头示警（不再"只落日志"），且计数与 crossRefIssues 实际条数一致。
        boolean flagged = false;
        for (String w : result.warnings) {
            if (w.contains("跨组件引用无法重映射")) flagged = true;
        }
        assertTrue(flagged, "warnings 应有一条跨组件引用无法重映射的提示（不再只落日志）: " + result.warnings);
        assertTrue(result.warnings.stream().anyMatch(w -> w.contains(String.valueOf(result.crossRefIssues.size()))),
            "warnings 的计数应与 crossRefIssues 实际条数一致: " + result.warnings + " vs crossRefIssues.size()=" + result.crossRefIssues.size());
    }

    // ── I-AC7-03：commit 仍成功，断链行为如实持久化（已知缺口，非本任务修复范围）──

    @Test
    @DisplayName("I-AC7-03: commit 仍 200；落库后 COMP-0032 的 cross_tab_ref.source 仍是原始悬空 UUID（未被静默修好）")
    void commit_stillSucceeds_brokenRefPersistsAsIs() throws Exception {
        ComponentExportBundle bundle = loadFixtureWithNulledItemId();

        ImportCommitResult result = importService.commit(dirId, bundle, "RENAME", true, true);

        assertTrue(result.createdCount > 0, "commit 不应因跨引用断链新增阻断");

        String finalCode = null;
        for (ImportCommitResult.CreatedItem ci : result.created) {
            if ("COMP-0032".equals(ci.originalCode)) finalCode = ci.finalCode;
        }
        assertTrue(finalCode != null, "COMP-0032 应在本次落库结果里");

        String formulasText = (String) em.createNativeQuery(
                "SELECT formulas::text FROM component WHERE directory_id = :dir AND code = :code")
                .setParameter("dir", dirId).setParameter("code", finalCode).getSingleResult();

        assertTrue(formulasText.contains(MISSING_TARGET_ORIGINAL_UUID),
            "断链应如实持久化：cross_tab_ref.source 仍应是原始悬空 UUID（idMap 缺 COMP-0033 条目，"
            + "无法重映射——这是当前已知且验收范围内接受的行为，preview 已显式暴露，commit 不要求修复）");
    }
}
