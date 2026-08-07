package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateComponentSnapshot;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * task-0806：已发布模板渲染配置的<b>唯一</b>读取入口。
 *
 * <p>渲染期一切「这个模板有哪些页签、每个页签什么配置」的问题都问它——收口 10+ 处曾各自
 * 手写 {@code JOIN component} / {@code Component.findById} 的 native SQL（本 bug 潜伏至今的
 * 原因之一）。
 *
 * <p><b>接口形状必须是批量的</b>：把 CLAUDE.md「单个业务操作 SQL 条数与 N 无关」的铁律焊死
 * 在签名里。🚫 <b>禁止</b>新增 {@code tabOf(templateId, sortOrder)} 这类单条查询方法被调用方
 * 放进循环——那就是把 N+1 重新引回来。若某调用点确实只要一条，让它拿 {@link #allTabsOf}
 * 的结果在内存里挑（见 {@link #findTab}）。
 *
 * <p><b>miss 语义（FR-6 / AC-6）</b>：模板 status ∈ (PUBLISHED, ARCHIVED) 且查不到任何快照行
 * → 抛 {@link BusinessException}(500)。<b>绝不允许</b>回落活表 {@code component} /
 * {@code template_component}——报价单绑定已被 task-0729 强制要求 PUBLISHED，渲染期不存在
 * DRAFT 分支，不需要兜底路径。DRAFT 模板本就不写快照（草稿期一律走活表，不在本类职责内）。
 */
@ApplicationScoped
public class PublishedTemplateReader {

    /** 该模板全部页签快照，按 sortOrder 升序。一次查询。 */
    public List<TemplateComponentSnapshot> allTabsOf(UUID templateId) {
        if (templateId == null) return List.of();
        List<TemplateComponentSnapshot> rows = TemplateComponentSnapshot.list(
                "templateId = ?1 ORDER BY sortOrder ASC", templateId);
        if (rows.isEmpty()) {
            assertNotFrozenMiss(templateId);
        }
        return rows;
    }

    /** driver 组件（data_driver_path 非空）。委派 {@link #allTabsOf}，不新增查询。 */
    public List<TemplateComponentSnapshot> driverCompsOf(UUID templateId) {
        List<TemplateComponentSnapshot> out = new ArrayList<>();
        for (TemplateComponentSnapshot s : allTabsOf(templateId)) {
            if (s.dataDriverPath != null && !s.dataDriverPath.isBlank()) out.add(s);
        }
        return out;
    }

    /** 树页签（tab_type = 'BOM'）。委派 {@link #allTabsOf}，不新增查询。 */
    public List<TemplateComponentSnapshot> treeTabsOf(UUID templateId) {
        List<TemplateComponentSnapshot> out = new ArrayList<>();
        for (TemplateComponentSnapshot s : allTabsOf(templateId)) {
            if ("BOM".equals(s.tabType)) out.add(s);
        }
        return out;
    }

    /** 是否存在 bom_recursive_expand = true 的页签。委派 {@link #allTabsOf}，不新增查询。 */
    public boolean hasRecursiveExpand(UUID templateId) {
        for (TemplateComponentSnapshot s : allTabsOf(templateId)) {
            if (Boolean.TRUE.equals(s.bomRecursiveExpand)) return true;
        }
        return false;
    }

    /**
     * 多模板批量（供整单场景，避免逐模板查）。一次 {@code IN} 查询取全部模板的全部页签，
     * 按 templateId 分桶；每个请求的 templateId 若无结果且模板本身已发布/归档 → miss 报错。
     */
    public Map<UUID, List<TemplateComponentSnapshot>> allTabsOfMany(Collection<UUID> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) return Map.of();
        List<UUID> distinct = templateIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();

        List<TemplateComponentSnapshot> all = TemplateComponentSnapshot.list(
                "templateId IN ?1 ORDER BY templateId, sortOrder ASC", distinct);
        Map<UUID, List<TemplateComponentSnapshot>> byTemplate = new LinkedHashMap<>();
        for (TemplateComponentSnapshot s : all) {
            byTemplate.computeIfAbsent(s.templateId, k -> new ArrayList<>()).add(s);
        }
        for (UUID tid : distinct) {
            if (!byTemplate.containsKey(tid)) {
                assertNotFrozenMiss(tid);
                byTemplate.put(tid, List.of());
            }
        }
        return byTemplate;
    }

    /**
     * 从 {@link #allTabsOf} 的结果里按 sortOrder 精确取一条（内存挑选，不新增查询）。
     * 调用方应先在循环外调一次 {@link #allTabsOf} 拿到完整列表，避免在循环里调用本方法。
     *
     * @return 命中的快照行；templateId 非 PUBLISHED/ARCHIVED 且未命中时返回 null（DRAFT 场景）
     * @throws BusinessException 500，模板已发布/归档但该 sortOrder 缺失快照行（AC-6）
     */
    public TemplateComponentSnapshot findTab(List<TemplateComponentSnapshot> allTabs, UUID templateId, int sortOrder) {
        if (allTabs != null) {
            for (TemplateComponentSnapshot s : allTabs) {
                if (s.sortOrder != null && s.sortOrder == sortOrder) return s;
            }
        }
        Template t = Template.findById(templateId);
        if (t != null && isFrozenStatus(t.status)) {
            throw new BusinessException(500,
                    "模板快照缺失：templateId=" + templateId + ", sortOrder=" + sortOrder
                    + "。已发布模板必须有完整冻结快照，请检查 template_component_snapshot");
        }
        return null;
    }

    // ---- 内部工具 ----

    private static boolean isFrozenStatus(String status) {
        return "PUBLISHED".equals(status) || "ARCHIVED".equals(status);
    }

    /** 结果为空时按模板状态判定是否属于 miss（PUBLISHED/ARCHIVED 应恒有快照）；DRAFT 静默放行。 */
    private void assertNotFrozenMiss(UUID templateId) {
        Template t = Template.findById(templateId);
        if (t == null) {
            throw new BusinessException(404, "Template not found: " + templateId);
        }
        if (isFrozenStatus(t.status)) {
            throw new BusinessException(500,
                    "模板快照缺失：templateId=" + templateId + ", sortOrder=ALL（该模板无任何冻结快照行）。"
                    + "已发布模板必须有完整冻结快照，请检查 template_component_snapshot");
        }
    }
}
