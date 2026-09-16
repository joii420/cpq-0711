package com.cpq.task260915;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.cpq.component.dto.ComponentExportBundle;
import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260915 · 分片 S-B · <b>AC-7</b>：反射契约测试拦得住新增列。
 *
 * <h3>本类是什么，不是什么</h3>
 * AC-7 验的是 <b>B-7 交付的那个防御机制</b>（实体新增列未在导出 DTO 登记 ⇒ 测试变红）。
 * 一个防御机制最典型的失效形态是「<b>它其实从来不会触发</b>」——首次看到绿证明不了它接上了
 * （{@code CLAUDE.md}：自己写的验证脚本首次 PASS 也可能是空验证）。所以 AC-7 的验收由<b>两半</b>组成：
 *
 * <ol>
 *   <li><b>还原实验</b>（本类做不到，在类外）：给 {@link Component} 临时加一个持久化字段 {@code foo}、
 *       不改 {@code ComponentExportBundle.Item} → 跑 B-7 的测试 → 必须<b>变红且点名 foo</b>；删掉 → 必须<b>恢复绿</b>。
 *       脚本：{@code dev-docs/task-260915-组件导出导入往返保真/实验/ac7-还原实验.sh}。</li>
 *   <li><b>本类</b>：一份<b>独立复算</b>的同义契约断言 + 8 个新字段的<b>定点断言</b>。
 *       它堵的是还原实验堵不住的那个缺口 —— B-7 的白名单如果写宽了（比如把
 *       {@code builderConfig} 一并加进白名单并注明「暂不导出」），B-7 自己永远绿、还原实验也照样红/绿正常，
 *       只有本类的定点断言会红。</li>
 * </ol>
 *
 * <p>手法先例：{@code PrecisionScaleConsistencyTest}（反射比对 + 定点回归双保险）。
 * <p>本类<b>纯反射、零 DB、零 HTTP</b>，不需要 {@code @QuarkusTest}，因此也不受共享库状态影响。
 */
@DisplayName("task-260915 S-B · AC-7 导出契约反射防线（独立复算 + 定点）")
class Ac7ExportContractGuardTest {

    /**
     * {@code component} 表不参与导出的列，<b>每条必须带理由</b>（需求文档 §③ AC-6 白名单原文）：
     * <ul>
     *   <li>{@code id} —— 主键，导入端重新分配（bundle 里的 {@code Item.id} 是"原 id"，供跨组件引用重映射，
     *       不是要还原的值）</li>
     *   <li>{@code directoryId} —— 目标目录由导入时用户指定，目录属性明确不在保真范围（需求文档 §② 不做清单）</li>
     *   <li>{@code code} —— 冲突策略 RENAME 下允许不同（AC-18 只要求 code 变、其余字段不变）</li>
     *   <li>{@code createdAt} / {@code updatedAt} —— 审计时间戳，导入即"新建"，按目标库当前时间落</li>
     * </ul>
     */
    private static final Set<String> COMPONENT_WHITELIST =
            Set.of("id", "directoryId", "code", "createdAt", "updatedAt");

    /**
     * {@code component_sql_view} 表不参与导出的列，每条带理由：
     * <ul>
     *   <li>{@code id} —— 主键，导入端重新分配</li>
     *   <li>{@code componentId} —— 外键，导入端指向新建的组件</li>
     *   <li>{@code createdBy} —— 操作人，导入时是导入者本人，不是源库那个人</li>
     *   <li>{@code createdAt} / {@code updatedAt} —— 同上，审计时间戳</li>
     * </ul>
     */
    private static final Set<String> SQL_VIEW_WHITELIST =
            Set.of("id", "componentId", "createdBy", "createdAt", "updatedAt");

    /** 本任务补齐的 8 个字段（需求文档 §② 丢失字段清单）—— 定点钉死，防"白名单写宽"。 */
    private static final List<String> ITEM_MUST_HAVE = List.of(
            "treeConfig", "bomRecursiveExpand",
            "elementCodeField", "elementPriceField", "elementCurrencyField");

    private static final List<String> SQLVIEW_MUST_HAVE = List.of(
            "builderConfig", "builderVersion", "status");

