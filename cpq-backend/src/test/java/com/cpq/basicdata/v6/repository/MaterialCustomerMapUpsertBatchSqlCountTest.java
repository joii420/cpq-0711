package com.cpq.basicdata.v6.repository;

import com.cpq.basicdata.v6.repository.MaterialCustomerMapRepository.MapRow;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-260825 大单量导入建单性能 —— B-27（AC-3 · material_customer_map upsert 语句数 · 导入 Q02 批量化护栏）。
 *
 * <p><b>签名来源</b>：主线 2026-08-28 读了实际代码后直接给出（不是本测试猜测）——
 * {@link MaterialCustomerMapRepository#upsertQuoteBatch(List, UUID, UUID)}，
 * 返回值语义（javadoc 原文）：本批次实际写入（INSERT 或客户守卫放行的 UPDATE）成功的
 * material_no 集合；折叠后某个 material_no 不在返回集合里 = 客户守卫拦截（跨客户串号）。
 *
 * <p><b>写本测试前必须知道的两条（主线原话，均已落实到夹具与断言里）</b>：
 * <ol>
 *   <li>该方法内部按 material_no 折叠（{@code LinkedHashMap<String,MapRow> folded}）——
 *       故本测试 N 行夹具的 material_no <b>两两不同</b>，避免折叠导致实际写库行数 &lt; N，
 *       使 SQL 条数判据失真。</li>
 *   <li>跨客户串号不抛异常，只是不出现在返回集合里——故本测试<b>不用"没抛异常"当成功判据</b>，
 *       改判返回集合的 size（并独立查库复核落库行数，双重确认）。</li>
 * </ol>
 *
 * <p><b>度量口径</b>：与 B-25（{@code SqlCountNPlusOneGuardTest}）同款——原生 UPSERT 走
 * {@code executeUpdate()}，不会被 {@code Statistics.getQueries()} 记录（已用一次性 probe 验证过，
 * 见 B-25 交付说明），改用 {@code Statistics.getPrepareStatementCount()} 增量。
 *
 * <p><b>证伪控制组</b>：用单行版 {@link MaterialCustomerMapRepository#upsertQuote(MapRow, UUID)}
 * 循环 N 次做对照，证明同款判据（ratioDelta &lt; ratioN/2）能把"真逐行"实现判红——不做这一步的绿不算数
 * （主线原话）。
 */
@QuarkusTest
class MaterialCustomerMapUpsertBatchSqlCountTest {

    private static final String TAG = "B27X";  // 2026-08-28 更正:material_no 列 varchar(20),原 TAG 太长导致 falsification 控制组溢出,改短
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    @Inject MaterialCustomerMapRepository repo;
    @Inject EntityManager em;

    @AfterEach
    @Transactional
    void cleanup() {
        // 精确按本测试专属前缀 TAG 清理，不涉及无 WHERE / 命中面不明的删除。
        em.createNativeQuery("DELETE FROM material_customer_map WHERE material_no LIKE :p")
                .setParameter("p", TAG + "%").executeUpdate();
    }

    private Statistics stats() {
        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);
        return st;
    }

    /** N 行夹具：material_no 与 customer_product_no 两两不同（防被折叠 / 防撞 uq_mcm_quote_cust_prod），
     *  customer_no 相同（同客户，避免触发客户守卫拦截——本测试测的是 SQL 条数，不是守卫语义）。 */
    private List<MapRow> buildRows(String tag, int n) {
        List<MapRow> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String materialNo = TAG + "-" + tag + "-M" + i;
            String customerProductNo = TAG + "-" + tag + "-P" + i;
            assertTrue(materialNo.length() <= 20,
                    "material_no 列上限 varchar(20),本用例生成串必须留在限内,实际=" + materialNo + "(" + materialNo.length() + "字符)");
            rows.add(new MapRow(
                    materialNo,
                    TAG + "-" + tag + "-C",
                    "客户名",
                    "客户料件名",
                    customerProductNo,
                    null, null, null, null, null, null));
        }
        return rows;
    }

    @Test
    @Transactional
    @DisplayName("B-27(AC-3): upsertQuoteBatch 的 JDBC 语句数不随 N 线性增长")
    void b27_upsertBatch_statementCountNotLinearInN() {
        Statistics st = stats();

        List<MapRow> rows5 = buildRows("N5", 5);
        st.clear();
        long ps0 = st.getPrepareStatementCount();
        Set<String> written5 = repo.upsertQuoteBatch(rows5, USER, null);
        long delta5 = st.getPrepareStatementCount() - ps0;
        assertEquals(5, written5.size(),
                "5 行两两不同 material_no、同客户、customerProductNo 两两不同,不应触发客户守卫拦截,应全部写入,实际=" + written5);

        List<MapRow> rows50 = buildRows("N50", 50);
        st.clear();
        long ps1 = st.getPrepareStatementCount();
        Set<String> written50 = repo.upsertQuoteBatch(rows50, USER, null);
        long delta50 = st.getPrepareStatementCount() - ps1;
        assertEquals(50, written50.size(),
                "50 行应全部写入(不因折叠或客户守卫而减少),实际写入数=" + written50.size());

        System.out.printf("[B-27] N=5 ps_delta=%d(written=%d) | N=50 ps_delta=%d(written=%d)%n",
                delta5, written5.size(), delta50, written50.size());

        assertTrue(delta5 > 0, "N=5 应产生真实 JDBC 语句(非空验证,不是空跑)");
        assertTrue(delta50 > 0, "N=50 应产生真实 JDBC 语句(非空验证,不是空跑)");
        double ratioN = 50.0 / 5.0;
        double ratioDelta = (double) delta50 / (double) delta5;
        assertTrue(ratioDelta < ratioN / 2.0,
                "N 从 5 增至 50(10倍),upsertQuoteBatch 的 JDBC 语句数增长比例应显著小于 10 倍" +
                "(当前=" + String.format("%.2f", ratioDelta) + " 倍),否则说明是逐行 upsert 而非批量。" +
                " delta5=" + delta5 + " delta50=" + delta50);

        // 独立复核:DB 里真实落了这些行(非空验证,双重确认,不只信返回值)
        Number cnt5 = (Number) em.createNativeQuery(
                "SELECT count(*) FROM material_customer_map WHERE material_no LIKE :p")
                .setParameter("p", TAG + "-N5-%").getSingleResult();
        assertEquals(5, cnt5.longValue(), "DB 内 N=5 应有 5 行落库");
        Number cnt50 = (Number) em.createNativeQuery(
                "SELECT count(*) FROM material_customer_map WHERE material_no LIKE :p")
                .setParameter("p", TAG + "-N50-%").getSingleResult();
        assertEquals(50, cnt50.longValue(), "DB 内 N=50 应有 50 行落库");
    }

    @Test
    @Transactional
    @DisplayName("B-27·证伪控制组: upsertQuote(单行版)循环N次(真逐行),同款判据应把它判红")
    void b27_falsification_perRowUpsert_scalesLinearlyWithN() {
        Statistics st = stats();

        List<MapRow> rows5 = buildRows("FALSN5", 5);
        st.clear();
        long ps0 = st.getPrepareStatementCount();
        for (MapRow r : rows5) {
            int affected = repo.upsertQuote(r, USER);
            assertEquals(1, affected, "全新 material_no,单行版 upsertQuote 应影响 1 行(非空验证)");
        }
        long delta5 = st.getPrepareStatementCount() - ps0;

        List<MapRow> rows50 = buildRows("FALSN50", 50);
        st.clear();
        long ps1 = st.getPrepareStatementCount();
        for (MapRow r : rows50) repo.upsertQuote(r, USER);
        long delta50 = st.getPrepareStatementCount() - ps1;

        double ratioN = 50.0 / 5.0;
        double ratioDelta = (double) delta50 / (double) delta5;
        System.out.printf("[B-27-FALSIFY] 单行版循环: N=5 delta=%d | N=50 delta=%d | ratioDelta=%.2f(期望≈%.2f,即接近N倍)%n",
                delta5, delta50, ratioDelta, ratioN);

        assertTrue(ratioDelta >= ratioN / 2.0,
                "测量工具失效:单行版 upsertQuote 循环 N 次(真逐行)的 ps_delta 比例=" + ratioDelta +
                " 竟然 < " + (ratioN / 2.0) + "(与 B-27 正式测试同一个阈值),说明这把尺子测不出'逐行'与'批量'的区别," +
                "B-27 正式测试的绿不可信");
        System.out.println("[B-27-FALSIFY] 判据确认有效:真逐行实现下 ratioDelta=" + ratioDelta +
                " >= " + (ratioN / 2.0) + "(会被 B-27 同款判据正确判红)——证明 B-27 的 PASS 不是测量工具失灵导致的空跑");
    }
}
