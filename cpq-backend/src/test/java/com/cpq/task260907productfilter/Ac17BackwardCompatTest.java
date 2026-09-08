package com.cpq.task260907productfilter;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>T-B10 · AC-17</b>（向后兼容：不传客户号 = 不过滤）—— <b>反向 AC，A/B 基线比对</b>。
 *
 * <p>需求文档.md §③ AC-17：绕过 UI，直接以<b>不带</b> {@code customerNo} 参数调用本次改造的 5 个端点。
 * 断言：① 返回的行集合（{@code axisValue} 全集）与 {@code total} 与改动前<b>完全一致</b>；
 * ② 改动前已存在的键，其值逐字未变；③ 本次新增的键（{@code customerNo}/{@code customerName}）
 * <b>不参与比对</b>。
 *
 * <h3>🚨 D-12 裁决（原「逐字一致」判据已被推翻，不是让步补丁）</h3>
 * 原文写「响应逐字一致」，与 <b>AC-5②</b>（客户两列无条件下发）自相矛盾且永远无法满足——
 * 字段一旦无条件出现，不传 {@code customerNo} 时的响应就不可能与改动前逐字相同
 * （实测改动前 item 有 <b>13 个键</b>，改动后 15 个）。
 * <p>该矛盾由本套测试代理照 AC 写用例时撞出，两轮闸门 A 自检未能发现（自检验的是「AC 有没有人认领」，
 * 验不出「两条 AC 互相打架」）。用户 2026-09-07 裁决 <b>D-12</b>：换掉断言对象——
 * AC-17 真正要保证的是<b>过滤行为没变</b>，不是<b>字节没变</b>；「字节完全相同」和「写死数字」
 * 是同一类判据错误，都把某一时刻的快照当成了判据。落地见
 * {@link PfTestBase#assertPartsRowSetAndKeysUnchanged}。
 * <p>{@code overview}/{@code rows}/{@code versions} 三个端点契约本就「不加字段」
 * （api.md §2 明确「响应结构不变」），D-12 的让步对它们是空操作，故继续用严格逐字比对
 * {@link PfTestBase#assertByteIdenticalToBaseline}。
 *
 * <h3>🚨 实跑暴露的第二类同源问题（2026-09-08）：{@code lastUpdatedAt}/{@code updatedAt} 时间戳</h3>
 * 首次真实连跑两遍后发现：{@code parts} 的 item 里带 {@code lastUpdatedAt}，PUT 的响应带
 * {@code updatedAt}——这两个字段是<b>服务端时间戳</b>，只要锚点被写过一次就会变，与
 * "customerNo 有没有传"完全无关。继续做逐字比对会把"这条用例自己在写数据"误判成"响应变了"，
 * 与 D-12 要修正的是<b>同一类判据错误</b>（把某一时刻的快照当判据）。
 * ⇒ {@code parts} 把 {@code lastUpdatedAt} 并入 {@link #IGNORED_VOLATILE_KEYS} 一起剔除；
 * {@code PUT} 改用 {@link PfTestBase#assertJsonUnchangedIgnoringKeys} 剔除 {@code updatedAt} 后比对。
 *
 * <h3>基线策略</h3>
 * 本类维护一个<b>持久化、不被清理</b>的锚点行（{@link #ANCHOR}，仿照本工程
 * {@code product-hub.helpers.ts} 的 {@code HERO_EDIT} 惯例：「本套用例唯一允许写/长期占用的料号」），
 * 首次跑通过 {@link #ensureAnchorExists()} 幂等建好；不在任何 {@code @AfterEach} 里删除它——
 * 它必须在「改动前」与「改动后」两次运行之间原样存在，否则 A/B 比对无从谈起。
 * <p>🔑 <b>锚点插入不引用 {@code customer_no} 列</b>——AC-17 测的是「不传 customerNo 参数时的行为」，
 * 与该列是否存在无关；这样设计让本类<b>不依赖</b>外部 28 表 DDL，可以在 2026-09-07 当天就实跑，
 * 也确实已经实跑（详见测试代理回报里的原始输出：改动前 {@code parts} 响应 13 键、customerNo 参数
 * 当前被静默忽略）。
 * <p>🚨 <b>基线文件一旦被首次写盘，就不得重采</b>——重采 = 拿当前值当基线 = 断言退化成恒真
 * （test.md §4 纪律 1）。若怀疑基线本身采晚了（例如已经混入了本任务的改动），删掉对应基线文件
 * 并在确认「改动尚未落地」的时间窗口里重新触发一次捕获。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-17 向后兼容（不传 customerNo = 不过滤，D-12 判据）")
@QuarkusTest
class Ac17BackwardCompatTest extends PfTestBase {

    private static final String ANCHOR = FX + "AC17ANCHOR";
    private static final String PUT_FIXED_VALUE = FX + "AC17FIXEDVALUE";

    /** D-12③：这两个新增键不参与 {@code parts} 端点的比对。 */
    private static final Set<String> IGNORED_NEW_KEYS = Set.of("customerNo", "customerName");
    /** 服务端时间戳，天然易变，与"customerNo 有没有传"无关，同样不参与比对。 */
    private static final Set<String> IGNORED_VOLATILE_KEYS = Set.of("lastUpdatedAt", "updatedAt");

    @BeforeEach
    void ensureAnchorExists() {
        long n = count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + ANCHOR + "'");
        if (n == 0) {
            QuarkusTransaction.requiringNew().run(() ->
                    em.createNativeQuery("INSERT INTO ds_quote_material "
                            + "(material_no, material_name, production_no, source, created_at) "
                            + "VALUES (:mn, 'T260907M AC-17 基线锚点（长期存在，不清理）', :pn, 'IMPORT', now())")
                            .setParameter("mn", ANCHOR)
                            .setParameter("pn", FX + "AC17ANCHOR-PROD")
                            .executeUpdate());
            System.out.println("[AC-17] 锚点 " + ANCHOR + " 首次建立（长期存在，不会被 @AfterEach 清理）");
        }
        // 注意：ANCHOR 不通过 registerCleanup/cleanupMaterialRow 登记，因此不会被 PfTestBase 的
        // tearDown 删除——这是刻意设计，不是遗漏。也不引用 customer_no 列，因此不受外部 DDL 依赖门约束。
    }

    @Test
    @DisplayName("AC-17（D-12）：GET /parts 不传 customerNo，行集合+total 与 A 侧基线一致，"
            + "存量键值逐字未变（customerNo/customerName 不参与比对）")
    void partsWithoutCustomerNoUnaffected() {
        Response r = PfApi.parts(adminSession(), PfApi.QUOTE, null, 0, 500, ANCHOR);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        Set<String> ignored = new java.util.HashSet<>(IGNORED_NEW_KEYS);
        ignored.addAll(IGNORED_VOLATILE_KEYS);
        assertPartsRowSetAndKeysUnchanged("AC17-parts-noCustomerNo", r, ignored,
                "AC-17(parts,D-12)：不传 customerNo 时的过滤行为/存量字段应与改动前一致");
    }

    @Test
    @DisplayName("AC-17：GET /overview 不传 customerNo，响应与 A 侧基线逐字一致（契约不加字段）")
    void overviewWithoutCustomerNoUnaffected() {
        Response r = PfApi.overview(adminSession(), PfApi.QUOTE, ANCHOR, null);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        assertByteIdenticalToBaseline("AC17-overview-noCustomerNo", r.asString(),
                "AC-17(overview)：不传 customerNo 时响应应与改动前逐字一致（api.md §2/A-3 明确不加字段）");
    }

    @Test
    @DisplayName("AC-17：GET /sheets/{sheetKey}/rows 不传 customerNo，响应与 A 侧基线逐字一致")
    void rowsWithoutCustomerNoUnaffected() {
        String sheetKey = resolveSheetKey(PfApi.QUOTE, "物料BOM");
        Response r = PfApi.rows(adminSession(), PfApi.QUOTE, ANCHOR, sheetKey, null, null);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        assertByteIdenticalToBaseline("AC17-rows-noCustomerNo", r.asString(),
                "AC-17(rows)：不传 customerNo 时响应应与改动前逐字一致");
    }

    @Test
    @DisplayName("AC-17：GET /sheets/{sheetKey}/versions 不传 customerNo，响应与 A 侧基线逐字一致")
    void versionsWithoutCustomerNoUnaffected() {
        String sheetKey = resolveSheetKey(PfApi.QUOTE, "物料BOM");
        Response r = PfApi.versions(adminSession(), PfApi.QUOTE, ANCHOR, sheetKey, null);
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        assertByteIdenticalToBaseline("AC17-versions-noCustomerNo", r.asString(),
                "AC-17(versions)：不传 customerNo 时响应应与改动前逐字一致");
    }

    @Test
    @DisplayName("AC-17：PUT /parts/{axisValue} 不传 customerNo，写入同一个幂等值时响应体（剔除 updatedAt 时间戳后）与 A 侧基线一致")
    void putWithoutCustomerNoUnaffected() {
        // 🔑 幂等：两次调用（改动前采集 / 改动后比对）都把值设成同一个常量，响应体才具备可比性——
        // 但 `updatedAt` 每次调用都会刷新（这本来就是 PUT 的正常行为），必须剔除后再比，
        // 否则会把"这条用例自己在写数据"误判成"响应变了"（与 D-12 同一类判据错误）。
        // 🚨 锚点此时应恰好只有 1 行（未来 DDL 落地后也不会变多，除非有人手工插了重复客户号行）。
        long hitRows = count("SELECT count(*) FROM ds_quote_material WHERE material_no = '" + ANCHOR + "'");
        System.out.println("[AC-17 PUT] 锚点当前命中行数=" + hitRows + "（应为 1，若 >1 说明其它用例的夹具串了进来）");
        Response r = PfApi.updatePart(adminSession(), PfApi.QUOTE, ANCHOR, null,
                Map.of("productionNo", PUT_FIXED_VALUE));
        assertEquals(200, r.statusCode(), "body=" + r.asString());
        assertJsonUnchangedIgnoringKeys("AC17-put-noCustomerNo", r.asString(), IGNORED_VOLATILE_KEYS,
                "AC-17(PUT)：不传 customerNo 且只命中 1 行时，响应体（剔除 updatedAt 后）应与改动前一致");
    }
}
