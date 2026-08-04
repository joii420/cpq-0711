package com.cpq.basicdata.v6.quote;

import com.cpq.basicdata.v6.parser.SheetImportResult;
import com.cpq.basicdata.v6.parser.SheetRow;
import com.cpq.basicdata.v6.versioning.VersionedGroupSpec;
import com.cpq.basicdata.v6.versioning.VersionedV6Writer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * repair-0804：「来料年降」「组装加工费年降」「年降系数」三个 Sheet 共用的落库写入器。
 *
 * <p>三者业务同构，差异只有两点，由各自 Handler 声明：
 * <ul>
 *   <li>{@code discount_type}：INCOMING_MATERIAL / ASSEMBLY_PROCESS / FINISHED</li>
 *   <li>{@code target_no}：投入料号（材质料号） / 解析后的工序编号 / null</li>
 * </ul>
 * 其余（读 content 七列、组装 groupKey、组级版本化写入、pending 归属）全在本类。
 *
 * <p><b>为什么一套读列 key 能吃三个 Sheet</b>：{@link SheetRow#getStr} 是 contains 匹配，
 * 故 {@code "年降系数"} 同时命中「年降系数（%）」与「年降系数（%/年）」，
 * {@code "单次固定年降"} 同时命中「单次固定年降值」与「单次固定年降金额」。
 */
@ApplicationScoped
public class AnnualDiscountWriter {

    @Inject VersionedV6Writer writer;

    @ConfigProperty(name = "cpq.v6import-setbased-writer", defaultValue = "false")
    boolean setBased;

    public static final String TABLE = "annual_discount";
    public static final String VERSION_COLUMN = "version_no";

    /** 组内逐行可能不同的列。行集维度 = discount_order（在 uq_annual_discount 内）。 */
    public static final List<String> CONTENT = List.of(
        "discount_order", "discount_ratio", "fixed_discount_value",
        "currency", "unit", "discount_times", "seq_no");

    /** 读一行的 content 七列。键集必须恒等于 {@link #CONTENT}。 */
    public static Map<String, Object> readContent(SheetRow row) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("discount_order", row.getInt("年降顺序"));
        c.put("discount_ratio", row.getDecimal("年降系数"));
        c.put("fixed_discount_value", row.getDecimal("单次固定年降"));  // 比例/固定二选一，空值留 NULL
        c.put("currency", row.getStr("货币"));
        c.put("unit", row.getStr("计价单位"));
        c.put("discount_times", row.getInt("降价次数"));
        c.put("seq_no", row.getInt("项次"));
        return c;
    }

    /** 组装 5 列 groupKey。{@code targetNo} 允许为 null（FINISHED / 组装工序留空）。 */
    public static Map<String, Object> groupKey(String discountType, String customerNo,
                                               String materialNo, String targetNo) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("system_type", "QUOTE");
        g.put("customer_no", customerNo);
        g.put("discount_type", discountType);
        g.put("material_no", materialNo);
        g.put("target_no", targetNo);
        return g;
    }

    /**
     * 把 Handler 攒好的分组写入 {@code annual_discount}。
     * {@code versionTriggerColumns} 传 null = 任何内容变化即升版（与 Q08/Q15 改造前口径一致）。
     *
     * @param groupKeyOf Handler 内部 key → groupKey 列值
     * @param contentOf  Handler 内部 key → 该组的行集
     */
    public void write(Map<List<Object>, Map<String, Object>> groupKeyOf,
                      Map<List<Object>, List<Map<String, Object>>> contentOf,
                      SheetImportResult result, UUID pendingQuotationId) {
        if (contentOf.isEmpty()) return;
        if (setBased) {
            LinkedHashMap<Map<String, Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
                groups.put(groupKeyOf.get(e.getKey()), e.getValue());
            }
            try {
                writer.writeVersionedGroups(TABLE, VERSION_COLUMN, CONTENT, null,
                    List.of(), groups, pendingQuotationId);
                for (List<Map<String, Object>> groupRows : groups.values()) {
                    result.recordWrite(TABLE, groupRows.size());
                }
            } catch (Exception ex) {
                result.recordError(0, "_batch_", ex.getMessage());
            }
        } else {
            for (Map.Entry<List<Object>, List<Map<String, Object>>> e : contentOf.entrySet()) {
                try {
                    writer.writeVersionedGroup(new VersionedGroupSpec(
                        TABLE, VERSION_COLUMN, groupKeyOf.get(e.getKey()), CONTENT,
                        e.getValue(), null, pendingQuotationId));
                    result.recordWrite(TABLE, e.getValue().size());
                } catch (Exception ex) {
                    result.recordError(0, "_group_", ex.getMessage());
                }
            }
        }
    }

    /**
     * Handler 攒分组用的小工具：把一行塞进对应的组，按「同组同 {@code discount_order} 逐字段末值非空胜」归并。
     *
     * <p><b>为什么不能无脑 append</b>：同一 {@code (groupKey, discount_order)} 在一个 Sheet 内允许出现多行——
     * 真实业务场景是一行只填「年降系数」、另一行只补「单次固定年降值」，两行本质是同一条年降记录的
     * 增量填写。{@link MaterialMasterBatchImportIntegrationTest} 187 行的 dup 夹具就显式钉死了这个契约：
     * {@code AD1/order=1} 先来一行只带 ratio，再来一行只带 fixed，归并后必须是"ratio 保留、fixed 补上"的
     * 一行，而不是两行。这层归并语义原来由已删除的 {@code AnnualDiscountRepository.accDiscount}
     * （单表化改造前，逐行 {@code ON CONFLICT DO UPDATE SET col = COALESCE(EXCLUDED.col, existing.col)}
     * 的批内等价物）承担；三个 Sheet 收敛到 {@link VersionedV6Writer} 组级版本化写入时被误判为
     * "重复即透传给 DB 让 uq 约束 fail loud"而丢失，导致同 key 同 order 的批内重复直接撞
     * {@code uq_annual_discount} 触发整单回滚。
     *
     * <p>归并只在<b>同一 groupKey（即同一 {@code key} 入参）内、且 {@code discount_order} 相等</b>
     * （用 {@link Objects#equals} 比较，null 也算相等——理论上走不到，Phase 1 已强制 discount_order
     * 必填，这里只是兜底防 NPE）时才发生；不同 groupKey 或不同 discount_order 一律各自成行，不会被
     * 误合并。合并方向 = 后到的行逐字段覆盖先到的行，仅当新值非 null 才覆盖（末值非空胜），
     * 与 {@code COALESCE(EXCLUDED, existing)} 的语义等价。
     */
    public static void accumulate(Map<List<Object>, Map<String, Object>> groupKeyOf,
                                  Map<List<Object>, List<Map<String, Object>>> contentOf,
                                  List<Object> key, Map<String, Object> gk, Map<String, Object> content) {
        groupKeyOf.putIfAbsent(key, gk);
        List<Map<String, Object>> rows = contentOf.computeIfAbsent(key, k -> new ArrayList<>());
        Object order = content.get("discount_order");
        for (Map<String, Object> existing : rows) {
            if (Objects.equals(existing.get("discount_order"), order)) {
                for (Map.Entry<String, Object> e : content.entrySet()) {
                    if (e.getValue() != null) {
                        existing.put(e.getKey(), e.getValue());
                    }
                }
                return;
            }
        }
        rows.add(content);
    }
}
