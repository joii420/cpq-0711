package com.cpq.notification.service;

import com.cpq.notification.entity.Notification;
import com.cpq.system.entity.User;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationService.class);

    @Inject
    Mailer mailer;

    public List<Notification> listByRecipient(UUID recipientId, int page, int size) {
        page = com.cpq.common.dto.Pagination.clampPage(page);
        size = com.cpq.common.dto.Pagination.clampSize(size);
        return Notification.find("recipientId = ?1 ORDER BY createdAt DESC", recipientId)
                .page(page, size)
                .list();
    }

    public long getUnreadCount(UUID recipientId) {
        return Notification.count("recipientId = ?1 AND isRead = false", recipientId);
    }

    @Transactional
    public void markRead(UUID id) {
        Notification notification = Notification.findById(id);
        if (notification != null && !notification.isRead) {
            notification.isRead = true;
            notification.readAt = OffsetDateTime.now();
            LOG.debugf("Marked notification id=%s as read", id);
        }
    }

    @Transactional
    public void markAllRead(UUID recipientId) {
        long updated = Notification.update(
                "isRead = true, readAt = ?1 WHERE recipientId = ?2 AND isRead = false",
                OffsetDateTime.now(), recipientId);
        LOG.infof("Marked all notifications read for recipientId=%s count=%d", recipientId, updated);
    }

    @Transactional
    public Notification create(UUID recipientId, String type, String title, String content,
                                String link, String relatedType, UUID relatedId) {
        Notification notification = new Notification();
        notification.recipientId = recipientId;
        notification.type = type;
        notification.title = title;
        notification.content = content;
        notification.link = link;
        notification.relatedType = relatedType;
        notification.relatedId = relatedId;
        notification.persist();
        LOG.infof("Created notification type=%s recipientId=%s", type, recipientId);

        // Try to send email asynchronously — do not block if it fails
        try {
            User recipient = User.findById(recipientId);
            if (recipient != null && recipient.email != null) {
                mailer.send(Mail.withText(
                    recipient.email,
                    title,
                    content != null ? content : title
                ));
            }
        } catch (Exception e) {
            LOG.warnf("Failed to send notification email to user %s: %s", recipientId, e.getMessage());
        }

        return notification;
    }
}
