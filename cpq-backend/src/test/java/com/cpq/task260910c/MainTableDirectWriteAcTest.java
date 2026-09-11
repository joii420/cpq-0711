package com.cpq.task260910c;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-10 · AC-13</b> —— 带版本表<b>直写主表</b>（与导入一致），{@code _record} 在建单末尾投影。
 *
 * <h3>🔴 本类原名 {@code RecordOnlyWriteAcTest}，为<b>方案②</b>而写；D-14 作废后按 D-22 改写</h3>
 * 原断言是「带版本表<b>只写 {@code _record}</b>、主表<b>零新增</b>」（{@code assertEquals(0L, mainM)}）。
 * <p><b>方案② 已被用户整体作废（D-14）</b>：实测 {@code ds_quote_material_bom} 的 source 分布
 * = {@code IMPORT} <b>2734</b> 行 / {@code MANUAL} 18 / {@code QUOTE_BACKFILL} 8
 * ⇒ 导入侧一直是<b>直写主表</b>、一次核价审核都没过 ⇒ 「新料号必须过核价才进主库」
 * 这条规则本来只约束选配一侧、不成立。用户原话：
 * 「那选配与导入看齐,也写入相同的主表. 两侧功能保持一致」。
 * <p>⇒ 断言<b>整体反转</b>：主表由「0 行」改为「1 行且列值具体」；类名与注释同步改（D-22 要求）。
 * 🚫 <b>不要留下描述旧设计的名字</b> —— 本任务已因过时注释踩过两次坑。
 *
 * <h3>AC-10 原文（{@code 需求文档.md §③ S-4}，已按 D-14 反转）</h3>
 * 前置「{@code CUST-0004}，选配新建零件（材质 {@code AgCu90} 100%）」；操作「提交，<b>不做任何其它操作</b>」；断言：
 * <ol>
 *   <li>{@code ds_quote_material_bom} 主表<b>新增 1 行</b>：{@code input_material_no='AgCu90'} ·
 *       {@code output_material_type='RECIPE'} · <b>{@code material_ratio=100.000000000000}</b> ·
 *       {@code version_no=1} · <b>{@code source='MANUAL'}</b>；</li>
 *   <li>{@code ds_quote_element_bom} 主表<b>新增 2 行</b>（{@code Ag 90} / {@code Cu 10}），
 *       {@code version_no=1} · {@code source='MANUAL'}；</li>
 *   <li>{@code ds_quote_material} 与 {@code ds_quote_customer_part} 各 1 行（免版本表，口径不变）；</li>
 *   <li>{@code ds_quote_material_bom_record} / {@code _element_bom_record} <b>有对应行</b>且
 *       {@code quotation_id}=本单（<b>建单末尾投影</b>）；</li>
 *   <li>🔑 <b>对照断言</b>：这四张表的写入形态与<b>导入建单</b>逐字同型 —— {@code source} 值域、
 *       {@code version_no} 起始、{@code _record} 的 {@code QUOTE_DRAFT}。</li>
 * </ol>
 * 🚫 原断言「主表零新增」已作废。<b>反向判据</b>：若主表 0 行，说明 B-12 的回退没做干净。
 */
@QuarkusTest
@DisplayName("AC-10/13 · 带版本表直写主表（与导入一致）+ _record 建单末尾投影")
class MainTableDirectWriteAcTest extends Task260910CBase {

