package com.cpq.basicdata.v6.service;

import com.cpq.basicdata.v6.entity.ProcessMaster;
import com.cpq.basicdata.v6.repository.ProcessMasterRepository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * repair-0727：工序编号解析器。把 Excel「组装工序」列的原始值（业务可能填工序编号，也可能填
 * 工序名称）解析为 {@code process_master} 的真编号 + 规范名称，供报价导入 Phase 1
 * （{@link com.cpq.basicdata.v6.quote.QuoteImportValidator}）消费。
 *
 * <p><b>批内索引</b>：一次导入只 {@link #buildIndex()} 一次（process_master 全表载入内存），
 * 严禁逐行查库（AC-11 性能要求）。
 *
 * <p><b>两段匹配（顺序不能颠倒）</b>：
 * <ol>
 *   <li>原始值 {@code strip()} 后按 {@code process_no} 精确匹配 → 命中直接采用；</li>
 *   <li>再按 {@code process_name} 精确匹配（trim）→ 唯一命中直接采用；命中多条按
 *       {@code process_no} 升序取第一条并 {@code Log.warn} 留痕（被选中编号 + 全部候选 + 原始值）；</li>
 *   <li>两段都不命中 → 返回 {@link Optional#empty()}，失败原因见 {@link #failReason(String)}。</li>
 * </ol>
 *
 * <p>匹配前统一 {@code strip()}；全半角、大小写差异<b>不做</b>归一（R3：避免误匹配，由错误
 * 文案引导业务对齐）。
 *
 * <p>纯函数式：{@link #resolve(String, Index)} 不访问 {@code ImportContext} /
 * {@code SheetImportResult}，{@link Index#of(List)} 亦不依赖 CDI/DB，均可脱离容器单测。
 */
@ApplicationScoped
public class ProcessNoResolver {

    @Inject ProcessMasterRepository repo;

    /** 解析成功结果：真工序编号 + 规范名称。 */
    public record Resolved(String processNo, String processName) {}

    /** 一次导入内共用的全表索引。 */
    public static final class Index {
        final Map<String, ProcessMaster> byNo = new HashMap<>();
        final Map<String, List<ProcessMaster>> byName = new HashMap<>();

        /** 供单测直接构造，无需 DB / CDI。 */
        public static Index of(List<ProcessMaster> all) {
            Index idx = new Index();
            for (ProcessMaster pm : all) {
                if (pm.processNo == null) continue;
                idx.byNo.put(pm.processNo.strip(), pm);
                String name = pm.processName == null ? null : pm.processName.strip();
                if (name != null && !name.isEmpty()) {
                    idx.byName.computeIfAbsent(name, k -> new ArrayList<>()).add(pm);
                }
            }
            // byName 每个候选列表必须按 process_no 升序排好，保证"取第一条"可复现（不依赖 DB 返回顺序）。
            for (List<ProcessMaster> list : idx.byName.values()) {
                list.sort(Comparator.comparing(p -> p.processNo));
            }
            return idx;
        }
    }

    /** 一次导入调一次（process_master 全表只查一次库）。 */
    public Index buildIndex() {
        return Index.of(repo.listAll());
    }

    /**
     * 解析 Excel 原始值。{@code null}/空白输入直接返回失败（不 NPE）。
     */
    public Optional<Resolved> resolve(String rawValue, Index idx) {
        if (rawValue == null || rawValue.isBlank()) return Optional.empty();
        String v = rawValue.strip();

        ProcessMaster byNo = idx.byNo.get(v);
        if (byNo != null) return Optional.of(new Resolved(byNo.processNo, byNo.processName));

        List<ProcessMaster> candidates = idx.byName.get(v);
        if (candidates != null && !candidates.isEmpty()) {
            ProcessMaster chosen = candidates.get(0);
            if (candidates.size() > 1) {
                StringBuilder allNos = new StringBuilder();
                for (ProcessMaster c : candidates) {
                    if (allNos.length() > 0) allNos.append(", ");
                    allNos.append(c.processNo);
                }
                Log.warnf("ProcessNoResolver: 工序名称「%s」同名多条(%d)，取 process_no 升序第一条「%s」；全部候选=[%s]",
                    v, candidates.size(), chosen.processNo, allNos);
            }
            return Optional.of(new Resolved(chosen.processNo, chosen.processName));
        }
        return Optional.empty();
    }

    /** 固定失败原因文案（可直接展示给业务）。 */
    public static String failReason(String rawValue) {
        return "工序「" + rawValue + "」未在工序主数据中登记，请先在 主数据维护 → 工序 中录入或导入";
    }
}
