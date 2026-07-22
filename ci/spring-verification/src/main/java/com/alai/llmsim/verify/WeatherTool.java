package com.alai.llmsim.verify;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * A real, callable tool -- not just a name Spring AI parses out of a
 * tool_calls block, but something Spring AI actually invokes. Used only
 * by the tool-call round-trip baseline test (VerificationTest's
 * openAiToolCallRoundTripActuallyExecutesAndAnswers). Every other test
 * in this module deliberately uses the raw ChatModel API, or a
 * ChatClient with no tools registered, specifically so nothing else
 * accidentally auto-executes a tool call it only means to inspect --
 * this class is never registered globally, only passed explicitly to
 * one dedicated ChatClient built inside that one test.
 *
 * Records what it was last called with, so the test can assert the
 * callback genuinely ran with the arguments llmsim's ToolCall step
 * sent, not just that Spring AI parsed a tool_calls block correctly --
 * that narrower check already exists as
 * openAiShapedClientSurfacesTheToolCall.
 */
public class WeatherTool {

    private volatile String lastCity;

    @Tool(name = "get_weather", description = "Get the current weather for a city")
    public String getWeather(@ToolParam(description = "The city name") String city) {
        lastCity = city;
        return "72F and sunny in " + city;
    }

    public String lastCity() {
        return lastCity;
    }
}
