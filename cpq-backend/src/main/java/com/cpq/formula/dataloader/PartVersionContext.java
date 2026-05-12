package com.cpq.formula.dataloader;

/**
 * 当前请求线程的 part_version 上下文（ThreadLocal）。
 *
 * <p>用途：让 DataLoader / TemplateFormulaService 等深层求值代码无需修改方法签名
 * 就能拿到 quotation_line_item.part_version_locked，自动注入到 14 张版本化表的查询谓词中。
 *
 * <p>调用模式（典型场景：ExcelViewService.buildRowData / regenerateAllSnapshots）：
 * <pre>
 *   PartVersionContext.set(li.partVersionLocked);
 *   try {
 *       // ... 调 templateFormulaService.evaluateFormula 等深层求值
 *   } finally {
 *       PartVersionContext.clear();
 *   }
 * </pre>
 *
 * <p>DataLoader.loadByPath 4-arg 重载内部会读 {@link #get()}，若非 null 则
 * 自动透传到 5-arg 重载（B3 实现），从而注入 AND part_version=N 谓词。
 *
 * <p>注意事项：
 * <ul>
 *   <li>仅适用于 HTTP 请求线程（单线程内顺序执行）；如果在 CompletableFuture 跨线程
 *       异步执行，ThreadLocal 不会自动传播</li>
 *   <li>务必在 finally 块清理，避免线程池里下一个请求误用旧值</li>
 *   <li>嵌套调用：上层 set 后，下层重复 set 会覆盖；下层 finally clear 也会清掉
 *       上层的值。如有嵌套需求改为 Deque&lt;Integer&gt; 栈式管理</li>
 * </ul>
 */
public final class PartVersionContext {

    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

    private PartVersionContext() {}

    /** 设置当前线程的 part_version；传 null 等价于 clear()。 */
    public static void set(Integer partVersion) {
        if (partVersion == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(partVersion);
        }
    }

    /** 返回当前线程的 part_version；未设置时返回 null。 */
    public static Integer get() {
        return CURRENT.get();
    }

    /** 清除当前线程的 part_version（finally 块必调）。 */
    public static void clear() {
        CURRENT.remove();
    }
}
