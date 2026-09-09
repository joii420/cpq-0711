package com.cpq.task260907r;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * AC-20 四层用例专属 profile。
 *
 * <p>🔑 <b>把日志级别钉进 profile，不留给命令行</b>：{@code dsrecord} 包 DEBUG ——
 * AC-20③ 要求「断言必须看到『粒度列兜底命中』日志」。
 * 🚨 若把它留给命令行，别人不带那个 flag 跑，日志断言会以「机制没生效」的面目<b>假红</b>。
 * ⇒ 判据依赖的前提，必须由用例自己保证。
 *
 * <h3>🕰️ 2026-09-08 摘掉 {@code record-check.enabled=false}（本类曾因它整类起不来）</h3>
 * 原来钉它的理由是「B-3 的 26 处 ALTER 未落库，开着起不来」。
 * <b>V431 落库之后，这个开关的语义翻转了</b>：
 * <pre>
 *   DatasetSchemaSelfCheck:178  boolean rec = reg.quoteRecordEnabled() &amp;&amp; recordCheckEnabled;
 *   DatasetSchemaSelfCheck:182  if (rec &amp;&amp; s.versioned) mt.put(SOURCE_QUOTATION_COLUMN, "uuid");
 *   DatasetSchemaSelfCheck:191  if (rec)               ht.put(SOURCE_QUOTATION_COLUMN, "uuid");
 * </pre>
 * {@code false} ⇒ {@code rec=false} ⇒ <b>期望列里不加 {@code source_quotation_id}</b>，
 * 而库里 V431 已经加了 ⇒ 启动自检报「多出未声明的列」×26 ⇒ <b>整类 boot 失败</b>
 * （实测：t20d ERROR + t20a/b/c SKIPPED，全部不是业务结论）。
 *
 * <p>🔑 一句话教训：<b>「落库前绕过缺列」的开关，在落库那一刻变成「制造多列」。</b>
 * 迁移落地时必须回头清掉当初为它加的每一处绕过 —— 绕过不会自己失效，它会反过来咬人。
 */
public class AnchorLogProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                // 🚫 这里刻意**不再**设 cpq.dataset.record-check.enabled —— 见类注释：
                //    V431 落库后设成 false 会让启动自检报「多出未声明的列」×26 而整类起不来。
                "quarkus.log.min-level", "DEBUG",
                "quarkus.log.category.\"com.cpq.quotation.service.dsrecord\".level", "DEBUG");
    }
}
