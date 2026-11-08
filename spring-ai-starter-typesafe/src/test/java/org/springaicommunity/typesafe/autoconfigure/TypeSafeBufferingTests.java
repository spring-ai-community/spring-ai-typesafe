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

package org.springaicommunity.typesafe.autoconfigure;



import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code buffer-content} against a socket, which is the only place its effect is visible.
 *
 * <p>
 * The default JDK transport streams the request body, so the POST goes out chunked and
 * declares no length. Some APIs refuse a POST without one. Buffering assembles the body
 * before sending, which lets the request carry {@code Content-Length}. The wrapping is done
 * on a clone of a builder and the resulting factory is not reachable from any context
 * assertion, so these tests read the headers a real server received.
 *
 * @author Tiago Santos
 */
class TypeSafeBufferingTests {

	private static final String OK_BODY = """
			{"model":"jev-1.13.0","answers":{"probe":{"type":"noul","noul":0.5}},"usage":{}}""";

	private HttpServer server;

	private final Map<String, List<String>> lastRequestHeaders = new ConcurrentHashMap<>();

	private String baseUrl;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class));

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
	void bufferingMakesTheRequestCarryContentLength() {
		// Remove the wrapping from the auto-configuration and this call goes out chunked
		// again, which is what makes the assertion worth having.
		runner().withPropertyValues("spring.ai.typesafe.buffer-content=true").run(this::evaluate);

		assertThat(this.lastRequestHeaders).containsKey("Content-length");
		assertThat(this.lastRequestHeaders).doesNotContainKey("Transfer-encoding");
	}

	@Test
	void theDefaultRequestDeclaresNoLengthAndStreamsChunked() {
		// The other half: the header above comes from the property, not from the JDK
		// client sending one on its own. This is the shape some APIs reject.
		runner().run(this::evaluate);

		assertThat(this.lastRequestHeaders).doesNotContainKey("Content-length");
		assertThat(this.lastRequestHeaders.get("Transfer-encoding")).containsExactly("chunked");
	}

	private ApplicationContextRunner runner() {
		return this.contextRunner.withPropertyValues("spring.ai.typesafe.api-key=test-api-key",
				"spring.ai.typesafe.base-url=" + this.baseUrl);
	}

	private void evaluate(ApplicationContext context) {
		Map<String, Question> questions = Map.of("probe", Noul.of("Is this a probe?"));
		assertThat(context.getBean(TypeSafeClient.class).systemOne("anything", questions).noulValue("probe"))
			.isEqualTo(0.5);
	}

	private void respond(HttpExchange exchange) throws IOException {
		// HttpServer headers are a Map<String, List<String>> with this capitalization.
		this.lastRequestHeaders.clear();
		this.lastRequestHeaders.putAll(exchange.getRequestHeaders());
		exchange.getRequestBody().readAllBytes();
		byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

}
