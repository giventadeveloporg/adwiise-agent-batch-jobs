package com.adwiiseagent.batch.repository;

import com.adwiiseagent.batch.domain.PromotionEmailSentLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PromotionEmailSentLogRepository extends JpaRepository<PromotionEmailSentLog, Long> {
}

