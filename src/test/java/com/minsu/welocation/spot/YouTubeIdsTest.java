package com.minsu.welocation.spot;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class YouTubeIdsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://youtube.com/shorts/dQw4w9WgXcQ?si=abc123",
            "youtube.com/shorts/dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10s",
            "https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=x",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
            "  https://www.youtube.com/shorts/dQw4w9WgXcQ  "})
    void readsTheVideoId(String input) {
        assertThat(YouTubeIds.parse(input)).contains("dQw4w9WgXcQ");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "dQw4w9WgXc",
            "https://evil.example/shorts/dQw4w9WgXcQ",
            "https://youtube.com.evil.example/shorts/dQw4w9WgXcQ",
            "javascript:alert(1)//youtube.com/shorts/dQw4w9WgXcQ",
            "ftp://youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ\"onload=x",
            "https://www.youtube.com/@channel",
            "https://www.youtube.com/watch?list=dQw4w9WgXcQ"})
    void rejectsAnythingElse(String input) {
        assertThat(YouTubeIds.parse(input)).isEmpty();
    }
}
