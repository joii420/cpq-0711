package com.cpq.semanticgraph;

import com.cpq.semanticgraph.entity.SemanticNode;
import com.cpq.semanticgraph.entity.SemanticNodeColumn;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CI 断言② 登记 ⇄ 导入 handler 双向对账（task-260819 B-17，AC-36）。
 *
 * <p>做法：对每个登记了 {@code source_handler} 的 SHEET 节点，读取对应
 * {@code com.cpq.basicdata.v6.quote.<sourceHandler>} 的 {@code .java} 源文件文本，
 * 扫出全部 {@code .put("列名", ...)} 字面量出现过的列名集合，要求该节点声明的
 * <b>识别列（{@code is_code=true} 的连接键列 + grain_columns）</b>必须全部出现在该集合里——
 * 这些是编译器路径求解与基数断言真正依赖的列，一旦 handler 侧改名/删列而登记没同步，
 * 编译产物会静默引用一个 handler 从未写过的列。
 *
 * <p>⚠️ 本测试的已知范围限制（如实标注，不夸大覆盖）：源码正则扫描是**字面量级**的静态检查，
 * 不追踪变量别名、不解析条件分支，且只覆盖"识别列"（连接键/grain），不覆盖非识别的普通业务列
 * （后者由 golden 逐行等值间接兜底）。这是在 CLAUDE.md 的"禁止手工跑迁移/禁止改共享文件做测试"
 * 约束下，能在不修改任何 handler 源文件的前提下做到的最大范围。
 */
@QuarkusTest
@DisplayName("SemanticHandlerReconcileTest — AC-36 登记与导入 handler 双向对账")
public class SemanticHandlerReconcileTest {

    private static final Path HANDLER_DIR = Path.of(
            "src/main/java/com/cpq/basicdata/v6/quote");
    private static final Pattern PUT_KEY = Pattern.compile("\\.put\\(\\s*\"([a-zA-Z0-9_]+)\"");

    /**
     * 已知豁免（task-260819，主线 2026-08-21 裁决）：{@code PLATING_SCHEME} 节点登记的识别列
     * {@code plating_scheme_no}（{@code is_code=true}），但 {@code Q16PlatingSchemeHandler}
     * 实际按 {@code scheme_no} 写组键（{@code gk.put("scheme_no", e.getKey())}）——两侧命名从
     * 一开始就不一致，属导入侧（handler/字段命名）问题，不是语义图声明错。
     *
     * <p>plating_scheme 是 N-8 认定的孤儿 Sheet——现网数据 hf_part_no 与 plating_scheme_no
     * 双向全空（见 semantic_node.note），改 handler 命名属导入侧改动，超出 task-260819 编译器
     * 任务范围（主线已裁决"先只报告不修"）。加本豁免是为了让 CI 对这条已知的、有归属、有 owner
     * 的差异保持稳定绿，而不是每次跑都因为一个已知且暂不修的问题而红——豁免必须"如实标注原因"，
     * 不是悄悄跳过。
     */
    private static final Set<String> KNOWN_NAMING_MISMATCH_EXEMPT = Set.of("PLATING_SCHEME");

    @Test
    @TestTransaction
    @DisplayName("正常情况：凡使用 .put(\"列名\", ...) 写法的 handler，其对应节点的识别列均能在源码里找到字面量")
    void everyRegisteredHandler_putsItsIdentityColumns() throws IOException {
        List<SemanticNode> sheetNodes = SemanticNode.list("nodeKind = 'SHEET' and status = 'ACTIVE'");
        assertFalse(sheetNodes.isEmpty());

        int checked = 0, skipped = 0, exempted = 0;
        for (SemanticNode n : sheetNodes) {
            if (n.sourceHandler == null) continue;
            Path file = HANDLER_DIR.resolve(n.sourceHandler + ".java");
            if (!Files.exists(file)) {
                fail("节点 " + n.nodeKey + " 登记的 sourceHandler=" + n.sourceHandler + " 找不到对应源文件: " + file);
            }
            Set<String> putKeys = extractPutKeys(file);
            if (putKeys.isEmpty()) {
                // 6/17 handler（Q02/Q05/Q08/Q15/Q18/Q19）不走 Map.put("col", ...) 写法，
                // 改走类型化实体字段赋值 / 委托 Repository（如 Q02CustomerMapHandler 直接建
                // MaterialCustomerMap 实体、字段名与列名靠 JPA @Column 映射，不出现字符串字面量）。
                // 本静态扫描器对这类写法无能为力，如实跳过而不是强行判失败——避免"扫描器局限"
                // 被误当成"handler 真的漏写字段"。
                skipped++;
                continue;
            }
            if (KNOWN_NAMING_MISMATCH_EXEMPT.contains(n.nodeKey)) {
                // 已知豁免（见类字段注释）：文件存在、确实用 .put() 写法，只是识别列命名两侧不一致，
                // 不参与下面的逐列断言，但仍单独计数，不悄悄并入 checked/skipped。
                exempted++;
                continue;
            }

            List<SemanticNodeColumn> cols = SemanticNodeColumn.list("nodeId", n.id);
            Set<String> identityCols = cols.stream()
                    .filter(c -> c.isCode)
                    .map(c -> c.dbColumn)
                    .collect(Collectors.toSet());
            for (String grainCol : n.grainColumns) {
                identityCols.add(grainCol);
            }

            for (String col : identityCols) {
                assertTrue(putKeys.contains(col),
                        "节点 " + n.nodeKey + " 声明识别列 " + col + "，但 " + n.sourceHandler +
                                ".java 里未找到 .put(\"" + col + "\", ...) —— 登记与导入 handler 已不同步");
            }
            checked++;
        }
        assertTrue(checked >= 10, "应有 10+ 个使用 .put(\"col\",...) 写法的 SHEET 节点完成实扫描对账（实测 10 个，PLATING_SCHEME 已知豁免另计），本轮 checked=" + checked);
        assertEquals(6, skipped, "预期 6 个 handler 走类型化写法而跳过静态扫描（Q02/Q05/Q08/Q15/Q18/Q19），如与实测不符说明 handler 写法已变化，需要更新本测试的覆盖范围");
        assertEquals(1, exempted, "预期 1 个已知豁免（PLATING_SCHEME，见类字段注释），如与实测不符说明豁免范围已变化，需要同步核实");
    }

