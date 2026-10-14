package com.cpq.units;

import java.math.BigDecimal;
import java.util.List;

public final class UnitDtos {
    private UnitDtos() {}

    public static class Unit {
        public Long id; public String code; public String category;
        public long conversionCount; public boolean enabled; public int sortOrder;
        public int version; public String createdAt; public String updatedAt;
    }
    public static class UnitList {
        public long total;
        public List<Unit> items;
        public UnitList() {}
        public UnitList(long total, List<Unit> items) { this.total = total; this.items = items; }
    }
    public static class UnitUpsert { public String code; public String category; public Boolean enabled; public Integer sortOrder; }
    public static class UnitUpdate { public String code; public Integer sortOrder; public Integer version; }
    public static class VersionRequest { public Integer version; }
    public static class RateTarget {
        public Long targetUnitId; public String targetUnitCode; public BigDecimal factor;
        public Integer version;
        public RateTarget() {}
        public RateTarget(Long id, String code, BigDecimal factor, Integer version) { this.targetUnitId=id; this.targetUnitCode=code; this.factor=factor; this.version=version; }
    }
    public static class ConversionGroup {
        public Unit sourceUnit; public List<RateTarget> targets;
        public ConversionGroup() {}
        public ConversionGroup(Unit source, List<RateTarget> targets) { this.sourceUnit=source; this.targets=targets; }
    }
    public static class ConversionSave { public Integer sourceVersion; public List<RateTarget> targets; }
    public static class SettlementUnitRequest { public String settlementUnit; public Integer version; }
}
