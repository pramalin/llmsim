package com.alai.llmsim.verify;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Exists only so Spring AI's auto-configuration has a context to attach
 * to -- this module never runs as a standalone app, only as the target
 * of {@code mvn test}. No web server, no other beans; just enough for
 * {@code @SpringBootTest} to wire an {@code OpenAiChatModel} and an
 * {@code AnthropicChatModel} pointed at llmsim (see application.yml).
 */
@SpringBootApplication
public class VerificationApplication {
}