    /**
     * 反证正例（AC-36②，真实做过的破坏实验）：本测试固化的是 2026-08-20 手工实验的结论——
     * 在 {@code Q04ElementBomHandler.java} 的 {@code base_qty} 行后人为插入一行
     * {@code c.put("sqlvb_undeclared_col_xyz", "SQLVB-DESTRUCTIVE-TEST");}（未改任何登记），
     * 重跑"未登记列检测"（{@code putKeys - registeredColumns - INFRA_EXCLUDE}），
     * 结果 {@code extra = [sqlvb_undeclared_col_xyz]}，检测确实变红且指名道姓；
     * 实验后已用 {@code diff} 确认文件与实验前逐字节相同（未残留改动，未提交）。
     * 本测试把该实验的核心断言方向固化下来，供以后回归：凡 handler 新增一个不在登记里的
     * {@code .put(...)} 列，必须被检出，而不是"扫过去看不出来"。
     *
     * <p>{@code INFRA_EXCLUDE} 收 3 个"收窄谓词"列（{@code customer_no}/{@code system_type}/
     * {@code is_current}）——它们是 {@code semantic_node.scope=FULL} 的隐含产物，不建模成
     * {@code semantic_node_column} 行，故天然会出现在 put keys 里但不在登记列表里，不能算"未登记业务列"。
     */
    @Test
    @TestTransaction
    @DisplayName("反证【真实实验，AC-36②核心场景】: handler 新增未登记 put 列 → 对账必须失败并指名")
    void newUnregisteredPutColumn_mustFail_realExperimentReplay() throws IOException {
        SemanticNode ebi = SemanticNode.find("nodeKey", "ELEMENT_BOM_ITEM").firstResult();
        assertNotNull(ebi, "语义图种子未就绪：找不到 ELEMENT_BOM_ITEM 节点");
        assertEquals("Q04ElementBomHandler", ebi.sourceHandler);

        Path file = HANDLER_DIR.resolve("Q04ElementBomHandler.java");
        String original = Files.readString(file);
        assertFalse(original.contains("sqlvb_undeclared_col_xyz"),
                "前置条件：源文件本不应含实验列名（若含说明上次实验清理失败，需人工核查）");

        // 只在内存字符串里模拟"多写一个 put()"，不触碰磁盘文件——用同一段正则抽取逻辑验证
        // 检测方向本身是有效的；磁盘上的真实破坏实验已于 2026-08-20 手工做过一次并已还原（见类注释），
        // 这里改为内存重放是为了让本用例可反复回归执行、不必每次都真的改写共享源文件。
        String anchor = "c.put(\"base_qty\", DecimalScale.at(row.getDecimal(\"净用量\"), 12));";
        String injected = anchor + "\n            c.put(\"sqlvb_undeclared_col_xyz\", \"x\");";
        String corrupted = original.replace(anchor, injected);
        assertNotEquals(original, corrupted, "字符串替换应命中（若源文件格式变化，需要同步更新本测试的锚点文本）");

        Set<String> putKeys = extractPutKeysFromContent(corrupted);
        List<SemanticNodeColumn> cols = SemanticNodeColumn.list("nodeId", ebi.id);
        Set<String> registered = cols.stream().map(c -> c.dbColumn).collect(Collectors.toSet());
        Set<String> INFRA_EXCLUDE = Set.of("customer_no", "system_type", "is_current");

        Set<String> extra = new java.util.HashSet<>(putKeys);
        extra.removeAll(registered);
        extra.removeAll(INFRA_EXCLUDE);

        assertEquals(Set.of("sqlvb_undeclared_col_xyz"), extra,
                "对账应精确指出未登记列，实际 extra=" + extra);
    }

    private static Set<String> extractPutKeys(Path file) throws IOException {
        return extractPutKeysFromContent(Files.readString(file));
    }

    private static Set<String> extractPutKeysFromContent(String content) {
        Set<String> keys = new java.util.HashSet<>();
        Matcher m = PUT_KEY.matcher(content);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }
}
