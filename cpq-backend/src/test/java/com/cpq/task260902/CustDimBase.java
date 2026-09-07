package com.cpq.task260902;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>task-260907「报价侧加客户维度」验收测试基座。</b>
 *
 * <h3>为什么这些类落在 com.cpq.task260902 包里</h3>
 * 报价 / 核价三份 Excel 模板的夹具构造器（{@link DatasetFixtureBuilder}）、契约薄封装
 * （{@link DatasetApi}）与主数据探针都是<b>包私有</b>的。另起一个包就必须整份复制 561 行的
 * 夹具构造器 —— 复制品会与原件漂移，而漂移的症状是「夹具错被当成实现错」。
 * 借用包名<b>只为可见性</b>；本文件与 CustDim*AcTest 全部归 task-260907。
 *
 * <h3>与 {@link DatasetAcTestBase} 的关系：刻意不继承</h3>
 * 那个基座的 {@code @BeforeEach} 会 DELETE ... LIKE 'TEST-DS-%'。多条会话并发时，
 * 谁先跑谁把对方的夹具删了，<b>症状是随机挂且极像业务回归</b>。
 * 本基座自带前缀 {@link #PREFIX}，且<b>清理一律用本轮自建的完整值精确删</b>，不用前缀 LIKE 删。
 *
 * <h3>共享库红线</h3>
 * test profile 实连共享开发库 cpq_db_0724。本套用例：无 TRUNCATE / DROP / 无条件 DELETE；
 * 不写 customer / element / material_recipe / process_master 等共享主数据；不动 costing_bom_tree_config。
 */
abstract class CustDimBase {

    // ============================ 夹具身份 ============================

    /** 本任务线专用前缀（T260907B- / Q- / T- / M- 已被其它会话占用）。 */
    static final String PREFIX = "T260907C-";

    /** 每轮唯一。静态 =&gt; 同一次 JVM 里所有用例共用一个 RUN_ID，清理面才收得住。 */
    static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 6);

    /** 报价侧轴值（销售料号）。ds_quote_*.material_no 是 varchar(128)，长度不成问题。 */
    static final String AXIS = PREFIX + RUN_ID;
    /** 第二个轴值 —— 「另一个料号不该被动」的对照。 */
    static final String AXIS_OTHER = PREFIX + RUN_ID + "-O";
    /** 电镀方案编号（免版本表 ds_quote_plating_scheme 的轴）。 */
    static final String SCHEME_NO = PREFIX + RUN_ID + "-S";
    /**
     * 核价侧轴值（生产料号）。
     *
     * <p>🚩 <b>2026-09-07 改：必须带 RUN_ID。</b> 原先刻意写成常量 {@code PREFIX + "COSTBASE"}，
     * 理由是「AC-6 的逐行 md5 基线要跨轮可比，轴值每轮变则恒红」——
     * <b>那个理由是错的，代价是并发互踩</b>：两个 CustDim 会话同库并跑时，
     * 一方的 {@code @AfterEach} 会把另一方正在用的行删掉，
     * <b>症状是随机挂 + 报「残留未清干净」，长得像清理面缺口，其实是并发。</b>
     *
     * <p>跨轮可比性改由 {@link #rowDigests} <b>把轴列本身排除出指纹</b>来保证 ——
     * 轴列在选定行集里恒为同一个值，本来就不携带信息，排除它零损失。
     */
    static final String COST_AXIS = PREFIX + RUN_ID + "-COSTBASE";
    /** 核价侧电镀方案编号 —— 同样带 RUN_ID，理由同 COST_AXIS。 */
    static final String COST_SCHEME_NO = PREFIX + RUN_ID + "-COSTSCHM";

    /**
     * V6 表 material_bom_item 的 material_no / component_no / customer_no 都是 <b>varchar(20)</b>。
     * AC-1 的递归夹具要写 V6，值必须 &lt;= 20，所以用这个短前缀，不能用 PREFIX + RUN_ID 再加后缀。
     */
    static final String SHORT = "T260907C" + RUN_ID;   // 8 + 6 = 14 字符，留 6 位后缀

    /** 客户编号：AC-2 原文点名的两个（customer.code 实存）。 */
    static final String CUST_A = "CUST-0004";   // 正泰
    static final String CUST_B = "CUST-0001";   // 罗克韦尔（存量统一回填值）
    /**
     * 客户产品编号：两个客户各用各的，避开 uq_ds_quote_customer_part(customer_no,customer_product_no)
     * 与占号表 material_customer_map 的跨客户冲突 —— 那是另一条故障线，不该混进本任务判据。
     */
    static final String CPN_A = PREFIX + RUN_ID + "-PA";
    static final String CPN_B = PREFIX + RUN_ID + "-PB";

    static final String T_MATERIAL = "ds_quote_material";
    static final String T_MATERIAL_BOM = "ds_quote_material_bom";
    static final String T_MATERIAL_BOM_HISTORY = "ds_quote_material_bom_history";
    static final String T_CUSTOMER_PART = "ds_quote_customer_part";
    static final String T_PLATING_SCHEME = "ds_quote_plating_scheme";

    @Inject
    EntityManager em;

    // ============================ 生命周期 ============================

    @BeforeEach
    void custDimSetUp() {
        // 上一轮若崩在中间会留残渣；先清再自检 —— 让「残留」以残留的名义失败，不要伪装成业务缺陷。
        cleanupFixtures();
        assertResidueFree("@BeforeEach");
    }

    @AfterEach
    void custDimTearDown() {
        try {
            cleanupFixtures();
        } finally {
            assertResidueFree("@AfterEach");
        }
    }

    // ============================ 只读 SQL 助手 ============================

    long count(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    String scalar(String sql) {
        List<?> l = em.createNativeQuery(sql).getResultList();
        return l.isEmpty() || l.get(0) == null ? null : String.valueOf(l.get(0));
    }

    @SuppressWarnings("unchecked")
    List<Object> col(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    List<String> strCol(String sql) {
        return col(sql).stream().map(o -> o == null ? null : String.valueOf(o)).toList();
    }

    @SuppressWarnings("unchecked")
    List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    boolean tableExists(String t) {
        return count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='" + t + "'") > 0;
    }

    boolean columnExists(String t, String c) {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_schema='public'"
                + " AND table_name='" + t + "' AND column_name='" + c + "'") > 0;
    }

    /** 库里当前所有 ds_quote_% 表（含 _history / _record）。不写死表数。 */
    List<String> dsQuoteTables() {
        return tablesLike("ds\\_quote\\_%");
    }

    List<String> tablesLike(String likePattern) {
        return strCol("SELECT table_name FROM information_schema.tables WHERE table_schema='public'"
                + " AND table_type='BASE TABLE' AND table_name LIKE '" + likePattern + "' ORDER BY 1");
    }

    List<String> columnsOf(String t) {
        return strCol("SELECT lower(column_name) FROM information_schema.columns WHERE table_schema='public'"
                + " AND table_name='" + t + "' ORDER BY ordinal_position");
    }

    static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ============================ session ============================

    /** 静态缓存：登录带 Redis 限流（30 次/分/IP），每条用例登录一次必然自撞，症状是「从某条起全部 401」。 */
    private static String cachedSession;

    String session() {
        if (cachedSession == null) {
            Response r = RestAssured.given().contentType(ContentType.JSON)
                    .body("{\"username\":\"admin\",\"password\":\"Admin@2026\"}")
                    .when().post("/api/cpq/auth/login");
            if (r.statusCode() != 200) {
                throw new AssertionError("admin 登录失败 -> HTTP " + r.statusCode() + "，响应=" + r.asString()
                        + "\n  依次排查：1 Redis 登录限流；2 admin 被 E2E 置成 INACTIVE；3 locked_until。");
            }
            cachedSession = r.cookie("CPQ_SESSION");
            assertNotNull(cachedSession, "登录 200 但没拿到 CPQ_SESSION cookie");
        }
        return cachedSession;
    }

    void assertStatus(Response r, int expected, String ac) {
        if (r.statusCode() == 401 && expected != 401) {
            throw new AssertionError(ac + "：收到 401 —— 先怀疑登录限流/账号被置 INACTIVE，不要当成端点没做。响应="
                    + r.asString());
        }
        assertEquals(expected, r.statusCode(), ac + "：HTTP 状态码不符。响应体=" + r.asString());
    }

    // ============================ 主数据探针（只读） ============================

    Fixtures.MasterDataProbe probe() {
        return (table, column, value) -> {
            if (!tableExists(table) || !columnExists(table, column)) {
                return false;
            }
            return count("SELECT count(*) FROM " + table + " WHERE " + column + " = '"
                    + value.replace("'", "''") + "'") > 0;
        };
    }

    /** 只在库里查不到时才替换（与 {@link Fixtures} 同口径），并登记本次回退。 */
    final Map<String, String> substitutions = new LinkedHashMap<>();

    void resolveColumn(DatasetFixtureBuilder b, String sheet, String column, boolean versioned,
                       String masterTable, String masterColumn, String fallback) {
        Fixtures.MasterDataProbe p = probe();
        for (int rowNo : b.dataRowNumbers(sheet, versioned)) {
            String v = b.readAsString(sheet, rowNo, column);
            if (v == null || v.isBlank()) {
                continue;
            }
            if (p.exists(masterTable, masterColumn, v.trim())) {
                continue;
            }
            substitutions.put(masterTable + "." + masterColumn + "=" + v.trim(), fallback);
            b.setText(sheet, rowNo, column, fallback);
        }
    }

    // ============================ 报价夹具 ============================

    static final String CUSTOMER_NO_COLUMN = "客户编号";
    static final String QUOTE_AXIS_COLUMN = "销售料号";
    /** 三套「物料」sheet 共有的料号类型列（裁决 D-30，值域 零件 / 外购件）。 */
    static final String MATERIAL_TYPE_COLUMN = "类型";
    static final String MATERIAL_TYPE_VALUE = "零件";

    static final List<String> QUOTE_VERSIONED_SHEETS = Fixtures.QUOTE_VERSIONED_SHEETS;

    /**
     * 构造一份「整份 Excel 只谈一个销售料号、只属于一个客户」的报价夹具。
     *
     * <p><b>为什么把所有 sheet 的轴值统一成同一个料号</b>：AC-2(1) 的判据是
     * 「<b>本次导入所写入的每一张表</b>，其 customer_no 全 = 本次所选客户」。
     * 模板原样有 4 个不同料号散在不同 sheet 上，「每一张表」覆盖不到几张，
     * 断言会以「通过」的形态在很小的面上空跑。统一轴值后多张主表同时有本料号的行。
     *
     * @param customerNo 客户编号，写进「客户料号」sheet 的红底必填列
     * @param axis       销售料号
     * @param cpn        客户产品编号（两个客户必须各用各的，见 {@link #CPN_A}）
     */
    DatasetFixtureBuilder quoteFixture(String customerNo, String axis, String cpn) {
        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(DatasetFixtureBuilder.quoteTemplate());

        // 1 客户编号列（模板 2026-09-03 04:22 起自带；退回旧版本时才补）
        if (!b.hasColumn("客户料号", CUSTOMER_NO_COLUMN)) {
            b.insertColumnAt("客户料号", 0, CUSTOMER_NO_COLUMN, customerNo);
        }

        // 2 模板可能自带轴值为空的错位残行（来料回收折扣）—— 按 R-1 必然 400，删掉。
        //   这不是放宽校验：轴列为空该被拒是另一条 AC 的判据，不在本任务范围。
        removeBlankAxisRows(b, "来料回收折扣");

        // 3 「物料」只留一行，轴值 = axis
        List<Integer> matRows = b.dataRowNumbers("物料", false);
        assertFalse(matRows.isEmpty(), "夹具构造：报价模板「物料」sheet 一条数据行都没有，轴值登记不了（D-24）");
        for (int i = matRows.size() - 1; i >= 1; i--) {
            b.deleteRow("物料", matRows.get(i));
        }
        int matRow = b.dataRowNumbers("物料", false).get(0);
        b.setText("物料", matRow, QUOTE_AXIS_COLUMN, axis);
        // 裁决 D-30：「类型」列值域 = 零件 / 外购件。模板那格填的是提示文本「零件/外购件」，
        // 原样导入必得 400「值不在允许值域」——这是模板的提示行为，不是实现缺陷。
        if (b.hasColumn("物料", MATERIAL_TYPE_COLUMN)) {
            b.setText("物料", matRow, MATERIAL_TYPE_COLUMN, MATERIAL_TYPE_VALUE);
        }

        // 4 「客户料号」只留一行：客户编号 / 客户产品编号 / 销售料号 三格全由本方法指定
        List<Integer> cpRows = b.dataRowNumbers("客户料号", false);
        assertFalse(cpRows.isEmpty(), "夹具构造：报价模板「客户料号」sheet 一条数据行都没有");
        for (int i = cpRows.size() - 1; i >= 1; i--) {
            b.deleteRow("客户料号", cpRows.get(i));
        }
        int cpRow = b.dataRowNumbers("客户料号", false).get(0);
        b.setText("客户料号", cpRow, CUSTOMER_NO_COLUMN, customerNo);
        b.setText("客户料号", cpRow, "客户产品编号", cpn);
        b.setText("客户料号", cpRow, QUOTE_AXIS_COLUMN, axis);

        // 5 13 张带版本 sheet 的轴值统一成 axis
        for (String sheet : QUOTE_VERSIONED_SHEETS) {
            for (int rowNo : b.dataRowNumbers(sheet, true)) {
                b.setText(sheet, rowNo, QUOTE_AXIS_COLUMN, axis);
            }
        }

        // 6 电镀方案（免版本，轴 = 方案编号）：改成本轮自建的方案号。
        //   不能沿用模板的 A0001 —— 那是共享行，导入会改到别人的数据（属全局状态，见 test.md 数据纪律）。
        for (int rowNo : b.dataRowNumbers("电镀方案", false)) {
            b.setText("电镀方案", rowNo, "方案编号", SCHEME_NO);
        }

        // 7 元素代码：库里有就原样用，缺了才回退（回退会登记进 substitutions）
        resolveColumn(b, "物料与元素BOM", "元素", true, "element", "element_code", "Cu");

        return b;
    }

    private void removeBlankAxisRows(DatasetFixtureBuilder b, String sheet) {
        List<Integer> victims = new ArrayList<>();
        for (int rowNo : b.dataRowNumbers(sheet, true)) {
            String v = b.readAsString(sheet, rowNo, QUOTE_AXIS_COLUMN);
            if (v == null || v.isBlank()) {
                victims.add(rowNo);
            }
        }
        for (int i = victims.size() - 1; i >= 0; i--) {
            b.deleteRow(sheet, victims.get(i));
        }
    }

    /**
     * 导入一份报价夹具。
     *
     * <h3>契约不确定点（已如实登记，不替实现拍板）</h3>
     * api.md 只写「导入端点契约不变，客户号由调用方填好」，<b>没写</b>数据集导入端点
     * 从哪里拿客户号。当前 16 个 sheet 里只有「客户料号」带客户编号列，所以本方法
     * <b>同时</b>把客户号写进工作簿、并作为 ?customerNo= query 参数发出，两处取值一致。
     * <ul>
     *   <li>实现若从工作簿推导 =&gt; 生效，query 参数被忽略，无害；</li>
     *   <li>实现若要求 query 参数 =&gt; 生效；</li>
     *   <li>实现若要求别的形状（如 multipart 字段）=&gt; 本方法会红，<b>那正是要报给主线的契约缺口</b>，
     *       不许在这里猜着改。</li>
     * </ul>
     */
    Response importQuote(String customerNo, String axis, String cpn, String fileName,
                         Consumer<DatasetFixtureBuilder> mutate) {
        DatasetFixtureBuilder b = quoteFixture(customerNo, axis, cpn);
        try {
            if (mutate != null) {
                mutate.accept(b);
            }
            return RestAssured.given()
                    .cookie("CPQ_SESSION", session())
                    .queryParam("customerNo", customerNo)
                    .multiPart("file", fileName, b.toBytes(),
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    // 2026-09-07 实测：只发 query 参数会被拒
                    // {"code":400,"message":"导入报价数据必须指定客户（customerNo）"}
                    // ⇒ 端点收的是 multipart 表单字段，不是 query 参数。两处都发，取值一致。
                    .multiPart("customerNo", customerNo)
                    .when().post("/api/cpq/dataset/{dataset}/import", DatasetApi.QUOTE);
        } finally {
            b.close();
        }
    }

    Response importQuote(String customerNo, String axis, String cpn, String fileName) {
        return importQuote(customerNo, axis, cpn, fileName, null);
    }

    // ============================ 维护端（带客户号）============================

    /**
     * 维护端读行 / 存行的薄封装 —— <b>比 {@link DatasetApi} 多带一个 customerNo</b>。
     *
     * <p>🚩 2026-09-07 实测：轴变复合后维护端两个端点都要客户号，
     * 不带就返 {@code {"code":400,"message":"保存报价数据必须指定客户（customerNo）"}}。
     * 传输形态按 A0-2 裁决的 query 参数试；若某天改成别的形状，这里会红，
     * <b>那正是契约变更该被发现的地方</b>，🚫 不要靠 try/catch 兜过去。
     */
    Response maintRows(String dataset, String axis, String sheetKey, String customerNo) {
        var req = RestAssured.given().cookie("CPQ_SESSION", session());
        if (customerNo != null) {
            req = req.queryParam("customerNo", customerNo);
        }
        return req.when().get("/api/cpq/dataset/{dataset}/parts/{axis}/sheets/{sheetKey}/rows",
                dataset, axis, sheetKey);
    }

    Response maintSave(String dataset, String axis, String sheetKey, String customerNo,
                       int baseVersion, List<Map<String, Object>> rows) {
        var req = RestAssured.given().cookie("CPQ_SESSION", session())
                .contentType(ContentType.JSON)
                .body(Map.of("baseVersion", baseVersion, "rows", rows));
        if (customerNo != null) {
            req = req.queryParam("customerNo", customerNo);
        }
        return req.when().put("/api/cpq/dataset/{dataset}/parts/{axis}/sheets/{sheetKey}/rows",
                dataset, axis, sheetKey);
    }

    // ============================ 核价夹具（AC-6 反向回归用） ============================

    DatasetFixtureBuilder costBasicFixture(String axis) {
        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(DatasetFixtureBuilder.costBasicTemplate());
        retargetCostAxis(b, Fixtures.COST_BASIC_VERSIONED_SHEETS, axis);
        resolveColumn(b, "加工费&组装费", "工序编号", true, "process_master", "process_no", "Z100");
        resolveColumn(b, "其他外加工成本", "工序编号", true, "process_master", "process_no", "Z100");
        resolveColumn(b, "物料与元素BOM", "材质料号", true, "material_recipe", "code", "00006");
        resolveColumn(b, "物料与元素BOM", "元素代码", true, "element", "element_code", "Cu");
        return b;
    }

    DatasetFixtureBuilder costDetailFixture(String axis) {
        DatasetFixtureBuilder b = DatasetFixtureBuilder.from(DatasetFixtureBuilder.costDetailTemplate());
        retargetCostAxis(b, Fixtures.COST_DETAIL_VERSIONED_SHEETS, axis);
        resolveColumn(b, "产能", "工序编号", true, "process_master", "process_no", "Z100");
        // 电镀方案（免版本，轴 = 方案编号）改成本轮自建的方案号，并同步改「电镀成本」sheet 的引用。
        // 🚨 不改的话，导入会 updated=2 地写到共享行 A0001 上（2026-09-07 首轮实测确实写了，
        //    值没变但 updated_at / updated_by 被改）—— 那属于「测试改变共享库的全局状态」。
        for (int rowNo : b.dataRowNumbers("电镀方案", false)) {
            b.setText("电镀方案", rowNo, "方案编号", COST_SCHEME_NO);
        }
        for (int rowNo : b.dataRowNumbers("电镀成本", true)) {
            String ref = b.readAsString("电镀成本", rowNo, "电镀方案编号");
            if (ref != null && !ref.isBlank()) {
                b.setText("电镀成本", rowNo, "电镀方案编号", COST_SCHEME_NO);
            }
        }
        return b;
    }

    /** 核价侧轴列是「生产料号」；同样统一轴值 + 删占位行（轴有值其余全空，按 D-23 必然 400）。 */
    private void retargetCostAxis(DatasetFixtureBuilder b, List<String> versionedSheets, String axis) {
        List<Integer> matRows = b.dataRowNumbers("物料", false);
        for (int i = matRows.size() - 1; i >= 1; i--) {
            b.deleteRow("物料", matRows.get(i));
        }
        for (int rowNo : b.dataRowNumbers("物料", false)) {
            b.setText("物料", rowNo, "生产料号", axis);
            if (b.hasColumn("物料", MATERIAL_TYPE_COLUMN)) {
                b.setText("物料", rowNo, MATERIAL_TYPE_COLUMN, MATERIAL_TYPE_VALUE);
            }
        }
        for (String sheet : versionedSheets) {
            List<Integer> victims = new ArrayList<>();
            for (int rowNo : b.dataRowNumbers(sheet, true)) {
                String v = b.readAsString(sheet, rowNo, "生产料号");
                if (v == null || v.isBlank()) {
                    continue;
                }
                if (b.isOnlyColumnFilled(sheet, rowNo, "生产料号")) {
                    victims.add(rowNo);
                } else {
                    b.setText(sheet, rowNo, "生产料号", axis);
                }
            }
            for (int i = victims.size() - 1; i >= 0; i--) {
                b.deleteRow(sheet, victims.get(i));
            }
        }
    }

    Response importCost(String dataset, DatasetFixtureBuilder b, String fileName) {
        try {
            return DatasetApi.importFile(session(), dataset, b.toBytes(), fileName);
        } finally {
            b.close();
        }
    }

    // ============================ 内容指纹（AC-6 逐行 md5 用） ============================

    /**
     * 把一张表里属于某个轴值的全部行导出成<b>稳定的逐行 md5 列表</b>。
     *
     * <p>排除 id / origin_id / created_at / updated_at / archived_at 等每次导入必然不同的列 ——
     * 否则「逐行 md5 相同」恒假，红得毫无信息量。其余列（含 version_no / row_fingerprint / source）
     * <b>全部计入</b>。行序不进判据（AP-66 / task-260903 已实证 UNION ALL 会改行序）。
     */
    List<String> rowDigests(String table, String axisColumn, String axisValue) {
        if (!tableExists(table) || !columnExists(table, axisColumn)) {
            return List.of();
        }
        // 🚩 axisColumn 本身也必须排除：它在本次选中的行集里恒等于 axisValue，
        //    不携带任何信息，却会把「轴值带 RUN_ID」直接变成「指纹每轮都不同」。
        //    排除它之后，轴值可以安全地唯一化（并发不互踩），基线仍然跨轮可比。
        // row_fingerprint 也必须排除：它是**行内容的哈希**，夹具身份（RUN_ID）一旦经由
        // 引用列（如 ds_cost_detail_plating_cost.plating_scheme_no）进入行内容，就会被哈希进去，
        // 而哈希不可逆 —— 上面的 RUN_ID 归一化对它无效，基线会永远对不上。
        // 🚩 代价与补偿（如实登记，不假装无损）：
        //    排除它 = 指纹本身不再逐字比对。补偿有两处仍在判据里：
        //    ① version_no 仍计入；② 快照头部记录了维护端保存的 result（UNCHANGED / UPGRADED / CREATED）——
        //    指纹只要变了，那个 result 必然从 UNCHANGED 翻成 UPGRADED，照样会红。
        Set<String> skip = Set.of("id", "origin_id", "created_at", "updated_at", "archived_at",
                "row_fingerprint", axisColumn.toLowerCase());
        List<String> cols = columnsOf(table).stream().filter(c -> !skip.contains(c)).toList();
        if (cols.isEmpty()) {
            return List.of();
        }
        // 🚩 RUN_ID 归一化：轴值带 RUN_ID 之后，它会经由**引用列**渗进指纹
        //    （实测：ds_cost_detail_plating_cost.plating_scheme_no 存的就是本轮方案号），
        //    于是基线每轮都不同 —— 那是夹具身份在漏，不是核价侧被改了。
        //    这里把任意列值里出现的 RUN_ID 统一替换掉，指纹只保留真正的业务内容。
        String expr = String.join(" || '|' || ", cols.stream()
                .map(c -> "replace(coalesce(" + c + "::text,'~NULL~'), '" + RUN_ID + "', '#RUN#')")
                .toList());
        List<String> out = new ArrayList<>(strCol("SELECT md5(" + expr + ") FROM " + table
                + " WHERE " + axisColumn + " = '" + axisValue.replace("'", "''") + "'"));
        out.sort(String::compareTo);
        return out;
    }

    /** 一整个数据集（主表 + history）的逐行 md5 快照。key = 表名。 */
    Map<String, List<String>> datasetDigest(String tablePrefixLike, String axisColumn, String axisValue) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (String t : tablesLike(tablePrefixLike)) {
            List<String> d = rowDigests(t, axisColumn, axisValue);
            if (!d.isEmpty()) {
                m.put(t, d);
            }
        }
        return m;
    }

    // ============================ 清理（一律精确值，不用前缀 LIKE） ============================

    void cleanupFixtures() {
        QuarkusTransaction.requiringNew().run(() -> {
            for (String t : dsQuoteTables()) {
                if (columnExists(t, "material_no")) {
                    em.createNativeQuery("DELETE FROM " + t + " WHERE material_no IN (:a,:b)")
                            .setParameter("a", AXIS).setParameter("b", AXIS_OTHER).executeUpdate();
                }
                if (columnExists(t, "scheme_no")) {
                    em.createNativeQuery("DELETE FROM " + t + " WHERE scheme_no = :s")
                            .setParameter("s", SCHEME_NO).executeUpdate();
                }
                if (columnExists(t, "customer_product_no")) {
                    em.createNativeQuery("DELETE FROM " + t + " WHERE customer_product_no IN (:a,:b)")
                            .setParameter("a", CPN_A).setParameter("b", CPN_B).executeUpdate();
                }
            }
            for (String t : tablesLike("ds\\_cost\\_%")) {
                if (columnExists(t, "production_no")) {
                    em.createNativeQuery("DELETE FROM " + t + " WHERE production_no = :a")
                            .setParameter("a", COST_AXIS).executeUpdate();
                }
                if (columnExists(t, "scheme_no")) {
                    em.createNativeQuery("DELETE FROM " + t + " WHERE scheme_no = :s")
                            .setParameter("s", COST_SCHEME_NO).executeUpdate();
                }
            }
            // 占号表：报价导入会给客户料号占号，属本轮自建，按完整值精确删
            if (tableExists("material_customer_map")) {
                em.createNativeQuery("DELETE FROM material_customer_map WHERE customer_product_no IN (:a,:b)"
                                + " OR material_no IN (:m1,:m2)")
                        .setParameter("a", CPN_A).setParameter("b", CPN_B)
                        .setParameter("m1", AXIS).setParameter("m2", AXIS_OTHER).executeUpdate();
            }
            if (tableExists("sel_product_no")) {
                em.createNativeQuery("DELETE FROM sel_product_no WHERE customer_product_no IN (:a,:b)")
                        .setParameter("a", CPN_A).setParameter("b", CPN_B).executeUpdate();
            }
            // AC-1 的 V6 递归夹具（短前缀，值全由本轮生成）
            if (tableExists("material_bom_item")) {
                em.createNativeQuery("DELETE FROM material_bom_item WHERE material_no LIKE :p"
                                + " OR component_no LIKE :p")
                        .setParameter("p", SHORT + "%").executeUpdate();
            }
        });
    }

    /**
     * 残留自检。
     * <b>守卫不能在被守卫对象的下游</b>：先确认「表清单非空」，再谈「表里没有我的行」——
     * 表一张都扫不到时，0 残留是恒真的假绿。
     */
    void assertResidueFree(String when) {
        List<String> quoteTables = dsQuoteTables();
        assertFalse(quoteTables.isEmpty(),
                when + " 残留自检：information_schema 里一张 ds_quote_% 表都没扫到，"
                        + "「残留 = 0」是恒真的空验证，先确认连的是哪个库。");
        List<String> dirty = new ArrayList<>();
        for (String t : quoteTables) {
            if (columnExists(t, "material_no")) {
                long n = count("SELECT count(*) FROM " + t + " WHERE material_no IN ('" + AXIS + "','"
                        + AXIS_OTHER + "')");
                if (n > 0) {
                    dirty.add(t + "=" + n);
                }
            }
            if (columnExists(t, "scheme_no")) {
                long n = count("SELECT count(*) FROM " + t + " WHERE scheme_no = '" + SCHEME_NO + "'");
                if (n > 0) {
                    dirty.add(t + "(scheme)=" + n);
                }
            }
        }
        for (String t : tablesLike("ds\\_cost\\_%")) {
            if (columnExists(t, "production_no")) {
                long n = count("SELECT count(*) FROM " + t + " WHERE production_no = '" + COST_AXIS + "'");
                if (n > 0) {
                    dirty.add(t + "=" + n);
                }
            }
            if (columnExists(t, "scheme_no")) {
                long n = count("SELECT count(*) FROM " + t + " WHERE scheme_no = '" + COST_SCHEME_NO + "'");
                if (n > 0) {
                    dirty.add(t + "(scheme)=" + n);
                }
            }
        }
        if (tableExists("material_bom_item")) {
            long n = count("SELECT count(*) FROM material_bom_item WHERE material_no LIKE '" + SHORT
                    + "%' OR component_no LIKE '" + SHORT + "%'");
            if (n > 0) {
                dirty.add("material_bom_item=" + n);
            }
        }
        assertTrue(dirty.isEmpty(), when + " 夹具残留未清干净（这不是业务缺陷，是清理面没盖住）：" + dirty);
    }

    // ============================ 断言小工具 ============================

    /** 先证明集合非空，再断言集合里每个元素合格 —— 顺序反了就是「守卫在被守卫对象下游」。 */
    static void assertNonEmptyThenAllEqual(List<String> actual, String expected, String what) {
        assertFalse(actual.isEmpty(), what + "：取到 0 行，「全部等于 " + expected
                + "」是恒真的空验证（假绿）。先确认前置数据真的写进去了。");
        Set<String> distinct = new LinkedHashSet<>(actual);
        assertEquals(Set.of(expected), distinct,
                what + "：期望全部等于 " + expected + "，实际取值集合 = " + distinct
                        + "（共 " + actual.size() + " 行）");
    }
}
