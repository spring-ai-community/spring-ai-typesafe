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



import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.exception.TypeSafeAnswerTypeException;
import org.springaicommunity.typesafe.exception.TypeSafeApiResponseValidationException;
import org.springaicommunity.typesafe.exception.TypeSafeMissingAnswerException;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.AnswerType;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springaicommunity.typesafe.response.UnknownAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * The response bodies from the TypeSafe API reference are parsed into the sealed
 * {@code Answer} hierarchy.
 *
 * @author Christian Tzolov
 */
class TypeSafeApiResponseTests {

	/** The three documented answers plus one the SDK deliberately does not model. */
	private static final String DOCUMENTED_BODY = """
			{
			  "model":"jev-1.13.0",
			  "answers": {
			    "is_urgent": { "type": "noul", "noul": 0.92 },
			    "department": {
			      "type": "choice",
			      "choice": "technical",
			      "probabilities": { "billing": 0.08, "technical": 0.85, "sales": 0.07 },
			      "confidence": 0.82
			    },
			    "frustration": {
			      "type": "score",
			      "score": 1.6,
			      "legend": { "0": "Calm", "1": "Frustrated", "2": "Very angry" },
			      "probabilities": { "0": 0.05, "1": 0.3, "2": 0.65 },
			      "confidence": 0.78
			    }
			  },
			  "usage": { "input_tokens": 312, "output_tokens": 48 }
			}""";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	@Test
	void parsesANoulAnswer() {
		SystemOneResponse response = evaluate(DOCUMENTED_BODY);

		assertThat(response.answer("is_urgent")).isInstanceOf(NoulAnswer.class);
		assertThat(response.noul("is_urgent").value()).isEqualTo(0.92);
		assertThat(response.noul("is_urgent").isTrue()).isTrue();
		assertThat(response.noul("is_urgent").isTrue(0.95)).isFalse();
		assertThat(response.noulValue("is_urgent")).isEqualTo(0.92);
	}

	@Test
	void parsesAChoiceAnswer() {
		ChoiceAnswer department = evaluate(DOCUMENTED_BODY).choice("department");

		assertThat(department.value()).isEqualTo("technical");
		assertThat(department.confidence()).isEqualTo(0.82);
		assertThat(department.probabilities()).containsEntry("billing", 0.08).containsEntry("technical", 0.85);
		assertThat(department.probabilityOf("sales")).isEqualTo(0.07);
		assertThat(department.probabilityOf("unheard-of")).isZero();
		assertThat(department.optionsAbove(0.05)).containsExactly("technical", "billing", "sales");
		assertThat(department.optionsAbove(0.5)).containsExactly("technical");
	}

	@Test
	void parsesAScoreAnswerWithIntegerKeyedMaps() {
		ScoreAnswer frustration = evaluate(DOCUMENTED_BODY).score("frustration");

		assertThat(frustration.value()).isEqualTo(1.6);
		assertThat(frustration.confidence()).isEqualTo(0.78);
		assertThat(frustration.probabilities()).containsEntry(2, 0.65);
		assertThat(frustration.nearestLevel()).isEqualTo(2);
		assertThat(frustration.nearestLabel()).isEqualTo("Very angry");
		assertThat(frustration.maxLevel()).isEqualTo(2);
		assertThat(frustration.labelOf(0)).isNotNull();
		assertThat(frustration.labelOf(0).asText()).isEqualTo("Calm");
		assertThat(frustration.labelOf(9)).isNull();
	}

	@Test
	void reportsUsageAndModel() {
		SystemOneResponse response = evaluate(DOCUMENTED_BODY);

		// The server reports the version it resolved the alias to, not the alias sent.
		assertThat(response.model()).isEqualTo("jev-1.13.0");
		assertThat(response.usage().inputTokens()).isEqualTo(312);
		assertThat(response.usage().outputTokens()).isEqualTo(48);
		assertThat(response.usage().totalTokens()).isEqualTo(360);
	}

	@Test
	void carriesTheRequestIdHeaderOntoTheResponse() {
		assertThat(evaluate(DOCUMENTED_BODY).requestId()).isEqualTo("req_0123456789");
	}

