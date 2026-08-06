package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.ImportCommitResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * task-0805 · 测试用例.md §4.4/§5.8 —— 往返保真（AC-8）。
 *
 * <p>两阶段构造起点 A（§7 Q3 已裁决采纳）：
 * <ol>
 *   <li>构造合成 bundle X0（T0805-RTA 引用 T0805-RTB 的 cross_tab_ref + 条件公式 + 普通 FORMULA
 *       字段 + excelColumns + sqlViews + tabType 等全字段） → commit 到 dir1（既有 binder/remap
 *       逻辑跑一遍，产出「已规范化」状态：formula_id / formulas[].id 均已固化，cross_tab_ref.source
 *       已指向 dir1 内 T0805-RTB 副本）</li>
 *   <li>导出 dir1 得 A（此时 A 已是稳定态，不会因为"起点本身没绑定"而产生假失败）</li>
 *   <li>删除 dir1 组件（腾出全局唯一 code 命名空间，让第二轮落 CREATE 而非 RENAME，见 §7 Q4）</li>
 *   <li>用 A 导入全新 dir2 → 导出得 A″</li>
 * </ol>
 *
 * <p><b>比较方法（§7 Q3 裁决核心）</b>：`cross_tab_ref.source` 是跨组件 UUID 引用，每次 commit
 * 都生成全新组件 id，字面值在 A 与 A″ 之间必然不同——这是 G3 重映射机制的必然结果，不是缺陷。
 * 因此对这一个字段按"拓扑等价"（是否指向同批次内 T0805-RTB 副本的 Item.id）比较，其余全部字段
 * （含 formula_id / formulas[].id ——它们只在组件内部自引用，不受跨组件重映射影响）按字面值比较。
 */
