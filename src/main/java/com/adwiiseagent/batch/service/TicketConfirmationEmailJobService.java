package com.adwiiseagent.batch.service;

import com.adwiiseagent.batch.domain.BatchJobExecution;
import com.adwiiseagent.batch.dto.TicketConfirmationEmailJobRequest;
import com.adwiiseagent.batch.dto.TicketConfirmationEmailJobResponse;
import com.adwiiseagent.batch.service.TicketConfirmationEmailContentBuilder.TicketConfirmationEmailContent;
import com.adwiiseagent.batch.service.TicketConfirmationEmailContentBuilder.TicketLine;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Sends the paid-ticket QR confirmation that the API used to send itself.
 * The API claims {@code confirmation_sent_at} immediately before this job is accepted,
 * so a timestamp set in the last couple of minutes is this request and must still be sent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketConfirmationEmailJobService {

    private static final String JOB_NAME = "ticketConfirmationEmailJob";
    private static final String JOB_TYPE = "TICKET_CONFIRMATION_EMAIL";

    private final BatchJobExecutionService batchJobExecutionService;
    private final EmailService emailService;
    private final S3Service s3Service;
    private final TicketConfirmationEmailContentBuilder contentBuilder;
    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;
    private final ObjectProvider<JwtEncoder> jwtEncoderProvider;

    @Value("${aws.s3.bucket-name:}")
    private String s3BucketName;

    @Value("${aws.s3.region:us-east-1}")
    private String s3Region;

    public TicketConfirmationEmailJobResponse trigger(TicketConfirmationEmailJobRequest request) {
        String validationError = validate(request);
        if (validationError != null) {
            return failure(validationError);
        }

        String parametersJson = String.format(
            "{\"tenantId\":\"%s\",\"eventId\":%d,\"ticketTransactionId\":%d,\"recipientEmail\":\"%s\"}",
            request.getTenantId(),
            request.getEventId(),
            request.getTicketTransactionId(),
            request.getRecipientEmail()
        );

        BatchJobExecution execution = batchJobExecutionService.createJobExecution(
            JOB_NAME,
            JOB_TYPE,
            request.getTenantId(),
            "API",
            parametersJson
        );

        processAsync(execution.getId(), request);

        return TicketConfirmationEmailJobResponse.builder()
            .success(true)
            .message("Ticket confirmation email job accepted for processing")
            .jobExecutionId(execution.getId())
            .processedCount(0L)
            .successCount(0L)
            .failedCount(0L)
            .build();
    }

    @Async
    public void processAsync(Long executionId, TicketConfirmationEmailJobRequest request) {
        try {
            Map<String, Object> transaction = firstRow(
                """
                SELECT id, tenant_id, event_id, total_amount, final_amount, discount_amount,
                       transaction_reference, qr_code_image_url, confirmation_sent_at
                FROM event_ticket_transaction
                WHERE id = ?
                """,
                request.getTicketTransactionId()
            );
            if (transaction == null) {
                completeFailure(executionId, "Ticket transaction " + request.getTicketTransactionId() + " was not found");
                return;
            }
            if (!request.getTenantId().equals(stringValue(transaction.get("tenant_id"))) || !request.getEventId().equals(longValue(transaction.get("event_id")))) {
                completeFailure(executionId, "Ticket transaction does not match the requested tenant and event");
                return;
            }

            ZonedDateTime confirmationSentAt = zonedDateTime(transaction.get("confirmation_sent_at"));
            if (confirmationSentAt != null && confirmationSentAt.isBefore(ZonedDateTime.now().minusMinutes(2))) {
                log.info(
                    "Ticket confirmation for transaction {} was already sent at {}. Skipping a second copy.",
                    request.getTicketTransactionId(),
                    confirmationSentAt
                );
                batchJobExecutionService.completeJobExecution(
                    executionId,
                    "COMPLETED",
                    0L,
                    0L,
                    0L,
                    "Skipped because confirmation was already sent"
                );
                return;
            }

            if (!isSubscribed(request.getRecipientEmail(), request.getTenantId())) {
                log.info("Recipient {} is unsubscribed for tenant {}. Ticket email was not sent.", request.getRecipientEmail(), request.getTenantId());
                batchJobExecutionService.completeJobExecution(executionId, "COMPLETED", 1L, 0L, 0L, "Recipient is unsubscribed");
                return;
            }

            Map<String, Object> event = firstRow(
                """
                SELECT title, start_date, end_date, timezone, location, from_email, email_header_image_url
                FROM event_details
                WHERE id = ?
                """,
                request.getEventId()
            );
            if (event == null) {
                completeFailure(executionId, "Event " + request.getEventId() + " was not found");
                return;
            }

            List<TicketLine> items = loadItems(request.getTicketTransactionId());
            String tenantId = request.getTenantId();
            String profilePrefix = activeProfilePrefix();
            String s3BaseUrl = s3BaseUrl();
            String headerImageUrl = stringValue(event.get("email_header_image_url"));
            if (headerImageUrl == null || headerImageUrl.isBlank()) {
                headerImageUrl = String.format(
                    "%s/%s/events/tenantId/%s/event-id/%d/tickets/email-templates/email_header_image.jpeg",
                    s3BaseUrl,
                    profilePrefix,
                    tenantId,
                    request.getEventId()
                );
            }

            String unsubscribeLink = unsubscribeLink(request.getEmailHostUrlPrefix(), request.getRecipientEmail());
            String footerHtml = footerHtml(s3BaseUrl, profilePrefix, tenantId);
            String ticketNumber = stringValue(transaction.get("transaction_reference"));
            if (ticketNumber == null || ticketNumber.isBlank()) {
                ticketNumber = "TKTN" + request.getTicketTransactionId();
            }

            String html = contentBuilder.build(
                new TicketConfirmationEmailContent(
                    headerImageUrl,
                    stringValue(transaction.get("qr_code_image_url")),
                    ticketNumber,
                    stringValue(event.get("title")),
                    localDate(event.get("start_date")),
                    localDate(event.get("end_date")),
                    stringValue(event.get("timezone")),
                    stringValue(event.get("location")),
                    decimal(transaction.get("discount_amount")),
                    decimal(transaction.get("total_amount")),
                    decimal(transaction.get("final_amount")),
                    unsubscribeLink,
                    footerHtml,
                    items
                )
            );

            String fromEmail = stringValue(event.get("from_email"));
            if (fromEmail == null || fromEmail.isBlank()) {
                fromEmail = tenantFromEmail(tenantId);
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("List-Unsubscribe", "<mailto:unsubscribe@yourdomain.com>, <" + unsubscribeLink + ">");
            String eventName = stringValue(event.get("title"));
            emailService.sendEmail(
                fromEmail,
                null,
                request.getRecipientEmail(),
                "Your Event Ticket for " + (eventName == null ? "" : eventName),
                html,
                true,
                headers
            );

            batchJobExecutionService.completeJobExecution(executionId, "COMPLETED", 1L, 1L, 0L, null);
            log.info("Ticket confirmation email sent for transaction {} to {}", request.getTicketTransactionId(), request.getRecipientEmail());
        } catch (Exception e) {
            log.error("Ticket confirmation email failed for transaction {}: {}", request.getTicketTransactionId(), e.getMessage(), e);
            completeFailure(executionId, e.getMessage());
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private String validate(TicketConfirmationEmailJobRequest request) {
        if (request == null) {
            return "Request is required";
        }
        if (request.getTenantId() == null || request.getTenantId().isBlank()) {
            return "Tenant ID is required";
        }
        if (request.getEventId() == null) {
            return "Event ID is required";
        }
        if (request.getTicketTransactionId() == null) {
            return "Ticket transaction ID is required";
        }
        if (request.getRecipientEmail() == null || request.getRecipientEmail().isBlank()) {
            return "Recipient email is required";
        }
        if (request.getEmailHostUrlPrefix() == null || request.getEmailHostUrlPrefix().isBlank()) {
            return "Email host URL prefix is required";
        }
        return null;
    }

    private boolean isSubscribed(String email, String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT is_email_subscribed FROM user_profile WHERE email = ? AND tenant_id = ?",
            email,
            tenantId
        );
        if (rows.isEmpty()) {
            return true;
        }
        for (Map<String, Object> row : rows) {
            Object value = row.get("is_email_subscribed");
            if (Boolean.TRUE.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private List<TicketLine> loadItems(Long transactionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT i.quantity, i.price_per_unit, i.total_amount, t.name
            FROM event_ticket_transaction_item i
            LEFT JOIN event_ticket_type t ON t.id = i.ticket_type_id
            WHERE i.transaction_id = ?
            """,
            transactionId
        );
        List<TicketLine> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Integer quantity = row.get("quantity") == null ? 0 : ((Number) row.get("quantity")).intValue();
            items.add(new TicketLine(stringValue(row.get("name")), quantity, decimal(row.get("price_per_unit")), decimal(row.get("total_amount"))));
        }
        return items;
    }

    private String footerHtml(String s3BaseUrl, String profilePrefix, String tenantId) {
        String tenantIdPath = tenantId != null ? tenantId : "tenant_demo_001";
        String footerHtmlS3Url = String.format(
            "%s/%s/events/tenantId/%s/email-templates/email_footer.html",
            s3BaseUrl,
            profilePrefix,
            tenantIdPath
        );
        String logoS3Url = String.format(
            "%s/%s/events/tenantId/%s/email-templates/email_footer_logo.png",
            s3BaseUrl,
            profilePrefix,
            tenantIdPath
        );
        try {
            String footerHtml = s3Service.downloadHtmlFromUrl(footerHtmlS3Url);
            return footerHtml == null ? "" : footerHtml.replace("{{LOGO_URL}}", logoS3Url);
        } catch (Exception e) {
            log.debug("Ticket email footer was not loaded from {}: {}", footerHtmlS3Url, e.getMessage());
            return "";
        }
    }

    private String unsubscribeLink(String emailHostUrlPrefix, String recipient) {
        JwtEncoder jwtEncoder = jwtEncoderProvider.getIfAvailable();
        String token = "";
        if (jwtEncoder != null) {
            JwtClaimsSet claims = JwtClaimsSet.builder().subject(recipient).build();
            JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
            token = jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
        }
        return String.format(emailHostUrlPrefix + "/unsubscribe-email?email=%s&token=%s", recipient, token);
    }

    private String tenantFromEmail(String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT email FROM tenant_settings WHERE tenant_id = ?", tenantId);
        if (rows.isEmpty()) {
            return null;
        }
        return stringValue(rows.get(0).get("email"));
    }

    private String activeProfilePrefix() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length == 0) {
            return "dev";
        }
        String profile = activeProfiles[0];
        if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
            return "prod";
        }
        return "dev";
    }

    private String s3BaseUrl() {
        return String.format("https://%s.s3.%s.amazonaws.com", s3BucketName, s3Region);
    }

    private Map<String, Object> firstRow(String sql, Object id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void completeFailure(Long executionId, String message) {
        batchJobExecutionService.completeJobExecution(executionId, "FAILED", 1L, 0L, 1L, message);
    }

    private TicketConfirmationEmailJobResponse failure(String message) {
        return TicketConfirmationEmailJobResponse.builder()
            .success(false)
            .message(message)
            .processedCount(0L)
            .successCount(0L)
            .failedCount(0L)
            .build();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static Long longValue(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal amount) {
            return amount;
        }
        return new BigDecimal(value.toString());
    }

    private static LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        String text = value.toString();
        return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text);
    }

    private static ZonedDateTime zonedDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ZonedDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toZonedDateTime();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atZone(ZoneId.systemDefault());
        }
        return null;
    }
}