	@Test
	void groupsAnswersByKind() {
		SystemOneResponse response = evaluate(DOCUMENTED_BODY);

		assertThat(response.nouls()).containsOnlyKeys("is_urgent");
		assertThat(response.choices()).containsOnlyKeys("department");
		assertThat(response.scores()).containsOnlyKeys("frustration");
	}

	@Test
	void surfacesAnUnmodelledAnswerKindInsteadOfFailing() {
		SystemOneResponse response = evaluate("""
				{
				  "model":"jev-1.13.0",
				  "answers": {
				    "embedding": { "type": "vector", "vector": [0.1, 0.2], "confidence": 0.5 }
				  },
				  "usage": { "input_tokens": 1, "output_tokens": 1 }
				}""");

		assertThat(response.answer("embedding")).isInstanceOfSatisfying(UnknownAnswer.class, unknown -> {
			assertThat(unknown.typeName()).isEqualTo("vector");
			assertThat(unknown.type()).isEqualTo(AnswerType.UNKNOWN);
			assertThat(unknown.raw()).containsKey("vector");
			// An object answer is preserved as it stands; only a non-object one is wrapped.
			assertThat(unknown.raw()).doesNotContainKey(UnknownAnswer.VALUE_KEY);
		});
	}

	@Test
	void surfacesAnAnswerThatIsNotAnObjectInsteadOfFailing() {
		// A future primitive may answer with a bare value rather than an object. Binding one
		// to a map used to throw, failing the whole call, including the noul that sits next
		// to it and is perfectly readable.
		SystemOneResponse response = evaluate("""
				{"model":"jev-1.13.0","answers":{
				  "is_urgent":{"type":"noul","noul":0.4},
				  "a_number":0.5,
				  "a_string":"text",
				  "a_boolean":true,
				  "a_list":[1,2]
				},"usage":{}}""");

		assertThat(response.noulValue("is_urgent")).isEqualTo(0.4);

		assertThat(response.answer("a_number")).isInstanceOfSatisfying(UnknownAnswer.class, unknown -> {
			assertThat(unknown.typeName()).isNull();
			assertThat(unknown.type()).isEqualTo(AnswerType.UNKNOWN);
			assertThat(unknown.raw()).containsEntry(UnknownAnswer.VALUE_KEY, 0.5d);
		});
		assertThat(response.answer("a_string")).isInstanceOfSatisfying(UnknownAnswer.class,
				unknown -> assertThat(unknown.raw()).containsEntry(UnknownAnswer.VALUE_KEY, "text"));
		assertThat(response.answer("a_boolean")).isInstanceOfSatisfying(UnknownAnswer.class,
				unknown -> assertThat(unknown.raw()).containsEntry(UnknownAnswer.VALUE_KEY, true));
		assertThat(response.answer("a_list")).isInstanceOfSatisfying(UnknownAnswer.class,
				unknown -> assertThat(unknown.raw()).containsEntry(UnknownAnswer.VALUE_KEY, List.of(1, 2)));
	}

	@Test
	void keepsAJsonNullAnswerAsAnUnknownAnswerWithNoPayload() {
		SystemOneResponse response = evaluate("""
				{"model":"jev-1.13.0","answers":{"nothing":null},"usage":{}}""");

		assertThat(response.answer("nothing")).isInstanceOfSatisfying(UnknownAnswer.class, unknown -> {
			assertThat(unknown.typeName()).isNull();
			assertThat(unknown.raw()).isEmpty();
		});
	}

	@Test
	void toleratesFieldsTheSdkDoesNotKnow() {
		SystemOneResponse response = evaluate("""
				{
				  "model":"jev-1.13.0",
				  "trace_id": "abc",
				  "answers": { "is_urgent": { "type": "noul", "noul": 0.4, "explanation": "unused" } },
				  "usage": { "input_tokens": 1, "output_tokens": 1, "cached_tokens": 7 }
				}""");

		assertThat(response.noulValue("is_urgent")).isEqualTo(0.4);
	}

	@Test
	void toleratesAbsentUsageCounts() {
		SystemOneResponse response = evaluate("""
				{"model":"jev-1.13.0","answers":{"is_urgent":{"type":"noul","noul":0.4}}}""");

		assertThat(response.usage().inputTokens()).isNull();
		assertThat(response.usage().totalTokens()).isZero();
	}

