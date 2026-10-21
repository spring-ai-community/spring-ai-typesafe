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



import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.exception.TypeSafeInternalServerException;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.ExpectedCount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Fanning the same question out over many states, which is the shape the reranking and
 * passage-classification recipes need and the one case a single call cannot cover.
 *
 * @author Christian Tzolov
 */
class TypeSafeBatchTests {

	private static final Pattern STATE_INDEX = Pattern.compile("state (\\d+)");

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	@Test
	void keepsResultsAlignedWithTheRequestsThatProducedThem() {
		// The response is derived from the request that asked for it, so a misaligned batch
		// cannot pass: slot i must carry the answer computed from "state i". Completion
		// order under concurrency is deliberately not controlled here.
		this.mock.server().expect(ExpectedCount.times(6), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(request -> {
				String body = ((MockClientHttpRequest) request).getBodyAsString();
				Matcher matcher = STATE_INDEX.matcher(body);
				assertThat(matcher.find()).isTrue();
				double noul = Integer.parseInt(matcher.group(1)) / 10.0d;
				return MockTypeSafeServer
					.jsonResponse("{\"model\":\"jev-1.13.0\",\"answers\":{\"probe\":{\"type\":\"noul\",\"noul\":" + noul
							+ "}},\"usage\":{}}")
					.createResponse(request);
			});

		List<JevBatchResult<SystemOneResponse>> results = this.mock.client()
			.systemOneAll(requests(6), JevBatchOptions.ofConcurrency(3));

		assertThat(results).hasSize(6);
		for (int i = 0; i < 6; i++) {
			assertThat(results.get(i).index()).isEqualTo(i);
			assertThat(results.get(i).succeeded()).isTrue();
			assertThat(results.get(i).orThrow().noulValue("probe")).isEqualTo(i / 10.0d);
		}
	}

	@Test
	void oneFailureDoesNotCostTheRestOfTheBatch() {
		expectOk(0.1d);
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(400, "{\"detail\":{\"error_type\":\"api_usage_error\","
					+ "\"message\":\"Invalid request.\"}}"));
		expectOk(0.3d);

		// Width 1 so the mock server's ordered expectations line up with the requests.
		List<JevBatchResult<SystemOneResponse>> results = this.mock.client()
			.systemOneAll(requests(3), JevBatchOptions.ofConcurrency(1));

		assertThat(results.get(0).succeeded()).isTrue();
		assertThat(results.get(2).succeeded()).isTrue();

		assertThat(results.get(1).succeeded()).isFalse();
		assertThat(results.get(1).failure()).isNotNull();
		assertThat(results.get(1).failure().getMessage()).contains("Invalid request.");
		assertThat(results.get(1).orElse(null)).isNull();
		assertThatExceptionOfType(org.springaicommunity.typesafe.exception.TypeSafeBadRequestException.class)
			.isThrownBy(() -> results.get(1).orThrow());
	}

