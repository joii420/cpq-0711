package com.cpq.task260907;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AC-15（D-32）判据①「结构层」的量具：把一份 JSON 压成<b>与数据无关的结构骨架</b>。
 *
 * <p>骨架 = 所有<b>键路径 + 该路径上的 JSON 类型</b>，数组下标一律折叠成 {@code []}。
 * 例：{@code data.items[].materialName:STRING}。
 *
 * <h3>为什么要这么切</h3>
 * D-32 之前 AC-15 的判据是「逐字一致」。实测发现 {@code parts} / {@code rows} 返回的是
 * <b>共享库的活数据</b> —— 任何并发会话写一行 {@code ds_quote_*}，逐字 diff 就红，
 * 而那<b>不是回归</b>。⇒ 判据拆成两层：
 * <ul>
 *   <li><b>①结构层</b>（本类）：字段集与嵌套结构。<b>这层才是「有没有被本任务连累」的真判据</b>，
 *       且它对数据漂移天然免疫</li>
 *   <li><b>②数据层</b>：值差异必须能逐条归因，来路不明即失败（见测试类）</li>
 * </ul>
 *
 * <h3>⚠️ 一个刻意的取舍</h3>
 * {@code null} 值拿不到真实类型，统一记成 {@code NULL}。所以「某字段本轮恰好全 null」
 * 会与「A 侧该字段有值」产生一条结构差异 —— <b>这是有意的保守</b>：
 * 宁可多报一条让人去归因，也不要漏掉「字段被删了」。
 */
final class JsonShape {

    private static final ObjectMapper M = new ObjectMapper();

    private JsonShape() {
    }

    /** 抽结构骨架，返回排序稳定的「路径:类型」集合。 */
    static Set<String> of(String json) {
        Set<String> out = new LinkedHashSet<>();
        try {
            walk(M.readTree(json), "", out);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON（先看是不是 401/500 的 HTML 或纯文本）："
                    + json.substring(0, Math.min(300, json.length())), e);
        }
        return out;
    }

    private static void walk(JsonNode n, String path, Set<String> out) {
        if (n == null || n.isNull()) {
            out.add(path + ":NULL");
            return;
        }
        if (n.isObject()) {
            n.fieldNames().forEachRemaining(f -> walk(n.get(f), path.isEmpty() ? f : path + "." + f, out));
            if (n.isEmpty()) {
                out.add(path + ":OBJECT{}");
            }
            return;
        }
        if (n.isArray()) {
            if (n.isEmpty()) {
                out.add(path + "[]:EMPTY");
                return;
            }
            // 数组内所有元素的骨架取并集 —— 只要有一个元素少了字段，也要暴露出来
            n.forEach(e -> walk(e, path + "[]", out));
            return;
        }
        out.add(path + ":" + n.getNodeType());
    }
}
