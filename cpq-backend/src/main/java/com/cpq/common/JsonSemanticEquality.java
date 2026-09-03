package com.cpq.common;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * task-260901 B-1a：两段 JSON 文本的<b>语义</b>相等判定（用于 saveDraft 识别「这一行其实没变」）。
 *
 * <h3>为什么不能用字符串比对</h3>
 * {@code quotation_line_component_data.row_data} / {@code quotation_line_item.product_attribute_values}
 * 是 PostgreSQL <b>jsonb</b> 列。jsonb 入库时会做规范化：对象的键按「UTF-8 字节长度升序，再按字节序」
 * 重排，并统一插入 {@code ": "} / {@code ", "} 分隔空格。而前端来的串是 {@code JSON.stringify} 的
 * 插入序、无空格。两者<b>必然不等</b>，直接 {@code String.equals} 会把每一行都判成「变了」——
 * 2026-09-01 实测（task-260901 B-0）：同一份数据只改键序重发，9225 条里 7380 条文本不同的全部产生
 * 了一条 UPDATE，1845 条文本相同的（内容都是 {@code [{}]}）一条都没有，1:1 对应。
 *
 * <h3>规范形式（canonical form）</h3>
 * 递归重写为：对象键按 {@link String#compareTo} 升序、无多余空白、数字<b>保留源文本字面量</b>。
 *
 * <p>🔒 <b>数字保留字面量而不是转 BigDecimal/double</b>：既避开 Jackson 各版本对
 * {@code DecimalNode.equals}（compareTo 还是 equals）、{@code STRIP_TRAILING_BIGDECIMAL_ZEROES}
 * 的行为差异，也让 {@code 3.30} 与 {@code 3.3} 判为<b>不同</b>——报价场景里末尾零是有效位数信息，
 * 判「同」会让库里悄悄留着旧的 {@code 3.3}。方向上这也更安全：宁可多写一次，不可漏写。
 *
 * <h3>失败方向</h3>
 * 任一侧为 {@code null}、或任一侧解析失败 → 一律返回 {@code false}（＝「已变」，照常写库 + 照常
 * 失效卡片值）。<b>绝不能反过来</b>：判不准就当作变了，代价是一次多余的 UPDATE；判不准却当作没变，
 * 代价是用户的编辑静默丢失。
 */
public final class JsonSemanticEquality {

    private static final JsonFactory FACTORY = new JsonFactory();

    private JsonSemanticEquality() {
    }

    /**
     * @return 两段 JSON 语义是否相同。任一侧 null / 非法 JSON → {@code false}（按「已变」处理）。
     */
    public static boolean equal(String a, String b) {
        if (a == null || b == null) return false;
        // 🔒 先解析再走快路径，不能反过来：两侧同为<b>非法</b> JSON 时文本也相等，
        //    先比字符串会把「解析不了」判成「没变」，违反 fail-closed（本类单测 failClosed 钉死）。
        String ca = canonicalOrNull(a);
        if (ca == null) return false;
        // 快路径：a 已确认可解析，b 与 a 逐字节相同 ⇒ 必然同样可解析且语义相同，省第二次解析。
        if (a.equals(b)) return true;
        String cb = canonicalOrNull(b);
        if (cb == null) return false;
        return ca.equals(cb);
    }

    /** 规范形式；解析失败返回 {@code null}（调用方按「已变」处理）。包内可见供单测直接断言。 */
    public static String canonicalOrNull(String raw) {
        if (raw == null) return null;
        try (JsonParser p = FACTORY.createParser(raw)) {
            if (p.nextToken() == null) return null;   // 空串 / 全空白
            StringBuilder sb = new StringBuilder(raw.length());
            write(p, sb);
            if (p.nextToken() != null) return null;   // 尾部还有内容 → 不是单个合法 JSON 值
            return sb.toString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void write(JsonParser p, StringBuilder sb) throws IOException {
        JsonToken t = p.currentToken();
        if (t == null) throw new IOException("unexpected end of input");
        switch (t) {
            case START_OBJECT -> {
                // TreeMap = 键升序；重复键按「后者胜」，与常规 JSON 解析器一致。
                Map<String, String> fields = new TreeMap<>();
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    String name = p.currentName();
                    p.nextToken();
                    StringBuilder child = new StringBuilder();
                    write(p, child);
                    fields.put(name, child.toString());
                }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, String> e : fields.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    quote(e.getKey(), sb);
                    sb.append(':').append(e.getValue());
                }
                sb.append('}');
            }
            case START_ARRAY -> {
                sb.append('[');
                boolean first = true;
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    if (!first) sb.append(',');
                    first = false;
                    write(p, sb);
                }
                sb.append(']');
            }
            case VALUE_STRING -> quote(p.getText(), sb);
            // 🔒 数字取源文本字面量：保住 scale（"3.30" ≠ "3.3"），且不受 Jackson 数值节点语义影响。
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> sb.append(p.getText());
            case VALUE_TRUE -> sb.append("true");
            case VALUE_FALSE -> sb.append("false");
            case VALUE_NULL -> sb.append("null");
            default -> throw new IOException("unexpected token " + t);
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"').append(JsonStringEncoder.getInstance().quoteAsString(s)).append('"');
    }
}
