package com.cpq.component.dto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.cpq.component.entity.Component;
import com.cpq.component.entity.ComponentSqlView;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * task-260915 B-7（AC-7）—— <b>导出契约的防复发机制</b>，纯反射不打 DB。
 *
 * <p><b>它在防什么</b>：导出端（{@code ComponentExportService}）、导入端
 * （{@code ComponentImportService}）、契约 DTO（{@link ComponentExportBundle}）各维护一份
 * <b>手工</b>字段清单。给 {@code component} / {@code component_sql_view} 表加一列时，漏改任何一处
 * 都<b>不报错、不告警、静默丢数据</b>——这个故障族已经复发过两轮（{@code rowKeyFields} 一次，
 * {@code builder_config} 等 8 个字段一次），第二轮的表现是「在源库合法保存的组件，导出再导入
 * 必然被自己的校验拒绝」，而报错文案与真实根因毫无关系。
 *
 * <p><b>断言形态</b>：实体持久化字段集 <b>减去</b> DTO 字段集 <b>减去</b> 白名单 = <b>空集</b>。
 * 🚫 <b>不许退化成「字段数量相等」</b>——加一列同时删一列就骗过去了。
 *
 * <p><b>加了新列怎么办</b>（本测试变红时的两条出路，二选一）：
 * <ol>
 *   <li>让导出包带上它：DTO 加字段 + 导出端赋值 + 导入端恢复，<b>三处都要改</b>；</li>
 *   <li>确实不该导出：加进下面的白名单，<b>并写一行注释说明为什么不导</b>。</li>
 * </ol>
 */
class ExportBundleFieldCoverageTest {

    /**
     * {@code component} 表里<b>刻意不进导出包</b>的字段。每条都必须带理由——没有理由的白名单
     * 等于把这个测试关掉。
     */
    private static final Map<String, String> COMPONENT_WHITELIST = new LinkedHashMap<>();
    static {
        // 主键：导入端一律分配全新 UUID（bundle 里另有 Item.id 只作跨组件引用重映射的线索，不回写主键）
        COMPONENT_WHITELIST.put("id", "主键，导入端分配新 UUID");
        // 目录归属：导入时由用户指定目标目录，源目录 id 在目标库里通常根本不存在
        COMPONENT_WHITELIST.put("directoryId", "目录归属，导入时由用户指定目标目录");
        // code 全局唯一：冲突策略（RENAME/SKIP/ABORT）决定最终 code，不能照搬
        COMPONENT_WHITELIST.put("code", "全局唯一编号，导入端按冲突策略重新分配");
        // 审计时间戳：由 @PrePersist/@PreUpdate 在目标库重新生成，照搬会谎报「这条是什么时候建的」
        COMPONENT_WHITELIST.put("createdAt", "审计时间戳，目标库重新生成");
        COMPONENT_WHITELIST.put("updatedAt", "审计时间戳，目标库重新生成");
    }

    /** {@code component_sql_view} 表里<b>刻意不进导出包</b>的字段，理由同上。 */
    private static final Map<String, String> SQL_VIEW_WHITELIST = new LinkedHashMap<>();
    static {
        SQL_VIEW_WHITELIST.put("id", "主键，导入端分配新 UUID");
        // 视图挂在哪个组件上，由导入端按新建出来的组件 id 重新指定
        SQL_VIEW_WHITELIST.put("componentId", "外键，导入端指向新建组件的 id");
        // 创建人是目标库的用户体系，源库的 UUID 在目标库里没有意义
        SQL_VIEW_WHITELIST.put("createdBy", "创建人 UUID，属目标库的用户体系");
        SQL_VIEW_WHITELIST.put("createdAt", "审计时间戳，目标库重新生成");
        SQL_VIEW_WHITELIST.put("updatedAt", "审计时间戳，目标库重新生成");
    }

    /**
     * 实体字段名 → DTO 字段名的<b>显式映射</b>（仅当两侧命名不一致时登记，每条必须带注释）。
     *
     * <p>当前<b>为空</b>：实测 2026-09-15，两个实体的全部可导字段与 DTO 逐字同名。
     * 🚫 将来命名不一致时必须登记在这里，不要靠「模糊匹配」——那会把真正的漏字段一起放过。
     */
    private static final Map<String, String> COMPONENT_NAME_MAP = Map.of();
    private static final Map<String, String> SQL_VIEW_NAME_MAP = Map.of();

    @Test
    @DisplayName("AC-7：component 实体的每个持久化字段，要么进导出 DTO，要么在白名单里注明理由")
    void componentEntityFieldsAreAllCoveredByExportDto() {
        assertCovered("component", Component.class, ComponentExportBundle.Item.class,
                COMPONENT_WHITELIST, COMPONENT_NAME_MAP);
    }

