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

package org.springaicommunity.typesafe.demo.judge;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeModels;
import org.springaicommunity.typesafe.judge.JevJudgeInput;
import org.springaicommunity.typesafe.judge.JevVerdict;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * The demo's judge, exercised offline. This is the case the demo is built around: a
 * fluent, on-topic answer that happens to quote a temperature below absolute zero.
 *
 * @author Christian Tzolov
 */
class WeatherJudgeTests {

	private static final String BASE_URL = "https://api.typesafe.ai";

	private static final String SYSTEM_ONE_URL = BASE_URL + "/v1/systemone";

	private final RestClient.Builder restClientBuilder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(this.restClientBuilder)
		.bufferContent()
		.build();

	// baseUrl and defaultModel are pinned so that a developer's exported
	// TYPESAFE_BASE_URL / TYPESAFE_DEFAULT_MODEL cannot reach this offline test.
	private final TypeSafeClient typeSafeClient = TypeSafeClient.builder()
		.apiKey("test-api-key")
		.baseUrl(BASE_URL)
		.defaultModel(TypeSafeModels.JEV_LATEST)
		.retryPolicy(RetryPolicy.noRetry())
		.restClientBuilder(this.restClientBuilder)
		.build();

	@Test
	void asksAllThreeQuestionsInASingleCall() {
		this.server.expect(requestTo(SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.model").value("jev-latest"))
			.andExpect(jsonPath("$.questions.helpfulness.type").value("score"))
			.andExpect(jsonPath("$.questions.helpfulness.criteria.length()").value(4))
			.andExpect(jsonPath("$.questions.is_plausible.type").value("noul"))
			.andExpect(jsonPath("$.questions.is_grounded.type").value("noul"))
			.andRespond(respondWith(3.4, 0.99, 0.95, 0.91));

		assertThat(judge("What is the weather in Paris?", "It is 15 degrees Celsius in Paris.").passed()).isTrue();

		this.server.verify();
	}

	@Test
	void rejectsAFluentAnswerThatQuotesAnImpossibleTemperature() {
		// High helpfulness, high groundedness, but the value cannot occur. A single
		// overall rating would average this away; a dedicated noul does not.
		this.server.expect(requestTo(SYSTEM_ONE_URL)).andRespond(respondWith(3.1, 0.02, 0.88, 0.90));

		JevVerdict verdict = judge("What is the weather in Paris?", "It is currently -255 degrees Celsius in Paris.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.failures()).singleElement()
			.satisfies(finding -> assertThat(finding.name()).isEqualTo("is_plausible"));
		assertThat(verdict.feedback()).contains("At least one value is impossible")
			.contains("scored 0.02, needs at least 0.70");

		this.server.verify();
	}

	@Test
	void rejectsAnAnswerThatInventsFacts() {
		this.server.expect(requestTo(SYSTEM_ONE_URL)).andRespond(respondWith(3.1, 0.99, 0.11, 0.90));

		JevVerdict verdict = judge("What is the weather in Paris?",
				"It is 15 degrees Celsius in Paris and the Seine has frozen over.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.feedback()).contains("is_grounded: Introduces facts that appear nowhere in the question");

		this.server.verify();
	}

	@Test
	void rejectsAnAnswerGivenWithoutCallingTheTool() {
		this.server.expect(requestTo(SYSTEM_ONE_URL)).andRespond(respondWith(3.1, 0.99, 0.95, 0.90));

		JevVerdict verdict = ModelJudgeDemoApplication.createWeatherJudge(this.typeSafeClient)
			.judge("What is the weather in Paris?", "It is 15 degrees Celsius in Paris.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.failures()).singleElement()
			.satisfies(finding -> assertThat(finding.name()).isEqualTo("used_weather_tool"));

		this.server.verify();
	}

	@Test
	void rejectsAnAnswerThatDoesNotAddressTheQuestion() {
		this.server.expect(requestTo(SYSTEM_ONE_URL)).andRespond(respondWith(0.6, 0.99, 0.95, 0.82));

		JevVerdict verdict = judge("What is the weather in Paris?", "Weather is notoriously hard to predict.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.feedback())
			.contains("helpfulness: rated \"Mostly unhelpful: misses key aspects of the question\" (0.60)")
			.contains("needs to reach 2.00");

		this.server.verify();
	}

	/**
	 * Judges an answer as the advisor presents it: with the weather tool call it
	 * recorded.
	 */
	private JevVerdict judge(String question, String answer) {
		return ModelJudgeDemoApplication.createWeatherJudge(this.typeSafeClient)
			.judge(JevJudgeInput.builder()
				.question(question)
				.answer(answer)
				.toolCall(new JevJudgeInput.ToolCall("weather", "{\"location\":\"Paris\"}", "15 degrees Celsius"))
				.build());
	}

	/**
	 * The judge gates on the probability behind a verdict, so the distribution must agree
	 * with the score: all of it on the level the score rounds to.
	 */
	private static int nearestLevel(double score) {
		return (int) Math.max(0, Math.min(3, Math.round(score)));
	}

	private org.springframework.test.web.client.ResponseCreator respondWith(double helpfulness, double plausible,
			double grounded, double confidence) {
		String body = """
				{
				  "model":"jev-1.13.0",
				  "answers": {
				    "helpfulness": {
				      "type": "score", "score": %s,
				      "legend": {"0":"Terrible","1":"Mostly unhelpful","2":"Mostly helpful","3":"Excellent"},
				      "probabilities": {"%d": 1.0},
				      "confidence": %s
				    },
				    "is_plausible": { "type": "noul", "noul": %s },
				    "is_grounded":  { "type": "noul", "noul": %s }
				  },
				  "usage": { "input_tokens": 210, "output_tokens": 32 }
				}""".formatted(helpfulness, nearestLevel(helpfulness), confidence, plausible, grounded);
		return MockRestResponseCreators.withSuccess(body, MediaType.APPLICATION_JSON);
	}

}
