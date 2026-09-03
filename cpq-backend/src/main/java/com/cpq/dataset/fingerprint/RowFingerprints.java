package com.cpq.dataset.fingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 行指纹算法本体（task-260902 · 需求文档 R-3 · B-4 · AC-16/17/18）。
 *
 * <pre>
 * row_fingerprint = SHA-256( 各「对比项」列的规范化值，按 Registry 声明顺序，用 0x1F 连接 ) → 64 位小写 hex
 * </pre>
 *
 * <p>纯静态、无依赖，便于单测。<b>只有</b> {@code compared=true} 的列参与（调用方负责过滤）——
 * 典型是「项次」建字段但不参与指纹，故改项次不升版（AC-17）。
 */
public final class RowFingerprints {

    /** 字段分隔符 0x1F（ASCII Unit Separator）：业务值不可能包含，避免 "ab"+"c" 与 "a"+"bc" 撞串。 */
    public static final char SEPARATOR = 0x1F;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RowFingerprints() {
    }

    /**
     * 按列定义计算一行的指纹。
     *
     * @param comparedColumns 参与比对的列，<b>顺序即建表字段顺序</b>（Registry 声明顺序）
     * @param row             行数据（key = DB 列名）
     */
    public static String compute(List<FpColumn> comparedColumns, Map<String, Object> row) {
        List<String> parts = new ArrayList<>(comparedColumns.size());
        for (FpColumn c : comparedColumns) {
            Object raw = row == null ? null : row.get(c.name());
            parts.add(ValueNormalizer.normalize(raw, c.type(), c.scale()));
        }
        return hashNormalized(parts);
    }

    /** 对已规范化的值序列求 SHA-256（64 位小写 hex）。 */
    public static String hashNormalized(List<String> normalizedValues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalizedValues.size(); i++) {
            if (i > 0) sb.append(SEPARATOR);
            String v = normalizedValues.get(i);
            sb.append(v == null ? ValueNormalizer.EMPTY : v);
        }
        return sha256Hex(sb.toString());
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int b = digest[i] & 0xFF;
                out[i * 2] = HEX[b >>> 4];
                out[i * 2 + 1] = HEX[b & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);   // JDK 必带，不可能发生
        }
    }

    /**
     * 指纹<b>多重集</b>比较（R-4 · AC-16）：行序对调不算变更，但重复次数必须一致。
     * <p>🚫 严禁改成按下标逐行比 —— 那会让「两行对调顺序」误判为升版。
     */
    public static boolean sameMultiset(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (String s : a) counts.merge(s, 1, Integer::sum);
        for (String s : b) {
            Integer n = counts.get(s);
            if (n == null) return false;
            if (n == 1) counts.remove(s); else counts.put(s, n - 1);
        }
        return counts.isEmpty();
    }
}
