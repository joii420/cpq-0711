package com.cpq.task260915;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * task-260915 · 分片 S-B 专用测试 profile。
 *
 * <p>🚫 <b>不改 {@code application-test.properties}</b> —— 那是全体测试共用的共享配置文件
 * （{@code test.md §3 S-B} 明确禁止为了测 N+1 去动全局开关）。本类只在<b>测试作用域</b>内覆盖两项：
 *
 * <ul>
 *   <li>{@code cpq.security.rbac.enabled=false} —— 共享配置里是 {@code true}，而登录端点
 *       {@code POST /api/cpq/auth/login} 在测试环境写 Redis session 会稳定 CONNECTION_CLOSED
 *       （既有基线 {@code Task0805ExportBindingReportTest} 同款记录）。关掉 RBAC 直连端点验业务逻辑。</li>
 *   <li>{@code quarkus.hibernate-orm.statistics=true} —— AC-13 要按 SQL 文本统计执行次数
 *       （手法沿用既有 {@code SqlCountNPlusOneGuardTest} / {@code Ac8NoN1Test}）。</li>
 * </ul>
 *
 * <p>本片 4 个 {@code @QuarkusTest} 类共用同一个 profile 类，Quarkus 只需重启一次。
 */
public class Sb260915Profile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "cpq.security.rbac.enabled", "false",
                "quarkus.hibernate-orm.statistics", "true");
    }
}
