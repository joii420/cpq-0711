package com.cpq.task260907productfilter;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.restassured.response.Response;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>T-B6 · AC-8</b>（客户名称由后端一次 JOIN 带出，不产生 N+1）。
 *
 * <p>需求文档.md §③ AC-8：{@code GET /dataset/quote/parts} 的 SQL 条数<b>与返回行数无关</b>，恒为常数
 * （count 1 条 + page 1 条）。判据：客户名称必须在同一条 SELECT 里 {@code LEFT JOIN customer} 带出。
 *
 * <p>手法沿用本工程既有的 {@code SqlCountNPlusOneGuardTest}：Hibernate {@link Statistics} 对裸
 * {@code em.createNativeQuery(sql)} 与 REST 层执行的 SQL 同样按查询文本记录执行次数
 * （不依赖读取实现代码，只黑盒统计 JDBC prepared statement 数）。
 *
 * <p><b>判别逻辑</b>：分别在「5 行客户 A 夹具」与「5+20=25 行客户 A 夹具」两种数据量下调用同一个
 * {@code GET /parts?customerNo=A} 接口，统计期间产生的 SQL <b>预编译语句总数</b>
 * （{@link Statistics#getPrepareStatementCount()} 增量）。若为逐行查客户名（N+1），
 * 语句数会随行数从「常数」变成「随 N 增长」；若为一次 JOIN，两次调用的语句数应<b>相等</b>。
 */
@DisplayName("task-260907-产品管理客户过滤 · AC-8 客户名 JOIN 带出无 N+1")
@QuarkusTest
class Ac8NoN1Test extends PfTestBase {

    private static final String PREFIX_FEW = FX + "N1FEW-";
    private static final String PREFIX_MANY = FX + "N1MANY-";
    private final List<String> insertedMaterialNos = new ArrayList<>();

    @BeforeEach
    void setUpFixture() {
        assumeMaterialCustomerDimensionReady("AC-8");
        for (int i = 0; i < 5; i++) {
            insertPlain(PREFIX_FEW + i);
        }
    }

    private void insertPlain(String materialNo) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("INSERT INTO ds_quote_material "
                        + "(material_no, material_name, customer_no, source, created_at) "
                        + "VALUES (:mn, 'T260907M AC-8 夹具', :cn, 'IMPORT', now())")
                        .setParameter("mn", materialNo)
                        .setParameter("cn", CUST_A)
                        .executeUpdate());
        insertedMaterialNos.add(materialNo);
    }

    @AfterEach
    void cleanup() {
        if (insertedMaterialNos.isEmpty()) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() -> {
            for (String mn : insertedMaterialNos) {
                em.createNativeQuery("DELETE FROM ds_quote_material WHERE material_no = :mn")
                        .setParameter("mn", mn).executeUpdate();
            }
        });
        insertedMaterialNos.clear();
    }

    private Statistics stats() {
        Statistics st = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        st.setStatisticsEnabled(true);
        return st;
    }

    @Test
    @DisplayName("AC-8：GET /parts 产生的 SQL 预编译语句数与匹配行数无关（5 行 vs 25 行应相等）")
    void sqlStatementCountIndependentOfRowCount() {
        Statistics st = stats();

        st.clear();
        Response r1 = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 200, PREFIX_FEW);
        assertEquals(200, r1.statusCode(), "body=" + r1.asString());
        List<?> items1 = r1.jsonPath().getList("data.items");
        long few = items1 == null ? 0 : items1.size();
        long stmtsFew = st.getPrepareStatementCount();
        System.out.println("[AC-8] 5 行夹具：命中行数=" + few + " 预编译语句数=" + stmtsFew);
        assertTrue(few > 0, "AC-8 前置：5 行夹具一行都没命中 ⇒ 断言空跑");

        // 追加 20 行，行数从 5 变到 25（同一 keyword 前缀之外，另一批 —— 用不同 keyword 前缀单独统计）
        for (int i = 0; i < 20; i++) {
            insertPlain(PREFIX_MANY + i);
        }

        st.clear();
        Response r2 = PfApi.parts(adminSession(), PfApi.QUOTE, CUST_A, 0, 200, PREFIX_MANY);
        assertEquals(200, r2.statusCode(), "body=" + r2.asString());
        List<?> items2 = r2.jsonPath().getList("data.items");
        long many = items2 == null ? 0 : items2.size();
        long stmtsMany = st.getPrepareStatementCount();
        System.out.println("[AC-8] 20 行夹具：命中行数=" + many + " 预编译语句数=" + stmtsMany);
        assertTrue(many > 0, "AC-8 前置：20 行夹具一行都没命中 ⇒ 断言空跑");

        // 阳性对照：证明两次查询确实命中了不同数量的行（否则下面「语句数相等」的判别力为零）
        assertTrue(many > few, "AC-8 前置：第二次命中行数(" + many + ") 应大于第一次(" + few
                + ")，否则本判据无法区分「常数」与「随 N 增长」");

        assertEquals(stmtsFew, stmtsMany, "AC-8：GET /parts 产生的 SQL 语句数应恒为常数（与命中行数无关），"
                + "实际 5 行=" + stmtsFew + " 条，20 行(+20)=" + stmtsMany + " 条 —— "
                + "语句数随行数增长通常意味着逐行查询客户名称（N+1），而不是一次 LEFT JOIN 带出");
    }
}
