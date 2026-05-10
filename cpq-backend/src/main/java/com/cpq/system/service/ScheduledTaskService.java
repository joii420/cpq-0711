package com.cpq.system.service;

import com.cpq.importexcel.entity.ImportRecord;
import com.cpq.notification.entity.Notification;
import com.cpq.system.config.entity.SystemConfig;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ScheduledTaskService {

    private static final Logger LOG = Logger.getLogger(ScheduledTaskService.class);

    @Inject
    EntityManager em;

    /**
     * Mark quotations as EXPIRED daily at 00:30.
     * Targets SENT and APPROVED quotations whose expiry_date has passed.
     */
    @Scheduled(cron = "30 0 * * *")
    @Transactional
    public void markExpiredQuotations() {
        int updated = em.createNativeQuery(
                "UPDATE quotation SET status = 'EXPIRED', updated_at = now() " +
                "WHERE status IN ('SENT', 'APPROVED') AND expiry_date < CURRENT_DATE")
                .executeUpdate();
        LOG.infof("markExpiredQuotations: updated=%d", updated);
    }

    /**
     * Mark pricing strategies as EXPIRED daily at 01:00.
     * Targets ACTIVE strategies with a past expiration_date.
     */
    @Scheduled(cron = "0 1 * * *")
    @Transactional
    public void markExpiredStrategies() {
        int updated = em.createNativeQuery(
                "UPDATE pricing_strategy SET status = 'EXPIRED', updated_at = now() " +
                "WHERE status = 'ACTIVE' AND expiration_date IS NOT NULL AND expiration_date < CURRENT_DATE")
                .executeUpdate();
        LOG.infof("markExpiredStrategies: updated=%d", updated);
    }

    /**
     * Clean up expired password reset tokens daily at 03:00.
     */
    @Scheduled(cron = "0 3 * * *")
    @Transactional
    public void cleanExpiredTokens() {
        int deleted = em.createNativeQuery(
                "DELETE FROM password_reset_token WHERE expires_at < NOW()")
                .executeUpdate();
        LOG.infof("cleanExpiredTokens: deleted=%d", deleted);
    }

    /**
     * Clean notifications older than 6 months every Monday at 04:00.
     */
    @Scheduled(cron = "0 4 * * 1")
    @Transactional
    public void cleanOldNotifications() {
        int deleted = em.createNativeQuery(
                "DELETE FROM notification WHERE created_at < NOW() - INTERVAL '6 months'")
                .executeUpdate();
        LOG.infof("cleanOldNotifications: deleted=%d", deleted);
    }

    /**
     * CL-RETENTION-07: Purge basic_data_change_log rows older than the configured retention period.
     * Runs on the 1st of each month at 03:00.
     * Retention years is read from system_config key 'retention.change_log_years' (default 5).
     */
    @Scheduled(cron = "0 3 1 * *")
    @Transactional
    public void cleanupChangeLog() {
        int years = getRetentionYears("retention.change_log_years", 5);
        int deleted = em.createNativeQuery(
                "DELETE FROM basic_data_change_log WHERE changed_at < NOW() - (INTERVAL '1 year' * :years)")
                .setParameter("years", years)
                .executeUpdate();
        LOG.infof("cleanupChangeLog: deleted=%d (retention=%d years)", deleted, years);
    }

    /**
     * QIMP-RETENTION-19: Delete physical Excel import files and null-out the path in DB
     * for ImportRecord rows older than the configured retention period.
     * Runs on the 1st of each month at 03:30.
     * Retention months is read from system_config key 'retention.original_excel_months' (default 12).
     */
    @Scheduled(cron = "30 3 1 * *")
    @Transactional
    public void cleanupImportFiles() {
        int months = getRetentionMonths("retention.original_excel_months", 12);
        @SuppressWarnings("unchecked")
        List<ImportRecord> oldRecords = em.createNativeQuery(
                "SELECT * FROM import_record WHERE original_file_path IS NOT NULL " +
                "AND created_at < NOW() - (INTERVAL '1 month' * :months)",
                ImportRecord.class)
                .setParameter("months", months)
                .getResultList();
        int processed = 0;
        for (ImportRecord record : oldRecords) {
            try {
                Files.deleteIfExists(Path.of(record.originalFilePath));
            } catch (Exception e) {
                LOG.warnf("cleanupImportFiles: failed to delete file %s: %s", record.originalFilePath, e.getMessage());
            }
            record.originalFilePath = null;
            record.persist();
            processed++;
        }
        LOG.infof("cleanupImportFiles: processed=%d (retention=%d months)", processed, months);
    }

    // ---- helpers ----

    private int getRetentionYears(String key, int defaultValue) {
        try {
            SystemConfig cfg = SystemConfig.findByKey(key);
            if (cfg != null && cfg.configValue != null) {
                return Integer.parseInt(cfg.configValue.trim());
            }
        } catch (Exception e) {
            LOG.warnf("Failed to read system_config key=%s, using default=%d: %s", key, defaultValue, e.getMessage());
        }
        return defaultValue;
    }

    private int getRetentionMonths(String key, int defaultValue) {
        try {
            SystemConfig cfg = SystemConfig.findByKey(key);
            if (cfg != null && cfg.configValue != null) {
                return Integer.parseInt(cfg.configValue.trim());
            }
        } catch (Exception e) {
            LOG.warnf("Failed to read system_config key=%s, using default=%d: %s", key, defaultValue, e.getMessage());
        }
        return defaultValue;
    }

    /**
     * Send approval reminders daily at 09:00.
     * Targets SUBMITTED quotations older than 48 hours with no prior reminder notification.
     */
    @Scheduled(cron = "0 9 * * *")
    @Transactional
    void sendApprovalReminders() {
        LOG.info("Running approval reminder task...");
        try {
            // Find SUBMITTED quotations older than 48 hours that haven't been reminded
            List<Object[]> overdue = em.createNativeQuery(
                "SELECT q.id, q.quotation_number, q.assigned_approver_id " +
                "FROM quotation q " +
                "WHERE q.status = 'SUBMITTED' " +
                "AND q.updated_at < NOW() - INTERVAL '48 hours' " +
                "AND NOT EXISTS (" +
                "  SELECT 1 FROM notification n " +
                "  WHERE n.related_id = q.id " +
                "  AND n.type = 'APPROVAL_REMINDER'" +
                ")"
            ).getResultList();

            for (Object[] row : overdue) {
                UUID quotationId = (UUID) row[0];
                String quotationNumber = (String) row[1];
                UUID approverId = (UUID) row[2];
                if (approverId == null) continue;

                Notification notification = new Notification();
                notification.recipientId = approverId;
                notification.type = "APPROVAL_REMINDER";
                notification.title = "审批催办：" + quotationNumber + " 已等待48小时";
                notification.content = "报价单 " + quotationNumber + " 已提交超过48小时，请尽快审批。";
                notification.link = "/quotations/" + quotationId;
                notification.relatedType = "Quotation";
                notification.relatedId = quotationId;
                notification.persist();
            }
            LOG.infof("Approval reminder: notified %d overdue quotations", overdue.size());
        } catch (Exception e) {
            LOG.errorf(e, "Approval reminder task failed");
        }
    }
}
