package com.cpq.task260910c;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>AC-14</b> —— 两个材质 70 / 30 时，占比<b>原样落到主表</b>
 * {@code ds_quote_material_bom.material_ratio}。
 *
 * <h3>🔴 本类原名 {@code RecordRatioAcTest}，为<b>方案②</b>而写；按 D-22 改写</h3>
 * 原版断的是 <b>S-5 / B-16</b>：「{@code _record} 投影补 {@code material_ratio}」（D-10），
 * 并附带一条方案② 的不变量 {@code assertEquals(0L, mainM)}（新铸料号在主表零新增）。
 * <p>两处都已作废：
 * <ul>
 *   <li><b>D-14</b>：带版本表直写主表 ⇒ 「主表零新增」<b>整条反转</b>（现在应是 <b>2 行</b>）；</li>
 *   <li><b>D-10 + D-19</b>：{@code material_ratio} 由<b>主表直写天然带上</b>，渲染不再依赖 {@code _record}
 *       ⇒ B-16（补投影）由阻塞项<b>降为优化项、本期不做、登记 BACKLOG</b>；
 *       用户在 D-19 明确选「甲 = <b>接受 {@code _record.material_ratio} 为空的现状，不改
 *       {@code syncRecords}</b>」。实查支撑：{@code DsBackfillCollector} 的回填 {@code out}
 *       从<b>主表基底整行</b>起手逐列保留，表征列若 {@code _record} 未带 key 就 {@code continue} 跳过
 *       ⇒ {@code _record} 缺该列<b>抹不掉主表的值</b>。</li>
 * </ul>
 * ⇒ {@code test.md §3} 的 AC-14 关键断言已改为：<b>验主表侧</b>
 * 「两材质 70/30 ⇒ {@code ds_quote_material_bom.material_ratio} =
 * {@code 70.000000000000} / {@code 30.000000000000}（直写本来就带）」。
 *
 * <h3>🚨 已上报的文档口径冲突（🚫 不是我放宽断言）</h3>
 * {@code 需求文档.md §③ S-5} 的 <b>AC-14 原文本身尚未随 D-10/D-19 改写</b>，它仍写着
 * 「① {@code ds_quote_material_bom_record} 出现 2 行，{@code material_ratio} 分别为
 * {@code 70.000000000000} 与 {@code 30.000000000000}（🚫 不许为 NULL）」。
 * <p>该条与 <b>D-19 的裁决（接受 {@code _record.material_ratio} 为空）互相矛盾</b>，
 * 按原文断会必红，而红的原因是<b>用户已明确接受的现状</b>，不是实现缺陷。
 * ⇒ 本类按 {@code test.md} 矩阵的更新口径断<b>主表</b>，
 * 并把 {@code _record.material_ratio} 的实际值<b>打印出来当诊断</b>（🚫 不断言、也 🚫 不隐瞒），
 * 该冲突已在回报里点名请主线裁定文档回写。
 *
 * <h3>⚠️ AC-14② 不属于本片</h3>
 * ②「核价通过后主表两行的 {@code material_ratio} 逐字相同」要走<b>核价通过</b>
 * ⇒ 写 {@code ds_quote_*} 主表 + {@code _history}，属全局写，按 {@code test.md §1} 归
 * <b>S-全局</b> 片（与 AC-12 / AC-17 同）。本片只验 ①，🚫 不做核价通过，也<b>不声称</b>验过它。
 *
 * <h3>📌 「12 位小数」这个断言形态的依据（只读查证，非实现反推）</h3>
 * {@code information_schema.columns}：{@code ds_quote_material_bom.material_ratio}
 * 是 {@code numeric(26,12)} ⇒ 落库文本必然是 12 位小数，{@code 70} 会呈现为
 * {@code 70.000000000000}。所以 AC 给的那个字面值<b>是可断的</b>，不是笔误。
 */
@QuarkusTest
@DisplayName("AC-14 · 两材质 70/30 ⇒ 主表 material_ratio 原样落 70/30")
class MaterialRatioAcTest extends Task260910CBase {

