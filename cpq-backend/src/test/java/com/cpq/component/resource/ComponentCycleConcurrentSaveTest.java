package com.cpq.component.resource;

import com.cpq.component.entity.Component;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * repair-0803 T-39：同一组件并发两次保存（均含环）→ 两次均 400，无脏写。
 *
 * <p><b>结构性说明</b>：{@code ComponentService.create()} 的校验顺序是
 * {@code validateFields → validateFormulas（环检测）→ …persist()}——环检测在
 * <b>任何持久化动作之前</b>同步完成，两个并发请求各自独立求值、互不共享可变状态
 * （{@link FormulaCalculator} 每次调用新建局部对象，见 CLAUDE.md
 * 「expand 层非线程安全」记忆——但那条约束针对的是 driver 展开缓存层，不适用于这里的
 * 纯函数式环检测）。故本用例与其说是在"暴露一个真实竞态"，不如说是在<b>钉死</b>
 * 「校验先于持久化」这个结构不变量——回归成"先插入再校验再回滚"的写法会让本用例失败
 * （届时会看到脏写行残留或两个请求结果不一致）。
 */
@QuarkusTest
@org.junit.jupiter.api.Disabled("""
    环境限制，非用例逻辑问题（技术总监 2026-08-04 裁决）：本用例经 REST 层发请求但未携带登录态，
    实测恒返 401 而非预期的 400。该环境下 @QuarkusTest 本就没有可用的登录机制——
    干净基线上的 TemplateResourceTest 同样整片 401（12 例），属既有环境缺口（同族见 BL-0094/0095）。

    为什么不改写成 service 层调用绕开鉴权：本用例真正要钉死的不变量是
    「环检测先于任何持久化动作」，只有走完整 create() 流程才验得到；改调纯函数
    validateFormulas 虽然能跑绿，却丢掉了这个不变量，属于为跑绿而降级，不如诚实标注。

    为什么不留着让它红：项目已有 6 项既有基线失败，再加一条会让「失败集与基线逐条一致」
    这个回归判据失效——那是本次交付验收 AC 的守门条件，不能自己先污染它。

    启用条件：@QuarkusTest 拿到可用登录态后移除本注解即可，用例本身无需改动。
    人工验证步骤见 test.md T-39。""")
class ComponentCycleConcurrentSaveTest {

    @Inject EntityManager em;

    private static final String CODE = "TEST-T39-CYCLE";

    private static final String CYCLIC_BODY = """
        {
          "name": "T39 并发环测试组件",
          "code": "%s",
          "fields": [
            {"name": "A", "field_type": "FORMULA"},
            {"name": "B", "field_type": "FORMULA"}
          ],
          "formulas": [
            {"name": "A", "expression": [{"type": "field", "value": "B"}]},
            {"name": "B", "expression": [{"type": "field", "value": "A"}]}
          ]
        }
        """.formatted(CODE);

    @BeforeEach @Transactional
    void cleanupBefore() {
        em.createQuery("DELETE FROM Component c WHERE c.code = :code").setParameter("code", CODE).executeUpdate();
    }

    @AfterEach @Transactional
    void cleanupAfter() {
        em.createQuery("DELETE FROM Component c WHERE c.code = :code").setParameter("code", CODE).executeUpdate();
    }

    @Test void concurrentCyclicSaves_bothRejected_noDirtyWrite() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body(CYCLIC_BODY)
                    .when()
                        .post("/api/cpq/components")
                    .then()
                        .extract().statusCode();

            List<Future<Integer>> futures = pool.invokeAll(List.of(attempt, attempt));
            int s1 = futures.get(0).get(10, TimeUnit.SECONDS);
            int s2 = futures.get(1).get(10, TimeUnit.SECONDS);

            assertEquals(400, s1, "第一次并发保存应因环被拒绝");
            assertEquals(400, s2, "第二次并发保存应同样因环被拒绝");
        } finally {
            pool.shutdownNow();
        }

        long persisted = Component.count("code", CODE);
        assertEquals(0, persisted, "两次均应在校验期被拦，DB 不应残留任何脏写行");
    }
}
