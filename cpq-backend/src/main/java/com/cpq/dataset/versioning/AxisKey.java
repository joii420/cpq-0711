package com.cpq.dataset.versioning;

import com.cpq.dataset.registry.SheetDef;

/**
 * task-260907 · B-2 —— 版本化写入的<b>复合轴</b>键：{@code (customer_no, 轴值)}。
 *
 * <h3>为什么把它做成一个类型，而不是再加一个 {@code String customerNo} 参数</h3>
 * 判据必须落在<b>签名层</b>：调用方<b>无法构造</b>一个 {@code AxisKey} 而不对客户号表态。
 * 原来的形态是 {@code Map<String, List<Map<String,Object>>>}，新增调用方照着写一行就能编过，
 * 客户维度悄悄丢失 —— 而这个失败<b>不报错、不撞键、不留痕</b>：
 * {@code VersionedGroupWriter} 的隔离靠「整组删除 + 重插」，轴少一维 = 客户 A 导入料号 X 时
 * 把客户 B 的料号 X 整组删掉。
 *
 * <p>📌 立项文档记账：主线「找 writer 调用方」这件事连错三次，错法各不相同
 * （漏查使用引用 / 漏查未来调用方 / 把锁键工具当成写入方）⇒ <b>点名清单这个形式本身不可靠</b>，
 * 所以判据下沉到这里。
 *
 * @param customerNo 客户编号（{@code customer.code}，如 {@code CUST-0001}）。
 *                   <b>报价侧必填</b>；核价两套（无客户维度）必须传 {@code null}。
 * @param axisValue  原轴值：报价 = 销售料号，核价两套 = 生产料号。
 */
public record AxisKey(String customerNo, String axisValue) {

    /** 核价两套（无客户维度）的轴键。🚫 报价侧调用它会在写入器里被拒（不允许静默写 NULL）。 */
    public static AxisKey ofAxisOnly(String axisValue) {
        return new AxisKey(null, axisValue);
    }

    /**
     * 按 sheet 的客户维度开关构造：报价侧带客户号，核价两套自动丢弃客户号。
     *
     * <p>给「同一段代码同时服务三套数据集」的调用方用（如通用导入器）——
     * 它拿得到客户号，但不该自己判断这套数据集要不要客户维度。
     */
    public static AxisKey of(SheetDef sheet, String customerNo, String axisValue) {
        return new AxisKey(sheet != null && sheet.customerScoped() ? customerNo : null, axisValue);
    }

    /** 是否带客户维度。 */
    public boolean scoped() {
        return customerNo != null && !customerNo.isBlank();
    }

    @Override
    public String toString() {
        return customerNo == null ? String.valueOf(axisValue) : customerNo + "/" + axisValue;
    }
}
