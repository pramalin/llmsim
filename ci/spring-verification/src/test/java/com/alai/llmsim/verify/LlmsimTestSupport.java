package com.alai.llmsim.verify;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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

    /**
     * Polls GET /_llmsim/calls every 500ms until it returns a non-empty
     * array or the timeout elapses -- the same "poll rather than guess
     * a single wait" discipline llmsim's own DisconnectSpec.scala test
     * uses, and for the same reason: TCP-level detection of a dead
     * connection commonly needs more than one write attempt to
     * surface, so a single fixed wait is fragile regardless of how
     * generous it looks on paper.
     *
     * A deliberately loose check -- "the journal is non-empty" rather
     * than decoding and asserting a specific CallOutcome -- since this
     * module's job is confirming Spring AI's real client behaves
     * correctly against llmsim, not re-verifying llmsim's own journal
     * shape, which SimulatorSpec.scala and DisconnectSpec.scala in the
     * main project already cover thoroughly.
     */
    static boolean pollForNonEmptyJournal(Duration timeout) {
        String baseUrl = System.getenv().getOrDefault("LLMSIM_ANTHROPIC_BASE_URL", "http://localhost:8089");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/_llmsim/calls")).GET().build();
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            try {
                HttpResponse<String> response = HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2 && !response.body().trim().equals("[]")) {
                    return true;
                }
            } catch (IOException | InterruptedException e) {
                // Fall through and retry -- a transient failure here
                // shouldn't end the poll early.
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
