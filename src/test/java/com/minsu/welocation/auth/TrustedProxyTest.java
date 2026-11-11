package com.minsu.welocation.auth;

import com.minsu.welocation.TestcontainersConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The login limit counts per client IP, which only a trusted proxy may tell us through X-Forwarded-For.
// MockMvc never runs Tomcat's RemoteIpValve, so these go through a real server.
class TrustedProxyTest {

    static int login(int port, String forwardedFor) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                // a new email each time, so only the per-IP limit can trip
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\": \"" + UUID.randomUUID() + "@example.com\", \"password\": \"wrong-password-1\"}"));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    static int callsUntilLimited(int port, String forwardedFor) throws Exception {
        for (int i = 1; i <= AuthService.AUTH_CALLS_PER_MINUTE_PER_IP + 1; i++) {
            if (login(port, forwardedFor) == 429) {
                return i;
            }
        }
        return -1;
    }

    // the defaults: the web app's server on the same machine is trusted
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(TestcontainersConfig.class)
    class BehindTheWebServer {

        @LocalServerPort
        int port;

        @Test
        void eachClientGetsItsOwnLimit() throws Exception {
            assertThat(callsUntilLimited(port, "203.0.113.10")).isEqualTo(AuthService.AUTH_CALLS_PER_MINUTE_PER_IP + 1);
            // another client behind the same proxy isn't blocked by the first one
            assertThat(login(port, "203.0.113.20")).isEqualTo(401);
        }
    }

    // a caller that isn't a trusted proxy can't choose its IP by sending the header itself
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "server.tomcat.remoteip.internal-proxies=10.9.9.9/32")
    @Import(TestcontainersConfig.class)
    class FromAnUntrustedCaller {

        @LocalServerPort
        int port;

        @Test
        void forwardedForIsIgnored() throws Exception {
            for (int i = 0; i < AuthService.AUTH_CALLS_PER_MINUTE_PER_IP; i++) {
                assertThat(login(port, "198.51.100." + i)).isEqualTo(401);
            }
            assertThat(login(port, "198.51.100.200")).isEqualTo(429);
        }
    }
}
