package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeElementDTO;
import com.cpq.configure.dto.MaterialRecipeUpsertRequest;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeElement;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class MaterialRecipeService {

    /** GET /material-recipes — 列表(不带 elements). */
    public List<MaterialRecipeDTO> listActive() {
        return MaterialRecipe.<MaterialRecipe>find(
                "status = 'ACTIVE' ORDER BY sortOrder").list()
            .stream().map(this::toDTOLite).collect(Collectors.toList());
    }

    /** GET /material-recipes/{id} — 详情(带 elements). */
    public MaterialRecipeDTO getDetail(UUID id) {
        MaterialRecipe r = MaterialRecipe.findById(id);
        if (r == null) {
            throw new NotFoundException("material_recipe 不存在: " + id);
        }
        MaterialRecipeDTO dto = toDTOLite(r);
        dto.elements = MaterialRecipeElement.<MaterialRecipeElement>find(
                "recipeId = ?1 ORDER BY sortOrder", r.id).list()
            .stream().map(this::toElemDTO).collect(Collectors.toList());
        return dto;
    }

    // ── CRUD methods ──

    @Transactional
    public MaterialRecipeDTO create(MaterialRecipeUpsertRequest req) {
        validateUpsert(req, null);
        MaterialRecipe r = new MaterialRecipe();
        r.code = req.code.trim();
        r.symbol = req.symbol.trim();
        r.name = req.name.trim();
        r.specLabel = req.specLabel;
        r.recipeType = req.recipeType;
        r.sortOrder = req.sortOrder == null ? 0 : req.sortOrder;
        r.status = req.status == null ? "ACTIVE" : req.status;
        r.createdAt = OffsetDateTime.now();
        r.updatedAt = OffsetDateTime.now();
        r.persist();

        if (req.elements != null) {
            int seq = 1;
            for (MaterialRecipeUpsertRequest.ElementUpsert e : req.elements) {
                insertElement(r.id, e, seq++);
            }
        }
        return getDetail(r.id);
    }

    @Transactional
    public MaterialRecipeDTO update(UUID id, MaterialRecipeUpsertRequest req) {
        validateUpsert(req, id);
        MaterialRecipe r = MaterialRecipe.findById(id);
        if (r == null) throw new NotFoundException("material_recipe 不存在: " + id);

        r.code = req.code.trim();
        r.symbol = req.symbol.trim();
        r.name = req.name.trim();
        r.specLabel = req.specLabel;
        r.recipeType = req.recipeType;
        r.sortOrder = req.sortOrder == null ? r.sortOrder : req.sortOrder;
        r.status = req.status == null ? r.status : req.status;
        r.updatedAt = OffsetDateTime.now();
        r.persist();

        // 完全替换 elements (简单可靠;副作用:配过的 element id 会变 — 业务上 element 是 immutable 子项)
        MaterialRecipeElement.delete("recipeId", id);
        if (req.elements != null) {
            int seq = 1;
            for (MaterialRecipeUpsertRequest.ElementUpsert e : req.elements) {
                insertElement(id, e, seq++);
            }
        }
        return getDetail(id);
    }

    @Transactional
    public void deleteSoft(UUID id) {
        MaterialRecipe r = MaterialRecipe.findById(id);
        if (r == null) throw new NotFoundException("material_recipe 不存在: " + id);
        r.status = "INACTIVE";
        r.updatedAt = OffsetDateTime.now();
        r.persist();
    }

    private void insertElement(UUID recipeId,
                               MaterialRecipeUpsertRequest.ElementUpsert e,
                               int seq) {
        MaterialRecipeElement el = new MaterialRecipeElement();
        el.recipeId = recipeId;
        el.elementCode = e.elementCode.trim();
        el.elementName = e.elementName == null ? e.elementCode.trim() : e.elementName.trim();
        el.defaultPct = e.defaultPct;
        el.minPct = e.minPct;
        el.maxPct = e.maxPct;
        el.isLocked = e.isLocked != null && e.isLocked;
        el.sortOrder = e.sortOrder == null ? seq : e.sortOrder;
        el.createdAt = OffsetDateTime.now();
        el.persist();
    }

    private void validateUpsert(MaterialRecipeUpsertRequest req, UUID idForUpdate) {
        if (req == null) throw new IllegalArgumentException("request body 必填");
        if (req.code == null || req.code.isBlank()) throw new IllegalArgumentException("code 必填");
        if (req.symbol == null || req.symbol.isBlank()) throw new IllegalArgumentException("symbol 必填");
        if (req.name == null || req.name.isBlank()) throw new IllegalArgumentException("name 必填");
        if (req.recipeType == null
            || !List.of("locked", "editable", "partial").contains(req.recipeType)) {
            throw new IllegalArgumentException("recipeType 必须为 locked/editable/partial");
        }
        if (req.status != null
            && !List.of("ACTIVE", "INACTIVE").contains(req.status)) {
            throw new IllegalArgumentException("status 必须为 ACTIVE/INACTIVE");
        }

        // code 唯一性
        String trimmed = req.code.trim();
        MaterialRecipe dup = MaterialRecipe.find("code = ?1", trimmed).firstResult();
        if (dup != null && (idForUpdate == null || !dup.id.equals(idForUpdate))) {
            throw new IllegalArgumentException("code 已存在: " + trimmed);
        }

        // 校验 elements
        if (req.elements == null || req.elements.isEmpty()) {
            throw new IllegalArgumentException("elements 至少 1 项");
        }
        Set<String> codes = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (MaterialRecipeUpsertRequest.ElementUpsert e : req.elements) {
            if (e.elementCode == null || e.elementCode.isBlank()) {
                throw new IllegalArgumentException("element.elementCode 必填");
            }
            if (e.defaultPct == null) {
                throw new IllegalArgumentException("element.defaultPct 必填: " + e.elementCode);
            }
            if (!codes.add(e.elementCode.trim())) {
                throw new IllegalArgumentException("element.elementCode 重复: " + e.elementCode);
            }
            sum = sum.add(e.defaultPct);

            boolean locked = e.isLocked != null && e.isLocked;
            // recipeType=locked: 所有元素必须 is_locked=true, min/max 必须 NULL
            if ("locked".equals(req.recipeType)) {
                if (!locked) throw new IllegalArgumentException(
                    "recipeType=locked 时所有元素必须 isLocked=true: " + e.elementCode);
                if (e.minPct != null || e.maxPct != null) throw new IllegalArgumentException(
                    "locked 元素 min/max 必须为 NULL: " + e.elementCode);
            }
            // recipeType=editable: 所有元素必须 is_locked=false, min/max 必须有
            if ("editable".equals(req.recipeType)) {
                if (locked) throw new IllegalArgumentException(
                    "recipeType=editable 时所有元素必须 isLocked=false: " + e.elementCode);
                if (e.minPct == null || e.maxPct == null) throw new IllegalArgumentException(
                    "editable 元素必须填 min/max: " + e.elementCode);
                if (e.minPct.compareTo(e.maxPct) > 0) throw new IllegalArgumentException(
                    "min > max: " + e.elementCode);
            }
            // recipeType=partial: 每个元素分别 — locked 元素 min/max=NULL, unlocked 元素 min/max 必填
            if ("partial".equals(req.recipeType)) {
                if (locked) {
                    if (e.minPct != null || e.maxPct != null) throw new IllegalArgumentException(
                        "partial 中 locked 元素 min/max 必须为 NULL: " + e.elementCode);
                } else {
                    if (e.minPct == null || e.maxPct == null) throw new IllegalArgumentException(
                        "partial 中 unlocked 元素必须填 min/max: " + e.elementCode);
                    if (e.minPct.compareTo(e.maxPct) > 0) throw new IllegalArgumentException(
                        "min > max: " + e.elementCode);
                }
            }
        }
        // 默认含量之和必须为 100(±0.01)
        if (sum.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw new IllegalArgumentException(
                "元素 default_pct 之和必须 = 100, 当前: " + sum);
        }
    }

    private MaterialRecipeDTO toDTOLite(MaterialRecipe r) {
        MaterialRecipeDTO d = new MaterialRecipeDTO();
        d.id = r.id;
        d.code = r.code;
        d.symbol = r.symbol;
        d.name = r.name;
        d.specLabel = r.specLabel;
        d.recipeType = r.recipeType;
        d.status = r.status;
        d.sortOrder = r.sortOrder;
        return d;
    }

    private MaterialRecipeElementDTO toElemDTO(MaterialRecipeElement e) {
        MaterialRecipeElementDTO d = new MaterialRecipeElementDTO();
        d.elementCode = e.elementCode;
        d.elementName = e.elementName;
        d.defaultPct = e.defaultPct;
        d.minPct = e.minPct;
        d.maxPct = e.maxPct;
        d.isLocked = e.isLocked;
        d.sortOrder = e.sortOrder;
        return d;
    }
}
