package com.cpq.priceadjust.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * task-0729 B0 · {@code MaterialVersionUpgradeService.upgrade} 的返回结果。
 *
 * <p>三种非成功态语义（§11.6.3 / api.md §3.3）：
 * <ul>
 *   <li>{@code FAILED}——数据问题（含 L3 {@code SUBTOTAL_MISMATCH}），需人工处理后重试；</li>
 *   <li>{@code CONFLICT}——重算期间该行被改动（{@code row_version} 不匹配），直接重试即可；</li>
 *   <li>{@code STALE}——所属版本已被新版取代，终态不可重试（本类不产生，由调用方 B5 判定）。</li>
 * </ul>
 */
public class UpgradeResult {

    public enum Status { SUCCESS, FAILED, CONFLICT, SKIPPED }

    public Status status;
    public String errorCode;   // 如 SUBTOTAL_MISMATCH
    public String message;
    public BigDecimal diffValue;      // L3 守卫专用：|旧价重算 - li.subtotal|

    /**
     * task-0729 方向3 T2：L3 口径守卫<b>告警</b>码（{@code SUBTOTAL_MISMATCH}）。
     *
     * <p>与 {@link #errorCode} 正交：{@code errorCode} 非空 ⇒ {@link #status} 非 SUCCESS（阻断）；
     * 本字段非空 ⇒ <b>不阻断</b>，{@code status} 仍可为 {@code SUCCESS}，只是顺带检出前后端算值分叉。
     *
     * <p>🔒 <b>本 POJO 是跨事务边界带出告警的载体</b>：dryRun 路径下
     * {@code PriceAdjustBudgetService#runDryRunSnapshot}(REQUIRES_NEW) 会整体回滚，
     * 但本对象是普通 POJO、不随回滚消失，由外层<b>会提交</b>的
     * {@code processMaterial}(REQUIRES_NEW) 事务负责落库。故告警持久化<b>不需要</b>新增任何事务边界
     * （既有的 {@code budgetError} 就是靠同一机制从被回滚的 dryRun 事务里带出来的）。
     */
    public String warnCode;
    public String warnMessage;
    public BigDecimal oldSubtotal;
    public BigDecimal newSubtotal;
    public boolean dryRun;

    /** S2 定位到的价格承载组件预览（调试/验证用，S3 起据此逐个改写）。 */
    public List<PriceBearingComponent> priceBearingComponents;
    /**
     * S1 读到的版本明细条目数（调试/验证用）。repair-0807 D-10：语义是「本版明细元素数」
     * （含 {@code current_price IS NULL} 的无价元素），不是「有价元素数」——
     * {@code loadVersionPrices} 已不再过滤无价元素（见其 javadoc）。
     */
    public int versionPriceCount;

    public static UpgradeResult failed(String errorCode, String message) {
        UpgradeResult r = new UpgradeResult();
        r.status = Status.FAILED;
        r.errorCode = errorCode;
        r.message = message;
        return r;
    }

    public static UpgradeResult conflict(String message) {
        UpgradeResult r = new UpgradeResult();
        r.status = Status.CONFLICT;
        r.message = message;
        return r;
    }

    /** S2 结果：一个「价格承载组件」= 该 line item 卡片里接了取价函数、且角色字段配齐的组件。 */
    public static class PriceBearingComponent {
        public String componentId;
        public String componentCode;
        public String tabName;
        public String elementCodeField;
        public String elementPriceField;
        public String elementCurrencyField; // 可空

        public PriceBearingComponent(String componentId, String componentCode, String tabName,
                                      String elementCodeField, String elementPriceField,
                                      String elementCurrencyField) {
            this.componentId = componentId;
            this.componentCode = componentCode;
            this.tabName = tabName;
            this.elementCodeField = elementCodeField;
            this.elementPriceField = elementPriceField;
            this.elementCurrencyField = elementCurrencyField;
        }
    }
}