    /**
     * <b>AC-10</b> 主场景。
     *
     * <h3>断言顺序是刻意的（{@code testing.md §3} 第 3 号假绿陷阱）</h3>
     * 先钉两条<b>前置</b>，再断业务值：
     * <ul>
     *   <li><b>指纹自检</b>（{@code fingerprintMatched=false}）—— 复用时一个新料号都没铸，
     *       「主表有行」会被<b>存量行</b>骗过。这是本任务实测过的第二类假读数。</li>
     *   <li><b>免版本两表各 1 行</b>（③）—— 证明提交真的落库了。</li>
     * </ul>
     * ⇒ 顺序 <b>指纹 → ③ → ① → ② → ④ → ⑤</b>。
     */
    @Test
    @DisplayName("AC-10 · 主表 material_bom 1 行 + element_bom 2 行 + 免版本两表各 1 行 + _record 有行")
    void ac10_versionedTablesWriteMainTableDirectly() {
        requireRecordLayer();
        Fx fx = newBoundFixture("C-ac10");   // D-31：含「configure 之前绑 quotation.customer_template_id」

        String productNo = C + "AC10-" + RUN_C;
        Response res = configure(fx, submitBody(productNo,
                newPart(freshPartName("AC10零件"), "spec-234", "234", "11",
                        List.of(material(RECIPE_AGCU, CONFIG_AGCU, "100")),
                        List.of(PROC_CLEAN))));
        requireImplementationPresent(res, "AC-10");
        assertSubmitOk(res, "AC-10 选配提交");
        assertFreshlyMinted(res, "AC-10");

        String partNo = latestLinePartNo(fx);
        System.out.println("[AC-10] 本次新铸销售料号 = " + partNo + " / 客户 = " + fx.customerNo()
                + " / 报价单 = " + fx.quotationId());

        // ── ③ 免版本两表各 1 行（口径不变；同时证明提交真的落库了）──────────────
        long dsMat = count("SELECT count(*) FROM ds_quote_material WHERE material_no='" + partNo
                + "' AND customer_no='" + fx.customerNo() + "'");
        long dsCp = count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='"
                + fx.customerNo() + "' AND customer_product_no='" + productNo + "'");
        System.out.println("[AC-10③] ds_quote_material=" + dsMat + " ds_quote_customer_part=" + dsCp);
        assertEquals(1L, dsMat,
                "AC-10③：免版本表 ds_quote_material 应有 1 行（口径不变），实际 " + dsMat
                        + " 行。0 行 ⇒ 提交压根没落库，后面所有断言都是空验证");
        assertEquals(1L, dsCp,
                "AC-10③：免版本表 ds_quote_customer_part 应有 1 行（customer_product_no=" + productNo
                        + "），实际 " + dsCp + " 行");

        // ── ① 带版本主表 material_bom 新增 1 行，且列值逐个对 ─────────────────
        List<Object[]> mb = mainMbomRows(partNo);
        System.out.println("[AC-10①] 主表 " + MBOM + " 实际行 "
                + "(item_seq/input_material_no/output_material_type/material_ratio/version_no/source/customer_no/row_fingerprint) = "
                + dump(mb));
        assertNonEmpty(mb.size(), "AC-10①：" + MBOM + " 主表对新铸料号 " + partNo + " 的行数");
        assertEquals(1, mb.size(),
                "AC-10①：带版本主表 " + MBOM + " 对新铸料号 " + partNo + " 应**新增 1 行**"
                        + "（D-14：选配与导入看齐，直写主表），实际 " + mb.size() + " 行。"
                        + "\n  🚫 0 行 ⇒ B-12 的回退没做干净（仍按作废的方案② 只写 _record）；"
                        + "\n  🚫 >1 行 ⇒ 一个 100% 单材质写出了多行。实际值 = " + dump(mb));
        Object[] r = mb.get(0);
        assertEquals(RECIPE_AGCU, String.valueOf(r[1]),
                "AC-10①：input_material_no 应是材质 " + RECIPE_AGCU + "，实际 " + r[1]);
        assertEquals("RECIPE", String.valueOf(r[2]),
                "AC-10①：output_material_type 应为 'RECIPE'，实际 " + r[2]
                        + " ⇒ 下游按该字段判「投入的是材质还是零件」的读点会认错");
        assertEquals("100.000000000000", String.valueOf(r[3]),
                "AC-10①：material_ratio 应为 100.000000000000（numeric(26,12) 原样落库），实际 " + r[3]
                        + "。\n  🔑 这一列是 AC-12 终态「核价通过判 UNCHANGED、不升版」的前提 ——"
                        + "它为空就会让行指纹对不上（反面实证：0526-2609000006 曾升到 v2 source=QUOTE_BACKFILL）");
        assertEquals("1", String.valueOf(r[4]),
                "AC-10①：version_no 起始应为 1，实际 " + r[4]);
        assertEquals("MANUAL", String.valueOf(r[5]),
                "AC-10①：source 应为 'MANUAL'（选配写入的来源；导入侧是 'IMPORT'），实际 " + r[5]);
        assertEquals(fx.customerNo(), String.valueOf(r[6]),
                "AC-10①：customer_no 应是本单客户 " + fx.customerNo() + "，实际 " + r[6]
                        + " ⇒ 客户维度写错，该行会出现在别人的卡片里");
        assertTrue(!"(NULL)".equals(String.valueOf(r[7])) && !String.valueOf(r[7]).isBlank(),
                "AC-10⑤：row_fingerprint 不许为空 —— 导入行该列必填，它是核价回填判 UNCHANGED 的依据。实际 " + r[7]);