@QuarkusTest
class Task0805RoundTripTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Inject
    ComponentImportService importService;

    @Inject
    ComponentExportService exportService;

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

    private UUID newDirectory(String tag) throws Exception {
        UUID dir = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) " +
                "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dir)
                .setParameter("name", "T0805-RT-" + tag + "-" + dir.toString().substring(0, 8))
                .executeUpdate();
        utx.commit();
        dirsToClean.add(dir);
        return dir;
    }

    /** 构造 §4.4 描述的 X0（T0805-RTA 引用 T0805-RTB）。origIdA/origIdB 为自定义 Item.id（模拟"某源环境已有 id"）。 */
    private ComponentExportBundle buildX0(String suffix, String origIdA, String origIdB) throws Exception {
        String codeA = "T0805-RTA-" + suffix;
        String codeB = "T0805-RTB-" + suffix;

        ComponentExportBundle.Item itemB = new ComponentExportBundle.Item();
        itemB.id = origIdB;
        itemB.code = codeB;
        itemB.name = "T0805往返夹具B";
        itemB.componentType = "NORMAL";
        itemB.tabType = "零件";
        itemB.partNoField = "料号";
        itemB.fields = M.readTree("""
            [{"name":"料号","field_type":"INPUT_TEXT"},{"name":"单价","field_type":"INPUT_NUMBER"}]""");
        itemB.formulas = M.createArrayNode();
        itemB.excelColumns = M.createArrayNode();
        itemB.rowKeyFields = M.readTree("[\"料号\"]");

        ComponentExportBundle.Item itemA = new ComponentExportBundle.Item();
        itemA.id = origIdA;
        itemA.code = codeA;
        itemA.name = "T0805往返夹具A";
        itemA.componentType = "NORMAL";
        itemA.tabType = "主件";
        itemA.partNoField = "料号";
        itemA.partNameField = "名称";
        itemA.sortField = "数量";
        itemA.fields = M.readTree("""
            [{"name":"料号","field_type":"INPUT_TEXT"},
             {"name":"名称","field_type":"INPUT_TEXT"},
             {"name":"数量","field_type":"INPUT_NUMBER"},
             {"name":"引用小计","field_type":"FORMULA","formula_name":"引用B单价"},
             {"name":"综合费率","field_type":"FORMULA","conditional_formula":{
                 "rules":[{"when":{"kind":"group","logic":"and","children":[]},"formula":"高数量费率"}],
                 "default":"标准费率"}}]""");
        itemA.formulas = M.readTree("""
            [{"name":"引用B单价","expression":[{"type":"cross_tab_ref","agg":"SUM","source":"%s","target":"单价","match":[]}]},
             {"name":"高数量费率","expression":[{"type":"number","value":"1.2"}]},
             {"name":"标准费率","expression":[{"type":"number","value":"1.0"}]}]""".formatted(origIdB));
        itemA.excelColumns = M.readTree("""
            [{"key":"col1","label":"单价","source":"VARIABLE"}]""");
        ComponentExportBundle.SqlView sv = new ComponentExportBundle.SqlView();
        sv.sqlViewName = "v_t0805_rta_" + suffix;
        sv.sqlTemplate = "SELECT 1";
        sv.declaredColumns = M.createArrayNode();
        sv.requiredVariables = List.of();
        sv.scope = "COMPONENT";
        itemA.sqlViews = List.of(sv);
        itemA.rowKeyFields = M.readTree("[\"料号\"]");

        ComponentExportBundle bundle = new ComponentExportBundle();
        bundle.bundleVersion = "1.0";
        bundle.components = List.of(itemB, itemA); // B 先于 A，remap 时无顺序依赖(两遍扫描)
        return bundle;
    }

    private String findComponentIdByCode(ImportCommitResult result, String code) {
        for (ImportCommitResult.CreatedItem ci : result.created) {
            if (code.equals(ci.finalCode) || code.equals(ci.originalCode)) return ci.componentId;
        }
        throw new AssertionError("未找到 code=" + code);
    }

    @Test
    @DisplayName("I-RT-01~03: 往返保真 —— 两阶段构造起点 A，导出→导入 dir2→再导出 A″，拓扑等价比较")
    void roundTrip_exportImportExport_topologicalEquivalence() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String origIdA = UUID.randomUUID().toString();
        String origIdB = UUID.randomUUID().toString();

        // ── 阶段 1：X0 → dir1（规范化）──────────────────────────────────────
        UUID dir1 = newDirectory("dir1-" + suffix);
        ComponentExportBundle x0 = buildX0(suffix, origIdA, origIdB);
        ImportCommitResult commit1 = importService.commit(dir1, x0, "RENAME", true, true);
        assertEquals(2, commit1.createdCount, "X0 两个组件都应成功创建");

        // ── 阶段 2：导出 dir1 得 A ──────────────────────────────────────────
        ComponentExportBundle bundleA = exportService.exportDirectory(dir1);

        // I-RT-01：验证起点已规范化（前置断言）
        ComponentExportBundle.Item aItemA = findItem(bundleA, "T0805-RTA-" + suffix);
        JsonNode fieldsA = aItemA.fields;
        JsonNode fieldByName = findField(fieldsA, "引用小计");
        assertNotNull(fieldByName.path("formula_id").asText(null), "起点 A 的「引用小计」应已固化 formula_id");
        assertTrue(!fieldByName.path("formula_id").asText("").isBlank());
        JsonNode compRule = findField(fieldsA, "综合费率").path("conditional_formula");
        assertTrue(!compRule.path("rules").get(0).path("formula_id").asText("").isBlank(), "规则分支应已固化 id");
        assertTrue(!compRule.path("default_formula_id").asText("").isBlank(), "默认分支应已固化 id");

        // ── 阶段 3：删除 dir1 组件，腾出 code 命名空间 ──────────────────────
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN " +
                "(SELECT id FROM component WHERE directory_id = :dir)").setParameter("dir", dir1).executeUpdate();
        em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir").setParameter("dir", dir1).executeUpdate();
        utx.commit();

        // ── 阶段 4：导入 A → dir2 → 导出得 A″ ──────────────────────────────
        UUID dir2 = newDirectory("dir2-" + suffix);
        ImportCommitResult commit2 = importService.commit(dir2, bundleA, "RENAME", true, true);
        assertEquals(2, commit2.createdCount, "A 的两个组件都应成功创建");
        ComponentExportBundle bundleA2 = exportService.exportDirectory(dir2);

        // ══ I-RT-02：结构化比对 A vs A″ ══════════════════════════════════════
        ComponentExportBundle.Item a2ItemA = findItem(bundleA2, "T0805-RTA-" + suffix);
        ComponentExportBundle.Item aItemB = findItem(bundleA, "T0805-RTB-" + suffix);
        ComponentExportBundle.Item a2ItemB = findItem(bundleA2, "T0805-RTB-" + suffix);

        // ① code（无冲突场景，dir1 已清空，应逐字节相等）
        assertEquals(aItemA.code, a2ItemA.code);
        assertEquals(aItemB.code, a2ItemB.code);

        // ② name/componentType/tabType/partNoField/partNameField/sortField/rowKeyFields/columnCount
        assertEquals(aItemA.name, a2ItemA.name);
        assertEquals(aItemA.componentType, a2ItemA.componentType);
        assertEquals(aItemA.tabType, a2ItemA.tabType);
        assertEquals(aItemA.partNoField, a2ItemA.partNoField);
        assertEquals(aItemA.partNameField, a2ItemA.partNameField);
        assertEquals(aItemA.sortField, a2ItemA.sortField);
        assertEquals(aItemA.rowKeyFields.toString(), a2ItemA.rowKeyFields.toString());
        assertEquals(aItemA.columnCount, a2ItemA.columnCount);

        // ③ fields：逐字段比较（含 formula_id 字面相等 —— 组件内自引用，不受跨组件 remap 影响）
        assertEquals(aItemA.fields.toString(), a2ItemA.fields.toString(),
            "T0805-RTA 的 fields 应逐字节相等（formula_id/conditional_formula 内部引用均为组件内自引用，"
            + "ensureFormulaIds/bindFormulaIdsToFields 对已有 id 原样保留，不重新生成）");

        // ④ formulas：id 字面相等；expression 结构相等，唯独 cross_tab_ref.source 按拓扑等价比较
        assertEquals(aItemA.formulas.size(), a2ItemA.formulas.size());
        for (int i = 0; i < aItemA.formulas.size(); i++) {
            JsonNode fa = aItemA.formulas.get(i);
            JsonNode fa2 = a2ItemA.formulas.get(i);
            assertEquals(fa.path("id").asText(), fa2.path("id").asText(),
                "formulas[" + i + "].id 必须字面相等（往返不得重新生成）");
            assertEquals(fa.path("name").asText(), fa2.path("name").asText());
        }
        // 「引用B单价」这条公式的 cross_tab_ref.source：A 应指向 A 里 T0805-RTB 的 Item.id，
        // A″ 应指向 A″ 里 T0805-RTB 的 Item.id —— 拓扑等价，不要求两次字面 UUID 相同。
        JsonNode refFormulaA = findFormula(aItemA.formulas, "引用B单价");
        JsonNode refFormulaA2 = findFormula(a2ItemA.formulas, "引用B单价");
        String sourceInA = refFormulaA.path("expression").get(0).path("source").asText();
        String sourceInA2 = refFormulaA2.path("expression").get(0).path("source").asText();
        assertEquals(aItemB.id, sourceInA, "A 的 cross_tab_ref.source 应指向 A 自身 T0805-RTB 副本的 Item.id");
        assertEquals(a2ItemB.id, sourceInA2, "A″ 的 cross_tab_ref.source 应指向 A″ 自身 T0805-RTB 副本的 Item.id");
        assertNotEquals(sourceInA, sourceInA2,
            "两次 source 字面值必须不同 —— 每次 commit 都生成全新组件 id，这是 G3 重映射机制的必然结果（§7 Q3 裁决），"
            + "若相等反而说明 remap 没有真正重新执行");

        // ⑤ excelColumns / sqlViews 字面相等
        assertEquals(aItemA.excelColumns.toString(), a2ItemA.excelColumns.toString());
        assertEquals(aItemA.sqlViews.size(), a2ItemA.sqlViews.size());
        assertEquals(aItemA.sqlViews.get(0).sqlViewName, a2ItemA.sqlViews.get(0).sqlViewName);
        assertEquals(aItemA.sqlViews.get(0).sqlTemplate, a2ItemA.sqlViews.get(0).sqlTemplate);

        // T0805-RTB 自身（无 FORMULA 字段，逐字段应完全相等）
        assertEquals(aItemB.fields.toString(), a2ItemB.fields.toString());
        assertEquals(aItemB.tabType, a2ItemB.tabType);
        assertEquals(aItemB.partNoField, a2ItemB.partNoField);

        // ══ I-RT-03：允许且必须不同的字段 ══════════════════════════════════
        assertNotEquals(aItemA.id, a2ItemA.id, "components[].id 必须不同（新 UUID）");
        assertNotEquals(aItemB.id, a2ItemB.id, "components[].id 必须不同（新 UUID）");
        assertNotEquals(bundleA.exportedAt, bundleA2.exportedAt, "exportedAt 必须不同");
        assertNotEquals(bundleA.checksum, bundleA2.checksum, "checksum 必须不同");
        assertNotEquals(bundleA.source.directoryId, bundleA2.source.directoryId, "source.directoryId 必须不同");
    }

    private ComponentExportBundle.Item findItem(ComponentExportBundle bundle, String code) {
        for (ComponentExportBundle.Item it : bundle.components) {
            if (code.equals(it.code)) return it;
        }
        throw new AssertionError("未找到 code=" + code + "，实际 codes=" +
                bundle.components.stream().map(i -> i.code).toList());
    }

    private JsonNode findField(JsonNode fields, String name) {
        for (JsonNode f : fields) {
            if (name.equals(f.path("name").asText())) return f;
        }
        throw new AssertionError("未找到字段 " + name);
    }

    private JsonNode findFormula(JsonNode formulas, String name) {
        for (JsonNode f : formulas) {
            if (name.equals(f.path("name").asText())) return f;
        }
        throw new AssertionError("未找到公式 " + name);
    }
}
