package com.cpq.component.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.dto.ImportCommitResult;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * task-260915 B-12（AC-7 的补强）—— <b>双向</b>「赋值覆盖」守门。
 *
 * <p><b>它补的是 {@code ExportBundleFieldCoverageTest} 的盲区</b>：那份纯反射测试只能看到
 * 「DTO 上有没有这个字段」，看不到<b>有没有人给它赋值</b>。所以下面两种漏法它一个都拦不住，
 * 而两种都是静默的：
 * <ul>
 *   <li><b>导出端漏</b>：DTO 加了字段但 {@code ComponentExportService} 忘了
 *       {@code item.xxx = c.xxx} ⇒ 导出恒 null。而且 1.1 起新字段带
 *       {@code @JsonInclude(NON_NULL)}，null 连<b>键都不出现</b>，比以前更难察觉；</li>
 *   <li><b>导入端漏</b>：DTO 有字段但 {@code ComponentImportService} 忘了
 *       {@code c.xxx = it.xxx} ⇒ 导进去的组件少一列配置，报错要等到很久以后。</li>
 * </ul>
 *
 * <p><b>手法</b>：反射造一个<b>所有持久化字段都非 null</b> 的组件（+ 其 SQL 视图）落库 →
 * 导出 → 断言 DTO 里没有 null 字段 → 再把这份 bundle 导入另一个目录 →
 * 断言落库实体没有字段丢失。三段任一环节漏赋值都会点名。
 *
 * <p>🚫 <b>不许把失败简单地加白名单了事</b>：白名单口径与
 * {@code ExportBundleFieldCoverageTest} 共用同一套理由，加之前先想清楚「这个字段真的不该往返吗」。
 */
@QuarkusTest
class Task260915AssignmentCoverageTest {

    @Inject ComponentExportService exportService;
    @Inject ComponentImportService importService;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private final List<UUID> dirsToClean = new ArrayList<>();

    /**
     * 不参与本守门的字段，理由与 {@code ExportBundleFieldCoverageTest} 的白名单同源
     * （主键 / 目录归属 / code 冲突策略 / 审计时间戳 / 创建人）。
     */
    private static final Set<String> COMPONENT_SKIP = Set.of(
            "id", "directoryId", "code", "createdAt", "updatedAt");
    private static final Set<String> SQLVIEW_SKIP = Set.of(
            "id", "componentId", "createdBy", "createdAt", "updatedAt");

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

    @Test
    @DisplayName("AC-7/B-12：全字段非空的组件导出后，DTO 里不得有 null 字段（导出端漏赋值即点名）")
    void exportMustAssignEveryField() throws Exception {
        UUID dir = newDirectory("exp");
        String code = persistFullyPopulatedComponent(dir);

        ComponentExportBundle bundle = exportService.exportDirectory(dir);
        ComponentExportBundle.Item item = bundle.components.stream()
                .filter(i -> code.equals(i.code)).findFirst()
                .orElseThrow(() -> new AssertionError("导出结果里找不到夹具组件 " + code));

        Set<String> nullItemFields = nullFieldNames(item, Set.of("sqlViews"));
        Set<String> nullViewFields = item.sqlViews.isEmpty()
                ? new TreeSet<>(Set.of("<该组件一条视图都没导出来>"))
                : nullFieldNames(item.sqlViews.get(0), Set.of());

        System.out.println("[B-12 导出] item 为 null 的字段=" + nullItemFields
                + " ; sqlView 为 null 的字段=" + nullViewFields);

        assertTrue(nullItemFields.isEmpty(), failMsg("导出端", "ComponentExportService",
                "ComponentExportBundle.Item", nullItemFields,
                "item.<字段> = c.<对应列>"));
        assertTrue(nullViewFields.isEmpty(), failMsg("导出端", "ComponentExportService",
                "ComponentExportBundle.SqlView", nullViewFields,
                "sv.<字段> = v.<对应列>"));
    }

    @Test
    @DisplayName("AC-7/B-12：全字段非空的包导入后，落库实体不得有字段丢失（导入端漏赋值即点名）")
    void importMustRestoreEveryField() throws Exception {
        UUID srcDir = newDirectory("imp-src");
        persistFullyPopulatedComponent(srcDir);
        ComponentExportBundle bundle = exportService.exportDirectory(srcDir);

        UUID dstDir = newDirectory("imp-dst");
        ImportCommitResult r = importService.commit(dstDir, bundle, "RENAME", true, true);
        assertEquals(1, r.createdCount);
        UUID newId = UUID.fromString(r.created.get(0).componentId);

        utx.begin();
        em.joinTransaction();
        Component c;
        ComponentSqlView v;
        try {
            c = Component.findById(newId);
            v = ComponentSqlView.find("componentId", newId).firstResult();
        } finally {
            utx.commit();
        }

        Set<String> lostOnComponent = nullFieldNames(c, COMPONENT_SKIP);
        Set<String> lostOnView = (v == null)
                ? new TreeSet<>(Set.of("<导入后一条视图都没建出来>"))
                : nullFieldNames(v, SQLVIEW_SKIP);

        System.out.println("[B-12 导入] component 丢失字段=" + lostOnComponent
                + " ; component_sql_view 丢失字段=" + lostOnView);

        assertTrue(lostOnComponent.isEmpty(), failMsg("导入端", "ComponentImportService",
                "Component 实体", lostOnComponent, "c.<字段> = it.<DTO 对应字段>"));
        assertTrue(lostOnView.isEmpty(), failMsg("导入端", "ComponentImportService",
                "ComponentSqlView 实体", lostOnView, "v.<字段> = sv.<DTO 对应字段>"));
    }

