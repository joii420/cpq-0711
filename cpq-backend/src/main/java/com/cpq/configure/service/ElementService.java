package com.cpq.configure.service;

import com.cpq.common.exception.BusinessException;
import com.cpq.configure.dto.ElementDTO;
import com.cpq.configure.dto.ElementUpsertRequest;
import com.cpq.configure.entity.Element;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 元素主表管理服务（task-0709 / BL-0040）。
 *
 * <p>模型 B：{@code element_no}=不可改业务主键；{@code element_code}=符号（被引用即锁）；
 * {@code element_name}=中文（随时可改）。只软删（status=INACTIVE），永不物理删。
 */
@ApplicationScoped
public class ElementService {

    @Inject
    EntityManager em;

    /**
     * GET /elements?keyword= — 列表 + referencedCount + 排序。
     * 单条 SQL：LEFT JOIN material_recipe_element（按 element_no）聚合被引用数，禁 N+1。
     * 排序：启用优先 → 修改时间倒序 → 创建时间倒序。返回全状态。
     */
    @SuppressWarnings("unchecked")
    public List<ElementDTO> list(String keyword) {
        boolean hasKw = keyword != null && !keyword.isBlank();
        StringBuilder sql = new StringBuilder(
            "SELECT e.id, e.element_no, e.element_code, e.element_name, e.status, " +
            "       e.created_at, e.updated_at, COUNT(mre.id) AS ref_count " +
            "FROM element e " +
            "LEFT JOIN material_recipe_element mre ON mre.element_no = e.element_no ");
        if (hasKw) {
            sql.append("WHERE (e.element_no ILIKE :kw OR e.element_code ILIKE :kw OR e.element_name ILIKE :kw) ");
        }
        sql.append("GROUP BY e.id, e.element_no, e.element_code, e.element_name, e.status, e.created_at, e.updated_at ")
           .append("ORDER BY (e.status = 'ACTIVE') DESC, e.updated_at DESC, e.created_at DESC");

        var q = em.createNativeQuery(sql.toString());
        if (hasKw) q.setParameter("kw", "%" + keyword.trim() + "%");
        List<Object[]> rows = q.getResultList();

        List<ElementDTO> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            ElementDTO d = new ElementDTO();
            d.id = r[0] instanceof UUID u ? u : (r[0] != null ? UUID.fromString(r[0].toString()) : null);
            d.elementNo = (String) r[1];
            d.elementCode = (String) r[2];
            d.elementName = (String) r[3];
            d.status = (String) r[4];
            d.createdAt = toOffsetDateTime(r[5]);
            d.updatedAt = toOffsetDateTime(r[6]);
            d.referencedCount = ((Number) r[7]).longValue();
            d.codeLocked = d.referencedCount > 0;
            out.add(d);
        }
        return out;
    }

    @Transactional
    public ElementDTO create(ElementUpsertRequest req) {
        if (req == null) throw new IllegalArgumentException("request body 必填");
        String no = trimOrNull(req.elementNo);
        String code = trimOrNull(req.elementCode);
        String name = trimOrNull(req.elementName);
        if (no == null) throw new IllegalArgumentException("elementNo 必填");
        if (code == null) throw new IllegalArgumentException("elementCode 必填");
        if (name == null) throw new IllegalArgumentException("elementName 必填");
        if (Element.find("elementNo", no).firstResult() != null)
            throw new BusinessException(409, "元素编号已存在: " + no);
        if (Element.find("elementCode", code).firstResult() != null)
            throw new BusinessException(409, "符号已存在: " + code);

        Element e = new Element();
        e.elementNo = no;
        e.elementCode = code;
        e.elementName = name;
        e.status = normalizeStatus(req.status, "ACTIVE");
        e.createdAt = OffsetDateTime.now();
        e.updatedAt = OffsetDateTime.now();
        e.persist();
        return toDTO(e, 0L);
    }

    @Transactional
    public ElementDTO update(String elementNo, ElementUpsertRequest req) {
        if (req == null) throw new IllegalArgumentException("request body 必填");
        Element e = Element.<Element>find("elementNo", elementNo).firstResult();
        if (e == null) throw new NotFoundException("元素不存在: " + elementNo);

        long refCount = countReferences(e.elementNo);

        // 符号锁：被引用 + 改符号 → 409；未引用可改但须唯一。element_no 不可改（忽略 req.elementNo）。
        String newCode = trimOrNull(req.elementCode);
        if (newCode != null && !newCode.equals(e.elementCode)) {
            if (refCount > 0) {
                throw new BusinessException(409, "符号已被 " + refCount + " 个材质引用，不可修改");
            }
            Element dup = Element.<Element>find("elementCode", newCode).firstResult();
            if (dup != null && !dup.id.equals(e.id)) {
                throw new BusinessException(409, "符号已存在: " + newCode);
            }
            e.elementCode = newCode;
        }

        // element_name / status 随时可改
        String newName = trimOrNull(req.elementName);
        if (newName != null) e.elementName = newName;
        if (req.status != null) e.status = normalizeStatus(req.status, e.status);

        e.updatedAt = OffsetDateTime.now();
        e.persist();
        return toDTO(e, refCount);
    }

    /** DELETE /elements/{elementNo} — 软删（status→INACTIVE，幂等）。不物理删、不动 material_recipe_element。 */
    @Transactional
    public void softDelete(String elementNo) {
        Element e = Element.<Element>find("elementNo", elementNo).firstResult();
        if (e == null) throw new NotFoundException("元素不存在: " + elementNo);
        if (!"INACTIVE".equals(e.status)) {
            e.status = "INACTIVE";
            e.updatedAt = OffsetDateTime.now();
            e.persist();
        }
    }

    // ── helpers ──

    private long countReferences(String elementNo) {
        Number n = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM material_recipe_element WHERE element_no = :no")
            .setParameter("no", elementNo)
            .getSingleResult();
        return n.longValue();
    }

    private ElementDTO toDTO(Element e, long refCount) {
        ElementDTO d = new ElementDTO();
        d.id = e.id;
        d.elementNo = e.elementNo;
        d.elementCode = e.elementCode;
        d.elementName = e.elementName;
        d.status = e.status;
        d.referencedCount = refCount;
        d.codeLocked = refCount > 0;
        d.createdAt = e.createdAt;
        d.updatedAt = e.updatedAt;
        return d;
    }

    private String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String normalizeStatus(String s, String fallback) {
        if (s == null || s.isBlank()) return fallback;
        String u = s.trim().toUpperCase();
        if (!u.equals("ACTIVE") && !u.equals("INACTIVE")) {
            throw new IllegalArgumentException("status 必须为 ACTIVE/INACTIVE");
        }
        return u;
    }

    private OffsetDateTime toOffsetDateTime(Object o) {
        if (o == null) return null;
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        if (o instanceof java.time.Instant i) return i.atOffset(java.time.ZoneOffset.UTC);
        return null;
    }
}
