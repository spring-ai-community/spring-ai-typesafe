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



import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.api.TypeSafeApi;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.exception.TypeSafeApiResponseValidationException;
import org.springaicommunity.typesafe.exception.TypeSafeApiTimeoutException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.ListModelsResponse;
import org.springaicommunity.typesafe.response.ModelMetadata;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;

/**
 * The entry point of the Jev SDK. Wraps {@link TypeSafeApi} with a default model, a retry
 * policy and the typed exceptions, and stitches the {@code x-typesafe-request-id} header
 * onto the response so it travels with the data it describes.
 *
 * <pre>{@code
 * TypeSafeClient client = TypeSafeClient.builder().apiKey(System.getenv("TYPESAFE_API_KEY")).build();
 *
 * SystemOneResponse response = client.systemOne(
 *         Map.of("message", "Help! My payouts have been failing for 3 days."),
 *         Map.of("is_urgent", Noul.of("Does this convey urgency?"),
 *                "department", Choice.builder()
 *                        .instructions("Which team should handle this?")
 *                        .option("billing", "Payments, invoicing, refunds")
 *                        .option("technical", "Bugs, outages, integrations")
 *                        .build()));
 *
 * if (response.noulValue("is_urgent") > 0.8) {
 *     escalate(response.choiceValue("department"));
 * }
 * }</pre>
 *
 * Every question of a call is answered against the same state in parallel, so asking
 * several narrow questions costs little more than asking one, and each answer stays
 * independently thresholdable.
 *
 * @author Christian Tzolov
 */
public class TypeSafeClient {

	private static final Logger logger = LoggerFactory.getLogger(TypeSafeClient.class);

	private final TypeSafeApi typeSafeApi;

	private final String defaultModel;

	private final RetryPolicy retryPolicy;

	private final @Nullable Duration perAttemptTimeout;

	/**
	 * Creates a client over an API instance whose per-attempt timeout is not known. The
	 * retry budget then bounds only the waits between attempts, not the final attempt.
	 * @param typeSafeApi the transport layer
	 * @param defaultModel the model applied to a request that does not name one
	 * @param retryPolicy how transient failures are handled
	 */
	public TypeSafeClient(TypeSafeApi typeSafeApi, String defaultModel, RetryPolicy retryPolicy) {
		this(typeSafeApi, defaultModel, retryPolicy, null);
	}

	/**
	 * Creates a client over an API instance.
	 * @param typeSafeApi the transport layer
	 * @param defaultModel the model applied to a request that does not name one
	 * @param retryPolicy how transient failures are handled
	 * @param perAttemptTimeout the HTTP timeout of a single attempt. It is counted against
	 * {@link RetryPolicy#totalTimeout()} before another attempt is started, so the budget
	 * bounds the whole call rather than just the waits; {@code null} when unknown
	 */
	public TypeSafeClient(TypeSafeApi typeSafeApi, String defaultModel, RetryPolicy retryPolicy,
			@Nullable Duration perAttemptTimeout) {
		Assert.notNull(typeSafeApi, "typeSafeApi must not be null");
		Assert.hasText(defaultModel, "defaultModel must not be empty");
		Assert.notNull(retryPolicy, "retryPolicy must not be null");
		this.typeSafeApi = typeSafeApi;
		this.defaultModel = defaultModel;
		this.retryPolicy = retryPolicy;
		this.perAttemptTimeout = perAttemptTimeout;
	}

	/**
	 * Answers questions about a text state.
	 * @param state the content to evaluate
	 * @param questions the questions, keyed by the names their answers will carry
	 * @return the answers
	 */
	public SystemOneResponse systemOne(String state, Map<String, ? extends Question> questions) {
		return systemOne(JsonContent.of(state), questions);
	}

	/**
	 * Answers questions about a structured state. Naming the parts of the state is the
	 * recommended shape: it lets a question point at a field instead of relying on
	 * position inside a serialized blob.
	 * @param state the content to evaluate
	 * @param questions the questions, keyed by the names their answers will carry
	 * @return the answers
	 */
	public SystemOneResponse systemOne(Map<String, ?> state, Map<String, ? extends Question> questions) {
		return systemOne(JsonContent.of(state), questions);
	}

