package com.cpq.datasource.sqlview;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0725 根因 2 —— {@link SqlViewExecutor} 命名占位符替换/提取的注释屏蔽自测。
 *
 * <p>{@code CP_VIEW}/{@code BOM_VIEW} 常量是 2026-07-25 从共享库 {@code cpq_db} 的
 * {@code component_sql_view} 表直接取出的<b>真实生产模板</b>（{@code SELECT sql_template FROM
 * component_sql_view WHERE sql_view_name IN ('cp_view','bom_view')}），不是人造字符串——对齐
 * backtask T1「真实性验证」要求：cp_view 首行注释含 {@code :customerCode}、bom_view 首行注释含
 * {@code :total_material_no}（另有 2 处真实出现于正文 WHERE 子句），与 {@code 需求说明.md §4.3
 * 根因2} 描述的复现样本逐字一致。
 *
 * <p>{@code rewriteNamedParams} 是 {@link SqlViewExecutor} 的私有纯文本方法（不触发任何 DB 查询），
 * 用反射直接调用以贴近真实实现路径，而不是重新拷贝一份等价逻辑（避免测试与实现走两套代码产生
 * "测试证明的是测试自己的理解，不是实现"的假阳性）。
 */
@QuarkusTest
class SqlViewExecutorNamedParamMaskingTest {

    @Inject SqlViewExecutor executor;

    @Inject DataSource dataSource;

    /** cp_view 真实模板（产品页签）：首行注释含 :customerCode，正文 JOIN 谓词含真实 1 处。 */
    private static final String CP_VIEW =
        "-- 产品(主件, 平铺契约: hf_part_no + :customerCode)\n" +
        "SELECT\n" +
        "  mm.material_no AS hf_part_no,\n" +
        "  mm.material_no AS _销售料号,\n" +
        "  mcm.customer_material_name AS _客户料号名称,\n" +
        "  mcm.customer_product_no AS _客户产品编号,\n" +
        "  mm.standard_unit AS _单位,\n" +
        "  mcm.exchange_rate AS _汇率\n" +
        "FROM material_master mm\n" +
        "LEFT JOIN material_customer_map mcm\n" +
        "  ON mcm.material_no = mm.material_no AND mcm.customer_no = :customerCode\n";

    /** bom_view 真实模板（BOM 树页签）：首行注释含 :total_material_no，正文 2 处真实出现（UNION ALL 两分支各一）。 */
    private static final String BOM_VIEW =
        "-- BOM 树页签(树契约: material_no=子/parent_no=父 + :total_material_no; 边式全子件 + 根分支)\n" +
        "SELECT\n" +
        "  mbi.component_no AS material_no,\n" +
        "  mbi.material_no  AS parent_no,\n" +
        "  COALESCE(mm.material_name, mr.name) AS _料件名称,\n" +
        "  mbi.scrap_rate AS _损耗率,\n" +
        "  mbi.net_weight AS _净重,\n" +
        "  mbi.rough_weight AS _毛重,\n" +
        "  mbi.weight_unit AS _用量单位,\n" +
        "  mbi.composition_qty AS _组成数量,\n" +
        "  mbi.issue_unit AS _组成单位\n" +
        "FROM material_bom_item mbi\n" +
        "  LEFT JOIN material_master mm ON mm.material_no = mbi.component_no\n" +
        "  LEFT JOIN material_recipe mr ON mr.code = mbi.component_no\n" +
        "WHERE mbi.system_type = 'QUOTE' AND mbi.is_current\n" +
        "  AND mbi.component_no = ANY(:total_material_no)\n" +
        "UNION ALL\n" +
        "SELECT\n" +
        "  mm.material_no, NULL::text, mm.material_name,\n" +
        "  NULL::numeric, NULL::numeric, NULL::numeric,\n" +
        "  NULL::varchar, NULL::numeric, mm.standard_unit\n" +
        "FROM material_master mm\n" +
        "WHERE mm.material_no = ANY(:total_material_no)\n" +
        "  AND NOT EXISTS (SELECT 1 FROM material_bom_item x\n" +
        "                  WHERE x.component_no = mm.material_no\n" +
        "                    AND x.system_type = 'QUOTE' AND x.is_current)\n";

    // ─────────────────────────── extractNamedParams ───────────────────────────

    @Test
    void extractNamedParams_cpView_commentTokenIgnored_onlyRealCustomerCode() {
        List<String> params = executor.extractNamedParams(CP_VIEW);
        assertEquals(List.of("customerCode"), params,
            "cp_view 注释里的 :customerCode 不应被识别为占位符；真实谓词里的 1 个必须保留");
    }

    @Test
    void extractNamedParams_bomView_commentTokenIgnored_dedupedToOneNamedParam() {
        List<String> params = executor.extractNamedParams(BOM_VIEW);
        assertEquals(List.of("total_material_no"), params,
            "bom_view 注释里的 :total_material_no 不应被识别；正文 2 处真实出现去重后只有 1 个 name");
    }

