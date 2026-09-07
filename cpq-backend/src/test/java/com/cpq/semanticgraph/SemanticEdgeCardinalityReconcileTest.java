package com.cpq.semanticgraph;

import com.cpq.semanticgraph.entity.SemanticEdge;
import com.cpq.semanticgraph.entity.SemanticEdgeKey;
import com.cpq.semanticgraph.entity.SemanticNode;
import com.cpq.semanticgraph.entity.SemanticNodeColumn;
import com.cpq.semanticgraph.service.DiscriminatorResolver;
import com.cpq.semanticgraph.service.SemanticGraphValidator;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI 断言① 边基数（task-260819 B-17，AC-35 / AC-121）。
 *
 * <p>🔑 「从表读边定义」：本测试查询 {@code semantic_edge} 表（不是任何 Java 常量/枚举声明）。
 * 这正是 D-27 之后 AC-35 的口径变化——真源在库里，CI 断言也必须查库，不能再查代码。
 *
 * <h3>🚦 2026-09-04 主线裁决（S-29-a，方案 A：改成不变量 + 阳性对照，不作废）</h3>
 * <p>裁决理由：校验器 {@code checkEdgeCardinality} 本身没坏，「有人往图里加一条撒谎的
 * {@code MANY_TO_ONE} 边」这个风险也没消失 —— v9 换图后消失的只是「恰好有对象可查」。
 * 此时删护栏 = 把未来的保护一起删了。⇒ <b>护栏保留，另配一个真正跑得起来的阳性对照</b>。
 *
 * <p>本类现在断言两件事，两者共用同一个 {@code asserted} 计数器（下限守卫 {@code asserted > 0}
 * 保证「断言从未执行」这类假绿不会再发生）：
 * <ol>
 *   <li><b>不变量（当前空真，将来生效）</b>：凡 {@code MANY_TO_ONE} 且 to 节点有物理表的边，
 *       都必须通过基数校验。v9 图上此类边 = 0 条（唯一的 {@code MANY_TO_ONE} 是
 *       {@code ELEMENT_BOM → FUNC_ELEMENT_PRICE}，to 是 FUNCTION 节点、无物理表），
 *       所以这一半现在跑不出断言 —— 但只要将来有人加一条指向实体表的 {@code MANY_TO_ONE} 边，
 *       它立刻恢复效力。</li>
 *   <li><b>阳性对照（真的查库、真的跑断言）</b>：28 条 {@code NARROW} 边的
 *       <b>输入收窄唯一性</b>。D-110 把料号桥从「输出 LOOKUP」改成「输入收窄」后，
 *       {@code AC-112①（不扇出）} 的全部安全性都压在一个事实上 ——
 *       桥表 {@code ds_quote_material} 上<b>销售料号 {@code material_no} 唯一</b>，
 *       因而 {@code WHERE material_no = ANY(:total_material_no)} 至多解析出一个
 *       {@code production_no}，收窄永不翻倍。这个断言走的是<b>与不变量同一个校验器函数</b>
 *       {@link SemanticGraphValidator#checkEdgeCardinality}，所以它同时是「校验器在新图上确实能用」的证明。</li>
 * </ol>
 *
 * <p>⚠️ 阳性对照<b>不是常量比常量</b>：桥表名与右键列从 {@code semantic_edge}/{@code semantic_edge_key}
 * 读；输入列的存在性回到 {@code semantic_node_column} 核对；唯一性由校验器<b>实际扫表</b>得出；
 * 断言前先断言「边集非空」「桥表非空行」，防止它自己又变成空跑。
 *
 * <p>反证型（AC-35② / AC-121）：{@link #corruptedCardinality_mustFail()}。
 * 🚨 <b>2026-09-04 换对象</b>：原来喂的是 V6 表 {@code element_bom_item} —— 它物理上还在（V6 表从未 DROP），
 * 所以校验器照样 FAIL、用例照样绿，但 V413 已把它从语义图里删光 ⇒ 那个绿只证明「校验器对一张
 * <b>图外</b>的表能算重复」，证明不了它在新图上有效。现改喂<b>新图内</b>的
 * {@code ds_quote_material}（{@code QUOTE_MATERIAL_BRIDGE}，ACTIVE）按 {@code production_no}
 * 单列 —— 这正是「有人把桥边反向声明成 {@code production_no} 唯一」时校验器必须抓到的谎。
 * 之所以不直接改 {@code semantic_edge} 表后 rollback，是因为该表是全应用共享的不可变快照来源。
 */
@QuarkusTest
@DisplayName("SemanticEdgeCardinalityReconcileTest — AC-35/AC-121 边基数断言（从表读取，非代码声明）")
public class SemanticEdgeCardinalityReconcileTest {

    @Inject SemanticGraphValidator validator;
    @Inject EntityManager em;

    /**
     * 料号桥的<b>收窄输入列</b>（D-110「输入收窄」方向：销售料号 → 生产料号）。
     *
     * <p>登记成显式表，是为了让「图里新增了一座桥、却没人想过它扇不扇出」这件事<b>硬失败</b>，
     * 而不是被一个「找不到就跳过」的分支悄悄放过。新增桥 ⇒ 必须在这里补一行并说明它的输入列。
     */
    private static final Map<String, String> BRIDGE_NARROWING_INPUT = Map.of(
            "ds_quote_material", "material_no");

    @Test
    @TestTransaction
    @DisplayName("边基数不变量：① MANY_TO_ONE 边不得 FAIL（当前空真，将来生效）；② NARROW 桥的收窄输入必须唯一（阳性对照，真跑）")
    void allManyToOneEdges_passOrThin() {
        // 🚨 2026-09-04（S-29-a）：本方法此前**只有 edges.isEmpty() 这一道守卫**，
        //    而循环体里有两处 `continue`（FUNCTION 节点无物理表 / 无独立右键）。
        //    v9 新图上唯一的 MANY_TO_ONE 边指向 FUNCTION 节点 FUNC_ELEMENT_PRICE（physical_table 为 NULL）
        //    ⇒ 每次都 continue ⇒ **一条断言都没跑，测试却报绿**（四类假绿里的「断言从未执行」）。
        //    裁决方案 A：护栏留着，另配阳性对照让 asserted 真有东西可数。
        int asserted = 0;

        // ── ① 不变量：MANY_TO_ONE 边（当前 0 条命中，将来加边即生效）────────────────
        List<SemanticEdge> m2o = SemanticEdge.list("cardinality = ?1 and status = 'ACTIVE'", "MANY_TO_ONE");
        for (SemanticEdge e : m2o) {
            SemanticNode to = SemanticNode.findById(e.toNodeId);
            assertNotNull(to, "边 " + e.id + " 的 to_node 必须存在（RESTRICT FK 保证）");
            if (to.physicalTable == null) continue; // FUNCTION 节点无物理表，基数断言不适用

            List<SemanticEdgeKey> keys = SemanticEdgeKey.list("edgeId = ?1 order by seq", e.id);
            if (keys.isEmpty()) continue; // SAME/PRICE 等无独立右键的边不适用本断言

            List<String> rightCols = keys.stream().map(k -> k.rightColumn).sorted().toList();
            SemanticNode from = SemanticNode.findById(e.fromNodeId);
            // MATERIAL_BOM 的判别式按来向边动态推导（AC-6），不能只看 to.discriminator（NULL）——
            // 见 DiscriminatorResolver 类注释，与 SemanticGraphService 生产路径用同一份解析逻辑。
            SemanticGraphValidator.CheckResult r =
                    validator.checkEdgeCardinality(to.physicalTable, rightCols, DiscriminatorResolver.resolve(from, to));
            assertNotEquals("FAIL", r.status,
                    "边 " + e.id + " (" + to.physicalTable + "." + String.join(",", rightCols) + ") 基数断言失败: " + r.message);
            asserted++;
        }
        System.out.println("[CI①/不变量] MANY_TO_ONE 边 " + m2o.size() + " 条，其中有物理表且有独立右键的 = " + asserted + " 条");

        // ── ② 阳性对照：NARROW 边的输入收窄唯一性（AC-112① 不扇出的全部安全性所在）──
        List<SemanticEdge> narrow = SemanticEdge.list("edgeKind = ?1 and status = 'ACTIVE'", "NARROW");
        assertFalse(narrow.isEmpty(),
                "🚨 CI 断言① 阳性对照失去对象：semantic_edge 里 edge_kind='NARROW' 且 ACTIVE 的边 = 0 条。\n"
                        + "  实测（2026-09-04，库 cpq_db_0724）应为 28 条（全部指向 QUOTE_MATERIAL_BRIDGE）。\n"
                        + "  为 0 说明料号桥被整体摘除或改名 ⇒ 判据形态需重新定义，🚫 不许当成通过。");

        // 桥表 → 该桥上所有被用作右键的列（从边定义读，不写死）
        Map<String, Set<String>> bridgeRightCols = new java.util.LinkedHashMap<>();
        for (SemanticEdge e : narrow) {
            SemanticNode to = SemanticNode.findById(e.toNodeId);
            assertNotNull(to, "NARROW 边 " + e.id + " 的 to_node 必须存在");
            assertNotNull(to.physicalTable,
                    "NARROW 边 " + e.id + " 的 to 节点 " + to.nodeKey + " 无 physical_table —— 桥必须落在实体表/视图上");
            List<SemanticEdgeKey> keys = SemanticEdgeKey.list("edgeId = ?1 order by seq", e.id);
            assertFalse(keys.isEmpty(), "NARROW 边 " + e.id + "（" + to.nodeKey + "）无右键声明，收窄无从生成");
            bridgeRightCols.computeIfAbsent(to.physicalTable, k -> new LinkedHashSet<>())
                    .addAll(keys.stream().map(k -> k.rightColumn).toList());
        }
        System.out.println("[CI①/阳性] NARROW 边 " + narrow.size() + " 条，落在 " + bridgeRightCols.size()
                + " 张桥表上：" + bridgeRightCols);

        for (Map.Entry<String, Set<String>> en : bridgeRightCols.entrySet()) {
            String bridgeTable = en.getKey();
            Set<String> rightCols = en.getValue();

            String inputCol = BRIDGE_NARROWING_INPUT.get(bridgeTable);
            assertNotNull(inputCol,
                    "🚨 图里出现了一座未登记收窄输入列的桥：" + bridgeTable + "（右键=" + rightCols + "）。\n"
                            + "  收窄形如 `锚点.<右键> IN (SELECT <右键> FROM " + bridgeTable + " WHERE <输入列> = ANY(:total_material_no))`，\n"
                            + "  「输入列是否唯一」决定它会不会扇出（AC-112①）。请在 BRIDGE_NARROWING_INPUT 补一行并说明，\n"
                            + "  🚫 不许让这个分支静默跳过 —— 那正是本用例上一版空跑的成因。");

            // 前提 a：输入列真的在语义图里声明过（回到图核对，不是纯常量）
            List<SemanticNode> bridgeNodes = SemanticNode.list("physicalTable = ?1 and status = 'ACTIVE'", bridgeTable);
            assertFalse(bridgeNodes.isEmpty(), "桥表 " + bridgeTable + " 在 semantic_node 里没有 ACTIVE 节点");
            for (SemanticNode bn : bridgeNodes) {
                Set<String> declared = SemanticNodeColumn.<SemanticNodeColumn>list("nodeId", bn.id)
                        .stream().map(c -> c.dbColumn).collect(Collectors.toSet());
                assertTrue(declared.contains(inputCol),
                        "节点 " + bn.nodeKey + "(" + bn.dialect + ") 未声明收窄输入列 " + inputCol + "，已声明=" + declared);
                for (String rc : rightCols) {
                    assertTrue(declared.contains(rc),
                            "节点 " + bn.nodeKey + "(" + bn.dialect + ") 未声明右键列 " + rc + "，已声明=" + declared);
                }
            }

            // 前提 b：桥表必须有真实行 —— 空表上「唯一」恒成立，是典型的断言空跑
            long rows = ((Number) em.createNativeQuery("SELECT count(*) FROM " + bridgeTable)
                    .getSingleResult()).longValue();
            assertTrue(rows > 0,
                    "🚨 桥表 " + bridgeTable + " 0 行：空表上「输入列唯一」恒成立，本阳性对照会退化成空跑。\n"
                            + "  这不是产品缺陷，是数据前置未就绪 ⇒ 本条判定为【未验证】。");

            // 断言 b1（业务事实，直接查库）：一个输入值不得映射到多个右键值 ⇒ 收窄不扇出
            String dupSql = "SELECT count(*) FROM (SELECT " + inputCol + " FROM " + bridgeTable
                    + " GROUP BY " + inputCol + " HAVING count(DISTINCT (" + String.join(",", rightCols) + ")) > 1) t";
            long fanOut = ((Number) em.createNativeQuery(dupSql).getSingleResult()).longValue();
            long distinctInput = ((Number) em.createNativeQuery(
                    "SELECT count(DISTINCT " + inputCol + ") FROM " + bridgeTable).getSingleResult()).longValue();
            System.out.println("[CI①/阳性] " + bridgeTable + ": 行数=" + rows + "，distinct(" + inputCol + ")="
                    + distinctInput + "，映射到多个 " + rightCols + " 的输入值 = " + fanOut + " 个");
            assertEquals(0L, fanOut,
                    "🚨 AC-112① 的安全性被打破：" + bridgeTable + " 上有 " + fanOut + " 个 " + inputCol
                            + " 值映射到多个 " + rightCols + " ⇒ 桥收窄会扇出、核价侧行数会翻倍。\n  取证 SQL: " + dupSql);

            // 断言 b2（同一个校验器函数，证明它在新图上确实能用）：输入列本身在桥上唯一
            SemanticGraphValidator.CheckResult r = validator.checkEdgeCardinality(bridgeTable, List.of(inputCol));
            System.out.println("[CI①/阳性] checkEdgeCardinality(" + bridgeTable + ", [" + inputCol + "]) → "
                    + r.status + " / " + r.message);
            assertNotEquals("FAIL", r.status,
                    "🚨 收窄输入列 " + bridgeTable + "." + inputCol + " 在桥上不唯一（校验器判 FAIL: " + r.message + "）。\n"
                            + "  ⇒ `WHERE " + inputCol + " = ANY(:total_material_no)` 会选出多行，收窄解析出多个 "
                            + rightCols + "，AC-112①「不扇出」不再成立。");
            asserted++;
        }

        // ── 下限守卫（S-29）：真正跑过断言的边数必须 > 0，否则本方法等于空跑
        assertTrue(asserted > 0,
                "🚨 CI 断言① 空跑：MANY_TO_ONE 边 " + m2o.size() + " 条 + NARROW 边 " + narrow.size()
                        + " 条，但**真正执行了基数断言的 = " + asserted + " 条**。\n"
                        + "  两条腿同时断了才可能走到这里：MANY_TO_ONE 全被 continue 掉（这是 v9 图上的常态，"
                        + "唯一一条指向 FUNCTION 节点 FUNC_ELEMENT_PRICE，physical_table=NULL），"
                        + "且 NARROW 桥一张都没解析出来。\n"
                        + "  ⇒ 判据形态需重新定义，🚫 不许当成通过。");
    }

    @Test
    @DisplayName("反证：右键选得不够窄（新图内真实存在重复）→ 断言必须 FAIL 并指出重复键（AC-35② / AC-121）")
    void corruptedCardinality_mustFail() {
        // 🚨 2026-09-04（S-29-a）反证对象换新：从 V6 表 element_bom_item 换成新图内的 ds_quote_material。
        //    element_bom_item 物理上仍在（V6 表从未 DROP），所以旧写法照样 FAIL、照样绿 ——
        //    但 V413 已把它从语义图里删光，那个绿只证明「校验器对一张图外的表能算重复」。
        //
        //    换成 ds_quote_material 按 production_no 单列：这不是随便挑的对象，而是
        //    **料号桥的反方向**。D-110 明写方向决定一切 —— 销售料号→生产料号唯一（阳性对照断言的就是它），
        //    反方向「一个生产料号对生产多个销售料号」天然重复（AC-112① 的夹具 TEST0813-P01-PROD 即是）。
        //    ⇒ 若有人把桥边反向声明成「production_no 唯一的 MANY_TO_ONE」，校验器必须抓到。
        String table = "ds_quote_material";
        String liarColumn = "production_no";

        // 前提 ①：该对象必须在新图内（不是图外的表）
        long inGraph = SemanticNode.count("physicalTable = ?1 and status = 'ACTIVE'", table);
        System.out.println("[CI①/反证] " + table + " 在 semantic_node(ACTIVE) 命中节点数 = " + inGraph);
        assertTrue(inGraph > 0,
                "🚨 反证对象已不在新图内：" + table + " 在 semantic_node 里命中 " + inGraph + " 个 ACTIVE 节点。\n"
                        + "  对一张图外的表做反证，只能证明「校验器能算重复」，证明不了它在 v9 新图上有效。\n"
                        + "  ⇒ 需重新挑选新图内、且该列组合真有重复的对象；在此之前本反证判定为【未验证】。");

        // 前提 ②：该列真的有重复 —— 否则「校验器没报 FAIL」是因为数据本来就唯一，不是校验器坏了
        long dupGroups = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM (SELECT " + liarColumn + " FROM " + table
                        + " WHERE " + liarColumn + " IS NOT NULL GROUP BY " + liarColumn + " HAVING count(*) > 1) t")
                .getSingleResult()).longValue();
        System.out.println("[CI①/反证] " + table + " 按 " + liarColumn + " 单列分组，非空重复组数 = " + dupGroups);
        assertTrue(dupGroups > 0,
                "🚨 反证前提不成立：" + table + "." + liarColumn + " 当前<b>没有</b>非空重复值（重复组=" + dupGroups + "）。\n"
                        + "  实测（2026-09-04，库 cpq_db_0724）应 ≥ 1 组（AC-112① 夹具 TEST0813-P01-PROD "
                        + "对应 TEST0813-P01 / TEST0813-P01-BD1 两个销售料号）。\n"
                        + "  为 0 说明夹具被清理 ⇒ 本反证失去对象，判定为【未验证】，"
                        + "🚫 不许因为「校验器没报 FAIL」就认为校验器坏了，也不许当成通过。");

        SemanticGraphValidator.CheckResult r = validator.checkEdgeCardinality(table, List.of(liarColumn));
        System.out.println("[CI①/反证] checkEdgeCardinality(" + table + ", [" + liarColumn + "]) → "
                + r.status + " / " + r.message + " / detail=" + r.detail);
        assertEquals("FAIL", r.status,
                "错误的单列基数声明必须被断言否决（" + table + "." + liarColumn + " 实测有 " + dupGroups + " 组重复）");
        assertNotNull(r.detail);
        assertTrue(r.detail.containsKey("duplicates"), "失败详情必须列出重复的键值，实际 detail=" + r.detail);
        @SuppressWarnings("unchecked")
        List<Object> dups = (List<Object>) r.detail.get("duplicates");
        assertFalse(dups.isEmpty(), "必须至少指出一组重复");
    }
}
