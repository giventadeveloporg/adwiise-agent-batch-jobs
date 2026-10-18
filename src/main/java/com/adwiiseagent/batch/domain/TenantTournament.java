package com.adwiiseagent.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Data;

/**
 * Tenant-hosted tournament. Menu text is {@code shortLabel}. Year is optional.
 * Mapped so batch jobs stay aligned with {@code tenant_tournament}; jobs do not write this table.
 */
@Entity
@Table(name = "tenant_tournament")
@Data
public class TenantTournament implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "short_label", nullable = false, length = 40)
    private String shortLabel;

    @Column(name = "edition_year")
    private Integer editionYear;

    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    @Column(name = "show_in_header", nullable = false)
    private Boolean showInHeader = false;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "UPCOMING";

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "is_published", nullable = false)
    private Boolean isPublished = false;

    @Column(name = "welcome_text", columnDefinition = "text")
    private String welcomeText;

    @Column(name = "about_text", columnDefinition = "text")
    private String aboutText;

    @Column(name = "details_text", columnDefinition = "text")
    private String detailsText;

    @Column(name = "age_group_label", length = 64)
    private String ageGroupLabel;

    @Column(name = "chief_guest_name", length = 255)
    private String chiefGuestName;

    @Column(name = "chief_guest_image_url", length = 1024)
    private String chiefGuestImageUrl;

    @Column(name = "hero_image_url", length = 1024)
    private String heroImageUrl;

    @Column(name = "fixtures_image_url", length = 1024)
    private String fixturesImageUrl;

    @Column(name = "registration_email", length = 255)
    private String registrationEmail;

    @Column(name = "external_results_url", length = 1024)
    private String externalResultsUrl;

    @Column(name = "external_results_label", length = 128)
    private String externalResultsLabel;

    @Column(name = "legacy_page_url", length = 1024)
    private String legacyPageUrl;

    @Column(name = "winner_name", length = 255)
    private String winnerName;

    @Column(name = "runner_up_name", length = 255)
    private String runnerUpName;

    @Column(name = "third_place_name", length = 255)
    private String thirdPlaceName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
