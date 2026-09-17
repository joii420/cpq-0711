package com.cpq.component.service;

import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.ImportCommitResult;
import com.cpq.component.entity.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-260916 B-4 (AC-16): importing the real legacy export bundles (1.0 / 1.1) rewrites the Excel
 * TAB_JOIN_FORMULA text to the "(小计)" notation; export is 1.2; re-import of the 1.2 bundle is stable.
 * Runs against the one-off database only (DB_NAME=cpq_db_rp0916c). Creates its own directories and
 * deletes them (and only them) in {@link #cleanup()}.
 */
@QuarkusTest
class Repair260916LegacyBundleImportTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> EXPECTED =
        List.of("[物料.材料成本(小计)]", "[物料.回收成本(小计)]", "[产品小计(总计)]");

    @Inject ComponentImportService importService;
    @Inject ComponentExportService exportService;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private final List<UUID> dirsToClean = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        if (dirsToClean.isEmpty()) return;
        utx.begin();
        em.joinTransaction();
        for (UUID dir : dirsToClean) {
            em.createNativeQuery("DELETE FROM component_sql_view WHERE component_id IN "
                    + "(SELECT id FROM component WHERE directory_id = :dir)")
                .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component WHERE directory_id = :dir")
                .setParameter("dir", dir).executeUpdate();
            em.createNativeQuery("DELETE FROM component_directory WHERE id = :id")
                .setParameter("id", dir).executeUpdate();
        }
        utx.commit();
        dirsToClean.clear();
    }

    private static ComponentExportBundle load(String res) throws Exception {
        try (InputStream is = Repair260916LegacyBundleImportTest.class.getClassLoader().getResourceAsStream(res)) {
            assertNotNull(is, res);
            return M.readValue(is, ComponentExportBundle.class);
        }
    }

    private UUID newDirectory(String tag) throws Exception {
        UUID dir = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) "
                + "VALUES (:id, :name, 0, NOW())")
            .setParameter("id", dir)
            .setParameter("name", "RP0916-BE-" + tag + "-" + dir.toString().substring(0, 8))
            .executeUpdate();
        utx.commit();
        dirsToClean.add(dir);
        return dir;
    }

    private List<String> ex1Expressions(ImportCommitResult r) throws Exception {
        return ex1Expressions(r, "COMP-0011");
    }

    private List<String> ex1Expressions(ImportCommitResult r, String ex1CodeInBundle) throws Exception {
        String id = r.created.stream().filter(ci -> ex1CodeInBundle.equals(ci.originalCode))
            .findFirst().orElseThrow(() -> new AssertionError("ex1 (COMP-0011) not created")).componentId;
        utx.begin();
        em.joinTransaction();
        String stored;
        try {
            stored = ((Component) Component.findById(UUID.fromString(id))).excelColumns;
        } finally {
            utx.commit();
        }
        List<String> out = new ArrayList<>();
        M.readTree(stored).forEach(c -> out.add(c.path("expression").asText()));
        System.out.println("[repair-260916 B-4] ex1 excel_columns = " + stored);
        return out;
    }

    private static List<String> ex1ExpressionsInBundle(ComponentExportBundle b) {
        var ex1 = b.components.stream().filter(i -> "ex1".equals(i.name)).findFirst()
            .orElseThrow(() -> new AssertionError("ex1 missing in bundle"));
        List<String> out = new ArrayList<>();
        ex1.excelColumns.forEach(c -> out.add(c.path("expression").asText()));
        return out;
    }

    /**
     * The real 1.0 bundle cannot be imported at all on this branch nor on master: the pre-existing
     * task-260915 tree gate rejects COMP-0002「物料」(tree_ref formulas, builder view without
     * builder_config in a 1.0 bundle). This is unrelated to repair-260916 and is reported to the
     * coordinator as an AC-16① precondition conflict; here we only pin that the rejection (and its
     * legacy hint wording) is unchanged. The 1.0 text rewrite itself is covered by
     * {@code TabJoinSubtotalSuffixRewriterTest#realLegacyBundles_rewriteEx1Columns}.
     */
    @Test
    @DisplayName("AC-16 (1.0): real 1.0 bundle is still rejected by the pre-existing tree gate, hint unchanged")
    void legacyV10BundleStillRejectedByTreeGate() throws Exception {
        ComponentExportBundle legacy = load("repair-260916/bundle-v1.0.json");
        UUID dir = newDirectory("v1.0");
        var ex = assertThrows(com.cpq.common.exception.BusinessException.class,
            () -> importService.commit(dir, legacy, "RENAME", true, true));
        System.out.println("[repair-260916 B-4] v1.0 import rejected: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("而这个导入包是旧格式（bundleVersion 1.0"), ex.getMessage());
    }

    @Test
    @DisplayName("AC-16 ①②③ (1.1): legacy bundle → (小计) text; export 1.2 keeps it; re-import stable")
    void legacyBundlesRewriteAndRoundTrip() throws Exception {
        for (String ver : List.of("1.1")) {
            ComponentExportBundle legacy = load("repair-260916/bundle-v" + ver + ".json");
            assertEquals(ver, legacy.bundleVersion, "precondition");
            assertEquals(List.of("[物料.材料成本]", "[物料.回收成本]", "[产品小计(总计)]"),
                ex1ExpressionsInBundle(legacy), "precondition: legacy text in fixture");

            UUID dir = newDirectory("v" + ver);
            ImportCommitResult r = importService.commit(dir, legacy, "RENAME", true, true);
            assertEquals(legacy.components.size(), r.createdCount);
            assertEquals(EXPECTED, ex1Expressions(r), "① imported text (bundle " + ver + ")");
            // the input bundle object itself is not mutated
            assertEquals(List.of("[物料.材料成本]", "[物料.回收成本]", "[产品小计(总计)]"), ex1ExpressionsInBundle(legacy));

            ComponentExportBundle exported = exportService.exportDirectory(dir);
            System.out.println("[repair-260916 B-4] export of v" + ver + " import: bundleVersion=" + exported.bundleVersion
                + " ex1=" + ex1ExpressionsInBundle(exported));
            assertEquals("1.2", exported.bundleVersion, "② export version");
            assertEquals(EXPECTED, ex1ExpressionsInBundle(exported), "② exported text");

            // ③ JSON round-trip (as over HTTP) then re-import: text must not change again
            ComponentExportBundle reparsed = M.readValue(M.writeValueAsString(exported), ComponentExportBundle.class);
            UUID dir2 = newDirectory("re-v" + ver);
            ImportCommitResult r2 = importService.commit(dir2, reparsed, "RENAME", true, true);
            String ex1Code = reparsed.components.stream().filter(i -> "ex1".equals(i.name))
                .findFirst().orElseThrow().code;   // export carries the RENAMEd code
            assertEquals(EXPECTED, ex1Expressions(r2, ex1Code), "③ re-import text (from v" + ver + ")");
        }
    }

    @Test
    @DisplayName("1.2 bundle carrying a bare [tab.subtotalCol] is written verbatim (no rewrite)")
    void currentVersionBundleIsNotRewritten() throws Exception {
        ComponentExportBundle b = load("repair-260916/bundle-v1.1.json");
        b.bundleVersion = "1.2";   // same content, new-notation semantics: bare ref = row value
        UUID dir = newDirectory("v12-verbatim");
        ImportCommitResult r = importService.commit(dir, b, "RENAME", true, true);
        assertEquals(List.of("[物料.材料成本]", "[物料.回收成本]", "[产品小计(总计)]"), ex1Expressions(r));
    }
}
