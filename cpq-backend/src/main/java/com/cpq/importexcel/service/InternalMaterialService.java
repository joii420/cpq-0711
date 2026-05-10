package com.cpq.importexcel.service;

import com.cpq.common.dto.PageResult;
import com.cpq.common.exception.BusinessException;
import com.cpq.importexcel.dto.CreateInternalMaterialRequest;
import com.cpq.importexcel.dto.InternalMaterialDTO;
import com.cpq.importexcel.entity.CustomerMaterialMapping;
import com.cpq.importexcel.entity.InternalMaterial;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.poi.ss.usermodel.*;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class InternalMaterialService {

    private static final Logger LOG = Logger.getLogger(InternalMaterialService.class);

    public PageResult<InternalMaterialDTO> list(int page, int size, String keyword, String statusCode) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        StringBuilder where = new StringBuilder("1=1");
        Map<String, Object> params = new HashMap<>();

        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (LOWER(materialNo) LIKE :kw OR LOWER(name) LIKE :kw)");
            params.put("kw", "%" + keyword.toLowerCase() + "%");
        }
        if (statusCode != null && !statusCode.isBlank()) {
            where.append(" AND statusCode = :statusCode");
            params.put("statusCode", statusCode);
        }

        String query = where + " ORDER BY createdAt DESC";
        long total = InternalMaterial.count(where.toString(), params);
        List<InternalMaterialDTO> content = InternalMaterial.find(query, params)
                .page(page, size)
                .<InternalMaterial>list()
                .stream()
                .map(InternalMaterialDTO::from)
                .collect(Collectors.toList());

        return new PageResult<>(content, page, size, total);
    }

    public InternalMaterialDTO getById(UUID id) {
        InternalMaterial m = InternalMaterial.findById(id);
        if (m == null) throw new BusinessException(404, "InternalMaterial not found: " + id);
        return InternalMaterialDTO.from(m);
    }

    @Transactional
    public InternalMaterialDTO create(CreateInternalMaterialRequest request) {
        long existing = InternalMaterial.count("materialNo = ?1", request.materialNo);
        if (existing > 0) throw new BusinessException(400, "materialNo already exists: " + request.materialNo);

        InternalMaterial m = new InternalMaterial();
        m.materialNo = request.materialNo;
        m.name = request.name;
        m.specification = request.specification;
        m.size = request.size;
        if (request.statusCode != null) m.statusCode = request.statusCode;
        m.persist();

        LOG.infof("Created internal material id=%s materialNo=%s", m.id, m.materialNo);
        return InternalMaterialDTO.from(m);
    }

    @Transactional
    public InternalMaterialDTO update(UUID id, CreateInternalMaterialRequest request) {
        InternalMaterial m = InternalMaterial.findById(id);
        if (m == null) throw new BusinessException(404, "InternalMaterial not found: " + id);

        if (request.materialNo != null && !request.materialNo.equals(m.materialNo)) {
            long existing = InternalMaterial.count("materialNo = ?1 AND id != ?2", request.materialNo, id);
            if (existing > 0) throw new BusinessException(400, "materialNo already exists: " + request.materialNo);
            m.materialNo = request.materialNo;
        }
        if (request.name != null) m.name = request.name;
        if (request.specification != null) m.specification = request.specification;
        if (request.size != null) m.size = request.size;
        if (request.statusCode != null) m.statusCode = request.statusCode;

        return InternalMaterialDTO.from(m);
    }

    @Transactional
    public void delete(UUID id) {
        InternalMaterial m = InternalMaterial.findById(id);
        if (m == null) throw new BusinessException(404, "InternalMaterial not found: " + id);

        long refs = CustomerMaterialMapping.count("materialId = ?1", id);
        if (refs > 0) throw new BusinessException(400, "Cannot delete: material is referenced by customer mappings");

        m.delete();
        LOG.infof("Deleted internal material id=%s", id);
    }

    @Transactional
    public int importFromExcel(InputStream inputStream) {
        int count = 0;
        try (Workbook wb = WorkbookFactory.create(inputStream)) {
            Sheet sheet = wb.getSheetAt(0);
            // Row 0 = header, data starts from row 1
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String materialNo = getCellStringValue(row, 0);
                String name = getCellStringValue(row, 1);
                if (materialNo == null || materialNo.isBlank()) continue;
                if (name == null || name.isBlank()) continue;

                long existing = InternalMaterial.count("materialNo = ?1", materialNo);
                if (existing > 0) continue; // skip duplicates

                InternalMaterial m = new InternalMaterial();
                m.materialNo = materialNo;
                m.name = name;
                m.specification = getCellStringValue(row, 2);
                m.size = getCellStringValue(row, 3);
                String status = getCellStringValue(row, 4);
                m.statusCode = (status != null && !status.isBlank()) ? status : "Y";
                m.persist();
                count++;
            }
        } catch (Exception e) {
            throw new BusinessException(400, "Failed to parse Excel: " + e.getMessage());
        }
        LOG.infof("Imported %d internal materials from Excel", count);
        return count;
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