	/**
	 * Answers questions about a sequence, for example the turns of a conversation.
	 * @param state the content to evaluate
	 * @param questions the questions, keyed by the names their answers will carry
	 * @return the answers
	 */
	public SystemOneResponse systemOne(List<?> state, Map<String, ? extends Question> questions) {
		return systemOne(JsonContent.of(state), questions);
	}

	/**
	 * Answers questions about a state.
	 * @param state the content to evaluate
	 * @param questions the questions, keyed by the names their answers will carry
	 * @return the answers
	 */
	public SystemOneResponse systemOne(JsonContent state, Map<String, ? extends Question> questions) {
		return systemOne(SystemOneRequest.builder().state(state).model(this.defaultModel).questions(questions).build());
	}

	/**
	 * Answers a fully assembled request, applying the client's default model when the
	 * request does not name one.
	 * @param request the request
	 * @return the answers, carrying the request id from the response headers
	 */
	public SystemOneResponse systemOne(SystemOneRequest request) {
		Assert.notNull(request, "request must not be null");
		SystemOneRequest effective = StringUtils.hasText(request.model()) ? request
				: request.withModel(this.defaultModel);

		return execute("POST /v1/systemone", () -> {
			ResponseEntity<SystemOneResponse> entity = this.typeSafeApi.systemOne(effective);
			SystemOneResponse body = entity.getBody();
			if (body == null) {
				throw new TypeSafeApiResponseValidationException("The evaluation response had no body",
						entity.getStatusCode().value(), null, entity.getHeaders(), "POST /v1/systemone", null);
			}
			if (body.answers().isEmpty()) {
				throw new TypeSafeApiResponseValidationException("The evaluation response carried no answers",
						entity.getStatusCode().value(), null, entity.getHeaders(), "POST /v1/systemone", "answers");
			}
			return body.withRequestId(entity.getHeaders().getFirst(TypeSafeApiException.REQUEST_ID_HEADER));
		});
	}

	/**
	 * Answers several independent requests concurrently, four at a time.
	 * @param requests the requests
	 * @return one result per request, in the order submitted
	 * @see #systemOneAll(List, JevBatchOptions)
	 */
	public List<JevBatchResult<SystemOneResponse>> systemOneAll(List<SystemOneRequest> requests) {
		return systemOneAll(requests, JevBatchOptions.defaults());
	}

	/**
	 * Answers several independent requests concurrently.
	 *
	 * <p>
	 * Reach for this only when the states genuinely differ. Asking several questions
	 * <em>about the same state</em> is not a batch: pack them into one
	 * {@link #systemOne(JsonContent, Map) systemOne} call, where the service answers them in
	 * parallel against a state it reads once. A batch is for the other shape — the same
	 * question asked about many states, such as scoring each candidate passage of a search
	 * result, which is irreducibly one call per item.
	 *
	 * <p>
	 * Each request carries the client's own retry policy, so a transient failure is retried
	 * within the request rather than failing its slot. A request that fails for good does
	 * not abort the batch: its slot holds the failure and the rest still run, unless
	 * {@link JevBatchOptions#failFast()} is set. Results stay aligned with the input, so
	 * {@code results.get(i)} always answers {@code requests.get(i)}.
	 * @param requests the requests
	 * @param options how wide to run, and on which executor
	 * @return one result per request, in the order submitted
	 */
	public List<JevBatchResult<SystemOneResponse>> systemOneAll(List<SystemOneRequest> requests,
			JevBatchOptions options) {
		Assert.notNull(requests, "requests must not be null");
		Assert.noNullElements(requests.toArray(), "requests must not contain a null request");
		Assert.notNull(options, "options must not be null");
		if (requests.isEmpty()) {
			return List.of();
		}

		int width = Math.min(options.concurrency(), requests.size());
		ExecutorService owned = (options.executor() == null) ? Executors.newFixedThreadPool(width, batchThreadFactory())
				: null;
		Executor executor = (owned != null) ? owned : options.executor();
		// Bounds in-flight work even when the caller supplied an unbounded executor.
		Semaphore permits = new Semaphore(options.concurrency());
		AtomicBoolean aborted = new AtomicBoolean(false);

		try {
			List<CompletableFuture<JevBatchResult<SystemOneResponse>>> futures = new ArrayList<>(requests.size());
			for (int i = 0; i < requests.size(); i++) {
				int index = i;
				SystemOneRequest request = requests.get(i);
				futures.add(CompletableFuture.supplyAsync(() -> runOne(index, request, options, permits, aborted),
						executor));
			}
			return futures.stream().map(CompletableFuture::join).toList();
		}
		finally {
			if (owned != null) {
				owned.shutdown();
			}
		}
	}

