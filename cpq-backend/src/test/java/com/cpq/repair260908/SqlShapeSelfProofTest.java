package com.cpq.repair260908;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * repair-260908 · S-1 的<b>量具自证</b>（{@code test.md §3}，强制，不许跳）。
 *
 * <h3>为什么量具要有自己的常驻用例</h3>
 * {@code task-260907} 一天内造过三次假绿，共同点是<b>量具本身是坏的</b>：
 * 表名匹配用裸 substring、diff 拿两个空文件比、计数器恒返 0。
 * 那三次的症状都一样 —— <b>全绿</b>。
 * ⇒ 一个自己会静默失效的量具，只是把静默失效推迟了一层。
 * 本类就是「量具还活着」的机械信号，每轮都跑。
 *
 * <h3>🚨 共享库红线（CLAUDE.md §3.2）</h3>
 * 本类<b>刻意不是 {@code @QuarkusTest}</b>：全部输入是 2026-09-08 从
 * {@code cpq_db_0724.component_sql_view.sql_template} <b>逐字抄回</b>的真实编译产物常量。
 * 不启 Quarkus、不连库、<b>不写任何一行数据</b>。
 *
 * <h3>⚠️ 常量的取材纪律</h3>
 * 下面四段 SQL <b>不是我编的</b>，勿改写措辞。它们各自守一种形状：
 * <ul>
 *   <li>{@link #COST_BASIC_BOM} —— UNION ALL 两分支 + 2 处 {@code NARROW} 桥子查询 + 1 处 NOT EXISTS：
 *       <b>并发线正是在这一条上翻的车</b>；</li>
 *   <li>{@link #QUOTE_MAIN} —— 报价侧「产品」页签（AC-1b 的被测对象）；</li>
 *   <li>{@link #QUOTE_SELF_PROCESS_FEE} —— {@code ds_quote_material} 出现在 <b>JOIN</b> 而非 FROM，
 *       且 ON 上带<b>列对列</b>客户连接键（客户维度形态③，AC-6 要守住的那种）；</li>
 *   <li>{@link #QUOTE_ELEMENT_BOM} —— JOIN 目标是<b>表函数</b> {@code f_material_element_price(:customerCode, …)}，
 *       别名在右括号之后（别名解析最容易挂的形状）。</li>
 * </ul>
 */
class SqlShapeSelfProofTest {

    // ═══════════════════ 真实编译产物常量（2026-09-08 逐字抄自共享库） ═══════════════════

    /** {@code builder_32ab8212df6c}（COST_BASIC / BOM）。 */
    static final String COST_BASIC_BOM = """
            -- 树契约: material_no=子 / parent_no=父 + :total_material_no; 边式全子件 + 根分支
            SELECT
              vdcbmba.component_no AS material_no,
              vdcbmba.production_no AS parent_no,
              vdcbmba.component_no AS hf_part_no,
              vdcbmba.production_no AS "production_no",
              vdcbmba.version_no::text AS view_version
            FROM v_ds_cost_basic_material_bom_all vdcbmba
              LEFT JOIN ds_cost_basic_material dcbm ON dcbm.production_no = vdcbmba.component_no
              LEFT JOIN material_recipe mr ON mr.code = vdcbmba.component_no
            WHERE vdcbmba.component_no IN (SELECT dqm.production_no FROM ds_quote_material dqm WHERE dqm.material_no = ANY(:total_material_no)) AND :versionFilter(vdcbmba.is_current, vdcbmba.version_no::text, vdcbmba.production_no)
            UNION ALL
            -- 根分支：本单闭包里无父边的成品自身（树根，parent_no 恒 NULL）
            SELECT
              dcbm2.production_no,
              NULL::text,
              dcbm2.production_no,
              NULL,
              NULL::text
            FROM ds_cost_basic_material dcbm2
            WHERE dcbm2.production_no IN (SELECT dqm2.production_no FROM ds_quote_material dqm2 WHERE dqm2.material_no = ANY(:total_material_no))
              AND NOT EXISTS (SELECT 1 FROM v_ds_cost_basic_material_bom_all vdcbmba2 WHERE vdcbmba2.component_no = dcbm2.production_no)
            ORDER BY parent_no, material_no, "item_seq"
            """;

    /** {@code builder_a71947b68d50}（QUOTE / 主件）—— AC-1b 的被测视图。 */
    static final String QUOTE_MAIN = """
            SELECT
              dqm.material_no AS hf_part_no,
              dqm.material_no AS "_物料_销售料号",
              dqcp.customer_product_no AS "_客户料号_客户产品编号",
              dqm.material_type AS "_物料_类型"
            FROM ds_quote_material dqm
              LEFT JOIN ds_quote_customer_part dqcp ON dqcp.material_no = dqm.material_no AND dqcp.customer_no = :customerCode
            WHERE dqm.material_no = ANY(:total_material_no)
            ORDER BY dqm.material_no
            """;

    /** {@code builder_9cc11850f425}（QUOTE / 费用类 SELF_PROCESS_FEE）。 */
    static final String QUOTE_SELF_PROCESS_FEE = """
            SELECT
              dqspf.material_no AS hf_part_no,
              dqm.material_name AS "_物料_材料名"
            FROM ds_quote_self_process_fee dqspf
              LEFT JOIN ds_quote_material dqm ON dqm.material_no = dqspf.input_material_no AND dqm.customer_no = dqspf.customer_no
            WHERE dqspf.material_no = ANY(:total_material_no)
            ORDER BY dqspf.material_no, dqspf.input_material_no, dqspf.item_seq
            """;

    /** {@code builder_196aadeeb89f}（QUOTE / 材质元素）—— JOIN 目标是表函数。 */
    static final String QUOTE_ELEMENT_BOM = """
            SELECT
              dqeb.material_no AS hf_part_no,
              cep.unit_price AS "元素单价"
            FROM ds_quote_element_bom dqeb
              LEFT JOIN f_material_element_price(:customerCode, :priceBaseDate) cep ON cep.element_code = dqeb.element_code AND cep.material_no = dqeb.material_no
            WHERE dqeb.material_no = ANY(:total_material_no)
            ORDER BY dqeb.material_no, dqeb.material_part_no
            """;

    // ═══════════════════ 量具① 标识符边界（backtask.md 硬约束 7） ═══════════════════

    @Test
    @DisplayName("量具①: 表名匹配必须带标识符边界 —— ds_quote_material 不许命中 ds_quote_material_bom，两个方向都要过")
    void gauge1_identifierBoundaryBothDirections() {
        String onlyBom = "FROM ds_quote_material_bom dqmb WHERE dqmb.input_material_no = ANY(:x)";

        // 方向 A（假阳性侧）：只有 _bom 的文本里不许找到裸表名
        assertFalse(SqlShape.containsIdentifier(onlyBom, "ds_quote_material"),
                "量具①A: `ds_quote_material` 不得命中 `ds_quote_material_bom`。文本=" + onlyBom);
        // 对照：证明「不加边界确实会错」—— 若这一行有朝一日变 false，说明我拿错了反例
        assertTrue(onlyBom.contains("ds_quote_material"),
                "量具①A 对照失效：裸 substring 竟然不命中了，反例选错，A 的通过没有意义");

        // 方向 B（假阴性侧）：真有该表时必须命中
        assertTrue(SqlShape.containsIdentifier(QUOTE_MAIN, "ds_quote_material"),
                "量具①B: 真含 ds_quote_material 的产物必须命中");
        assertTrue(SqlShape.containsIdentifier(onlyBom, "ds_quote_material_bom"),
                "量具①B: 真含 ds_quote_material_bom 的文本必须命中");

        // 前缀 / 后缀两侧都要挡住
        assertFalse(SqlShape.containsIdentifier("xds_quote_material y", "ds_quote_material"),
                "量具①: 左侧粘连不算命中");
        assertFalse(SqlShape.containsIdentifier("ds_quote_materialx", "ds_quote_material"),
                "量具①: 右侧粘连不算命中");
    }

    // ═══════════════════ 量具② 外层 FROM vs 子查询 FROM ═══════════════════

    @Test
    @DisplayName("量具②🚨: 外层 FROM 与子查询 FROM 必须分得开 —— 复刻并发线『歪打正着』的翻车点")
    void gauge2_outerVsSubqueryFrom() {
        List<String> outer = SqlShape.outerBlocks(COST_BASIC_BOM).stream().map(b -> b.from().table()).toList();
        List<String> sub = SqlShape.subBlocks(COST_BASIC_BOM).stream().map(b -> b.from().table()).toList();
        System.out.println("[量具②] 外层 FROM=" + outer + "\n[量具②] 子查询 FROM=" + sub);

        assertEquals(List.of("v_ds_cost_basic_material_bom_all", "ds_cost_basic_material"), outer,
                "量具②: COST_BASIC/BOM 的外层 FROM 应是 UNION 两分支的锚点，实得=" + outer);
        assertFalse(outer.contains("ds_quote_material"),
                "量具②🚨: `ds_quote_material` 是桥子查询的表，🚫 绝不能被算成外层锚点 —— "
                        + "并发线正是在这里把谓词加到了错误的对象上（虽然位置碰巧是对的）。实得外层=" + outer);
        assertTrue(sub.contains("ds_quote_material"),
                "量具②: 桥子查询里必须认出 ds_quote_material，实得子查询=" + sub);

        // 对照：证明并发线的正则确实抽错 —— 若它哪天不再抽错，本条判据就失去了针对性
        Matcher m = Pattern.compile("FROM\\s+(ds_quote_\\w+)").matcher(COST_BASIC_BOM);
        List<String> wrong = new java.util.ArrayList<>();
        while (m.find()) {
            wrong.add(m.group(1));
        }
        System.out.println("[量具②对照] `FROM\\s+(ds_quote_\\w+)` 抽出=" + wrong);
        assertTrue(wrong.contains("ds_quote_material") && !wrong.contains("v_ds_cost_basic_material_bom_all"),
                "量具②对照失效：并发线那条正则现在不抽错了 —— 反例选错，本量具的通过失去意义。实得=" + wrong);
    }

    @Test
    @DisplayName("量具②b: JOIN 目标不是 FROM 目标 —— ds_quote_material 在费用类视图里是 JOIN，AC-2b 不许要求它带客户谓词")
    void gauge2b_joinIsNotFrom() {
        SqlShape.Block b = SqlShape.outerBlocks(QUOTE_SELF_PROCESS_FEE).get(0);
        assertEquals("ds_quote_self_process_fee", b.from().table(),
                "量具②b: 外层 FROM 应是费用表本身，实得=" + b.from());
        assertTrue(b.joins().stream().anyMatch(j -> "ds_quote_material".equals(j.table())),
                "量具②b: ds_quote_material 应被识别为 JOIN 目标。joins=" + SqlShape.joinFingerprint(QUOTE_SELF_PROCESS_FEE));

        // 表函数 JOIN：别名在右括号之后，最容易解析成 null
        List<SqlShape.Rel> fj = SqlShape.allJoins(QUOTE_ELEMENT_BOM);
        assertEquals(1, fj.size(), "量具②b: 材质元素视图应有且仅有 1 个 JOIN，实得=" + fj);
        assertEquals("cep", fj.get(0).alias(),
                "量具②b: 表函数 JOIN 的别名应解析为 cep，实得=" + fj.get(0));
    }

    // ═══════════════════ 量具③ diff 比较器（先红后空，🚫 不许拿空对空） ═══════════════════

    @Test
    @DisplayName("量具③: diff 比较器先证会报红，再证会报空 —— 🚫 不许拿两个空的比出『相同』")
    void gauge3_diffComparatorRedThenGreen() {
        List<String> base = SqlShape.joinFingerprint(QUOTE_SELF_PROCESS_FEE);
        System.out.println("[量具③] 基线 JOIN 指纹=" + base);

        // 🚨 先证判据有作用对象：指纹非空。空对空恒相等 = 零证据
        assertFalse(base.isEmpty(),
                "量具③🚨: 基线 JOIN 指纹为空 —— 那么『相同』只是两个空列表相等，是零证据不是弱证据");

        // ① 人为改 1 处 ON 的一个字 ⇒ 必须报红
        String m1 = QUOTE_SELF_PROCESS_FEE.replaceFirst(
                "dqm\\.material_no = dqspf\\.input_material_no", "dqm.material_no = dqspf.material_no");
        assertNotEquals(QUOTE_SELF_PROCESS_FEE, m1, "量具③: 变异体没生成，replaceFirst 没命中");
        assertNotEquals(base, SqlShape.joinFingerprint(m1),
                "量具③①: 改了 ON 子句一个词，比较器必须报红。实得=" + SqlShape.joinFingerprint(m1));

        // ② LEFT → INNER ⇒ 必须报红（AC-6 主目标）
        String m2 = QUOTE_SELF_PROCESS_FEE.replaceFirst("LEFT JOIN", "INNER JOIN");
        assertNotEquals(base, SqlShape.joinFingerprint(m2),
                "量具③②: LEFT 被收成 INNER，比较器必须报红。实得=" + SqlShape.joinFingerprint(m2));

        // ③ 还原后 ⇒ 必须报空
        assertEquals(base, SqlShape.joinFingerprint(QUOTE_SELF_PROCESS_FEE),
                "量具③③: 同一段 SQL 两次取指纹必须相等（比较器不能不稳定）");

        // ④ 只改排版不改语义 ⇒ 不许报红（判据要在合法变化时不红）
        String reformatted = QUOTE_SELF_PROCESS_FEE.replace("\n  LEFT JOIN", "\n\n        LEFT   JOIN");
        assertEquals(base, SqlShape.joinFingerprint(reformatted),
                "量具③④: 纯排版差异不得被判成 JOIN 变了，否则每次都红、判据失去指示性");
    }

    // ═══════════════════ 量具④ 客户谓词匹配器（既不恒真也不恒假） ═══════════════════

    @Test
    @DisplayName("量具④: 客户谓词匹配器 —— 改动前必须『找不到』，人为补上必须『找得到』，别名不对必须『找不到』")
    void gauge4_customerPredicateMatcher() {
        SqlShape.Block before = SqlShape.outerBlocks(QUOTE_MAIN).get(0);
        System.out.println("[量具④] 改动前 whereTop=" + before.whereTop());

        // 恒真检查：改动前（缺陷①现状）必须判为「没有」
        assertFalse(SqlShape.hasCustomerCodePredicate(before.whereTop(), before.from().alias()),
                "量具④: 改动前的产物里不应找到客户谓词 —— 找到了说明匹配器恒真。whereTop=" + before.whereTop());

        // 恒假检查：人为补上必须判为「有」
        String patched = QUOTE_MAIN.replace(
                "WHERE dqm.material_no = ANY(:total_material_no)",
                "WHERE dqm.material_no = ANY(:total_material_no) AND dqm.customer_no = :customerCode");
        assertNotEquals(QUOTE_MAIN, patched, "量具④: 变异体没生成，replace 没命中");
        SqlShape.Block after = SqlShape.outerBlocks(patched).get(0);
        assertTrue(SqlShape.hasCustomerCodePredicate(after.whereTop(), after.from().alias()),
                "量具④: 补上谓词后必须找到 —— 找不到说明匹配器恒假。whereTop=" + after.whereTop());

        // 别名必须真的参与判定：拿 JOIN 别名去匹配不许命中
        assertFalse(SqlShape.hasCustomerCodePredicate(after.whereTop(), "dqcp"),
                "量具④: 用别的别名 dqcp 不得命中 dqm 的谓词，否则别名维度形同虚设");

        // 🚨 LEFT JOIN 的 ON 上本来就有 `dqcp.customer_no = :customerCode`：
        //    whereTop 判据必须看不见它，否则 AC-2b 会把「JOIN 上的客户条件」误读成「主表已加谓词」——
        //    并发线第一版判「4 个已有」全是误报，就是栽在这里。
        assertFalse(SqlShape.hasCustomerCodePredicate(before.whereTop(), "dqcp"),
                "量具④🚨: JOIN ON 上的 dqcp.customer_no = :customerCode 不得被 WHERE 判据看见。"
                        + "\n  这正是并发线第一版『4 个已有』误报的成因。whereTop=" + before.whereTop());
        assertTrue(QUOTE_MAIN.contains("dqcp.customer_no = :customerCode"),
                "量具④ 对照失效：原文里本该有 JOIN 上的客户条件作为诱饵，现在没有了 ⇒ 上一条通过没有意义");
    }

    // ═══════════════════ 量具⑤ 桥子查询定位（AC-16 的作用域） ═══════════════════

    @Test
    @DisplayName("量具⑤: NARROW 桥子查询能被单独定位，且与 NOT EXISTS 相关子查询区分得开")
    void gauge5_bridgeSubqueryIsolation() {
        List<SqlShape.Block> subs = SqlShape.subBlocks(COST_BASIC_BOM);
        System.out.println("[量具⑤] 子查询块：");
        subs.forEach(b -> System.out.println("    FROM=" + b.from()
                + "  WHERE(本层)=" + b.whereTop() + "  WHERE(全文)=" + b.whereFull()));

        // 🔑 桥的签名 = 子查询自己的 WHERE **全文**里消费 :total_material_no。
        //    必须用 whereFull() 而不是 whereTop()：入参写在 `ANY( … )` 的括号里，
        //    whereTop 会把括号内容压成 `( … )` ⇒ 用 whereTop 判会恒为「不是桥」，
        //    整条 AC-16 变成空跑。（2026-09-08 本量具首跑就是栽在这里，已由本条守住。）
        List<SqlShape.Block> bridges = subs.stream()
                .filter(b -> b.whereFull() != null && b.whereFull().contains(":total_material_no")).toList();
        assertEquals(2, bridges.size(),
                "量具⑤: COST_BASIC/BOM 有 UNION 两分支 ⇒ 应认出 2 处桥子查询（dqm / dqm2）。实得=" + bridges);
        assertEquals(List.of("ds_quote_material", "ds_quote_material"),
                bridges.stream().map(b -> b.from().table()).toList(), "量具⑤: 两处桥的表都应是 ds_quote_material");
        assertEquals(List.of("dqm", "dqm2"), bridges.stream().map(b -> b.from().alias()).toList(),
                "量具⑤🚨: 两处桥的别名不同（dqm / dqm2）—— AC-16 必须逐处断言，"
                        + "只验第一处会让第二处的漏加静默通过");

        // NOT EXISTS 相关子查询不是桥：它不消费 :total_material_no
        List<SqlShape.Block> notBridges = subs.stream()
                .filter(b -> b.whereFull() == null || !b.whereFull().contains(":total_material_no")).toList();
        assertEquals(1, notBridges.size(), "量具⑤: 应有 1 处非桥子查询（NOT EXISTS）。实得=" + notBridges);
        assertEquals("v_ds_cost_basic_material_bom_all", notBridges.get(0).from().table(),
                "量具⑤: 非桥子查询应是根分支的 NOT EXISTS。实得=" + notBridges.get(0));
    }

    // ═══════════════════ 量具⑥ UNION 分支不许被吞（AC-2b 的作用域） ═══════════════════

    @Test
    @DisplayName("量具⑥: UNION 每个分支各是一个外层块，各有各的 FROM/WHERE —— 只看第一个分支会漏掉根分支")
    void gauge6_unionBranchesAreSeparateBlocks() {
        List<SqlShape.Block> outer = SqlShape.outerBlocks(COST_BASIC_BOM);
        assertEquals(2, outer.size(), "量具⑥: UNION ALL 两分支应产出 2 个外层块。实得=" + outer.size());
        assertNotEquals(outer.get(0).from().alias(), outer.get(1).from().alias(),
                "量具⑥: 两分支的锚点别名应不同（vdcbmba / dcbm2）");
        assertTrue(outer.get(0).whereTop().contains(":versionFilter"),
                "量具⑥: 第一分支的 WHERE 应含 :versionFilter，实得=" + outer.get(0).whereTop());
        assertFalse(outer.get(1).whereTop().contains(":versionFilter"),
                "量具⑥: 根分支的 WHERE 不含 :versionFilter —— 两分支的 WHERE 必须各归各的，"
                        + "串在一起会让『某分支漏加谓词』被另一分支掩盖。实得=" + outer.get(1).whereTop());
        assertEquals(0, outer.get(1).joins().size(),
                "量具⑥: 根分支没有 JOIN，JOIN 不许从第一分支串过来。实得=" + outer.get(1).joins());
    }
}
