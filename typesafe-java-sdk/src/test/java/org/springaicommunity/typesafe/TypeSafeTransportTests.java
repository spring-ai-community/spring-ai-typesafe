/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.typesafe;



import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.exception.TypeSafeApiTimeoutException;
import org.springaicommunity.typesafe.question.Noul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The transport this SDK builds for itself, against a socket.
 *
 * <p>
 * {@code MockRestServiceServer} cannot reach the request factory: it is bound to the
 * {@link org.springframework.web.client.RestClient.Builder}, and the factory the client
 * builds for itself is never consulted by anything a test can inspect. What the factory is
 * for shows up only in the requests that reach a server: the timeout it enforces, and
 * whether the request declares its length. These tests answer those questions the only way
 * they can be answered: a server that reads headers and takes too long to answer.
 *
 * @author Tiago Santos
 */
class TypeSafeTransportTests {

	private static final String OK_BODY = """
			{"model":"jev-1.13.0","answers":{"probe":{"type":"noul","noul":0.5}},"usage":{}}""";

	/** Long enough that a client without a read timeout answers, short enough to stay fast. */
	private static final long SLOW_DELAY_MS = 400;

	private HttpServer server;

	private final Map<String, List<String>> lastRequestHeaders = new ConcurrentHashMap<>();

	/** How long the server waits before answering; only the timeout tests raise it. */
	private volatile long serverDelayMs = 0;

	private String baseUrl;

	@BeforeEach
	void startServer() throws IOException {
		this.lastRequestHeaders.clear();
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/v1/systemone", this::respond);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		this.server.stop(0);
	}

	@Test
	void theConfiguredTimeoutReachesTheTransportItBuilds() {
		// No restClientBuilder, so the client builds its own JdkClientHttpRequestFactory.
		// Remove the setReadTimeout call and this succeeds on the factory default instead of
		// timing out, which is what makes the assertion worth having.
		this.serverDelayMs = SLOW_DELAY_MS;
		TypeSafeClient client = TypeSafeClient.builder()
			.apiKey("test-api-key")
			.baseUrl(this.baseUrl)
			.timeout(Duration.ofMillis(50))
			.retryPolicy(RetryPolicy.noRetry())
			.build();

		assertThatExceptionOfType(TypeSafeApiTimeoutException.class)
			.isThrownBy(() -> client.systemOne("anything", Map.of("probe", Noul.of("Is this a probe?"))))
			.withMessageContaining("POST /v1/systemone timed out");
	}

	@Test
	void answersWhenNoTimeoutIsDeclaredAndTheServerIsMerelySlow() {
		// Proves the call above ends because of the timeout, and not because of the socket,
		// the delay or the exception mapping.
		this.serverDelayMs = SLOW_DELAY_MS;
		TypeSafeClient client = TypeSafeClient.builder()
			.apiKey("test-api-key")
			.baseUrl(this.baseUrl)
			.retryPolicy(RetryPolicy.noRetry())
			.build();

		assertThat(client.systemOne("anything", Map.of("probe", Noul.of("Is this a probe?"))).noulValue("probe"))
			.isEqualTo(0.5);
	}

	@Test
	void bufferContentMakesTheRequestItBuildsCarryContentLength() {
		// The default transport streams the body, so the POST goes out chunked and declares
		// no length. Some APIs refuse a POST without one. Wrapping assembles the body
		// first, and the request states its length.
		TypeSafeClient client = TypeSafeClient.builder()
			.apiKey("test-api-key")
			.baseUrl(this.baseUrl)
			.bufferContent(true)
			.retryPolicy(RetryPolicy.noRetry())
			.build();

		assertThat(client.systemOne("anything", Map.of("probe", Noul.of("Is this a probe?"))).noulValue("probe"))
			.isEqualTo(0.5);
		assertThat(this.lastRequestHeaders).containsKey("Content-length");
		assertThat(this.lastRequestHeaders).doesNotContainKey("Transfer-encoding");
	}

	@Test
	void theDefaultTransportStreamsTheBodyChunked() {
		// The other half: the header above comes from bufferContent, not from the JDK
		// client sending one on its own. This is the shape those APIs refuse.
		TypeSafeClient client = TypeSafeClient.builder()
			.apiKey("test-api-key")
			.baseUrl(this.baseUrl)
			.retryPolicy(RetryPolicy.noRetry())
			.build();

		client.systemOne("anything", Map.of("probe", Noul.of("Is this a probe?")));

		assertThat(this.lastRequestHeaders).doesNotContainKey("Content-length");
		assertThat(this.lastRequestHeaders.get("Transfer-encoding")).containsExactly("chunked");
	}

	private void respond(HttpExchange exchange) throws IOException {
		// HttpServer headers are a Map<String, List<String>> with this capitalization.
		this.lastRequestHeaders.clear();
		this.lastRequestHeaders.putAll(exchange.getRequestHeaders());
		exchange.getRequestBody().readAllBytes();
		if (this.serverDelayMs > 0) {
			try {
				Thread.sleep(this.serverDelayMs);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

}
