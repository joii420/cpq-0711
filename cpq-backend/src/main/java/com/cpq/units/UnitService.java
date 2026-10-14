package com.cpq.units;

import com.cpq.common.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;
import static com.cpq.units.UnitDtos.*;

/** Database-backed unit catalogue. All code comparisons intentionally use lower(trim(code)). */
@ApplicationScoped
public class UnitService {
    @Inject EntityManager em;

    @PostConstruct
    void warmConversionCache() {
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery("SELECT s.code,t.code,r.factor FROM unit_conversion_rate r JOIN unit_definition s ON s.id=r.source_unit_id JOIN unit_definition t ON t.id=r.target_unit_id WHERE s.enabled AND t.enabled").getResultList();
            Map<String, Map<String, BigDecimal>> grouped = new HashMap<>();
            for (Object[] r : rows) grouped.computeIfAbsent(String.valueOf(r[0]), k -> new HashMap<>()).put(String.valueOf(r[1]), (BigDecimal) r[2]);
            com.cpq.engine.unit.UnitConversion.clearRuntimeRelations();
            grouped.forEach(com.cpq.engine.unit.UnitConversion::replaceSourceRelations);
        } catch (RuntimeException ignored) {
            // Flyway may still be starting in tests; the built-in seed remains a safe fallback.
        }
    }

    @SuppressWarnings("unchecked")
    public UnitList list(String category, Boolean enabled, int page, int size) {
        return list(category, enabled, null, page, size);
    }

    @SuppressWarnings("unchecked")
    public UnitList list(String category, Boolean enabled, String keyword, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT u.id,u.code,u.category,u.enabled,u.sort_order,u.version,u.created_at,u.updated_at,(SELECT count(*) FROM unit_conversion_rate r WHERE r.source_unit_id=u.id) FROM unit_definition u WHERE 1=1 ");
        if (category != null && !category.isBlank()) sql.append("AND u.category=:category ");
        if (enabled != null) sql.append("AND u.enabled=:enabled ");
        if (keyword != null && !keyword.isBlank()) sql.append("AND lower(u.code) LIKE :keyword ");
        sql.append("ORDER BY u.sort_order,u.id");
        Query q = em.createNativeQuery(sql.toString());
        if (category != null && !category.isBlank()) q.setParameter("category", category.trim().toUpperCase(Locale.ROOT));
        if (enabled != null) q.setParameter("enabled", enabled);
        if (keyword != null && !keyword.isBlank()) q.setParameter("keyword", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        String totalSql = "SELECT count(*) FROM unit_definition u WHERE 1=1 " +
                (category != null && !category.isBlank() ? "AND u.category=:category " : "") +
                (enabled != null ? "AND u.enabled=:enabled " : "") +
                (keyword != null && !keyword.isBlank() ? "AND lower(u.code) LIKE :keyword" : "");
        Query totalQuery = em.createNativeQuery(totalSql);
        if (category != null && !category.isBlank()) totalQuery.setParameter("category", category.trim().toUpperCase(Locale.ROOT));
        if (enabled != null) totalQuery.setParameter("enabled", enabled);
        if (keyword != null && !keyword.isBlank()) totalQuery.setParameter("keyword", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        long total = ((Number) totalQuery.getSingleResult()).longValue();
        int safePage = Math.max(page, 0), safeSize = size <= 0 ? 50 : Math.min(size, 500);
        q.setFirstResult(safePage * safeSize); q.setMaxResults(safeSize);
        List<Object[]> rows = q.getResultList(); List<Unit> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) { Unit u = new Unit(); u.id=((Number)r[0]).longValue(); u.code=(String)r[1]; u.category=(String)r[2]; u.enabled=(Boolean)r[3]; u.sortOrder=((Number)r[4]).intValue(); u.version=((Number)r[5]).intValue(); u.createdAt=Objects.toString(r[6],null); u.updatedAt=Objects.toString(r[7],null); u.conversionCount=((Number)r[8]).longValue(); out.add(u); }
        return new UnitList(total, out);
    }

    @Transactional
    public Unit create(UnitUpsert req) {
        String code = normalizeCode(req == null ? null : req.code); String category = normalizeCategory(req == null ? null : req.category);
        if (code.isEmpty()) throw new BusinessException(400,"单位代码不能为空");
        if (existsCode(code)) throw new BusinessException(400,"单位代码已存在: " + code);
        boolean enabled = Boolean.TRUE.equals(req.enabled);
        if (enabled) throw new BusinessException(400,"新单位需先补齐同类型有向关系后才能启用");
        Number id = (Number) em.createNativeQuery("INSERT INTO unit_definition(code,category,enabled,sort_order,created_by) VALUES (:code,:category,false,:sort,:by) RETURNING id")
                .setParameter("code", code).setParameter("category", category).setParameter("sort", req == null || req.sortOrder == null ? 0 : req.sortOrder).setParameter("by", actor()).getSingleResult();
        return get(id.longValue());
    }

    @Transactional
    public Unit update(long id, UnitUpdate req) {
        if (req == null || req.version == null) throw new BusinessException(400,"version 不能为空");
        String code = normalizeCode(req.code); if (code.isEmpty()) throw new BusinessException(400,"单位代码不能为空");
        if (existsCodeOtherThan(code,id)) throw new BusinessException(400,"单位代码已存在: " + code);
        int n = em.createNativeQuery("UPDATE unit_definition SET code=:code,sort_order=:sort,version=version+1,updated_at=now(),updated_by=:by WHERE id=:id AND version=:version")
                .setParameter("code",code).setParameter("sort",req.sortOrder == null ? 0 : req.sortOrder).setParameter("by",actor()).setParameter("id",id).setParameter("version",req.version).executeUpdate();
        if (n == 0) throw versionOrNotFound(id, req.version);
        return get(id);
    }

    @Transactional
    public void disable(long id, Integer version) {
        if (version == null) throw new BusinessException(400,"version 不能为空");
        int n = em.createNativeQuery("UPDATE unit_definition SET enabled=false,version=version+1,updated_at=now(),updated_by=:by WHERE id=:id AND version=:version")
                .setParameter("by",actor()).setParameter("id",id).setParameter("version",version).executeUpdate();
        if (n == 0) throw versionOrNotFound(id,version);
        Object code = em.createNativeQuery("SELECT code FROM unit_definition WHERE id=:id").setParameter("id", id).getResultStream().findFirst().orElse(null);
        if (code != null) com.cpq.engine.unit.UnitConversion.removeUnit(code.toString());
    }

    public Unit get(long id) {
        List<?> result = em.createNativeQuery("SELECT u.id,u.code,u.category,u.enabled,u.sort_order,u.version,u.created_at,u.updated_at,(SELECT count(*) FROM unit_conversion_rate x WHERE x.source_unit_id=u.id) FROM unit_definition u WHERE u.id=:id").setParameter("id",id).getResultList();
        if (result.isEmpty()) throw new BusinessException(404, "单位不存在");
        Object[] r = (Object[]) result.get(0);
        Unit u = new Unit(); u.id=((Number)r[0]).longValue(); u.code=(String)r[1]; u.category=(String)r[2]; u.enabled=(Boolean)r[3]; u.sortOrder=((Number)r[4]).intValue(); u.version=((Number)r[5]).intValue(); u.createdAt=Objects.toString(r[6],null); u.updatedAt=Objects.toString(r[7],null); u.conversionCount=((Number)r[8]).longValue(); return u;
    }

    @SuppressWarnings("unchecked")
    public ConversionGroup conversions(long sourceId) {
        Unit source = get(sourceId);
        // 返回同类型的全部其它单位，缺失关系也要显式展示，便于停用/新建单位补齐有向关系。
        List<Object[]> rows = em.createNativeQuery("SELECT t.id,t.code,r.factor,COALESCE(r.version,0) FROM unit_definition t LEFT JOIN unit_conversion_rate r ON r.target_unit_id=t.id AND r.source_unit_id=:id WHERE t.category=:category AND t.id<>:id ORDER BY t.sort_order,t.id")
                .setParameter("id",sourceId).setParameter("category",source.category).getResultList();
        List<RateTarget> targets = new ArrayList<>(rows.size()); for (Object[] r: rows) targets.add(new RateTarget(((Number)r[0]).longValue(),(String)r[1],(BigDecimal)r[2],((Number)r[3]).intValue()));
        return new ConversionGroup(source,targets);
    }

    @Transactional
    public ConversionGroup saveConversions(long sourceId, ConversionSave req) {
        Unit source = get(sourceId);
        if (req == null || req.sourceVersion == null || req.targets == null) throw new BusinessException(400,"sourceVersion 和 targets 不能为空");
        if (!Objects.equals(req.sourceVersion, source.version)) throw new BusinessException(409,"数据已被其他用户更新，请刷新后重试");
        Set<Long> seen = new HashSet<>();
        for (RateTarget t : req.targets) {
            if (t == null || t.targetUnitId == null || t.factor == null || t.factor.signum() <= 0) throw new BusinessException(400,"目标单位及系数必须有效且系数大于 0");
            if (!seen.add(t.targetUnitId)) throw new BusinessException(400,"目标单位不能重复");
            Object[] x = unitPair(t.targetUnitId); if (x == null) throw new BusinessException(400,"目标单位不存在");
            if (!source.category.equals(x[1])) throw new BusinessException(400,"来源与目标必须属于同一单位类型");
            if (sourceId == t.targetUnitId) throw new BusinessException(400,"来源与目标不能相同");
        }
        long expected = ((Number)em.createNativeQuery("SELECT count(*) FROM unit_definition WHERE category=:cat AND enabled AND id<>:id").setParameter("cat",source.category).setParameter("id",sourceId).getSingleResult()).longValue();
        long enabledTargets = req.targets.stream().filter(t -> {
            Object enabled = em.createNativeQuery("SELECT enabled FROM unit_definition WHERE id=:id").setParameter("id", t.targetUnitId).getResultStream().findFirst().orElse(false);
            return Boolean.TRUE.equals(enabled);
        }).count();
        if (source.enabled && enabledTargets != expected) throw new BusinessException(400,"启用单位必须补齐同类型全部目标关系");
        int deleted = em.createNativeQuery("DELETE FROM unit_conversion_rate WHERE source_unit_id=:id").setParameter("id",sourceId).executeUpdate();
        for (RateTarget t: req.targets) em.createNativeQuery("INSERT INTO unit_conversion_rate(source_unit_id,target_unit_id,factor,created_by) VALUES (:s,:t,:f,:by)").setParameter("s",sourceId).setParameter("t",t.targetUnitId).setParameter("f",t.factor).setParameter("by",actor()).executeUpdate();
        Map<String, BigDecimal> runtime = new HashMap<>();
        for (RateTarget t : req.targets) {
            Object code = em.createNativeQuery("SELECT code FROM unit_definition WHERE id=:id").setParameter("id", t.targetUnitId)
                    .getResultStream().findFirst().orElse(null);
            if (code != null) runtime.put(code.toString(), t.factor);
        }
        com.cpq.engine.unit.UnitConversion.replaceSourceRelations(source.code, runtime);
        em.createNativeQuery("UPDATE unit_definition SET version=version+1,updated_at=now(),updated_by=:by WHERE id=:id AND version=:version").setParameter("by",actor()).setParameter("id",sourceId).setParameter("version",req.sourceVersion).executeUpdate();
        return conversions(sourceId);
    }

    /** Enables only when every currently enabled peer has a directed rate. */
    @Transactional public Unit enable(long id, Integer version) {
        Unit u=get(id); if (!Objects.equals(u.version,version)) throw new BusinessException(409,"数据已被其他用户更新，请刷新后重试");
        long peers=((Number)em.createNativeQuery("SELECT count(*) FROM unit_definition WHERE category=:cat AND id<>:id AND enabled").setParameter("cat",u.category).setParameter("id",id).getSingleResult()).longValue();
        long rates=((Number)em.createNativeQuery("SELECT count(*) FROM unit_conversion_rate r JOIN unit_definition t ON t.id=r.target_unit_id WHERE r.source_unit_id=:id AND t.enabled").setParameter("id",id).getSingleResult()).longValue();
        if (rates != peers) throw new BusinessException(400,"启用前必须补齐同类型全部目标关系");
        long incoming=((Number)em.createNativeQuery("SELECT count(*) FROM unit_conversion_rate r JOIN unit_definition s ON s.id=r.source_unit_id WHERE r.target_unit_id=:id AND s.enabled").setParameter("id",id).getSingleResult()).longValue();
        if (incoming != peers) throw new BusinessException(400,"启用前必须补齐其他同类型单位指向本单位的关系");
        em.createNativeQuery("UPDATE unit_definition SET enabled=true,version=version+1,updated_at=now(),updated_by=:by WHERE id=:id AND version=:version").setParameter("by",actor()).setParameter("id",id).setParameter("version",version).executeUpdate();
        warmConversionCache();
        return get(id);
    }

    public Optional<BigDecimal> factor(String sourceCode, String targetCode) {
        if (sourceCode == null || targetCode == null || sourceCode.isBlank() || targetCode.isBlank()) return Optional.empty();
        if (normalizeCode(sourceCode).equals(normalizeCode(targetCode))) return Optional.of(BigDecimal.ONE);
        Object v = em.createNativeQuery("SELECT r.factor FROM unit_conversion_rate r JOIN unit_definition s ON s.id=r.source_unit_id JOIN unit_definition t ON t.id=r.target_unit_id WHERE lower(btrim(s.code))=lower(btrim(:s)) AND lower(btrim(t.code))=lower(btrim(:t)) AND s.enabled AND t.enabled AND s.category=t.category").setParameter("s",sourceCode).setParameter("t",targetCode).getResultStream().findFirst().orElse(null);
        return v instanceof BigDecimal b ? Optional.of(b) : Optional.empty();
    }

    private Object[] unitPair(long id) { return (Object[])em.createNativeQuery("SELECT id,category FROM unit_definition WHERE id=:id").setParameter("id",id).getResultStream().findFirst().orElse(null); }
    private boolean existsCode(String code) { return ((Number)em.createNativeQuery("SELECT count(*) FROM unit_definition WHERE lower(btrim(code))=lower(btrim(:code))").setParameter("code",code).getSingleResult()).longValue()>0; }
    private boolean existsCodeOtherThan(String code,long id) { return ((Number)em.createNativeQuery("SELECT count(*) FROM unit_definition WHERE lower(btrim(code))=lower(btrim(:code)) AND id<>:id").setParameter("code",code).setParameter("id",id).getSingleResult()).longValue()>0; }
    private BusinessException versionOrNotFound(long id,int version) { boolean found=((Number)em.createNativeQuery("SELECT count(*) FROM unit_definition WHERE id=:id").setParameter("id",id).getSingleResult()).longValue()>0; return new BusinessException(found?409:404,found?"数据已被其他用户更新，请刷新后重试":"单位不存在"); }
    static String normalizeCode(String code) { return code == null ? "" : code.trim(); }
    private static String normalizeCategory(String c) { String x=c==null?"":c.trim().toUpperCase(Locale.ROOT); if (!x.equals("QUANTITY")&&!x.equals("WEIGHT")) throw new BusinessException(400,"单位类型必须为 QUANTITY 或 WEIGHT"); return x; }
    private String actor() { return "api"; }
}
