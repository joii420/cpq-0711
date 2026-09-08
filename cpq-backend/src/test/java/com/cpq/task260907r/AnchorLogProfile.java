package com.cpq.task260907r;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * AC-20 四层用例专属 profile。
 *
 * <p>🔑 <b>把两个开关钉进 profile，不留给命令行</b>：
 * <ul>
 *   <li>{@code record-check.enabled=false} —— B-3 的 26 处 ALTER 未落库，开着起不来；</li>
 *   <li>{@code dsrecord} 包 DEBUG —— AC-20③ 要求「断言必须看到『粒度列兜底命中』日志」。
 *       🚨 若把它留给命令行，别人不带那个 flag 跑，日志断言会以「机制没生效」的面目**假红**。
 *       ⇒ 判据依赖的前提，必须由用例自己保证。</li>
 * </ul>
 */
public class AnchorLogProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "cpq.dataset.record-check.enabled", "false",
                "quarkus.log.min-level", "DEBUG",
                "quarkus.log.category.\"com.cpq.quotation.service.dsrecord\".level", "DEBUG");
    }
}
