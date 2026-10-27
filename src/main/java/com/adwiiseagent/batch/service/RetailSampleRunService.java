package com.adwiiseagent.batch.service;

import com.adwiiseagent.batch.dto.RetailSampleRunRequest;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

/**
 * Calls the AI engine file-run. This job does not parse the CSV and does not wait for night.
 */
@Service
@Slf4j
public class RetailSampleRunService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private final RestTemplate restTemplate;

    @Value("${retail.engine.base-url:http://127.0.0.1:8090}")
    private String engineBaseUrl;

    public RetailSampleRunService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public Map<String, Object> run(RetailSampleRunRequest request) {
        RetailSampleRunRequest body = request == null ? new RetailSampleRunRequest() : request;
        if (body.getSource() == null || body.getSource().isBlank()) {
            body.setSource("sample");
        }
        if (body.getSiteType() == null || body.getSiteType().isBlank()) {
            body.setSiteType("RETAIL");
        }
        if (body.getPost() == null) {
            body.setPost(false);
        }
        String base = engineBaseUrl.endsWith("/") ? engineBaseUrl.substring(0, engineBaseUrl.length() - 1) : engineBaseUrl;
        log.info(
            "Retail sample run tenant={} retailerType={} source={}",
            body.getTenantId(),
            body.getRetailerType(),
            body.getSource()
        );
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                base + "/v1/runs/from-files",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body),
                MAP
            );
            return response.getBody();
        } catch (RestClientResponseException ex) {
            log.warn("Engine file-run returned {}: {}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw ex;
        }
    }
}
