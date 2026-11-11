package com.minsu.welocation.common;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Not every missing variable stops startup by itself: an empty REDIS_PASSWORD means "no auth", and the ranking keeps
// working on its MySQL fallback when Redis is unreachable. So in prod every connection setting is checked, and
// startup fails with the names of the missing ones. As a BeanFactoryPostProcessor this runs before any bean exists,
// so Flyway can't migrate a database before a gap elsewhere is found.
@Component
@Profile("prod")
class ProdSettingsCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    static final Map<String, String> REQUIRED = Map.of(
            "spring.datasource.url", "DB_URL",
            "spring.datasource.username", "DB_USERNAME",
            "spring.datasource.password", "DB_PASSWORD",
            "spring.data.redis.host", "REDIS_HOST",
            "spring.data.redis.port", "REDIS_PORT",
            "spring.data.redis.password", "REDIS_PASSWORD",
            "welocation.jwt.secret", "JWT_SECRET");

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(environment);
    }

    static void check(Environment environment) {
        List<String> missing = new ArrayList<>();
        REQUIRED.forEach((property, variable) -> {
            if (isMissing(environment, property)) {
                missing.add(variable);
            }
        });
        if (!missing.isEmpty()) {
            missing.sort(null);
            throw new IllegalStateException("prod profile needs these environment variables: " + String.join(", ", missing));
        }
    }

    // an unset variable leaves the prod yml's ${NAME} unresolved, which getProperty reports by throwing
    private static boolean isMissing(Environment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return value == null || value.isBlank();
        } catch (IllegalArgumentException unresolvable) {
            return true;
        }
    }
}
