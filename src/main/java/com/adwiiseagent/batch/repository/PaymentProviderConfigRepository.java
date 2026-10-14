package com.adwiiseagent.batch.repository;

import com.adwiiseagent.batch.domain.PaymentProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * Repository for PaymentProviderConfig entity.
 */
@Repository
public interface PaymentProviderConfigRepository extends JpaRepository<PaymentProviderConfig, Long> {

    /**
     * Find Stripe configuration for a tenant.
     */
    Optional<PaymentProviderConfig> findByTenantIdAndProvider(String tenantId, String provider);
}






















