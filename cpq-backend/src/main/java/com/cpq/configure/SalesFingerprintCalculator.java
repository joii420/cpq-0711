package com.cpq.configure;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 * <p>组合产品(COMPOSITE): sha256("v1|CUST=custNo|COMBO=childQuotePartNo:qty_sorted|CPROC=defCode_sorted")
 *
 * <p><b>不变量</b>: 入参码值（customerNo/materialCode/elementCode/processCode/childQuotePartNo）
 * 不得含分隔符 {@code | = , : ∅}，否则抛 IllegalArgumentException（fail-fast 防规范串碰撞——
 * 规范串本身用这五个字符分隔 token/字段/集合项，若码值本身含这些字符会产生规范串歧义，
 * 例如工序码 ["a","b,c"] 与 ["a,b","c"] 都会渲染成 "PRC=a,b,c"，造成两个不同选配复用同一
 * 报价料号的静默错价；比生产侧 FingerprintCalculator 更严格是有意为之）。
 */
@ApplicationScoped
public class SalesFingerprintCalculator {

    /**
     * 结构版本号，与 T1 sel_part_signature.structure_version + FingerprintCalculator.VERSION 命名统一.
     *
     * <p><b>task-260902 升 v1 → v2</b>（三层模型，api.md §4）：SIMPLE 串新增 {@code PART=} / {@code WEIGHT=}
     * 两个固定前缀 token，{@code MAT=} 改为「材质码:占比(元素码:含量,…)」多材质形态，{@code ELE=} 删除
     * （元素含量折进 MAT 的括号内按材质分组）。COMPOSITE 串结构未变，仅因共用本常量一并升到 v2。
     * 实测 {@code sel_part_signature} 0 行 ⇒ 升版无存量失配风险。
     */
    public static final String STRUCTURE_VERSION = "v2";

    private static final String SENTINEL_EMPTY = "∅";

    /** 元素码 + 含量 — ELEMENT 类型启用参数的组成项. */
    public record ElementPct(String elementCode, BigDecimal pct) {}

    /**
     * task-260902（v2）：一个材质在指纹里的完整投影 —— 材质码 + 占比 + 该材质的元素含量。
     *
     * <p>🚫 <b>不含 configNo</b>：AC-10 裁决「配方编号不进指纹，按含量内容判同」——
     * 含量逐字相同的两条配置（乃至一条标准配方 vs 一份自定义含量，AC-22③）必须复用同一料号。
     */
    public record MaterialPct(String materialCode, BigDecimal ratio, List<ElementPct> elements) {}

