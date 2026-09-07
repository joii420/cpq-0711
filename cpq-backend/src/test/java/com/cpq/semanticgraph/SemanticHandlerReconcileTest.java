package com.cpq.semanticgraph;

import com.cpq.semanticgraph.entity.SemanticNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 🪦 <b>留碑：CI 断言② 「登记 ⇄ 导入 handler 双向对账」已于 2026-09-04 明确作废</b>
 * （task-260819 S-29-b，<b>用户裁决方案 A：明确作废，留碑</b>）。
 *
 * <p>本类原先做的事（AC-36，2026-08-21 立）：对每个登记了 {@code source_handler} 的 SHEET 节点，
 * 正则扫 {@code com.cpq.basicdata.v6.quote.<sourceHandler>} 的 {@code .java} 源文件，
 * 把 {@code .put("列名", ...)} 的字面量列名集合与节点声明的识别列（{@code is_code} + {@code grain_columns}）对账，
 * 目的是钉住一件事 —— <b>「图里声明的列，导入侧真的会写」</b>。
 *
 * <h3>为什么作废（裁决理由原文，不是「反正跑不了就删掉」）</h3>
 * <p>该目的在新数据集下<b>已被两条精确覆盖</b>：
 * <table>
 *   <tr><th>机制</th><th>钉住的两侧</th><th>强度</th></tr>
 *   <tr><td>{@code AC-104}（本任务用例，{@code V9SemanticGraphSeedTest}）</td>
 *       <td>{@code semantic_node_column} ⇄ {@code information_schema}</td><td>双向无差集</td></tr>
 *   <tr><td>{@code DatasetSchemaSelfCheck}（{@code com.cpq.dataset.registry}，182 行，<b>启动期</b>跑）</td>
 *       <td>Registry 声明的列 ⇄ {@code information_schema}</td>
 *       <td>45 主表 + 39 {@code _history} 表逐表<b>完全相等</b>，多一列少一列都算漂移，
 *           不一致直接 {@code IllegalStateException} 起不来</td></tr>
 * </table>
 * <p>两侧都被钉死在<b>同一个第三方（真实 DDL）</b>上，且都是<b>精确相等</b>
 * ⇒ 「图 ⇄ Registry 一致」是<b>精确推出</b>的，不是近似兜底。
 *
 * <p>而老的 handler 对账只是<b>字面量级正则扫描</b>：不追变量别名、不解析条件分支、只覆盖识别列、
 * 还要为 6 个不用 {@code .put} 写法的 handler 开跳过（Q02/Q05/Q08/Q15/Q18/Q19）+ 1 个命名不一致开豁免
 * （{@code PLATING_SCHEME} 登记 {@code plating_scheme_no}、handler 写 {@code scheme_no}）。
 * ⇒ <b>新机制严格强于它</b>。作废不是能力退化，是被更强的判据取代。
 *
 * <h3>作废的事实前提（2026-09-04 实测，库 {@code cpq_db_0724}）</h3>
 * <ul>
 *   <li>{@code semantic_node} ACTIVE 共 <b>44</b> 个（SHEET 41 + LOOKUP 2 + FUNCTION 1）；</li>
 *   <li>{@code source_handler} 非空 = <b>0</b> —— v9 新图全是 {@code ds_*} 表，写入方是
 *       {@code com.cpq.dataset.**} 的<b>通用参数化导入器</b>（{@code POST /dataset/{dataset}/import}），
 *       不是 V6 那 17 个 {@code Q*Handler}（源文件仍在，21 个）⇒ <b>对账的另一侧整个换人了</b>。</li>
 * </ul>
 *
 * <h3>🚫 这里没有留成「永久 skip 的死用例」</h3>
 * <p>项目规矩 {@code skip != pass}，所以本类<b>不用 {@code @Disabled}</b>。
 * 留下的 {@link #retiredReconcile_tombstone_premiseStillHolds()} 是一条<b>会真的执行</b>的
 * 「作废前提守卫」：一旦有人重新往 {@code semantic_node.source_handler} 里登记 handler，
 * 「对账另一侧换人了」这个作废前提就不再成立，本条<b>变红</b>并提示重新评估是否要把对账接回来。
 * 换句话说：<b>作废的是判据形态，不是那份担心</b>。
 *
 * <h3>📌 被作废的两个方法的历史价值（保留文字，勿删）</h3>
 * <ol>
 *   <li>{@code everyRegisteredHandler_putsItsIdentityColumns()} —— 正向对账，
 *       最后一次有效运行的口径是 checked ≥ 10 / skipped = 6 / exempted = 1。</li>
 *   <li>{@code newUnregisteredPutColumn_mustFail_realExperimentReplay()} —— 反证型，
 *       固化的是 <b>2026-08-20 真实做过的破坏实验</b>：在 {@code Q04ElementBomHandler.java} 的
 *       {@code base_qty} 行后人为插入 {@code c.put("sqlvb_undeclared_col_xyz", "SQLVB-DESTRUCTIVE-TEST");}
 *       （未改任何登记），重跑「未登记列检测」得 {@code extra = [sqlvb_undeclared_col_xyz]}，
 *       检测确实变红且指名道姓；实验后已用 {@code diff} 确认文件与实验前逐字节相同（未残留、未提交）。
 *       该实验结论本身仍然成立，只是它验的对象（V6 handler）已不在语义图内。</li>
 * </ol>
 *
 * @see com.cpq.task260819v9.V9SemanticGraphSeedTest AC-104 列声明 ⇄ information_schema 双向无差集
 */
@QuarkusTest
@DisplayName("SemanticHandlerReconcileTest — 🪦 AC-36 handler 对账已作废（2026-09-04 S-29-b 裁决），仅留前提守卫")
public class SemanticHandlerReconcileTest {

    @Test
    @TestTransaction
    @DisplayName("作废前提守卫: semantic_node 不再登记任何 source_handler —— 一旦有人登记回来，本条变红要求重新评估")
    void retiredReconcile_tombstone_premiseStillHolds() {
        List<SemanticNode> withHandler = SemanticNode.list("sourceHandler is not null");
        long activeTotal = SemanticNode.count("status = 'ACTIVE'");
        long activeSheets = SemanticNode.count("nodeKind = 'SHEET' and status = 'ACTIVE'");
        System.out.println("[🪦 AC-36 留碑] semantic_node ACTIVE=" + activeTotal + "（其中 SHEET=" + activeSheets
                + "），source_handler 非空 = " + withHandler.size()
                + (withHandler.isEmpty() ? "" : " → " + withHandler.stream().map(n -> n.nodeKey + ":" + n.sourceHandler).toList()));

        assertTrue(activeTotal > 0,
                "🚨 语义图为空（ACTIVE 节点 " + activeTotal + " 个）—— 这不是「作废前提成立」，是种子没就位。"
                        + "本条判定为【未验证】，🚫 不许当成通过。");

        assertTrue(withHandler.isEmpty(),
                "🚦 CI 断言②「登记 ⇄ 导入 handler 对账」的**作废前提已被推翻**：\n"
                        + "  semantic_node.source_handler 非空的节点重新出现了 " + withHandler.size() + " 个 —— "
                        + withHandler.stream().map(n -> n.nodeKey + " → " + n.sourceHandler).toList() + "\n"
                        + "  2026-09-04 作废该断言的唯一理由是「对账的另一侧整个换人了（V6 Q*Handler → dataset 通用导入器），"
                        + "登记侧 source_handler 恒为 0，判据没有对象」。\n"
                        + "  现在对象回来了 ⇒ 必须重新评估：要么把对账接回来（历史实现见本类 git 历史），"
                        + "要么解释清楚这些新登记为什么不需要对账。\n"
                        + "  🚫 不许直接把本断言删掉了事 —— 那等于把「图里声明的列导入侧真的会写」这道防线一起删了。");
    }
}
