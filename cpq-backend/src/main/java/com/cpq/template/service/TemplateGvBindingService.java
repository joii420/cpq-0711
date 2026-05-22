package com.cpq.template.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.globalvariable.GlobalVariableDefinition;
import com.cpq.globalvariable.GlobalVariableService;
import com.cpq.template.dto.TemplateGvBindingDTO;
import com.cpq.template.dto.UpdateBindingsRequest;
import com.cpq.template.entity.Template;
import com.cpq.template.entity.TemplateGlobalVariableBinding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * V212: 模板全局变量绑定服务.
 *
 * <p>三个核心方法:
 * <ol>
 *   <li>{@link #listByTemplateId} — JOIN GVD 回填展示字段</li>
 *   <li>{@link #updateBindings} — 全量替换 (PUT 语义), 校验模板 DRAFT 状态</li>
 *   <li>{@link #copyBindings} — createNewDraft 调用, 复制绑定关系到新草稿</li>
 * </ol>
 */
@ApplicationScoped
public class TemplateGvBindingService {

    private static final Logger LOG = Logger.getLogger(TemplateGvBindingService.class);

    /** 软上限: bindings.length > 20 时记录警告 (仍可接受, ADR-002 §4.3 422 TOO_MANY_BINDINGS) */
    private static final int SOFT_LIMIT = 20;

    @Inject
    EntityManager em;

    @Inject
    GlobalVariableService globalVariableService;

    /**
     * 查询模板的全局变量绑定列表, JOIN global_variable_definition 回填名称/类型/单位.
     *
     * @param templateId 模板 ID
     * @return 按 display_order 升序的绑定列表 (含 GVD 回填字段)
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<TemplateGvBindingDTO> listByTemplateId(UUID templateId) {
        // 验证模板存在
        Template template = Template.findById(templateId);
        if (template == null) {
            throw new BusinessException(404, "模板不存在: " + templateId);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT b.id, b.template_id, b.global_variable_code, b.display_order, b.created_at, " +
                "       g.name, g.var_type, g.unit, g.is_active " +
                "FROM template_global_variable_binding b " +
                "JOIN global_variable_definition g ON g.code = b.global_variable_code " +
                "WHERE b.template_id = :tid " +
                "ORDER BY b.display_order ASC, b.created_at ASC")
                .setParameter("tid", templateId)
                .getResultList();

        List<TemplateGvBindingDTO> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(rowToDto(r));
        }
        return result;
    }

    /**
     * 全量替换模板的全局变量绑定 (PUT 语义).
     *
     * <p>前置校验:
     * <ul>
     *   <li>模板必须为 DRAFT 状态 (非 DRAFT 抛 403)</li>
     *   <li>每个 globalVariableCode 必须在 global_variable_definition 中存在 (抛 400 INVALID_GV_CODE)</li>
     *   <li>每个 globalVariableCode 对应的 GV 必须 is_active=true (抛 400 INACTIVE_GV_CODE)</li>
     * </ul>
     *
     * @param templateId 模板 ID
     * @param req        PUT 请求体
     * @return 更新后的绑定列表
     */
    @Transactional
    public List<TemplateGvBindingDTO> updateBindings(UUID templateId, UpdateBindingsRequest req) {
        // 1. 验证模板存在
        Template template = Template.findById(templateId);
        if (template == null) {
            throw new BusinessException(404, "模板不存在: " + templateId);
        }

        // 2. 验证模板为 DRAFT 状态
        if (!"DRAFT".equals(template.status)) {
            throw new WebApplicationException(
                    Response.status(Response.Status.FORBIDDEN)
                            .entity("{\"code\":403,\"message\":\"TEMPLATE_NOT_DRAFT: 只有 DRAFT 状态的模板可以修改绑定\"}")
                            .type("application/json")
                            .build());
        }

        // 3. 软上限警告
        if (req.bindings != null && req.bindings.size() > SOFT_LIMIT) {
            LOG.warnf("[TemplateGvBindingService] templateId=%s bindings.size=%d 超过软上限 %d",
                    templateId, req.bindings.size(), SOFT_LIMIT);
        }

        // 4. 校验每个 code
        if (req.bindings != null) {
            for (UpdateBindingsRequest.BindingItem item : req.bindings) {
                if (item.globalVariableCode == null || item.globalVariableCode.isBlank()) {
                    throw new BusinessException(400, "globalVariableCode 不能为空");
                }
                GlobalVariableDefinition def = globalVariableService.getByCode(item.globalVariableCode)
                        .orElseThrow(() -> new BusinessException(400,
                                "INVALID_GV_CODE: 全局变量不存在: " + item.globalVariableCode));
                if (Boolean.FALSE.equals(def.isActive)) {
                    throw new BusinessException(400,
                            "INACTIVE_GV_CODE: 全局变量已停用: " + item.globalVariableCode);
                }
            }
        }

        // 5. 全量替换: 先删除该模板所有现有绑定, 再插入新列表
        em.createNativeQuery(
                "DELETE FROM template_global_variable_binding WHERE template_id = :tid")
                .setParameter("tid", templateId)
                .executeUpdate();

        if (req.bindings != null && !req.bindings.isEmpty()) {
            for (UpdateBindingsRequest.BindingItem item : req.bindings) {
                TemplateGlobalVariableBinding binding = new TemplateGlobalVariableBinding();
                binding.templateId = templateId;
                binding.globalVariableCode = item.globalVariableCode;
                binding.displayOrder = item.displayOrder;
                binding.persist();
            }
        }

        LOG.infof("[TemplateGvBindingService] updateBindings: templateId=%s, count=%d",
                templateId, req.bindings != null ? req.bindings.size() : 0);

        // 6. 返回新列表
        return listByTemplateId(templateId);
    }

    /**
     * 复制绑定关系到新 DRAFT (供 TemplateService.createNewDraft 末尾调用).
     *
     * <p>display_order 原样保留.
     *
     * @param sourceTemplateId 源模板 ID (通常为 PUBLISHED)
     * @param newTemplateId    新草稿模板 ID
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void copyBindings(UUID sourceTemplateId, UUID newTemplateId) {
        List<TemplateGlobalVariableBinding> sourceBindings = TemplateGlobalVariableBinding.list(
                "templateId = ?1 ORDER BY displayOrder ASC", sourceTemplateId);

        for (TemplateGlobalVariableBinding src : sourceBindings) {
            TemplateGlobalVariableBinding copy = new TemplateGlobalVariableBinding();
            copy.templateId = newTemplateId;
            copy.globalVariableCode = src.globalVariableCode;
            copy.displayOrder = src.displayOrder;
            copy.persist();
        }

        LOG.infof("[TemplateGvBindingService] copyBindings: source=%s → new=%s, copied=%d",
                sourceTemplateId, newTemplateId, sourceBindings.size());
    }

    // ── 私有工具 ────────────────────────────────────────────────────────

    private TemplateGvBindingDTO rowToDto(Object[] r) {
        TemplateGvBindingDTO dto = new TemplateGvBindingDTO();
        // b.id, b.template_id, b.global_variable_code, b.display_order, b.created_at,
        // g.name, g.var_type, g.unit, g.is_active
        dto.id = r[0] instanceof UUID u ? u : UUID.fromString(r[0].toString());
        dto.templateId = r[1] instanceof UUID u ? u : UUID.fromString(r[1].toString());
        dto.globalVariableCode = (String) r[2];
        dto.displayOrder = r[3] != null ? ((Number) r[3]).intValue() : 0;
        dto.createdAt = r[4] instanceof java.sql.Timestamp ts
                ? ts.toInstant().atOffset(java.time.ZoneOffset.UTC)
                : (r[4] instanceof java.time.OffsetDateTime odt ? odt : null);
        dto.globalVariableName = (String) r[5];
        dto.varType = (String) r[6];
        dto.unit = (String) r[7];
        dto.isActive = r[8] != null && (Boolean) r[8];
        return dto;
    }
}
