package com.minsu.welocation.guide;

import com.minsu.welocation.TestcontainersConfig;
import com.minsu.welocation.auth.TokenService;
import com.minsu.welocation.user.User;
import com.minsu.welocation.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Spring AI alone refuses to start without a key; with GuideConfig the API starts and only the guide is off.
@SpringBootTest(properties = "welocation.guide.api-key=")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class GuideNotConfiguredTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    TokenService tokenService;

    @AfterEach
    void cleanUp() {
        userRepository.deleteAll();
    }

    @Test
    void withoutAKeyTheGuideAnswers503AndTheRestWorks() throws Exception {
        User user = userRepository.save(User.signUp("nokey@example.com", "$2a$10$unused", "nokey"));
        mvc.perform(post("/api/guide").contentType(MediaType.APPLICATION_JSON).content("{\"question\": \"카페\"}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.issue(user).accessToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("the AI guide is not set up"));
        mvc.perform(get("/api/spots"))
                .andExpect(status().isOk());
    }
}