	@Test
	void rejectsReadingAnAnswerAsTheWrongPrimitive() {
		SystemOneResponse response = evaluate(DOCUMENTED_BODY);

		assertThatExceptionOfType(TypeSafeAnswerTypeException.class).isThrownBy(() -> response.score("is_urgent"))
			.satisfies(ex -> {
				assertThat(ex.expected()).isEqualTo(AnswerType.SCORE);
				assertThat(ex.actual()).isEqualTo(AnswerType.NOUL);
			})
			.withMessageContaining("is a noul answer, not a score answer");
	}

	@Test
	void namesTheAvailableAnswersWhenAskedForAMissingOne() {
		SystemOneResponse response = evaluate(DOCUMENTED_BODY);

		assertThatExceptionOfType(TypeSafeMissingAnswerException.class).isThrownBy(() -> response.noul("sentiment"))
			.satisfies(ex -> assertThat(ex.availableNames()).contains("is_urgent", "department", "frustration"))
			.withMessageContaining("No answer named 'sentiment'");
	}

	@Test
	void rejectsASuccessfulResponseThatCarriesNoAnswers() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse("""
					{"model":"jev-1.13.0","answers":{},"usage":{}}"""));

		assertThatExceptionOfType(TypeSafeApiResponseValidationException.class)
			.isThrownBy(() -> this.mock.client(RetryPolicy.noRetry())
				.systemOne("anything", Map.of("probe", Noul.of("Is this a probe?"))))
			.satisfies(ex -> assertThat(ex.fieldPath()).isEqualTo("answers"));
	}

	@Test
	void keepsAnUnknownAnswerThatCarriesANullField() {
		// The forward-compatibility case: a primitive this SDK does not model, with a null
		// field in it. Map.copyOf used to throw here and fail the whole call.
		SystemOneResponse response = evaluate("""
				{"model":"jev-1.13.0","answers":{
				  "embedding":{"type":"vector","values":[0.1,0.2],"explanation":null}
				},"usage":{}}""");

		Answer answer = response.answer("embedding");
		assertThat(answer).isInstanceOf(UnknownAnswer.class);
		UnknownAnswer unknown = (UnknownAnswer) answer;
		assertThat(unknown.typeName()).isEqualTo("vector");
		assertThat(unknown.raw()).containsKey("explanation");
		assertThat(unknown.raw().get("explanation")).isNull();
	}

	@Test
	void treatsANonStringTypeAsAnUnknownAnswer() {
		// Jackson 3's stringValue() is strict; a numeric or object "type" must still land
		// in UnknownAnswer rather than abort binding of the whole response.
		SystemOneResponse response = evaluate("""
				{"model":"jev-1.13.0","answers":{
				  "odd":{"type":3,"value":1},
				  "odder":{"type":{"kind":"x"},"value":2}
				},"usage":{}}""");

		assertThat(response.answer("odd")).isInstanceOf(UnknownAnswer.class);
		assertThat(((UnknownAnswer) response.answer("odd")).typeName()).isNull();
		assertThat(response.answer("odder")).isInstanceOf(UnknownAnswer.class);
	}

	@Test
	void keepsAnswersInTheOrderTheyArrived() {
		// Eight keys, so a randomized Map.copyOf order cannot pass by luck.
		SystemOneResponse response = evaluate("""
				{"model":"jev-1.13.0","answers":{
				  "h":{"type":"noul","noul":0.1},"g":{"type":"noul","noul":0.2},
				  "f":{"type":"noul","noul":0.3},"e":{"type":"noul","noul":0.4},
				  "d":{"type":"noul","noul":0.5},"c":{"type":"noul","noul":0.6},
				  "b":{"type":"noul","noul":0.7},"a":{"type":"noul","noul":0.8}
				},"usage":{}}""");

		assertThat(response.answers().keySet()).containsExactly("h", "g", "f", "e", "d", "c", "b", "a");
		assertThat(response.nouls().keySet()).containsExactly("h", "g", "f", "e", "d", "c", "b", "a");
	}

	private SystemOneResponse evaluate(String responseBody) {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(responseBody));

		return this.mock.client().systemOne("Help! My payouts have been failing for 3 days.",
				Map.of("is_urgent", Noul.of("Does this convey urgency?")));
	}

}
