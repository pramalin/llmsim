package com.alai.llmsim.verify;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * llmsim has exactly one script cursor per running instance -- no
 * per-request session concept (see the roadmap's note on why that was
 * deliberately not built, in favor of one ephemeral llmsim instance per
 * test run). That means two test methods sharing one llmsim instance
 * are NOT independent by default: whichever runs second gets whatever
 * step the first one's calls advanced the cursor to, and JUnit does not
 * guarantee method or class execution order unless explicitly declared.
 *
 * Rather than relying on that order, every test resets the cursor to
 * position 0 itself before making any calls, then makes exactly as many
 * calls as needed to reach the step it cares about, discarding any
 * earlier ones. Slightly wasteful, but makes every test independently
 * correct regardless of what ran before it or in what order.
 */
final class LlmsimTestSupport {

    private LlmsimTestSupport() {
    }

    static void resetLlmsim() {
        String baseUrl = System.getenv().getOrDefault("LLMSIM_ANTHROPIC_BASE_URL", "http://localhost:8089");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/_llmsim/reset"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<Void> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("llmsim reset failed: HTTP " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not reset llmsim at " + baseUrl
                    + " -- is it running with LLMSIM_SCRIPT=com.alai.llmsim.scripts.VerificationFlow?", e);
        }
    }
}
