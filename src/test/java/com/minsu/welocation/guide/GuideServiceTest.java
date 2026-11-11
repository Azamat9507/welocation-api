package com.minsu.welocation.guide;

import com.minsu.welocation.auth.CurrentUser;
import com.minsu.welocation.common.ServiceUnavailableException;
import com.minsu.welocation.spot.SpotRepository;
import com.minsu.welocation.spot.SpotService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuideServiceTest {

    static GuideProperties properties(Duration timeout, Duration deadline) {
        return new GuideProperties("key", "primary", "fallback", timeout, deadline, 1024, 0, 3, 20, 500);
    }

    @Test
    void slowModelRunsOutOfTimeAndTheFallbackIsNotStartedLate() {
        List<String> models = new ArrayList<>();
        // every call takes 300 ms and asks for another search
        ChatModel slow = prompt -> {
            models.add(prompt.getOptions().getModel());
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(
                    new AssistantMessage.ToolCall("1", "function", "searchSpots", "{}"))).build())));
        };
        SpotRepository spots = mock(SpotRepository.class);
        when(spots.findCandidates(any(), any(), any(), anyInt())).thenReturn(List.of());
        GuideService service = new GuideService(slow, ToolCallingManager.builder().build(), spots,
                mock(SpotService.class), mock(GuideQuota.class), properties(Duration.ofMillis(800), Duration.ofMillis(500)));

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.ask("카페", new CurrentUser(1)))
                .isInstanceOf(ServiceUnavailableException.class);

        // calls start at about 0 and 300 ms, before the 500 ms deadline; the next would start at 600. The margins are
        // 200 ms either way so a slow machine doesn't flip the result. The fallback isn't tried either.
        assertThat(models).containsExactly("primary", "primary");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(900));
    }

    @Test
    void fallbackIsSkippedWhenTooLittleTimeIsLeftForIt() {
        List<String> models = new ArrayList<>();
        // the primary model fails after 300 ms, leaving 200 ms of a 500 ms deadline: less than half an 800 ms timeout
        ChatModel failing = prompt -> {
            models.add(prompt.getOptions().getModel());
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("unavailable");
        };
        GuideService service = new GuideService(failing, ToolCallingManager.builder().build(), mock(SpotRepository.class),
                mock(SpotService.class), mock(GuideQuota.class), properties(Duration.ofMillis(800), Duration.ofMillis(500)));

        assertThatThrownBy(() -> service.ask("카페", new CurrentUser(1))).isInstanceOf(ServiceUnavailableException.class);
        assertThat(models).containsExactly("primary");
    }

    @Test
    void answerIsAllVisibleTextPartsWithoutThoughts() {
        AssistantMessage thought = AssistantMessage.builder().content("let me think")
                .properties(Map.of(GoogleGenAiChatModel.THOUGHT_METADATA_KEY, true)).build();
        ChatResponse response = new ChatResponse(List.of(new Generation(thought),
                new Generation(new AssistantMessage("{\"intro\": \"a\", ")),
                new Generation(new AssistantMessage("\"picks\": [], \"wantsRoute\": false}"))));

        assertThat(GuideService.answerText(response)).isEqualTo("{\"intro\": \"a\", \"picks\": [], \"wantsRoute\": false}");
    }

    @Test
    void databaseErrorsReachTheModelOnlyAsAPlainMessage() {
        SpotRepository spots = mock(SpotRepository.class);
        when(spots.findCandidates(any(), any(), any(), anyInt()))
                .thenThrow(new QueryTimeoutException("SELECT s.id FROM spots s WHERE secret_column = 1"));
        SpotSearchTool tool = new SpotSearchTool(spots, mock(SpotService.class), 3);

        assertThatThrownBy(() -> tool.searchSpots(null, null, null, null, null))
                .hasMessage("search failed; answer with the spots already found");
    }

    @Test
    void quotaDayEndsAtMidnightInKorea() {
        // 14:59 UTC is 23:59 in Seoul
        ZonedDateTime almostMidnight = Instant.parse("2026-11-12T14:59:00Z").atZone(GuideQuota.SEOUL);
        assertThat(GuideQuota.secondsToMidnight(almostMidnight)).isEqualTo(60);
    }
}
