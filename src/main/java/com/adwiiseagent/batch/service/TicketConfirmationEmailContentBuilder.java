package com.adwiiseagent.batch.service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Builds the paid-ticket QR email that {@code QRCodeResource.sendTicketEmail} used to build in the API service.
 */
@Component
public class TicketConfirmationEmailContentBuilder {

    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_RANGE = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    public String build(TicketConfirmationEmailContent content) {
        String unsubscribeHtml = String.format(
            "<div style='margin:24px 0 0 0; text-align:center; color:#888; font-size:13px;'>If you no longer wish to receive these emails, <a href='%s' style='color:#6b207c;'>click here to unsubscribe</a>.</div>",
            content.unsubscribeLink()
        );
        String template = String.format(
            """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8">
              <title>Your Event Ticket</title>
            </head>
            <body style="font-family: Arial, sans-serif; background: #f9f9f9; padding: 20px;">
              <div style="max-width: 650px; margin: auto; background: #fff; border-radius: 12px; box-shadow: 0 2px 12px #eee; overflow: hidden;">
                <img src="%s" alt="Event Header" style="width: 100%%; display: block; border-bottom: 1px solid #eee;">
                <div style="padding: 32px;">
                  <h2 style="margin-top: 0; text-align:center; color:#6b207c;">%s Your Ticket QR Code</h2>
                  <div style="text-align:center; margin-bottom: 32px;">
                    <img src="%s" alt="QR Code" style="width:180px;height:180px; border: 1px solid #eee; border-radius: 8px; background: #fafafa; padding: 12px;">
                  </div>
                  <div style="margin-bottom: 32px;">
                    <h3 style="margin-bottom: 12px; color:#6b207c;">%s Ticket # <span style='color:#2d3748;font-weight:bold;'>%s</span></h3>
                    <h3 style="margin-bottom: 12px; color:#6b207c;">%s Ticket Breakdown</h3>
                    <table style="width:100%%; border-collapse:collapse;">
                      <tr style="background:#f5f5f5;">
                        <th style="padding:8px; text-align:center;">Ticket Type</th>
                        <th style="padding:8px; text-align:center;">Quantity</th>
                        <th style="padding:8px; text-align:center;">Price Per Unit</th>
                        <th style="padding:8px; text-align:center;">Total</th>
                      </tr>
                      %s
                      %s
                    </table>
                  </div>
                  <div style="margin-bottom: 32px;">
                    <h3 style="margin-bottom: 12px; color:#6b207c;">%s Event Details</h3>
                    <p>
                      <strong>%s</strong><br>
                      <span>%s | %s - %s</span><br>
                    </p>
                    %s
                  </div>
                </div>
              </div>
            </body>
            </html>
            """,
            nullToEmpty(content.headerImageUrl()),
            "🎫",
            nullToEmpty(content.qrCodeImageUrl()),
            "🎟️",
            nullToEmpty(content.ticketNumber()),
            "🎟️",
            ticketRows(content.items()),
            additionalRows(content),
            "📅",
            nullToEmpty(content.eventName()),
            eventDate(content),
            eventStart(content),
            eventEnd(content),
            locationDisplay(content.location())
        );
        String ticketPolicyHtml =
            """
            <div style="margin: 24px 0; padding: 20px; background: #f8f9fa; border-radius: 8px; font-size: 13px; color: #555;">
              <h4 style="margin: 0 0 12px 0; color: #333; font-size: 14px;">Ticket Sales Policy</h4>
              <p style="margin: 0 0 12px 0;"><strong>All ticket sales are final and non-refundable, with the following exceptions:</strong></p>
              <ul style="margin: 0 0 12px 0; padding-left: 20px;">
                <li style="margin-bottom: 8px;"><strong>Event Cancellation:</strong> If the event is outright canceled, you will be eligible for a full refund.</li>
                <li style="margin-bottom: 8px;"><strong>Event Postponement/Rescheduling:</strong> In the event of a postponement or rescheduling, you may be offered the option of a refund or the ability to use your ticket for the new date, depending on the specific circumstances. Please refer to further communication regarding postponed or rescheduled events for details.</li>
                <li style="margin-bottom: 8px;"><strong>Refund Processing:</strong> Approved refunds will typically be processed back to the original method of payment.</li>
              </ul>
              <p style="margin: 0; font-weight: bold;"><strong>By purchasing this ticket, you acknowledge and agree to the terms of this Ticket Sales Policy.</strong></p>
            </div>
            """;
        return template + unsubscribeHtml + nullToEmpty(content.footerHtml()) + ticketPolicyHtml;
    }

    private String ticketRows(List<TicketLine> items) {
        StringBuilder rows = new StringBuilder();
        if (items == null) {
            return "";
        }
        for (TicketLine item : items) {
            rows.append(
                String.format(
                    "<tr><td style='padding:8px; text-align:center;'>%s</td><td style='padding:8px; text-align:center;'>%d</td><td style='padding:8px; text-align:center;'>$%.2f</td><td style='padding:8px; text-align:center;'>$%.2f</td></tr>",
                    nullToEmpty(item.typeName()),
                    item.quantity() == null ? 0 : item.quantity(),
                    money(item.pricePerUnit()),
                    money(item.totalAmount())
                )
            );
        }
        return rows.toString();
    }

