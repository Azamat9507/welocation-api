package com.minsu.welocation.common;

import com.minsu.welocation.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class OpenApiTest {

    @Autowired
    MockMvc mvc;

    // without @ParameterObject the search records would show up as one opaque "search" object
    @Test
    void documentsSearchRecordsAsSeparateQueryParameters() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("WeLocation API"))
                .andExpect(jsonPath("$.paths['/api/spots/nearby'].get.parameters[*].name")
                        .value(hasItems("lat", "lng", "radiusKm", "category", "size")))
                .andExpect(jsonPath("$.paths['/api/spots'].get.parameters[*].name")
                        .value(hasItems("region", "category", "tag", "page", "size")));
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
