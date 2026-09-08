package com.cpq.task260907productfilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260907「产品管理客户过滤」验收测试公共基座。
 *
 * <h3>断言来源</h3>
 * 每条断言指回 {@code dev-docs/task-260907-产品管理客户过滤/需求文档.md §③} 的 AC 原文，
 * 请求结构指回同目录 {@code api.md}。
 * <b>🚫 本套用例不读实现代码</b>（不读 {@code cpq-backend/src/main/java/com/cpq/dataset/**}），
 * 只读立项文档、库 schema、库里的数据、既有测试代码（{@code com.cpq.task260902.*} / {@code com.cpq.task260907.*}）。
 *
 * <h3>🚨 本任务最大的测试风险（test.md §0）</h3>
 * 现网 {@code ds_quote_material} 49 行 49 个不同 {@code material_no}，同料号跨客户在现网造不出来 ⇒
 * 隔离类用例必须<b>自造前缀化夹具</b>，且断言前先断言「结果非空」，每条隔离断言配一个阳性对照。
 * 见各子类。
 *
 * <h3>🚨 外部依赖门（最重要的一条前置纪律）</h3>
 * 本任务全部「客户过滤」能力都建立在 {@code task-260907-报价侧加客户维度} 的 DDL 之上
 * （28 张 {@code ds_quote_*} 表加 {@code customer_no}、{@code uq_ds_quote_material} 扩为复合唯一索引）。
 * 2026-09-07 立项时点该 DDL **尚未合并 master**，一个字段都没落共享库。
 * ⇒ 依赖它的用例一律先 {@link Assumptions#assumeTrue}，命中门槛没开就<b>干净跳过</b>（JUnit 报 skipped，
 * 不是 passed，也不是 failed）——跳过 ≠ 通过，是「尚不能判定」，避免制造假绿或因 schema 缺列而
 * 抛出难看的 SQL 异常掩盖真实断言。
 * <p>不依赖该 DDL 的两条例外（{@code ds_quote_customer_part} 早就有 {@code customer_no}）：
 * <b>AC-2 候选并集</b>与<b>AC-14①客户产品过滤</b>——这两条不设门槛，应当能在今天就跑。
 *
 * <h3>🚨 共享库红线（{@code CLAUDE.md} §3.2 + test.md 夹具纪律）</h3>
 * <ul>
 *   <li>🚫 全套用例<b>不含</b> {@code TRUNCATE} / {@code DROP} / 无 {@code WHERE} 的 {@code DELETE}；</li>
 *   <li>🚨 <b>清理一律按主键或完整值精确匹配</b>，🚫 <b>禁止 {@code LIKE 'T260907%'}</b>
 *       ——那会把并发三线（{@code T260907B-}/{@code T260907Q-}/{@code T260907T-}）的夹具一起删掉，
 *       症状是随机挂且极像业务回归。本类的清理只对 {@link #registerCleanup} 登记过的「表+列+精确值」生效；</li>
 *   <li>本套用例的唯一前缀是 {@link #FX}（{@code T260907M-}），且只用于<b>本套自建的行</b>，
 *       不批量按前缀删除。</li>
 * </ul>
 */
public abstract class PfTestBase {

    /** 本套用例唯一的夹具值前缀（M = Maintenance / 测试工程师线，test.md 分配）。 */
    static final String FX = "T260907M-";

    // ── 真实存在的客户（test.md §「客户取值」，2026-09-07 实查） ──
    static final String CUST_A = "CUST-0001"; // 罗克韦尔
    static final String CUST_B = "CUST-0004"; // 正泰
    /** 已在业务表出现但未建档的客户号（现网真实存在，不需要造）。 */
    static final String CUST_UNREG_1 = "C1";
    static final String CUST_UNREG_2 = "Q13CUST0617";
    /** 明确不存在于 candidate 集合、也不应出现在任何业务表里的客户号（长度 &lt;=20，避免撞列宽）。 */
    static final String CUST_ABSENT = "T260907M-NOPE";

    @Inject
    protected EntityManager em;

    private static String cachedSession;

    /** 本轮自建、待精确清理的 {@code (table, column, value)} 三元组队列。 */
    private final List<String[]> cleanupQueue = new ArrayList<>();

    // ═══════════════════════ 生命周期 ═══════════════════════

    @AfterEach
    void tearDownFixtures() {
        // 逆序删，避免子表未清先清父表导致外键/业务一致性问题（本任务用到的表都无强 FK，逆序仍是稳妥习惯）。
        for (int i = cleanupQueue.size() - 1; i >= 0; i--) {
            String[] e = cleanupQueue.get(i);
            String table = e[0], column = e[1], value = e[2];
            if (!tableExists(table) || !columnExists(table, column)) {
                continue;
            }
            QuarkusTransaction.requiringNew().run(() ->
                    em.createNativeQuery("DELETE FROM " + table + " WHERE " + column + " = :v")
                            .setParameter("v", value)
                            .executeUpdate());
        }
        cleanupQueue.clear();
    }

    /**
     * 登记一条「精确删除」清理指令：{@code DELETE FROM table WHERE column = value}。
     * 🚨 只接受精确值，不接受通配符 —— 方法签名上就不给 LIKE 留口子。
     */
    protected void registerCleanup(String table, String column, String value) {
        cleanupQueue.add(new String[]{table, column, value});
    }

    // ═══════════════════════ 外部依赖门 ═══════════════════════

    /**
     * 28 张表的 DDL 是否已落共享库。用 {@code ds_quote_material.customer_no} 作探针
     * ——它是 api.md 里全部隔离类 AC（AC-3/5/6/7/8/11/16/17）唯一直接依赖的列。
     */
    protected boolean materialHasCustomerNo() {
        return columnExists("ds_quote_material", "customer_no");
    }

    /** 物料表唯一索引是否已扩为 {@code (customer_no, material_no)}（同料号跨客户能否插得进）。 */
    protected boolean materialUniqueIndexIsComposite() {
        Long n = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM pg_index i "
                        + "JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE c.relname = 'uq_ds_quote_material' AND i.indnkeyatts > 1")
                .getSingleResult()).longValue();
        return n > 0;
    }

    /**
     * 依赖门：本任务的「客户候选新端点」是否已实现（不依赖外部 DDL，B-1 自身范围）。
     * 用一次探测请求判断，而不是硬编码「一定还没做」——B-1 按需求文档「刚在实现」，
     * 写用例这一刻可能已经落地，用探测让用例自己感知现实，而不是靠人记着改。
     */
    protected boolean candidatesEndpointExists() {
        Response r = PfApi.customers(adminSession(), PfApi.QUOTE);
        return r.statusCode() != 404;
    }

    /** 依赖门断言：命中门槛没开就干净跳过，附清楚的理由（不是「测试写错了」）。 */
    protected void assumeMaterialCustomerDimensionReady(String acLabel) {
        Assumptions.assumeTrue(materialHasCustomerNo(),
                acLabel + "：外部依赖未满足 —— task-260907-报价侧加客户维度 的 28 张表 customer_no DDL "
                        + "尚未合并 master / 落共享库（backtask.md「开工前置」）。此用例暂不可执行，跳过而非判定。");
    }

    protected void assumeMaterialUniqueIndexComposite(String acLabel) {
        assumeMaterialCustomerDimensionReady(acLabel);
        Assumptions.assumeTrue(materialUniqueIndexIsComposite(),
                acLabel + "：uq_ds_quote_material 仍是单列唯一索引 —— 同料号跨客户插不进第二行，"
                        + "本用例的夹具造不出来，跳过而非判定。");
    }

    // ═══════════════════════ session（沿用 task260902 的既有踩坑规避） ═══════════════════════

    /**
     * 取 admin 的 {@code CPQ_SESSION}。
     * 🚨 <b>静态</b>缓存：登录带 Redis 限流（30 次/分/IP），每条用例各登录一次必然撞限流，
     * 症状是「从某一条起全部 401」——长得像鉴权坏了，其实是自己打的（{@code task260902.DatasetAcTestBase} 同款注释）。
     */
    protected String adminSession() {
        if (cachedSession == null) {
            cachedSession = login("admin", "Admin@2026");
            assertNotNull(cachedSession, "admin 登录未拿到 CPQ_SESSION —— 先查 admin 是否被 E2E 置成 INACTIVE");
        }
        return cachedSession;
    }

    protected String login(String username, String password) {
        Response r = RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                .when().post("/api/cpq/auth/login");
        if (r.statusCode() != 200) {
            throw new AssertionError("登录失败：" + username + " → HTTP " + r.statusCode()
                    + "\n  响应体：" + r.asString()
                    + "\n  ⚠️ 依次排查：① Redis 登录限流（30 次/分/IP）；② admin 被 E2E 置成 INACTIVE；③ 账号锁定。");
        }
        return r.cookie("CPQ_SESSION");
    }

    // ═══════════════════════ 只读 SQL 助手 ═══════════════════════

    protected long count(String sql) {
        Object v = em.createNativeQuery(sql).getSingleResult();
        return ((Number) v).longValue();
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    @SuppressWarnings("unchecked")
    protected List<Object> col(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }

    protected boolean tableExists(String table) {
        return count("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename='" + table + "'") > 0;
    }

    protected boolean columnExists(String table, String column) {
        return count("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='"
                + table + "' AND column_name='" + column + "'") > 0;
    }

    /** 全部带 {@code customer_no} 列的 {@code ds_quote_*} 表名（AC-2 判据 SQL 用，动态推导不写死表名）。 */
    @SuppressWarnings("unchecked")
    protected List<String> tablesWithCustomerNo() {
        List<Object> names = col("SELECT table_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND column_name='customer_no' AND table_name LIKE 'ds\\_quote\\_%' "
                + "ORDER BY 1");
        List<String> out = new ArrayList<>();
        for (Object o : names) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    // ═══════════════════════ md5 / 基线比对（AC-17/AC-18 反向用） ═══════════════════════

    protected static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(s.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** 基线文件目录：任务目录下 {@code 证据/baseline/}，随任务提交，不落 {@code target/}。 */
    protected static File baselineDir() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParentFile()) {
            File c = new File(dir, "dev-docs/task-260907-产品管理客户过滤/证据/baseline");
            if (c.isDirectory()) {
                return c;
            }
        }
        throw new IllegalStateException("找不到任务目录 dev-docs/task-260907-产品管理客户过滤/证据/baseline，"
                + "cwd=" + System.getProperty("user.dir"));
    }

    /**
     * 🚨 A/B 基线比对（test.md §4 手法的落地）。
     *
     * <p><b>第一次调用</b>（基线文件不存在）：把 {@code current} 落盘为基线并<b>让用例失败</b>——
     * 不是让它悄悄通过。「刚捕获了一份基线」和「已经比对过一致」是两件完全不同的事，
     * 如果第一次调用就返回绿色，会被误读成「AC-17 已验证」，而实际上从未发生过一次真正的比较。
     * <b>第二次调用起</b>（基线文件已存在）：与基线做 md5 比对，不一致则列出差异并失败。
     *
     * @param key     基线文件名（不含扩展名），建议 {@code "AC17-parts-noCustomerNo"} 这类可读名
     * @param current 本次实际响应体（原始字符串，通常是 {@code response.asString()}）
     * @param because 失败信息的前缀说明，指回 AC 编号
     */
    protected void assertByteIdenticalToBaseline(String key, String current, String because) {
        assertFalse(current == null || current.isBlank(), because + "：当前响应为空 ⇒ 比对无意义（假绿风险）");
        File f = new File(baselineDir(), key + ".baseline.txt");
        if (!f.isFile()) {
            try {
                Files.writeString(f.toPath(), current, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError(because + "：写基线文件失败 " + f + " → " + e, e);
            }
            throw new AssertionError(because + "：基线文件不存在，本次调用已将当前响应捕获为 A 侧基线 → " + f
                    + "\n🚨 这不构成「验证通过」——请在确认这份基线确实采于『本任务改动之前』后，"
                    + "重新执行本用例做真正的比对（比对逻辑在下一次调用才会跑）。"
                    + "\n若这份基线其实采于『改动之后』，删掉该文件并在 dependency 满足、代码未改动前重采。");
        }
        String baseline;
        try {
            baseline = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(because + "：读基线文件失败 " + f + " → " + e, e);
        }
        assertFalse(baseline.isBlank(), because + "：基线文件为空 ⇒ 比对会退化成空跑（假绿）。文件=" + f);
        String am5 = md5(baseline), bm5 = md5(current);
        System.out.println("[" + key + "] A(md5=" + am5 + ") vs B(md5=" + bm5 + ")");
        assertTrue(am5.equals(bm5), because + "：与 A 侧基线不逐字一致。\n  A(md5=" + am5 + "):\n" + baseline
                + "\n  B(md5=" + bm5 + "):\n" + current);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 🚨 <b>D-12 判据</b>（AC-17 与 AC-5② 矛盾的裁决落地）：AC-17 真正要保的是<b>过滤行为没变</b>，
     * 不是<b>字节没变</b> —— 换判据对象而不是打豁免补丁。
     *
     * <p>比对三件事：① {@code data.total} 与改动前完全一致；② 返回的行集合（{@code axisValue} 全集）
     * 与改动前完全一致；③ 交集内每个 {@code axisValue}，剔除 {@code ignoredKeys} 后其余键值逐字未变。
     * {@code ignoredKeys} 传本次契约新增的键（{@code customerNo}/{@code customerName}），它们不参与比对。
     *
     * <p>第一次调用（基线文件不存在）：捕获当前响应为基线并<b>让用例失败</b>——理由同
     * {@link #assertByteIdenticalToBaseline}：捕获动作本身不构成一次真正的比较。
     */
    @SuppressWarnings("unchecked")
    protected void assertPartsRowSetAndKeysUnchanged(String baselineKey, Response current, Set<String> ignoredKeys,
                                                      String because) {
        Map<String, Object> body;
        try {
            body = JSON.readValue(current.asString(), Map.class);
        } catch (Exception e) {
            throw new AssertionError(because + "：响应不是合法 JSON。body=" + current.asString(), e);
        }
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertNotNull(data, because + "：响应缺 data。body=" + current.asString());
        Object totalObj = data.get("total");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertNotNull(items, because + "：响应缺 data.items");
        assertFalse(items.isEmpty(), because + "：data.items 为空 ⇒ 比对退化成空集比空集（假绿）");

        Map<String, Map<String, Object>> byAxis = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> stripped = new LinkedHashMap<>(it);
            for (String k : ignoredKeys) {
                stripped.remove(k);
            }
            byAxis.put(String.valueOf(it.get("axisValue")), stripped);
        }

        File f = new File(baselineDir(), baselineKey + ".json");
        if (!f.isFile()) {
            Map<String, Object> baseline = new LinkedHashMap<>();
            baseline.put("total", totalObj);
            baseline.put("itemsByAxis", byAxis);
            try {
                JSON.writerWithDefaultPrettyPrinter().writeValue(f, baseline);
            } catch (IOException e) {
                throw new AssertionError(because + "：写基线文件失败 " + f, e);
            }
            throw new AssertionError(because + "：基线文件不存在，本次调用已将当前响应（剔除 " + ignoredKeys
                    + " 后）捕获为 A 侧基线 → " + f
                    + "\n🚨 这不构成「验证通过」——请确认这份基线确实采于『本任务改动之前』后，重新执行本用例做真正比对。");
        }

        Map<String, Object> baseline;
        try {
            baseline = JSON.readValue(f, Map.class);
        } catch (IOException e) {
            throw new AssertionError(because + "：读基线文件失败 " + f, e);
        }
        Object baseTotal = baseline.get("total");
        Map<String, Map<String, Object>> baseByAxis = (Map<String, Map<String, Object>>) baseline.get("itemsByAxis");
        assertNotNull(baseByAxis, because + "：基线文件格式异常，缺 itemsByAxis。文件=" + f);
        assertFalse(baseByAxis.isEmpty(), because + "：基线 itemsByAxis 为空 ⇒ 比对是假绿。文件=" + f);

        assertEquals(String.valueOf(baseTotal), String.valueOf(totalObj),
                because + "：D-12① total 不一致，基线=" + baseTotal + " 当前=" + totalObj);

        Set<String> baseAxes = baseByAxis.keySet();
        Set<String> nowAxes = byAxis.keySet();
        assertEquals(baseAxes, nowAxes, because + "：D-12① 行集合(axisValue)不一致。\n  基线=" + baseAxes
                + "\n  当前=" + nowAxes);

        List<String> diffs = new ArrayList<>();
        for (String axis : baseAxes) {
            Map<String, Object> b = baseByAxis.get(axis);
            Map<String, Object> n = byAxis.get(axis);
            if (!Objects.equals(b, n)) {
                diffs.add(axis + ":\n    基线=" + b + "\n    当前=" + n);
            }
        }
        assertTrue(diffs.isEmpty(), because + "：D-12② 存量键值发生了变化（新增键 " + ignoredKeys + " 已排除在比对之外）：\n"
                + String.join("\n", diffs));
    }

    /**
     * 🚨 <b>通用 JSON 响应比对（剔除易变字段）</b>——与 D-12 同一类判据修正手法，用在响应体
     * 天然含服务端时间戳等字段的场景（如 {@code updatedAt}/{@code lastUpdatedAt}）：
     * 这些字段<b>每次调用都会不同</b>，与「过滤行为有没有变」无关，若不剔除会把
     * 「这条用例本身在写数据」误判成「响应变了」。
     * <p>实证（2026-09-08）：{@code Ac17BackwardCompatTest} 的 PUT 用例把 {@code updatedAt} 计入了
     * 逐字比对，同一份夹具两次真实调用因为时间戳不同而必然报"不一致"——这本身就是
     * 「把某一时刻的快照当判据」的同一类错误，只是換了个字段而非換了个 AC。
     * <p>剔除是<b>递归</b>的（任意层级、key 名匹配即删），首次调用捕获基线并强制失败，规则同
     * {@link #assertByteIdenticalToBaseline}。
     */
    @SuppressWarnings("unchecked")
    protected void assertJsonUnchangedIgnoringKeys(String baselineKey, String currentRawJson, Set<String> ignoredKeys,
                                                    String because) {
        assertFalse(currentRawJson == null || currentRawJson.isBlank(), because + "：当前响应为空 ⇒ 比对无意义");
        Object current;
        try {
            current = JSON.readValue(currentRawJson, Object.class);
        } catch (Exception e) {
            throw new AssertionError(because + "：响应不是合法 JSON。body=" + currentRawJson, e);
        }
        Object strippedCurrent = stripKeysDeep(current, ignoredKeys);

        File f = new File(baselineDir(), baselineKey + ".json");
        if (!f.isFile()) {
            try {
                JSON.writerWithDefaultPrettyPrinter().writeValue(f, strippedCurrent);
            } catch (IOException e) {
                throw new AssertionError(because + "：写基线文件失败 " + f, e);
            }
            throw new AssertionError(because + "：基线文件不存在，本次调用已将响应（剔除 " + ignoredKeys
                    + " 后）捕获为 A 侧基线 → " + f
                    + "\n🚨 这不构成「验证通过」——请重新执行本用例做真正比对。");
        }
        Object baseline;
        try {
            baseline = JSON.readValue(f, Object.class);
        } catch (IOException e) {
            throw new AssertionError(because + "：读基线文件失败 " + f, e);
        }
        System.out.println("[" + baselineKey + "] 基线(剔除" + ignoredKeys + ")=" + baseline);
        System.out.println("[" + baselineKey + "] 当前(剔除" + ignoredKeys + ")=" + strippedCurrent);
        assertEquals(baseline, strippedCurrent, because + "：剔除 " + ignoredKeys + " 后与 A 侧基线不一致。\n  基线="
                + baseline + "\n  当前=" + strippedCurrent);
    }

    /** 递归剔除任意层级里 key 名命中 {@code keys} 的条目，用于 {@link #assertJsonUnchangedIgnoringKeys}。 */
    @SuppressWarnings("unchecked")
    private static Object stripKeysDeep(Object node, Set<String> keys) {
        if (node instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                String k = String.valueOf(e.getKey());
                if (keys.contains(k)) {
                    continue;
                }
                out.put(k, stripKeysDeep(e.getValue(), keys));
            }
            return out;
        }
        if (node instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) node) {
                out.add(stripKeysDeep(o, keys));
            }
            return out;
        }
        return node;
    }

    /** 从 GET /sheets 拿 sheetName → sheetKey（不猜实现里的枚举名，沿用 task260902 的既有手法）。 */
    protected String resolveSheetKey(String dataset, String sheetName) {
        Response r = PfApi.sheets(adminSession(), dataset);
        assertTrue(r.statusCode() == 200, "GET /dataset/" + dataset + "/sheets → " + r.statusCode()
                + " body=" + r.asString());
        List<java.util.Map<String, Object>> sheetsList = r.jsonPath().getList("data.sheets");
        assertNotNull(sheetsList, "GET /sheets 缺 data.sheets");
        assertFalse(sheetsList.isEmpty(), "GET /sheets 返回空 ⇒ 后续断言空跑");
        return sheetsList.stream()
                .filter(m -> sheetName.equals(String.valueOf(m.get("sheetName"))))
                .map(m -> String.valueOf(m.get("sheetKey")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "GET /sheets 里没有 sheetName=" + sheetName + "，实际：" + sheetsList));
    }

    // ═══════════════════════ 隔离类夹具（AC-3/5/6/7/8/16 共用） ═══════════════════════

    /**
     * 往 {@code ds_quote_material} 插一行「客户 × 料号」夹具（复合唯一索引落地后才插得进第二个客户）。
     * 🚨 调用前必须先过 {@link #assumeMaterialCustomerDimensionReady}（同料号跨客户需再过
     * {@link #assumeMaterialUniqueIndexComposite}），否则会因列不存在而抛出难看的 SQL 异常。
     * <p>自动登记精确清理（按 {@code material_no + customer_no} 复合条件，见 {@link #cleanupMaterialRow}）。
     */
    protected void insertMaterialRow(String materialNo, String customerNo, String productionNo) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("INSERT INTO ds_quote_material "
                        + "(material_no, material_name, customer_no, production_no, source, created_at) "
                        + "VALUES (:mn, :name, :cn, :pn, 'IMPORT', now())")
                        .setParameter("mn", materialNo)
                        .setParameter("name", "T260907M 测试夹具")
                        .setParameter("cn", customerNo)
                        .setParameter("pn", productionNo)
                        .executeUpdate());
        cleanupMaterialRow(materialNo, customerNo);
    }

    /** 精确清理：{@code DELETE FROM ds_quote_material WHERE material_no=? AND customer_no=?}（复合精确匹配，非 LIKE）。 */
    protected void cleanupMaterialRow(String materialNo, String customerNo) {
        materialRowCleanupQueue.add(new String[]{materialNo, customerNo});
    }

    private final List<String[]> materialRowCleanupQueue = new ArrayList<>();

    @AfterEach
    void tearDownMaterialFixtures() {
        for (int i = materialRowCleanupQueue.size() - 1; i >= 0; i--) {
            String[] e = materialRowCleanupQueue.get(i);
            if (!materialHasCustomerNo()) {
                continue;
            }
            QuarkusTransaction.requiringNew().run(() ->
                    em.createNativeQuery("DELETE FROM ds_quote_material WHERE material_no = :mn AND customer_no = :cn")
                            .setParameter("mn", e[0]).setParameter("cn", e[1])
                            .executeUpdate());
        }
        materialRowCleanupQueue.clear();
    }

    // ═══════════════════════ AC-6③ 专用：版本化子表「物料BOM」 ═══════════════════════

    /**
     * 主线 2026-09-07 核实：sheetKey {@code "MATERIAL_BOM"}（显示名「物料BOM」）对应表
     * {@code ds_quote_material_bom}，{@code SheetDef.versioned(...)}——报价侧 16 个 sheet 里
     * 13 个 versioned、3 个 unversioned（{@code MATERIAL}/{@code CUSTOMER_PART}/{@code PLATING_SCHEME}）。
     * 用它验证 AC-6③「版本号不跨客户混排」。
     * <p>🚨 实测（2026-09-07）该表尚未加 {@code customer_no} 列——外部依赖仍未落地，
     * 依赖门见 {@link #materialBomHasCustomerNo()}，未满足时调用方应 {@code assumeTrue} 跳过。
     */
    protected boolean materialBomHasCustomerNo() {
        return columnExists("ds_quote_material_bom", "customer_no");
    }

    /**
     * 往 {@code ds_quote_material_bom} 插一行「客户 × 料号 × 版本」夹具。
     * 该表以 {@code version_no} 直接落在主表（非 {@code is_current}+history 分离设计，见
     * {@code idx_ds_quote_material_bom_axis_ver btree(material_no, version_no)} 非唯一索引），
     * 故可直接插入多个 {@code version_no} 来模拟"该客户已有多版历史"。
     * {@code row_fingerprint} 为 {@code CHAR(64) NOT NULL}，用调用方传入的短串即可（Postgres 自动补空格）。
     */
    protected void insertMaterialBomRow(String materialNo, String customerNo, int versionNo, String fingerprint) {
        insertMaterialBomRow(materialNo, customerNo, 1, versionNo, fingerprint, materialNo);
    }

    /**
     * 同上，但可指定 {@code itemSeq}（同一 material_no+customer_no+version_no 下插多行时用不同 itemSeq
     * 区分）与 {@code inputMaterialNo}（「投入料号」——sheet 定义里 {@code role=VALUE, compared=true}
     * 的业务列，两个客户传不同值即可造出"内容可区分"的夹具，供 overview/rows 的隔离断言使用）。
     */
    protected void insertMaterialBomRow(String materialNo, String customerNo, int itemSeq, int versionNo,
                                         String fingerprint, String inputMaterialNo) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("INSERT INTO ds_quote_material_bom "
                        + "(material_no, item_seq, input_material_no, customer_no, version_no, row_fingerprint, "
                        + "source, created_at) "
                        + "VALUES (:mn, :seq, :input, :cn, :ver, :fp, 'IMPORT', now())")
                        .setParameter("mn", materialNo)
                        .setParameter("seq", itemSeq)
                        .setParameter("input", inputMaterialNo)
                        .setParameter("cn", customerNo)
                        .setParameter("ver", versionNo)
                        .setParameter("fp", fingerprint)
                        .executeUpdate());
        materialBomCleanupQueue.add(new Object[]{materialNo, customerNo, versionNo});
    }

    private final List<Object[]> materialBomCleanupQueue = new ArrayList<>();

    @AfterEach
    void tearDownMaterialBomFixtures() {
        for (int i = materialBomCleanupQueue.size() - 1; i >= 0; i--) {
            Object[] e = materialBomCleanupQueue.get(i);
            if (!materialBomHasCustomerNo()) {
                continue;
            }
            QuarkusTransaction.requiringNew().run(() ->
                    em.createNativeQuery("DELETE FROM ds_quote_material_bom "
                            + "WHERE material_no = :mn AND customer_no = :cn AND version_no = :ver")
                            .setParameter("mn", e[0]).setParameter("cn", e[1]).setParameter("ver", e[2])
                            .executeUpdate());
        }
        materialBomCleanupQueue.clear();
    }

    protected void assumeMaterialBomCustomerDimensionReady(String acLabel) {
        Assumptions.assumeTrue(materialBomHasCustomerNo(),
                acLabel + "：外部依赖未满足 —— ds_quote_material_bom 的 customer_no DDL 尚未合并 master / "
                        + "落共享库（task-260907-报价侧加客户维度）。此用例暂不可执行，跳过而非判定。");
    }

    /** 去重集合是否升序（AC-2 排序断言用）。 */
    protected static boolean isAscending(List<String> xs) {
        for (int i = 1; i < xs.size(); i++) {
            if (xs.get(i - 1).compareTo(xs.get(i)) > 0) {
                return false;
            }
        }
        return true;
    }

    protected static Set<String> setOf(List<String> xs) {
        return new LinkedHashSet<>(xs);
    }
}
