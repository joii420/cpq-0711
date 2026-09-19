package com.cpq.priceadjust.ac260918;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * test.md §3.2 注入缝的通用落地：用 {@link QuarkusMock} 给某个 bean 装一个「默认把一切调用委派给真实实例」的
 * Mockito mock，在委派前先跑一个钩子（记录 / 抛异常 / 变慢 / 探测）。
 *
 * <p>为什么不按方法签名 stub：test.md 只点名了方法名（{@code upgrade} 的「含 dryRun 参数的各重载」），
 * 测试方<b>不许读实现</b>去查重载的确切签名 ⇒ 按方法名拦截、按参数类型解析，对任何重载都生效。
 *
 * <p>⚠️ 约束：
 * <ul>
 *   <li>必须在<b>没有</b> mock 已安装时调用（{@code @BeforeEach} 或测试方法体里；QuarkusMock 在每个测试结束后自动复位），
 *       否则 {@link ClientProxy#unwrap} 取到的是上一个 mock —— 已加防御性检查；</li>
 *   <li>真实实例是容器里的上下文实例（带拦截器的子类），反射调用照样经过 {@code @Transactional} 等拦截器；</li>
 *   <li>只拦截<b>经由客户端代理</b>的调用（其他 bean 注入它再调用）；bean 内部的 this.xxx() 自调用拦不到。</li>
 * </ul>
 */
public final class DelegatingMock {

    @FunctionalInterface
    public interface Hook {
        /** 在委派给真实实例之前调用；抛出的任何 Throwable 会原样抛给调用方（真实方法不执行）。 */
        void before(Method method, Object[] rawArgs) throws Throwable;
    }

    private DelegatingMock() {
    }

    public static <T> T install(Class<T> type, Hook hook) {
        T proxy = Arc.container().instance(type).get();
        if (proxy == null) {
            throw new IllegalStateException("容器里找不到 bean: " + type.getName());
        }
        Object real = ClientProxy.unwrap(proxy);
        if (real == null || Mockito.mockingDetails(real).isMock()) {
            throw new IllegalStateException("取不到 " + type.getName() + " 的真实实例（可能已有 mock 在位）");
        }
        T mock = Mockito.mock(type, Mockito.withSettings().defaultAnswer(inv -> answer(inv, real, hook)));
        QuarkusMock.installMockForType(mock, type);
        return mock;
    }

    private static Object answer(InvocationOnMock inv, Object real, Hook hook) throws Throwable {
        Method m = inv.getMethod();
        Object[] raw = inv.getRawArguments();
        hook.before(m, raw);
        try {
            m.setAccessible(true);
            return m.invoke(real, raw);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }
}
