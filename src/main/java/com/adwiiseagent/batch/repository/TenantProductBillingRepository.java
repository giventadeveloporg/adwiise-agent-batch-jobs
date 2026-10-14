package com.adwiiseagent.batch.repository;

import com.adwiiseagent.batch.domain.TenantProductBilling;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantProductBillingRepository extends JpaRepository<TenantProductBilling, Long> {

    @Modifying
    @Query(
        "update TenantProductBilling b set b.status = 'LOCKED', b.updatedAt = :now " +
        "where b.chargeEnabled = true and b.status in ('PAYMENT_DUE', 'PAST_DUE') " +
        "and b.graceEndsAt is not null and b.graceEndsAt < :now"
    )
    int lockExpiredGrace(@Param("now") ZonedDateTime now);

    @Modifying
    @Query(
        "update TenantProductBilling b set b.status = 'LOCKED', b.updatedAt = :now " +
        "where b.chargeEnabled = true and b.billingMode = 'ONE_TIME' and b.status = 'ACTIVE' " +
        "and b.paidThroughDate is not null and b.paidThroughDate < :today"
    )
    int lockExpiredOneTime(@Param("now") ZonedDateTime now, @Param("today") LocalDate today);
}
