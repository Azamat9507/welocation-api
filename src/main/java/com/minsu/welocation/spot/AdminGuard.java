package com.minsu.welocation.spot;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.ForbiddenException;
import com.minsu.welocation.user.Role;
import com.minsu.welocation.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AdminGuard {

    private final UserRepository userRepository;

    // read from the database, not the token: a demoted admin must lose this right immediately
    void require(CurrentUser me) {
        boolean admin = userRepository.findById(me.id()).map(user -> user.getRole() == Role.ADMIN).orElse(false);
        if (!admin) {
            throw new ForbiddenException("admins only");
        }
    }
}
