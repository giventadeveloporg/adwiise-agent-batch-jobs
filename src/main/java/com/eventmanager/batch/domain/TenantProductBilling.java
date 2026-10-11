package com.eventmanager.batch.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import lombok.Data;

/**
 * Platform fee row. The batch job only reads and updates lock status.
 */
@Entity
@Table(name = "tenant_product_billing")
@Data
public class TenantProductBilling implements Serializable {

    @Id
    private Long id;

    @Column(name = "charge_enabled", nullable = false)
    private Boolean chargeEnabled;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "billing_mode", length = 20)
    private String billingMode;

    @Column(name = "grace_ends_at")
    private ZonedDateTime graceEndsAt;

    @Column(name = "paid_through_date")
    private LocalDate paidThroughDate;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;
}
