package com.cpq.notification.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class Notification extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(name = "recipient_id", nullable = false)
    public UUID recipientId;

    @Column(nullable = false, length = 50)
    public String type;

    @Column(nullable = false, length = 500)
    public String title;

    @Column
    public String content;

    @Column(length = 500)
    public String link;

    @Column(name = "related_type", length = 50)
    public String relatedType;

    @Column(name = "related_id")
    public UUID relatedId;

    @Column(name = "is_read", nullable = false)
    public boolean isRead = false;

    @Column(name = "read_at")
    public OffsetDateTime readAt;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
