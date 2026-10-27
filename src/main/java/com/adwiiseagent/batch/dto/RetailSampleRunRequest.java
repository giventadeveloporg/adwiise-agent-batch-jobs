package com.adwiiseagent.batch.dto;

import lombok.Data;

/**
 * On-demand retail file run. The engine reads the bundled sample or the tenant inbox.
 */
@Data
public class RetailSampleRunRequest {
    private String tenantId;
    private String retailerType;
    private String siteType = "RETAIL";
    private String metricDate;
    private Integer stationId = 1;
    private String source = "sample";
    private Boolean post = false;
}