	@Test
	void failFastSkipsTheRequestsThatHadNotStarted() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(500, "boom"));

		List<JevBatchResult<SystemOneResponse>> results = this.mock.client(RetryPolicy.noRetry())
			.systemOneAll(requests(4), JevBatchOptions.ofConcurrency(1).withFailFast(true));

		assertThat(results).hasSize(4);
		assertThat(results.get(0).failure()).isInstanceOf(TypeSafeInternalServerException.class);
		// The remaining three never reached the wire, which the mock server verifies below.
		assertThat(results.subList(1, 4)).allSatisfy(result -> {
			assertThat(result.succeeded()).isFalse();
			assertThat(result.failure().getMessage()).contains("Batch aborted");
		});

		this.mock.server().verify();
	}

	@Test
	void widthOfOneMatchesSequentialResults() {
		for (int i = 0; i < 4; i++) {
			expectOk(i / 10.0d);
		}

		List<JevBatchResult<SystemOneResponse>> results = this.mock.client()
			.systemOneAll(requests(4), JevBatchOptions.ofConcurrency(1));

		assertThat(results).extracting(result -> result.orThrow().noulValue("probe"))
			.containsExactly(0.0d, 0.1d, 0.2d, 0.3d);
		this.mock.server().verify();
	}

	@Test
	void runsOnACallerSuppliedExecutorAndLeavesItOpen() {
		for (int i = 0; i < 3; i++) {
			expectOk(0.5d);
		}
		AtomicInteger submitted = new AtomicInteger();
		ExecutorService caller = Executors.newFixedThreadPool(2);
		ExecutorService counting = new java.util.concurrent.AbstractExecutorService() {
			@Override
			public void execute(Runnable command) {
				submitted.incrementAndGet();
				caller.execute(command);
			}

			@Override
			public void shutdown() {
				caller.shutdown();
			}

			@Override
			public List<Runnable> shutdownNow() {
				return caller.shutdownNow();
			}

			@Override
			public boolean isShutdown() {
				return caller.isShutdown();
			}

			@Override
			public boolean isTerminated() {
				return caller.isTerminated();
			}

			@Override
			public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
				return true;
			}
		};

		try {
			this.mock.client().systemOneAll(requests(3), JevBatchOptions.defaults().withExecutor(counting));

			assertThat(submitted.get()).isEqualTo(3);
			// A caller's executor is theirs to manage; the batch must not close it.
			assertThat(counting.isShutdown()).isFalse();
		}
		finally {
			caller.shutdownNow();
		}
	}

	@Test
	void anEmptyBatchIsNotACall() {
		assertThat(this.mock.client().systemOneAll(List.of())).isEmpty();
		this.mock.server().verify();
	}

	@Test
	void rejectsAWidthBelowOne() {
		assertThatIllegalArgumentException().isThrownBy(() -> JevBatchOptions.ofConcurrency(0))
			.withMessageContaining("concurrency must be at least 1");
	}

	private void expectOk(double noul) {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(
					"{\"model\":\"jev-1.13.0\",\"answers\":{\"probe\":{\"type\":\"noul\",\"noul\":" + noul
							+ "}},\"usage\":{}}"));
	}

	@Test
	void anUnexpectedRuntimeFailureLandsInItsSlotRatherThanOutOfTheWholeBatch() {
		// Not every failure arrives as a TypeSafeException: an interceptor, an observation
		// handler or a customized transport can throw anything. Letting it escape would
		// complete the future exceptionally and make join() throw out of systemOneAll,
		// costing the caller every result that had already come back.
		TypeSafeClient client = TypeSafeClient.builder()
			.apiKey("test-key")
			.baseUrl("http://localhost")
			.defaultModel(TypeSafeModels.JEV_LATEST)
			.restClientBuilder(RestClient.builder().requestInterceptor((request, body, execution) -> {
				throw new IllegalStateException("interceptor blew up");
			}))
			.build();

		List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(requests(2),
				JevBatchOptions.ofConcurrency(1));

		assertThat(results).hasSize(2);
		assertThat(results).allSatisfy(result -> {
			assertThat(result.succeeded()).isFalse();
			assertThat(result.failure()).isInstanceOf(TypeSafeException.class)
				.hasRootCauseInstanceOf(IllegalStateException.class);
		});
		assertThat(results.get(0).failure()).hasMessageContaining("Request 0 failed");
	}

	@Test
	void keepsBoundedExecutorRejectionsInTheirResultSlots() throws InterruptedException {
		CountDownLatch occupied = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ThreadPoolExecutor caller = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
				new ThreadPoolExecutor.AbortPolicy());
		caller.execute(() -> {
			occupied.countDown();
			try {
				release.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});

		try {
			assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
			List<JevBatchResult<SystemOneResponse>> results = this.mock.client()
				.systemOneAll(requests(2), JevBatchOptions.ofConcurrency(1).withExecutor(caller));

			assertThat(results).hasSize(2);
			assertThat(results).extracting(JevBatchResult::index).containsExactly(0, 1);
			assertThat(results).allSatisfy(result -> {
				assertThat(result.succeeded()).isFalse();
				assertThat(result.failure()).isInstanceOf(TypeSafeException.class)
					.hasRootCauseInstanceOf(RejectedExecutionException.class)
					.hasMessageContaining("rejected by the batch executor");
			});
			assertThat(caller.isShutdown()).isFalse();
			this.mock.server().verify();
		}
		finally {
			release.countDown();
			caller.shutdownNow();
		}
	}

	@Test
	void joinsAcceptedWorkAlongsideARejectedSlotWithFailFast() {
		CountDownLatch apiStarted = new CountDownLatch(1);
		CountDownLatch releaseApi = new CountDownLatch(1);
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(request -> {
				apiStarted.countDown();
				try {
					if (!releaseApi.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("rejected submission did not release the accepted call");
					}
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("accepted call was interrupted", ex);
				}
				return MockTypeSafeServer
					.jsonResponse("{\"model\":\"jev-1.13.0\",\"answers\":{"
							+ "\"probe\":{\"type\":\"noul\",\"noul\":0.5}},\"usage\":{}}")
					.createResponse(request);
			});

		ThreadPoolExecutor delegate = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
				(command, executor) -> {
					releaseApi.countDown();
					throw new RejectedExecutionException("executor is full");
				});
		AtomicInteger submissions = new AtomicInteger();
		Executor caller = command -> {
			int submission = submissions.getAndIncrement();
			delegate.execute(command);
			if (submission == 0) {
				try {
					if (!apiStarted.await(5, TimeUnit.SECONDS)) {
						throw new IllegalStateException("accepted call did not reach the API");
					}
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("waiting for the accepted call was interrupted", ex);
				}
			}
		};

		try {
			List<JevBatchResult<SystemOneResponse>> results = this.mock.client()
				.systemOneAll(requests(2),
						JevBatchOptions.ofConcurrency(1).withFailFast(true).withExecutor(caller));

			assertThat(results).hasSize(2);
			assertThat(results.get(0).succeeded()).isTrue();
			assertThat(results.get(0).orThrow().noulValue("probe")).isEqualTo(0.5d);
			assertThat(results.get(1).failure()).isInstanceOf(TypeSafeException.class)
				.hasRootCauseInstanceOf(RejectedExecutionException.class);
			assertThat(delegate.isShutdown()).isFalse();
			this.mock.server().verify();
		}
		finally {
			releaseApi.countDown();
			delegate.shutdownNow();
		}
	}

	private List<SystemOneRequest> requests(int count) {
		List<SystemOneRequest> requests = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			requests.add(SystemOneRequest.builder()
				.state("state " + i)
				.model(TypeSafeModels.JEV_LATEST)
				.questions(Map.of("probe", Noul.of("Is this a probe?")))
				.build());
		}
		return requests;
	}

}
