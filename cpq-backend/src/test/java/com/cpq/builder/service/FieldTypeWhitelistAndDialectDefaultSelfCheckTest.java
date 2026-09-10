package com.cpq.builder.service;

import com.cpq.builder.compiler.BuilderConfig;
import com.cpq.builder.compiler.CompileDialect;
import com.cpq.builder.compiler.CompileResult;
import com.cpq.builder.dto.BuilderDTOs.SaveRequest;
import com.cpq.builder.exception.BuilderApiException;
import com.cpq.component.dto.CreateComponentRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260909 B-3 开发自测：{@code fieldType} 白名单（B-1 / AC-8 / AC-9）
 * + 默认值按数据集方言（B-2 / AC-3 / AC-4 / AC-5）。
 *
 * <p>⚠️ <b>纯 JUnit 零查库</b>（{@code application-test.properties} 的默认库就是<b>共享开发库</b>
 * {@code cpq_db_0724}，见 {@code CLAUDE.md} §1 profile 表）。本类不建任何持久化夹具、不写一行数据，
 * 全部靠手工装配的 {@code CompileResult} 打进 {@code buildComponentUpdateRequest}。
 * 开发自测不是正式验收用例 —— AC 的落库/渲染取证在 {@code test.md} 那一侧。
 *
 * <p>🔬 <b>本类是「接线守卫」，不只是「判据守卫」</b>：{@link #costingDialectsDefaultToBasicData()}
 * 与 {@link #quoteDialectKeepsInputTypesAndNestedBinding()} 走的是真实的
 * {@code buildComponentUpdateRequest}，所以把调用点的 {@code defaultFieldType(dialect, …)}
 * 改回「恒按数据类型推」时它们<b>必红</b>；只测 {@link BuilderService#defaultFieldType} 这个纯函数
 * 是测不出接线被删的（B-48 / B-52 的教训，见 {@code BuilderService#compileForPreview} 的注释）。
 */
class FieldTypeWhitelistAndDialectDefaultSelfCheckTest {

    // ---------------- 装配helper：一列 = 一个已被编译器回填过的 ColumnConfig ----------------

    private static BuilderConfig.ColumnConfig col(String fieldName, String viewColumn,
                                                  String dataType, String explicitFieldType) {
        BuilderConfig.ColumnConfig c = new BuilderConfig.ColumnConfig("ANCHOR", "db_" + fieldName, fieldName);
        c.viewColumn = viewColumn;
        c.resolvedDataType = dataType;
        c.fieldType = explicitFieldType;
        return c;
    }

    private static SaveRequest req(String dialect, BuilderConfig.ColumnConfig... cols) {
        SaveRequest r = new SaveRequest();
        r.dialect = dialect;
        r.tabType = "主件";
        r.columns = new java.util.ArrayList<>(List.of(cols));
        return r;
    }

    /**
     * 调真实的 {@code buildComponentUpdateRequest}（private，反射进入）。
     *
     * <p>可以 {@code new BuilderService()} 而不注入任何依赖，是因为本方法只在
     * <b>存在 {@code FUNC_ELEMENT_PRICE} 列</b>时才会走到 {@code resolveElementCodeSource}
     * （那里才用 {@code loader}）—— 本类的夹具一列都不带价格策略，那条分支进不去。
     */
    private static CreateComponentRequest build(SaveRequest r, String viewName) {
        CompileResult cr = new CompileResult();
        cr.effectiveColumns = new java.util.ArrayList<>(r.columns);
        try {
            Method m = BuilderService.class.getDeclaredMethod("buildComponentUpdateRequest",
                    com.cpq.component.entity.Component.class, SaveRequest.class,
                    CompileResult.class, String.class);
            m.setAccessible(true);
            return (CreateComponentRequest) m.invoke(new BuilderService(),
                    new com.cpq.component.entity.Component(), r, cr, viewName);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldNamed(CreateComponentRequest req, String name) {
        return req.fields.stream().filter(f -> name.equals(f.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("字段未产出: " + name));
    }

    private static void assertValidFieldTypes(BuilderConfig cfg) throws Throwable {
        try {
            Method m = BuilderService.class.getDeclaredMethod("assertValidFieldTypes", BuilderConfig.class);
            m.setAccessible(true);
            m.invoke(null, cfg);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- B-1 / AC-8：白名单 ----------------

    @Test
    void threeLegalValuesPass() {
        for (String legal : List.of("BASIC_DATA", "INPUT_TEXT", "INPUT_NUMBER")) {
            assertDoesNotThrow(() -> assertValidFieldTypes(req("QUOTE", col("甲", "_甲", "TEXT", legal))),
                    "合法值应放行: " + legal);
        }
        assertEquals(List.of("BASIC_DATA", "INPUT_TEXT", "INPUT_NUMBER"),
                BuilderService.ALLOWED_FIELD_TYPES, "值域恰好 3 个（D-4，api.md §1.2）");
    }

    /**
     * 🔑 {@code FORMULA} / {@code FIXED_VALUE} / {@code LIST_FORMULA} <b>都在下游
     * {@code ComponentService.VALID_FIELD_TYPES}（6 个）里</b> —— 没有本层白名单它们会一路存进库、
     * <b>全程不报错</b>，到渲染期才空值。这三个是本条最该守的，不是那个显眼的垃圾串。
     */
    @Test
    void illegalValuesRejectedWith400AndMessageListsThreeLegalValues() {
        for (String bad : List.of("FORMULA", "DATA_SOURCE", "FIXED_VALUE", "LIST_FORMULA", "XXX",
                "basic_data", "INPUT", "  ")) {
            if ("  ".equals(bad)) continue; // 空白按"未传"处理，见下一条用例
            BuilderApiException ex = assertThrows(BuilderApiException.class,
                    () -> assertValidFieldTypes(req("COST_BASIC", col("甲", "_甲", "TEXT", bad))),
                    "非法值应 400: " + bad);
            assertEquals(400, ex.getCode(), "必须是 400 不是 500: " + bad);
            assertTrue(ex.getMessage().contains(bad), "消息应带回收到的值: " + ex.getMessage());
            for (String legal : BuilderService.ALLOWED_FIELD_TYPES) {
                assertTrue(ex.getMessage().contains(legal),
                        "消息必须列出 3 个合法值，缺 " + legal + ": " + ex.getMessage());
            }
        }
    }

    /** AC-9 向后兼容门禁：旧客户端不发 fieldType（null），以及空串，都不许报错。 */
    @Test
    void absentOrBlankFieldTypeIsAccepted() {
        assertDoesNotThrow(() -> assertValidFieldTypes(req("COST_BASIC", col("甲", "_甲", "TEXT", null))));
        assertDoesNotThrow(() -> assertValidFieldTypes(req("COST_BASIC", col("甲", "_甲", "TEXT", ""))));
        assertDoesNotThrow(() -> assertValidFieldTypes(req("COST_BASIC", col("甲", "_甲", "TEXT", "   "))));
        assertDoesNotThrow(() -> assertValidFieldTypes(req("QUOTE"))); // columns 为空列表
    }

    // ---------------- B-2 / AC-3 · AC-4：核价两套默认 BASIC_DATA + 顶层绑定键 ----------------

    @Test
    void costingDialectsDefaultToBasicData() {
        for (String dialect : List.of("COST_BASIC", "COST_DETAIL")) {
            CreateComponentRequest out = build(
                    req(dialect,
                            col("材质名称", "material_name", "TEXT", null),
                            col("组成用量", "component_qty", "NUMBER", null)),
                    "builder_ft");

            assertEquals(2, out.fields.size(), dialect + "：应产出 2 个字段");
            for (String name : List.of("材质名称", "组成用量")) {
                assertEquals("BASIC_DATA", fieldNamed(out, name).get("field_type"),
                        dialect + " 的「" + name + "」默认应为 BASIC_DATA（D-2）"
                                + " —— 注意「组成用量」是 NUMBER：核价侧的默认**与数据类型无关**，"
                                + "这正是与改动前『恒按数据类型推』的分水岭");
            }
        }
    }

    /** 绑定键位置的逐字断言（D-73/B-30：跟 field_type 走）。 */
    @Test
    void costingWritesFlatBasicDataPathAndNoDefaultSource() {
        for (String dialect : List.of("COST_BASIC", "COST_DETAIL")) {
            CreateComponentRequest out = build(
                    req(dialect, col("组成用量", "component_qty", "NUMBER", null)), "builder_ft");
            Map<String, Object> f = fieldNamed(out, "组成用量");

            assertEquals("BASIC_DATA", f.get("field_type"), dialect);
            assertEquals("$builder_ft.component_qty", f.get("basic_data_path"),
                    dialect + "：BASIC_DATA 必须写**顶层平铺** basic_data_path（AC-3/AC-4）");
            assertNull(f.get("default_source"),
                    dialect + "：BASIC_DATA 不得再写嵌套 default_source");
        }
    }

    // ---------------- B-2 / AC-5：报价侧逐位不变 ----------------

    @Test
    void quoteDialectKeepsInputTypesAndNestedBinding() {
        CreateComponentRequest out = build(
                req("QUOTE",
                        col("料件", "_料件", "TEXT", null),
                        col("单价", "_单价", "NUMBER", null),
                        col("日期", "_日期", "DATE", null)),
                "builder_ft");

        assertEquals("INPUT_TEXT", fieldNamed(out, "料件").get("field_type"), "TEXT → INPUT_TEXT");
        assertEquals("INPUT_NUMBER", fieldNamed(out, "单价").get("field_type"), "非 TEXT → INPUT_NUMBER");
        assertEquals("INPUT_NUMBER", fieldNamed(out, "日期").get("field_type"), "非 TEXT 一律 INPUT_NUMBER（现状）");

        for (String name : List.of("料件", "单价", "日期")) {
            Map<String, Object> f = fieldNamed(out, name);
            assertNull(f.get("basic_data_path"), "报价侧 INPUT_* 不写顶层 basic_data_path");
            @SuppressWarnings("unchecked")
            Map<String, Object> ds = (Map<String, Object>) f.get("default_source");
            assertNotNull(ds, "报价侧 INPUT_* 必须写嵌套 default_source（AC-5 零回归）");
            assertEquals("BASIC_DATA", ds.get("type"));
        }
        assertEquals("$builder_ft._单价", ((Map<?, ?>) fieldNamed(out, "单价").get("default_source")).get("path"));
    }

    /** 不传 dialect（旧客户端）= QUOTE，行为与改动前逐位一致。 */
    @Test
    void absentDialectBehavesAsQuote() {
        CreateComponentRequest out = build(req(null, col("料件", "_料件", "TEXT", null)), "builder_ft");
        Map<String, Object> f = fieldNamed(out, "料件");
        assertEquals("INPUT_TEXT", f.get("field_type"));
        assertNotNull(f.get("default_source"));
        assertNull(f.get("basic_data_path"));
    }

    // ---------------- 显式值恒优先于默认（api.md §1.3） ----------------

    @Test
    void explicitFieldTypeAlwaysWinsOverDialectDefault() {
        // 核价侧显式选 INPUT_TEXT → 不被默认覆盖，且绑定键跟着 field_type 走嵌套
        CreateComponentRequest costing = build(
                req("COST_BASIC", col("备注", "remark", "TEXT", "INPUT_TEXT")), "builder_ft");
        Map<String, Object> cf = fieldNamed(costing, "备注");
        assertEquals("INPUT_TEXT", cf.get("field_type"), "显式值必须压过方言默认");
        assertNull(cf.get("basic_data_path"));
        assertNotNull(cf.get("default_source"));

        // 报价侧显式选 BASIC_DATA → 同样以显式值为准，绑定键转顶层
        CreateComponentRequest quote = build(
                req("QUOTE", col("成材率", "yield_rate", "NUMBER", "BASIC_DATA")), "builder_ft");
        Map<String, Object> qf = fieldNamed(quote, "成材率");
        assertEquals("BASIC_DATA", qf.get("field_type"));
        assertEquals("$builder_ft.yield_rate", qf.get("basic_data_path"),
                "D-73/B-30 不变量：绑定键跟 field_type 走，**不跟报价/核价侧走**");
        assertNull(qf.get("default_source"));
    }

    // ---------------- 纯函数判据（接线守卫之外的第二道） ----------------

    // ---------------- B-5 / AC-15 · AC-16：回填一致性 ----------------

    /** 造一个"存量组件"：component.fields[] 有真实 field_type，builder_config 侧 fieldType 全 null。 */
    private static com.cpq.component.entity.Component legacyComponent(String fieldsJson) {
        com.cpq.component.entity.Component c = new com.cpq.component.entity.Component();
        c.id = java.util.UUID.randomUUID();
        c.fields = fieldsJson;
        return c;
    }

    private static BuilderConfig cfgWithNullFieldTypes(String... fieldNames) {
        BuilderConfig cfg = new BuilderConfig();
        cfg.columns = new java.util.ArrayList<>();
        for (String n : fieldNames) cfg.columns.add(col(n, "_" + n, "TEXT", null));
        return cfg;
    }

    private static void backfill(BuilderConfig cfg, com.cpq.component.entity.Component comp) {
        try {
            Method m = BuilderService.class.getDeclaredMethod("backfillFieldTypesFromComponent",
                    BuilderConfig.class, com.cpq.component.entity.Component.class);
            m.setAccessible(true);
            m.invoke(new BuilderService(), cfg, comp);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * AC-15 存量保护：{@code builder_config.fieldType} 全 null 的存量组件，
     * GET 必须回填出 {@code component.fields[]} 里的<b>真实</b>类型，而不是让前端显示兜底值。
     *
     * <p>取材于实测的 {@code COMP-2299}（12/12 null，真实为 INPUT_TEXT/INPUT_NUMBER 混排）。
     * 🔑 断言里特意让 NUMBER 列与 TEXT 列<b>交替出现</b> —— 若哪天有人把匹配改成按下标，
     * 交替排布会让串位立刻显形；同名同序的夹具是看不出来的。
     */
    @Test
    void getBackfillsRealFieldTypeForLegacyComponent() {
        var comp = legacyComponent("""
            [{"name":"生产料号","field_type":"INPUT_TEXT"},
             {"name":"项次","field_type":"INPUT_NUMBER"},
             {"name":"料号","field_type":"INPUT_TEXT"},
             {"name":"组成用量","field_type":"INPUT_NUMBER"}]""");
        BuilderConfig cfg = cfgWithNullFieldTypes("生产料号", "项次", "料号", "组成用量");

        backfill(cfg, comp);

        assertEquals("INPUT_TEXT", cfg.columns.get(0).fieldType, "生产料号");
        assertEquals("INPUT_NUMBER", cfg.columns.get(1).fieldType,
                "项次是 NUMBER 列：显示成兜底的 INPUT_TEXT 就是 AC-15 要防的降级");
        assertEquals("INPUT_TEXT", cfg.columns.get(2).fieldType, "料号");
        assertEquals("INPUT_NUMBER", cfg.columns.get(3).fieldType, "组成用量");
    }

    /** 🚫 按字段名匹配，不按下标（AP-54 同族）：两边顺序不同也必须各归各位。 */
    @Test
    void backfillMatchesByFieldNameNotByIndex() {
        var comp = legacyComponent("""
            [{"name":"组成用量","field_type":"INPUT_NUMBER"},
             {"name":"料号","field_type":"INPUT_TEXT"}]""");
        // builder_config 侧顺序与 fields[] **相反**（用户拖拽排序过）
        BuilderConfig cfg = cfgWithNullFieldTypes("料号", "组成用量");

        backfill(cfg, comp);

        assertEquals("INPUT_TEXT", cfg.columns.get(0).fieldType,
                "「料号」拿到了 INPUT_NUMBER ⇒ 匹配退化成按下标，类型串到别的列上了（且不报错）");
        assertEquals("INPUT_NUMBER", cfg.columns.get(1).fieldType, "「组成用量」应拿到 INPUT_NUMBER");
    }

    /** 读路径不过滤、不报错：实测存量里有 FORMULA（COMP-2300），必须原样返回。 */
    @Test
    void backfillReturnsNonWhitelistedValuesAsIs() {
        var comp = legacyComponent("""
            [{"name":"小计","field_type":"FORMULA"},
             {"name":"备注","field_type":"FIXED_VALUE"},
             {"name":"条件列","field_type":"LIST_FORMULA"}]""");
        BuilderConfig cfg = cfgWithNullFieldTypes("小计", "备注", "条件列");

        assertDoesNotThrow(() -> backfill(cfg, comp),
                "读路径按写路径白名单报错，会把「打得开的组件」变成「打不开的组件」");
        assertEquals("FORMULA", cfg.columns.get(0).fieldType);
        assertEquals("FIXED_VALUE", cfg.columns.get(1).fieldType);
        assertEquals("LIST_FORMULA", cfg.columns.get(2).fieldType);
    }

    /** 显式值不被回填覆盖；同名字段找不到时保持 null（列被改名/新拖入未保存）。 */
    @Test
    void backfillNeverOverwritesExplicitAndToleratesMissingName() {
        var comp = legacyComponent("""
            [{"name":"料号","field_type":"INPUT_TEXT"}]""");
        BuilderConfig cfg = new BuilderConfig();
        cfg.columns = new java.util.ArrayList<>(List.of(
                col("料号", "_料号", "TEXT", "BASIC_DATA"),   // 已显式选过
                col("新拖入的列", "_x", "TEXT", null)));       // fields[] 里没有

        backfill(cfg, comp);

        assertEquals("BASIC_DATA", cfg.columns.get(0).fieldType, "显式值不得被库里的旧值覆盖");
        assertNull(cfg.columns.get(1).fieldType, "同名字段不存在 → 保持 null，交给前端自己兜底");
    }

    /** 字段 jsonb 坏掉时不放大故障面：不抛异常，维持原样。 */
    @Test
    void backfillDoesNotBlowUpOnCorruptFieldsJson() {
        var comp = legacyComponent("{ 这不是合法 JSON ");
        BuilderConfig cfg = cfgWithNullFieldTypes("料号");
        assertDoesNotThrow(() -> backfill(cfg, comp), "读路径不应因 fields 解析失败而 500");
        assertNull(cfg.columns.get(0).fieldType);
    }

    /**
     * AC-16：save 之后 {@code builder_config.columns[].fieldType} 不再为 null，
     * 且与实际落库的 {@code component.fields[].field_type} 同名字段逐字相同。
     *
     * <p>这里直接驱动 {@code applyEffectiveFieldTypes}（save 里真正调的那个），
     * 再把同一批列喂给 {@code buildComponentUpdateRequest}，比对两侧取值 —— 因为
     * {@code effectiveColumns} 与 {@code req.columns} 是同一批对象引用，这正是生产路径的形态。
     */
    @Test
    void saveBackfillMakesBuilderConfigMatchComponentFields() {
        for (String dialect : List.of("QUOTE", "COST_BASIC", "COST_DETAIL")) {
            SaveRequest r = req(dialect,
                    col("料件", "_料件", "TEXT", null),
                    col("单价", "_单价", "NUMBER", null),
                    col("备注", "_备注", "TEXT", "INPUT_TEXT"));   // 显式值混排

            applyEffective(r.columns, dialect);

            for (BuilderConfig.ColumnConfig c : r.columns) {
                assertNotNull(c.fieldType, dialect + "：save 后 builder_config 的 fieldType 仍为 null（AC-16）");
                assertFalse(c.fieldType.isBlank(), dialect);
            }

            // 与真正落库的 component.fields[].field_type 逐字比对
            CreateComponentRequest out = build(r, "builder_ft");
            for (BuilderConfig.ColumnConfig c : r.columns) {
                assertEquals(fieldNamed(out, c.fieldName).get("field_type"), c.fieldType,
                        dialect + "：字段「" + c.fieldName + "」builder_config 与 component.fields 不一致 ⇒ "
                                + "「界面显示的 ≠ 实际生效的」这个根子没堵住（AC-16）");
            }
            assertEquals("INPUT_TEXT", fieldNamed(out, "备注").get("field_type"),
                    dialect + "：显式值仍须压过方言默认");
        }
    }

    private static void applyEffective(List<BuilderConfig.ColumnConfig> cols, String dialect) {
        try {
            Method m = BuilderService.class.getDeclaredMethod("applyEffectiveFieldTypes",
                    List.class, CompileDialect.class);
            m.setAccessible(true);
            m.invoke(null, cols, CompileDialect.parse(dialect));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** B-5 两处接线的源码级守卫（同 saveMethodActuallyCallsWhitelistBeforeAnyWrite 的理由）。 */
    @Test
    void bothBackfillCallSitesAreWired() throws Exception {
        String raw = java.nio.file.Files.readString(builderServiceSource());

        String getBody = methodBody(raw, "public GetBuilderResponse get(");
        assertTrue(getBody.contains("backfillFieldTypesFromComponent("),
                "GET /builder 里没调 backfillFieldTypesFromComponent —— B-5① 接线丢了，"
                        + "存量组件会显示兜底值，AC-15 破功（且不报错）。");

        String saveBody = methodBody(raw, "public SaveResponse save(");
        int applyIdx = saveBody.indexOf("applyEffectiveFieldTypes(");
        assertTrue(applyIdx >= 0, "save() 里没调 applyEffectiveFieldTypes —— B-5② 接线丢了，AC-16 破功。");
        int serializeIdx = saveBody.indexOf("MAPPER.valueToTree(req)");
        assertTrue(serializeIdx >= 0, "save() 里找不到 builder_config 序列化点——本守卫需同步更新");
        assertTrue(applyIdx < serializeIdx,
                "applyEffectiveFieldTypes 必须早于 builder_config 序列化，否则回填的值根本没进 JSONB");
    }

    // ---------------- B-4 / AC-10：行身份与 field_type 正交 ----------------

    /**
     * AC-10 的后端半边：{@code part_no_field} / {@code part_name_field} / {@code row_key_fields}
     * <b>按角色（{@code resolvedRoles}）推导，与 {@code field_type} 无关</b>。
     *
     * <p>backtask B-4 要求「<b>实证该假设成立</b>」而不是假定它成立 —— 若推导链某处顺带看了
     * {@code field_type}，那是需求缺口，须停下报主线（🚫 不许自行加"料号列不许配 BASIC_DATA"这类
     * 新业务规则）。本条把假设机器化：<b>同一份列配置跑两遍，唯一变量是 field_type</b>，
     * 断言三项行身份属性逐字相同。
     *
     * <p>渲染侧那半边（行仍能归属到卡片、行数与对照组相同）在 {@code test.md} 的 AC-10。
     */
    @Test
    void rowIdentityIsDerivedFromRolesNotFieldType() {
        for (String dialect : List.of("QUOTE", "COST_BASIC")) {
            CreateComponentRequest asInput = build(req(dialect,
                    roled(col("料号", "material_no", "TEXT", "INPUT_TEXT"), "PART_NO", "ROW_KEY"),
                    roled(col("品名", "material_name", "TEXT", "INPUT_TEXT"), "PART_NAME"),
                    roled(col("序", "seq_no", "NUMBER", "INPUT_NUMBER"), "SORT")), "builder_ft");

            CreateComponentRequest asBasic = build(req(dialect,
                    roled(col("料号", "material_no", "TEXT", "BASIC_DATA"), "PART_NO", "ROW_KEY"),
                    roled(col("品名", "material_name", "TEXT", "BASIC_DATA"), "PART_NAME"),
                    roled(col("序", "seq_no", "NUMBER", "BASIC_DATA"), "SORT")), "builder_ft");

            // 前置非空：避免"两边都是 null 所以相等"这种恒真通过
            assertEquals("料号", asInput.partNoField, dialect + "：对照组本身就没推出 partNoField，用例无效");
            assertEquals(List.of("料号"), asInput.rowKeyFields, dialect);

            assertEquals(asInput.partNoField, asBasic.partNoField,
                    dialect + "：把料号列配成 BASIC_DATA 后 partNoField 变了 ⇒ AC-10 的假设不成立，"
                            + "推导链某处看了 field_type，须停下报主线（这是需求缺口，不是可以就地加约束的事）");
            assertEquals(asInput.partNameField, asBasic.partNameField, dialect + "：partNameField 应与 field_type 无关");
            assertEquals(asInput.sortField, asBasic.sortField, dialect + "：sortField 应与 field_type 无关");
            assertEquals(asInput.rowKeyFields, asBasic.rowKeyFields, dialect + "：rowKeyFields 应与 field_type 无关");

            // 同时确认 field_type 本身确实被改掉了 —— 否则"两边相同"可能只是因为改动没生效
            assertEquals("INPUT_TEXT", fieldNamed(asInput, "料号").get("field_type"), dialect);
            assertEquals("BASIC_DATA", fieldNamed(asBasic, "料号").get("field_type"),
                    dialect + "：对照组的 field_type 没真的变成 BASIC_DATA ⇒ 本用例什么都没验");
        }
    }

    private static BuilderConfig.ColumnConfig roled(BuilderConfig.ColumnConfig c, String... roles) {
        c.resolvedRoles = List.of(roles);
        return c;
    }

    // ---------------- B-1 接线守卫（源码级） ----------------

    /**
     * 🔬 <b>本条是「还原实验」逼出来的。</b>
     *
     * <p>上面那几条白名单用例是<b>反射直调</b> {@code assertValidFieldTypes} 的 —— 判据对不对它们守得住，
     * 但「{@code save()} 到底有没有调它」它们<b>一个字都没验</b>。实测：把 {@code save()} 里那行调用整行删掉，
     * 9 条用例<b>全绿</b>（2026-09-09 还原实验 2 实录）。⇒ 没有本条，AC-8 在后端侧就是白测。
     *
     * <p>为什么走源码扫描而不是打真实端点：{@code save()} 是 {@code @Transactional} 且第一步
     * {@code requireComponent} 就要查库，而 {@code application-test.properties} 的默认库<b>就是共享开发库</b>
     * {@code cpq_db_0724}（{@code CLAUDE.md} §1）—— 为一条接线守卫去写共享库不划算。
     * 端到端的 400 取证在 {@code test.md} 的 AC-8（打真实保存端点）那一侧，本条只守「接线还在、且在写之前」。
     * 手法沿用既有先例 {@code QuotePendingScopeOpenWhitelistTest}（同样是"人工 grep 会腐化"的不变式机器化）。
     *
     * <p>第二条断言（调用点必须早于任何写）守的是 AC-8 的<b>「库中不发生变更」</b>那一半：
     * 即便有人把校验挪到 {@code persist()} 之后，靠事务回滚"看起来"也对，但那是<b>依赖回滚语义</b>的正确，
     * 一旦将来有人给某一步加 {@code REQUIRES_NEW} 就会静默破功。
     */
    @Test
    void saveMethodActuallyCallsWhitelistBeforeAnyWrite() throws Exception {
        String raw = java.nio.file.Files.readString(builderServiceSource());
        String body = saveMethodBody(raw);

        int callIdx = body.indexOf("assertValidFieldTypes(req);");
        assertTrue(callIdx >= 0,
                "BuilderService.save() body 里找不到 assertValidFieldTypes(req); —— B-1 的接线丢了。"
                        + "白名单方法还在不代表它被调用：反射直调的那几条用例照样全绿（实测）。");

        for (String write : List.of("componentSqlViewService.create(", "componentSqlViewService.update(",
                ".persist()", "componentService.update(")) {
            int writeIdx = body.indexOf(write);
            assertTrue(writeIdx >= 0,
                    "在 save() body 里找不到写操作标记「" + write + "」—— 不是校验出问题，是本守卫的"
                            + "方法体提取逻辑或该写点本身变了，先修守卫再谈结论（避免"
                            + "「什么都没找到所以恒真」这种假绿）。");
            assertTrue(callIdx < writeIdx,
                    "assertValidFieldTypes 必须早于「" + write + "」——AC-8 要求校验失败时库中不发生变更，"
                            + "把校验放到写之后就变成了依赖事务回滚，而不是结构上不可能写。");
        }
    }

    private static java.nio.file.Path builderServiceSource() {
        java.nio.file.Path dir = java.nio.file.Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            for (String prefix : List.of("cpq-backend/src/main/java", "src/main/java")) {
                java.nio.file.Path c = dir.resolve(prefix)
                        .resolve("com/cpq/builder/service/BuilderService.java");
                if (java.nio.file.Files.isRegularFile(c)) return c;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("定位不到 BuilderService.java；cwd="
                + java.nio.file.Paths.get("").toAbsolutePath());
    }

    /**
     * 取 {@code public SaveResponse save(...)} 的方法体原文。
     * 先做极简词法屏蔽（字符串/字符/行注释/块注释 → 等长空白），避免注释与日志串里的花括号打乱深度计数；
     * 屏蔽串与原文<b>等长</b>，所以下标可以直接回原文切片。
     */
    private static String saveMethodBody(String raw) {
        return methodBody(raw, "public SaveResponse save(");
    }

    private static String methodBody(String raw, String signature) {
        String masked = maskJava(raw);
        int sig = masked.indexOf(signature);
        assertTrue(sig >= 0, "找不到方法签名「" + signature + "」——方法被改名/改签名了，本守卫需同步更新");
        int brace = masked.indexOf('{', sig);
        assertTrue(brace >= 0, "签名「" + signature + "」之后找不到方法体起始花括号");
        int depth = 1;
        int i = brace + 1;
        while (i < masked.length() && depth > 0) {
            char c = masked.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            i++;
        }
        assertEquals(0, depth, "save() 方法体花括号未闭合——词法屏蔽或提取逻辑坏了");
        return raw.substring(brace + 1, i - 1);
    }

    /** 极简 Java 词法屏蔽（同 QuotePendingScopeOpenWhitelistTest#maskJava 的思路，等长替换）。 */
    private static String maskJava(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '\\' && i + 1 < n) { out.append("  "); i += 2; continue; }
                    if (d == quote) { out.append(' '); i++; break; }
                    out.append(d == '\n' ? '\n' : ' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                out.append("  ");
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    out.append(src.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i + 1 < n) { out.append("  "); i += 2; } else { i = n; }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ---------------- 纯函数判据（接线守卫之外的第二道） ----------------

    @Test
    void defaultFieldTypeRuleTable() {
        assertEquals("BASIC_DATA", BuilderService.defaultFieldType(CompileDialect.COST_BASIC, "TEXT"));
        assertEquals("BASIC_DATA", BuilderService.defaultFieldType(CompileDialect.COST_BASIC, "NUMBER"));
        assertEquals("BASIC_DATA", BuilderService.defaultFieldType(CompileDialect.COST_DETAIL, "TEXT"));
        assertEquals("BASIC_DATA", BuilderService.defaultFieldType(CompileDialect.COST_DETAIL, "NUMBER"));
        assertEquals("INPUT_TEXT", BuilderService.defaultFieldType(CompileDialect.QUOTE, "TEXT"));
        assertEquals("INPUT_NUMBER", BuilderService.defaultFieldType(CompileDialect.QUOTE, "NUMBER"));
        assertEquals("INPUT_NUMBER", BuilderService.defaultFieldType(CompileDialect.QUOTE, null));

        // 🔒 用 isCosting() 判的间接守卫：将来若再加一个 COST_* 方言，它必须自动落到 BASIC_DATA，
        //    而不是"忘了加一个 == 分支"就静默按报价侧推（CompileDialect#isCosting 的 javadoc）。
        for (CompileDialect d : CompileDialect.values()) {
            String expected = d == CompileDialect.QUOTE ? "INPUT_TEXT" : "BASIC_DATA";
            assertEquals(expected, BuilderService.defaultFieldType(d, "TEXT"),
                    "方言 " + d + " 的默认值");
        }
    }
}
