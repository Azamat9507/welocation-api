package com.minsu.welocation.user;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);

    long countByUserId(Long userId);

    // The user behind a sign-in, with both rows locked. As the first statement of a transaction it also keeps
    // the later plain reads current: a like, bookmark or review added for this user now waits for the commit.
    @Query(value = """
            SELECT u.id FROM user_identities i JOIN users u ON u.id = i.user_id
            WHERE i.provider = :provider AND i.subject = :subject
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockUserIdOf(String provider, String subject);

    @Modifying
    @Query("delete from UserIdentity i where i.user.id = :userId")
    int deleteAllByUser(long userId);
}
