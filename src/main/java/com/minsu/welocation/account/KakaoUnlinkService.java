package com.minsu.welocation.account;

import com.minsu.welocation.common.NotFoundException;
import com.minsu.welocation.common.UnauthorizedException;
import com.minsu.welocation.user.IdentityProvider;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserIdentityRepository;
import com.minsu.welocation.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

// Kakao calls this when someone disconnects WeLocation from their Kakao account or deletes that account.
// The call carries "Authorization: KakaoAK <primary admin key>", which only Kakao and this server know.
// A user who can still sign in some other way (a password or a Google account) only loses the Kakao link;
// a user Kakao was the only way in for is deleted with everything they left.
// Kakao sends each call once and never again, so a clash with the user's own requests is retried here.
@Slf4j
@Service
public class KakaoUnlinkService {

    private static final int ATTEMPTS = 3;

    private final UserIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final AccountDeletionService deletion;
    private final TransactionTemplate tx;
    private final byte[] expectedHeader;
    private final String appId;

    KakaoUnlinkService(UserIdentityRepository identityRepository, UserRepository userRepository,
                       AccountDeletionService deletion, PlatformTransactionManager transactionManager,
                       @Value("${welocation.oauth.kakao.admin-key}") String adminKey,
                       @Value("${welocation.oauth.kakao.app-id}") String appId) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.deletion = deletion;
        this.tx = new TransactionTemplate(transactionManager);
        this.expectedHeader = adminKey.isBlank() ? null : ("KakaoAK " + adminKey).getBytes(StandardCharsets.UTF_8);
        this.appId = appId;
    }

    public void unlink(String authorization, String appId, String userId, String referrerType) {
        if (expectedHeader == null) {
            throw new NotFoundException("Kakao unlink webhook is not set up");
        }
        byte[] given = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, expectedHeader)) {
            throw new UnauthorizedException("not from Kakao");
        }
        // a second guard: the key already belongs to one Kakao app, but its id says which one the call is about
        if (!this.appId.isBlank() && !this.appId.equals(appId)) {
            log.warn("Kakao unlink for app {} ignored, this server is app {}", appId, this.appId);
            return;
        }
        if (userId == null || userId.isBlank()) {
            log.warn("Kakao unlink ({}) without a user id", referrerType);
            return;
        }
        for (int attempt = 1; ; attempt++) {
            try {
                tx.executeWithoutResult(status -> apply(userId, referrerType));
                return;
            } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
                if (attempt == ATTEMPTS) {
                    // the user then has to be removed by hand; the Kakao id is enough to find them
                    log.error("Kakao unlink ({}) for Kakao user {} failed {} times", referrerType, userId, ATTEMPTS, e);
                    throw e;
                }
                log.warn("Kakao unlink ({}) for Kakao user {} clashed with another request, trying again", referrerType, userId);
            }
        }
    }

    private void apply(String kakaoId, String referrerType) {
        // locks first, so nothing the user does meanwhile slips past the deletion (see lockUserIdOf)
        Optional<Long> locked = identityRepository.lockUserIdOf(IdentityProvider.KAKAO.name(), kakaoId);
        if (locked.isEmpty()) {
            log.info("Kakao unlink ({}) for a Kakao user with no account here", referrerType);
            return;
        }
        long id = locked.get();
        User user = userRepository.findById(id).orElseThrow();
        if (user.getPasswordHash() != null || identityRepository.countByUserId(id) > 1) {
            identityRepository.findByProviderAndSubject(IdentityProvider.KAKAO, kakaoId).ifPresent(identityRepository::delete);
            log.info("Kakao unlink ({}): user {} keeps the account, Kakao sign-in removed", referrerType, id);
        } else {
            deletion.delete(id);
            log.info("Kakao unlink ({}): user {} deleted", referrerType, id);
        }
    }
}
