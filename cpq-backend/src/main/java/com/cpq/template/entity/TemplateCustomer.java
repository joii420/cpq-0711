package com.cpq.template.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.*;
import java.util.stream.Collectors;

@Entity
@Table(name = "template_customer")
public class TemplateCustomer extends PanacheEntityBase {
    @EmbeddedId
    public Id id;

    @Embeddable
    public static class Id {
        @Column(name = "template_id")
        public UUID templateId;
        @Column(name = "customer_id")
        public UUID customerId;

        public Id() {}

        public Id(UUID templateId, UUID customerId) {
            this.templateId = templateId;
            this.customerId = customerId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Id other
                    && Objects.equals(templateId, other.templateId)
                    && Objects.equals(customerId, other.customerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(templateId, customerId);
        }
    }

    public static List<UUID> customerIds(UUID templateId) {
        return list("id.templateId = ?1 order by id.customerId", templateId).stream()
                .map(x -> ((TemplateCustomer) x).id.customerId)
                .collect(Collectors.toList());
    }
}
