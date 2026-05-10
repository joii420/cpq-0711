package com.cpq.importexcel.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.customer.entity.Customer;
import com.cpq.importexcel.dto.ImportRecordDTO;
import com.cpq.importexcel.entity.CustomerExcelTemplate;
import com.cpq.importexcel.entity.ImportMappingTemplate;
import com.cpq.importexcel.entity.ImportRecord;
import com.cpq.template.entity.Template;
import com.cpq.system.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ImportRecordService {

    private static final Logger LOG = Logger.getLogger(ImportRecordService.class);

    public PageResult<ImportRecordDTO> list(int page, int size, UUID customerId, String importStatus,
                                            UUID importedBy, OffsetDateTime startDate, OffsetDateTime endDate) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (customerId != null) {
            where.append(" AND customerId = :customerId");
            params.put("customerId", customerId);
        }
        if (importStatus != null && !importStatus.isBlank()) {
            where.append(" AND importStatus = :importStatus");
            params.put("importStatus", importStatus);
        }
        if (importedBy != null) {
            where.append(" AND importedBy = :importedBy");
            params.put("importedBy", importedBy);
        }
        if (startDate != null) {
            where.append(" AND createdAt >= :startDate");
            params.put("startDate", startDate);
        }
        if (endDate != null) {
            where.append(" AND createdAt <= :endDate");
            params.put("endDate", endDate);
        }

        String query = where + " ORDER BY createdAt DESC";
        long total = ImportRecord.count(where.toString(), params);
        List<ImportRecordDTO> content = ImportRecord.find(query, params)
                .page(page, size)
                .<ImportRecord>list()
                .stream()
                .map(r -> enrichDto(ImportRecordDTO.from(r), r))
                .collect(Collectors.toList());

        return new PageResult<>(content, page, size, total);
    }

    public ImportRecordDTO getById(UUID id) {
        ImportRecord r = ImportRecord.findById(id);
        if (r == null) throw new BusinessException(404, "ImportRecord not found: " + id);
        return enrichDto(ImportRecordDTO.from(r), r);
    }

    private ImportRecordDTO enrichDto(ImportRecordDTO dto, ImportRecord r) {
        Customer customer = Customer.findById(r.customerId);
        if (customer != null) dto.customerName = customer.name;

        // v3: enrich with template name
        if (r.templateId != null) {
            Template template = Template.findById(r.templateId);
            if (template != null) dto.templateName = template.name;
        }

        // v1/v2 backward compat
        if (r.excelTemplateId != null) {
            CustomerExcelTemplate excelTemplate = CustomerExcelTemplate.findById(r.excelTemplateId);
            if (excelTemplate != null) dto.excelTemplateName = excelTemplate.name;
        }

        if (r.mappingTemplateId != null) {
            ImportMappingTemplate mappingTemplate = ImportMappingTemplate.findById(r.mappingTemplateId);
            if (mappingTemplate != null) dto.mappingTemplateName = mappingTemplate.name;
        }

        User importUser = User.findById(r.importedBy);
        if (importUser != null) dto.importedByName = importUser.fullName;

        return dto;
    }
}
