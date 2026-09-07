package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>T-B10 · AC-17</b>（向后兼容：不传客户号 = 不过滤）—— <b>反向 AC，A/B 基线比对</b>。
 *
 * <p>需求文档.md §③ AC-17：绕过 UI，直接以<b>不带</b> {@code customerNo} 参数调用本次改造的 5 个端点。
 * 断言：响应与改动前<b>逐字一致</b>（同一份夹具，改动前后结果逐行 md5 比对）。
 *
 * <h3>🚨 本类对 AC 原文的一处质疑（已在回报里同步给主线，这里留痕）</h3>
 * {@code api.md} §2/A-2 明确「新增响应字段（{@code customerNo}/{@code customerName}）」且未说这两个
 * 字段是否条件性出现；而 {@code overview}/{@code rows}/{@code versions} 三端点 §2 明确「响应结构不变
 * （不加字段）」。如果 A-2 的两个新字段是<b>无条件</b>随每行出现（同一 SELECT 里 JOIN 的产物，
 * 不因 {@code customerNo} 有没有传而消失），那么 {@code GET /parts} 在不传 {@code customerNo} 时的响应
 * <b>不可能</b>与改动前逐字相同——items 里每一项都会多出两个键。这与 AC-17「逐字一致」的字面表述矛盾。
 * ⇒ 本类对 {@code parts} 端点的比对<b>剔除</b> {@code customerNo}/{@code customerName} 两个键后再比对
 * （即验证「除新增的两个只读展示字段外，其余部分与改动前逐字一致」），并在断言信息里明确写出这个让步，
 * 不悄悄把它解释成「AC 就是这个意思」。{@code overview}/{@code rows}/{@code versions}/{@code PUT} 四个
 * 端点契约本就「不加字段」，按原文严格逐字比对。
 *
 * <h3>基线策略</h3>
 * 本类维护一个<b>持久化、不被清理</b>的锚点行（{@link #ANCHOR}，仿照本工程
 * {@code product-hub.helpers.ts} 的 {@code HERO_EDIT} 惯例：「本套用例唯一允许写/长期占用的料号」），
 * 首次跑通过 {@link #ensureAnchorExists()} 幂等建好；不在任何 {@code @AfterEach} 里删除它——
 * 它必须在「改动前」与「改动后」两次运行之间原样存在，否则 A/B 比对无从谈起。
 * <p>🚨 <b>基线文件一旦被 {@link PfTestBase#assertByteIdenticalToBaseline} 首次写盘，就不得重采</b>
 * ——重采 = 拿当前值当基线 = 断言退化成恒真（test.md §4 纪律 1）。若怀疑基线本身采晚了
 * （例如已经混入了本任务的改动），删掉对应 {@code .baseline.txt} 文件并在确认「改动尚未落地」的
 * 时间窗口里重新触发一次捕获。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-17 向后兼容（不传 customerNo = 不过滤，A/B 基线比对）")
@QuarkusTest
class Ac17BackwardCompatTest extends PfTestBase {

    private static final String ANCHOR = FX + "AC17ANCHOR";
    private static final String PUT_FIXED_VALUE = FX + "AC17FIXEDVALUE";

    @BeforeEach
    void ensureAnchorExists() {
        assumeMaterialCustomerDimensionReady("AC-17");
        long n = count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + ANCHOR + "'");
        if (n == 0) {
            QuarkusTransaction.requiringNew().run(() ->
                    em.createNativeQuery("INSERT INTO ds_quote_material "
                            + "(material_no, material_name, customer_no, production_no, source, created_at) "
                            + "VALUES (:mn, 'T260907M AC-17 基线锚点（长期存在，不清理）', :cn, :pn, 'IMPORT', now())")
                            .setParameter("mn", ANCHOR)
                            .setParameter("cn", CUST_A)
                            .setParameter("pn", FX + "AC17ANCHOR-PROD")
                            .executeUpdate());
            System.out.println("[AC-17] 锚点 " + ANCHOR + " 首次建立（长期存在，不会被 @AfterEach 清理）");
        }
        // 注意：ANCHOR 不通过 registerCleanup/cleanupMaterialRow 登记，因此不会被 PfTestBase 的
        // tearDown 删除——这是刻意设计，不是遗漏。
    }

    @Test
    @DisplayName("AC-17：GET /parts 不传 customerNo，剔除新增两字段后与 A 侧基线逐字一致（结构层让步已在类注释说明）")
    void partsWithoutCustomerNoUnaffected() {
        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, null, 0, 500, ANCHOR);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        String normalized = normalizeRemovingCustomerFields(r.asString());
        assertByteIdenticalToBaseline("AC17-parts-noCustomerNo", normalized,
                "AC-17(parts)：剔除 customerNo/customerName 后仍与改动前不一致，说明不传参数时的既有字段被波及了");
    }

    @Test
    @DisplayName("AC-17：GET /overview 不传 customerNo，响应与 A 侧基线逐字一致")
    void overviewWithoutCustomerNoUnaffected() {
        Response r = PfApi.overview(adminSession(), PfApi.QUOTE, ANCHOR, null);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        assertByteIdenticalToBaseline("AC17-overview-noCustomerNo", r.asString(),
                "AC-17(overview)：不传 customerNo 时响应应与改动前逐字一致（api.md §2/A-3 明确不加字段）");
    }

    @Test
    @DisplayName("AC-17：GET /sheets/{sheetKey}/rows 不传 customerNo，响应与 A 侧基线逐字一致")
    void rowsWithoutCustomerNoUnaffected() {
        String sheetKey = resolveSheetKey(PfApi.QUOTE, "物料");
        Response r = PfApi.rows(adminSession(), PfApi.QUOTE, ANCHOR, sheetKey, null, null);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        assertByteIdenticalToBaseline("AC17-rows-noCustomerNo", r.asString(),
                "AC-17(rows)：不传 customerNo 时响应应与改动前逐字一致");
    }

    @Test
    @DisplayName("AC-17：GET /sheets/{sheetKey}/versions 不传 customerNo，响应与 A 侧基线逐字一致")
    void versionsWithoutCustomerNoUnaffected() {
        String sheetKey = resolveSheetKey(PfApi.QUOTE, "物料");
        Response r = PfApi.versions(adminSession(), PfApi.QUOTE, ANCHOR, sheetKey, null);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        assertByteIdenticalToBaseline("AC17-versions-noCustomerNo", r.asString(),
                "AC-17(versions)：不传 customerNo 时响应应与改动前逐字一致");
    }

    @Test
    @DisplayName("AC-17：PUT /parts/{axisValue} 不传 customerNo，写入同一个幂等值时响应体与 A 侧基线逐字一致")
    void putWithoutCustomerNoUnaffected() {
        // 🔑 幂等：两次调用（改动前采集 / 改动后比对）都把值设成同一个常量，响应体才具备可比性。
        // 🚨 锚点此时应恰好只有『客户 A』这一行（首次插入即为 A），不传 customerNo 命中 1 行，语义等价于旧行为。
        long hitRows = count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + ANCHOR + "'");
        System.out.println("[AC-17 PUT] 锚点当前命中行数=" + hitRows + "（应为 1，若 >1 说明其它用例的夹具串了进来）");
        Response r = PfApi.updatePart(adminSession(), PfApi.QUOTE, ANCHOR, null,
                Map.of("productionNo", PUT_FIXED_VALUE));
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        assertByteIdenticalToBaseline("AC17-put-noCustomerNo", r.asString(),
                "AC-17(PUT)：不传 customerNo 且只命中 1 行时，响应体应与改动前逐字一致");
    }

    /** 剔除每个 item 里的 customerNo/customerName 键（保留其余字段原样，键顺序按原文本，不做语义级重排）。 */
    private static String normalizeRemovingCustomerFields(String rawJson) {
        // 只做最朴素的字符串级剔除：把 "customerNo":"...", 与 "customerName":null/"...", 整段去掉。
        // 🚨 这是黑盒字符串手术，不解析实现的 DTO 结构；若字段顺序/转义变化导致误伤，
        // 该测试会以“正则没匹配到任何东西”的方式在断言里体现（因为剔除前后字符串相同，比对反而更严格），
        // 不会静默放过真实差异。
        return rawJson
                .replaceAll(",?\"customerNo\":(\"[^\"]*\"|null)", "")
                .replaceAll(",?\"customerName\":(\"[^\"]*\"|null)", "");
    }
}
