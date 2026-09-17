package com.cpq.task260916;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.path.json.config.JsonPathConfig;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-260916「元素价格支持 9 位小数」· <b>测试分片 S-API（私有写片，{@code cpq_db_test}）</b>公共基座。
 *
 * <h3>本片认领</h3>
 * AC-3（取价第 10 位舍入）、AC-10（直调价格接口按 9 位存）、AC-11（策略系数/加价按 9 位存）；
 * 辅助用例 T-API-04~08；还原实验 RX-1。
 * 断言来源只有 {@code 需求文档.md §③} 原文、{@code api.md §2/§3}、{@code test.md §2/§3}，
 * 以及 task-0722 / update-0724 的接口契约文档（取请求体形状）。
 * 🚫 未读 {@code cpq-backend/src/main/java/**}、迁移目录、{@code cpq-frontend/src/**}。
 *
 * <h3>共库纪律（{@code testing.md §4.5}）</h3>
 * <ul>
 *   <li>客户编号前缀 {@link #CUST_PREFIX}、价格源名前缀 {@link #SRC_PREFIX}，再拼本轮随机 {@link #RUN}；</li>
 *   <li>元素只用库里已有且启用的 Cu / Zn / Ni，<b>只在自造源下写日价</b>；不写 {@code element} 表；</li>
 *   <li>🚫 不写全局计数；列表类断言一律先按自造源 / 自造客户过滤；</li>
 *   <li>每个用例 {@code finally} 调 {@link #cleanup(Fixture)}：每条 DELETE 都被「自造客户号精确值」
 *       或「自造源 id 精确值」限死，删完做残留自检并打印。</li>
 * </ul>
 *
 * <h3>本片写入面</h3>
 * <pre>
 *   customer                              code        = T916-API-*（本轮）
 *   element_price_source                  id          = 本轮自造（名 T916-API-SRC-*）
 *   element_daily_price / _log            source_id   = 本轮自造源
 *   element_price_strategy / _log         customer_no = 本轮自造客户
 *   customer_price_adjust_strategy(+级联) customer_no = 本轮自造客户（仅 T-API-08）
 *   customer_price_adjust_strategy_log    customer_no = 本轮自造客户（仅 T-API-08）
 *   element_price_version(+级联 item)     customer_no = 本轮自造客户（仅 T-API-08）
 *   material_price_review / _update_job / _version_ref  customer_no = 本轮自造客户（T-API-08 兜底清理，预期 0 行）
 * </pre>
 */
abstract class T916ApiBase {

    static final String CUST_PREFIX = "T916-API-";
    static final String SRC_PREFIX = "T916-API-SRC-";
    /** 本轮随机后缀：防上一轮残留冒充本轮数据。 */
    static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();

    static final String EP = "/api/cpq/element-price";

    @Inject
    EntityManager em;

    // ═══════════════════ 会话（静态缓存：登录带 Redis 限流 30 次/分/IP） ═══════════════════

    private static Map<String, String> ADMIN_COOKIES;

    /**
     * admin（SYSTEM_ADMIN）会话，RBAC 保持 test profile 默认（开启）——
     * 价格维护 / 策略 / 调价三组端点的权限矩阵都含 SYSTEM_ADMIN。
     * 🚫 不改 user 表；登录失败 = 环境未就绪，全部 AC 记「未验证」报主线。
     */
    protected static Map<String, String> adminCookies() {
        if (ADMIN_COOKIES == null) {
            Response r = RestAssured.given().contentType(ContentType.JSON)
                    .body(Map.of("username", "admin", "password", "Admin@2026"))
                    .post("/api/cpq/auth/login").thenReturn();
            if (r.statusCode() != 200 || r.getCookies().isEmpty()) {
                throw new AssertionError("🔴【环境未就绪，非产品缺陷】admin 登录返 " + r.statusCode()
                        + " body=" + r.asString()
                        + "\n  依次排查：① Redis 登录限流；② admin 被置 INACTIVE / locked_until；③ 口令变更。"
                        + "\n  🚫 本片不自行改 user 表，本类 AC 记【未验证】，请报主线。");
            }
            ADMIN_COOKIES = new LinkedHashMap<>(r.getCookies());
        }
        return ADMIN_COOKIES;
    }

    protected static RequestSpecification asAdmin() {
        return RestAssured.given().cookies(adminCookies()).contentType(ContentType.JSON);
    }

    protected static RequestSpecification asAdminMultipart() {
        return RestAssured.given().cookies(adminCookies());
    }

    /** 🚨 假绿守卫：被鉴权 / 路由挡在业务层之外时，任何「非 200」类断言都会照样通过。 */
    protected static void assertReachedBusinessLayer(Response r, String when) {
        if (r.statusCode() == 401 || r.statusCode() == 403) {
            throw new AssertionError(when + "：admin 请求被鉴权拦下（" + r.statusCode()
                    + "）—— harness 故障，不是 AC 结论。body=" + r.asString());
        }
        assertFalse(r.statusCode() == 404 && r.asString().contains("RESTEASY"),
                when + "：端点 404（路由未命中）⇒ 路径与 api.md 不一致。body=" + r.asString());
        assertFalse(r.statusCode() == 405, when + "：405 ⇒ HTTP 方法与 api.md 不一致。body=" + r.asString());
    }

    protected static void assertStatus(Response r, int expected, String when) {
        assertReachedBusinessLayer(r, when);
        assertEquals(expected, r.statusCode(), when + "：HTTP 状态码不符。body=" + r.asString());
    }

    /** 数字一律按 BigDecimal 取，避免 JSON number 走 float/double 丢精度造成的假红 / 假绿。 */
    protected static JsonPath json(Response r) {
        return r.jsonPath(JsonPathConfig.jsonPathConfig()
                .numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));
    }

    // ═══════════════════ 数值断言 ═══════════════════

    /** 接受十进制字符串或 BigDecimal；null 直接判失败（防「字段不存在 ⇒ 空比较」）。 */
    protected static BigDecimal dec(Object v, String what) {
        assertNotNull(v, what + "：值为 null（字段缺失或未返回）⇒ 断言无法执行");
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v).trim());
    }

    /**
     * 「数值等于」：{@code compareTo == 0}（{@code 3.12345679} 与 {@code 3.123456790000} 视为相等），
     * 并打印实际值原文与其 Java 类型，便于报告逐字引用。
     */
    protected static void assertNumEq(String expected, Object actual, String what) {
        BigDecimal a = dec(actual, what);
        System.out.println("[S-API] " + what + " 实际=" + actual
                + "（" + actual.getClass().getSimpleName() + "）期望数值=" + expected);
        assertEquals(0, new BigDecimal(expected).compareTo(a),
                what + "：期望数值 " + expected + "，实际 " + actual);
    }

    // ═══════════════════ SQL 小工具 ═══════════════════

    private Query bind(String sql, Object... params) {
        Query q = em.createNativeQuery(sql);
        for (int i = 0; i < params.length; i++) {
            q.setParameter(i + 1, params[i]);
        }
        return q;
    }

    protected long count(String sql, Object... params) {
        return QuarkusTransaction.requiringNew().call(
                () -> ((Number) bind(sql, params).getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    protected List<Object[]> rows(String sql, Object... params) {
        return QuarkusTransaction.requiringNew().call(() -> (List<Object[]>) bind(sql, params).getResultList());
    }

    protected Object scalar(String sql, Object... params) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> r = bind(sql, params).getResultList();
            return r.isEmpty() ? null : r.get(0);
        });
    }

    protected int exec(String sql, Object... params) {
        return QuarkusTransaction.requiringNew().call(() -> bind(sql, params).executeUpdate());
    }

    // ═══════════════════ 造数 ═══════════════════

    /** 一个用例造出的全部私有对象。 */
    protected static final class Fixture {
        final String customerNo;
        UUID sourceId;
        String sourceName;

        Fixture(String customerNo) {
            this.customerNo = customerNo;
        }
    }

    /** 自造客户（SQL 直插；策略接口要求客户号在 customer 表存在）。 */
    protected Fixture newCustomer(String label) {
        String code = CUST_PREFIX + label + "-" + RUN;
        assertTrue(code.length() <= 50, "客户编号超长：" + code);
        int n = exec("INSERT INTO customer (id, code, name, level, status, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), ?1, ?2, 'STANDARD', 'ACTIVE', NOW(), NOW())",
                code, "T916 S-API 测试客户 " + label);
        assertEquals(1, n, "造客户应插入 1 行");
        System.out.println("[S-API 造数] customer " + code);
        return new Fixture(code);
    }

    /** 自造价格源（走接口，响应为裸 DTO）。 */
    protected void newSource(Fixture fx, String label) {
        String name = SRC_PREFIX + label + "-" + RUN;
        Response r = asAdmin()
                .body(Map.of("sourceName", name, "sourceUrl", "https://t916.example/" + label + "/" + RUN))
                .post(EP + "/sources").thenReturn();
        assertStatus(r, 200, "建价格源 " + name);
        fx.sourceId = UUID.fromString(r.jsonPath().getString("id"));
        fx.sourceName = name;
        System.out.println("[S-API 造数] element_price_source " + name + " id=" + fx.sourceId);
    }

    /**
     * SQL 直插日价（用于「超过 9 位小数的原始日价」—— 这种值走任何写接口都会先被舍入，
     * 只能直插；列本身是 numeric(26,12)，容得下 10 位）。
     */
    protected void insertDailyPrice(Fixture fx, String elementCode, LocalDate date, String rawPrice) {
        assertNotNull(fx.sourceId, "日价必须挂在自造源下");
        int n = exec("INSERT INTO element_daily_price (id, element_name, source_id, price_date, raw_price, "
                + "currency, price_unit, fetch_status, created_at, updated_at) "
                + "VALUES (gen_random_uuid(), ?1, ?2, ?3, CAST(?4 AS numeric), 'CNY', 'kg', 'IMPORT', NOW(), NOW())",
                elementCode, fx.sourceId, date, rawPrice);
        assertEquals(1, n);
        Object back = scalar("SELECT raw_price FROM element_daily_price WHERE source_id = ?1 "
                + "AND element_name = ?2 AND price_date = ?3", fx.sourceId, elementCode, date);
        // 前置自证：库里确实存的是 10 位原值，否则 AC-3「第 10 位舍入」是空验证
        assertNumEq(rawPrice, back, "前置：直插日价回读 " + elementCode + "@" + date);
    }

    /** 默认策略（走接口）。factor / premium 按契约传十进制字符串。 */
    protected Response putDefaultStrategy(Fixture fx, String method, String factor, String premium) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerNo", fx.customerNo);
        body.put("sourceId", fx.sourceId.toString());
        body.put("method", method);
        body.put("factor", factor);
        body.put("premium", premium);
        return asAdmin().body(body).put(EP + "/strategies/default").thenReturn();
    }

    protected Response postException(Fixture fx, String elementCode, String factor, String premium) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerNo", fx.customerNo);
        body.put("elementCode", elementCode);
        body.put("sourceId", fx.sourceId.toString());
        body.put("method", "LATEST");
        body.put("factor", factor);
        body.put("premium", premium);
        return asAdmin().body(body).post(EP + "/strategies/exceptions").thenReturn();
    }

    protected Response putException(Fixture fx, String id, String elementCode, String factor, String premium) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerNo", fx.customerNo);
        body.put("elementCode", elementCode);
        body.put("sourceId", fx.sourceId.toString());
        body.put("method", "LATEST");
        body.put("factor", factor);
        body.put("premium", premium);
        return asAdmin().body(body).put(EP + "/strategies/exceptions/" + id).thenReturn();
    }

    protected Response postPrice(Fixture fx, String elementCode, LocalDate date, String price) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("elementCode", elementCode);
        body.put("sourceId", fx.sourceId.toString());
        body.put("priceDate", date.toString());
        body.put("price", price);
        body.put("currency", "CNY");
        body.put("priceUnit", "kg");
        return asAdmin().body(body).post(EP + "/prices").thenReturn();
    }

    protected Response putPrice(String id, String price) {
        return asAdmin().body(Map.of("price", price, "currency", "CNY", "priceUnit", "kg"))
                .put(EP + "/prices/" + id).thenReturn();
    }

    /** 取价函数：返回 elementCode → unit_price（原始 BigDecimal）。 */
    protected Map<String, Object> customerElementPrice(Fixture fx, LocalDate baseDate) {
        List<Object[]> rs = rows("SELECT element_code, unit_price FROM f_customer_element_price(?1, ?2) "
                + "ORDER BY element_code", fx.customerNo, baseDate);
        Map<String, Object> m = new LinkedHashMap<>();
        for (Object[] r : rs) {
            m.put(String.valueOf(r[0]), r[1]);
        }
        System.out.println("[S-API] f_customer_element_price('" + fx.customerNo + "', " + baseDate + ") = " + m);
        return m;
    }

    // ═══════════════════ 清理 ═══════════════════

    @FunctionalInterface
    protected interface Body {
        void run() throws Exception;
    }

    /**
     * 用例执行器：body 跑完（无论成败）都在 finally 里清理 fixtures。
     * 主失败优先抛出，清理失败挂到 suppressed —— 避免清理断言把真正的 AC 失败盖掉。
     */
    protected void withCleanup(List<Fixture> fixtures, Body body) throws Exception {
        Throwable primary = null;
        try {
            body.run();
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            Throwable cleanupErr = null;
            for (Fixture fx : fixtures) {
                try {
                    cleanup(fx);
                } catch (Throwable t) {
                    if (cleanupErr == null) {
                        cleanupErr = t;
                    } else {
                        cleanupErr.addSuppressed(t);
                    }
                }
            }
            if (cleanupErr != null) {
                if (primary != null) {
                    primary.addSuppressed(cleanupErr);
                } else if (cleanupErr instanceof Exception e) {
                    throw e;
                } else {
                    throw (Error) cleanupErr;
                }
            }
        }
    }

    /**
     * 按「自造客户号精确值 / 自造源 id 精确值」清理，删完做残留自检。
     * 每条 DELETE 前先校验键值带本片前缀，杜绝空值 / 别片值扩大命中面。
     */
    protected void cleanup(Fixture fx) {
        if (fx == null) {
            return;
        }
        String c = fx.customerNo;
        assertTrue(c != null && c.startsWith(CUST_PREFIX), "清理键不是本片客户号：" + c);
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("material_price_update_job_item", exec("DELETE FROM material_price_update_job_item WHERE job_id IN "
                + "(SELECT id FROM material_price_update_job WHERE customer_no = ?1)", c));
        deleted.put("material_price_update_job", exec("DELETE FROM material_price_update_job WHERE customer_no = ?1", c));
        deleted.put("material_price_review", exec("DELETE FROM material_price_review WHERE customer_no = ?1", c));
        deleted.put("material_price_version_ref", exec("DELETE FROM material_price_version_ref WHERE customer_no = ?1", c));
        deleted.put("element_price_version(+item)", exec("DELETE FROM element_price_version WHERE customer_no = ?1", c));
        deleted.put("customer_price_adjust_strategy(+级联)",
                exec("DELETE FROM customer_price_adjust_strategy WHERE customer_no = ?1", c));
        deleted.put("customer_price_adjust_strategy_log",
                exec("DELETE FROM customer_price_adjust_strategy_log WHERE customer_no = ?1", c));
        deleted.put("element_price_strategy_log", exec("DELETE FROM element_price_strategy_log WHERE customer_no = ?1", c));
        deleted.put("element_price_strategy", exec("DELETE FROM element_price_strategy WHERE customer_no = ?1", c));
        if (fx.sourceId != null) {
            UUID s = fx.sourceId;
            deleted.put("element_daily_price_log", exec("DELETE FROM element_daily_price_log WHERE source_id = ?1", s));
            deleted.put("element_daily_price", exec("DELETE FROM element_daily_price WHERE source_id = ?1", s));
            deleted.put("element_price_source", exec("DELETE FROM element_price_source WHERE id = ?1 "
                    + "AND source_name LIKE 'T916-API-SRC-%'", s));
        }
        deleted.put("customer", exec("DELETE FROM customer WHERE code = ?1 AND code LIKE 'T916-API-%'", c));
        System.out.println("[S-API 清理] " + c + " / " + fx.sourceName + " 删除行数 = " + deleted);

        long residue = count("SELECT "
                + "(SELECT count(*) FROM customer WHERE code = ?1)"
                + "+(SELECT count(*) FROM element_price_strategy WHERE customer_no = ?1)"
                + "+(SELECT count(*) FROM element_price_strategy_log WHERE customer_no = ?1)"
                + "+(SELECT count(*) FROM element_price_version WHERE customer_no = ?1)"
                + "+(SELECT count(*) FROM customer_price_adjust_strategy WHERE customer_no = ?1)"
                + "+(SELECT count(*) FROM customer_price_adjust_strategy_log WHERE customer_no = ?1)"
                + "+(SELECT count(*) FROM material_price_update_job WHERE customer_no = ?1)"
                + "+(SELECT count(*) FROM material_price_review WHERE customer_no = ?1)"
                + "+(SELECT count(*) FROM material_price_version_ref WHERE customer_no = ?1)", c);
        long srcResidue = fx.sourceId == null ? 0 : count("SELECT "
                + "(SELECT count(*) FROM element_price_source WHERE id = ?1)"
                + "+(SELECT count(*) FROM element_daily_price WHERE source_id = ?1)"
                + "+(SELECT count(*) FROM element_daily_price_log WHERE source_id = ?1)", fx.sourceId);
        System.out.println("[S-API 清理自检] 客户侧残留=" + residue + "（应 0） 源侧残留=" + srcResidue + "（应 0）");
        assertEquals(0L, residue + srcResidue, "清理后仍有残留，见上方日志");
    }
}