    // =========================================================================

    private static String failMsg(String side, String svc, String target,
                                   Set<String> fields, String pattern) {
        return side + "漏了 " + fields.size() + " 个字段的赋值：" + fields + "\n"
             + "  夹具里这些字段**都填了非 null 值**，结果在 " + target + " 上却是 null\n"
             + "  ⇒ " + svc + " 里缺 `" + pattern + "` 这一行（静默丢数据，不报错不告警）。\n"
             + "  🚨 加字段是**三处都要改**：ComponentExportBundle（DTO） / ComponentExportService（导出赋值）\n"
             + "     / ComponentImportService（导入恢复）—— 反射契约测试只管第一处，本测试管后两处。\n"
             + "  若该字段确实不该往返：加进本测试的 SKIP 集合与 ExportBundleFieldCoverageTest 的白名单，\n"
             + "     **两处都要加，并各写一行理由**。";
    }

    /** 对象上值为 null 的（非 static、非合成）字段名。 */
    private static Set<String> nullFieldNames(Object o, Set<String> skip) {
        Set<String> out = new TreeSet<>();
        for (Field f : o.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (Modifier.isTransient(f.getModifiers())) continue;
            if (f.isSynthetic() || f.getName().contains("$")) continue;
            if (f.isAnnotationPresent(jakarta.persistence.Transient.class)) continue;
            if (skip.contains(f.getName())) continue;
            try {
                f.setAccessible(true);
                if (f.get(o) == null) out.add(f.getName());
            } catch (IllegalAccessException e) {
                throw new AssertionError("反射读取字段失败: " + f.getName(), e);
            }
        }
        return out;
    }

    /**
     * 造一个<b>所有持久化字段都非 null</b> 的组件 + 一条视图，并<b>当场反射核验</b>确实一个 null 都没有
     * —— 否则夹具本身就是空验证（字段是 null 的话，导出端漏不漏赋值都测不出来）。
     */
    private String persistFullyPopulatedComponent(UUID dir) throws Exception {
        String code = "T260915-FULL-" + UUID.randomUUID().toString().substring(0, 8);
        utx.begin();
        em.joinTransaction();
        try {
            Component c = new Component();
            c.code = code;
            c.name = "全字段非空夹具";
            c.componentType = "EXCEL";
            c.columnCount = 3;
            c.status = "ACTIVE";
            c.dataDriverPath = "$builder_full_probe";
            c.fields = "[{\"name\":\"料号\",\"field_type\":\"INPUT_TEXT\"}]";
            c.formulas = "[{\"name\":\"f1\",\"expression\":[{\"type\":\"number\",\"value\":\"1\"}]}]";
            c.excelColumns = "[{\"col_key\":\"col_1\",\"title\":\"列1\",\"source_type\":\"VARIABLE\"}]";
            c.rowKeyFields = "[\"料号\"]";
            c.treeConfig = "{\"idField\":\"料号\",\"parentField\":\"父料号\",\"defaultExpanded\":true}";
            c.bomRecursiveExpand = Boolean.TRUE;
            c.tabType = "主件";
            c.partNoField = "料号";
            c.partNameField = "品名";
            c.sortField = "项次";
            c.elementCodeField = "元素编号";
            c.elementPriceField = "元素单价";
            c.elementCurrencyField = "币种";
            c.directoryId = dir;
            c.persist();

            ComponentSqlView v = new ComponentSqlView();
            v.componentId = c.id;
            v.sqlViewName = "builder_full_probe";
            v.sqlTemplate = "SELECT 1";
            v.declaredColumns = "[{\"name\":\"料号\",\"dataType\":\"text\"}]";
            v.requiredVariables = new String[]{"customerId"};
            v.scope = "COMPONENT";
            v.status = "INACTIVE";           // 故意非默认值：能抓出「导入端硬编码 ACTIVE」这类漏法
            v.description = "全字段非空夹具视图";
            v.builderConfig = "{\"tabType\":\"主件\",\"variantKey\":\"\",\"dialect\":\"QUOTE\"}";
            v.builderVersion = 1;
            v.persist();

            // 夹具自检：这两个对象上不许有 null，否则本测试是空验证
            Set<String> cNulls = nullFieldNames(c, new LinkedHashSet<>(Set.of(
                    "id", "directoryId", "createdAt", "updatedAt")));
            Set<String> vNulls = nullFieldNames(v, new LinkedHashSet<>(Set.of(
                    "id", "componentId", "createdBy", "createdAt", "updatedAt")));
            assertTrue(cNulls.isEmpty(), "夹具没填满 Component 的字段（本测试会变成空验证）：" + cNulls
                    + " —— 实体新增了列，请在 persistFullyPopulatedComponent 里给它填一个非 null 值");
            assertTrue(vNulls.isEmpty(), "夹具没填满 ComponentSqlView 的字段（本测试会变成空验证）：" + vNulls
                    + " —— 实体新增了列，请在 persistFullyPopulatedComponent 里给它填一个非 null 值");
        } finally {
            utx.commit();
        }
        return code;
    }

    private UUID newDirectory(String tag) throws Exception {
        UUID dir = UUID.randomUUID();
        utx.begin();
        em.joinTransaction();
        em.createNativeQuery("INSERT INTO component_directory(id, name, sort_order, created_at) "
                + "VALUES (:id, :name, 0, NOW())")
                .setParameter("id", dir)
                .setParameter("name", "T260915-FULL-" + tag + "-" + dir.toString().substring(0, 8))
                .executeUpdate();
        utx.commit();
        dirsToClean.add(dir);
        return dir;
    }
}
