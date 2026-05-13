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
 * 配置指纹计算器 (F2 算法).
 *
 * <p>独立产品(SIMPLE): sha256("v1|SIMPLE|recipe_code|element_sorted=...")
 * <p>组合产品(COMPOSITE): sha256("v1|COMBO|child_part_no_sorted=...")
 *
 * <p>BigDecimal 规范化通过 stripTrailingZeros 防止 '12' vs '12.0' 误判不等.
 */
@ApplicationScoped
public class FingerprintCalculator {

    /** 指纹算法版本号. 未来算法升级时切 v2,与旧 v1 指纹共存. */
    public static final String VERSION = "v1";

    /** 元素输入 — 用于独立产品指纹计算. */
    public static class ElementInput {
        public String elementCode;
        public BigDecimal pct;

        public ElementInput() {}
        public ElementInput(String elementCode, BigDecimal pct) {
            this.elementCode = elementCode;
            this.pct = pct;
        }
    }

    /**
     * 计算独立产品指纹.
     * @param recipeCode material_recipe.code (如 "AgNi90")
     * @param elements 元素覆盖列表(顺序无关,内部排序)
     * @return sha256 hex (64 字符)
     */
    public String simpleFingerprint(String recipeCode, List<ElementInput> elements) {
        if (recipeCode == null || recipeCode.isBlank()) {
            throw new IllegalArgumentException("recipeCode required");
        }
        if (elements == null || elements.isEmpty()) {
            throw new IllegalArgumentException("elements required");
        }
        String sortedElems = elements.stream()
            .sorted(Comparator.comparing(e -> e.elementCode))
            .map(e -> e.elementCode + "=" + normalize(e.pct))
            .collect(Collectors.joining(","));
        String input = VERSION + "|SIMPLE|" + recipeCode + "|" + sortedElems;
        return sha256(input);
    }

    /**
     * 计算组合产品指纹.
     * @param childHfPartNos 子配件 hf_part_no 列表(顺序无关,内部排序)
     * @return sha256 hex
     */
    public String compositeFingerprint(List<String> childHfPartNos) {
        if (childHfPartNos == null || childHfPartNos.size() < 2) {
            throw new IllegalArgumentException("childHfPartNos required (size >= 2)");
        }
        String sorted = childHfPartNos.stream().sorted().collect(Collectors.joining(","));
        String input = VERSION + "|COMBO|" + sorted;
        return sha256(input);
    }

    /** BigDecimal 规范化: stripTrailingZeros 防 '12' vs '12.0' 误判. */
    private String normalize(BigDecimal val) {
        if (val == null) return "0";
        return val.stripTrailingZeros().toPlainString();
    }

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
