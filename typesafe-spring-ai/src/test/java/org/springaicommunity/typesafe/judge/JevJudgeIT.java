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

package org.springaicommunity.typesafe.judge;



import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeConstants;
import org.springaicommunity.typesafe.TypeSafeModels;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;

import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judge against the real Jev API.
 *
 * <p>
 * The offline tests pin the judge's thresholds and feedback wording against canned answers,
 * which proves the arithmetic but not the premise. This is the premise: that several narrow
 * questions asked of the real model actually separate a good answer from a bad one, and that
 * a defect in one dimension fails that dimension alone rather than being averaged away.
 *
 * <p>
 * The assertions are on which criteria pass and fail, never on the numbers behind them. Jev
 * is a model, and a test that pinned its output would be measuring the weather.
 *
 * @author Christian Tzolov
 */
@EnabledIfEnvironmentVariable(named = TypeSafeConstants.API_KEY_ENV, matches = ".+",
		disabledReason = "Set TYPESAFE_API_KEY to run the judge against the real Jev API")
class JevJudgeIT {

	private static final String QUESTION = "What is the weather in Paris?";

	private static final Score HELPFULNESS = Score.builder()
		.instructions("How well does `assistant_answer` address the question in `user_question`?")
		.level("Terrible: irrelevant to the question, or almost entirely missing")
		.level("Mostly unhelpful: misses key aspects of the question")
		.level("Mostly helpful: answers the question but could be improved")
		.level("Excellent: relevant, direct and addresses every concern raised")
		.build();

	private static final Noul PLAUSIBLE = Noul.builder()
		.instructions("Are all the numeric values in `assistant_answer` physically plausible for their units?")
		.whenTrue("Every value is within a range that can actually occur")
		.whenFalse("At least one value is impossible, such as a temperature below absolute zero")
		.build();

	private static final Noul GROUNDED_IN_TOOLS = Noul.builder()
		.instructions("Is every value in `assistant_answer` supported by a result in `tool_calls`?")
		.whenTrue("Every value appears in a tool result")
		.whenFalse("States a value no tool returned")
		.build();

	private final TypeSafeClient client = TypeSafeClient.builder()
		.baseUrl(TypeSafeConstants.DEFAULT_BASE_URL)
		.defaultModel(TypeSafeModels.JEV_LATEST)
		.build();

