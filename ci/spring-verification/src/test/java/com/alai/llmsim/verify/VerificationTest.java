package com.alai.llmsim.verify;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * All of ci/spring-verification's assertions, merged into one ordered
 * class rather than split across BasicVerificationTest/
 * RateLimitVerificationTest.
 *
 * Why merged: llmsim has exactly one shared script cursor for the whole
 * process, and a "reset, then discard N calls to walk to the step I
 * want" pattern turned out to be fragile in practice -- not because
 * reset doesn't work (ScriptRunner.reset is a plain positionRef.set(0),
 * provably correct), but because a call that lands past the end of the
 * script gets a 500, and both the OpenAI and Anthropic official SDKs
 * treat 500 as transient and silently retry it 2-3 times
 * (RetryingHttpClient, visible in every stack trace in this module's
 * failures). Each retry is a brand new HTTP request as far as llmsim is
 * concerned, so one exhausted call can consume far more than one
 * "slot" worth of confusion. The fix is to never let that condition
 * arise: reset exactly once for the whole class, and have every test
 * consume exactly the next step in order with a single call -- no
 * warmups, no discards, no room for retry amplification to matter.
 *
 * Requires llmsim running with
 * LLMSIM_SCRIPT=com.alai.llmsim.scripts.VerificationFlow, whose nine
 * steps this class's nine tests (the fifth disabled) consume in order.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = VerificationApplication.class)
class VerificationTest {

    @Autowired
    private OpenAiChatModel openAiChatModel;

    @Autowired
    private AnthropicChatModel anthropicChatModel;

    @BeforeAll
    static void resetOnce() {
        LlmsimTestSupport.resetLlmsim();
    }

    @Order(1)
    @Test
    void openAiShapedClientReceivesTheScriptedReply() {
        // VerificationFlow step 0.
        String content = ChatClient.create(openAiChatModel).prompt("hello").call().content();
        assertThat(content).isEqualTo("hello there world");
    }

    @Order(2)
    @Test
    void anthropicShapedClientReceivesTheScriptedReply() {
        // VerificationFlow step 1 -- a second, identical reply, since
        // each provider needs its own step under this one-call-per-test
        // design.
        String content = ChatClient.create(anthropicChatModel).prompt("hello").call().content();
        assertThat(content).isEqualTo("hello there world");
    }

