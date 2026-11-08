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
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeConstants;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Jev System One client.
 *
 * <pre>{@code
 * spring.ai.typesafe.api-key=${TYPESAFE_API_KEY}
 * spring.ai.typesafe.model=jev-latest
 * spring.ai.typesafe.timeout=10s
 * spring.ai.typesafe.retry.max-retries=2
 * }</pre>
 *
 * @author Christian Tzolov
 */
@ConfigurationProperties(TypeSafeProperties.CONFIG_PREFIX)
public class TypeSafeProperties {

	public static final String CONFIG_PREFIX = "spring.ai.typesafe";

	/**
	 * TypeSafe API key. Without it no client is created.
	 */
	private @Nullable String apiKey;

	/**
	 * API root.
	 */
	private String baseUrl = TypeSafeConstants.DEFAULT_BASE_URL;

	/**
	 * Model name or alias applied to requests that do not name one.
	 */
	private String model = TypeSafeConstants.DEFAULT_MODEL;

	/**
	 * Timeout of each HTTP operation.
	 */
	private Duration timeout = TypeSafeConstants.DEFAULT_TIMEOUT;

	/**
	 * Path of the evaluation endpoint, relative to the API root.
	 */
	private String systemOnePath = TypeSafeConstants.DEFAULT_SYSTEM_ONE_PATH;

	/**
	 * Path of the model listing endpoint, relative to the API root.
	 */
	private String modelsPath = TypeSafeConstants.DEFAULT_MODELS_PATH;

	/**
	 * Whether request and response bodies are buffered in memory. Request buffering is what
	 * makes the POST carry {@code Content-Length} instead of chunked transfer encoding,
	 * which some APIs require.
	 */
	private boolean bufferContent = false;

	private final Retry retry = new Retry();

	public @Nullable String getApiKey() {
		return this.apiKey;
	}

	public void setApiKey(@Nullable String apiKey) {
		this.apiKey = apiKey;
	}

	public String getBaseUrl() {
		return this.baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public String getSystemOnePath() {
		return this.systemOnePath;
	}

	public void setSystemOnePath(String systemOnePath) {
		this.systemOnePath = systemOnePath;
	}

	public String getModelsPath() {
		return this.modelsPath;
	}

	public void setModelsPath(String modelsPath) {
		this.modelsPath = modelsPath;
	}

	public boolean isBufferContent() {
		return this.bufferContent;
	}

	public void setBufferContent(boolean bufferContent) {
		this.bufferContent = bufferContent;
	}

	public Retry getRetry() {
		return this.retry;
	}

	/**
	 * Assembles the configured {@link RetryPolicy}.
	 * @return the policy described by these properties
	 */
	public RetryPolicy toRetryPolicy() {
		return RetryPolicy.builder()
			.maxRetries(this.retry.getMaxRetries())
			.initialBackoff(this.retry.getInitialBackoff())
			.maxBackoff(this.retry.getMaxBackoff())
			.jitter(this.retry.getJitter())
			.retryableStatuses(this.retry.getStatuses())
			.respectRetryAfter(this.retry.isRespectRetryAfter())
			.retryConnectionErrors(this.retry.isRetryConnectionErrors())
			.totalTimeout(this.retry.getTotalTimeout())
			.build();
	}

	/**
	 * How transient failures are retried.
	 */
	public static class Retry {

		/**
		 * Retries after the initial attempt. Zero disables retrying.
		 */
		private int maxRetries = 2;

		/**
		 * First backoff delay, doubled on each subsequent attempt.
		 */
		private Duration initialBackoff = Duration.ofMillis(500);

		/**
		 * Upper bound on a single backoff delay.
		 */
		private Duration maxBackoff = Duration.ofSeconds(5);

		/**
		 * Fraction of each delay randomly subtracted, between 0 and 1.
		 */
		private double jitter = 0.25d;

		/**
		 * Status codes retried on top of every 5xx, which is always retried.
		 */
		private Set<Integer> statuses = RetryPolicy.DEFAULT_RETRYABLE_STATUSES;

		/**
		 * Whether a server stated wait overrides the computed backoff.
		 */
		private boolean respectRetryAfter = true;

		/**
		 * Whether failures without an HTTP response are retried.
		 */
		private boolean retryConnectionErrors = true;

		/**
		 * Budget for a whole call including waits.
		 */
		private @Nullable Duration totalTimeout = Duration.ofSeconds(30);

		public int getMaxRetries() {
			return this.maxRetries;
		}

		public void setMaxRetries(int maxRetries) {
			this.maxRetries = maxRetries;
		}

		public Duration getInitialBackoff() {
			return this.initialBackoff;
		}

		public void setInitialBackoff(Duration initialBackoff) {
			this.initialBackoff = initialBackoff;
		}

		public Duration getMaxBackoff() {
			return this.maxBackoff;
		}

		public void setMaxBackoff(Duration maxBackoff) {
			this.maxBackoff = maxBackoff;
		}

		public double getJitter() {
			return this.jitter;
		}

		public void setJitter(double jitter) {
			this.jitter = jitter;
		}

		public Set<Integer> getStatuses() {
			return this.statuses;
		}

		public void setStatuses(Set<Integer> statuses) {
			this.statuses = statuses;
		}

		public boolean isRespectRetryAfter() {
			return this.respectRetryAfter;
		}

		public void setRespectRetryAfter(boolean respectRetryAfter) {
			this.respectRetryAfter = respectRetryAfter;
		}

		public boolean isRetryConnectionErrors() {
			return this.retryConnectionErrors;
		}

		public void setRetryConnectionErrors(boolean retryConnectionErrors) {
			this.retryConnectionErrors = retryConnectionErrors;
		}

		public @Nullable Duration getTotalTimeout() {
			return this.totalTimeout;
		}

		public void setTotalTimeout(@Nullable Duration totalTimeout) {
			this.totalTimeout = totalTimeout;
		}

	}

}