    @Test
    @DisplayName("AC-14① · ds_quote_material_bom 两行 material_ratio = 70.000000000000 / 30.000000000000")
    void ac14_mainTableCarriesMaterialRatio() {
        requireRecordLayer();
        Fx fx = newBoundFixture("C-ac14");   // D-31：含「configure 之前绑 quotation.customer_template_id」

        Response res = configure(fx, submitBody(C + "AC14-" + RUN_C,
                newPart(freshPartName("AC14双材质零件"), "spec-234", "234", "11",
                        List.of(material(RECIPE_AGCU, CONFIG_AGCU, "70"),
                                material(RECIPE_AGNI, CONFIG_AGNI, "30")),
                        List.of(PROC_CLEAN))));
        requireImplementationPresent(res, "AC-14");
        assertSubmitOk(res, "AC-14 选配提交（两材质 70/30）");
        assertFreshlyMinted(res, "AC-14");

        String partNo = latestLinePartNo(fx);
        System.out.println("[AC-14] 新铸销售料号 = " + partNo + " / 报价单 = " + fx.quotationId());

        // 🚨 先证明有行 —— 否则「每一行的 ratio 都对」在 0 行时也成立（testing.md §3 第 3 号陷阱）
        List<Object[]> mb = mainMbomRows(partNo);
        System.out.println("[AC-14①] 主表 " + MBOM
                + " 实际行 (item_seq/input_material_no/output_material_type/material_ratio/version_no/source/customer_no/row_fingerprint) = "
                + dump(mb));
        assertNonEmpty(mb.size(), "AC-14 前置：" + MBOM + " 主表对新铸料号 " + partNo + " 的行数");
        assertEquals(2, mb.size(),
                "AC-14①：两个材质应在主表 " + MBOM + " 写出 **2 行**（D-14 直写），实际 " + mb.size()
                        + " 行。行数不对时 ratio 断言就算全过也说明不了问题。实际值 = " + dump(mb));

        List<String> ratios = mb.stream().map(x -> String.valueOf(x[3])).toList();
        List<String> inputs = mb.stream().map(x -> String.valueOf(x[1])).toList();
        System.out.println("[AC-14①] 主表落库 (input_material_no → material_ratio) = "
                + inputs + " → " + ratios);

        assertTrue(ratios.stream().noneMatch("(NULL)"::equals),
                "AC-14①：主表 material_ratio **不许为 NULL** —— D-14 后这一列由直写天然带上。"
                        + "实际落库值 = " + ratios
                        + "。\n  📌 它为空的后果：核价回填时行指纹对不上 ⇒ 无意义升版"
                        + "（反面实证：0526-2609000006 曾升到 v2 source=QUOTE_BACKFILL）⇒ AC-12 终态必红。");
        assertTrue(ratios.contains("70.000000000000"),
                "AC-14①：占比 70 应原样落库为 70.000000000000（numeric(26,12)），实际 = " + ratios
                        + "（对应投入料号 " + inputs + "）");
        assertTrue(ratios.contains("30.000000000000"),
                "AC-14①：占比 30 应原样落库为 30.000000000000（numeric(26,12)），实际 = " + ratios
                        + "（对应投入料号 " + inputs + "）");

        // 占比要落在对的材质上 —— 只断「有 70 有 30」时，两个值互换也会绿
        for (Object[] row : mb) {
            String input = String.valueOf(row[1]);
            String ratio = String.valueOf(row[3]);
            if (RECIPE_AGCU.equals(input)) {
                assertEquals("70.000000000000", ratio,
                        "AC-14①：材质 " + RECIPE_AGCU + " 的占比应是 70，实际 " + ratio
                                + " ⇒ 占比落到了错的材质上（只断「有 70 有 30」时两值互换也会绿）");
            } else if (RECIPE_AGNI.equals(input)) {
                assertEquals("30.000000000000", ratio,
                        "AC-14①：材质 " + RECIPE_AGNI + " 的占比应是 30，实际 " + ratio);
            }
        }

        // AC-10④ 的同型断言：_record 仍应投影出对应行（角色 = 主数据投影 / 回填来源）
        requireCardDataMaterialized(fx, "AC-14 附带");
        long recM = recRows(MBOM_REC, fx, partNo);
        System.out.println("[AC-14] " + MBOM_REC + " 本单该料号行数 = " + recM);
        assertNonEmpty(recM, "AC-14 附带（AC-10④ 同型）：" + MBOM_REC + " 在本单该料号下的行数");
        assertEquals(2L, recM,
                "AC-14 附带：两个材质应投影出 **2 行** _record，实际 " + recM + " 行");

        // 🔎 诊断（🚫 不是断言）：D-19 已裁决接受 _record.material_ratio 为空的现状
        List<Object> recRatios = col("SELECT coalesce(material_ratio::text,'(NULL)') FROM " + MBOM_REC
                + " WHERE quotation_id='" + fx.quotationId() + "' AND material_no='" + partNo
                + "' ORDER BY item_seq");
        System.out.println("[AC-14 诊断] " + MBOM_REC + ".material_ratio 实际值 = " + recRatios
                + "\n  ⚠️ **本行是诊断不是断言**：需求文档 AC-14① 原文要求这里非 NULL，"
                + "但 D-19 用户已明确选「甲 = 接受现状、不改 syncRecords」，B-16 降为优化项本期不做。"
                + "\n  ⇒ 两处文档口径冲突（需求文档 AC-14 原文 vs D-10/D-19 + test.md 矩阵），"
                + "已报主线请裁定回写；🚫 我没有把该断言改松，而是把它整体移出本片的判据并如实登记。");

        System.out.println("[AC-14] ⚠️ ②「核价通过后主表两行 material_ratio 逐字相同」不属本片 —— "
                + "核价通过写主表 + _history 属全局写，归 S-全局 片（test.md §1）。本片不声称验过它。");
    }
}