    @Test
    void extractNamedParams_lineComment_singleQuoteLiteral_blockComment_castAllExcluded() {
        String sql =
            "SELECT 1 -- :fakeFromLineComment\n" +
            "/* :fakeFromBlockComment\n" +
            "   spans lines */\n" +
            "FROM t\n" +
            "WHERE t.lbl = ':fakeFromLiteral'\n" +
            "  AND t.id::uuid = :realId\n" +
            "  AND t.code = :realCode\n";
        List<String> params = executor.extractNamedParams(sql);
        assertEquals(List.of("realId", "realCode"), params,
            "行注释/块注释/字符串字面量内的 token 及 ::uuid cast 均不应被识别，仅 2 个真实占位符按出现顺序保留");
    }

    // ─────────────────────────── rewriteNamedParams（反射） ───────────────────────────

    @Test
    void rewriteNamedParams_cpView_javaBoundCountMatchesPgjdbc() throws Exception {
        Map<String, Object> namedParams = new HashMap<>();
        namedParams.put("customerCode", "CUST-TEST");
        RewrittenSqlView rewritten = invokeRewrite(CP_VIEW, namedParams);

        assertEquals(1, rewritten.params.size(),
            "cp_view 注释内的 :customerCode 已被屏蔽，Java 侧只应绑定正文真实谓词的 1 个");
        assertFalse(rewritten.sql.contains("mcm.customer_no = :customerCode"),
            "正文真实谓词里的 :customerCode 必须被替换成 ?");
        assertTrue(rewritten.sql.contains("-- 产品(主件, 平铺契约: hf_part_no + :customerCode)"),
            "注释原文须逐字保留（包括其中的 :customerCode 字面文本）——只是不参与占位符绑定，"
            + "不是把注释内容删掉或改写掉");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(rewritten.sql)) {
            int pgjdbcCount = ps.getParameterMetaData().getParameterCount();
            assertEquals(rewritten.params.size(), pgjdbcCount,
                "Java 侧绑定数必须等于 pgjdbc 认到的占位符数（否则 setObject 越界，复现 "
                + "'The column index is out of range' — task-0725 根因2 已复现的生产报错）");
        }
    }

    @Test
    void rewriteNamedParams_bomView_javaBoundCountMatchesPgjdbc() throws Exception {
        Map<String, Object> namedParams = new HashMap<>();
        namedParams.put("total_material_no", List.of("M-0001"));
        RewrittenSqlView rewritten = invokeRewrite(BOM_VIEW, namedParams);

        assertEquals(2, rewritten.params.size(),
            "bom_view 注释内 1 个 :total_material_no 已被屏蔽，Java 侧只应绑定正文 UNION ALL 两分支各 1 处、共 2 处真实出现");
        assertFalse(rewritten.sql.contains("ANY(:total_material_no)"),
            "正文 UNION ALL 两分支里真实的 :total_material_no 必须都被替换成 ?");
        assertTrue(rewritten.sql.contains(
                "-- BOM 树页签(树契约: material_no=子/parent_no=父 + :total_material_no; 边式全子件 + 根分支)"),
            "注释原文须逐字保留（包括其中的 :total_material_no 字面文本）");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(rewritten.sql)) {
            int pgjdbcCount = ps.getParameterMetaData().getParameterCount();
            assertEquals(rewritten.params.size(), pgjdbcCount,
                "Java 侧绑定数必须等于 pgjdbc 认到的占位符数");
        }
    }

    @Test
    void rewriteNamedParams_bodyTokenStillReplaced_bindingOrderCorrect() throws Exception {
        String sql =
            "SELECT * FROM t\n" +
            "-- 说明：:b 是废弃占位符，勿用\n" +
            "WHERE t.a = :a AND t.b = :b AND t.c = :a\n";
        Map<String, Object> namedParams = new HashMap<>();
        namedParams.put("a", "VAL_A");
        namedParams.put("b", "VAL_B");
        RewrittenSqlView rewritten = invokeRewrite(sql, namedParams);

        // 正文内 3 处真实出现（:a, :b, :a）按出现顺序绑定；注释内的 :b 不计入。
        assertEquals(List.of("VAL_A", "VAL_B", "VAL_A"), rewritten.params,
            "正文内同名 token 应正常替换，且绑定顺序须与出现顺序一致");
        assertTrue(rewritten.sql.contains("-- 说明：:b 是废弃占位符，勿用"),
            "注释原文应保持逐字不变，其中的 :b 不应被替换成 ?（只是不再计入绑定，替换仍作用于原文）");
    }

    /** rewriteNamedParams 是 SqlViewExecutor 私有方法（纯文本处理，无 DB/CDI 依赖），反射调用以贴近真实实现路径。 */
    @SuppressWarnings("unchecked")
    private RewrittenSqlView invokeRewrite(String sql, Map<String, Object> namedParams) throws Exception {
        Method m = SqlViewExecutor.class.getDeclaredMethod("rewriteNamedParams", String.class, Map.class);
        m.setAccessible(true);
        Object result = m.invoke(executor, sql, namedParams);
        Field sqlField = result.getClass().getDeclaredField("sql");
        Field paramsField = result.getClass().getDeclaredField("params");
        sqlField.setAccessible(true);
        paramsField.setAccessible(true);
        return new RewrittenSqlView((String) sqlField.get(result), (List<Object>) paramsField.get(result));
    }

    private record RewrittenSqlView(String sql, List<Object> params) {}
}
