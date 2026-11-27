package com.adwiiseagent.batch.repository;

import com.adwiiseagent.batch.domain.TenantOrganization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface TenantOrganizationRepository extends JpaRepository<TenantOrganization, Long> {
}
