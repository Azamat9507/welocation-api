package com.minsu.welocation.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

// A Google or Kakao account linked to a user, found again by the provider's own id for that person
@Entity
@Table(name = "user_identities",
        uniqueConstraints = @UniqueConstraint(name = "uk_identity_provider_subject", columnNames = {"provider", "subject"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IdentityProvider provider;

    @Column(nullable = false, length = 100)
    private String subject;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public UserIdentity(User user, IdentityProvider provider, String subject) {
        this.user = user;
        this.provider = provider;
        this.subject = subject;
    }
}
