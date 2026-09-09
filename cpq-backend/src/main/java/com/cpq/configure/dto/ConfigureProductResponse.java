package com.cpq.configure.dto;

import java.util.List;
import java.util.Map;

public class ConfigureProductResponse {
    public List<Map<String, Object>> lineItems;    // SIMPLE: 1 行;COMPOSITE: 1 父 + N 子
    public boolean fingerprintMatched;             // 至少 1 个料号是复用
    public List<String> reusedHfPartNos;           // 哪些料号是复用的
    /**
     * B2.3: 后端按 Σqty 兜底裁决后的有效 productType（"SIMPLE"/"COMPOSITE"），
     * 可能与请求里的 req.productType 不同（如单行 qty>=2 请求声明 SIMPLE 也会被裁成 COMPOSITE）。
     */
    public String productType;

    /**
     * task-260902 · B-11（api.md §1.3，AC-7 状态 C）：命中复用时带出的销售产品信息；
     * 未命中为 null。
     */
    public ReusedProductInfoDTO reusedProductInfo;

    /**
     * task-260902 · B-11：本次指纹的结构版本（{@code SalesFingerprintCalculator.STRUCTURE_VERSION}），
     * 便于前端与排查时对账「这个料号是用哪一版指纹口径算出来的」。
     */
    public String structureVersion;

    /**
     * repair-260908 B-8（AC-18）：本次刷新中<b>行键对不上、因而被丢弃的 {@code editRows} 条数</b>。
     *
     * <p>缺陷① 修好后行键必然变化（跨客户重复行消失 ⇒ {@code #N} 消歧后缀消失），
     * 而 {@code CardSnapshotService#filterEditRowsToNewBaseRows} 对匹配不上的 editRows 是
     * <b>直接丢弃</b>：不记日志、不计数、不报错、HTTP 200 —— 用户手填的值就这么没了。
     *
     * <p>🚫 <b>判据不是「刷新成功」</b>（那是恒真信号）：要看的就是这个数字和下面的明细。
     * 🚫 也**不做**按内容迁移（D-14）—— {@code #0}/{@code #1} 是两个客户的两行，
     * 合并时保留哪一条没有唯一正确答案，猜错 = 把别家客户的编辑值写进本家。
     *
     * <p>仅 {@code refresh-snapshot} 端点填充；其余端点为 {@code null}（加法式，存量调用方零影响）。
     */
    public Integer editRowMismatchCount;

    /**
     * 失配明细（{@code componentId} / {@code rowKey} / 原值摘要），最多 200 条。
     * {@code editRowMismatchTruncated=true} 表示条数多于明细数，以 {@link #editRowMismatchCount} 为准。
     */
    public java.util.List<java.util.Map<String, Object>> editRowMismatches;

    /** 明细是否被截断（总数 &gt; 明细条数）。 */
    public Boolean editRowMismatchTruncated;
}
