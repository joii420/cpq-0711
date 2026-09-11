package com.cpq.task260910c;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-18 · AC-19</b> —— S-7（D-12）：客户产品编号不存在时<b>直接绑定已有销售料号</b>。
 *
 * <h3>🔴 2026-09-11 更正：原来那段「D-22 复核结论」<b>已被实测推翻</b></h3>
 * <b>改前原文</b>：「S-7 绑定路径按定义<b>一行 BOM 都不写</b> ⇒ AC-18④ 的『主表与 {@code _record}
 * <b>均零新增</b>』在两种设计下<b>都成立</b> ⇒ 本类只加了复核说明，<b>没有改动任何断言</b>。」
 * <p>⚠️ <b>前半句对、后半句错</b>：主表侧确实一行不写（④-a 实测全绿），但 {@code _record} 侧
 * <b>必然有投影行</b> —— 因为卡片确实渲染了该料号的 BOM（AC-18⑥ 实测 compData 14 条 / 页签 6 行），
 * 而 D-14 之后 {@code _record} 的角色就是「报价单对主数据的投影」。
 * <p>🔬 <b>为什么 D-22 那一轮没看出来</b>：那一轮本单 {@code quotation_line_component_data} = <b>0 条</b>
 * ⇒ 投影被静默跳过 ⇒ {@code _record} 真的零新增 ⇒ 旧断言 <b>0 == 0</b> 恒成立。
 * ⇒ <b>那个绿是「环境坏了所以碰巧符合一条错的断言」</b>，不是「设计两种都成立」的证据
 * （{@code testing.md §3}「断言从未执行 = 假绿」）。D-31 补上夹具前置后它第一次判出红。
 * <p>⇒ <b>AC-18④ 已按裁决拆成 ④-a / ④-b</b>（用户 2026-09-11 选「甲 = 改 AC」），
 * 四条理由写在 {@link #ac18_bindWritesExactlyTwoRows()} 的 ④ 段注释里，🚫 不要改回去。
 *
 * <p>AC-18 原文（{@code 需求文档.md §③ S-7}）—— 断言「🚫 不多不少」六项：
 * <ol>
 *   <li>{@code ds_quote_customer_part} 新增 1 行：{@code customer_no} · {@code customer_product_no}
 *       · {@code material_no} · {@code source='MANUAL'}；</li>
 *   <li>{@code quotation_line_item} 新增 1 行，{@code product_part_no_snapshot} = 被绑料号；</li>
 *   <li>{@code ds_quote_material} <b>零新增</b>（料号已存在，不铸新号）；</li>
 *   <li>🔴 <b>已按裁决改写</b>（原文是「主表与 {@code _record} <b>均</b>零新增」，{@code _record} 那半句错）：
 *       <b>④-a</b> {@code ds_quote_material_bom} / {@code _element_bom} <b>主表零新增</b>；
 *       <b>④-b</b> 两张 {@code _record} <b>应有投影行</b>，且每行 {@code origin_id} 都<b>认领到主表既有行</b>、
 *       值与被认领行<b>逐字一致</b>、{@code source='QUOTE_DRAFT'}；</li>
 *   <li>{@code sel_part_signature} <b>零新增</b>（不进指纹）；</li>
 *   <li>卡片渲染出被绑料号的既有 BOM 数据（非空）。</li>
 * </ol>
 * 请求形状见 {@code api.md §2.3}：{@code bindExistingMaterialNo} 非空 ⇒ {@code parts} 必须为空数组或 null；
 * 响应 {@code fingerprintMatched=false} · {@code reusedHfPartNos=[]} · {@code productType="SIMPLE"}。
 *
 * <h3>📌 为什么用自造料号而不是 AC 原文点名的 {@code S0004}</h3>
 * 见 {@link Task260910CBase#seedExistingSalesMaterial}：{@code S0004} 挂 {@code CUST-0004}（共享客户），
 * 往它名下增删属于「动别片/真人的作用域」。AC-18 的<b>实质</b>是「绑定一个在该客户
 * {@code ds_quote_material} 里已存在的销售料号」，不是「必须叫 S0004」。
 * 📌 现网 {@code S0004} 的形态已只读核对（{@code material_bom} 3 行、
 * <b>{@code element_bom} 0 行</b>）—— 用它时 AC-18⑥ 的元素侧无从验起，自造夹具反而更严。
 */
@QuarkusTest
@DisplayName("AC-18/19 · 直接绑定已有销售料号")
class BindExistingMaterialAcTest extends Task260910CBase {

    /**
     * <b>AC-18 ①~⑤</b> —— 落库「不多不少」。
     * <p>🔴 ④ 已按 2026-09-11 裁决拆成 <b>④-a 主表零新增</b> + <b>④-b {@code _record} 有投影行且认领既有行</b>，
     * 理由见方法体内 ④ 段那四条注释。
     */
    @Test
    @DisplayName("AC-18①~⑤ · 只写 customer_part + line_item；主表/指纹零新增，_record 投影认领既有行")
    void ac18_bindWritesExactlyTwoRows() {
        requireRecordLayer();
        Fx fx = newBoundFixture("C-ac18");   // D-31：含「configure 之前绑 quotation.customer_template_id」
        String existing = seedExistingSalesMaterial(fx, "BIND");
        String productNo = C + "BIND-01-" + RUN_C;

        // ── 跑前基线（「零新增」类断言必须有 before/after，不能只看 after）──────
        long cpBefore = count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='"
                + fx.customerNo() + "'");
        long matBefore = count("SELECT count(*) FROM ds_quote_material WHERE material_no='" + existing + "'");
        long mbomBefore = count("SELECT count(*) FROM " + MBOM + " WHERE material_no='" + existing + "'");
        long ebomBefore = count("SELECT count(*) FROM " + EBOM + " WHERE material_no='" + existing + "'");
        long recMBefore = count("SELECT count(*) FROM " + MBOM_REC + " WHERE material_no='" + existing + "'");
        long recEBefore = count("SELECT count(*) FROM " + EBOM_REC + " WHERE material_no='" + existing + "'");
        long sigBefore = count("SELECT count(*) FROM sel_part_signature WHERE customer_no='"
                + fx.customerNo() + "'");
        long liBefore = count("SELECT count(*) FROM quotation_line_item WHERE quotation_id='"
                + fx.quotationId() + "'");
        System.out.println("[AC-18 基线] customer_part=" + cpBefore + " material=" + matBefore
                + " mbom=" + mbomBefore + " ebom=" + ebomBefore + " mbom_rec=" + recMBefore
                + " ebom_rec=" + recEBefore + " signature=" + sigBefore + " line_item=" + liBefore);
        assertEquals(0L, liBefore, "AC-18 基线：本单起点应 0 个报价行，实际 " + liBefore);

        // ── 绑定提交 ──────────────────────────────────────────────────
        Response res = configure(fx, bindBody(productNo, existing));
        requireImplementationPresent(res, "AC-18");
        assertSubmitOk(res, "AC-18 绑定提交");
        recMbom(fx);   // 登记本单，@AfterEach 要清 _record

        // 响应契约（api.md §2.3）—— 绑定路径不进指纹
        // 📌 实测（2026-09-10）：本端点的成功响应把载荷放在 **根层**，不在 `data` 下 ——
        //    起初我按 `data.fingerprintMatched` 取，拿到 null 报了红，那是**我的用例缺陷**不是产品缺陷。
        //    ⇒ 两个路径都试，并把实际值打出来；🚫 不许因为「取不到」就当通过。
        Object fpMatched = firstNonNull(res, "fingerprintMatched", "data.fingerprintMatched");
        Object reused = firstNonNull(res, "reusedHfPartNos", "data.reusedHfPartNos");
        Object pType = firstNonNull(res, "productType", "data.productType");
        System.out.println("[AC-18] 响应 fingerprintMatched=" + fpMatched
                + " reusedHfPartNos=" + reused + " productType=" + pType);
        assertNotNull(fpMatched,
                "AC-18：响应里应带 fingerprintMatched 字段（api.md §2.3），两个路径都取不到 ⇒ 契约变了，"
                        + "先判契约再判缺陷。响应=" + res.asString());
        assertEquals(Boolean.FALSE, fpMatched,
                "AC-18 / api.md §2.3：绑定路径不进指纹 ⇒ fingerprintMatched 应为 false。响应=" + res.asString());
        assertEquals("SIMPLE", String.valueOf(pType),
                "AC-18 / api.md §2.3：绑定路径 productType 应为 \"SIMPLE\"，实际 " + pType);

        // ── ② quotation_line_item 新增 1 行，料号 = 被绑料号 ──────────────
        List<Object> snaps = col("SELECT product_part_no_snapshot FROM quotation_line_item "
                + "WHERE quotation_id='" + fx.quotationId() + "' ORDER BY sort_order, created_at");
        System.out.println("[AC-18②] 本单报价行料号 = " + snaps);
        assertEquals(1, snaps.size(),
                "AC-18②：应新增 **1 行** quotation_line_item，实际 " + snaps.size() + " 行（" + snaps + "）");
        assertEquals(existing, String.valueOf(snaps.get(0)),
                "AC-18②：product_part_no_snapshot 应等于被绑料号 " + existing + "，实际 " + snaps.get(0));

        // ── ① ds_quote_customer_part 新增 1 行且 source='MANUAL' ──────────
        List<Object[]> cpRows = rows("SELECT customer_no, customer_product_no, material_no, source "
                + "FROM ds_quote_customer_part WHERE customer_no='" + fx.customerNo()
                + "' AND customer_product_no='" + productNo + "'");
        System.out.println("[AC-18①] 新增 customer_part 行 = " + fmt(cpRows));
        assertEquals(1, cpRows.size(),
                "AC-18①：ds_quote_customer_part 应新增 **1 行**（customer_product_no=" + productNo
                        + "），实际 " + cpRows.size() + " 行");
        Object[] cp = cpRows.get(0);
        assertEquals(fx.customerNo(), String.valueOf(cp[0]), "AC-18①：customer_no");
        assertEquals(productNo, String.valueOf(cp[1]), "AC-18①：customer_product_no");
        assertEquals(existing, String.valueOf(cp[2]), "AC-18①：material_no 应是被绑的既有料号");
        assertEquals("MANUAL", String.valueOf(cp[3]),
                "AC-18①：source 应为 'MANUAL'（选配写入的唯一来源），实际 " + cp[3]
                        + " ⇒ 还原逻辑与后续按来源过滤的读点都会认不出这行");
        assertEquals(cpBefore + 1, count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='"
                        + fx.customerNo() + "'"),
                "AC-18①：本客户的 customer_part 应恰好 +1（不多不少），基线 " + cpBefore);

        // ── ③ ds_quote_material 零新增 ─────────────────────────────────
        long matAfter = count("SELECT count(*) FROM ds_quote_material WHERE material_no='" + existing + "'");
        assertEquals(matBefore, matAfter,
                "AC-18③：ds_quote_material 对被绑料号 " + existing + " 应**零新增**（料号已存在，不铸新号），"
                        + "基线 " + matBefore + " → 现在 " + matAfter);

        // ── ④ 🔴 **按裁决已拆成两半**（2026-09-11，用户选「甲 = 改 AC」）──────────────
        //
        // 🔬 改前的 AC-18④ 原文：「ds_quote_material_bom / _element_bom **主表与 _record 均零新增**
        //    （沿用该料号既有数据）」—— 其中「_record 零新增」这半句**是错的**，已按裁决改写。
        //
        // 【为什么原断言错、新断言对】—— 🚨 这四条理由必须留在这里，否则下一个人会把它改回去：
        //
        //  1️⃣ **D-14 已定 _record 的角色** = 「报价单对主数据的**投影** / 核价回填的**数据来源**」
        //     （🚫 不再是方案② 里那个「写入目标」）。⇒ 绑定已有料号的单，卡片**确实**渲染了该料号的
        //     BOM（AC-18⑥ 已实测 compData 14 条 / 页签 6 行），建单末尾投影就**必然**产出 _record 行。
        //     「零新增」只在方案② 的语境下成立，那个语境随 D-14 消失了。
        //
        //  2️⃣ **新断言比原断言更强，不是更松**：原断言只要求「没有行」，而它**挡不住**真正的风险 ——
        //     「投影凭空造了一组新 BOM」。新断言要求每一行的 origin_id **认领到主表既有行**且
        //     **值逐字一致** ⇒ origin_id 为 NULL、认领不上、或值不一致，**必须红**。
        //     （origin_id 为 NULL 的行会被回填当「新增」追加 ⇒ 正是 AC-17「整组翻倍」那条风险。）
        //
        //  3️⃣ 🚨 **证伪实验是最有力的反证**：撤掉 D-31 那条夹具前置
        //     （`UPDATE quotation SET customer_template_id`）后，本条断言在旧口径下**反而变绿**
        //     —— 那时本单 compData = 0、投影被静默跳过、_record 真的零新增。
        //     ⇒ **原来那个绿是「环境坏了所以碰巧符合一条错的断言」**，不是功能正确的证据。
        //     这正是 testing.md §3 说的「断言从未执行 = 假绿」的教科书形态。
        //
        //  4️⃣ **「乙 = 改实现让绑定路径不投影」已被否决**：那会让绑定的单**无法参与核价回填**
        //     （回填的数据来源就是 _record），直接违反 D-14 用户原话「两侧功能保持一致」。
        //
        // 判据来源：需求文档 AC-18④（已按本次裁决回写）+ D-14 / D-21 / D-31。🚫 未读实现。

        // ── ④-a 主表零新增（这一半**原样不变**）────────────────────────
        assertEquals(mbomBefore, count("SELECT count(*) FROM " + MBOM + " WHERE material_no='" + existing + "'"),
                "AC-18④-a：" + MBOM + " 主表应零新增（沿用既有数据，不铸新 BOM），基线 " + mbomBefore);
        assertEquals(ebomBefore, count("SELECT count(*) FROM " + EBOM + " WHERE material_no='" + existing + "'"),
                "AC-18④-a：" + EBOM + " 主表应零新增（沿用既有数据），基线 " + ebomBefore);

        // ── ④-b _record 应有投影行，且每行都「认领既有行 + 值逐字一致 + source=QUOTE_DRAFT」──
        List<Object[]> recMRows = rows("SELECT quotation_id::text, item_seq::text, "
                + "coalesce(input_material_no,'(NULL)'), coalesce(material_ratio::text,'(NULL)'), "
                + "coalesce(source,'(NULL)'), coalesce(origin_id::text,'(NULL)') "
                + "FROM " + MBOM_REC + " WHERE material_no='" + existing + "' ORDER BY item_seq");
        List<Object[]> recERows = rows("SELECT quotation_id::text, item_seq::text, "
                + "coalesce(element_code,'(NULL)'), coalesce(content_pct::text,'(NULL)'), "
                + "coalesce(source,'(NULL)'), coalesce(origin_id::text,'(NULL)') "
                + "FROM " + EBOM_REC + " WHERE material_no='" + existing + "' ORDER BY item_seq");
        System.out.println("[AC-18④-b] " + MBOM_REC + " 被绑料号 " + existing
                + " 的实际行 (quotation_id/item_seq/input_material_no/material_ratio/source/origin_id) = "
                + fmt(recMRows));
        System.out.println("[AC-18④-b] " + EBOM_REC + " 被绑料号 " + existing
                + " 的实际行 (quotation_id/item_seq/element_code/content_pct/source/origin_id) = "
                + fmt(recERows));
        System.out.println("[AC-18④-b] 本单 = " + fx.quotationId()
                + "（上面的 quotation_id 必须等于本单 ⇒ 是**本次绑定**产生的投影，不是存量行）");

        // ⓵ 先断「有行」—— 🚨 这一步不可省：0 行时下面的「全部认领到」「无值差异」**全部空转成立**
        //    （0 == 0 / 0 个差异），整条 ④-b 会退化成上一轮那种假绿。
        assertNonEmpty(recMRows.size(), "AC-18④-b：" + MBOM_REC + " 的投影行数");
        assertNonEmpty(recERows.size(), "AC-18④-b：" + EBOM_REC + " 的投影行数");

        // ⓶ 每行的 quotation_id 都必须是本单（排除「读到存量行」这个假绿来源）
        for (Object[] r : recMRows) {
            assertEquals(fx.quotationId().toString(), String.valueOf(r[0]),
                    "AC-18④-b：" + MBOM_REC + " 该行的 quotation_id 应是本单（否则是存量行，判据被污染）。行 = "
                            + java.util.Arrays.toString(r));
        }
        for (Object[] r : recERows) {
            assertEquals(fx.quotationId().toString(), String.valueOf(r[0]),
                    "AC-18④-b：" + EBOM_REC + " 该行的 quotation_id 应是本单。行 = " + java.util.Arrays.toString(r));
        }

        // ⓷ origin_id 全部认领到「该料号的主表既有行」—— NULL 或认领不上都必须红
        long claimedM = count("SELECT count(*) FROM " + MBOM_REC + " r JOIN " + MBOM + " m ON m.id = r.origin_id "
                + "WHERE r.material_no='" + existing + "' AND m.material_no='" + existing + "'");
        long claimedE = count("SELECT count(*) FROM " + EBOM_REC + " r JOIN " + EBOM + " m ON m.id = r.origin_id "
                + "WHERE r.material_no='" + existing + "' AND m.material_no='" + existing + "'");
        System.out.println("[AC-18④-b 认领] origin_id 能 JOIN 上该料号主表既有行的条数："
                + MBOM_REC + "=" + claimedM + "/" + recMRows.size() + "，"
                + EBOM_REC + "=" + claimedE + "/" + recERows.size());
        assertEquals((long) recMRows.size(), claimedM,
                "AC-18④-b：" + MBOM_REC + " 的**每一行**都必须把 origin_id 认领到该料号主表的既有行，"
                        + "实际只认领到 " + claimedM + "/" + recMRows.size() + " 行。实际行 = " + fmt(recMRows)
                        + "\n  🚨 认领不上（含 origin_id 为 NULL）意味着投影把它当「**新增**一组 BOM」——"
                        + "核价回填会按新增**追加**进主表，那正是 AC-17「不许整组翻倍」要防的形态。"
                        + "\n  🚫 这不是「绑定路径不该投影」，是「投影没认领对」（修法方向不同，别混）。");
        assertEquals((long) recERows.size(), claimedE,
                "AC-18④-b：" + EBOM_REC + " 的每一行都必须认领到主表既有行，实际 " + claimedE + "/"
                        + recERows.size() + "。实际行 = " + fmt(recERows) + "　归因同上一条。");

        // ⓸ 被认领行与投影行的值**逐字一致** —— 防「认领对了行、却改了值」
        List<Object[]> diffM = rows("SELECT r.item_seq::text, "
                + "coalesce(r.input_material_no,'(NULL)') || ' vs ' || coalesce(m.input_material_no,'(NULL)'), "
                + "coalesce(r.material_ratio::text,'(NULL)') || ' vs ' || coalesce(m.material_ratio::text,'(NULL)') "
                + "FROM " + MBOM_REC + " r JOIN " + MBOM + " m ON m.id = r.origin_id "
                + "WHERE r.material_no='" + existing + "' AND ("
                + "  r.input_material_no IS DISTINCT FROM m.input_material_no "
                + "  OR r.material_ratio IS DISTINCT FROM m.material_ratio)");
        List<Object[]> diffE = rows("SELECT r.item_seq::text, "
                + "coalesce(r.element_code,'(NULL)') || ' vs ' || coalesce(m.element_code,'(NULL)'), "
                + "coalesce(r.content_pct::text,'(NULL)') || ' vs ' || coalesce(m.content_pct::text,'(NULL)') "
                + "FROM " + EBOM_REC + " r JOIN " + EBOM + " m ON m.id = r.origin_id "
                + "WHERE r.material_no='" + existing + "' AND ("
                + "  r.element_code IS DISTINCT FROM m.element_code "
                + "  OR r.content_pct IS DISTINCT FROM m.content_pct)");
        System.out.println("[AC-18④-b 逐字一致] 值有差异的行数：" + MBOM_REC + "=" + diffM.size()
                + "（差异明细 = " + fmt(diffM) + "），" + EBOM_REC + "=" + diffE.size()
                + "（差异明细 = " + fmt(diffE) + "）");
        assertEquals(0, diffM.size(),
                "AC-18④-b：" + MBOM_REC + " 的投影值必须与它认领的主表行**逐字一致**（沿用既有数据，"
                        + "🚫 不许改写），实际有 " + diffM.size() + " 行不一致：" + fmt(diffM)
                        + "\n  🔑 值不一致 ⇒ 核价回填会把主表既有数据**改掉**（本该判 UNCHANGED 却判成变更、升版）。");
        assertEquals(0, diffE.size(),
                "AC-18④-b：" + EBOM_REC + " 的投影值必须与认领的主表行逐字一致，实际有 " + diffE.size()
                        + " 行不一致：" + fmt(diffE) + "　归因同上一条。");

        // ⓹ source 值域只能是 QUOTE_DRAFT（D-18：_record 的 source 常量只有这一个；
        //    出现 IMPORT / MANUAL / QUOTE_BACKFILL 说明写错了表或混进了主表口径）
        List<Object> srcM = col("SELECT DISTINCT coalesce(source,'(NULL)') FROM " + MBOM_REC
                + " WHERE material_no='" + existing + "'");
        List<Object> srcE = col("SELECT DISTINCT coalesce(source,'(NULL)') FROM " + EBOM_REC
                + " WHERE material_no='" + existing + "'");
        System.out.println("[AC-18④-b source] " + MBOM_REC + "=" + srcM + " / " + EBOM_REC + "=" + srcE);
        assertEquals(List.of("QUOTE_DRAFT"), srcM,
                "AC-18④-b：" + MBOM_REC + " 的 source 值域应只有 'QUOTE_DRAFT'（D-18），实际 " + srcM);
        assertEquals(List.of("QUOTE_DRAFT"), srcE,
                "AC-18④-b：" + EBOM_REC + " 的 source 值域应只有 'QUOTE_DRAFT'（D-18），实际 " + srcE);

        // ── ⑤ sel_part_signature 零新增 ────────────────────────────────
        long sigAfter = count("SELECT count(*) FROM sel_part_signature WHERE customer_no='"
                + fx.customerNo() + "'");
        assertEquals(sigBefore, sigAfter,
                "AC-18⑤：sel_part_signature 应**零新增**（绑定路径不进指纹，D-12），基线 " + sigBefore
                        + " → 现在 " + sigAfter + "。进了指纹 ⇒ 下次同输入会误判成「已有产品可复用」");

        System.out.println("[AC-18] ①~③ / ④-a / ④-b / ⑤ 全部落库断言完成（⑥ 卡片渲染见 ac18_6 用例）");
    }

    /**
     * <b>AC-18⑥</b> —— 卡片渲染出被绑料号的既有 BOM 数据（非空）。
     *
     * <p>🚨 判据是「非空」：空列表 / 0 行 / 「—」/「加载中…」<b>一律不算通过</b>（AP-31 族）。
     * <p>⚠️ 单独成一条用例，避免它的失败把 ①~⑤ 的结论一起盖掉。
     * <p>📌 本条在<b>后端可观测层</b>断言：{@code quotation_line_component_data} 是编辑页
     * 卡片读的那份持久化数据。真实 UI 的「点开页签看到行」由 Playwright 用例
     * {@code cpq-frontend/e2e/task260910c-sc.spec.ts} 覆盖（AC-11 / AC-20 同一份）。
     */
    @Test
    @DisplayName("AC-18⑥ · 绑定后卡片渲染出既有 BOM 数据（非空）")
    void ac18_6_cardRendersExistingBom() {
        requireRecordLayer();
        Fx fx = newBoundFixture("C-ac18f");  // D-31：含「configure 之前绑 quotation.customer_template_id」
        String existing = seedExistingSalesMaterial(fx, "BND6");

        Response res = configure(fx, bindBody(C + "BIND-06-" + RUN_C, existing));
        requireImplementationPresent(res, "AC-18⑥");
        assertSubmitOk(res, "AC-18⑥ 绑定提交");
        recMbom(fx);

        // 先排除 BL-0202 这个混淆项（template_id 恒 NULL ⇒ 物化 0 条），再物化卡片
        String tplId = ensureLineTemplate(fx);
        System.out.println("[AC-18⑥] 生效 template_id=" + tplId);
        io.restassured.RestAssured.given().cookies(adminSession())
                .contentType(io.restassured.http.ContentType.JSON)
                .post("/api/cpq/quotations/" + fx.quotationId() + "/ensure-card-values").thenReturn();

        long cdRows = count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id='" + fx.quotationId() + "'");
        long tabRows = count("SELECT coalesce(sum(jsonb_array_length("
                + "coalesce(cd.snapshot_rows, cd.row_data, '[]'::jsonb))),0) "
                + "FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id='" + fx.quotationId() + "'");
        System.out.println("[AC-18⑥] 本单卡片组件数据 " + cdRows + " 条，页签总行数 " + tabRows);

        assertTrue(cdRows > 0,
                "AC-18⑥：本单应物化出卡片组件数据，实际 0 条。\n"
                        + "  ⚠️ 两种可能，归因不能混：(a) 绑定路径没建卡片 = 本 AC 的缺陷；"
                        + "(b) 选配建的行 template_id 恒 NULL（已登记 BL-0202）导致模板解析不到 = 夹具/既有约束。"
                        + "\n  👉 需要主线确认走 (a) 还是 (b) 再判红绿。");
        assertTrue(tabRows > 0,
                "AC-18⑥：卡片页签必须**有行**（渲染出被绑料号 " + existing + " 的既有 BOM），实际 0 行。"
                        + "\n  📌 该料号在主表确有 2 行 material_bom + 2 行 element_bom（夹具自检已断言）"
                        + "⇒ 0 行不是「没数据」，是「读不到既有数据」。");

        // 既有数据真的被读出来了 —— 断内容，不只断行数
        long hit = count("SELECT count(*) FROM quotation_line_component_data cd "
                + "JOIN quotation_line_item li ON li.id = cd.line_item_id "
                + "WHERE li.quotation_id='" + fx.quotationId() + "' AND ("
                + "  coalesce(cd.snapshot_rows,'[]'::jsonb)::text LIKE '%" + RECIPE_AGCU + "%' "
                + "  OR coalesce(cd.row_data,'[]'::jsonb)::text LIKE '%" + RECIPE_AGCU + "%')");
        System.out.println("[AC-18⑥] 卡片数据里命中既有投入料号 " + RECIPE_AGCU + " 的组件条数 = " + hit);
        assertTrue(hit > 0,
                "AC-18⑥：卡片数据里应能找到被绑料号既有 BOM 的投入料号 " + RECIPE_AGCU
                        + "（夹具在 " + MBOM + " 里为它落了 item_seq=1 的行），实际 0 条 ⇒ "
                        + "页签有行但不是这个料号的数据（渲染读错了轴）");
    }

    /**
     * <b>AC-19（边界 · 编号占用仍被硬拦）</b>原文：
     * 前置「{@code T260910-BIND-01} 已被 AC-18 占用」；操作「再走一次 AC-18 的流程，填同一编号」；
     * 断言「返回 <b>409 {@code CUSTOMER_PRODUCT_NO_TAKEN}</b>，{@code ds_quote_customer_part} 不新增第 2 行」。
     *
     * <p>⚠️ 前端错误码解析的既有坑（{@code api.md §3} 注）：错误信封的 {@code code} 有时装的是
     * HTTP 状态码数字。⇒ 本用例<b>同时</b>断 HTTP 409 与信封里的业务码字符串，
     * 两者缺一都可能让「拦住了但拦的理由不对」溜过去。
     */
    @Test
    @DisplayName("AC-19 · 重复客户产品编号 → 409 CUSTOMER_PRODUCT_NO_TAKEN，不新增第 2 行")
    void ac19_duplicateProductNoRejected() {
        requireRecordLayer();
        Fx fx = newBoundFixture("C-ac19");   // D-31：含「configure 之前绑 quotation.customer_template_id」
        String existing = seedExistingSalesMaterial(fx, "BND9");
        String productNo = C + "BIND-09-" + RUN_C;

        // 第 1 次：占用编号
        Response first = configure(fx, bindBody(productNo, existing));
        requireImplementationPresent(first, "AC-19 前置");
        assertSubmitOk(first, "AC-19 前置：第 1 次绑定应成功（占用编号）");
        recMbom(fx);
        long cpAfterFirst = count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='"
                + fx.customerNo() + "' AND customer_product_no='" + productNo + "'");
        assertEquals(1L, cpAfterFirst,
                "AC-19 前置自检：第 1 次绑定后该编号应恰好 1 行，实际 " + cpAfterFirst
                        + " ⇒ 前置没成立，「不新增第 2 行」是空验证");

        // 第 2 次：同编号 —— 必须被硬拦
        Response second = configure(fx, bindBody(productNo, existing));
        System.out.println("[AC-19] 第 2 次同编号提交 status=" + second.statusCode()
                + " body=" + second.asString());
        assertTrue(second.statusCode() != 404,
                "AC-19：端点 404 ⇒ 实现未落地，这是交付状态结论不是 AC 结论。响应=" + second.asString());
        assertEquals(409, second.statusCode(),
                "AC-19：客户产品编号已被占用时应返 **409**，实际 " + second.statusCode()
                        + "。200 ⇒ 编号唯一性在绑定路径上失守（一个客户编号绑到两个料号）。响应=" + second.asString());

        String code = firstNonNumericCode(second);
        System.out.println("[AC-19] 解析出的业务码 = " + code);
        assertNotNull(code,
                "AC-19：响应信封里应带业务码字符串，实际一个非纯数字的 code 都没找到。"
                        + "（api.md §3 注：现网 code 有时装 HTTP 状态码数字，前端只把非纯数字串当业务码）"
                        + "响应=" + second.asString());
        assertEquals("CUSTOMER_PRODUCT_NO_TAKEN", code,
                "AC-19：业务码应为 CUSTOMER_PRODUCT_NO_TAKEN（api.md §3），实际 '" + code
                        + "'。码不对 ⇒ 前端弹不出正确文案，用户看到的是通用错误");

        long cpAfterSecond = count("SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='"
                + fx.customerNo() + "' AND customer_product_no='" + productNo + "'");
        System.out.println("[AC-19] 该编号在 ds_quote_customer_part 的行数 = " + cpAfterSecond);
        assertEquals(1L, cpAfterSecond,
                "AC-19：被拒后 ds_quote_customer_part **不许新增第 2 行**，实际 " + cpAfterSecond + " 行");
    }

    /** 载荷可能在根层也可能在 {@code data} 下 —— 两个路径都试，并把实际取到的那个返回。 */
    private static Object firstNonNull(Response r, String... paths) {
        for (String path : paths) {
            Object v;
            try { v = r.jsonPath().get(path); } catch (RuntimeException e) { continue; }
            if (v != null) return v;
        }
        return null;
    }

    /** 取信封里第一个「非纯数字」的 code —— 与前端的解析口径一致（api.md §3 注）。 */
    private static String firstNonNumericCode(Response r) {
        for (String path : List.of("code", "data.code", "error.code", "errorCode")) {
            Object v;
            try { v = r.jsonPath().get(path); } catch (RuntimeException e) { continue; }
            if (v == null) continue;
            String s = String.valueOf(v);
            if (!s.isBlank() && !s.matches("\\d+")) return s;
        }
        return null;
    }

    private static String fmt(List<Object[]> rs) {
        StringBuilder sb = new StringBuilder();
        for (Object[] r : rs) {
            sb.append("\n    ");
            for (Object c : r) sb.append('[').append(c).append(']');
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }
}
