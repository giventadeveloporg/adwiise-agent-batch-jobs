package com.adwiiseagent.batch.repository;

import com.adwiiseagent.batch.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    /**
     * One message per email. Two user_profile rows may share an email when their user_id values
     * come from different Clerk applications, and those rows must stay one recipient.
     */
    @Query("SELECT DISTINCT u.email FROM UserProfile u " +
           "WHERE u.tenantId = :tenantId " +
           "AND u.isEmailSubscribed = true " +
           "AND u.email IS NOT NULL " +
           "AND u.email != '' " +
           "AND u.emailSubscriptionToken IS NOT NULL")
    List<String> findSubscribedEmailsByTenantId(@Param("tenantId") String tenantId);
}

