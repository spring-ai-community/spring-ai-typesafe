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



import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.exception.TypeSafeApiResponseValidationException;
import org.springaicommunity.typesafe.exception.TypeSafeAuthenticationException;
import org.springaicommunity.typesafe.exception.TypeSafeBadRequestException;
import org.springaicommunity.typesafe.exception.TypeSafeInternalServerException;
import org.springaicommunity.typesafe.exception.TypeSafeNotFoundException;
import org.springaicommunity.typesafe.exception.TypeSafeOverloadedException;
import org.springaicommunity.typesafe.exception.TypeSafePermissionDeniedException;
import org.springaicommunity.typesafe.exception.TypeSafeRateLimitException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.exception.TypeSafeUnprocessableEntityException;
import org.springaicommunity.typesafe.question.Noul;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Every documented error status is surfaced as its own exception type, so a caller
 * branches on the failure rather than on a number.
 *
 * @author Christian Tzolov
 */
class TypeSafeErrorMappingTests {

	/** An application error, copied verbatim from a live 401. */
	private static final String AUTH_BODY = """
			{"detail":{"error_type":"authentication_error","message":"Cannot authenticate with the server. Please check your API key and try again."}}""";

	/** Request validation, copied verbatim from a live 422. */
	private static final String VALIDATION_BODY = """
			{"detail":[{"type":"missing","loc":["body","questions"],"msg":"Field required","input":{"model":"jev-latest","state":"hi"}}]}""";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	@Test
	void mapsBadRequest() {
		assertThatStatus(400).isInstanceOf(TypeSafeBadRequestException.class);
	}

	@Test
	void mapsAuthenticationFailure() {
		assertThatStatus(401).isInstanceOf(TypeSafeAuthenticationException.class);
	}

	@Test
	void mapsPermissionDenied() {
		assertThatStatus(403).isInstanceOf(TypeSafePermissionDeniedException.class);
	}

	@Test
	void mapsNotFound() {
		assertThatStatus(404).isInstanceOf(TypeSafeNotFoundException.class);
	}

	@Test
	void mapsValidationFailure() {
		assertThatStatus(422).isInstanceOf(TypeSafeUnprocessableEntityException.class);
	}

	@Test
	void mapsServerError() {
		assertThatStatus(500).isInstanceOf(TypeSafeInternalServerException.class);
	}

	@Test
	void mapsTheNonStandardOverloadedStatus() {
		assertThatStatus(529).isInstanceOf(TypeSafeOverloadedException.class);
	}

	@Test
	void reportsAnOverloadAsAServerErrorToo() {
		// 529 is numerically a 5xx, and both official SDKs surface it as their
		// internal-server error. Someone porting `except TypeSafeInternalServerError`
		// must not silently stop catching an overload here.
		assertThatStatus(529).isInstanceOf(TypeSafeInternalServerException.class);
	}