    // ══════════════════════════════════════════════════════════════════════
    // T1 / T2：实体字段集 ⊆ 导出 DTO 字段集（独立复算 B-7 的同一条契约）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-7-a: Component 实体的持久化字段（除白名单）全部在 ComponentExportBundle.Item 里登记")
    void componentEntityFieldsAllRegisteredInExportDto() {
        Set<String> entityFields = persistentFieldNames(Component.class);
        // 防呆：反射收集本身若失效（比如以后换了 Panache 写法、字段变 private），
        // 差集天然为空 ⇒ 本测试恒绿。先钉住一个下界。实查 2026-09-15：component 表 23 列，实体 23 个字段。
        assertTrue(entityFields.size() >= 23,
                "只反射到 " + entityFields.size() + " 个 Component 持久化字段（预期 ≥23）——"
                        + "反射收集逻辑本身可能失效了，先排查它，不要看下面的差集结论: " + entityFields);

        Set<String> dtoFields = fieldNames(ComponentExportBundle.Item.class);
        Set<String> missing = new TreeSet<>(entityFields);
        missing.removeAll(COMPONENT_WHITELIST);
        missing.removeAll(dtoFields);

        assertTrue(missing.isEmpty(),
                "以下 component 列没有在导出包里登记，导出→导入会静默丢数据: " + missing
                        + "\n → 要么在 ComponentExportBundle.Item 带上它（导出端/导入端同时赋值），"
                        + "要么加入 COMPONENT_WHITELIST 并注明理由。"
                        + "\n（本缺陷族已复发两次：rowKeyFields 是上一轮事后补的，builder_config 是这一轮）");
    }

    @Test
    @DisplayName("AC-7-b: ComponentSqlView 实体的持久化字段（除白名单）全部在 ComponentExportBundle.SqlView 里登记")
    void sqlViewEntityFieldsAllRegisteredInExportDto() {
        Set<String> entityFields = persistentFieldNames(ComponentSqlView.class);
        // 实查 2026-09-15：component_sql_view 表 14 列；实体另有 1 个 @Transient(axisScope) 不算。
        assertTrue(entityFields.size() >= 14,
                "只反射到 " + entityFields.size() + " 个 ComponentSqlView 持久化字段（预期 ≥14）——"
                        + "反射收集逻辑本身可能失效了: " + entityFields);
        assertFalse(entityFields.contains("axisScope"),
                "axisScope 是 @Transient（没有物理列），不该出现在持久化字段集里 ——"
                        + "@Transient 过滤逻辑失效了，其余结论都不可信");

        Set<String> dtoFields = fieldNames(ComponentExportBundle.SqlView.class);
        Set<String> missing = new TreeSet<>(entityFields);
        missing.removeAll(SQL_VIEW_WHITELIST);
        missing.removeAll(dtoFields);

        assertTrue(missing.isEmpty(),
                "以下 component_sql_view 列没有在导出包里登记: " + missing
                        + "\n → builder_config / builder_version 缺一，导入后取数配置器打开就是空的，"
                        + "且树身份丢失 ⇒ tree_ref 公式被自己的校验 400 拒掉（本任务的起因报错）");
    }

    // ══════════════════════════════════════════════════════════════════════
    // T3：8 个新字段定点断言 —— 堵「白名单写宽 ⇒ B-7 恒绿」
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-7-c: 本任务补齐的 8 个字段逐个在导出 DTO 里按名登记（白名单写宽也躲不过）")
    void eightRecoveredFieldsAreRegisteredByName() {
        Set<String> item = fieldNames(ComponentExportBundle.Item.class);
        Set<String> view = fieldNames(ComponentExportBundle.SqlView.class);

        List<String> absent = new ArrayList<>();
        for (String f : ITEM_MUST_HAVE) {
            if (!item.contains(f)) {
                absent.add("ComponentExportBundle.Item#" + f);
            }
        }
        for (String f : SQLVIEW_MUST_HAVE) {
            if (!view.contains(f)) {
                absent.add("ComponentExportBundle.SqlView#" + f);
            }
        }

        assertTrue(absent.isEmpty(),
                "需求文档 §② 点名补齐的 8 个字段缺了: " + absent
                        + "\n → 这 8 个是本任务的交付本体，任何情况下都不许进白名单");
    }

    // ══════════════════════════════════════════════════════════════════════
    // T3b（AC-19 防复发守门）：8 个新字段必须带 @JsonInclude(NON_NULL)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * AC-19 的守门条款：8 个新字段<b>全部带</b> {@code @JsonInclude(NON_NULL)}，漏一个即失败并点名该字段。
     *
     * <p>为什么这是一条<b>契约</b>而不是实现细节：导入端 {@code verifyChecksum} 拿反序列化后的 DTO 重算
     * checksum。只要有一个新字段在老包上被序列化成 {@code "xxx":null}，重算出的字节就与老包原始 checksum
     * 不同 ⇒ <b>每一份合法老包都会被判「可能被改动或损坏」</b>（实测 {@code Task0805ExportBindingReportTest}
     * 由 23/2 恶化到 23/18）。
     *
     * <p>字段级注解缺失时回落到类级注解 —— 两种写法都满足契约，但<b>必须有一种</b>。
     */
    @Test
    @DisplayName("AC-19-d（守门）: 8 个新字段全部带 @JsonInclude(NON_NULL)（漏一个即点名）")
    void eightRecoveredFieldsCarryJsonIncludeNonNull() {
        List<String> offenders = new ArrayList<>();
        for (String f : ITEM_MUST_HAVE) {
            checkNonNullInclude(ComponentExportBundle.Item.class, f, offenders);
        }
        for (String f : SQLVIEW_MUST_HAVE) {
            checkNonNullInclude(ComponentExportBundle.SqlView.class, f, offenders);
        }
        assertTrue(offenders.isEmpty(),
                "AC-19：以下新字段没有 @JsonInclude(NON_NULL)（字段级和类级都没有）: " + offenders
                        + "\n → 后果不是「多一个 null 键」这么轻："
                        + "老包反序列化后这些字段是 null，重新序列化就会多出 \"xxx\":null，"
                        + "导入端重算 checksum 必然失配 ⇒ 每一份合法老包都被判「可能被改动或损坏」。"
                        + "\n → 要么给字段加 @JsonInclude(JsonInclude.Include.NON_NULL)，"
                        + "要么在 Item/SqlView 类上加同款类级注解。");
    }

