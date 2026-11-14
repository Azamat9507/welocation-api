package com.minsu.welocation.account;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Reached through the web app's /auth/kakao-unlink, which passes Kakao's request on unchanged.
// 200 for every call that carries the right key, also when the user is unknown: Kakao only looks at the status.
@Hidden
@RestController
@RequiredArgsConstructor
public class KakaoUnlinkController {

    private final KakaoUnlinkService service;

    @PostMapping(path = "/api/auth/oauth/kakao/unlink", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void unlink(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                       @RequestParam(name = "app_id", required = false) String appId,
                       @RequestParam(name = "user_id", required = false) String userId,
                       @RequestParam(name = "referrer_type", required = false) String referrerType) {
        service.unlink(authorization, appId, userId, referrerType);
    }
}
