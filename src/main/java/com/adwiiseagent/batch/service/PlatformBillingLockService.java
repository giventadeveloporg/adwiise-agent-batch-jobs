package com.adwiiseagent.batch.service;

import com.adwiiseagent.batch.repository.TenantProductBillingRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformBillingLockService {

    private final TenantProductBillingRepository tenantProductBillingRepository;

    @Transactional
    public int lockOverdueRows() {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        LocalDate today = now.toLocalDate();
        int graceLocked = tenantProductBillingRepository.lockExpiredGrace(now);
        int oneTimeLocked = tenantProductBillingRepository.lockExpiredOneTime(now, today);
        log.info("Platform billing lock: grace={}, oneTimePaidThrough={}", graceLocked, oneTimeLocked);
        return graceLocked + oneTimeLocked;
    }
}
