package com.cpq.basicdata.v6.resource;

import com.cpq.basicdata.v6.dto.ProcessMasterDTO;
import com.cpq.common.dto.ApiResponse;
import com.cpq.common.dto.PageResult;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * task-0728 · 四个变更点的端点层自检（路由 + Resource→Service 参数直通 + 响应头）。
 *
 * <p>后端 dev server(8081) 跑的是主仓代码、没有本分支的新端点，无法 curl 验证；这里做两层等价验证：
 * <ol>
 *   <li><b>路由层</b>：走进程内 HTTP。测试环境 RBAC 生效且无会话，故受保护端点返 <b>401</b>；
 *       用「同前缀的不存在子路径返 404」作对照，401 即证明<b>路由确实命中了资源方法</b>
 *       （尤其 {@code /v6/process-master/categories} 没被 {@code /{id}} 吞掉）；</li>
 *   <li><b>参数直通层</b>：直接调用 Resource 方法（绕过鉴权），验证 {@code @QueryParam} 的顺序 /
 *       语义没接错（如 isOutsource 与 processCategory 传反）。</li>
 * </ol>
 *
 * <p>⚠️ <b>task-260907（2026-09-07）</b>：原 A1（核价基础数据 parts 端点参数直通）与
 * A4（核价基础数据模板下载响应头）两个用例，已随主数据维护端核价页签的整体下线而删除，
 * 其被测端点不再存在。本类其余用例（工序主数据端点的版式断言）不受影响。
 */
@QuarkusTest
class MasterDataLayoutEndpointsTest {

    @Inject EntityManager em;
    @Inject ProcessMasterResource processMasterResource;
    @Inject BasicDataImportV6Resource importResource;

    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM process_master WHERE process_no LIKE 'ZT0728E%'").executeUpdate();
    }

    @Transactional
    void seed() {
        em.createNativeQuery("INSERT INTO process_master(process_no, process_name, process_category,"
            + " is_outsource, created_at, updated_at)"
            + " VALUES ('ZT0728E1','端点自检-自制','ZT分类甲', FALSE, now(), now()),"
            + "        ('ZT0728E2','端点自检-外协','ZT分类乙', TRUE,  now(), now())").executeUpdate();
    }

    @BeforeEach void before() { cleanup(); seed(); }
    @AfterEach  void after()  { cleanup(); }

    private static List<String> nos(PageResult<ProcessMasterDTO> p) {
        return p.getContent().stream().map(d -> d.processNo).collect(Collectors.toList());
    }

    // ------------------------------------------------------- ① 路由层（HTTP）

    /**
     * A3 路由不冲突：{@code /v6/process-master/categories} 命中资源方法（401=被鉴权拦下，说明匹配成功），
     * 而同前缀的不存在子路径返 404 —— 两者对照排除「401 是兜底返回」的可能。
     */
    @Test
    void categoriesPath_isRoutedNotSwallowedById() {
        int categories = RestAssured.given()
            .when().get("/api/cpq/v6/process-master/categories").getStatusCode();
        int bogus = RestAssured.given()
            .when().get("/api/cpq/v6/process-master/no-such-subpath-zt0728").getStatusCode();

        // 对照组落在 @Path("/{id}") 模板上：该模板只有 PUT/DELETE，没有 GET → 405 Method Not Allowed。
        // 这恰好就是「/categories 端点不存在」时会出现的结果，故 401 vs 405 是精确的路由判据。
        assertEquals(405, bogus,
            "对照组应命中 /{id} 模板但无 GET 方法（405）；实际=" + bogus + "，判据失效需重新设计对照");
        assertEquals(401, categories,
            "GET /categories 未命中自己的资源方法（被 /{id} 吞掉或未注册）");
    }

    /** 工序主数据列表端点带新参数时能路由到（401 而非 404/405/500）。 */
    @Test
    void changedEndpoints_areRoutedWithNewParams() {
        assertEquals(401, RestAssured.given()
            .queryParam("sortBy", "processName").queryParam("sortOrder", "desc")
            .queryParam("isOutsource", true).queryParam("processCategory", "制造")
            .when().get("/api/cpq/v6/process-master").getStatusCode());
    }

    // --------------------------------------------- ② 参数直通层（Resource 方法）

    /** A2：Resource 的四个 @QueryParam 按正确语义传给 service（防 isOutsource / processCategory 接反）。 */
    @Test
    void processMasterResource_passesParamsThrough() {
        var r1 = processMasterResource.list(0, 50, "ZT0728E", "processName", "desc", Boolean.TRUE, null);
        assertEquals(List.of("ZT0728E2"), nos(r1.getData()));

        var r2 = processMasterResource.list(0, 50, "ZT0728E", null, "asc", null, "ZT分类甲");
        assertEquals(List.of("ZT0728E1"), nos(r2.getData()));

        // 非法 sortBy 回退，不抛异常
        var r3 = processMasterResource.list(0, 50, "ZT0728E", "no_such_column", "desc", null, null);
        assertEquals(List.of("ZT0728E1", "ZT0728E2"), nos(r3.getData()));

        // 不传新参数 = 全部
        var r4 = processMasterResource.list(0, 50, "ZT0728E", null, "asc", null, null);
        assertEquals(2, r4.getData().getTotalElements());
    }

    /** A3：categories 端点返回去重分类（非 null）。 */
    @Test
    void categoriesResource_returnsList() {
        ApiResponse<List<String>> resp = processMasterResource.categories();
        List<String> cats = resp.getData();
        assertNotNull(cats);
        assertTrue(cats.containsAll(List.of("ZT分类甲", "ZT分类乙")), "缺自建分类: " + cats);
    }

}
