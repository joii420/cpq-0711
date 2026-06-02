package com.cpq.formula;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把百分比字面量 12% / 2.5% 重写为 (12/100.0)。本语法不提供取模，故 % 仅作百分比。 */
public final class PercentLiteral {
    private PercentLiteral() {}
    private static final Pattern P = Pattern.compile("(\\d+(?:\\.\\d+)?)%");

    public static String rewrite(String expr) {
        if (expr == null) return null;
        Matcher m = P.matcher(expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement("(" + m.group(1) + "/100.0)"));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
