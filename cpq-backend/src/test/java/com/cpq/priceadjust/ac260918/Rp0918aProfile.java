package com.cpq.priceadjust.ac260918;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * repair-260918 · S-BE 分片统一测试 profile（本包所有测试类共用 ⇒ 只启动一次 Quarkus）。
 *
 * <ul>
 *   <li>{@code cpq.security.rbac.enabled=false}：与既有 price-adjust HTTP 契约测试同口径，免登录；</li>
 *   <li>{@code quarkus.scheduler.enabled=false}：本测试进程连的是共享测试库 {@code cpq_db_test}，
 *       定时扫描会替库里<b>别人的客户</b>生成版本（全局副作用），且可能替本片自建客户生成版本打乱断言。
 *       如阶段二实测发现预算管道依赖调度器，用 {@code -Drp0918a.schedulerEnabled=true} 打开并回报主线；</li>
 *   <li>{@code cpq.price-adjust.startup-recovery.enabled=false}：显式关闭启动收尾（问题说明 ⑤-14 / test.md §1）。
 *       本 profile 下调 {@code runOnStartup()} 是 AC-25 的被测条件；🚫 不得改成 true。</li>
 * </ul>
 */
public class Rp0918aProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> m = new HashMap<>();
        m.put("cpq.security.rbac.enabled", "false");
        m.put("quarkus.scheduler.enabled", System.getProperty("rp0918a.schedulerEnabled", "false"));
        m.put("cpq.price-adjust.startup-recovery.enabled", "false");
        return m;
    }
}