	@Test
	void keepsStatusBodyEndpointAndRequestIdOnTheException() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(TypeSafeApiException.REQUEST_ID_HEADER, "req_abcdef");

		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(422, VALIDATION_BODY, headers));

		assertThatExceptionOfType(TypeSafeUnprocessableEntityException.class).isThrownBy(this::evaluate).satisfies(ex -> {
			assertThat(ex.status()).isEqualTo(422);
			assertThat(ex.body()).contains("Field required");
			assertThat(ex.endpoint()).isEqualTo("POST " + MockTypeSafeServer.SYSTEM_ONE_URL);
			assertThat(ex.requestId()).isEqualTo("req_abcdef");
			assertThat(ex.getMessage()).contains("422 Unprocessable Content")
				.contains("request id req_abcdef")
				.contains("body.questions: Field required");
		});
	}

	@Test
	void readsTheErrorTypeAndMessageOfAnApplicationError() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(401, AUTH_BODY));

		assertThatExceptionOfType(TypeSafeAuthenticationException.class).isThrownBy(this::evaluate).satisfies(ex -> {
			assertThat(ex.errorType()).isEqualTo("authentication_error");
			assertThat(ex.errorMessage()).isEqualTo("Cannot authenticate with the server. Please check your API key and try again.");
			assertThat(ex.validationErrors()).isEmpty();
			// The raw envelope is still there for anything the accessors do not model.
			assertThat(ex.body()).isEqualTo(AUTH_BODY);
			// ...but the message states what the server said, not the JSON.
			assertThat(ex.getMessage()).contains("Cannot authenticate with the server.")
				.contains("[authentication_error]")
				.doesNotContain("{\"detail\"");
		});
	}

	@Test
	void readsThePerFieldValidationErrorsOfA422() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(422, VALIDATION_BODY));

		assertThatExceptionOfType(TypeSafeUnprocessableEntityException.class).isThrownBy(this::evaluate)
			.satisfies(ex -> {
				assertThat(ex.errorType()).isNull();
				assertThat(ex.validationErrors()).singleElement().satisfies(error -> {
					assertThat(error.type()).isEqualTo("missing");
					assertThat(error.loc()).containsExactly("body", "questions");
					assertThat(error.msg()).isEqualTo("Field required");
					assertThat(error.path()).isEqualTo("body.questions");
				});
			});
	}

	@Test
	void readsAPlainStringDetail() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(404, "{\"detail\":\"Not Found\"}"));

		assertThatExceptionOfType(TypeSafeNotFoundException.class).isThrownBy(this::evaluate).satisfies(ex -> {
			assertThat(ex.errorMessage()).isEqualTo("Not Found");
			assertThat(ex.errorType()).isNull();
			assertThat(ex.validationErrors()).isEmpty();
		});
	}

	@Test
	void readsAnOllamaStyleErrorField() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(404, "{\"error\":\"model 'jev-latest' not found\"}"));

		assertThatExceptionOfType(TypeSafeNotFoundException.class).isThrownBy(this::evaluate).satisfies(ex -> {
			assertThat(ex.errorMessage()).isEqualTo("model 'jev-latest' not found");
			assertThat(ex.errorType()).isNull();
			assertThat(ex.validationErrors()).isEmpty();
			assertThat(ex.getMessage()).endsWith(": model 'jev-latest' not found").doesNotContain("{\"error\"");
		});
	}

	@Test
	void fallsBackToTheRawBodyWhenItIsNotADetailEnvelope() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(500, "<html>502 Bad Gateway</html>"));

		assertThatExceptionOfType(TypeSafeInternalServerException.class).isThrownBy(this::evaluate).satisfies(ex -> {
			assertThat(ex.errorDetail()).isNull();
			assertThat(ex.errorType()).isNull();
			assertThat(ex.errorMessage()).isNull();
			assertThat(ex.validationErrors()).isEmpty();
			assertThat(ex.getMessage()).contains("<html>502 Bad Gateway</html>");
		});
	}

	@Test
	void readsTheMillisecondPrecisionRetryAfterHeader() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(TypeSafeRateLimitException.RETRY_AFTER_MS_HEADER, "1500");

		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(429, "{\"error\":\"rate limited\"}", headers));

		assertThatExceptionOfType(TypeSafeRateLimitException.class)
			.isThrownBy(() -> evaluate(RetryPolicy.noRetry()))
			.satisfies(ex -> {
				assertThat(ex.retryAfterMs()).isEqualTo(1500L);
				assertThat(ex.retryAfter()).hasMillis(1500);
			});
	}

	@Test
	void fallsBackToTheStandardRetryAfterSecondsHeader() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.RETRY_AFTER, "2");

		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(429, "rate limited", headers));

		assertThatExceptionOfType(TypeSafeRateLimitException.class)
			.isThrownBy(() -> evaluate(RetryPolicy.noRetry()))
			.satisfies(ex -> assertThat(ex.retryAfterMs()).isEqualTo(2000L));
	}

	@Test
	void ignoresARetryAfterGivenAsAnHttpDate() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2026 07:28:00 GMT");

		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(429, "rate limited", headers));

		assertThatExceptionOfType(TypeSafeRateLimitException.class)
			.isThrownBy(() -> evaluate(RetryPolicy.noRetry()))
			.satisfies(ex -> assertThat(ex.retryAfterMs()).isNull());
	}

	@Test
	void reportsANonJsonSuccessBodyAsAResponseValidationFailure() {
		// A proxy or load balancer answering 200 with HTML. RestClient throws
		// UnknownContentTypeException, which used to escape as a raw Spring exception and
		// so bypass a caller's catch (TypeSafeException e).
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockRestResponseCreators.withSuccess("<html>Service Unavailable</html>", MediaType.TEXT_HTML));

		assertThatExceptionOfType(TypeSafeApiResponseValidationException.class).isThrownBy(this::evaluate)
			.satisfies(ex -> {
				assertThat(ex.status()).isEqualTo(200);
				assertThat(ex.body()).contains("Service Unavailable");
				assertThat(ex.endpoint()).contains("/v1/systemone");
				assertThat(ex.getMessage()).contains("text/html");
			});
	}

	@Test
	void reportsMalformedJsonAsATypeSafeException() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse("{\"model\":\"jev-1.13.0\",\"answers\":{\"probe\":"));

		assertThatExceptionOfType(TypeSafeException.class).isThrownBy(this::evaluate)
			.satisfies(ex -> assertThat(ex).isNotInstanceOf(RestClientException.class));
	}

	private org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertThatStatus(int status) {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(status, "{\"error\":\"boom\"}"));

		return org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluate(RetryPolicy.noRetry()));
	}

	private void evaluate() {
		evaluate(RetryPolicy.noRetry());
	}

	private void evaluate(RetryPolicy retryPolicy) {
		this.mock.client(retryPolicy).systemOne("anything", Map.of("probe", Noul.of("Is this a probe?")));
	}

}
