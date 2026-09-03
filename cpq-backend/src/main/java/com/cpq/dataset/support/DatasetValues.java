package com.cpq.dataset.support;

import com.cpq.dataset.fingerprint.ValueNormalizer;
import com.cpq.dataset.registry.ColumnDef;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Excel 串 / 前端 JSON 值 → JDBC 绑定值（task-260902 · B-7）。
 *
 * <p>Excel 解析出来的一律是字符串；PG 的 {@code numeric} / {@code integer} / {@code boolean} 列
 * 不能直接绑字符串（报 {@code column is of type numeric but expression is of type character varying}），
 * 所以入库前必须按列类型转型。
 *
 * <p>类型判定<b>以 {@link ColumnDef#pgType} 为准</b>（= 建表类型原文，与 {@code V401~V404} 迁移同源），
 * 不看 {@code type} 这个面向前端的粗分类 —— {@code type=NUMBER} 既可能是 {@code integer} 也可能是
 * {@code numeric(26,12)}，绑错类型 PG 会拒。
 *
 * <p>🚫 <b>这里不做任何舍入</b>：入库定标交给 PG 的列 scale，指纹归一交给
 * {@link ValueNormalizer}（按 {@code ColumnDef.scale}）。在第三个地方再舍一次，
 * 只会让「库里的值」与「指纹用的值」出现第三种口径。
 */
public final class DatasetValues {

    private DatasetValues() {}

    public static boolean isInteger(ColumnDef c) {
        String t = c.pgType;
        return t != null && (t.startsWith("integer") || t.startsWith("bigint") || t.startsWith("smallint"));
    }

    public static boolean isNumeric(ColumnDef c) {
        String t = c.pgType;
        return t != null && t.startsWith("numeric");
    }

    public static boolean isBoolean(ColumnDef c) {
        String t = c.pgType;
        return t != null && t.startsWith("boolean");
    }

    /** varchar(n) 的 n；非字符列返回 null（{@code ColumnDef.maxLength} 已由 Registry 预解析）。 */
    public static Integer maxLength(ColumnDef c) {
        return c.maxLength;
    }

    /** 转成可直接 {@code setParameter} 的值；空（NULL/空串/纯空白）一律落 {@code null}。 */
    public static Object coerce(ColumnDef c, Object raw) {
        if (ValueNormalizer.isBlank(raw)) return null;
        String s = ValueNormalizer.toRawString(raw);
        if (isBoolean(c)) return "true".equals(ValueNormalizer.normalizeBoolean(s));
        if (isInteger(c)) {
            BigInteger bi = ValueNormalizer.parseInteger(s);
            if (bi == null) return null;
            return bi.bitLength() < 31 ? (Object) bi.intValue() : (Object) bi.longValue();
        }
        if (isNumeric(c)) {
            BigDecimal bd = ValueNormalizer.parseDecimal(s);
            return bd;                       // Phase 1 已保证可解析
        }
        return s;                            // 字符列：原串。🚫 绝不截断（AC-40 由 Phase 1 报错拦截）
    }
}
