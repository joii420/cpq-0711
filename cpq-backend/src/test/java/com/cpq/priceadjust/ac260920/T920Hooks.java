package com.cpq.priceadjust.ac260920;

import com.cpq.priceadjust.entity.MaterialPriceReview;
import com.cpq.priceadjust.entity.MaterialPriceVersionRef;
import com.cpq.priceadjust.service.PriceAdjustBudgetService;
import io.quarkus.arc.ClientProxy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * AC-2 的两侧判定。
 *
 * <h3>① 旧判定：原样调用现有逐料号函数（🚫 重写）</h3>
 * 组合方式取自 AC-2 原文列出的分支树（不是从实现读来的）：
 * <pre>
 *   依据行 = findBasisLine(customerNo, m)
 *   无依据行 → hasEverRejected ? 进池 : 推进
 *   有依据行 → 指针 = MaterialPriceVersionRef.findRef(customerNo, m)
 *              无指针 → 进池
 *              有指针 → hasRelevantPriceChange(目标版本, 指针版本, 依据行 id) ? 进池 : 推进
 * </pre>
 * 「扫不出相关元素 → 进池」由 {@code hasRelevantPriceChange} 自身承担（AC-2 要求原样调用，本类不另判）。
 * 签名来源：只 grep 了签名行（派工 c 段例外）——
 * {@code private BasisLine findBasisLine(String, String)}、{@code boolean hasRelevantPriceChange(UUID, UUID, UUID)}、
 * {@code static boolean MaterialPriceReview.hasEverRejected(String, String)}、
 * {@code static MaterialPriceVersionRef findRef(String, String)}。
 * {@code BasisLine} / {@code MaterialPriceVersionRef} 的字段名<b>未读</b>：按候选名反射，取不到时报出实际字段清单（执行期自证）。
 *
 * <h3>② 新批量判定：需要实现方提供的只读入口（见回报「需实现方配合」）</h3>
 * 约定（可用系统属性改写，不读实现）：{@code -Dt260920.batchDecision.method=<方法名>}（默认 {@code decideEnqueueBatch}），
 * 在 {@link PriceAdjustBudgetService} 上，参数为 {@code (UUID versionId, String customerNo)} 或
 * {@code (UUID versionId, String customerNo, Collection<String> materials)}（materials 传 null = 该客户全部范围）；返回对象须有
 * 进池集合与推进集合两个访问器（候选名见 {@link #POOLED} / {@link #ADVANCED}）。🔒 该入口必须只读、加入调用方事务。
 */
public final class T920Hooks {

    static final String[] POOLED = {"pooledMaterials", "pooled", "toPool", "pooledMaterialNos"};
    static final String[] ADVANCED = {"advancedMaterials", "advanced", "toAdvance", "advancedMaterialNos"};
    static final String[] BASIS_LINE_ID = {"lineItemId", "lineId", "basisLineItemId", "liId"};
    static final String[] REF_VERSION = {"versionId"};

    public record Decision(Set<String> pooled, Set<String> advanced) {
    }

    private T920Hooks() {
    }

    /** ① 旧判定（必须在调用方事务内调用，保证与新判定看到同一快照）。 */
    public static Decision oldDecision(PriceAdjustBudgetService budget, UUID versionId, String customerNo,
                                       Collection<String> scope) throws Exception {
        Object real = ClientProxy.unwrap(budget);
        Method findBasis = PriceAdjustBudgetService.class.getDeclaredMethod("findBasisLine", String.class, String.class);
        findBasis.setAccessible(true);
        Method relevant = PriceAdjustBudgetService.class.getDeclaredMethod("hasRelevantPriceChange", UUID.class, UUID.class,
            UUID.class);
        relevant.setAccessible(true);
        Set<String> pooled = new LinkedHashSet<>();
        Set<String> advanced = new LinkedHashSet<>();
        for (String m : scope) {
            Object basis = invoke(findBasis, real, customerNo, m);
            boolean pool;
            if (basis == null) {
                pool = MaterialPriceReview.hasEverRejected(customerNo, m);
            } else {
                MaterialPriceVersionRef ref = MaterialPriceVersionRef.findRef(customerNo, m);
                if (ref == null) {
                    pool = true;
                } else {
                    UUID prev = (UUID) field(ref, REF_VERSION);
                    UUID lineId = (UUID) field(basis, BASIS_LINE_ID);
                    pool = (Boolean) invoke(relevant, real, versionId, prev, lineId);
                }
            }
            (pool ? pooled : advanced).add(m);
        }
        return new Decision(pooled, advanced);
    }

    /** ② 新批量判定。 */
    @SuppressWarnings("unchecked")
    public static Decision newDecision(PriceAdjustBudgetService budget, UUID versionId, String customerNo,
                                       Collection<String> scope) throws Exception {
        String name = System.getProperty("t260920.batchDecision.method", "decideEnqueueBatch");
        Object real = ClientProxy.unwrap(budget);
        Method target = null;
        List<String> seen = new ArrayList<>();
        for (Class<?> c = real.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                seen.add(m.getName());
                if (m.getName().equals(name) && m.getParameterCount() >= 2 && m.getParameterCount() <= 3) {
                    target = m;
                    break;
                }
            }
            if (target != null) break;
        }
        if (target == null) {
            throw new AssertionError("AC-2 未执行：找不到新批量判定入口 " + name + "（需后端提供，见回报「需实现方配合」）；"
                + "PriceAdjustBudgetService 现有方法：" + new LinkedHashSet<>(seen));
        }
        target.setAccessible(true);
        Object result = target.getParameterCount() == 2
            ? invoke(target, real, versionId, customerNo)
            : invoke(target, real, versionId, customerNo, scope == null ? null : new ArrayList<>(scope));
        if (result == null) throw new AssertionError("AC-2：新批量判定返回 null");
        return new Decision(new LinkedHashSet<>((Collection<String>) accessor(result, POOLED)),
            new LinkedHashSet<>((Collection<String>) accessor(result, ADVANCED)));
    }

    static Object invoke(Method m, Object target, Object... args) throws Exception {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) throw ex;
            throw new IllegalStateException(c);
        }
    }

    static Object field(Object o, String[] candidates) throws Exception {
        List<String> names = new ArrayList<>();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (String n : candidates) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException ignored) {
                    // next
                }
            }
            for (Field f : c.getDeclaredFields()) names.add(f.getName() + ":" + f.getType().getSimpleName());
        }
        throw new AssertionError("取不到字段 " + String.join("/", candidates) + "，" + o.getClass().getName()
            + " 实际字段：" + names + " —— 报主线确认字段名（测试侧未读实现）");
    }

    static Object accessor(Object o, String[] candidates) throws Exception {
        for (String n : candidates) {
            try {
                Method m = o.getClass().getMethod(n);
                return m.invoke(o);
            } catch (NoSuchMethodException ignored) {
                // next
            }
        }
        return field(o, candidates);
    }
}