    private static void checkNonNullInclude(Class<?> dto, String fieldName, List<String> offenders) {
        Field f;
        try {
            f = dto.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            offenders.add(dto.getSimpleName() + "#" + fieldName + "（字段不存在）");
            return;
        }
        JsonInclude ann = f.getAnnotation(JsonInclude.class);
        if (ann == null) {
            ann = dto.getAnnotation(JsonInclude.class);   // 回落：类级注解同样满足契约
        }
        if (ann == null) {
            offenders.add(dto.getSimpleName() + "#" + fieldName + "（无 @JsonInclude）");
        } else if (ann.value() != JsonInclude.Include.NON_NULL
                && ann.value() != JsonInclude.Include.NON_ABSENT) {
            offenders.add(dto.getSimpleName() + "#" + fieldName + "（@JsonInclude=" + ann.value() + "，需 NON_NULL）");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // T4：B-7 的防线文件确实存在（还原实验的前提）
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-7-d: 工程里确实存在一个针对导出 DTO 的反射契约测试（B-7 交付物，还原实验的靶子）")
    void reflectionContractTestFileExists() throws IOException {
        Path testRoot = testSourceRoot();
        List<String> hits = new ArrayList<>();
        List<Path> javaFiles;
        try (Stream<Path> s = Files.walk(testRoot)) {
            javaFiles = s.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    // 排除本任务的验收用例包 com/cpq/task260915/ —— 那里住的是本次任务的 AC 用例
                    // （跑完就归档），B-7 交付的是一条**长期**防线，应当与被它保护的 DTO 同域。
                    // 不排除的话，别的分片的往返用例只要同时提到 ComponentExportBundle 和反射，
                    // 就会把本断言变成恒绿。
                    .filter(f -> !f.toString().replace('\\', '/').contains("/com/cpq/task260915/"))
                    .toList();
        }
        for (Path p : javaFiles) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            boolean touchesBundle = src.contains("ComponentExportBundle");
            boolean reflects = src.contains("getDeclaredFields") || src.contains("reflect.Field");
            if (touchesBundle && reflects) {
                hits.add(testRoot.relativize(p).toString());
            }
        }
        System.out.println("[AC-7-d] 发现的反射契约测试文件: " + hits);
        assertFalse(hits.isEmpty(),
                "没找到任何「同时引用 ComponentExportBundle + 用反射取字段」的测试文件 —— "
                        + "B-7（反射契约测试）是本任务的核心交付物，缺它 AC-7 的还原实验没有靶子。"
                        + "\n若 B-7 换了实现手法（比如用 Jackson introspection 而不是 java.lang.reflect），"
                        + "把本断言的识别条件一并更新，不要直接删掉。");
    }

    // ══════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════

    /** 实体的持久化字段名：public 非 static、非 java transient、无 {@code @Transient}、非 synthetic。 */
    private static Set<String> persistentFieldNames(Class<?> entity) {
        Set<String> out = new LinkedHashSet<>();
        for (Field f : entity.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            if (f.isAnnotationPresent(jakarta.persistence.Transient.class)) {
                continue;
            }
            out.add(f.getName());
        }
        return out;
    }

    /** DTO 的字段名（public 非 static）。 */
    private static Set<String> fieldNames(Class<?> dto) {
        Set<String> out = new LinkedHashSet<>();
        for (Field f : dto.getDeclaredFields()) {
            if (f.isSynthetic() || Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            out.add(f.getName());
        }
        return out;
    }

    /** {@code cpq-backend/src/test/java} 绝对路径（surefire 的 user.dir = cpq-backend/）。 */
    private static Path testSourceRoot() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && cur != null; i++, cur = cur.getParent()) {
            Path p = cur.resolve("src").resolve("test").resolve("java");
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException("定位不到 src/test/java（user.dir=" + Path.of("").toAbsolutePath() + "）");
    }
}
