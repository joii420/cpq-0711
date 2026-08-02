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
    public BigDecimal oldSubtotal;
    public BigDecimal newSubtotal;
    public boolean dryRun;

    /** S2 定位到的价格承载组件预览（调试/验证用，S3 起据此逐个改写）。 */
    public List<PriceBearingComponent> priceBearingComponents;
    /** S1 读到的版本价条目数（调试/验证用）。 */
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
