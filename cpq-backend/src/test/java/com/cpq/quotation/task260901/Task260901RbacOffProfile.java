package com.cpq.quotation.task260901;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * task-260901 后端契约测试的共享 Test Profile —— 关掉 RBAC。
 *
 * <p><b>为什么必须有它</b>（2026-09-01 首跑 16/16 全红 401「未登录」的根因）：
 * {@code src/test/resources/application.properties} 里虽然写了
 * {@code cpq.security.rbac.enabled=false}，但 {@code src/main/resources/application-test.properties:86}
 * 又把它设回了 {@code true} —— **profile 专属配置的优先级高于非 profile 的 application.properties**，
 * 于是测试期 RBAC 实际是开着的，{@code RoleFilter} 对带 {@code @RoleAllowed} 的
 * {@code QuotationResource} 一律返回 401。
 *
 * <p>既有测试对此有两种应对：{@code EnsureCardValuesEndpointTest} 选择**容忍 401**
 * （那等于放弃断言，是假绿），{@code Tc059ConcurrentSubmitHttpTest} 选择用 {@code @TestProfile}
 * 覆盖。本包一律走后者 —— 断言严格钉死 200/409，**绝不容忍 401**。
 *
 * <p>三个测试类共用同一个 profile 类，Quarkus 才会复用同一个测试实例（不同 profile 会各自重启）。
 */
public class Task260901RbacOffProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("cpq.security.rbac.enabled", "false");
    }
}
