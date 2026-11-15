package com.adwiiseagent.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Backfills denormalized {@code search_text} (and optionally {@code ocr_text_from_images_upload}) for
 * event_details, gallery_album, focus_group, and event_sponsors.
 * <p>
 * Structured-field rebuild is always safe/idempotent. Image OCR runs only when
 * {@code batch.search-text-backfill.ocr-enabled=true} and Tess4J/native Tesseract
 * are available.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchTextBackfillService {

    private final JdbcTemplate jdbcTemplate;

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
                ocrUpdated += ocrGalleryAlbumCovers();
                ocrUpdated += ocrSponsorImages();
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

    private int ocrGalleryAlbumCovers() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT id, cover_image_url AS image_url, ocr_text_from_images_upload, title, description, event_location
            FROM gallery_album
            WHERE cover_image_url IS NOT NULL AND btrim(cover_image_url) <> ''
              AND (ocr_text_from_images_upload IS NULL OR btrim(ocr_text_from_images_upload) = '')
            ORDER BY id
            LIMIT ?
            """,
            limit
        );
        int updated = 0;
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            String imageUrl = (String) row.get("image_url");
            String extracted = ocrImageUrl(imageUrl, "gallery-album-" + id);
            if (extracted == null || extracted.isBlank()) {
                continue;
            }
            String merged = mergeOcr((String) row.get("ocr_text_from_images_upload"), extracted);
            String search = joinSearch(
                (String) row.get("title"),
                (String) row.get("description"),
                (String) row.get("event_location"),
                merged
            );
            jdbcTemplate.update(
                "UPDATE gallery_album SET ocr_text_from_images_upload = ?, search_text = ?, updated_at = NOW() WHERE id = ?",
                merged,
                search,
                id
            );
            updated++;
        }
        log.info("[SearchTextBackfill] gallery_album OCR updates={}", updated);
        return updated;
    }

    private int ocrSponsorImages() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
            SELECT id, logo_url, hero_image_url, banner_image_url, ocr_text_from_images_upload,
                   name, company_name, tagline, description, type
            FROM event_sponsors
            WHERE (ocr_text_from_images_upload IS NULL OR btrim(ocr_text_from_images_upload) = '')
              AND (
                (logo_url IS NOT NULL AND btrim(logo_url) <> '')
                OR (hero_image_url IS NOT NULL AND btrim(hero_image_url) <> '')
                OR (banner_image_url IS NOT NULL AND btrim(banner_image_url) <> '')
              )
            ORDER BY id
            LIMIT ?
            """,
            limit
        );
        int updated = 0;
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            String merged = (String) row.get("ocr_text_from_images_upload");
            for (String col : List.of("logo_url", "hero_image_url", "banner_image_url")) {
                String url = (String) row.get(col);
                if (url == null || url.isBlank()) continue;
                String extracted = ocrImageUrl(url, "sponsor-" + id + "-" + col);
                if (extracted != null && !extracted.isBlank()) {
                    merged = mergeOcr(merged, extracted);
                }
            }
            if (merged == null || merged.isBlank()) continue;
            String search = joinSearch(
                (String) row.get("name"),
                (String) row.get("company_name"),
                (String) row.get("tagline"),
                (String) row.get("description"),
                (String) row.get("type"),
                merged
            );
            jdbcTemplate.update(
                "UPDATE event_sponsors SET ocr_text_from_images_upload = ?, search_text = ?, updated_at = NOW() WHERE id = ?",
                merged,
                search,
                id
            );
            updated++;
        }
        log.info("[SearchTextBackfill] event_sponsors OCR updates={}", updated);
        return updated;
    }

    private String ocrImageUrl(String imageUrl, String label) {
        Path temp = null;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl)).GET().timeout(Duration.ofSeconds(60)).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[SearchTextBackfill] Download failed for {}: HTTP {}", label, response.statusCode());
                return null;
            }
            temp = Files.createTempFile("ocr-backfill-", ".img");
            try (InputStream in = response.body()) {
                Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            net.sourceforge.tess4j.Tesseract tesseract = new net.sourceforge.tess4j.Tesseract();
            String text = tesseract.doOCR(temp.toFile());
            if (text == null) return null;
            String cleaned = text.replaceAll("\\s+", " ").trim();
            log.info("[SearchTextBackfill] OCR {} chars from {}", cleaned.length(), label);
            return cleaned;
        } catch (NoClassDefFoundError | Exception e) {
            log.warn("[SearchTextBackfill] OCR unavailable for {}: {}", label, e.getMessage());
            return null;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private static String mergeOcr(String existing, String incoming) {
        String a = existing == null ? "" : existing.trim();
        String b = incoming == null ? "" : incoming.trim();
        if (b.isEmpty()) return a;
        if (a.isEmpty()) return b;
        if (a.toLowerCase(Locale.ROOT).contains(b.toLowerCase(Locale.ROOT))) return a;
        return (a + "\n" + b).replaceAll("\n{3,}", "\n\n").trim();
    }

    private static String joinSearch(String... parts) {
        return Stream.of(parts)
            .filter(p -> p != null && !p.isBlank())
            .map(String::trim)
            .collect(Collectors.joining(" "))
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    public record BackfillResult(int structuredUpdates, int ocrUpdates, List<String> errors) {}
}
