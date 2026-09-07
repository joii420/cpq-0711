package com.cpq.semanticgraph;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🪦 <b>留碑：AC-59「上下文缺失必须显式报错，不得静默返回 0 行」已于 2026-09-05 作废</b>
 * （用户裁决，{@code 需求文档.md} 的 {@code D-123}）。
 *
 * <p>本类原覆盖 需求文档.md §3.6b 闭包机制统一为「主树供数组」（{@code D-50 ~ D-53}）—— AC-58 ~ AC-61
 * 中的 AC-59（AC-58 / AC-60 属 T-5 真实渲染链路与前端 grep，本就不在本类）。
 *
 * <h3>📌 被作废的方法做的是什么（保留历史价值说明，勿删）</h3>
 * {@code ac59_missingContextMustErrorNotSilentlyReturnZeroRows()}：
 * <ol>
 *   <li>前置断言编译产物含 {@code = ANY(:total_material_no)}；</li>
 *   <li><b>构造「上下文缺失」的手段是「只给 {@code customerCode}、完全不给 {@code partNo}」</b>；</li>
 *   <li>硬断言 {@code /preview} 必须 <b>非 2xx</b>，且<b>不得</b>出现「200 + {@code rowCount=0}」——
 *       因为 {@code ANY(NULL)} 恒 0 行且不报错，会把配置错误伪装成「这个客户没数据」。</li>
 * </ol>
 * 🔑 <b>它要防的那个失败形态（静默 0 行）是真问题，而且后来反复应验</b> ——
 * {@code D-115}（{@code /preview} 核价侧恒 0 行）与 {@code D-119}（无 {@code partNo} 时恒 1 行）
 * 都是同一族缺陷的第三、第四次。<b>作废的是这份实现的构造手段，不是那份担心。</b>
 *
 * <h3>为什么必须作废这份实现（裁决理由，不是「反正跑不了就删掉」）</h3>
 * 它的第 2 步<b>已经被两条后续裁决直接推翻</b>，继续留着会变成一条「与现行契约相反」的红：
 * <ul>
 *   <li><b>{@code D-70}</b>（2026-08-26）：<b>改 {@code /preview} 收窄口径 —— 无 {@code partNo} 时注入
 *       「该客户下的全部料号」</b>。裁决原文明确否掉了「给用例补 {@code partNo}」这条路，理由是
 *       「契约从没写过『预览必须指定料号』，改用例是把设计缺口藏进测试」。
 *       ⇒ <b>「不给 partNo」从此是<u>合法输入</u>，不再是「上下文缺失」。</b></li>
 *   <li><b>{@code D-119}</b>（2026-09-04）：无 {@code partNo} 的核价预览<b>绕过料号桥、走直接轴收窄</b>
 *       （新增 {@code compile(..., skipNarrowPredicates)}）。⇒ 该场景现在有明确定义的正确行为，
 *       正确结果是<b>返回整表基准行数</b>（主线实测 12 行 = {@code ds_cost_basic_material} 整表 12 行），
 *       而不是报错。</li>
 * </ul>
 * ⇒ 本方法的硬断言（{@code status >= 400}）在现行契约下<b>必然为红，且红得没有意义</b> ——
 * 它测的是一个已被裁决改掉的行为。
 *
 * <h3>接替者（这道防线交给谁了）</h3>
 * <ul>
 *   <li><b>{@code D-122} / {@code B-53} 编译期护栏</b>（本期已做）：编译产物里「锚点直接轴谓词」与
 *       「NARROW 桥半连接」<b>不允许共存</b>，共存即抛异常并点名两条谓词原文 + dialect + 锚点 + 边 id。
 *       这正是 {@code D-119} 那类「两条收窄谓词交集为空 ⇒ 0 行、不抛异常、不告警、不留诊断」的<b>结构性根治</b>，
 *       比「靠一个特定输入去撞报错」强得多。其验收判据是 {@code D-122} 规定的证伪实验四步。</li>
 *   <li><b>{@code D-115} 重写的 0 行诊断</b>：核价侧 0 行按「销售料号 → 料号桥 → 生产料号」三步给排查顺序，
 *       并明说第②步（生产料号未填）是正常业务状态。配套验收：
 *       {@code Sec35.ac27}（QUOTE 侧 0 行必须给<b>可操作</b>诊断，本轮已换 v9 对象保留）
 *       + <b>{@code AC-112②}</b>（桥收窄后 0 行、不抛异常、diagnostics 非空）。</li>
 *   <li><b>{@code AC-108}</b>：三方言产物都必须带轴收窄谓词 —— 原方法的第 1 步（前置）由它承担。</li>
 * </ul>
 *
 * <h3>🚫 这里没有留成「永久 skip 的死用例」</h3>
 * 项目规矩 {@code skip != pass}，所以本类<b>不用 {@code @Disabled}</b>。
 * {@link #retiredAc59_tombstone_premiseStillHolds()} 是一条<b>会真的执行</b>的作废前提守卫。
 *
 * @see com.cpq.task260819v9.V9CompileArtifactTest AC-108 轴收窄 / AC-112② 0 行且诊断非空
 */
@QuarkusTest
@TestProfile(SemanticGraphTestSupport.RbacOffProfile.class)
@DisplayName("Sec36bClosureUnificationTest — 🪦 AC-59 已作废（2026-09-05 D-123；构造手段被 D-70/D-119 推翻），仅留前提守卫")
class Sec36bClosureUnificationTest {

    @Inject
    EntityManager em;

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    @Test
    @DisplayName("🪦 AC-59 作废前提守卫: 闭包统一机制仍在（桥边为 NARROW、无二跳、无 V6 BOM 节点）")
    void retiredAc59_tombstone_premiseStillHolds() {
        long activeNodes = scalar("SELECT count(*) FROM semantic_node WHERE status='ACTIVE'");
        long narrowEdges = scalar("SELECT count(*) FROM semantic_edge WHERE edge_kind='NARROW' AND status='ACTIVE'");
        long v6BomNodes = scalar("SELECT count(*) FROM semantic_node "
                + "WHERE physical_table IN ('material_bom_item','element_bom_item')");
        long twoHop = scalar("SELECT count(*) FROM semantic_edge e1 JOIN semantic_edge e2 "
                + "ON e2.from_node_id = e1.to_node_id WHERE e1.status='ACTIVE' AND e2.status='ACTIVE'");

        System.out.println("[🪦 AC-59 留碑] ACTIVE 节点=" + activeNodes + " · NARROW 边=" + narrowEdges
                + " · V6 BOM 节点=" + v6BomNodes + " · 二跳路径=" + twoHop);

        assertTrue(activeNodes > 0,
                "🚨 语义图为空（ACTIVE 节点 " + activeNodes + " 个）—— 这不是「作废前提成立」，是种子没就位。"
                        + "本条判定为【未验证】，🚫 不许当成通过。");

        assertEquals(0L, v6BomNodes,
                "🚦 AC-59 的作废前提被推翻：V6 的 material_bom_item / element_bom_item 又回到图里了"
                        + "（命中 " + v6BomNodes + " 个节点）。\n"
                        + "  AC-59 是 2026-09-05 按 D-123 作废的，技术理由是「它构造『上下文缺失』的手段"
                        + "（不给 partNo）已被 D-70/D-119 明确改成合法输入」。\n"
                        + "  V6 BOM 节点回来了 ⇒ 闭包/收窄的口径可能又变了，必须重新评估"
                        + "「上下文缺失会不会重新变成一种静默 0 行」。");

        assertTrue(narrowEdges > 0,
                "🚦 AC-59 的作废前提被推翻：图里一条 NARROW 桥边都没有（实际 " + narrowEdges + " 条）。\n"
                        + "  D-110 把料号桥做成「输入收窄（半连接）」正是接替 AC-59 那份担心的机制之一"
                        + "（配合 D-122/B-53 编译期护栏）。桥没了 ⇒ 收窄语义变了，必须重新评估这条防线由谁承担。");

        assertEquals(0L, twoHop,
                "🚦 AC-59 相关前提被推翻：图里出现了 " + twoHop + " 条二跳路径。\n"
                        + "  v9 的星形图（各锚点 → 料号桥、桥无出边）是「收窄谓词只可能有两个来源」这一判断的基础，"
                        + "而 D-122/B-53 护栏正是按「两条轴收窄谓词不许共存」写的。\n"
                        + "  出现多跳 ⇒ 收窄来源可能多于两条，护栏的覆盖面必须重新评估。");
    }
}