    @Test
    @DisplayName("AC-7：component_sql_view 实体的每个持久化字段，要么进导出 DTO，要么在白名单里注明理由")
    void componentSqlViewEntityFieldsAreAllCoveredByExportDto() {
        assertCovered("component_sql_view", ComponentSqlView.class, ComponentExportBundle.SqlView.class,
                SQL_VIEW_WHITELIST, SQL_VIEW_NAME_MAP);
    }

    /**
     * task-260915 B-9（AC-19）：<b>1.1 新增的 8 个字段必须全部带 {@code @JsonInclude(NON_NULL)}</b>。
     *
     * <p><b>为什么要用测试兜住</b>：导入端 {@code ComponentImportService#verifyChecksum} 拿
     * <b>反序列化后的 DTO 对象</b>重算 checksum（不是校验原始字节）。本 DTO 每多一个会被写成
     * {@code "xxx":null} 的字段，<b>所有老 bundle 的 checksum 当场全部失配</b> —— 用户侧的表现是
     * 每份合法老包在导入抽屉里都挂一条「bundle 可能被改动或损坏」的假警报。
     * 实测：8 个字段未加注解时 {@code Task0805ExportBindingReportTest#oldBundle_checksumStillValid}
     * 由 2/18 失败恶化为 18/18；加上注解后回到 2/18。
     *
     * <p>「记得给新字段加注解」本身就是一条<b>靠人记</b>的规矩，而本测试类存在的理由恰恰是
     * 「靠人记的字段清单必然漏」。所以这条不能只写在注释里，必须有断言。
     *
     * <p>🚫 <b>不要把既有字段加进本清单</b>：给既有字段加 {@code NON_NULL} 会改变老包的既有形状
     * （老包的 JSON 里 {@code "sortField":null} 是写出来了的），是同一个坑的镜像。
     */
    @Test
    @DisplayName("AC-19：1.1 新增的 8 个字段必须全部带 @JsonInclude(NON_NULL)，漏一个即点名")
    void newFieldsMustBeAnnotatedNonNull() {
        Set<String> missing = new TreeSet<>();
        for (String f : ITEM_FIELDS_ADDED_IN_1_1) {
            if (!hasNonNullInclude(ComponentExportBundle.Item.class, f)) missing.add("Item." + f);
        }
        for (String f : SQLVIEW_FIELDS_ADDED_IN_1_1) {
            if (!hasNonNullInclude(ComponentExportBundle.SqlView.class, f)) missing.add("SqlView." + f);
        }
        assertTrue(missing.isEmpty(),
                "以下 1.1 新增字段缺少 @JsonInclude(JsonInclude.Include.NON_NULL)：" + missing + "\n"
                + "  ⇒ 它们会被序列化成 \"xxx\":null，而导入端 verifyChecksum 拿反序列化后的 DTO 重算 checksum，\n"
                + "    多出来的键会让**所有老 bundle 的 checksum 全部失配**（用户侧 = 每份合法老包都挂\n"
                + "    「bundle 可能被改动或损坏」的假警报）。\n"
                + "  ⇒ 修法：给上列字段补上 @JsonInclude(JsonInclude.Include.NON_NULL)。\n"
                + "  🚫 不要反过来给**既有**字段加 —— 那会改变老包的既有形状，是同一个坑的镜像。");
    }

    /** 1.1（task-260915）给 {@code Item} 新增的字段。新增字段进 1.2 时请一并登记到这里。 */
    private static final List<String> ITEM_FIELDS_ADDED_IN_1_1 = List.of(
            "treeConfig", "bomRecursiveExpand",
            "elementCodeField", "elementPriceField", "elementCurrencyField");

    /** 1.1（task-260915）给 {@code SqlView} 新增的字段。 */
    private static final List<String> SQLVIEW_FIELDS_ADDED_IN_1_1 = List.of(
            "builderConfig", "builderVersion", "status");

    /** 本测试自身的守门：上面两张「1.1 新增字段」清单里的名字，必须在 DTO 上真实存在。 */
    @Test
    @DisplayName("AC-19：1.1 新增字段清单里的名字必须在 DTO 上真实存在（防清单随改名而失效）")
    void annotatedFieldListEntriesMustExistOnDto() {
        Set<String> itemFields = dtoFieldNames(ComponentExportBundle.Item.class);
        for (String f : ITEM_FIELDS_ADDED_IN_1_1) {
            assertTrue(itemFields.contains(f),
                    "Item." + f + " 在 DTO 上已不存在 —— 字段被改名或删除，NON_NULL 守门会静默失效");
        }
        Set<String> viewFields = dtoFieldNames(ComponentExportBundle.SqlView.class);
        for (String f : SQLVIEW_FIELDS_ADDED_IN_1_1) {
            assertTrue(viewFields.contains(f),
                    "SqlView." + f + " 在 DTO 上已不存在 —— 字段被改名或删除，NON_NULL 守门会静默失效");
        }
    }