    private String additionalRows(TicketConfirmationEmailContent content) {
        StringBuilder rows = new StringBuilder();
        if (content.discountAmount() != null && content.discountAmount().doubleValue() > 0) {
            rows.append(
                String.format(
                    "<tr style=\"border-top: 2px solid #e5e7eb;\"><td colspan=\"2\" style=\"padding:8px; text-align:right; font-weight:bold;\">🏷️ Discount Applied:</td><td colspan=\"2\" style=\"padding:8px; text-align:center; color:#dc2626; font-weight:bold;\">-$%.2f</td></tr>",
                    content.discountAmount()
                )
            );
        }
        BigDecimal paid = content.finalAmount() != null ? content.finalAmount() : content.totalAmount();
        rows.append(
            String.format(
                "<tr style=\"border-top: 2px solid #e5e7eb; background:#f0f9ff;\"><td colspan=\"2\" style=\"padding:12px; text-align:right; font-weight:bold; font-size:16px;\">💵 Amount Paid:</td><td colspan=\"2\" style=\"padding:12px; text-align:center; color:#059669; font-weight:bold; font-size:16px;\">$%.2f</td></tr>",
                money(paid)
            )
        );
        return rows.toString();
    }

    private String locationDisplay(String locationAddress) {
        if (locationAddress == null || locationAddress.trim().isEmpty()) {
            return "<span style=\"color: #6b7280;\">Location TBD</span>";
        }
        String googleMapsUrl;
        try {
            googleMapsUrl = "https://www.google.com/maps/search/" + URLEncoder.encode(locationAddress, StandardCharsets.UTF_8);
        } catch (Exception e) {
            googleMapsUrl = "https://www.google.com/maps";
        }
        return String.format(
            """
            <div style="display: flex; align-items: flex-start; gap: 8px; margin-top: 8px;">
              <span style="color: #dc2626; font-size: 16px; margin-top: 2px;">📍</span>
              <div style="flex: 1;">
                <div style="background: #f8f9fa; padding: 8px; border-radius: 4px; border-left: 3px solid #dc2626; margin: 4px 0;">
                  <span style="color: #374151; line-height: 1.5; font-weight: 500;">%s</span>
                </div>
                <div style="margin-top: 8px;">
                  <a href="%s" target="_blank"
                     style="display: inline-flex; align-items: center; gap: 4px; padding: 6px 12px;
                            background: #f3f4f6; border-radius: 6px; text-decoration: none;
                            color: #059669; font-size: 13px; border: 1px solid #d1d5db;">
                    <span>🗺️</span>
                    <span>View on Google Maps</span>
                  </a>
                </div>
              </div>
            </div>
            """,
            locationAddress,
            googleMapsUrl
        );
    }

    private String eventDate(TicketConfirmationEmailContent content) {
        ZonedDateTime start = startAt(content);
        return start == null ? "" : start.format(DATE_ONLY);
    }

    private String eventStart(TicketConfirmationEmailContent content) {
        ZonedDateTime start = startAt(content);
        return start == null ? "" : start.format(TIME_RANGE);
    }

    private String eventEnd(TicketConfirmationEmailContent content) {
        if (content.startDate() == null || content.endDate() == null) {
            return "";
        }
        ZoneId zone = zone(content);
        ZonedDateTime end = content.endDate().atStartOfDay(zone);
        return end.format(TIME_RANGE) + " (" + timeZoneAbbr(content, zone) + ")";
    }

    private ZonedDateTime startAt(TicketConfirmationEmailContent content) {
        if (content.startDate() == null || content.endDate() == null) {
            return null;
        }
        return content.startDate().atStartOfDay(zone(content));
    }

    private ZoneId zone(TicketConfirmationEmailContent content) {
        if (content.timezone() == null || content.timezone().isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(content.timezone());
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    private String timeZoneAbbr(TicketConfirmationEmailContent content, ZoneId zone) {
        if (content.timezone() == null || content.timezone().isBlank()) {
            return "";
        }
        try {
            ZoneId.of(content.timezone());
            return content.endDate().atStartOfDay(zone).getZone().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        } catch (Exception e) {
            return zone.getId();
        }
    }

    private static double money(BigDecimal amount) {
        return amount == null ? 0d : amount.doubleValue();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record TicketLine(String typeName, Integer quantity, BigDecimal pricePerUnit, BigDecimal totalAmount) {}

    public record TicketConfirmationEmailContent(
        String headerImageUrl,
        String qrCodeImageUrl,
        String ticketNumber,
        String eventName,
        LocalDate startDate,
        LocalDate endDate,
        String timezone,
        String location,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        BigDecimal finalAmount,
        String unsubscribeLink,
        String footerHtml,
        List<TicketLine> items
    ) {}
}
