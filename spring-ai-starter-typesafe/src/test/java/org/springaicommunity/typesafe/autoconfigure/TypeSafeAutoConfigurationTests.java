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



import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeModels;
import org.springaicommunity.typesafe.api.TypeSafeApi;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Christian Tzolov
 */
class TypeSafeAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(TypeSafeAutoConfiguration.class));

	@Test
	void doesNothingWithoutAnApiKey() {
		this.contextRunner.run(context -> assertThat(context).doesNotHaveBean(TypeSafeClient.class));
	}

	@Test
	void createsTheClientOnceAnApiKeyIsConfigured() {
		this.contextRunner.withPropertyValues("spring.ai.typesafe.api-key=test-api-key").run(context -> {
			assertThat(context).hasSingleBean(TypeSafeClient.class);
			assertThat(context.getBean(TypeSafeClient.class).defaultModel()).isEqualTo(TypeSafeModels.JEV_LATEST);
		});
	}

	@Test
	void bindsEveryProperty() {
		this.contextRunner
			.withPropertyValues("spring.ai.typesafe.api-key=test-api-key",
					"spring.ai.typesafe.base-url=https://jev.internal.example", "spring.ai.typesafe.model=jev-1.13.0",
					"spring.ai.typesafe.timeout=42s", "spring.ai.typesafe.retry.max-retries=7",
					"spring.ai.typesafe.retry.initial-backoff=250ms", "spring.ai.typesafe.retry.max-backoff=9s",
					"spring.ai.typesafe.retry.jitter=0.5", "spring.ai.typesafe.retry.statuses=408,429,409",
					"spring.ai.typesafe.retry.respect-retry-after=false",
					"spring.ai.typesafe.retry.retry-connection-errors=false",
					"spring.ai.typesafe.retry.total-timeout=90s",
					"spring.ai.typesafe.system-one-path=/api/v2/evaluate",
					"spring.ai.typesafe.models-path=/api/v2/models",
					"spring.ai.typesafe.buffer-content=true")
			.run(context -> {
				TypeSafeProperties properties = context.getBean(TypeSafeProperties.class);
				assertThat(properties.getBaseUrl()).isEqualTo("https://jev.internal.example");
				assertThat(properties.getModel()).isEqualTo(TypeSafeModels.JEV_1_13_0);
				assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(42));
				assertThat(properties.getSystemOnePath()).isEqualTo("/api/v2/evaluate");
				assertThat(properties.getModelsPath()).isEqualTo("/api/v2/models");
				assertThat(properties.isBufferContent()).isTrue();

				RetryPolicy policy = context.getBean(TypeSafeClient.class).retryPolicy();
				assertThat(policy.maxRetries()).isEqualTo(7);
				assertThat(policy.initialBackoff()).isEqualTo(Duration.ofMillis(250));
				assertThat(policy.maxBackoff()).isEqualTo(Duration.ofSeconds(9));
				assertThat(policy.jitter()).isEqualTo(0.5d);
				assertThat(policy.retryableStatuses()).containsExactlyInAnyOrder(408, 429, 409);
				assertThat(policy.respectRetryAfter()).isFalse();
				assertThat(policy.retryConnectionErrors()).isFalse();
				assertThat(policy.totalTimeout()).isEqualTo(Duration.ofSeconds(90));

				assertThat(context.getBean(TypeSafeClient.class).defaultModel()).isEqualTo(TypeSafeModels.JEV_1_13_0);
			});
	}

	@Test
	void appliesTheDefaultRetryPolicyWhenNothingIsConfigured() {
		this.contextRunner.withPropertyValues("spring.ai.typesafe.api-key=test-api-key")
			.run(context -> assertThat(context.getBean(TypeSafeClient.class).retryPolicy())
				.isEqualTo(RetryPolicy.defaults()));
	}

	@Test
	void backsOffWhenTheApplicationDefinesItsOwnClient() {
		this.contextRunner.withPropertyValues("spring.ai.typesafe.api-key=test-api-key")
			.withUserConfiguration(CustomClientConfiguration.class)
			.run(context -> {
				assertThat(context).hasSingleBean(TypeSafeClient.class);
				assertThat(context.getBean(TypeSafeClient.class).defaultModel()).isEqualTo(TypeSafeModels.JEV_PREVIEW);
			});
	}

	@Test
	void exposesTheEndpointPathsInUse() {
		this.contextRunner.withPropertyValues("spring.ai.typesafe.api-key=test-api-key").run(context -> {
			TypeSafeAutoConfiguration.TypeSafeEndpoints endpoints = context.getBean(TypeSafeAutoConfiguration.TypeSafeEndpoints.class);
			assertThat(endpoints.systemOnePath()).isEqualTo(TypeSafeApi.DEFAULT_SYSTEM_ONE_PATH);
			assertThat(endpoints.modelsPath()).isEqualTo(TypeSafeApi.DEFAULT_MODELS_PATH);
		});
	}

	@Test
	void exposesTheConfiguredEndpointPathsRatherThanTheSdkDefaults() {
		this.contextRunner
			.withPropertyValues("spring.ai.typesafe.api-key=test-api-key",
					"spring.ai.typesafe.system-one-path=/api/v2/evaluate",
					"spring.ai.typesafe.models-path=/api/v2/models")
			.run(context -> {
				TypeSafeAutoConfiguration.TypeSafeEndpoints endpoints = context
					.getBean(TypeSafeAutoConfiguration.TypeSafeEndpoints.class);
				assertThat(endpoints.systemOnePath()).isEqualTo("/api/v2/evaluate");
				assertThat(endpoints.modelsPath()).isEqualTo("/api/v2/models");
			});
	}

	@Test
	void leavesTheEndpointPathsAndTheBufferingAtTheirDefaults() {
		this.contextRunner.withPropertyValues(
				"spring.ai.typesafe.api-key=test-api-key"
		).run(context -> {
			TypeSafeProperties properties = context.getBean(TypeSafeProperties.class);
			assertThat(properties.getSystemOnePath()).isEqualTo(TypeSafeApi.DEFAULT_SYSTEM_ONE_PATH);
			assertThat(properties.getModelsPath()).isEqualTo(TypeSafeApi.DEFAULT_MODELS_PATH);
			// Buffering costs a copy of every body, so it stays off unless asked for.
			assertThat(properties.isBufferContent()).isFalse();
		});
	}

	@Test
	void declinesToStartOnABlankApiKeyRatherThanBuildingAClientThatCannotCall() {
		// `api-key=${TYPESAFE_API_KEY:}` with the variable unset reaches the bean method
		// with a blank key, which is present enough to satisfy @ConditionalOnProperty.
		this.contextRunner.withPropertyValues("spring.ai.typesafe.api-key=").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context).getFailure().rootCause().hasMessageContaining("No API key configured");
		});
	}

	@Test
	void theConfiguredTimeoutReachesTheClientAndNotOnlyTheTransport() {
		// RetryPolicy.totalTimeout is checked against this value when deciding whether
		// another attempt fits, so a client left on the 10s default would let a call with a
		// 42s read timeout overrun its own declared budget.
		this.contextRunner
			.withPropertyValues("spring.ai.typesafe.api-key=test-api-key", "spring.ai.typesafe.timeout=42s")
			.run(context -> assertThat(context.getBean(TypeSafeClient.class).timeout())
				.isEqualTo(Duration.ofSeconds(42)));
	}

	@Test
	void doesNotRepointTheRequestFactoryOfASharedRestClientBuilder() {
		// The context's builder may be a singleton other consumers hold. Setting the
		// request factory on it in place would hand them this client's timeouts too, so
		// the autoconfiguration works on a clone.
		this.contextRunner.withPropertyValues("spring.ai.typesafe.api-key=test-api-key")
			.withUserConfiguration(SharedRestClientBuilderConfiguration.class)
			.run(context -> {
				assertThat(context).hasSingleBean(TypeSafeClient.class);

				// The bean still carries the factory the application gave it: a request
				// through it reaches the sentinel rather than a real transport.
				RestClient.Builder shared = context.getBean(RestClient.Builder.class);
				assertThatExceptionOfType(SentinelRequestException.class)
					.isThrownBy(() -> shared.build().get().uri("http://localhost/probe").retrieve().toBodilessEntity());
			});
	}

	/** Thrown by the sentinel request factory to prove which factory a builder still holds. */
	@SuppressWarnings("serial")
	static class SentinelRequestException extends RuntimeException {

		SentinelRequestException() {
			super("the application's own request factory");
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class SharedRestClientBuilderConfiguration {

		@Bean
		RestClient.Builder restClientBuilder() {
			return RestClient.builder().requestFactory((uri, httpMethod) -> {
				throw new SentinelRequestException();
			});
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomClientConfiguration {

		@Bean
		TypeSafeClient typeSafeClient() {
			return TypeSafeClient.builder()
				.apiKey("hand-rolled")
				.defaultModel(TypeSafeModels.JEV_PREVIEW)
				.restClientBuilder(RestClient.builder())
				.build();
		}

	}

}
