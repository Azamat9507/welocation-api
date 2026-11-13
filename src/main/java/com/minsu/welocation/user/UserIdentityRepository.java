package com.minsu.welocation.user;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);
}
