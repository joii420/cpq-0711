package com.cpq.configure.service;

import com.cpq.configure.dto.MaterialRecipeDTO;
import com.cpq.configure.dto.MaterialRecipeElementDTO;
import com.cpq.configure.entity.MaterialRecipe;
import com.cpq.configure.entity.MaterialRecipeElement;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
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
            throw new IllegalArgumentException("material_recipe 不存在: " + id);
        }
        MaterialRecipeDTO dto = toDTOLite(r);
        dto.elements = MaterialRecipeElement.<MaterialRecipeElement>find(
                "recipeId = ?1 ORDER BY sortOrder", r.id).list()
            .stream().map(this::toElemDTO).collect(Collectors.toList());
        return dto;
    }

    private MaterialRecipeDTO toDTOLite(MaterialRecipe r) {
        MaterialRecipeDTO d = new MaterialRecipeDTO();
        d.id = r.id;
        d.code = r.code;
        d.symbol = r.symbol;
        d.name = r.name;
        d.specLabel = r.specLabel;
        d.recipeType = r.recipeType;
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
