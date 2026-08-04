package com.cpq.quotation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0803 AC-6：全库组件跑批扫描 —— 找出「{@code SUM} 的 {@code targetExpr} 内
 * {@code b_field} 指向<b>本组件</b> FORMULA 字段」这个会被本次修复改变结果的确切集合，
 * 并断言它<b>恰好等于</b>需求文档 §5.4 已确认的清单，不多不少。
 *
 * <h2>为什么是「活库门禁测试」而不是「离线 fixture 单测」</h2>
 * <p>AC-6 的本质是「配置漂移探测」——它要回答的问题是「<b>现在</b>库里的公式配置，除了
 * 已知的那几条，还有没有别的会被这次改动波及」。这个问题的正确答案<b>只能</b>来自活库当前
 * 状态：
 * <ul>
 *   <li>固化成「录一份历史快照 JSON，测试只比对快照」——测的是「某一天的库长这样」，
 *       一旦当天之后有人在组件管理页新增/编辑了含 SUM+b_field 的公式，测试仍然照样绿，
 *       起不到「新配置漂移进受影响集合」的告警作用，AC-6 想守的口子形同虚设；</li>
 *   <li>写死在 CI 常跑的单测里——87 个组件的全表结构在合并前后必然增删（BOM 导入、
     *       客户模板新增等日常操作），会让这个用例变成「三天两头因无关改动变红」的噪音源，
 *       且需要真实网络连到 {@code 10.177.152.12}，不满足单测应可离线运行的前提。</li>
 * </ul>
 * 故选择：<b>默认跳过</b>（{@code @EnabledIfEnvironmentVariable}，不参与常规
 * {@code ./mvnw test}），只在显式设置 {@code RUN_LIVE_DB_SCAN=1} 时连活库运行 ——
 * 供合并前人工触发一次「配置漂移复核」，而不是钉死某个历史时间点的库快照。
 *
 * <h2>运行方式</h2>
 * <pre>
 * RUN_LIVE_DB_SCAN=1 ./mvnw -o test -Dtest=SumHostFieldAffectedFormulasLiveScanTest \
 *   -Dquarkus.flyway.validate-on-migrate=false -Dquarkus.flyway.migrate-at-start=false
 * </pre>
 * 连接参数与 {@code CLAUDE.md} 默认开发 profile 一致（可用同名环境变量覆盖）：
 * {@code DB_HOST}（默认 10.177.152.12）/ {@code DB_PORT}（5432）/
 * {@code DB_NAME}（cpq_db_0724）/ {@code DB_USERNAME}（postgres）/ {@code DB_PASSWORD}。
 *
 * <h2>2026-08-04 实测结果（本类断言即基于此次实测固化）</h2>
 * <p>全库 87 个组件，扫描算法与后端 {@code FormulaCalculator.addExprFieldDeps}（B2）同口径
 * （递归进 {@code cross_tab_ref.targetExpr}，遇 {@code projectToHostKey=true} 停止下探，
 * 只认 {@code b_field}）：命中 <b>4</b> 个 (组件, 公式) 二元组，与需求文档 §5.4 表格逐行一致；
 * 按 (组件, 公式, 被引用字段) 三元组展开恰好 <b>8</b> 条（4 个公式 × 各 2 个字段），
 * 与 §5.4 标题「存量 8 条」的真实含义相符（§5.4 表头文案有歧义，见下方 docNote 断言 —
 * 这是本次测试意外发现的<b>文档口径不一致</b>，已在测试报告中登记，不是测试本身的 bug）。
 * 全库 {@code b_field} 出现总数 19，与 §5.3「b_field token 总数 19」一致；其中指向本组件
 * FORMULA 字段的出现次数 12，与 §5.3「12 处」一致 —— 两个数字都能与活库对上，唯独
 * 「去重 8 条公式」这句话对不上（实测去重后是 <b>4</b> 条公式），已在报告中提请 PM 澄清用词。
 */
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_DB_SCAN", matches = "1")
class SumHostFieldAffectedFormulasLiveScanTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** (componentCode, formulaName) → 被引用的宿主 FORMULA 字段名集合（有序去重）。 */
    private record Affected(String componentCode, String componentName, String formulaName,
                            java.util.List<String> refFields) {}

    private static Connection connect() throws Exception {
        String host = System.getenv().getOrDefault("DB_HOST", "10.177.152.12");
        String port = System.getenv().getOrDefault("DB_PORT", "5432");
        String db = System.getenv().getOrDefault("DB_NAME", "cpq_db_0724");
        String user = System.getenv().getOrDefault("DB_USERNAME", "postgres");
        String pass = System.getenv().getOrDefault("DB_PASSWORD", "joii5231");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        return DriverManager.getConnection(url, user, pass);
    }

    /** 与 {@code FormulaCalculator.addExprFieldDeps}（B2）同口径的递归扫描：仅认 b_field，遇 KSUM 子 token 止步。 */
    private static void collectHostFormulaRefs(JsonNode expr, Set<String> formulaFieldNames,
                                               java.util.LinkedHashSet<String> hits) {
        if (expr == null || !expr.isArray()) return;
        for (JsonNode t : expr) {
            String type = t.path("type").asText("");
            if ("b_field".equals(type)) {
                String name = t.has("value") ? t.path("value").asText("") : t.path("name").asText("");
                if (formulaFieldNames.contains(name)) hits.add(name);
            } else if ("cross_tab_ref".equals(type) && !t.path("projectToHostKey").asBoolean(false)) {
                collectHostFormulaRefs(t.path("targetExpr"), formulaFieldNames, hits);
            }
        }
    }

    private static int countAllBFieldOccurrences(JsonNode expr) {
        if (expr == null || !expr.isArray()) return 0;
        int n = 0;
        for (JsonNode t : expr) {
            String type = t.path("type").asText("");
            if ("b_field".equals(type)) n++;
            else if ("cross_tab_ref".equals(type)) n += countAllBFieldOccurrences(t.path("targetExpr"));
        }
        return n;
    }

    private List<Affected> scanLiveDb(int[] totalComponentsOut, int[] totalBFieldOut) throws Exception {
        List<Affected> out = new ArrayList<>();
        try (Connection conn = connect();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT code, name, fields, formulas FROM component ORDER BY code")) {
            int total = 0;
            int totalBField = 0;
            while (rs.next()) {
                total++;
                String code = rs.getString("code");
                String name = rs.getString("name");
                JsonNode fields = M.readTree(rs.getString("fields"));
                JsonNode formulas = M.readTree(rs.getString("formulas"));

                Set<String> formulaFieldNames = new HashSet<>();
                if (fields.isArray()) {
                    for (JsonNode f : fields) {
                        if ("FORMULA".equals(f.path("field_type").asText(""))) {
                            formulaFieldNames.add(f.path("name").asText(""));
                        }
                    }
                }
                if (!formulas.isArray()) continue;
                for (JsonNode formula : formulas) {
                    JsonNode expr = formula.path("expression");
                    totalBField += countAllBFieldOccurrences(expr);
                    var hits = new java.util.LinkedHashSet<String>();
                    collectHostFormulaRefs(expr, formulaFieldNames, hits);
                    if (!hits.isEmpty()) {
                        out.add(new Affected(code, name, formula.path("name").asText(""),
                            new ArrayList<>(hits)));
                    }
                }
            }
            totalComponentsOut[0] = total;
            totalBFieldOut[0] = totalBField;
        }
        return out;
    }

    /**
     * AC-6 核心断言：受影响 (组件, 公式) 集合恰好等于需求文档 §5.4 表格的 4 行——
     * 一个不多、一个不少。多出一条 = 有新配置漂移进受影响范围，需求文档/AC-7 意图确认清单
     * 必须同步更新，测试应先红后由人工登记；少一条 = 说明这条公式已被 B9 重写挪出 SUM，
     * 需相应更新本类断言（属预期维护，不是回归）。
     */
    @Test void ac6_affectedFormulaSet_matchesDocumentedFourFormulas() throws Exception {
        int[] totalComponents = new int[1];
        int[] totalBField = new int[1];
        List<Affected> affected = scanLiveDb(totalComponents, totalBField);

        assertEquals(87, totalComponents[0],
            "全库组件数应为 87（需求文档 §5.3 实测基数）；数字漂移需先确认是否为无关的组件新增/删除");
        assertEquals(19, totalBField[0],
            "全库 b_field token 总数应为 19（需求文档 §5.3）；数字漂移说明有人改了组件公式配置，"
            + "需要重新过一遍受影响集合，不能想当然沿用旧结论");

        Set<String> actualPairs = new TreeSet<>();
        for (Affected a : affected) actualPairs.add(a.componentCode() + "::" + a.formulaName());

        Set<String> expectedPairs = new TreeSet<>(List.of(
            "COMP-0157::银点材料成本公式",
            "COMP-0157::公式11",
            "COMP-0157::v2-原材料成本公式(银点类)",
            "COMP-0032::银点材料成本公式"));

        assertEquals(expectedPairs, actualPairs,
            "受影响 (组件,公式) 集合必须恰好等于需求文档 §5.4 的 4 行；"
            + "差集即「结果会变但未被确认意图」的配置，AC-6/AC-7 双双不达标：" + actualPairs);

        // 逐条核对引用的字段名，确保不是「巧合命中同名」
        for (Affected a : affected) {
            assertEquals(List.of("来料损耗率", "来料加工费"), a.refFields(),
                a.componentCode() + "::" + a.formulaName() + " 引用字段应恰为 [来料损耗率, 来料加工费]："
                + a.refFields());
        }

        int totalFieldRefTriples = affected.stream().mapToInt(a -> a.refFields().size()).sum();
        assertEquals(8, totalFieldRefTriples,
            "按 (组件,公式,字段) 三元组展开应恰为 8（4 公式 × 2 字段），"
            + "这正是需求文档 §5.4 标题「存量 8 条」的真实计数口径");
    }

    /**
     * 文档口径澄清（非功能断言，供测试报告引用）：§5.3 说「12 处 / 去重 8 条公式」，
     * 但 §5.4 表格只列了 4 行公式。实测证实：12 = (组件,公式,字段) 三元组去重后的引用
     * <b>出现次数</b>（有的公式同一字段引用了两次，如 COMP-0157「银点材料成本公式」的
     * 「来料损耗率」「来料加工费」各出现 2 次 → 4 次，见需求文档实测明细）；
     * 8 = (公式,字段) 组合数（4 公式 × 2 字段）；<b>4 才是「去重后的公式条数」</b>。
     * §5.4 标题「存量 8 条公式清单」与其表格内容（4 行）不一致，建议改为
     * 「存量 4 条公式（8 处字段引用）清单」。本方法只做只读断言，不修改任何文档。
     */
    @Test void docNote_twelveOccurrences_eightFieldRefTriples_fourDistinctFormulas() throws Exception {
        List<Affected> affected = scanLiveDb(new int[1], new int[1]);
        long distinctFormulas = affected.stream()
            .map(a -> a.componentCode() + "::" + a.formulaName()).distinct().count();
        assertEquals(4, distinctFormulas,
            "去重后的公式条数应为 4（不是 §5.4 标题写的 8）；"
            + "8 是「公式×字段」引用条目数，不是公式数，建议 PM 澄清措辞");
    }
}
