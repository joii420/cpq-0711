package com.cpq.priceadjust.ac260920;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * task-260920 · S-1 的四个测试 profile（每个 profile 启动一次 Quarkus）。
 *
 * <ul>
 *   <li>共同：{@code quarkus.scheduler.enabled=false}（测试库是共享库，定时扫描会替别人的客户生成版本）；</li>
 *   <li>{@link Base}：RBAC 关、启动收尾关、并发度取测试配置默认（1）；</li>
 *   <li>{@link Conc3}：同上 + 并发度<b>显式 3</b>（test.md §1.1 S1-2：AC-17 / AC-24）；</li>
 *   <li>{@link RbacOn}：RBAC <b>开</b>（AC-29；阳性对照另在用例里证明开关确实生效）；</li>
 *   <li>{@link StartupOn}：启动收尾开关<b>开</b>（AC-16 / AC-18）。⚠️ 开关开着时 Quarkus 启动本身就会调一次
 *       {@code runOnStartup()}，作用于测试库全部待处理版本 ⇒ 使用它的测试类以
 *       {@code -Dt260920.allowRunOnStartup=true} 门控，未经用户批准（D-12）不得启动。</li>
 * </ul>
 */
public final class T920Profiles {

    private T920Profiles() {
    }

    static Map<String, String> common() {
        Map<String, String> m = new HashMap<>();
        m.put("quarkus.scheduler.enabled", "false");
        m.put("cpq.price-adjust.startup-recovery.enabled", "false");
        m.put("cpq.security.rbac.enabled", "false");
        return m;
    }

    public static class Base implements QuarkusTestProfile {
        public Base() {
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return common();
        }
    }

    public static class Conc3 implements QuarkusTestProfile {
        public Conc3() {
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> m = common();
            m.put("cpq.price-adjust.budget.concurrency", "3");
            return m;
        }
    }

    public static class RbacOn implements QuarkusTestProfile {
        public RbacOn() {
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> m = common();
            m.put("cpq.security.rbac.enabled", "true");
            return m;
        }
    }

    public static class StartupOn implements QuarkusTestProfile {
        public StartupOn() {
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> m = common();
            m.put("cpq.price-adjust.startup-recovery.enabled", "true");
            return m;
        }
    }
}