        // ── ② 带版本主表 element_bom 新增 2 行（Ag 90 / Cu 10）──────────────
        List<Object[]> eb = mainEbomRows(partNo);
        System.out.println("[AC-10②] 主表 " + EBOM
                + " 实际行 (item_seq/element_code/content_pct/version_no/source) = " + dump(eb));
        assertNonEmpty(eb.size(), "AC-10②：" + EBOM + " 主表对新铸料号 " + partNo + " 的行数");
        assertEquals(2, eb.size(),
                "AC-10②：带版本主表 " + EBOM + " 应**新增 2 行**（" + CONFIG_AGCU + " 配置 = Ag 90 / Cu 10），"
                        + "实际 " + eb.size() + " 行。实际值 = " + dump(eb));
        List<String> codes = eb.stream().map(x -> String.valueOf(x[1])).toList();
        assertTrue(codes.contains("Ag") && codes.contains("Cu"),
                "AC-10②：元素应是 Ag / Cu 两个，实际 " + codes);
        for (Object[] e : eb) {
            String code = String.valueOf(e[1]);
            String expectPct = "Ag".equals(code) ? "90" : "10";
            assertTrue(String.valueOf(e[2]).startsWith(expectPct),
                    "AC-10②：元素 " + code + " 的 content_pct 应是 " + expectPct + "，实际 " + e[2]);
            assertEquals("1", String.valueOf(e[3]),
                    "AC-10②：" + code + " 行的 version_no 起始应为 1，实际 " + e[3]);
            assertEquals("MANUAL", String.valueOf(e[4]),
                    "AC-10②：" + code + " 行的 source 应为 'MANUAL'，实际 " + e[4]);
        }

        // ── ④ _record 有对应行，且挂在本单上（建单末尾投影）─────────────────
        // ⛔ 先过投影的必要条件闸门：compData 为空时投影会早退跳过 ⇒ 那是**前置不成立**，不是缺陷
        requireCardDataMaterialized(fx, "AC-10④");
        long recM = recRows(MBOM_REC, fx, partNo);
        long recE = recRows(EBOM_REC, fx, partNo);
        System.out.println("[AC-10④] " + MBOM_REC + "=" + recM + " 行 / " + EBOM_REC + "=" + recE
                + " 行（均已按 quotation_id=" + fx.quotationId() + " 收窄）");
        assertNonEmpty(recM, "AC-10④：" + MBOM_REC + " 在本单该料号下的行数");
        assertNonEmpty(recE, "AC-10④：" + EBOM_REC + " 在本单该料号下的行数");

        // 🚨 「有行」还不够 —— 必须证明这些行的 quotation_id 真的是本单，
        //    否则「按 quotation_id 查到行」这件事本身就已经预设了结论。反向再查一次：
        long recMAnyQuotation = count("SELECT count(*) FROM " + MBOM_REC
                + " WHERE material_no='" + partNo + "'");
        assertEquals(recM, recMAnyQuotation,
                "AC-10④：该新铸料号在 " + MBOM_REC + " 的全部 " + recMAnyQuotation
                        + " 行里，只有 " + recM + " 行挂在本单 " + fx.quotationId()
                        + " 上 ⇒ 有行挂到了别的 quotation_id（或 quotation_id 写错）");

