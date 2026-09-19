package com.cpq.priceadjust.ac260918;

import com.cpq.priceadjust.service.MaterialVersionUpgradeService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * test.md §3.2 点名的注入缝：{@code MaterialVersionUpgradeService#upgrade}（「含 dryRun 参数的各重载」）。
 *
 * <p>按方法名 {@code upgrade} 拦截，参数按<b>类型</b>解析（不读实现、不依赖重载的确切签名）：
 * <ul>
 *   <li>{@code dryRun} = 第一个 Boolean 参数；</li>
 *   <li>{@code lineId} = 第一个 UUID 参数、{@code versionId} = 第二个 UUID 参数（与既有测试里
 *       {@code upgrade(lineItemId, targetVersionId, dryRun)} 的顺序一致）；规则匹配时对「任一 UUID 参数」
 *       做包含判断，重载的 UUID 顺序若不同也不会漏拦。</li>
 * </ul>
 *
 * <p>🔒 每个用到注入的用例都必须断言「规则确实触发过」（testing.md §5.7②：干预必须先证明生效）。
 */
public final class UpgradeInterceptor {

    public record Call(long seq, String signature, UUID lineId, UUID versionId, Boolean dryRun,
                       List<UUID> uuids, String thread, long atMillis) {
        public boolean touches(Collection<UUID> ids) {
            for (UUID u : uuids) if (ids.contains(u)) return true;
            return false;
        }
    }

    @FunctionalInterface
    public interface Action {
        void run(Call call) throws Throwable;
    }

    public static final class Rule {
        private final String name;
        private final Predicate<Call> when;
        private final Action action;
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final AtomicInteger fired = new AtomicInteger();

        Rule(String name, Predicate<Call> when, Action action) {
            this.name = name;
            this.when = when;
            this.action = action;
        }

        public Rule disarm() {
            armed.set(false);
            return this;
        }

        public Rule arm() {
            armed.set(true);
            return this;
        }

        public int fired() {
            return fired.get();
        }

        public String name() {
            return name;
        }
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<Rule> rules = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    private UpgradeInterceptor() {
    }

    /** 在 {@code @BeforeEach} / 测试方法体里调用；测试结束 QuarkusMock 自动复位。 */
    public static UpgradeInterceptor install() {
        UpgradeInterceptor ic = new UpgradeInterceptor();
        DelegatingMock.install(MaterialVersionUpgradeService.class, ic::before);
        return ic;
    }

    public Rule addRule(String name, Predicate<Call> when, Action action) {
        Rule r = new Rule(name, when, action);
        rules.add(r);
        return r;
    }

    public void clearRules() {
        for (Rule r : rules) r.disarm();
        rules.clear();
    }

    public List<Call> calls() {
        return new ArrayList<>(calls);
    }

    /** 按「签名 + dryRun + 线程类型」汇总拦到的 upgrade 调用次数（证据：三条路径的拦截是否触发）。 */
    public String signatureSummary() {
        java.util.Map<String, Integer> m = new java.util.TreeMap<>();
        for (Call c : calls) {
            String th = c.thread().startsWith("executor-thread") ? "executor" : (c.thread().equals("main") ? "main" : "other:" + c.thread());
            m.merge(c.signature() + " dryRun=" + c.dryRun() + " thread=" + th + " params=" + c.uuids().size() + "uuid", 1, Integer::sum);
        }
        return m.toString();
    }

    public List<Call> callsSince(long fromMillis) {
        List<Call> out = new ArrayList<>();
        for (Call c : calls) if (c.atMillis() >= fromMillis) out.add(c);
        return out;
    }

    private void before(Method m, Object[] args) throws Throwable {
        if (!"upgrade".equals(m.getName())) return;
        Call c = parse(m, args);
        calls.add(c);
        for (Rule r : rules) {
            if (r.armed.get() && r.when.test(c)) {
                r.fired.incrementAndGet();
                r.action.run(c);
            }
        }
    }

    private Call parse(Method m, Object[] args) {
        List<UUID> uuids = new ArrayList<>();
        Boolean dry = null;
        if (args != null) {
            for (Object a : args) {
                if (a instanceof UUID u) uuids.add(u);
                else if (a instanceof Boolean b && dry == null) dry = b;
            }
        }
        StringBuilder sig = new StringBuilder(m.getName()).append('(');
        Class<?>[] pt = m.getParameterTypes();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) sig.append(',');
            sig.append(pt[i].getSimpleName());
        }
        sig.append(')');
        return new Call(seq.incrementAndGet(), sig.toString(),
            uuids.isEmpty() ? null : uuids.get(0),
            uuids.size() > 1 ? uuids.get(1) : null,
            dry, List.copyOf(uuids), Thread.currentThread().getName(), System.currentTimeMillis());
    }

    // ── 常用谓词 / 动作 ──────────────────────────────────────────────────────

    /** dryRun == null 表示不限。 */
    public static Predicate<Call> onLines(Set<UUID> lineIds, Boolean dryRun) {
        return c -> (dryRun == null || dryRun.equals(c.dryRun())) && c.touches(lineIds);
    }

    public static Action throwing(Supplier<? extends Throwable> ex) {
        return c -> {
            throw ex.get();
        };
    }

    public static Action sleeping(long millis) {
        return c -> Thread.sleep(millis);
    }
}
