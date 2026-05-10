package com.cpq.importexcel.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.importexcel.dto.CustomerMaterialMappingDTO;
import com.cpq.importexcel.dto.InternalMaterialDTO;
import com.cpq.importexcel.entity.CustomerMaterialMapping;
import com.cpq.importexcel.entity.InternalMaterial;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class CustomerMaterialMappingService {

    private static final Logger LOG = Logger.getLogger(CustomerMaterialMappingService.class);

    @Inject
    EntityManager em;

    @SuppressWarnings("unchecked")
    public PageResult<CustomerMaterialMappingDTO> listByCustomer(UUID customerId, String keyword, int page, int size) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        StringBuilder jpql = new StringBuilder(
                "SELECT m, im FROM CustomerMaterialMapping m JOIN InternalMaterial im ON m.materialId = im.id WHERE m.customerId = :customerId");
        Map<String, Object> params = new HashMap<>();
        params.put("customerId", customerId);

        if (keyword != null && !keyword.isBlank()) {
            jpql.append(" AND (LOWER(m.customerPartNo) LIKE :kw OR LOWER(im.materialNo) LIKE :kw OR LOWER(im.name) LIKE :kw)");
            params.put("kw", "%" + keyword.toLowerCase() + "%");
        }
        jpql.append(" ORDER BY m.createdAt DESC");

        jakarta.persistence.TypedQuery<Object[]> q = em.createQuery(jpql.toString(), Object[].class);
        params.forEach(q::setParameter);

        // count
        String countJpql = "SELECT COUNT(m) FROM CustomerMaterialMapping m JOIN InternalMaterial im ON m.materialId = im.id WHERE m.customerId = :customerId"
                + (keyword != null && !keyword.isBlank() ? " AND (LOWER(m.customerPartNo) LIKE :kw OR LOWER(im.materialNo) LIKE :kw OR LOWER(im.name) LIKE :kw)" : "");
        jakarta.persistence.TypedQuery<Long> countQ = em.createQuery(countJpql, Long.class);
        params.forEach(countQ::setParameter);
        long total = countQ.getSingleResult();

        List<CustomerMaterialMappingDTO> content = q
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList()
                .stream()
                .map(row -> {
                    CustomerMaterialMapping mapping = (CustomerMaterialMapping) row[0];
                    InternalMaterial material = (InternalMaterial) row[1];
                    CustomerMaterialMappingDTO dto = CustomerMaterialMappingDTO.from(mapping);
                    dto.materialNo = material.materialNo;
                    dto.materialName = material.name;
                    return dto;
                })
                .collect(Collectors.toList());

        return new PageResult<>(content, page, size, total);
    }

    @Transactional
    public CustomerMaterialMappingDTO create(UUID customerId, String customerPartNo, UUID materialId) {
        long existing = CustomerMaterialMapping.count("customerId = ?1 AND customerPartNo = ?2", customerId, customerPartNo);
        if (existing > 0) throw new BusinessException(400, "Mapping already exists for this customer + partNo");

        InternalMaterial material = InternalMaterial.findById(materialId);
        if (material == null) throw new BusinessException(404, "InternalMaterial not found: " + materialId);

        CustomerMaterialMapping m = new CustomerMaterialMapping();
        m.customerId = customerId;
        m.customerPartNo = customerPartNo;
        m.materialId = materialId;
        m.persist();

        CustomerMaterialMappingDTO dto = CustomerMaterialMappingDTO.from(m);
        dto.materialNo = material.materialNo;
        dto.materialName = material.name;
        LOG.infof("Created customer material mapping id=%s customer=%s partNo=%s", m.id, customerId, customerPartNo);
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        CustomerMaterialMapping m = CustomerMaterialMapping.findById(id);
        if (m == null) throw new BusinessException(404, "Mapping not found: " + id);
        m.delete();
    }

    @Transactional
    public int batchImport(UUID customerId, InputStream inputStream) {
        int count = 0;
        try (Workbook wb = WorkbookFactory.create(inputStream)) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String customerPartNo = getCellStringValue(row, 0);
                String materialNo = getCellStringValue(row, 1);
                if (customerPartNo == null || customerPartNo.isBlank()) continue;
                if (materialNo == null || materialNo.isBlank()) continue;

                InternalMaterial material = InternalMaterial.find("materialNo = ?1", materialNo).firstResult();
                if (material == null) continue;

                long existing = CustomerMaterialMapping.count("customerId = ?1 AND customerPartNo = ?2", customerId, customerPartNo);
                if (existing > 0) continue;

                CustomerMaterialMapping m = new CustomerMaterialMapping();
                m.customerId = customerId;
                m.customerPartNo = customerPartNo;
                m.materialId = material.id;
                m.persist();
                count++;
            }
        } catch (Exception e) {
            throw new BusinessException(400, "Failed to parse Excel: " + e.getMessage());
        }
        LOG.infof("Batch imported %d customer material mappings for customer=%s", count, customerId);
        return count;
    }

    public InternalMaterialDTO matchPartNo(UUID customerId, String customerPartNo) {
        CustomerMaterialMapping mapping = CustomerMaterialMapping
                .find("customerId = ?1 AND customerPartNo = ?2", customerId, customerPartNo)
                .firstResult();
        if (mapping == null) return null;

        InternalMaterial material = InternalMaterial.findById(mapping.materialId);
        if (material == null) return null;
        return InternalMaterialDTO.from(material);
    }

    private String getCellStringValue(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }
}
