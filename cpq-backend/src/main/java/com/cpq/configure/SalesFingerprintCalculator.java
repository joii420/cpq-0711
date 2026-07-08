package com.cpq.configure;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 选配 Plan 3b — 销售侧客户维度指纹计算器 (T2).
 *
 * <p>与现役生产侧 {@link FingerprintCalculator} 并列、互不影响: 生产侧是全局指纹
 * (同配置全客户复用同一 hf_part_no)，本类是**客户维度**指纹 (同配置不同客户各自
 * 拥有独立的报价料号)，用于判断 T1 落地的 {@code sel_part_signature}
 * (customer_no, structure_version, config_fingerprint) 是否可复用。
 *
 * <p>normalize + sha256 逐字复刻 {@link FingerprintCalculator} 的口径，保证两侧
 * 去尾零 / 哈希编码规范统一，仅 token 拼接结构因维度不同而不同。
 *
 * <p>独立产品(SIMPLE): sha256("v1|CUST=custNo|ELE=...|MAT=...|PRC=...")
 * (token 按 paramTypeCode 升序排列)
 * <p>组合产品(COMPOSITE): sha256("v1|CUST=custNo|COMBO=childQuotePartNo_sorted")
 */
@ApplicationScoped
public class SalesFingerprintCalculator {

    /** 结构版本号，与 T1 sel_part_signature.structure_version + FingerprintCalculator.VERSION 命名统一. */
    public static final String STRUCTURE_VERSION = "v1";

    private static final String SENTINEL_EMPTY = "∅";

    /** 元素码 + 含量 — ELEMENT 类型启用参数的组成项. */
    public record ElementPct(String elementCode, BigDecimal pct) {}

    /**
     * 启用参数投影 —— 由 T3 运行时按本次使用模板的 enabled 参数 + 选值构造.
     *
     * @param paramTypeCode MATERIAL / ELEMENT / PROCESS（sel_param_type.code）
     * @param materialCode  MATERIAL: recipe/配比码；否则 null
     * @param elements      ELEMENT: 元素码+含量；否则 null
     * @param processCodes  PROCESS: 工序码集合（无序）；否则 null
     */
    public record EnabledParam(String paramTypeCode, String materialCode,
                                List<ElementPct> elements, List<String> processCodes) {}

    /** 计算结果: hash（落库/比对用）+ text（可读原文，便于调试与审计）. */
    public record Signature(String hash, String text) {}

    /**
     * 计算独立产品(SIMPLE)的客户维度指纹.
     *
     * <p>防坍缩守卫: enabled 为空会导致该客户所有选配坍缩成同一指纹 —— 真正防线在
     * T3 投影层（MATERIAL+ELEMENT 恒为槽位 + custom 模式强制非空），本方法仅做算法层兜底。
     *
     * @param customerNo 客户编号，不可空白
     * @param enabled    启用参数集，不可空/null（防指纹坍缩）
     */
    public Signature computeSimple(String customerNo, List<EnabledParam> enabled) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new IllegalArgumentException("computeSimple: customerNo 不能为空");
        }
        if (enabled == null || enabled.isEmpty()) {
            throw new IllegalArgumentException("computeSimple: enabled 参数集不能为空（防指纹坍缩）");
        }

        String tokens = enabled.stream()
            .sorted(Comparator.comparing(EnabledParam::paramTypeCode))
            .map(this::renderToken)
            .collect(Collectors.joining("|"));

        String text = STRUCTURE_VERSION + "|CUST=" + customerNo + "|" + tokens;
        return new Signature(sha256(text), text);
    }

    /**
     * 计算组合产品(COMPOSITE)的客户维度指纹.
     *
     * <p>组合工艺维度暂不纳入指纹（§2.7 待定，本 Task 不做）.
     * TODO(sel-plan3): 组合产品的独立工艺参数（如整机装配工序）如需纳入客户维度指纹，
     * 需在此追加 PRC= 类似的排序 token，当前先与子件指纹一致地仅按子件报价料号聚合。
     *
     * @param customerNo         客户编号，不可空白
     * @param childQuotePartNos  子件报价料号集合，不可空（顺序无关）
     */
    public Signature computeComposite(String customerNo, List<String> childQuotePartNos) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new IllegalArgumentException("computeComposite: customerNo 不能为空");
        }
        if (childQuotePartNos == null || childQuotePartNos.isEmpty()) {
            throw new IllegalArgumentException("computeComposite: childQuotePartNos 不能为空");
        }

        String sorted = childQuotePartNos.stream().sorted().collect(Collectors.joining(","));
        String text = STRUCTURE_VERSION + "|CUST=" + customerNo + "|COMBO=" + sorted;
        return new Signature(sha256(text), text);
    }

    private String renderToken(EnabledParam param) {
        switch (param.paramTypeCode()) {
            case "MATERIAL": {
                String materialCode = param.materialCode();
                return "MAT=" + ((materialCode == null || materialCode.isBlank()) ? SENTINEL_EMPTY : materialCode);
            }
            case "ELEMENT": {
                List<ElementPct> elements = param.elements();
                if (elements == null || elements.isEmpty()) {
                    return "ELE=" + SENTINEL_EMPTY;
                }
                String sortedElems = elements.stream()
                    .sorted(Comparator.comparing(ElementPct::elementCode))
                    .map(e -> e.elementCode() + ":" + normalize(e.pct()))
                    .collect(Collectors.joining(","));
                return "ELE=" + sortedElems;
            }
            case "PROCESS": {
                List<String> processCodes = param.processCodes();
                if (processCodes == null || processCodes.isEmpty()) {
                    return "PRC=" + SENTINEL_EMPTY;
                }
                String sortedProcs = processCodes.stream().sorted().collect(Collectors.joining(","));
                return "PRC=" + sortedProcs;
            }
            default:
                throw new IllegalArgumentException("computeSimple: 未知 paramTypeCode=" + param.paramTypeCode());
        }
    }

    /** BigDecimal 规范化: stripTrailingZeros 防 '12' vs '12.0' 误判. 与 FingerprintCalculator 口径一致. */
    private String normalize(BigDecimal val) {
        if (val == null) return "0";
        return val.stripTrailingZeros().toPlainString();
    }

    /** SHA-256 → 小写 64 位 hex. 与 FingerprintCalculator 口径一致. */
    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
