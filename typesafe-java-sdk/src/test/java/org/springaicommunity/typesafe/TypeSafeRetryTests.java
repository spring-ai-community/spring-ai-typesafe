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



import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.exception.TypeSafeApiConnectionException;
import org.springaicommunity.typesafe.exception.TypeSafeAuthenticationException;
import org.springaicommunity.typesafe.exception.TypeSafeInternalServerException;
import org.springaicommunity.typesafe.exception.TypeSafeRateLimitException;
import org.springaicommunity.typesafe.exception.TypeSafeUnprocessableEntityException;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.client.ExpectedCount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Retry behaviour. Backoffs are shrunk to near zero so the suite stays fast; what is under
 * test is which failures are repeated and how often, not how long the waits are.
 *
 * @author Christian Tzolov
 */
class TypeSafeRetryTests {

	private static final String OK_BODY = """
			{"model":"jev-1.13.0","answers":{"probe":{"type":"noul","noul":0.5}},"usage":{}}""";

	private static final RetryPolicy FAST = RetryPolicy.builder()
		.maxRetries(2)
		.initialBackoff(Duration.ofMillis(1))
		.maxBackoff(Duration.ofMillis(2))
		.jitter(0.0d)
		.build();

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	@Test
	void retriesARateLimitAndSucceeds() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(429, "rate limited"));
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(OK_BODY));

		assertThat(evaluate(FAST).noulValue("probe")).isEqualTo(0.5);

		this.mock.server().verify();
	}

	@Test
	void retriesAServerErrorAndSucceeds() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(529, "overloaded"));
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(OK_BODY));

		assertThat(evaluate(FAST).noulValue("probe")).isEqualTo(0.5);

		this.mock.server().verify();
	}

	@Test
	void stopsAfterMaxRetriesAndRethrowsTheLastFailure() {
		this.mock.server()
			.expect(ExpectedCount.times(3), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(500, "boom"));

		assertThatExceptionOfType(TypeSafeInternalServerException.class).isThrownBy(() -> evaluate(FAST));

		this.mock.server().verify();
	}

	@Test
	void doesNotRetryAnAuthenticationFailure() {
		this.mock.server()
			.expect(ExpectedCount.once(), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(401, "bad key"));

		assertThatExceptionOfType(TypeSafeAuthenticationException.class).isThrownBy(() -> evaluate(FAST));

		this.mock.server().verify();
	}

	@Test
	void doesNotRetryAValidationFailure() {
		this.mock.server()
			.expect(ExpectedCount.once(), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(422, "malformed question"));

		assertThatExceptionOfType(TypeSafeUnprocessableEntityException.class)
			.isThrownBy(() -> evaluate(FAST));

		this.mock.server().verify();
	}

	@Test
	void honoursTheServerStatedWaitInsteadOfItsOwnBackoff() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(TypeSafeRateLimitException.RETRY_AFTER_MS_HEADER, "40");

		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(429, "rate limited", headers));
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(OK_BODY));

		long startedAt = System.nanoTime();
		assertThat(evaluate(FAST).noulValue("probe")).isEqualTo(0.5);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

		// The policy's own backoff is 1ms; the server asked for 40ms and that wins.
		assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(35));
		this.mock.server().verify();
	}

	@Test
	void givesUpWhenTheNextWaitWouldExhaustTheBudget() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(TypeSafeRateLimitException.RETRY_AFTER_MS_HEADER, "60000");

		this.mock.server()
			.expect(ExpectedCount.once(), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(429, "rate limited", headers));

		RetryPolicy tightBudget = RetryPolicy.builder()
			.maxRetries(5)
			.initialBackoff(Duration.ofMillis(1))
			.totalTimeout(Duration.ofSeconds(1))
			.build();

		assertThatExceptionOfType(TypeSafeRateLimitException.class).isThrownBy(() -> evaluate(tightBudget));

		this.mock.server().verify();
	}

	@Test
	void noRetryPolicyFailsOnTheFirstAttempt() {
		this.mock.server()
			.expect(ExpectedCount.once(), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(500, "boom"));

		assertThatExceptionOfType(TypeSafeInternalServerException.class)
			.isThrownBy(() -> evaluate(RetryPolicy.noRetry()));

		this.mock.server().verify();
	}

	@Test
	void backoffGrowsExponentiallyAndIsCapped() {
		RetryPolicy policy = RetryPolicy.builder()
			.initialBackoff(Duration.ofMillis(500))
			.maxBackoff(Duration.ofSeconds(5))
			.jitter(0.0d)
			.build();
		RuntimeException failure = new TypeSafeApiConnectionException("boom", null);

		assertThat(policy.backoffFor(1, failure)).hasMillis(500);
		assertThat(policy.backoffFor(2, failure)).hasMillis(1000);
		assertThat(policy.backoffFor(3, failure)).hasMillis(2000);
		assertThat(policy.backoffFor(10, failure)).hasMillis(5000);
	}

	@Test
	void jitterOnlySubtracts() {
		RetryPolicy policy = RetryPolicy.builder()
			.initialBackoff(Duration.ofMillis(1000))
			.maxBackoff(Duration.ofMillis(1000))
			.jitter(0.25d)
			.build();
		RuntimeException failure = new TypeSafeApiConnectionException("boom", null);

		for (int i = 0; i < 50; i++) {
			assertThat(policy.backoffFor(1, failure)).isBetween(Duration.ofMillis(750), Duration.ofMillis(1000));
		}
	}

	@Test
	void countsTheNextAttemptsTimeoutAgainstTheBudget() {
		// elapsed (~0) + backoff (1ms) is well inside a 5s budget, but a 4.999s per-attempt
		// timeout means the next attempt could not finish inside it. Give up now rather than
		// overshoot the budget by a whole request.
		this.mock.server()
			.expect(ExpectedCount.once(), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(500, "boom"));

		RetryPolicy policy = RetryPolicy.builder()
			.maxRetries(5)
			.initialBackoff(Duration.ofMillis(1))
			.jitter(0.0d)
			.totalTimeout(Duration.ofSeconds(5))
			.build();
		TypeSafeClient client = this.mock.clientBuilder().retryPolicy(policy).timeout(Duration.ofMillis(4_999)).build();

		assertThatExceptionOfType(TypeSafeInternalServerException.class)
			.isThrownBy(() -> client.systemOne("anything", Map.of("probe", Noul.of("Is this a probe?"))));

		this.mock.server().verify();
	}

	@Test
	void doesNotAssumeATimeoutForATransportItDidNotBuild() {
		// The transport is the caller's and no timeout was declared, so nothing is known
		// about how long an attempt may take. Assuming the 10s default would exceed this
		// 5s budget and refuse a retry that, after an instant 429, plainly fits.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(429, "rate limited"));
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(OK_BODY));

		RetryPolicy policy = RetryPolicy.builder()
			.maxRetries(2)
			.initialBackoff(Duration.ofMillis(1))
			.jitter(0.0d)
			.totalTimeout(Duration.ofSeconds(5))
			.build();
		TypeSafeClient client = this.mock.clientBuilder().retryPolicy(policy).build();

		assertThat(client.timeout()).isNull();
		assertThat(client.systemOne("anything", Map.of("probe", Noul.of("Is this a probe?"))).noulValue("probe"))
			.isEqualTo(0.5);
		this.mock.server().verify();
	}

	@Test
	void usesTheDefaultTimeoutForATransportItBuilds() {
		TypeSafeClient client = TypeSafeClient.builder().apiKey("test-api-key").build();

		assertThat(client.timeout()).isEqualTo(TypeSafeConstants.DEFAULT_TIMEOUT);
	}

	private SystemOneResponse evaluate(RetryPolicy retryPolicy) {
		return this.mock.client(retryPolicy).systemOne("anything", Map.of("probe", Noul.of("Is this a probe?")));
	}

}