    /**
     * 启用参数投影 —— 由 T3 运行时按本次使用模板的 enabled 参数 + 选值构造.
     *
     * <p>每 paramTypeCode 至多一项（单槽位）——同一 enabled 列表内不应出现两个
     * MATERIAL / 两个 ELEMENT / 两个 PROCESS 项，否则 renderToken 按 paramTypeCode
     * 排序后会产生多个同名 token，破坏"每类型一个 token"的规范串结构。
     *
     * @param paramTypeCode MATERIAL / ELEMENT / PROCESS（sel_param_type.code）
     * @param materialCode  MATERIAL: recipe/配比码；否则 null
     * @param elements      ELEMENT: 元素码+含量；否则 null
     * @param processCodes  PROCESS: 工序码集合（无序）；否则 null
     */
    public record EnabledParam(String paramTypeCode, String materialCode,
                                List<ElementPct> elements, List<String> processCodes,
                                List<MaterialPct> materials) {
        /** v1 形态的 4 参构造（materials 留空）；PROCESS 槽位仍用它。 */
        public EnabledParam(String paramTypeCode, String materialCode,
                            List<ElementPct> elements, List<String> processCodes) {
            this(paramTypeCode, materialCode, elements, processCodes, null);
        }

        /** task-260902（v2）：MATERIAL 槽位的多材质构造。 */
        public static EnabledParam material(List<MaterialPct> materials) {
            return new EnabledParam("MATERIAL", null, null, null, materials);
        }
    }

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
        return computeSimple(customerNo, null, null, null, null, enabled);
    }

    /**
     * task-260902（v2，api.md §4.1）：带零件层信息的 SIMPLE 指纹。
     *
     * <pre>
     * v2|CUST=&lt;客户码&gt;|PART=&lt;len&gt;:&lt;品名&gt;&lt;len&gt;:&lt;规格&gt;&lt;len&gt;:&lt;尺寸&gt;|WEIGHT=&lt;总重&gt;|MAT=…|PRC=…
     * </pre>
     *
     * <p><b>为什么 {@code PART=} 用长度前缀而不是 {@code /} 分隔</b>（评审 P1-7，api.md §4.3）：
     * 实查 {@code material_recipe.symbol} 含 {@code /} 的有 74 条（{@code AgZnO12/Cu}…），品名含
     * {@code /} 的料号也真实存在（{@code AgNi10/Cu触点}）⇒ 本业务文本带 {@code /} 是常态。
     * 用 {@code /} 分隔时「品名 A/B + 规格 C」与「品名 A + 规格 B/C」渲染出同一个 {@code PART=}
     * → 同一料号 → <b>静默错价</b>（与本类类注释里那个 {@code PRC=a,b,c} 事故同型）。
     * 长度前缀编码在任何字符集下都无歧义。
     *
     * <p>{@code PART=} / {@code WEIGHT=} <b>不走 {@code sel_param_type} 槽位机制</b>（api.md §4.4）：
     * 那套是封闭枚举（实测恰好 3 行 + {@code renderToken} 的 {@code switch} 硬匹配），新增成员会连带
     * 迁移种子 / 候选服务 / 选配模板管理页；本任务定为由本方法显式接参拼接的固定前缀 token。
     *
     * @param partName   零件品名（可空 → 空串）
     * @param spec       规格（可空 → 空串）
     * @param dimension  尺寸（可空 → 空串）
     * @param unitWeight 零件总重（可空 → "0"）
     */
    public Signature computeSimple(String customerNo, String partName, String spec, String dimension,
                                    BigDecimal unitWeight, List<EnabledParam> enabled) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new IllegalArgumentException("computeSimple: customerNo 不能为空");
        }
        if (enabled == null || enabled.isEmpty()) {
            throw new IllegalArgumentException("computeSimple: enabled 参数集不能为空（防指纹坍缩）");
        }
        assertNoDelimiter(customerNo, "customerNo");
        assertNoDelimiter(partName, "partName");
        assertNoDelimiter(spec, "spec");
        assertNoDelimiter(dimension, "dimension");

        String tokens = enabled.stream()
            .sorted(Comparator.comparing(EnabledParam::paramTypeCode))
            .map(this::renderToken)
            .collect(Collectors.joining("|"));

        String text = STRUCTURE_VERSION + "|CUST=" + customerNo
            + "|PART=" + lenPrefixed(partName) + lenPrefixed(spec) + lenPrefixed(dimension)
            + "|WEIGHT=" + normalize(unitWeight)
            + "|" + tokens;
        return new Signature(sha256(text), text);
    }

    /** 长度前缀编码：{@code <字符数>:<内容>}；null 视为空串（{@code 0:}）。 */
    private String lenPrefixed(String v) {
        String safe = (v == null) ? "" : v;
        return safe.length() + ":" + safe;
    }

    /**
     * 计算组合产品(COMPOSITE)的客户维度指纹.
     *
     * <p>纳入子件装配用量(childQtys)与组合工艺(compositeProcessCodes)两个维度：同客户、同子件集，
     * 但装配用量或组合工序不同即视为不同产品，须产生不同指纹 —— 否则命中复用会在父级落库前
     * 短路跳过，静默丢弃新 qty/工序（见 T5 code review Important #1：错价风险）。
     *
     * @param customerNo             客户编号，不可空白
     * @param childQuotePartNos      子件报价料号集合，不可空（顺序无关）
     * @param childQtys              与 childQuotePartNos 平行、同下标的装配数量；元素为 null 或 &lt;1
     *                                兜底为 1；本参数整体为 null，或长度短于 childQuotePartNos 时，
     *                                缺失下标同样兜底为 1（容错，不抛异常）
     * @param compositeProcessCodes  组合工艺 defCode 集合（无序）；null/空 → CPROC=∅
     */
    public Signature computeComposite(String customerNo, List<String> childQuotePartNos,
                                       List<Integer> childQtys, List<String> compositeProcessCodes) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new IllegalArgumentException("computeComposite: customerNo 不能为空");
        }
        if (childQuotePartNos == null || childQuotePartNos.isEmpty()) {
            throw new IllegalArgumentException("computeComposite: childQuotePartNos 不能为空");
        }
        assertNoDelimiter(customerNo, "customerNo");
        childQuotePartNos.forEach(p -> assertNoDelimiter(p, "childQuotePartNo"));

        String sortedPairs = IntStream.range(0, childQuotePartNos.size())
            .mapToObj(i -> {
                String partNo = childQuotePartNos.get(i);
                Integer qty = (childQtys != null && i < childQtys.size()) ? childQtys.get(i) : null;
                int safeQty = (qty == null || qty < 1) ? 1 : qty;
                return partNo + ":" + safeQty;
            })
            .sorted()
            .collect(Collectors.joining(","));

        String cprocToken;
        if (compositeProcessCodes == null || compositeProcessCodes.isEmpty()) {
            cprocToken = SENTINEL_EMPTY;
        } else {
            compositeProcessCodes.forEach(c -> assertNoDelimiter(c, "compositeProcessCode"));
            cprocToken = compositeProcessCodes.stream().sorted().collect(Collectors.joining(","));
        }

        String text = STRUCTURE_VERSION + "|CUST=" + customerNo + "|COMBO=" + sortedPairs
            + "|CPROC=" + cprocToken;
        return new Signature(sha256(text), text);
    }

    private String renderToken(EnabledParam param) {
        switch (param.paramTypeCode()) {
            case "MATERIAL": {
                // task-260902（v2）：多材质形态 —— MAT=<材质码:占比(元素码:含量,…)>,… 按材质码排序。
                List<MaterialPct> materials = param.materials();
                if (materials != null && !materials.isEmpty()) {
                    String rendered = materials.stream()
                        .sorted(Comparator.comparing(MaterialPct::materialCode))
                        .map(m -> {
                            assertNoDelimiter(m.materialCode(), "materialCode");
                            List<ElementPct> els = m.elements() == null ? List.of() : m.elements();
                            String inner = els.stream()
                                .sorted(Comparator.comparing(ElementPct::elementCode))
                                .map(e -> {
                                    assertNoDelimiter(e.elementCode(), "elementCode");
                                    return e.elementCode() + ":" + normalize(e.pct());
                                })
                                .collect(Collectors.joining(","));
                            return m.materialCode() + ":" + normalize(m.ratio()) + "(" + inner + ")";
                        })
                        .collect(Collectors.joining(","));
                    return "MAT=" + rendered;
                }
                // v1 兼容分支（单值 materialCode）；本任务的投影层不再走这里。
                String materialCode = param.materialCode();
                if (materialCode == null || materialCode.isBlank()) {
                    return "MAT=" + SENTINEL_EMPTY;
                }
                assertNoDelimiter(materialCode, "materialCode");
                return "MAT=" + materialCode;
            }
            // v1 遗留：v2 起 ELEMENT 不再作为槽位投影（元素含量折进 MAT 的括号内按材质分组，
            // api.md §4.5）。分支保留是为了任何残留调用方不至于撞 default 抛异常。
            case "ELEMENT": {
                List<ElementPct> elements = param.elements();
                if (elements == null || elements.isEmpty()) {
                    return "ELE=" + SENTINEL_EMPTY;
                }
                String sortedElems = elements.stream()
                    .sorted(Comparator.comparing(ElementPct::elementCode))
                    .map(e -> {
                        assertNoDelimiter(e.elementCode(), "elementCode");
                        return e.elementCode() + ":" + normalize(e.pct());
                    })
                    .collect(Collectors.joining(","));
                return "ELE=" + sortedElems;
            }
            case "PROCESS": {
                List<String> processCodes = param.processCodes();
                if (processCodes == null || processCodes.isEmpty()) {
                    return "PRC=" + SENTINEL_EMPTY;
                }
                processCodes.forEach(p -> assertNoDelimiter(p, "processCode"));
                // 🚨 task-260902 AC-19/AC-20：本行**一个字都不许改**。
                //   · sorted() ⇒ 工序顺序不进指纹（换个次序仍是同一个产品，A0 裁决）；
                //   · 🚫 绝不可加 distinct() ⇒ sort 不去重正是「焊两次 ≠ 焊一次」的依据
                //     （["Z100","Z101","Z100"].sort() = Z100,Z100,Z101 ≠ Z100,Z101 ⇒ 必铸新料号）。
                String sortedProcs = processCodes.stream().sorted().collect(Collectors.joining(","));
                return "PRC=" + sortedProcs;
            }
            default:
                throw new IllegalArgumentException("未知 paramTypeCode=" + param.paramTypeCode());
        }
    }

    /**
     * 规范串分隔符碰撞守卫: 规范串使用 {@code | = , : ∅} 五个字符分隔 token/字段/集合项，
     * 若入参码值本身含这些字符会产生规范串歧义（不同载荷渲染出相同规范串 → 指纹碰撞 →
     * 静默错价）。fail-fast 优于静默撞串。
     */
    private void assertNoDelimiter(String value, String fieldName) {
        if (value == null) return;
        for (char c : new char[]{'|', '=', ',', ':', '∅'}) {
            if (value.indexOf(c) >= 0) {
                throw new IllegalArgumentException(
                    fieldName + " 不能包含分隔符 '" + c + "'（规范串碰撞风险）: " + value);
            }
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
