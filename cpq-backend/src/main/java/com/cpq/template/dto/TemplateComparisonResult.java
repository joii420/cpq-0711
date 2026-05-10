package com.cpq.template.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TemplateComparisonResult {

    public UUID templateAId;
    public UUID templateBId;
    public String templateAName;
    public String templateBName;
    public String templateAVersion;
    public String templateBVersion;

    public MetadataDiff metadata;
    public AttributesDiff productAttributes;
    public ComponentsDiff components;
    public Stats stats;

    public static class MetadataDiff {
        public FieldChange name;
        public FieldChange version;
        public FieldChange category;
        public FieldChange description;
    }

    public static class FieldChange {
        public Object valueA;
        public Object valueB;
        public boolean changed;

        public FieldChange(Object a, Object b) {
            this.valueA = a;
            this.valueB = b;
            this.changed = !objectsEqual(a, b);
        }

        private static boolean objectsEqual(Object a, Object b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            return a.equals(b);
        }
    }

    public static class AttributesDiff {
        public List<Map<String, Object>> added;
        public List<Map<String, Object>> removed;
        public List<AttributeChange> modified;
    }

    public static class AttributeChange {
        public String fieldName;
        public Object valueA;
        public Object valueB;
    }

    public static class ComponentsDiff {
        public List<Map<String, Object>> addedTabs;
        public List<Map<String, Object>> removedTabs;
        public List<TabChange> modifiedTabs;
    }

    public static class TabChange {
        public String tabName;
        public String componentId;
        public List<FieldChange> fieldChanges;
        public List<String> addedFields;
        public List<String> removedFields;
    }

    public static class Stats {
        public int totalDiffs;
        public int added;
        public int removed;
        public int modified;
        public double similarityPercent;
    }
}
