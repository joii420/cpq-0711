package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * task-0805 · 测试用例.md §5.6/§5.9 —— 18 份真实历史 bundle 端到端导入回归（AC-6）。
 *
 * <p>覆盖 E-IMP-01（参数化 ×18，四项通用断言）、E-IMP-02~06（bundle-01 点名断言）、
 * E-IMP-07（18 份 bundle 绑定统计总断言，纯内存对 fixture 跑 FormulaBindingInspector，不连库）。
 *
 * <p>目标目录一律 T0805-E2E- 前缀，@AfterEach 按本次用到的所有目录清理（见测试用例.md §10.2 ——
 * 这批 bundle 的组件 code 是原始历史 code，无法加 T0805 前缀，清理改按目录名定位）。
 */
@QuarkusTest
class Task0805RealBundleImportTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject
    ComponentImportService importService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    private final List<UUID> dirsToClean = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        utx.begin();
        em.joinTransaction();
        for (UUID dir : dirsToClean) {
            em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN " +
                    "(SELECT id FROM component WHERE directory_id = :dir)")
                    .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                    .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                    .setParameter("id", dir).executeUpdate();
        }
        utx.commit();
        dirsToClean.clear();
    }

    private ComponentExportBundle loadBundle(String fixtureName) throws Exception {
        try (var in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("fixtures/bundles/" + fixtureName)) {
            return M.readValue(in, ComponentExportBundle.class);
        }
    }

    private UUID newDirectory(String tag) throws Exception {
        UUID dir = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dir)
                .setParameter("name", "T0805-E2E-" + tag + "-" + dir.toString().substring(0, 8))
                .executeUpdate();
        utx.commit();
        dirsToClean.add(dir);
        return dir;
    }

    /** 结果容器：一次真实 bundle 导入的 preview + commit 产物，供断言复用。 */
    private static final class ImportOutcome {
        ImportPreviewResult preview;
        ImportCommitResult commit;
        UUID dirId;
    }

    private ImportOutcome importFixture(String fixtureName, UUID dir) throws Exception {
        ComponentExportBundle bundle = loadBundle(fixtureName);
        ImportOutcome out = new ImportOutcome();
        out.dirId = dir;
        out.preview = importService.preview(dir, bundle, "RENAME");
        // 每份 bundle 都要重新解析一次(bundle 对象在 commit 内部被就地重写引用重映射后的 formulas，
        // 不能复用 preview 用过的同一个 bundle 实例——虽然 preview 承诺只读，这里独立重新加载更保险)。
        ComponentExportBundle freshBundle = loadBundle(fixtureName);
        out.commit = importService.commit(dir, freshBundle, "RENAME", true, true);
        return out;
    }

    // ── 通用工具：递归收集 formulas 里的 cross_tab_ref.source / component_subtotal.component_code ──

    private void collectRefs(JsonNode node, Set<String> crossTabSources, Set<String> subtotalCodes) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            String type = node.path("type").asText("");
            if ("cross_tab_ref".equals(type)) {
                String src = node.path("source").asText("");
                if (!src.isBlank()) crossTabSources.add(src);
            }
            if ("component_subtotal".equals(type)) {
                String code = node.path("component_code").asText("");
                if (!code.isBlank()) subtotalCodes.add(code);
            }
            node.fields().forEachRemaining(e -> collectRefs(e.getValue(), crossTabSources, subtotalCodes));
        } else if (node.isArray()) {
            for (JsonNode c : node) collectRefs(c, crossTabSources, subtotalCodes);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> queryCreatedComponents(UUID dir) {
        return em.createNativeQuery(
                "SELECT code, fields::text, formulas::text FROM component WHERE directory_id = :dir ORDER BY code")
                .setParameter("dir", dir).getResultList();
    }

    // ── E-IMP-01：18 份 bundle 通用四项断言 ─────────────────────────────────────

    @ParameterizedTest(name = "E-IMP-01[{0}]")
    @ValueSource(strings = {
        "bundle-01.json", "bundle-02.json", "bundle-03.json", "bundle-04.json", "bundle-05.json",
        "bundle-06.json", "bundle-07.json", "bundle-08.json", "bundle-09.json", "bundle-10.json",
        "bundle-11.json", "bundle-12.json", "bundle-13.json", "bundle-14.json", "bundle-15.json",
        "bundle-16.json", "bundle-17.json", "bundle-18.json"
    })
    @DisplayName("E-IMP-01: 18 份真实 bundle 端到端导入 —— ①公式皆有id ②formula_id反查=preview展示 ③cross_tab_ref指向本批次新id ④component_subtotal指向本批次finalCode")
    void realBundle_endToEnd_fourAssertions(String fixtureName) throws Exception {
        UUID dir = newDirectory(fixtureName.replace(".json", ""));
        ImportOutcome outcome = importFixture(fixtureName, dir);

        assertTrue(outcome.commit.createdCount > 0, fixtureName + ": 应至少创建 1 个组件");
        assertEquals(0, outcome.commit.skippedCount, fixtureName + ": RENAME 策略下不应有 SKIP");

        // 本批次新建组件 id 全集 + finalCode 全集 + 原始 bundle Item.id 全集(用于反证③不等于旧值)
        Set<String> newComponentIds = new HashSet<>();
        Set<String> finalCodes = new HashSet<>();
        for (ImportCommitResult.CreatedItem ci : outcome.commit.created) {
            newComponentIds.add(ci.componentId);
            finalCodes.add(ci.finalCode);
        }
        ComponentExportBundle originalBundle = loadBundle(fixtureName);
        Set<String> originalItemIds = new HashSet<>();
        for (ComponentExportBundle.Item it : originalBundle.components) {
            if (it.id != null && !it.id.isBlank()) originalItemIds.add(it.id);
        }

        // preview 侧：originalCode -> (fieldName -> resolvedFormulaName)，只取非 UNRESOLVABLE
        Map<String, Map<String, String>> previewByCodeAndField = new HashMap<>();
        for (ImportPreviewResult.ComponentPlan plan : outcome.preview.components) {
            Map<String, String> m = new HashMap<>();
            if (plan.formulaBinding != null) {
                for (ImportPreviewResult.FormulaBindingItem it : plan.formulaBinding) {
                    if (it.resolvedFormulaName != null) m.put(it.fieldName, it.resolvedFormulaName);
                }
            }
            previewByCodeAndField.put(plan.code, m);
        }
        // originalCode -> finalCode 映射(供②按原 code 找回 preview 记录，同时按 finalCode 查库)
        Map<String, String> origToFinal = new HashMap<>();
        for (ImportCommitResult.CreatedItem ci : outcome.commit.created) {
            origToFinal.put(ci.originalCode, ci.finalCode);
        }

        List<Object[]> rows = queryCreatedComponents(dir);
        assertEquals(outcome.commit.createdCount, rows.size(), fixtureName + ": 落库组件数应等于 createdCount");

        int checkedFieldAssertions = 0;
        int checkedCrossTabRefs = 0;
        int checkedSubtotalRefs = 0;

        for (Object[] row : rows) {
            String finalCode = (String) row[0];
            JsonNode fields = M.readTree((String) row[1]);
            JsonNode formulas = M.readTree((String) row[2]);

            // ① 每条公式必须有非空 id
            for (JsonNode f : formulas) {
                String id = f.path("id").asText(null);
                assertTrue(id != null && !id.isBlank(),
                    fixtureName + "/" + finalCode + ": 公式「" + f.path("name").asText() + "」缺 id");
            }

            // ② 非条件 FORMULA 字段：formula_id 反查名称 == preview 展示的 resolvedFormulaName
            String originalCode = origToFinal.entrySet().stream()
                    .filter(e -> e.getValue().equals(finalCode)).map(Map.Entry::getKey).findFirst().orElse(null);
            Map<String, String> previewFields = previewByCodeAndField.getOrDefault(originalCode, Map.of());
            for (JsonNode f : fields) {
                if (!"FORMULA".equals(f.path("field_type").asText(""))) continue;
                if (f.has("conditional_formula")) continue; // 条件公式不走字段级 formula_id，见 §B2
                String fieldName = f.path("name").asText("");
                String expected = previewFields.get(fieldName); // null 除非 preview 报的是非 UNRESOLVABLE
                if (expected == null) continue; // 该字段 preview 阶段本就是 UNRESOLVABLE(本批 18 bundle 实测 0 例)，无需核对

                String formulaId = f.path("formula_id").asText(null);
                // 反向门禁关键：可解析的字段必须真的落了 formula_id，不能静默跳过 —— 否则
                // G-GATE-01(禁用 bindFormulaIdsToFields)会因为这里"formula_id 为空就 continue"
                // 而使本断言整体空转通过，起不到门禁作用。
                assertTrue(formulaId != null && !formulaId.isBlank(),
                    fixtureName + "/" + finalCode + "/" + fieldName
                    + ": preview 报可解析(" + expected + ")，commit 落库后 formula_id 不应为空");

                String resolvedName = null;
                for (JsonNode fm : formulas) {
                    if (formulaId.equals(fm.path("id").asText(null))) { resolvedName = fm.path("name").asText(); break; }
                }
                assertEquals(expected, resolvedName,
                    fixtureName + "/" + finalCode + "/" + fieldName + ": commit 落库反查名称应与 preview 展示一致");
                checkedFieldAssertions++;
            }

            // ③④ 递归收集 cross_tab_ref.source / component_subtotal.component_code
            Set<String> crossTabSources = new LinkedHashSet<>();
            Set<String> subtotalCodes = new LinkedHashSet<>();
            collectRefs(formulas, crossTabSources, subtotalCodes);

            for (String src : crossTabSources) {
                assertTrue(newComponentIds.contains(src),
                    fixtureName + "/" + finalCode + ": cross_tab_ref.source=" + src + " 应指向本批次新建组件 id");
                assertFalse(originalItemIds.contains(src),
                    fixtureName + "/" + finalCode + ": cross_tab_ref.source=" + src + " 不应仍是原始 bundle Item.id");
                checkedCrossTabRefs++;
            }
            for (String code : subtotalCodes) {
                assertTrue(finalCodes.contains(code),
                    fixtureName + "/" + finalCode + ": component_subtotal.component_code=" + code + " 应指向本批次 finalCode");
                checkedSubtotalRefs++;
            }
        }

        // 记录本次实际验证覆盖度(供交付证据):不是空跑
        System.out.printf("[E-IMP-01][%s] createdCount=%d fieldAssertions=%d crossTabRefs=%d subtotalRefs=%d%n",
                fixtureName, outcome.commit.createdCount, checkedFieldAssertions, checkedCrossTabRefs, checkedSubtotalRefs);
    }

    // ── E-IMP-02~06：bundle-01（bug2-重算.json）点名断言 ────────────────────────

    @Test
    @DisplayName("E-IMP-02: bundle-01 COMP-0032「材料成本」条件公式固化 —— 规则1=非银点类材料成本公式, 默认=银点材料成本公式")
    void bundle01_comp0032_conditionalFormula_consolidatedCorrectly() throws Exception {
        UUID dir = newDirectory("b01-02");
        ImportOutcome outcome = importFixture("bundle-01.json", dir);
        String finalCode = finalCodeOf(outcome, "COMP-0032");

        List<JsonNode> fieldsFormulas = fieldsAndFormulasOf(dir, finalCode);
        JsonNode fields = fieldsFormulas.get(0);
        JsonNode formulas = fieldsFormulas.get(1);

        JsonNode materialCostField = null;
        for (JsonNode f : fields) {
            if ("材料成本".equals(f.path("name").asText())) { materialCostField = f; break; }
        }
        assertTrue(materialCostField != null, "COMP-0032 应有「材料成本」字段");
        JsonNode cf = materialCostField.path("conditional_formula");

        String ruleFormulaId = cf.path("rules").get(0).path("formula_id").asText(null);
        String defaultFormulaId = cf.path("default_formula_id").asText(null);
        assertEquals("非银点类材料成本公式", nameById(formulas, ruleFormulaId), "规则1应固化到非银点类材料成本公式");
        assertEquals("银点材料成本公式", nameById(formulas, defaultFormulaId), "默认分支应固化到银点材料成本公式");
    }

    @Test
    @DisplayName("E-IMP-03: bundle-01 COMP-0032 三处 cross_tab_ref.source 均不再是原始 UUID，且落在本批次新 id 内")
    void bundle01_comp0032_crossTabRef_remappedFromOriginalUuids() throws Exception {
        UUID dir = newDirectory("b01-03");
        ImportOutcome outcome = importFixture("bundle-01.json", dir);
        String finalCode = finalCodeOf(outcome, "COMP-0032");

        Set<String> originalUuids = Set.of(
                "6206da55-5a6e-42f1-ac59-6f04a007d4d6",
                "894579da-2e69-48fd-ad8b-bb093e132490",
                "302b97cf-003a-486c-a536-e25207316c8d");

        Set<String> newComponentIds = new HashSet<>();
        for (ImportCommitResult.CreatedItem ci : outcome.commit.created) newComponentIds.add(ci.componentId);

        JsonNode formulas = fieldsAndFormulasOf(dir, finalCode).get(1);
        Set<String> crossTabSources = new LinkedHashSet<>();
        Set<String> subtotalCodes = new LinkedHashSet<>();
        collectRefs(formulas, crossTabSources, subtotalCodes);

        assertTrue(crossTabSources.size() >= 3, "COMP-0032 应至少含 3 处 cross_tab_ref: " + crossTabSources);
        for (String src : crossTabSources) {
            assertFalse(originalUuids.contains(src), "不应仍是原始 UUID: " + src);
            assertTrue(newComponentIds.contains(src), "应指向本批次新建组件 id: " + src);
        }
    }

    @Test
    @DisplayName("E-IMP-04: bundle-01 COMP-0028「管理费」formula_id 反查名称 == 管理费")
    void bundle01_comp0028_managementFee_resolvedByName() throws Exception {
        UUID dir = newDirectory("b01-04");
        ImportOutcome outcome = importFixture("bundle-01.json", dir);
        String finalCode = finalCodeOf(outcome, "COMP-0028");

        List<JsonNode> fieldsFormulas = fieldsAndFormulasOf(dir, finalCode);
        JsonNode fields = fieldsFormulas.get(0);
        JsonNode formulas = fieldsFormulas.get(1);

        JsonNode field = null;
        for (JsonNode f : fields) {
            if ("管理费".equals(f.path("name").asText())) { field = f; break; }
        }
        assertTrue(field != null, "COMP-0028 应有「管理费」字段");
        String formulaId = field.path("formula_id").asText(null);
        assertEquals("管理费", nameById(formulas, formulaId));
    }

    @Test
    @DisplayName("E-IMP-05: bundle-01 COMP-0031(SUBTOTAL) 「公式1」有id，fields 仍为空数组")
    void bundle01_comp0031_subtotalComponent_formulaHasId_fieldsEmpty() throws Exception {
        UUID dir = newDirectory("b01-05");
        ImportOutcome outcome = importFixture("bundle-01.json", dir);
        String finalCode = finalCodeOf(outcome, "COMP-0031");

        List<JsonNode> fieldsFormulas = fieldsAndFormulasOf(dir, finalCode);
        JsonNode fields = fieldsFormulas.get(0);
        JsonNode formulas = fieldsFormulas.get(1);

        assertTrue(fields.isArray() && fields.size() == 0, "COMP-0031 fields 应仍为空数组: " + fields);
        assertEquals(1, formulas.size());
        String id = formulas.get(0).path("id").asText(null);
        assertTrue(id != null && !id.isBlank(), "公式1 应有非空 id");
    }

    @Test
    @DisplayName("E-IMP-06: bundle-01 COMP-0032 全部非条件 FORMULA 字段 —— preview 展示与 commit 落库逐字段一致")
    void bundle01_comp0032_allFormulaFields_previewMatchesCommit() throws Exception {
        UUID dir = newDirectory("b01-06");
        ImportOutcome outcome = importFixture("bundle-01.json", dir);
        String finalCode = finalCodeOf(outcome, "COMP-0032");

        ImportPreviewResult.ComponentPlan plan = null;
        for (ImportPreviewResult.ComponentPlan p : outcome.preview.components) {
            if ("COMP-0032".equals(p.code)) { plan = p; break; }
        }
        assertTrue(plan != null);
        Map<String, String> previewMap = new HashMap<>();
        for (ImportPreviewResult.FormulaBindingItem it : plan.formulaBinding) {
            if (!it.fieldName.contains("›")) previewMap.put(it.fieldName, it.resolvedFormulaName);
        }

        List<JsonNode> fieldsFormulas = fieldsAndFormulasOf(dir, finalCode);
        JsonNode fields = fieldsFormulas.get(0);
        JsonNode formulas = fieldsFormulas.get(1);

        int checked = 0;
        for (JsonNode f : fields) {
            if (!"FORMULA".equals(f.path("field_type").asText(""))) continue;
            if (f.has("conditional_formula")) continue;
            String fieldName = f.path("name").asText("");
            if (!previewMap.containsKey(fieldName)) continue;
            String formulaId = f.path("formula_id").asText(null);
            String actualName = nameById(formulas, formulaId);
            assertEquals(previewMap.get(fieldName), actualName, "字段「" + fieldName + "」preview 与 commit 应一致");
            checked++;
        }
        // 实测更正：COMP-0032 本身有 7 个非条件 FORMULA 字段（来料回收费/来料财务费/材料损耗成本/
        // 来料损耗率/来料加工费/回收成本/公式10）；文件级总数 9 = 本组件 7 + COMP-0029「小计」1 +
        // COMP-0028「管理费」1，此前测试用例.md 设计阶段的静态分析笔记误把文件级总数当成单组件数。
        assertEquals(7, checked, "bundle-01 COMP-0032 应有 7 个非条件 FORMULA 字段参与核对");
    }

    // ── E-IMP-07（2026-08-05 追加）：18 份 bundle 绑定统计总断言（纯内存，不连库）─────

    @Test
    @DisplayName("E-IMP-07: 18 份 bundle 汇总 —— totalFormulaRefs=21, RESOLVED_BY_NAME=21, 其余三种=0")
    void allBundles_aggregateBindingStatistics() throws Exception {
        String[] names = {
            "bundle-01.json", "bundle-02.json", "bundle-03.json", "bundle-04.json", "bundle-05.json",
            "bundle-06.json", "bundle-07.json", "bundle-08.json", "bundle-09.json", "bundle-10.json",
            "bundle-11.json", "bundle-12.json", "bundle-13.json", "bundle-14.json", "bundle-15.json",
            "bundle-16.json", "bundle-17.json", "bundle-18.json"
        };
        List<FormulaBindingInspector.Report> reports = new ArrayList<>();
        for (String name : names) {
            ComponentExportBundle bundle = loadBundle(name);
            for (ComponentExportBundle.Item it : bundle.components) {
                reports.add(FormulaBindingInspector.inspect(it.code, it.name, it.fields, it.formulas));
            }
        }
        FormulaBindingInspector.Report merged = FormulaBindingInspector.merge(reports);

        int bound = 0, byName = 0, byPosition = 0, unresolvable = 0;
        for (FormulaBindingInspector.Item it : merged.items) {
            switch (it.status) {
                case "BOUND" -> bound++;
                case "RESOLVED_BY_NAME" -> byName++;
                case "RESOLVED_BY_POSITION" -> byPosition++;
                case "UNRESOLVABLE" -> unresolvable++;
                default -> throw new AssertionError("未知 status: " + it.status);
            }
        }

        assertEquals(21, merged.totalFormulaRefs, "17 个普通 FORMULA 字段 + 2 个条件公式字段各 1 rule + 1 default = 21");
        assertEquals(21, byName, "18 份真实 bundle 天然应全部 RESOLVED_BY_NAME（§0 关键发现①）");
        assertEquals(0, bound, "18 份真实 bundle 无任何字段带显式 formula_id");
        assertEquals(0, byPosition, "18 份真实 bundle 结构上不可能触发位置回退（见测试用例.md §0）");
        assertEquals(0, unresolvable, "18 份真实 bundle 天然无 UNRESOLVABLE");
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    private String finalCodeOf(ImportOutcome outcome, String originalCode) {
        for (ImportCommitResult.CreatedItem ci : outcome.commit.created) {
            if (originalCode.equals(ci.originalCode)) return ci.finalCode;
        }
        throw new AssertionError("未找到 originalCode=" + originalCode + " 对应的 finalCode");
    }

    /** 返回 [fields, formulas] 两个 JsonNode。 */
    private List<JsonNode> fieldsAndFormulasOf(UUID dir, String finalCode) {
        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT fields::text, formulas::text FROM component WHERE directory_id = :dir AND code = :code")
                .setParameter("dir", dir).setParameter("code", finalCode).getSingleResult();
        try {
            return List.of(M.readTree((String) row[0]), M.readTree((String) row[1]));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String nameById(JsonNode formulas, String id) {
        if (id == null) return null;
        for (JsonNode fm : formulas) {
            if (id.equals(fm.path("id").asText(null))) return fm.path("name").asText();
        }
        return null;
    }
}
