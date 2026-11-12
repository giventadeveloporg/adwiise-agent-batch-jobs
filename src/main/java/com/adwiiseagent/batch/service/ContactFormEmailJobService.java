package com.adwiiseagent.batch.service;

import com.adwiiseagent.batch.domain.BatchJobExecution;
import com.adwiiseagent.batch.domain.TenantEmailAddress;
import com.adwiiseagent.batch.domain.enumeration.TenantEmailType;
import com.adwiiseagent.batch.dto.ContactFormEmailJobRequest;
import com.adwiiseagent.batch.dto.ContactFormEmailJobResponse;
import com.adwiiseagent.batch.repository.TenantEmailAddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Service responsible for handling contact form email jobs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContactFormEmailJobService {

    private final BatchJobExecutionService batchJobExecutionService;
    private final TenantEmailAddressRepository tenantEmailAddressRepository;
    private final EmailContentBuilderService emailContentBuilderService;
    private final EmailService emailService;

    @Value("${aws.ses.from-email:}")
    private String configuredSesFromEmail;

    /**
     * Entry point used by REST controller to trigger a contact form email job.
     * Creates a job execution record and starts asynchronous processing.
     */
    public ContactFormEmailJobResponse triggerContactFormEmailJob(ContactFormEmailJobRequest request) {
        if (request.getTenantId() == null || request.getTenantId().isBlank()) {
            return ContactFormEmailJobResponse.builder()
                .success(false)
                .message("Tenant ID is required")
                .processedCount(0L)
                .successCount(0L)
                .failedCount(0L)
                .build();
        }

        String parametersJson = String.format(
            "{\"tenantId\":\"%s\",\"senderEmail\":\"%s\",\"emailType\":\"%s\",\"toEmail\":\"%s\"}",
            request.getTenantId(),
            request.getSenderEmail(),
            request.getEmailType(),
            request.getToEmail() == null ? "" : request.getToEmail()
        );

        BatchJobExecution execution = batchJobExecutionService.createJobExecution(
            "contactFormEmailJob",
            "CONTACT_FORM_EMAIL",
            request.getTenantId(),
            "API",
            parametersJson
        );

        return processContactFormEmail(execution.getId(), request);
    }

    /**
     * Sends the contact message and returns the real SES result.
     * Runs in the request so a failed send is not reported as success.
     */
    private ContactFormEmailJobResponse processContactFormEmail(Long executionId, ContactFormEmailJobRequest request) {
        long processedCount = 0L;
        long successCount = 0L;
        long failedCount = 0L;

        try {
            String tenantId = request.getTenantId();
            TenantEmailType emailType = resolveEmailType(request);

            String toAddress = request.getToEmail();
            if (toAddress == null || toAddress.isBlank()) {
                toAddress = resolveToEmailByType(tenantId, emailType);
            }
            if (toAddress == null || toAddress.isBlank()) {
                log.warn("No TO address resolved for tenant {} and emailType {}", tenantId, emailType);
                return finishJob(
                    executionId,
                    false,
                    processedCount,
                    successCount,
                    1L,
                    "No TO address configured for tenant and email type " + emailType
                );
            }

            List<String> sources = sendingSourceCandidates(tenantId, emailType, toAddress);
            if (sources.isEmpty()) {
                log.warn("No FROM address resolved for tenant {} and emailType {}", tenantId, emailType);
                return finishJob(
                    executionId,
                    false,
                    processedCount,
                    successCount,
                    1L,
                    "No FROM address configured for tenant and email type " + emailType
                );
            }

            String copyToAddress = resolveCopyToEmailByType(tenantId, emailType);
            String replyToAddress = resolveEffectiveReplyTo(tenantId, emailType, request);

            String subject = String.format(
                "Contact Form Submission from %s %s",
                request.getFirstName(),
                request.getLastName()
            );

            String mainBody = emailContentBuilderService.buildContactEmailBody(request);
            String confirmationBody = emailContentBuilderService.buildContactConfirmationEmailBody(request);

            processedCount = 1L;

            String fromAddress = null;
            String lastSendError = null;
            for (String source : sources) {
                try {
                    emailService.sendEmail(source, replyToAddress, toAddress, subject, mainBody, true);
                    fromAddress = source;
                    break;
                } catch (RuntimeException ex) {
                    lastSendError = ex.getMessage();
                    log.warn(
                        "Contact form send from {} to {} was rejected: {}",
                        source,
                        toAddress,
                        ex.getMessage()
                    );
                }
            }

            if (fromAddress == null) {
                return finishJob(
                    executionId,
                    false,
                    processedCount,
                    successCount,
                    1L,
                    lastSendError != null ? lastSendError : "Failed to send contact form email"
                );
            }

            if (copyToAddress != null && !copyToAddress.isBlank() && !copyToAddress.equalsIgnoreCase(toAddress)) {
                try {
                    emailService.sendEmail(
                        fromAddress,
                        replyToAddress,
                        copyToAddress,
                        subject + " (Copy)",
                        mainBody,
                        true
                    );
                } catch (RuntimeException ex) {
                    log.warn("Contact form copy to {} was rejected: {}", copyToAddress, ex.getMessage());
                }
            }

            String senderEmail = request.getSenderEmail();
            if (senderEmail != null && !senderEmail.isBlank() && !senderEmail.equalsIgnoreCase(toAddress)) {
                try {
                    emailService.sendEmail(
                        fromAddress,
                        null,
                        senderEmail.trim(),
                        "We received your message",
                        confirmationBody,
                        true
                    );
                } catch (RuntimeException ex) {
                    log.error(
                        "Contact form reached {} but the copy to {} was rejected: {}",
                        toAddress,
                        senderEmail,
                        ex.getMessage()
                    );
                    return finishJob(
                        executionId,
                        false,
                        processedCount,
                        successCount,
                        1L,
                        "Your message reached " + toAddress + ", but the copy to " + senderEmail.trim() + " was rejected."
                    );
                }
            }

            successCount = 1L;
            return finishJob(executionId, true, processedCount, successCount, failedCount, null);
        } catch (Exception e) {
            log.error("Failed to process contact form email job execution {}: {}", executionId, e.getMessage(), e);
            return finishJob(executionId, false, processedCount, successCount, 1L, e.getMessage());
        }
    }

    private ContactFormEmailJobResponse finishJob(
        Long executionId,
        boolean sent,
        long processedCount,
        long successCount,
        long failedCount,
        String errorMessage
    ) {
        batchJobExecutionService.completeJobExecution(
            executionId,
            sent ? "COMPLETED" : "FAILED",
            processedCount,
            successCount,
            failedCount,
            sent ? null : errorMessage
        );
        return ContactFormEmailJobResponse.builder()
            .success(sent)
            .message(sent ? "Contact form email sent" : errorMessage)
            .jobExecutionId(executionId)
            .processedCount(processedCount)
            .successCount(successCount)
            .failedCount(failedCount)
            .build();
    }

    /**
     * SES source identities to try, in order.
     * A verified inbox (the form's toEmail, usually sales@giventa.com) is tried
     * before any unrelated tenant address, which SES rejects when it is not verified.
     */
    private List<String> sendingSourceCandidates(String tenantId, TenantEmailType emailType, String toEmail) {
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        if (tenantId != null && !tenantId.isBlank()) {
            tenantEmailAddressRepository
                .findFirstByTenantIdAndEmailTypeAndIsActiveTrueOrderByIsDefaultDesc(tenantId, emailType)
                .map(TenantEmailAddress::getEmailAddress)
                .ifPresent(address -> addSendingSource(sources, address));
        }
        addSendingSource(sources, toEmail);
        addSendingSource(sources, configuredSesFromEmail);
        if (tenantId != null && !tenantId.isBlank()) {
            for (TenantEmailAddress address : tenantEmailAddressRepository.findByTenantIdAndIsActiveTrue(tenantId)) {
                addSendingSource(sources, address.getEmailAddress());
            }
        }
        return new ArrayList<>(sources);
    }

    private void addSendingSource(LinkedHashSet<String> sources, String email) {
        if (email == null) {
            return;
        }
        String value = email.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("noreply@example.com")) {
            return;
        }
        sources.add(value);
    }

    private TenantEmailType resolveEmailType(ContactFormEmailJobRequest request) {
        if (request.getEmailType() == null || request.getEmailType().isBlank()) {
            return TenantEmailType.CONTACT;
        }
        try {
            return TenantEmailType.valueOf(request.getEmailType().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown emailType '{}', defaulting to CONTACT", request.getEmailType());
            return TenantEmailType.CONTACT;
        }
    }

    private Optional<TenantEmailAddress> selectTenantEmailAddress(String tenantId, TenantEmailType emailType) {
        Optional<TenantEmailAddress> preferred = tenantEmailAddressRepository
            .findFirstByTenantIdAndEmailTypeAndIsActiveTrueOrderByIsDefaultDesc(tenantId, emailType);
        if (preferred.isPresent()) {
            return preferred;
        }

        Optional<TenantEmailAddress> defaultEmail = tenantEmailAddressRepository
            .findFirstByTenantIdAndIsDefaultTrueAndIsActiveTrueOrderByIdAsc(tenantId);
        if (defaultEmail.isPresent()) {
            return defaultEmail;
        }

        List<TenantEmailAddress> activeEmails = tenantEmailAddressRepository.findByTenantIdAndIsActiveTrue(tenantId);
        if (!activeEmails.isEmpty()) {
            return Optional.of(activeEmails.get(0));
        }

        return Optional.empty();
    }

    /**
     * Resolve verified SES FROM address for the given tenant and email type.
     */
    @Cacheable(cacheNames = "tenantEmailFromCache", key = "#tenantId + ':' + #emailType", unless = "#result == null || #result.isEmpty()")
    public String resolveFromEmailByType(String tenantId, TenantEmailType emailType) {
        if (tenantId == null || tenantId.isBlank()) {
            return "";
        }

        return selectTenantEmailAddress(tenantId, emailType)
            .map(TenantEmailAddress::getEmailAddress)
            .orElseGet(() -> {
                log.warn("No active tenant email address found for tenant {} and type {}", tenantId, emailType);
                return "";
            });
    }

    /**
     * Resolve primary inbox (TO) for contact submissions.
     * Prefers copy_to_email_address when set, otherwise uses the verified from address.
     */
    @Cacheable(cacheNames = "tenantEmailToCache", key = "#tenantId + ':' + #emailType", unless = "#result == null || #result.isEmpty()")
    public String resolveToEmailByType(String tenantId, TenantEmailType emailType) {
        if (tenantId == null || tenantId.isBlank()) {
            return "";
        }

        Optional<TenantEmailAddress> selected = selectTenantEmailAddress(tenantId, emailType);
        if (selected.isEmpty()) {
            return "";
        }

        TenantEmailAddress address = selected.get();
        String copyTo = address.getCopyToEmailAddress();
        if (copyTo != null && !copyTo.isBlank()) {
            return copyTo;
        }
        return address.getEmailAddress();
    }

    /**
     * Resolve optional CC copy address from tenant_email_addresses.copy_to_email_address.
     */
    @Cacheable(cacheNames = "tenantEmailCopyToCache", key = "#tenantId + ':' + #emailType", unless = "#result == null || #result.isEmpty()")
    public String resolveCopyToEmailByType(String tenantId, TenantEmailType emailType) {
        if (tenantId == null || tenantId.isBlank()) {
            return "";
        }

        return selectTenantEmailAddress(tenantId, emailType)
            .map(TenantEmailAddress::getCopyToEmailAddress)
            .filter(copyTo -> copyTo != null && !copyTo.isBlank())
            .orElse("");
    }

    /**
     * Resolve optional Reply-To from tenant_email_addresses.reply_to_email_address.
     */
    @Cacheable(cacheNames = "tenantEmailReplyToCache", key = "#tenantId + ':' + #emailType", unless = "#result == null || #result.isEmpty()")
    public String resolveReplyToEmailByType(String tenantId, TenantEmailType emailType) {
        if (tenantId == null || tenantId.isBlank()) {
            return "";
        }

        return selectTenantEmailAddress(tenantId, emailType)
            .map(TenantEmailAddress::getReplyToEmailAddress)
            .filter(replyTo -> replyTo != null && !replyTo.isBlank())
            .orElse("");
    }

    /**
     * Reply-To for outbound mail: tenant reply_to when configured, else visitor senderEmail (contact forms).
     */
    private String resolveEffectiveReplyTo(String tenantId, TenantEmailType emailType, ContactFormEmailJobRequest request) {
        String configuredReplyTo = resolveReplyToEmailByType(tenantId, emailType);
        if (configuredReplyTo != null && !configuredReplyTo.isBlank()) {
            return configuredReplyTo;
        }
        return request.getSenderEmail();
    }
}
