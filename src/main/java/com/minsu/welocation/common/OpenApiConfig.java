package com.minsu.welocation.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI welocationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("WeLocation API")
                .version("0.1.0")
                .description("위로가 되는 장소: cafés, beaches, lakes and viewpoints across Korea."))
                // shows the "Authorize" button; paste the accessToken from /api/auth/login there
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
