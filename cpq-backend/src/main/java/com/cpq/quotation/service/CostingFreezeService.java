package com.cpq.quotation.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.globalvariable.GlobalVariableDefinition;
import com.cpq.globalvariable.GlobalVariableService;
import com.cpq.quotation.dto.QuotationDTO;
import com.cpq.quotation.entity.CostingOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * CostingFreezeService — 核价单冻结服务（T2 实现）。
 *
 * <p>职责:
 * <ol>
 *   <li>buildFrozenDto: 把整份 QuotationDTO + gvDefs 序列化成 JSONB 字符串，供核价单 frozen_dto 列存储。</li>
 *   <li>createForSubmission: 每次报价单 submit 时创建新核价单（累积模式），携带冻结 DTO + totalAmount。</li>
 * </ol>
 *
 * <p>并发防护: persist + em.flush() 后若撞 uq_co_active 部分唯一索引，捕获
 * {@link org.hibernate.exception.ConstraintViolationException}（SQLState 23505）并抛 409 BusinessException。
 */
@ApplicationScoped
public class CostingFreezeService {

    private static final Logger LOG = Logger.getLogger(CostingFreezeService.class);

    @Inject
    QuotationService quotationService;

    @Inject
    GlobalVariableService globalVariableService;

    @Inject
    EntityManager em;

    /** Quarkus 管理的 ObjectMapper：已注册 JavaTimeModule，正确处理 OffsetDateTime 等 Java 8 时间类型。 */
    @Inject
    ObjectMapper mapper;

    /**
     * 根据报价单 ID 构建冻结 DTO JSON 字符串。
     * 内部调 QuotationService.getById() 拿 DTO，再附上 gvDefs 列表。
     *
     * @param quotationId 报价单 ID
     * @return frozen_dto JSONB 字符串
     */
    public String buildFrozenDto(UUID quotationId) {
        QuotationDTO dto = quotationService.getById(quotationId);
        return serializeWithGvDefs(dto);
    }

    /**
     * 每次报价单提交时创建新核价单（累积模式，非幂等）。
     * 冻结当前 QuotationDTO + gvDefs 到 frozen_dto；totalAmount 从 DTO 取。
     * costingOrderNumber 交由 {@link CostingOrder#prePersist()} 通过序列自动生成。
     *
     * @param quotationId 报价单 ID
     * @param submittedBy 提交用户 ID（可为 null，向后兼容）
     * @return 已持久化的 CostingOrder 实体
     * @throws BusinessException 409 如果同一报价单已存在 active（PENDING/APPROVED）核价单
     */
    public CostingOrder createForSubmission(UUID quotationId, UUID submittedBy) {
        // 一次 getById 同时用于 frozen_dto 序列化和 totalAmount — 避免二次查询
        QuotationDTO dto = quotationService.getById(quotationId);
        String frozenJson = serializeWithGvDefs(dto);

        CostingOrder co = new CostingOrder();
        co.quotationId = quotationId;
        co.submittedBy = submittedBy;
        co.frozenDto = frozenJson;
        co.totalAmount = dto.totalAmount;
        // costingOrderNumber / createdAt / enteredCostingAt / updatedAt 由 @PrePersist 填充

        try {
            co.persist();
            // 强制立即 flush，使 DB 层 uq_co_active 部分唯一索引冲突在此处抛出，
            // 而非推迟到事务提交时（届时已无法在业务层捕获并转 409）。
            em.flush();
        } catch (org.hibernate.exception.ConstraintViolationException cve) {
            // 在 Hibernate 6 中 ConstraintViolationException extends HibernateException extends PersistenceException，
            // 必须先于 PersistenceException 捕获（更具体的子类在前）。
            if (isActiveUniquenessViolation(cve)) {
                throw new BusinessException(409, "已存在进行中的核价单");
            }
            throw cve;
        } catch (PersistenceException pe) {
            // 其他持久化异常（非 ConstraintViolationException）：检查 cause 链是否有隐藏的约束冲突。
            Throwable cause = pe.getCause();
            while (cause != null) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException cve
                        && isActiveUniquenessViolation(cve)) {
                    throw new BusinessException(409, "已存在进行中的核价单");
                }
                cause = cause.getCause();
            }
            throw pe;
        }

        LOG.infof("[CostingFreeze] created costingOrder id=%s number=%s quotationId=%s",
                co.id, co.costingOrderNumber, quotationId);
        return co;
    }

    // ── 私有工具 ───────────────────────────────────────────────────────────────

    /**
     * 将 QuotationDTO 序列化为 ObjectNode，附加 gvDefs 字段后返回 JSON 字符串。
     */
    private String serializeWithGvDefs(QuotationDTO dto) {
        try {
            ObjectNode node = (ObjectNode) mapper.valueToTree(dto);
            List<GlobalVariableDefinition> gvList = globalVariableService.listAll();
            node.set("gvDefs", mapper.valueToTree(gvList));
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("CostingFreezeService: failed to serialize frozen DTO", e);
        }
    }

    /**
     * 判断 ConstraintViolationException 是否由 uq_co_active 部分唯一索引冲突引起。
     * 匹配条件：SQLState=23505（唯一约束冲突）且约束名包含 "uq_co_active"。
     */
    private static boolean isActiveUniquenessViolation(
            org.hibernate.exception.ConstraintViolationException cve) {
        java.sql.SQLException sqlEx = cve.getSQLException();
        String sqlState = sqlEx != null ? sqlEx.getSQLState() : null;
        String constraint = cve.getConstraintName();
        boolean isUniqueViolation = "23505".equals(sqlState);
        boolean isTargetConstraint = constraint != null && constraint.contains("uq_co_active");
        return isUniqueViolation && isTargetConstraint;
    }
}
