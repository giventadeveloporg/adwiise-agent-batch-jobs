package com.adwiiseagent.batch.repository;

import com.adwiiseagent.batch.domain.EventCompetitionGroupMember;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EventCompetitionGroupMemberRepository extends JpaRepository<EventCompetitionGroupMember, Long> {
  List<EventCompetitionGroupMember> findByRegistrationIdAndTenantIdOrderBySortOrderAsc(Long registrationId, String tenantId);
}
