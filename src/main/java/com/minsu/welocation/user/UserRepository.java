package com.minsu.welocation.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // A plain DELETE, not em.remove: the caller may still hold the user's sign-ins as loaded entities,
    // and removing the entity would make the flush trip over them. The context is cleared afterwards.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from User u where u.id = :id")
    int deleteNow(long id);
}
