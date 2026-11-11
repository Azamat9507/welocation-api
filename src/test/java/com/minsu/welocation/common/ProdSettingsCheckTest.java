package com.minsu.welocation.common;

import com.minsu.welocation.WeLocationApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdSettingsCheckTest {

    private static MockEnvironment complete() {
        MockEnvironment environment = new MockEnvironment();
        ProdSettingsCheck.REQUIRED.keySet().forEach(property -> environment.setProperty(property, "set"));
        return environment;
    }

    @Test
    void startsWhenEverythingIsSet() {
        assertThatCode(() -> ProdSettingsCheck.check(complete())).doesNotThrowAnyException();
    }

    @Test
    void namesEveryMissingVariableWithoutPrintingValues() {
        MockEnvironment environment = complete()
                // unset: the prod yml's placeholder can't be resolved
                .withProperty("spring.data.redis.password", "${REDIS_PASSWORD}")
                // set but empty
                .withProperty("spring.data.redis.host", "")
                .withProperty("spring.datasource.password", "   ");

        assertThatThrownBy(() -> ProdSettingsCheck.check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("prod profile needs these environment variables: DB_PASSWORD, REDIS_HOST, REDIS_PASSWORD");
    }

    @Test
    void nothingSetNamesAllSeven() {
        assertThatThrownBy(() -> ProdSettingsCheck.check(new MockEnvironment()))
                .hasMessage("prod profile needs these environment variables: DB_PASSWORD, DB_URL, DB_USERNAME, "
                        + "JWT_SECRET, REDIS_HOST, REDIS_PASSWORD, REDIS_PORT");
    }

    // The real application, prod profile, with two variables missing. Nothing is reachable at these addresses: the
    // check has to stop startup before Flyway or anything else tries to connect, and name both gaps.
    @Test
    void stopsTheRealApplicationBeforeAnyBeanIsCreated() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(WeLocationApplication.class)
                .profiles("prod")
                .web(WebApplicationType.NONE)
                .properties("DB_USERNAME=unused", "DB_PASSWORD=unused", "REDIS_HOST=unreachable.invalid",
                        "REDIS_PORT=6379", "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

        assertThatThrownBy(app::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("prod profile needs these environment variables: DB_URL, REDIS_PASSWORD");
    }
}
