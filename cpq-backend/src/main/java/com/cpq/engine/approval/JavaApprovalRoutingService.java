package com.cpq.engine.approval;

import com.cpq.approval.entity.ApprovalRule;
import com.cpq.system.entity.User;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Blocking
public class JavaApprovalRoutingService implements ApprovalRoutingService {

    private static final Logger LOG = Logger.getLogger(JavaApprovalRoutingService.class);

    @Inject
    EntityManager em;

    @Override
    public UUID routeApprover(UUID salesRepRegionId, UUID salesRepDepartmentId) {
        List<ApprovalRule> rules;
        try {
            rules = em.createQuery(
                "SELECT ar FROM ApprovalRule ar ORDER BY ar.priority ASC",
                ApprovalRule.class)
                .getResultList();
        } catch (Exception e) {
            LOG.warn("Failed to load approval rules (table may not exist yet): " + e.getMessage());
            return findFallbackAdmin();
        }

        for (ApprovalRule rule : rules) {
            if ("FIXED".equals(rule.ruleType)) {
                return rule.approverId;
            }
            if ("DYNAMIC".equals(rule.ruleType) && rule.matchField != null) {
                boolean matches = false;
                if ("REGION".equals(rule.matchField) && salesRepRegionId != null) {
                    matches = salesRepRegionId.equals(rule.matchValueId);
                } else if ("DEPARTMENT".equals(rule.matchField) && salesRepDepartmentId != null) {
                    matches = getDepartmentAncestorChain(salesRepDepartmentId).contains(rule.matchValueId);
                }
                if (matches) {
                    return rule.approverId;
                }
            }
        }

        // Fallback: find earliest ACTIVE SYSTEM_ADMIN user
        return findFallbackAdmin();
    }

    private UUID findFallbackAdmin() {
        try {
            List<User> admins = em.createQuery(
                "SELECT u FROM User u WHERE u.role = 'SYSTEM_ADMIN' AND u.status = 'ACTIVE' " +
                "ORDER BY u.createdAt ASC",
                User.class)
                .setMaxResults(1)
                .getResultList();
            if (!admins.isEmpty()) {
                return admins.get(0).id;
            }
        } catch (Exception e) {
            LOG.error("Failed to find fallback admin: " + e.getMessage());
        }
        return null;
    }

    /**
     * Get the ancestor chain of a department (including itself).
     * E.g., for "华南组" → returns [华南组ID, 销售一部ID, 销售部ID]
     */
    private List<UUID> getDepartmentAncestorChain(UUID departmentId) {
        List<UUID> chain = new java.util.ArrayList<>();
        UUID currentId = departmentId;
        int maxDepth = 10; // prevent infinite loops
        while (currentId != null && maxDepth-- > 0) {
            chain.add(currentId);
            try {
                com.cpq.system.entity.Department dept = em.find(com.cpq.system.entity.Department.class, currentId);
                currentId = (dept != null) ? dept.parentId : null;
            } catch (Exception e) {
                break;
            }
        }
        return chain;
    }
}