    @Order(3)
    @Test
    void openAiShapedClientSurfacesTheToolCall() {
        // VerificationFlow step 2. Lower-level ChatModel API, no
        // registered tool callback: checks the tool_calls block
        // round-trips correctly, not that Spring AI can auto-invoke it.
        Prompt prompt = new Prompt(List.of(new UserMessage("what is the weather in San Francisco?")));
        ChatResponse response = openAiChatModel.call(prompt);

        assertThat(response.getResult().getOutput().hasToolCalls()).isTrue();
        assertThat(response.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(response.getResult().getOutput().getToolCalls().get(0).arguments())
                .isEqualTo("{\"city\":\"San Francisco\"}");
    }

    @Order(4)
    @Test
    void anthropicRateLimitMetadataIsPopulatedFromHeaders() {
        // VerificationFlow step 3.
        var response = ChatClient.create(anthropicChatModel).prompt("hello").call().chatResponse();
        RateLimit rateLimit = response.getMetadata().getRateLimit();

        assertThat(rateLimit.getRequestsLimit()).isEqualTo(1000L);
        assertThat(rateLimit.getRequestsRemaining()).isEqualTo(999L);
    }

    @Order(5)
    @Disabled("spring-projects/spring-ai#6607 -- OpenAI rate-limit headers "
            + "aren't wired into ChatResponseMetadata as of Spring AI 2.0.0")
    @Test
    void openAiRateLimitMetadataIsPopulatedFromHeaders() {
        // Expected to start passing once spring-ai#6607 / #6609 land.
        // When re-enabling: also confirm whether
        // spring.ai.openai.metadata.rate-limit-metrics-enabled (or its
        // 2.x equivalent, if any) needs to be set explicitly. Since this
        // test is disabled, it doesn't consume a script step -- nothing
        // else needs to shift if/when it's turned back on.
        var response = ChatClient.create(openAiChatModel).prompt("hello").call().chatResponse();
        RateLimit rateLimit = response.getMetadata().getRateLimit();
        assertThat(rateLimit.getRequestsRemaining()).isEqualTo(59L);
    }

    @Order(6)
    @Test
    void openAiToolCallRoundTripActuallyExecutesAndAnswers() {
        // VerificationFlow steps 4 and 5: llmsim returns a tool call,
        // a REAL Java @Tool actually executes (not just "Spring AI
        // parsed the block" -- that's step 3's
        // openAiShapedClientSurfacesTheToolCall, no tool registered
        // there on purpose), its real return value comes back in the
        // follow-up request, and llmsim's replyFromToolResult answers
        // from that real value. A dedicated ChatClient with the tool
        // registered is built here rather than reusing the shared
        // field, so no other test in this class risks auto-executing a
        // tool call it only means to inspect.
        //
        // The prompt text itself is irrelevant to what llmsim returns --
        // llmsim is entirely script-position-driven, it never reads the
        // prompt -- kept generic on purpose so that isn't implied.
        WeatherTool weatherTool = new WeatherTool();
        ChatClient client = ChatClient.builder(openAiChatModel)
                .defaultTools(weatherTool)
                .build();

        String answer = client.prompt("what's the weather like?").call().content();

        assertThat(weatherTool.lastCity()).isEqualTo("Boston");
        assertThat(answer).contains("72F and sunny in Boston");
    }

    @Order(7)
    @Test
    void openAiShapedClientStreamsTheScriptedReply() {
        // VerificationFlow step 6. The real gate for llmsim's SSE work:
        // not "does llmsim emit plausible-looking chunks" (llmsim's own
        // SimulatorStreamingSpec-equivalent wire-level tests already
        // cover that) but "does a real Spring AI ChatClient parse them
        // and complete cleanly". collectList().block() rather than
        // StepVerifier deliberately -- avoids adding reactor-test as a
        // new pom dependency when plain reactor-core (already present
        // transitively via Spring AI's WebClient usage) is enough to
        // prove the stream both reconstructs correctly and completes.
        Flux<String> stream = ChatClient.create(openAiChatModel).prompt("hello").stream().content();

        List<String> tokens = stream.collectList().block(Duration.ofSeconds(10));

        assertThat(tokens).isNotNull();
        assertThat(String.join("", tokens)).isEqualTo("hello there world");
    }

    @Order(8)
    @Test
    void anthropicShapedClientStreamsTheScriptedReply() {
        // VerificationFlow step 7 -- Anthropic's named-event SSE format
        // is a genuinely different wire shape from OpenAI's data-only
        // chunks, parsed by different Spring AI client code, so this is
        // not a redundant check against the OpenAI streaming test above.
        Flux<String> stream = ChatClient.create(anthropicChatModel).prompt("hello").stream().content();

        List<String> tokens = stream.collectList().block(Duration.ofSeconds(10));

        assertThat(tokens).isNotNull();
        assertThat(String.join("", tokens)).isEqualTo("hello there world");
    }

    @Order(9)
    @Test
    void openAiShapedClientSurfacesTheStreamedToolCall() {
        // VerificationFlow step 8. Lower-level ChatModel API, no
        // registered tool callback -- same reasoning as step 3's
        // non-streaming openAiShapedClientSurfacesTheToolCall: this
        // checks the tool_calls block round-trips correctly once the
        // stream completes, not that Spring AI can auto-invoke it. A
        // full streamed tool-callback round trip (mirroring
        // openAiToolCallRoundTripActuallyExecutesAndAnswers, but over a
        // stream) is deliberately not attempted here -- see
        // VerificationFlow's Javadoc for why that's its own follow-up.
        Prompt prompt = new Prompt(List.of(new UserMessage("what is the weather in Seattle?")));
        Flux<ChatResponse> stream = openAiChatModel.stream(prompt);

        List<ChatResponse> chunks = stream.collectList().block(Duration.ofSeconds(10));

        assertThat(chunks).isNotNull().isNotEmpty();

        // Searching all chunks rather than assuming the tool call lands
        // in a specific one (e.g. the last) -- deliberately not assuming
        // whether Spring AI accumulates streaming deltas into each
        // emitted ChatResponse or passes raw per-chunk deltas straight
        // through; either way, the tool call must show up somewhere.
        ChatResponse toolCallChunk = chunks.stream()
                .filter(c -> c.getResult() != null && c.getResult().getOutput() != null
                        && c.getResult().getOutput().hasToolCalls())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no chunk in the stream carried a tool call: " + chunks));

        assertThat(toolCallChunk.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("get_weather");
        assertThat(toolCallChunk.getResult().getOutput().getToolCalls().get(0).arguments())
                .isEqualTo("{\"city\":\"Seattle\"}");
    }
}
