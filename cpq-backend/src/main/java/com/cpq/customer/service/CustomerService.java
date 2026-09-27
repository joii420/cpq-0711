package com.cpq.customer.service;

import com.cpq.basicdata.entity.ProductCategory;
import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.customer.dto.ContactDTO;
import com.cpq.customer.dto.CreateCustomerRequest;
import com.cpq.customer.dto.CustomerDTO;
import com.cpq.customer.entity.Customer;
import com.cpq.customer.entity.CustomerContact;
import com.cpq.system.service.OperationLogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import com.cpq.quotation.entity.Quotation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CustomerService {

    private static final Logger LOG = Logger.getLogger(CustomerService.class);

    @Inject
    EntityManager em;

    @Inject
    CustomerContactService contactService;

    @Inject
    OperationLogService operationLogService;

    public PageResult<CustomerDTO> list(int page, int size, String level, String status, String keyword) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        StringBuilder query = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (level != null && !level.isBlank()) {
            query.append(" AND level = :level");
            params.put("level", level);
        }
        if (status != null && !status.isBlank()) {
            query.append(" AND status = :status");
            params.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            query.append(" AND (name LIKE :kw OR code LIKE :kw OR id IN (SELECT cc.customerId FROM CustomerContact cc WHERE cc.name LIKE :kw OR cc.phone LIKE :kw))");
            params.put("kw", "%" + keyword + "%");
        }

        long total = Customer.count(query.toString(), params);
        List<CustomerDTO> content = Customer
                .find(query + " ORDER BY createdAt DESC", params)
                .page(page, size)
                .<Customer>list()
                .stream()
                .map(CustomerDTO::from)
                .collect(Collectors.toList());

        LOG.debugf("list customers page=%d size=%d total=%d", page, size, total);
        return new PageResult<>(content, page, size, total);
    }

    public CustomerDTO getById(UUID id) {
        Customer customer = Customer.findById(id);
        if (customer == null) {
            throw new BusinessException(404, "Customer not found: " + id);
        }
        List<CustomerContact> contacts = CustomerContact.<CustomerContact>list(
                "customerId = ?1 ORDER BY isPrimary DESC, createdAt ASC", id);
        CustomerDTO dto = CustomerDTO.from(customer, contacts);
        long quotationCount = 0;
        double avgDiscount = 100;
        try {
            quotationCount = em.createQuery("SELECT COUNT(q) FROM Quotation q WHERE q.customerId = :cid", Long.class)
                .setParameter("cid", id).getSingleResult();
            Double avg = em.createQuery("SELECT AVG(q.finalDiscountRate) FROM Quotation q WHERE q.customerId = :cid AND q.finalDiscountRate IS NOT NULL", Double.class)
                .setParameter("cid", id).getSingleResult();
            if (avg != null) avgDiscount = avg;
        } catch (Exception e) { /* table might not exist yet */ }
        dto.quotationCount = quotationCount;
        dto.avgDiscountRate = avgDiscount;
        return dto;
    }

    @Transactional
    public CustomerDTO create(CreateCustomerRequest request, UUID operatorId) {
        lockCustomerWrites();
        validateCustomerIdentity(request.name, request.code, null);
        if (request.contacts == null || request.contacts.isEmpty()) {
            throw new BusinessException("At least one contact is required");
        }
        boolean hasPrimary = request.contacts.stream().anyMatch(c -> Boolean.TRUE.equals(c.isPrimary));
        if (!hasPrimary) {
            throw new BusinessException("At least one primary contact is required");
        }

        // Validate all phone numbers
        for (ContactDTO c : request.contacts) {
            if (c.phone == null || !c.phone.matches("^\\d{11}$")) {
                throw new BusinessException("Phone must be 11 digits: " + c.phone);
            }
        }

        String code = request.code == null || request.code.isEmpty() ? generateCode() : request.code;

        Customer customer = new Customer();
        customer.name = request.name;
        customer.code = code;
        customer.level = request.level != null ? request.level : "STANDARD";
        customer.industry = request.industry;
        customer.industryCode = request.industryCode;
        // task-0712 update-071501 D3: 产品分类必填非空; 前端保证必选，后端兜底"默认分类"防漏传
        customer.productCategoryId = request.productCategoryId != null
                ? request.productCategoryId
                : ProductCategory.requireDefaultId();
        customer.region = request.region;
        customer.address = request.address;
        customer.creditLimit = request.creditLimit;
        customer.paymentMethod = request.paymentMethod;
        customer.remarks = request.remarks;
        customer.status = "ACTIVE";
        customer.persist();

        // Persist contacts
        for (ContactDTO contactDTO : request.contacts) {
            CustomerContact contact = new CustomerContact();
            contact.customerId = customer.id;
            contact.name = contactDTO.name;
            contact.role = contactDTO.role;
            contact.phone = contactDTO.phone;
            contact.email = contactDTO.email;
            contact.wechat = contactDTO.wechat;
            contact.isPrimary = Boolean.TRUE.equals(contactDTO.isPrimary);
            contact.persist();
        }

        List<CustomerContact> contacts = CustomerContact.<CustomerContact>list(
                "customerId = ?1 ORDER BY isPrimary DESC, createdAt ASC", customer.id);
        LOG.infof("Created customer code=%s name=%s contacts=%d", customer.code, customer.name, contacts.size());

        operationLogService.log(operatorId, "CREATE", "CUSTOMER", customer.id,
                String.format("name=%s,code=%s,level=%s", customer.name, customer.code, customer.level));

        return CustomerDTO.from(customer, contacts);
    }

    @Transactional
    public CustomerDTO update(UUID id, CreateCustomerRequest request) {
        lockCustomerWrites();
        Customer customer = Customer.findById(id);
        if (customer == null) {
            throw new BusinessException(404, "Customer not found: " + id);
        }
        if (request.name != null) {
            validateCustomerName(request.name, id);
            customer.name = request.name;
        }
        if (request.code != null && !request.code.isEmpty() && !request.code.equals(customer.code)) {
            throw new BusinessException(400, "客户编号创建后不可修改");
        }
        if (request.level != null) customer.level = request.level;
        if (request.industry != null) customer.industry = request.industry;
        if (request.industryCode != null) customer.industryCode = request.industryCode;
        // task-0712 update-071501 D4: 客户建后允许改绑产品分类; 改绑不追溯已有报价单(表头不持久化 categoryId)
        if (request.productCategoryId != null) customer.productCategoryId = request.productCategoryId;
        if (request.region != null) customer.region = request.region;
        if (request.address != null) customer.address = request.address;
        if (request.creditLimit != null) customer.creditLimit = request.creditLimit;
        if (request.paymentMethod != null) customer.paymentMethod = request.paymentMethod;
        if (request.remarks != null) customer.remarks = request.remarks;

        LOG.infof("Updated customer id=%s name=%s", id, customer.name);
        List<CustomerContact> contacts = CustomerContact.<CustomerContact>list(
                "customerId = ?1 ORDER BY isPrimary DESC, createdAt ASC", id);
        return CustomerDTO.from(customer, contacts);
    }

    @Transactional
    public void delete(UUID id) {
        lockCustomerWrites();
        Customer customer = Customer.findById(id);
        if (customer == null) {
            throw new BusinessException(404, "Customer not found: " + id);
        }
        checkNoActiveQuotations(id);
        customer.status = "INACTIVE";
        LOG.infof("Soft-deleted customer id=%s code=%s", id, customer.code);
    }

    @Transactional
    public void batchDelete(List<UUID> ids) {
        for (UUID id : ids) {
            delete(id);
        }
        LOG.infof("Batch soft-deleted %d customers", ids.size());
    }

    private void checkNoActiveQuotations(UUID customerId) {
        try {
            // Check if quotation table exists first
            Long tableExists = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'quotation'"
            ).getSingleResult();
            if (tableExists == null || tableExists == 0) {
                // Table doesn't exist yet, skip check
                return;
            }
            // Table exists, check for active quotations
            Long activeCount = (Long) em.createNativeQuery(
                "SELECT COUNT(*) FROM quotation WHERE customer_id = :cid AND status IN ('DRAFT','SUBMITTED','APPROVED')"
            ).setParameter("cid", customerId).getSingleResult();
            if (activeCount != null && activeCount > 0) {
                throw new BusinessException("Cannot delete customer with active quotations");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Skip check if any unexpected error
            LOG.debugf("Quotation check skipped for customerId=%s: %s", customerId, e.getMessage());
        }
    }

    public String generateCode() {
        return generateCode(Set.of());
    }

    /** Generate a code that is globally unique under the caller's customer-write lock. */
    public String generateCode(Set<String> reservedNormalizedCodes) {
        while (true) {
            Long seq = (Long) em.createNativeQuery("SELECT nextval('customer_code_seq')").getSingleResult();
            String candidate = String.format("CUST-%04d", seq);
            String normalized = normalizeIdentity(candidate);
            if (reservedNormalizedCodes.contains(normalized)) continue;
            Number existing = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM customer WHERE lower(trim(code)) = :code")
                    .setParameter("code", normalized)
                    .getSingleResult();
            if (existing.longValue() == 0) return candidate;
        }
    }

    /** Generate from a caller-provided complete reservation set (used by a locked batch import). */
    public String generateCodeFromReserved(Set<String> reservedNormalizedCodes) {
        while (true) {
            Long seq = (Long) em.createNativeQuery("SELECT nextval('customer_code_seq')").getSingleResult();
            String candidate = String.format("CUST-%04d", seq);
            if (reservedNormalizedCodes.add(normalizeIdentity(candidate))) return candidate;
        }
    }

    /**
     * Normalize only for comparison. Raw leading/trailing whitespace is rejected so that
     * values copied from spreadsheets do not silently mutate what the user entered.
     */
    public static String normalizeIdentity(String value) {
        return value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public void validateCustomerIdentity(String name, String code, UUID excludeId) {
        validateCustomerName(name, excludeId);
        if (code != null && !code.isEmpty()) {
            if (!code.equals(code.trim())) {
                throw new BusinessException(400, "客户编号首尾不能有空格");
            }
            if (code.length() > 50) {
                throw new BusinessException(400, "客户编号长度不能超过50个字符");
            }
            String normalized = normalizeIdentity(code);
            List<Customer> matches = Customer.<Customer>find("lower(trim(code)) = ?1", normalized).list();
            if (matches.stream().anyMatch(c -> excludeId == null || !excludeId.equals(c.id))) {
                throw new BusinessException(409, "客户编号已存在: " + code);
            }
        }
    }

    public void validateCustomerName(String name, UUID excludeId) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "客户名称不能为空");
        }
        if (!name.equals(name.trim())) {
            throw new BusinessException(400, "客户名称首尾不能有空格");
        }
        if (name.length() > 200) {
            throw new BusinessException(400, "客户名称长度不能超过200个字符");
        }
        String normalized = normalizeIdentity(name);
        List<Customer> matches = Customer.<Customer>find("lower(trim(name)) = ?1", normalized).list();
        if (matches.stream().anyMatch(c -> excludeId == null || !excludeId.equals(c.id))) {
            throw new BusinessException(409, "客户名称已存在: " + name);
        }
    }

    /** Serialize all customer writes without adding a functional DB unique index. */
    public void lockCustomerWrites() {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", 260926001L)
                .getSingleResult();
    }
}
