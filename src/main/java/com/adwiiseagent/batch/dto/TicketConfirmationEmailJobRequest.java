package com.adwiiseagent.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request to send the paid-ticket QR email. The batch job loads the ticket and builds the message.
 */
@Data
public class TicketConfirmationEmailJobRequest {

    @NotBlank
    private String tenantId;

    @NotNull
    private Long eventId;

    @NotNull
    private Long ticketTransactionId;

    @NotBlank
    private String recipientEmail;

    @NotBlank
    private String emailHostUrlPrefix;
}