    private static boolean hasNonNullInclude(Class<?> dto, String fieldName) {
        try {
            JsonInclude ann = dto.getDeclaredField(fieldName).getAnnotation(JsonInclude.class);
            return ann != null && ann.value() == JsonInclude.Include.NON_NULL;
        } catch (NoSuchFieldException e) {
            return false;   // 字段不存在由 annotatedFieldListEntriesMustExistOnDto 单独点名
        }
    }

    /** 本测试自身的守门：白名单里不许出现实体上根本没有的字段（改名后白名单会悄悄失效）。 */
    @Test
    @DisplayName("AC-7：白名单条目必须都是实体上真实存在的字段（防白名单随字段改名而失效）")
    void whitelistEntriesMustExistOnEntities() {
        Set<String> compFields = persistentFieldNames(Component.class);
        for (String w : COMPONENT_WHITELIST.keySet()) {
            assertTrue(compFields.contains(w),
                    "白名单条目 component." + w + " 在实体上已不存在——字段被改名或删除了，"
                    + "请同步更新 ExportBundleFieldCoverageTest 的白名单，否则它会静默放过真正的新字段");
        }
        Set<String> viewFields = persistentFieldNames(ComponentSqlView.class);
        for (String w : SQL_VIEW_WHITELIST.keySet()) {
            assertTrue(viewFields.contains(w),
                    "白名单条目 component_sql_view." + w + " 在实体上已不存在——字段被改名或删除了，"
                    + "请同步更新 ExportBundleFieldCoverageTest 的白名单");
        }
    }

    // =========================================================================

    private void assertCovered(String tableName, Class<?> entity, Class<?> dto,
                                Map<String, String> whitelist, Map<String, String> nameMap) {
        Set<String> entityFields = persistentFieldNames(entity);
        Set<String> dtoFields = dtoFieldNames(dto);

        Set<String> missing = new TreeSet<>();
        for (String f : entityFields) {
            if (whitelist.containsKey(f)) continue;
            String mapped = nameMap.getOrDefault(f, f);
            if (!dtoFields.contains(mapped)) missing.add(f);
        }

        assertTrue(missing.isEmpty(),
                "导出包漏了 " + tableName + " 表的 " + missing.size() + " 个字段：" + missing + "\n"
                + "  这些字段在实体 " + entity.getSimpleName() + " 上是持久化列，但 "
                + dto.getName() + " 里没有对应字段。\n"
                + "  ⇒ 导出→导入这条路会**静默丢掉**它们的值（不报错、不告警）。\n"
                + "  两条出路二选一：\n"
                + "    ① 带上它：" + dto.getSimpleName() + " 加字段 + ComponentExportService 赋值 "
                + "+ ComponentImportService 恢复（三处都要改），并把 bundleVersion 升一版；\n"
                + "    ② 确实不该导出：加入本测试的白名单，**并注明理由**。\n"
                + "  实体字段全集=" + entityFields + "\n"
                + "  DTO 字段全集=" + dtoFields);
    }

    /**
     * 实体的<b>持久化</b>字段名集合。排除：
     * <ul>
     *   <li>{@code static}（常量，不是列）；</li>
     *   <li>Java {@code transient} 关键字 与 JPA {@code @Transient}（后者如
     *       {@code ComponentSqlView#axisScope}——没有物理列，值另有出处）；</li>
     *   <li>合成字段（{@code $jacocoData} / {@code this$0} 等，由字节码增强插入）。</li>
     * </ul>
     * 只取 {@code getDeclaredFields()}（不含父类）—— Panache 基类
     * {@code PanacheEntityBase} 不声明任何实例字段，本项目实体的列全部声明在自身。
     */
    private static Set<String> persistentFieldNames(Class<?> entity) {
        Set<String> out = new LinkedHashSet<>();
        for (Field f : entity.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (Modifier.isTransient(f.getModifiers())) continue;
            if (f.isSynthetic() || f.getName().startsWith("$") || f.getName().contains("$")) continue;
            if (f.isAnnotationPresent(jakarta.persistence.Transient.class)) continue;
            out.add(f.getName());
        }
        return out;
    }

    /** DTO 的公开实例字段名集合（Jackson 默认按 public 字段序列化，无 getter）。 */
    private static Set<String> dtoFieldNames(Class<?> dto) {
        Set<String> out = new LinkedHashSet<>();
        for (Field f : dto.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (f.isSynthetic() || f.getName().contains("$")) continue;
            out.add(f.getName());
        }
        return out;
    }
}
