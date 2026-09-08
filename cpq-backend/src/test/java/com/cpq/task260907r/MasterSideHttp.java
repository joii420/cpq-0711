package com.cpq.task260907r;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 🅱️ <b>A/B 的「改动前」那一侧 —— 共享 dev server {@code localhost:8081}（跑的是 master 代码）。</b>
 *
 * <h3>为什么这不是取巧，而是本项目里<u>唯一</u>真实可得的 B 侧</h3>
 * AC-15② / AC-16 / AC-17 都要求「<b>同一时刻窗口</b>内、<b>改动前后</b>各跑一次」。
 * 而「改动后」在 worktree 的 {@code @QuarkusTest} 进程里（被测代码就在这儿），
 * 「改动前」若靠**历史记录的数字**充当，AC 原文明令禁止（共享库在漂，历史数字必然失效）。
 *
 * <p>🔑 <b>8081 恰好满足全部三个条件</b>（2026-09-07 实测，见下）：
 * <ol>
 *   <li><b>跑的是 master 代码</b> —— {@code quotation/service/dsrecord/} 这个包只在 worktree 里，
 *       ⇒ 8081 的 {@code costing-approve/preview} 响应<b>没有 {@code dsBackfill} 段</b>。
 *       这既是判别依据，也是 {@link #assertIsMasterSide} 的阳性对照。</li>
 *   <li><b>连的是同一个库</b> {@code cpq_db_0724} —— 实测 {@code GET /quotations} 的
 *       {@code totalElements} 与 {@code SELECT count(*) FROM quotation} 逐字相等（287 == 287）。</li>
 *   <li><b>同一时刻窗口</b> —— 两侧在同一条用例里背靠背调用，中间不隔任何东西。</li>
 * </ol>
 *
 * <h3>🚨 两条纪律</h3>
 * <ul>
 *   <li>🚫 <b>只对本任务前缀（{@code T260907R-}）的夹具做写操作</b>。别人的单一律<b>只读</b>
 *       （{@code api.md §1} 明写预览是只读、无副作用、幂等）—— 尤其<b>绝不</b>对别人的单点核价通过，
 *       那会真的写 V6 主表。</li>
 *   <li>⚠️ <b>不走系统代理</b>：本机 shell 常设 {@code http_proxy=127.0.0.1:7890}，
 *       走代理访问 localhost 会返 502。{@code HttpClient} 显式 {@code NO_PROXY}。</li>
 * </ul>
 */
final class MasterSideHttp {

    static final String BASE = "http://localhost:8081";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String cookieHeader;

    /** 结果三元组：状态码 + 原文 + 解析后的 JSON（解析失败时 {@code json} 为 null）。 */
    record R(int status, String body, JsonNode json) {
        JsonNode data() { return json == null ? null : json.path("data"); }
    }

    /**
     * 前置闸：8081 必须活着、必须能登录、必须<b>确实是 master 侧</b>。
     *
     * <p>🚫 拿不到就<b>硬失败并说清这是环境前置</b> —— 🚫 不许降级成「跳过 B 侧只验 A 侧」，
     * 那会让本用例退化成「单侧自说自话」，正是 {@code test.md} 风险点 5 要防的形态。
     */
    void login() {
        R alive = raw("GET", "/api/cpq/components", null, null);
        if (alive.status() != 401) {
            fail("⛔ 环境前置未满足（**不是被测功能的结论**）：共享 dev server " + BASE
                    + " 未登录访问 /api/cpq/components 期望 401，实际 " + alive.status()
                    + "。A/B 的「改动前」那一侧取自 8081，它不健康就<b>做不了 A/B</b>。"
                    + "🚫 不许降级成只验 A 侧。body=" + trim(alive.body()));
        }
        R login = raw("POST", "/api/cpq/auth/login",
                "{\"username\":\"admin\",\"password\":\"Admin@2026\"}", null);
        assertEquals(200, login.status(),
                "⛔ 环境前置：8081 admin 登录失败（" + login.status() + "）。body=" + trim(login.body()));
        R me = get("/api/cpq/auth/me");
        assertEquals(200, me.status(), "⛔ 环境前置：8081 会话未生效。body=" + trim(me.body()));
        assertEquals("SYSTEM_ADMIN", me.data().path("role").asText(),
                "⛔ 环境前置：8081 会话角色不是 SYSTEM_ADMIN ⇒ B 侧的 200 断言失去意义");
    }

    /**
     * 🚨 <b>A/B 的阳性对照</b>：证明 B 侧真的是<b>另一份代码</b>。
     *
     * <p>不打这一枪，「两侧结果相同」有两个无法区分的解释：
     * ① 本次改动确实没波及；② <b>两侧根本是同一份代码</b>（8081 恰好也在跑 worktree）。
     * 后者会让整条反向回归用例<b>恒真</b>。
     *
     * @param dsQuotationId 一张走 ds 新链路的报价单 —— 改动后侧会返 {@code dsBackfill}，master 侧不会
     */
    void assertIsMasterSide(String dsQuotationId) {
        R r = get("/api/cpq/quotations/" + dsQuotationId + "/costing-approve/preview");
        assertEquals(200, r.status(), "B 侧判别：预览应 200，实际 " + r.status() + " body=" + trim(r.body()));
        boolean hasDs = r.data() != null && r.data().has("dsBackfill");
        if (hasDs) {
            fail("🚨 A/B 阳性对照失败：" + BASE + " 的预览响应里**出现了** dsBackfill 段 ⇒ "
                    + "8081 跑的不是 master，而是本分支代码。此时「改动前 vs 改动后」两侧是同一份代码，"
                    + "任何「结果相同」都是恒真的假绿，本用例不构成证据。");
        }
    }

    R get(String path) { return raw("GET", path, null, cookieHeader); }

    R post(String path, String body) { return raw("POST", path, body, cookieHeader); }

    R put(String path, String body) { return raw("PUT", path, body, cookieHeader); }

    private R raw(String method, String path, String body, String cookie) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", "application/json");
            if (cookie != null) b.header("Cookie", cookie);
            b.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            captureCookies(resp);
            JsonNode json = null;
            try { json = MAPPER.readTree(resp.body()); } catch (Exception ignored) { }
            return new R(resp.statusCode(), resp.body(), json);
        } catch (Exception e) {
            throw new AssertionError("⛔ 环境前置：调用 " + BASE + path + " 失败（"
                    + e + "）。这是**用例环境失败**，不是业务结论。", e);
        }
    }

    private void captureCookies(HttpResponse<String> resp) {
        List<String> set = resp.headers().allValues("set-cookie");
        if (set.isEmpty()) return;
        List<String> pairs = new ArrayList<>();
        for (String s : set) {
            int i = s.indexOf(';');
            pairs.add(i < 0 ? s : s.substring(0, i));
        }
        cookieHeader = String.join("; ", pairs);
    }

    static String trim(String s) {
        if (s == null) return "<null>";
        return s.length() <= 400 ? s : s.substring(0, 400) + "…";
    }
}