        // ── ⑤ 与导入建单同型（本片可做的那部分，只读对照）────────────────────
        List<Object> recSources = col("SELECT DISTINCT coalesce(source,'(NULL)') FROM " + MBOM_REC
                + " WHERE quotation_id='" + fx.quotationId() + "'");
        System.out.println("[AC-10⑤] 本单 " + MBOM_REC + " 的 source 值域 = " + recSources);
        assertEquals(List.of("QUOTE_DRAFT"), recSources.stream().map(String::valueOf).toList(),
                "AC-10⑤ / D-18：_record 的 source 常量只有一个 'QUOTE_DRAFT'，"
                        + "导入建单与选配走同一个投影、写同一个值 ⇒ 本单应只有 'QUOTE_DRAFT'，实际 " + recSources
                        + "。🚫 主表那套值域（IMPORT/MANUAL/QUOTE_BACKFILL）不适用于 _record");

        long importMinVersion = count("SELECT coalesce(min(version_no),-1) FROM " + MBOM
                + " WHERE source='IMPORT'");
        System.out.println("[AC-10⑤] 导入侧 " + MBOM + " 的 version_no 起始（只读对照）= " + importMinVersion);
        assertEquals(importMinVersion, Long.parseLong(String.valueOf(r[4])),
                "AC-10⑤：选配写入的 version_no 起始应与导入侧一致（导入侧 min=" + importMinVersion
                        + "），实际选配写了 " + r[4] + " ⇒ 两侧口径不一致，回填/升版逻辑会按不同基准算");

