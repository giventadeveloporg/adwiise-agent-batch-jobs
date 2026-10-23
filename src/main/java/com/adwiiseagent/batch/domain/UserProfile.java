package com.adwiiseagent.batch.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;

/**
 * Entity for user profiles.
 * Simplified version for batch job processing.
 * No email unique constraint and no user_id column. Do not update user_id from email.
 */
@Entity
@Table(name = "user_profile")
@Data
public class UserProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "userProfileSeq")
    @SequenceGenerator(name = "userProfileSeq", sequenceName = "public.user_profile_id_seq", allocationSize = 1)
    @Column(name = "id")
    private Long id;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "tenant_id", length = 255)
    private String tenantId;

    @Column(name = "is_email_subscribed")
    private Boolean isEmailSubscribed;

    @Column(name = "email_subscription_token", length = 255)
    private String emailSubscriptionToken;
}

