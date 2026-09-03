package com.cpq.configure.rules;

import java.math.BigInteger;
import java.util.Collection;
import java.util.regex.Pattern;

/**
 * 材质模块的三个发号器（task-260901 · B-5 / B-6 / B-7）—— <b>纯函数，不碰库</b>。
 *
 * <p>把「怎么算下一个号」与「怎么查现有号」拆开，是为了让 T-U-01/02/03 三组单测能在纯内存里
 * 覆盖全部边界（扩位 / 脏值过滤 / 不回收），不依赖共享库状态。
 */
public final class MaterialRecipeNumbering {

    /** 材质编号自增只认五位补零的（D8）——脏值 '992' 不是五位，天然排除。 */
    public static final Pattern RECIPE_CODE_5 = Pattern.compile("^[0-9]{5}$");

    /** 元素编号自增只认纯数字的——主表存在脏行 element_no='白银'，正则过滤不可省，否则 ::bigint 抛异常。 */
    public static final Pattern PURE_DIGITS = Pattern.compile("^[0-9]+$");

    /** 库内一个五位编号都没有时的起点。 */
    static final long RECIPE_CODE_START = 1L;

    /** 元素主表一个纯数字编号都没有时的起点（现役编号段 10001~10095）。 */
    static final BigInteger ELEMENT_NO_START = BigInteger.valueOf(10001L);

    private MaterialRecipeNumbering() {}

    // ── B-5：配置编号（M-1 / M-2）────────────────────────────────

    /**
     * 配置编号格式化：{@code <材质编号>-<两位补零序号>}。
     *
     * <p>🚫 <b>禁止用 PG {@code lpad(x,2,'0')}</b> —— 它把 '100' 截成 '10'。
     * Java {@code %02d} 是「至少两位」，seq≥100 时自然给出三位（AC-26）。
     */
    public static String formatConfigNo(String recipeCode, int seq) {
        if (recipeCode == null || recipeCode.isBlank()) {
            throw new IllegalArgumentException("recipeCode 必填");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("配置序号必须 ≥ 1: " + seq);
        }
        return recipeCode.trim() + "-" + String.format("%02d", seq);
    }

    /**
     * 下一个配置序号 = max(该材质<b>全部</b>配置的 seq，<b>含 INACTIVE</b>) + 1。
     *
     * <p>「含 INACTIVE」是编号不回收（M-2 / AC-15）的唯一保证：删掉 {@code -02} 后再建拿到的是
     * {@code -03}，不是被回收的 {@code -02}。
     */
    public static int nextConfigSeq(Collection<Integer> existingSeqsIncludingInactive) {
        int max = 0;
        if (existingSeqsIncludingInactive != null) {
            for (Integer s : existingSeqsIncludingInactive) {
                if (s != null && s > max) max = s;
            }
        }
        return max + 1;
    }

    // ── B-6：材质编号（AC-3 / AC-4）─────────────────────────────

    /**
     * 下一个材质编号：只统计 {@code ^[0-9]{5}$} 的 code 求 max + 1，格式化为五位补零。
     * 脏值 '992'（非五位）被排除，故 '00993' 与 '992' 永不撞键。
     */
    public static String nextRecipeCode(Collection<String> existingCodes) {
        long max = 0L;
        if (existingCodes != null) {
            for (String c : existingCodes) {
                if (c == null) continue;
                String t = c.trim();
                if (!RECIPE_CODE_5.matcher(t).matches()) continue;
                long v = Long.parseLong(t);
                if (v > max) max = v;
            }
        }
        long next = max == 0L ? RECIPE_CODE_START : max + 1;
        return String.format("%05d", next);
    }

    // ── B-7：元素编号（AC-6）────────────────────────────────────

    /**
     * 下一个元素编号：只统计 {@code ^[0-9]+$} 的 element_no 求 max + 1。
     * ⚠️ 主表存在脏行 {@code element_no='白银'}，<b>正则过滤不可省</b>。
     */
    public static String nextElementNo(Collection<String> existingElementNos) {
        BigInteger max = null;
        if (existingElementNos != null) {
            for (String n : existingElementNos) {
                if (n == null) continue;
                String t = n.trim();
                if (!PURE_DIGITS.matcher(t).matches()) continue;
                BigInteger v = new BigInteger(t);
                if (max == null || v.compareTo(max) > 0) max = v;
            }
        }
        return max == null ? ELEMENT_NO_START.toString() : max.add(BigInteger.ONE).toString();
    }
}