        System.out.println("[AC-10⑤] ⚠️ 如实登记能力边界：完整的 FT-6（真跑一次**导入建单**、"
                + "与选配单逐列 diff）写入面覆盖十几张 ds_quote_* 表 + _record + quotation_line_*，"
                + "按 test.md §1 属 **S-全局 片**（AC-15/AC-23 同理）⇒ 本片只做上面这几项"
                + "**只读值域对照**，🚫 不声称做过逐列 diff。");
    }

    /**
     * <b>AC-13（边界 · {@code _record} 投影必须<b>晚于</b>物化）</b> —— 🔴 <b>方向已按 D-21 反转</b>。
     *
     * <h3>原断言写的是「早于」，方向反了</h3>
     * 那是 B-13 为方案② 改出来的顺序（方案②下冻结要先读到 {@code _record}），
     * 随 D-14 失去理由，且 <b>D-21 实测有害</b>：
     * <ul>
     *   <li><b>A 回退后</b>：line {@code …025} {@code 15:31:33.615} → {@code _record} {@code 15:31:34.204}，
     *       <b>同请求内</b>、{@code origin_id} 非空 ✅</li>
     *   <li><b>B 临时改回 B-13</b>：{@code record_rows = 0} ❌（主表仍 1 行 —— 坏掉的只有投影）</li>
     *   <li><b>A' 还原后</b>：{@code …027} 同请求内即有，且把 B 阶段留下的孤儿 {@code …026} 一起治好了 ✅</li>
     * </ul>
     * <b>根因</b>：上移后 {@code quotation_line_component_data} 里本行还没数据 ⇒
     * 投影被空 {@code compData} 早退跳过 ⇒ {@code _record} <b>恒慢一个写请求</b>。
     * <b>与导入侧同向</b>：{@code CreateQuotationMaterializer} 的 {@code ensureCardValues:129}
     * 早于 {@code syncRecordsForFlow:179}。
     *
     * <h3>本用例断在哪一层 —— 与旧版的关键差别</h3>
     * AC-13 有两个合取项：(a) 挂点顺序晚于两段物化；(b) <b>本次请求结束后</b>新料号在
     * {@code _record} 里<b>就有行</b>（{@code quotation_id}=本单、{@code origin_id} 非空）。
     * <p>旧版只想用<b>日志标记时序</b>验 (a)，而 {@code snapshotLines} 侧的标记原文本片拿不到
     * （禁读 {@code com.cpq.configure.**}）⇒ 旧版<b>恒硬失败在「标记未知」</b>，一条业务结论都给不出。
     * <p>🔑 (b) 是<b>可直接断的</b>，而且正是 D-21 A/B 实验的<b>判别量</b>：
     * 顺序错时 {@code record_rows=0}（主表仍 1 行）。⇒ 本版把 (b) 作为<b>硬断言</b>，
     * 并额外断 {@code origin_id → 主表.id} <b>连得上</b>（投影确实看见了已物化的主表行）。
     * <p>日志时序退为<b>辅助</b>：两侧标记都抓到才判序，抓不到就<b>如实打印「未取得日志证据」</b>
     * —— 🚫 不拿它当通过，也不再因它硬失败把 (b) 的结论一起盖掉。
     * <p>🚫 旧注释里的「权威判据是 FT-5」已作废（FT-5 随 D-14 整条删除，见 {@code test.md §4}）。
     */
    @Test
    @DisplayName("AC-13 · _record 投影晚于物化：本次请求结束后即有行，且 origin_id 连得上主表")
    void ac13_recordProjectionHappensAfterMaterialization() {
        requireRecordLayer();
        Fx fx = newBoundFixture("C-ac13");   // D-31：含「configure 之前绑 quotation.customer_template_id」

        List<String> captured = new ArrayList<>();
        Handler h = new Handler() {
            @Override public void publish(LogRecord r) {
                synchronized (captured) {
                    captured.add((r.getLoggerName() == null ? "" : r.getLoggerName()) + " :: "
                            + String.valueOf(r.getMessage()));
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger root = Logger.getLogger("");
        root.addHandler(h);
        Response res;
        try {
            res = configure(fx, submitBody(C + "AC13-" + RUN_C,
                    newPart(freshPartName("AC13零件"), "spec-234", "234", "11",
                            List.of(material(RECIPE_AGCU, CONFIG_AGCU, "100")),
                            List.of(PROC_CLEAN))));
        } finally {
            root.removeHandler(h);
        }
        requireImplementationPresent(res, "AC-13");
        assertSubmitOk(res, "AC-13 选配提交");
        assertFreshlyMinted(res, "AC-13");

        String partNo = latestLinePartNo(fx);
        System.out.println("[AC-13] 本次新铸销售料号 = " + partNo + " / 报价单 = " + fx.quotationId());

        // ── 前置：物化真的发生了（主表 1 行）。0 行时下面的判别量没有意义 ────────
        long mainM = count("SELECT count(*) FROM " + MBOM + " WHERE material_no='" + partNo + "'");
        System.out.println("[AC-13 前置] 主表 " + MBOM + " = " + mainM + " 行");
        assertEquals(1L, mainM,
                "AC-13 前置：主表应已直写 1 行（D-14），实际 " + mainM + " 行。"
                        + "主表 0 行时「_record 也 0 行」分不清是投影时序问题还是压根没写主表");

        // ── (b) 本次请求结束后 _record 就有行 —— D-21 A/B 实验的判别量 ─────────
        requireCardDataMaterialized(fx, "AC-13");
        long recM = recRows(MBOM_REC, fx, partNo);
        System.out.println("[AC-13①] " + MBOM_REC + " 本单该料号 = " + recM + " 行");
        assertNonEmpty(recM, "AC-13①：" + MBOM_REC + " 在本单该料号下的行数");
        assertTrue(recM > 0,
                "AC-13①：**本次请求结束后**新料号 " + partNo + " 就应在 " + MBOM_REC + " 有行，实际 " + recM
                        + " 行。\n  🔑 这正是 D-21 的判别量：投影被上移到物化之前时，"
                        + "quotation_line_component_data 里本行还没数据 ⇒ 投影被空 compData 早退跳过"
                        + " ⇒ record_rows=0（而主表仍 1 行）⇒ _record 恒慢一个写请求。"
                        + "\n  🧪 阳性对照已由后端做过 A/B/A 还原实验（干预已证生效）：B 阶段 record_rows=0 必红。");

        // ── (a) 的可观测代理：origin_id 连得上主表 ⇒ 投影看见了**已物化**的主表行 ──
        List<Object> originIds = col("SELECT coalesce(origin_id::text,'(NULL)') FROM " + MBOM_REC
                + " WHERE quotation_id='" + fx.quotationId() + "' AND material_no='" + partNo + "'");
        System.out.println("[AC-13②] 本单 _record 行的 origin_id 实际值 = " + originIds);
        assertTrue(originIds.stream().map(String::valueOf).noneMatch("(NULL)"::equals),
                "AC-13②：_record 行的 origin_id **不许为 NULL**（AC 原文点名），实际 " + originIds
                        + "。\n  📌 origin_id 为 NULL = 投影没认领到主表基底行 ⇒ 核价回填时会被当「新增」追加"
                        + "，主表整组翻倍（AC-17 的风险传导路径）。");

        long linked = count("SELECT count(*) FROM " + MBOM_REC + " r JOIN " + MBOM + " m ON m.id = r.origin_id "
                + "WHERE r.quotation_id='" + fx.quotationId() + "' AND r.material_no='" + partNo + "'");
        System.out.println("[AC-13②] origin_id 能 JOIN 上主表的行数 = " + linked + " / 共 " + recM + " 行");
        assertEquals(recM, linked,
                "AC-13②：本单 " + recM + " 行 _record 里只有 " + linked + " 行的 origin_id 能 JOIN 上 " + MBOM
                        + " ⇒ 投影认领的基底行不存在。"
                        + "\n  🔑 这条是「投影**晚于**物化」的构造性证据：主表行不先存在，origin_id 无从认领。");

        // ── 辅助：日志时序（拿不到标记就如实登记「未取得」，🚫 不当通过、也不遮盖上面的结论）──
        List<String> logs;
        synchronized (captured) { logs = List.copyOf(captured); }
        int recordAt = firstIndexContaining(logs, "ds-record");
        int snapshotAt = firstIndexContainingAny(logs,
                List.of("snapshotLines", "snapshot-lines", "snapshotLineValues", "冻结",
                        "snapshotQuotation", "expand-driver", "ensureCardValues"));
        System.out.println("[AC-13 辅助] 提交期间抓到 " + logs.size() + " 条日志；"
                + "ds-record 首次出现 #" + recordAt + " / 物化侧标记首次出现 #" + snapshotAt);
        if (recordAt >= 0 && snapshotAt >= 0) {
            assertTrue(snapshotAt < recordAt,
                    "AC-13 辅助（日志时序）：syncRecordsForFlow 应**晚于** snapshotLines / snapshotLineValues"
                            + "（= master 的 D-40 挂点），实际物化侧标记在 #" + snapshotAt
                            + "、ds-record 在 #" + recordAt + " ⇒ 投影仍在物化之前（B-13 的上移未回退）。"
                            + "\n  物化侧行：" + logs.get(snapshotAt)
                            + "\n  ds-record 行：" + logs.get(recordAt));
        } else {
            System.out.println("[AC-13 辅助] ⚠️ **日志时序未取得证据**（不是「通过」）："
                    + (recordAt < 0 ? "_record 侧标记 'ds-record' 未出现；" : "")
                    + (snapshotAt < 0 ? "物化侧标记在候选集里一个都没命中（本片禁读 com.cpq.configure.**，"
                                        + "拿不到标记原文）。" : "")
                    + "\n  🔑 本条 AC 的红绿由上面 ①② 两组**可观测断言**承担（_record 本请求内有行 + "
                    + "origin_id 能 JOIN 上主表），它们正是 D-21 A/B 实验的判别量。"
                    + "\n  🚫 日志时序只是辅助，🚫 不拿「抓不到标记」当通过。");
        }
    }

    private static int firstIndexContaining(List<String> logs, String needle) {
        for (int i = 0; i < logs.size(); i++) if (logs.get(i).contains(needle)) return i;
        return -1;
    }

    private static int firstIndexContainingAny(List<String> logs, List<String> needles) {
        for (int i = 0; i < logs.size(); i++) {
            for (String n : needles) if (logs.get(i).contains(n)) return i;
        }
        return -1;
    }
}
