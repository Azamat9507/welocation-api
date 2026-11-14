package com.minsu.welocation.common;

import com.minsu.welocation.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The database and Redis still come from Testcontainers; the variables below only have to exist,
// because the prod profile has no defaults.
@SpringBootTest(properties = {
        "DB_URL=jdbc:mysql://replaced-by-testcontainers/welocation", "DB_USERNAME=unused", "DB_PASSWORD=unused",
        "REDIS_HOST=replaced-by-testcontainers", "REDIS_PORT=6379", "REDIS_PASSWORD=unused",
        // base64 of a 32-byte test value
        "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        // the 236 real spots would stay in the shared test database
        "welocation.scenes.import=false"})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ProdProfileTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Environment environment;

    @Test
    void apiDocsAndSwaggerUiAreNotServed() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
        mvc.perform(get("/api/spots")).andExpect(status().isOk());
    }

    @Test
    void jwtSecretIsRequired() {
        assertThat(environment.getProperty("welocation.jwt.require-secret", Boolean.class)).isTrue();
    }

    // "${DB_URL:localhost...}" would quietly run production against a laptop default
    @Test
    void everyConnectionSettingComesFromTheEnvironmentWithoutAFallback() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yml"));
        @SuppressWarnings("unchecked")
        Map<String, Object> prod = (Map<String, Object>) sources.getFirst().getSource();

        Map<String, String> expected = Map.of(
                "spring.datasource.url", "DB_URL",
                "spring.datasource.username", "DB_USERNAME",
                "spring.datasource.password", "DB_PASSWORD",
                "spring.data.redis.host", "REDIS_HOST",
                "spring.data.redis.port", "REDIS_PORT",
                "spring.data.redis.password", "REDIS_PASSWORD",
                "welocation.jwt.secret", "JWT_SECRET");
        expected.forEach((key, variable) ->
                assertThat(String.valueOf(prod.get(key))).as(key).isEqualTo("${" + variable + "}"));
    }
}