	@Test
	void passesAGoodAnswerOnEveryCriterion() {
		JevVerdict verdict = judge().judge(QUESTION, "It is 15 degrees Celsius and overcast in Paris.");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.failures()).isEmpty();
		assertThat(verdict.feedback()).isEmpty();
	}

	@Test
	void failsOnlyThePlausibilityOfAFluentButImpossibleAnswer() {
		// The case the whole design exists for. The answer is fluent and exactly on topic,
		// so a single overall rating would score it well; only a dedicated question about
		// the values catches that the temperature cannot occur.
		JevVerdict verdict = judge().judge(QUESTION, "It is currently -455 degrees Celsius in Paris.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.failures()).extracting(JevFinding::name).contains("is_plausible");
		assertThat(verdict.feedback()).contains("is_plausible").contains("At least one value is impossible");
	}

	@Test
	void failsHelpfulnessWhenTheAnswerDoesNotAddressTheQuestion() {
		JevVerdict verdict = judge().judge(QUESTION, "Weather is notoriously difficult to forecast accurately.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.failures()).extracting(JevFinding::name).contains("helpfulness");
	}

	@Test
	void synthesisesFeedbackThatNamesTheRubricLevelReachedAndRequired() {
		JevVerdict verdict = judge().judge(QUESTION, "Weather is notoriously difficult to forecast accurately.");

		// Jev returns numbers, never prose: this wording is built by the SDK out of the
		// rubric the caller supplied, so it must survive contact with a real response.
		assertThat(verdict.feedback()).contains("helpfulness: rated").contains("needs to reach 2.00");
	}

	@Test
	void judgesGroundednessAgainstTheToolCallsField() {
		// The value a tool returned appears nowhere in the question, so grounding has to be
		// judged against `tool_calls`. Same answer, different tool result, opposite verdict.
		JevJudge grounded = JevJudge.builder(this.client).noul("is_grounded", GROUNDED_IN_TOOLS, 0.7d).build();

		JevVerdict supported = grounded.judge(JevJudgeInput.builder()
			.question(QUESTION)
			.answer("It is 15 degrees Celsius in Paris.")
			.toolCall(new JevJudgeInput.ToolCall("currentWeather", "{\"city\":\"Paris\"}", "15 degrees Celsius"))
			.build());
		JevVerdict unsupported = grounded.judge(JevJudgeInput.builder()
			.question(QUESTION)
			.answer("It is 28 degrees Celsius in Paris.")
			.toolCall(new JevJudgeInput.ToolCall("currentWeather", "{\"city\":\"Paris\"}", "15 degrees Celsius"))
			.build());

		assertThat(supported.passed()).isTrue();
		assertThat(unsupported.passed()).isFalse();
		assertThat(unsupported.feedback()).contains("States a value no tool returned");
	}

	@Test
	void judgesGroundednessAgainstEachRetrievedDocument() {
		// JevEvaluator sends supporting_context as one entry per document; the supporting
		// fact sits in the second one.
		JevEvaluator evaluator = new JevEvaluator(JevJudge.builder(this.client)
			.noul("is_grounded", Noul.builder()
				.instructions("Is every claim in `assistant_answer` supported by `supporting_context`?")
				.whenFalse("Introduces facts the context does not support")
				.build(), 0.7d)
			.build());
		List<Document> documents = List.of(new Document("The Louvre is the most visited museum in the world."),
				new Document("The Eiffel Tower is 330 metres tall."));

		assertThat(evaluator.evaluate(new EvaluationRequest("How tall is the Eiffel Tower?", documents,
				"The Eiffel Tower is 330 metres tall.")).isPass()).isTrue();
		assertThat(evaluator.evaluate(new EvaluationRequest("How tall is the Eiffel Tower?", documents,
				"The Eiffel Tower is 512 metres tall and was built in 1920.")).isPass()).isFalse();
	}

	@Test
	void reportsACodeCheckInTheSameVerdictAsARealAnswer() {
		JevJudge judge = JevJudge.builder(this.client)
			.check("used_weather_tool", input -> !input.toolCalls().isEmpty(), "answered without calling a tool")
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.build();

		JevVerdict verdict = judge.judge(JevJudgeInput.builder()
			.question(QUESTION)
			.answer("It is 15 degrees Celsius in Paris.")
			.build());

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.summary()).isEqualTo("passed=false [used_weather_tool=FAILED, is_plausible=PASSED]");
		assertThat(verdict.feedback()).isEqualTo("- used_weather_tool: answered without calling a tool");
	}

	@Test
	void routesAChoiceToAnAcceptedOptionOnARealAnswer() {
		JevJudge toneJudge = JevJudge.builder(this.client)
			.choice("tone",
					Choice.builder()
						.instructions("What is the tone of `assistant_answer`?")
						.option("helpful", "Tries to answer the question")
						.option("dismissive", "Brushes the question off")
						.build(),
					"helpful")
			.build();

		assertThat(toneJudge.judge(QUESTION, "It is 15 degrees Celsius in Paris.").passed()).isTrue();
		assertThat(toneJudge.judge(QUESTION, "Look it up yourself.").passed()).isFalse();
	}

	@Test
	void answersEveryCriterionInASingleCall() {
		JevVerdict verdict = judge().judge(QUESTION, "It is 15 degrees Celsius in Paris.");

		// One request, one usage record, both criteria answered against the same state.
		assertThat(verdict.findings()).extracting(JevFinding::name)
			.containsExactly("helpfulness", "is_plausible");
		assertThat(verdict.response().usage().inputTokens()).isNotNull().isPositive();
		assertThat(verdict.response().requestId()).isNotBlank();
	}

	private JevJudge judge() {
		return JevJudge.builder(this.client)
			.score("helpfulness", HELPFULNESS, 2.0d)
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.build();
	}

}
