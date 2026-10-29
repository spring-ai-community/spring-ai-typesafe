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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.exception.TypeSafeInternalServerException;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * {@link TypeSafeClient#systemOneAsync(SystemOneRequest, Executor)}: the call runs on the
 * caller's executor and fails the future with what the blocking call would have thrown.
 */
class TypeSafeAsyncTests {

	private static final String BODY = "{\"model\":\"jev-1.13.0\",\"answers\":{\"probe\":{\"type\":\"noul\",\"noul\":0.7}},\"usage\":{}}";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	private static SystemOneRequest request() {
		return SystemOneRequest.builder().state("hello").question("probe", Noul.of("Is it?")).build();
	}

	@Test
	void runsTheCallOnTheSuppliedExecutor() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(BODY));
		AtomicReference<Thread> ranOn = new AtomicReference<>();
		Executor executor = task -> {
			Thread thread = new Thread(task, "async-under-test");
			ranOn.set(thread);
			thread.start();
		};

		SystemOneResponse response = this.mock.client().systemOneAsync(request(), executor).join();

		assertThat(response.noulValue("probe")).isEqualTo(0.7d);
		assertThat(response.requestId()).isEqualTo("req_0123456789");
		assertThat(ranOn.get()).isNotSameAs(Thread.currentThread());
	}

	@Test
	void failsTheFutureWithTheExceptionTheBlockingCallWouldThrow() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(500, "boom"));

		CompletableFuture<SystemOneResponse> future = this.mock.client(RetryPolicy.noRetry())
			.systemOneAsync(request(), Runnable::run);

		assertThatExceptionOfType(CompletionException.class).isThrownBy(future::join)
			.withCauseInstanceOf(TypeSafeInternalServerException.class);
	}

	@Test
	void aRejectedSubmissionFailsTheFutureInsteadOfThrowing() {
		Executor rejecting = task -> {
			throw new RejectedExecutionException("full");
		};

		CompletableFuture<SystemOneResponse> future = this.mock.client().systemOneAsync(request(), rejecting);

		assertThat(future).isCompletedExceptionally();
	}

	@Test
	void rejectsNullArguments() {
		TypeSafeClient client = this.mock.client();

		assertThatIllegalArgumentException().isThrownBy(() -> client.systemOneAsync(null, Runnable::run));
		assertThatIllegalArgumentException().isThrownBy(() -> client.systemOneAsync(request(), null));
	}

}