	private JevBatchResult<SystemOneResponse> runOne(int index, SystemOneRequest request, JevBatchOptions options,
			Semaphore permits, AtomicBoolean aborted) {
		if (options.failFast() && aborted.get()) {
			return new JevBatchResult<>(index, null,
					new TypeSafeException("Batch aborted before this request ran because failFast is set"));
		}
		permits.acquireUninterruptibly();
		try {
			if (options.failFast() && aborted.get()) {
				return new JevBatchResult<>(index, null,
						new TypeSafeException("Batch aborted before this request ran because failFast is set"));
			}
			return new JevBatchResult<>(index, systemOne(request), null);
		}
		catch (TypeSafeException ex) {
			aborted.set(true);
			return new JevBatchResult<>(index, null, ex);
		}
		catch (RuntimeException ex) {
			// Anything else — an interceptor, an observation handler, a customized
			// transport. Letting it escape would complete this future exceptionally and
			// make join() throw out of systemOneAll, costing the caller every result that
			// had already come back. A slot is the right place for it.
			aborted.set(true);
			return new JevBatchResult<>(index, null,
					new TypeSafeException("Request %d failed: %s".formatted(index, ex.getMessage()), ex));
		}
		finally {
			permits.release();
		}
	}

	private static ThreadFactory batchThreadFactory() {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, "jev-batch-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	/**
	 * Lists the models available to the account.
	 * @return the models, never {@code null}
	 */
	public List<ModelMetadata> listModels() {
		return execute("GET /v1/models", () -> {
			ResponseEntity<ListModelsResponse> entity = this.typeSafeApi.listModels();
			ListModelsResponse body = entity.getBody();
			if (body == null) {
				throw new TypeSafeApiResponseValidationException("The model listing response had no body",
						entity.getStatusCode().value(), null, entity.getHeaders(), "GET /v1/models", null);
			}
			return body.models();
		});
	}

	/**
	 * @return the model applied to requests that do not name one
	 */
	public String defaultModel() {
		return this.defaultModel;
	}

	/**
	 * The HTTP timeout of a single attempt, as declared to this client. It is what
	 * {@link RetryPolicy#totalTimeout()} counts the next attempt against; {@code null} when
	 * nothing was declared and the transport's own default applies.
	 * @return the per-attempt timeout, or {@code null}
	 */
	public @Nullable Duration timeout() {
		return this.perAttemptTimeout;
	}

	/**
	 * @return the retry policy in force
	 */
	public RetryPolicy retryPolicy() {
		return this.retryPolicy;
	}

	/**
	 * Runs one call, translating transport failures and repeating the attempt while the
	 * retry policy says a repeat is worthwhile and the budget allows it.
	 */
	private <T> T execute(String endpoint, Supplier<T> call) {
		long startedAt = System.nanoTime();
		int attempt = 0;
		TypeSafeException lastFailure;

		while (true) {
			attempt++;
			try {
				return call.get();
			}
			catch (TypeSafeException ex) {
				lastFailure = ex;
			}
			catch (ResourceAccessException ex) {
				lastFailure = toConnectionException(endpoint, ex);
			}
			// The two below are body-decoding failures on a response that did arrive. They
			// used to escape as raw Spring exceptions, bypassing the typed contract and
			// the retry policy. Neither is retryable: the server answered, just not with
			// the JSON this SDK understands.
			catch (UnknownContentTypeException ex) {
				HttpHeaders headers = ex.getResponseHeaders() != null ? ex.getResponseHeaders() : new HttpHeaders();
				lastFailure = new TypeSafeApiResponseValidationException(
						"Jev %s returned %s where JSON was expected".formatted(endpoint, ex.getContentType()),
						ex.getStatusCode().value(), ex.getResponseBodyAsString(), headers, endpoint, null);
			}
			catch (RestClientException ex) {
				lastFailure = new TypeSafeException(
						"Jev %s response could not be read: %s".formatted(endpoint, ex.getMessage()), ex);
			}

			if (attempt > this.retryPolicy.maxRetries() || !this.retryPolicy.isRetryable(lastFailure)) {
				throw lastFailure;
			}

			Duration backoff = this.retryPolicy.backoffFor(attempt, lastFailure);
			if (exceedsBudget(startedAt, backoff)) {
				if (logger.isDebugEnabled()) {
					logger.debug("Jev {} giving up after attempt {}: the retry budget cannot absorb another {}ms wait",
							endpoint, attempt, backoff.toMillis());
				}
				throw lastFailure;
			}

			if (logger.isDebugEnabled()) {
				logger.debug("Jev {} attempt {} failed ({}), retrying in {}ms", endpoint, attempt,
						lastFailure.getMessage(), backoff.toMillis());
			}
			sleep(backoff, lastFailure);
		}
	}

	private boolean exceedsBudget(long startedAt, Duration backoff) {
		Duration budget = this.retryPolicy.totalTimeout();
		if (budget == null) {
			return false;
		}
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
		// Count the next attempt's own timeout, not only the wait before it. Otherwise the
		// last attempt can overshoot the budget by a whole HTTP timeout, and "30s budget"
		// would really mean "up to 30s plus one more request".
		Duration nextAttempt = this.perAttemptTimeout == null ? Duration.ZERO : this.perAttemptTimeout;
		return elapsed.plus(backoff).plus(nextAttempt).compareTo(budget) >= 0;
	}

	private void sleep(Duration backoff, TypeSafeException lastFailure) {
		if (backoff.isZero() || backoff.isNegative()) {
			return;
		}
		try {
			Thread.sleep(backoff.toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw lastFailure;
		}
	}

	private TypeSafeException toConnectionException(String endpoint, ResourceAccessException ex) {
		Throwable cause = ex.getCause();
		if (cause instanceof SocketTimeoutException || cause instanceof HttpConnectTimeoutException
				|| cause instanceof java.net.http.HttpTimeoutException) {
			return new TypeSafeApiTimeoutException("Jev %s timed out".formatted(endpoint), null, ex);
		}
		return new TypeSafeApiConnectionException("Jev %s failed without an HTTP response".formatted(endpoint), ex);
	}

	/**
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link TypeSafeClient}.
	 *
	 * <p>
	 * Unset values fall back to the environment variables named in
	 * {@link TypeSafeConstants}, so a client built with no arguments works in an
	 * environment configured the way the other TypeSafe SDKs expect.
	 */
	public static final class Builder {

		private @Nullable Supplier<String> apiKey;

		private @Nullable String baseUrl;

		private @Nullable String defaultModel;

		private Duration timeout = TypeSafeConstants.DEFAULT_TIMEOUT;

		private RetryPolicy retryPolicy = RetryPolicy.defaults();

		private HttpHeaders headers = new HttpHeaders();

		private RestClient.@Nullable Builder restClientBuilder;

		private @Nullable TypeSafeApi typeSafeApi;

		private Builder() {
		}

		public Builder apiKey(String apiKey) {
			Assert.hasText(apiKey, "apiKey must not be empty");
			return apiKey(() -> apiKey);
		}

		/**
		 * Supplies the bearer token per request, so a rotated key takes effect without
		 * rebuilding the client.
		 * @param apiKey the token supplier
		 * @return this builder
		 */
		public Builder apiKey(Supplier<String> apiKey) {
			Assert.notNull(apiKey, "apiKey must not be null");
			this.apiKey = apiKey;
			return this;
		}

		public Builder baseUrl(String baseUrl) {
			Assert.hasText(baseUrl, "baseUrl must not be empty");
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder defaultModel(String defaultModel) {
			Assert.hasText(defaultModel, "defaultModel must not be empty");
			this.defaultModel = defaultModel;
			return this;
		}

		/**
		 * Sets the per operation HTTP timeout. Not applied to the transport when
		 * {@link #restClientBuilder(RestClient.Builder)} supplies it, since that builder
		 * then owns its own timeouts — but it is still counted against
		 * {@link RetryPolicy#totalTimeout()} before each retry, so declare the transport's
		 * real timeout here even when supplying your own builder.
		 * @param timeout the timeout
		 * @return this builder
		 */
		public Builder timeout(Duration timeout) {
			Assert.notNull(timeout, "timeout must not be null");
			this.timeout = timeout;
			return this;
		}

		public Builder retryPolicy(RetryPolicy retryPolicy) {
			Assert.notNull(retryPolicy, "retryPolicy must not be null");
			this.retryPolicy = retryPolicy;
			return this;
		}

		/**
		 * Adds headers sent on every request.
		 * @param headers the headers
		 * @return this builder
		 */
		public Builder headers(HttpHeaders headers) {
			Assert.notNull(headers, "headers must not be null");
			// Accumulate into a builder-owned copy: a second call adds to the first rather
			// than replacing it, and a caller mutating their own instance afterwards does
			// not reach the client.
			this.headers.addAll(headers);
			return this;
		}

		/**
		 * Supplies the transport. The builder is cloned rather than mutated, and its
		 * request factory is left untouched, so a caller keeps full control of timeouts,
		 * interceptors and observability. This is also the hook a test uses to bind a
		 * {@code MockRestServiceServer}.
		 * @param restClientBuilder the builder to derive the HTTP client from
		 * @return this builder
		 */
		public Builder restClientBuilder(RestClient.Builder restClientBuilder) {
			Assert.notNull(restClientBuilder, "restClientBuilder must not be null");
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		/**
		 * Uses a pre-built transport layer, bypassing every other transport setting.
		 * @param typeSafeApi the API instance
		 * @return this builder
		 */
		public Builder typeSafeApi(TypeSafeApi typeSafeApi) {
			Assert.notNull(typeSafeApi, "typeSafeApi must not be null");
			this.typeSafeApi = typeSafeApi;
			return this;
		}

		public TypeSafeClient build() {
			String model = firstNonBlank(this.defaultModel, System.getenv(TypeSafeConstants.DEFAULT_MODEL_ENV),
					TypeSafeConstants.DEFAULT_MODEL);

			if (this.typeSafeApi != null) {
				return new TypeSafeClient(this.typeSafeApi, model, this.retryPolicy, this.timeout);
			}

			Supplier<String> key = this.apiKey;
			if (key == null) {
				String fromEnv = System.getenv(TypeSafeConstants.API_KEY_ENV);
				Assert.hasText(fromEnv,
						"No API key configured. Call apiKey(..) or set the " + TypeSafeConstants.API_KEY_ENV
								+ " environment variable.");
				key = () -> fromEnv;
			}

			String url = firstNonBlank(this.baseUrl, System.getenv(TypeSafeConstants.BASE_URL_ENV),
					TypeSafeConstants.DEFAULT_BASE_URL);

			RestClient.Builder transport = this.restClientBuilder;
			if (transport == null) {
				JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
				requestFactory.setReadTimeout(this.timeout);
				transport = RestClient.builder().requestFactory(requestFactory);
			}

			// A copy, so that reusing this builder after build() cannot alter the client.
			HttpHeaders headersCopy = new HttpHeaders();
			headersCopy.addAll(this.headers);

			TypeSafeApi api = TypeSafeApi.builder()
				.baseUrl(url)
				.apiKey(key)
				.headers(headersCopy)
				.restClientBuilder(transport)
				.build();

			return new TypeSafeClient(api, model, this.retryPolicy, this.timeout);
		}

		private static String firstNonBlank(String... candidates) {
			for (String candidate : candidates) {
				if (StringUtils.hasText(candidate)) {
					return candidate;
				}
			}
			throw new IllegalStateException("No non-blank candidate available");
		}

	}
}
