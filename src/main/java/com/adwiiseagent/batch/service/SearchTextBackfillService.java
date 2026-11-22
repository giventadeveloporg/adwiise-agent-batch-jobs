package com.adwiiseagent.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Backfills denormalized {@code search_text} (and optionally {@code ocr_text_from_images_upload}) for
 * event_details, gallery_album, focus_group, and event_sponsors.
 * <p>
 * Structured-field rebuild is always safe/idempotent. Image reading runs only when
 * {@code batch.search-text-backfill.ocr-enabled=true}. It calls the service Bedrock endpoint
 * for rows that are not already marked BEDROCK with words, including BEDROCK rows whose
 * image text is still blank. Rows marked BEDROCK_NONE are left alone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchTextBackfillService {

    private final JdbcTemplate jdbcTemplate;
    private final BackendApiService backendApiService;

    @Value("${batch.search-text-backfill.ocr-enabled:false}")
    private boolean ocrEnabled;

    @Value("${batch.search-text-backfill.limit:200}")
    private int limit;

    public BackfillResult runBackfill() {
        int structured = 0;
        int ocrUpdated = 0;
        List<String> errors = new ArrayList<>();

        structured += backfillEventDetailsStructured();
        structured += backfillGalleryAlbumsStructured();
        structured += backfillFocusGroupsStructured();
        structured += backfillEventSponsorsStructured();

        if (ocrEnabled) {
            try {
                ocrUpdated += refreshImages(
                    "event_sponsors",
                    """
                    SELECT id FROM event_sponsors
                    WHERE (
                        image_text_source IS NULL
                        OR (image_text_source <> 'BEDROCK' AND image_text_source <> 'BEDROCK_NONE')
                        OR (
                            image_text_source = 'BEDROCK'
                            AND (ocr_text_from_images_upload IS NULL OR btrim(ocr_text_from_images_upload) = '')
                        )
                    )
                      AND (
                        (logo_url IS NOT NULL AND btrim(logo_url) <> '')
                        OR (hero_image_url IS NOT NULL AND btrim(hero_image_url) <> '')
                        OR (banner_image_url IS NOT NULL AND btrim(banner_image_url) <> '')
                      )
                    ORDER BY id
                    LIMIT ?
                    """
                );
                ocrUpdated += refreshImages(
                    "gallery_album",
                    """
                    SELECT id FROM gallery_album
                    WHERE cover_image_url IS NOT NULL AND btrim(cover_image_url) <> ''
                      AND (
                        image_text_source IS NULL
                        OR (image_text_source <> 'BEDROCK' AND image_text_source <> 'BEDROCK_NONE')
                        OR (
                            image_text_source = 'BEDROCK'
                            AND (ocr_text_from_images_upload IS NULL OR btrim(ocr_text_from_images_upload) = '')
                        )
                    )
                    ORDER BY id
                    LIMIT ?
                    """
                );
                ocrUpdated += refreshImages(
                    "focus_group",
                    """
                    SELECT id FROM focus_group
                    WHERE cover_image_url IS NOT NULL AND btrim(cover_image_url) <> ''
                      AND (
                        image_text_source IS NULL
                        OR (image_text_source <> 'BEDROCK' AND image_text_source <> 'BEDROCK_NONE')
                        OR (
                            image_text_source = 'BEDROCK'
                            AND (ocr_text_from_images_upload IS NULL OR btrim(ocr_text_from_images_upload) = '')
                        )
                    )
                    ORDER BY id
                    LIMIT ?
                    """
                );
                ocrUpdated += refreshImages(
                    "event_details",
                    """
                    SELECT id FROM event_details
                    WHERE email_header_image_url IS NOT NULL AND btrim(email_header_image_url) <> ''
                      AND (
                        image_text_source IS NULL
                        OR (image_text_source <> 'BEDROCK' AND image_text_source <> 'BEDROCK_NONE')
                        OR (
                            image_text_source = 'BEDROCK'
                            AND (ocr_text_from_images_upload IS NULL OR btrim(ocr_text_from_images_upload) = '')
                        )
                    )
                    ORDER BY id
                    LIMIT ?
                    """
                );
            } catch (Exception e) {
                log.error("[SearchTextBackfill] OCR phase failed: {}", e.getMessage(), e);
                errors.add("OCR phase: " + e.getMessage());
            }
        } else {
            log.info("[SearchTextBackfill] OCR skipped (batch.search-text-backfill.ocr-enabled=false)");
        }

        log.info(
            "[SearchTextBackfill] Done. structuredUpdates={}, ocrUpdates={}, errors={}",
            structured,
            ocrUpdated,
            errors.size()
        );
        return new BackfillResult(structured, ocrUpdated, errors);
    }

    private int backfillEventDetailsStructured() {
        String sql =
            """
            UPDATE event_details
            SET search_text = lower(trim(both ' ' FROM concat_ws(' ',
                nullif(trim(title), ''),
                nullif(trim(caption), ''),
                nullif(trim(description), ''),
                nullif(trim(location), ''),
                nullif(trim(ocr_text_from_images_upload), '')
            )))
            WHERE search_text IS NULL OR btrim(search_text) = ''
            """;
        int n = jdbcTemplate.update(sql);
        log.info("[SearchTextBackfill] event_details structured updates={}", n);
        return n;
    }

    private int backfillGalleryAlbumsStructured() {
        String sql =
            """
            UPDATE gallery_album
            SET search_text = lower(trim(both ' ' FROM concat_ws(' ',
                nullif(trim(title), ''),
                nullif(trim(description), ''),
                nullif(trim(event_location), ''),
                nullif(trim(ocr_text_from_images_upload), '')
            )))
            WHERE search_text IS NULL OR btrim(search_text) = ''
            """;
        int n = jdbcTemplate.update(sql);
        log.info("[SearchTextBackfill] gallery_album structured updates={}", n);
        return n;
    }

    private int backfillFocusGroupsStructured() {
        String sql =
            """
            UPDATE focus_group
            SET search_text = lower(trim(both ' ' FROM concat_ws(' ',
                nullif(trim(name), ''),
                nullif(trim(slug), ''),
                nullif(trim(description), ''),
                nullif(trim(ocr_text_from_images_upload), '')
            )))
            WHERE search_text IS NULL OR btrim(search_text) = ''
            """;
        int n = jdbcTemplate.update(sql);
        log.info("[SearchTextBackfill] focus_group structured updates={}", n);
        return n;
    }

    private int backfillEventSponsorsStructured() {
        String sql =
            """
            UPDATE event_sponsors
            SET search_text = lower(trim(both ' ' FROM concat_ws(' ',
                nullif(trim(name), ''),
                nullif(trim(company_name), ''),
                nullif(trim(tagline), ''),
                nullif(trim(description), ''),
                nullif(trim(type), ''),
                nullif(trim(ocr_text_from_images_upload), '')
            )))
            WHERE search_text IS NULL OR btrim(search_text) = ''
            """;
        int n = jdbcTemplate.update(sql);
        log.info("[SearchTextBackfill] event_sponsors structured updates={}", n);
        return n;
    }

    private int refreshImages(String entityType, String sql) {
        List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong(1), limit);
        int updated = 0;
        for (Long id : ids) {
            try {
                backendApiService.refreshImageSearchText(entityType, id);
                updated++;
            } catch (Exception e) {
                log.warn("[SearchTextBackfill] {} {} skipped: {}", entityType, id, e.getMessage());
            }
        }
        log.info("[SearchTextBackfill] {} image updates={}", entityType, updated);
        return updated;
    }

    public record BackfillResult(int structuredUpdates, int ocrUpdates, List<String> errors) {}
}
