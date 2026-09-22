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




import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.MockTypeSafeServer;
import org.springaicommunity.typesafe.judge.JevCriterion.QuestionCriterion;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Threshold and feedback-synthesis behaviour of the judge. Jev returns numbers, never
 * prose, so the feedback these tests assert on is built by the SDK out of the rubric the
 * caller supplied.
 *
 * @author Christian Tzolov
 */
class JevJudgeTests {

	private static final Score HELPFULNESS = Score.builder()
		.instructions("How well does `assistant_answer` address `user_question`?")
		.level("Terrible: irrelevant or off-topic")
		.level("Mostly unhelpful: misses the main point")
		.level("Mostly helpful: minor gaps remain")
		.level("Excellent: fully and correctly addressed")
		.build();

	private static final Noul PLAUSIBLE = Noul.builder()
		.instructions("Are the values in `assistant_answer` physically plausible?")
		.whenTrue("Every value is physically possible")
		.whenFalse("Contains an impossible or absurd value")
		.build();

	private static final String PLAUSIBLE_ONLY = """
			{"model":"jev-1.13.0","answers":{"is_plausible":{"type":"noul","noul":0.95}},"usage":{}}""";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	@Test
	void passesWhenEveryCriterionIsMet() {
		respondWith("""
				{"model":"jev-1.13.0","answers":{
				  "helpfulness":{"type":"score","score":3.2,
				    "legend":{"0":"Terrible","1":"Mostly unhelpful","2":"Mostly helpful","3":"Excellent"},
				    "probabilities":{"0":0.01,"1":0.04,"2":0.15,"3":0.80},"confidence":0.86},
				  "is_plausible":{"type":"noul","noul":0.95}
				},"usage":{"input_tokens":120,"output_tokens":20}}""");

		JevVerdict verdict = judge().judge("What is the weather in Paris?", "It is 15 degrees Celsius in Paris.");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.feedback()).isEmpty();
		assertThat(verdict.failures()).isEmpty();
		assertThat(verdict.summary()).isEqualTo("passed=true [helpfulness=PASSED, is_plausible=PASSED]");
		assertThat(verdict.response().requestId()).isEqualTo("req_0123456789");
	}

	@Test
	void failsANoulAndQuotesItsFalseSideAsTheDefect() {
		respondWith("""
				{"model":"jev-1.13.0","answers":{
				  "helpfulness":{"type":"score","score":3.0,
				    "legend":{"0":"Terrible","1":"Mostly unhelpful","2":"Mostly helpful","3":"Excellent"},
				    "probabilities":{"3":1.0},"confidence":0.9},
				  "is_plausible":{"type":"noul","noul":0.04}
				},"usage":{}}""");

		JevVerdict verdict = judge().judge("What is the weather in Paris?", "It is -255 degrees Celsius in Paris.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.failures()).singleElement()
			.satisfies(finding -> assertThat(finding.name()).isEqualTo("is_plausible"));
		assertThat(verdict.feedback())
			.isEqualTo("- is_plausible: Contains an impossible or absurd value (scored 0.04, needs at least 0.70)");
	}

	@Test
	void failsAScoreAndNamesBothTheLevelReachedAndTheLevelRequired() {
		respondWith("""
				{"model":"jev-1.13.0","answers":{
				  "helpfulness":{"type":"score","score":1.2,
				    "legend":{"0":"Terrible","1":"Mostly unhelpful","2":"Mostly helpful","3":"Excellent"},
				    "probabilities":{"0":0.10,"1":0.65,"2":0.20,"3":0.05},"confidence":0.71},
				  "is_plausible":{"type":"noul","noul":0.99}
				},"usage":{}}""");

		JevVerdict verdict = judge().judge("What is the weather in Paris?", "Weather is hard to predict.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.feedback()).isEqualTo(
				"- helpfulness: rated \"Mostly unhelpful: misses the main point\" (1.20), "
						+ "needs to reach 2.00 which is \"Mostly helpful: minor gaps remain\"");
	}

	@Test
	void reportsBothDefectsWhenBothCriteriaFail() {
		respondWith("""
				{"model":"jev-1.13.0","answers":{
				  "helpfulness":{"type":"score","score":0.4,
				    "legend":{"0":"Terrible","1":"Mostly unhelpful","2":"Mostly helpful","3":"Excellent"},
				    "probabilities":{"0":0.70,"1":0.20,"2":0.07,"3":0.03},"confidence":0.68},
				  "is_plausible":{"type":"noul","noul":0.10}
				},"usage":{}}""");

		JevVerdict verdict = judge().judge("What is the weather in Paris?", "It is -255 degrees in Narnia.");

		assertThat(verdict.failures()).hasSize(2);
		assertThat(verdict.feedback()).contains("- helpfulness: rated \"Terrible: irrelevant or off-topic\"")
			.contains("- is_plausible: Contains an impossible or absurd value");
	}

	@Test
	void treatsAFlatDistributionAsUndecidedRatherThanFailed() {
		respondWith("""
				{"model":"jev-1.13.0","answers":{
				  "helpfulness":{"type":"score","score":1.5,
				    "legend":{"0":"Terrible","1":"Mostly unhelpful","2":"Mostly helpful","3":"Excellent"},
				    "probabilities":{"0":0.25,"1":0.25,"2":0.25,"3":0.25},"confidence":0.20},
				  "is_plausible":{"type":"noul","noul":0.99}
				},"usage":{}}""");

		JevVerdict verdict = judge().judge("What is the weather in Paris?", "Possibly mild.");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.failures()).isEmpty();
		assertThat(verdict.inconclusive()).singleElement()
			.satisfies(finding -> assertThat(finding.name()).isEqualTo("helpfulness"));
	}

	@Test
	void canBeConfiguredToRejectAnUndecidedCriterion() {
		respondWith("""
				{"model":"jev-1.13.0","answers":{
				  "helpfulness":{"type":"score","score":1.5,
				    "legend":{"0":"Terrible","1":"Mostly unhelpful","2":"Mostly helpful","3":"Excellent"},
				    "probabilities":{"0":0.25,"1":0.25,"2":0.25,"3":0.25},"confidence":0.20}
				},"usage":{}}""");

		JevJudge strict = JevJudge.builder(this.mock.client())
			.score("helpfulness", HELPFULNESS, 2.0d)
			.failOnInconclusive(true)
			.build();

		JevVerdict verdict = strict.judge("What is the weather in Paris?", "Possibly mild.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.feedback()).contains("the rubric did not settle whether this reaches 2.00")
			.contains("0.50 of the probability supports the verdict, needs at least 0.60");
	}

	@Test
	void nouIsNotGatedOnConfidenceBecauseItCarriesNone() {
		respondWith("""
				{"model":"jev-1.13.0","answers":{"is_plausible":{"type":"noul","noul":0.99}},"usage":{}}""");

		JevJudge strict = JevJudge.builder(this.mock.client())
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.minConfidence(0.99d)
			.failOnInconclusive(true)
			.build();

		assertThat(strict.judge("q", "a").passed()).isTrue();
	}

	@Test
	void judgesAChoiceAgainstTheAcceptedOptions() {
		respondWith("""
				{"model":"jev-1.13.0","answers":{"tone":{"type":"choice","choice":"dismissive",
				  "probabilities":{"helpful":0.12,"neutral":0.20,"dismissive":0.68},"confidence":0.74}},"usage":{}}""");

		JevJudge toneJudge = JevJudge.builder(this.mock.client())
			.choice("tone",
					Choice.builder()
						.instructions("What tone does `assistant_answer` take?")
						.option("helpful", "Answers and offers next steps")
						.option("neutral", "Answers plainly")
						.option("dismissive", "Brushes the question off")
						.build(),
					"helpful", "neutral")
			.build();

		JevVerdict verdict = toneJudge.judge("q", "Figure it out yourself.");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.feedback())
			.contains("tone: classified as \"dismissive\" (0.68 of the probability on unacceptable options)")
			.contains("acceptable values are");
	}

	@Test
	void rejectsAnAcceptedOptionThatIsNotOneOfTheChoiceOptions() {
		Choice tone = Choice.of("What tone?", "helpful", "neutral");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> JevCriterion.choice("tone", tone, "enthusiastic"))
			.withMessageContaining("is not one of the choice's options");
	}

	@Test
	void rejectsAScoreThresholdAboveTheRubric() {
		assertThatIllegalArgumentException().isThrownBy(() -> JevCriterion.score("helpfulness", HELPFULNESS, 9.0d))
			.withMessageContaining("highest level (3)");
	}

	@Test
	void rejectsDuplicateCriterionNames() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> JevJudge.builder(this.mock.client())
				.noul("is_plausible", PLAUSIBLE, 0.7d)
				.noul("is_plausible", PLAUSIBLE, 0.8d))
			.withMessageContaining("already declared");
	}

	@Test
	void describesAStructuredCriterionAsJsonRatherThanJavaToString() {
		// This is the path that reaches a model: describeFalseSide -> JevFinding.detail ->
		// feedback() -> appended to the retry prompt. A criterion described with a POJO or a
		// map used to render as Java syntax, describing the value differently from how it
		// was sent.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(
					"{\"model\":\"jev-1.13.0\",\"answers\":{\"is_plausible\":{\"type\":\"noul\",\"noul\":0.04}},"
							+ "\"usage\":{}}"));

		JevJudge judge = JevJudge.builder(this.mock.client())
			.noul("is_plausible", Noul.builder()
				.instructions("Are the values plausible?")
				.whenFalse(Map.of("what", "An impossible value", "example", "-255 C"))
				.build(), 0.7d)
			.build();

		// Map.of has a randomised iteration order, so assert on the rendering of a field
		// rather than on which one Jackson happens to write first.
		assertThat(judge.judge("q", "a").feedback()).contains("\"what\":\"An impossible value\"")
			.doesNotContain("what=An impossible value");
	}

	@Test
	void rejectsAJudgeWithoutCriteria() {
		assertThatIllegalArgumentException().isThrownBy(() -> JevJudge.builder(this.mock.client()).build())
			.withMessageContaining("at least one criterion");
	}

	@Test
	void acceptsARepeatedOptionAndKeepsTheOptionsInDeclarationOrder() {
		// Set.of would throw on the duplicate, and its iteration order is salted per JVM
		// run, which would make the feedback text differ between runs.
		Choice tone = Choice.of("What tone?", "helpful", "neutral", "dismissive");

		JevCriterion.QuestionCriterion criterion = JevCriterion.choice("tone", tone, "helpful", "neutral", "helpful");

		assertThat(criterion.acceptedOptions()).containsExactly("helpful", "neutral");
	}

	@Test
	void rejectsANullAcceptedOption() {
		Choice tone = Choice.of("What tone?", "helpful", "neutral");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> JevCriterion.choice("tone", tone, "helpful", null))
			.withMessageContaining("must not contain a null option");
	}

	@Test
	void reportsAMissingAnswerAsAnErrorRatherThanFailingTheWholeCall() {
		// A partial response should degrade that one criterion, the same way an
		// unrecognised answer kind does, not abort the caller's chat call. It is an
		// instrument failure, not an ambiguous question, so it is ERROR, not INCONCLUSIVE.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse("""
					{"model":"jev-1.13.0","answers":{
					  "helpfulness":{"type":"score","score":3.0,
					    "legend":{"0":"Terrible","1":"Unhelpful","2":"Helpful","3":"Excellent"},
					    "probabilities":{"3":1.0},"confidence":0.9}
					},"usage":{}}"""));

		JevVerdict verdict = judge().judge("q", "a");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.findings()).extracting(JevFinding::name).contains("is_plausible");
		assertThat(verdict.findings())
			.filteredOn(finding -> finding.name().equals("is_plausible"))
			.singleElement()
			.satisfies(finding -> {
				assertThat(finding.outcome()).isEqualTo(JevFinding.Outcome.ERROR);
				assertThat(finding.detail()).contains("returned no answer");
			});
		assertThat(verdict.errors()).singleElement().satisfies(finding -> assertThat(finding.name()).isEqualTo("is_plausible"));
		assertThat(verdict.inconclusive()).isEmpty();
	}

	@Test
	void failsACodeCheckAndQuotesItsDefectAlongsideTheQuestions() {
		// The check is answered in code, but it must land in the same verdict: it fails
		// the verdict, reaches the feedback and shows in the summary like a question does.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.questions.searched").doesNotExist())
			.andExpect(jsonPath("$.questions.is_plausible.type").value("noul"))
			.andRespond(MockTypeSafeServer.jsonResponse(PLAUSIBLE_ONLY));

		JevVerdict verdict = searchJudge().judge(JevJudgeInput.builder().question("Weather in Dublin?")
			.answer("No rain expected.")
			.field("search_required", true)
			.build());

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.summary()).isEqualTo("passed=false [searched=FAILED, is_plausible=PASSED]");
		assertThat(verdict.failures()).singleElement().satisfies(finding -> {
			assertThat(finding.criterion()).isInstanceOf(JevCriterion.CodeCriterion.class);
			assertThat(finding.answer()).isNull();
		});
		assertThat(verdict.feedback()).isEqualTo("- searched: a required search was skipped");
		this.mock.server().verify();
	}

	@Test
	void passesACodeCheckThatHolds() {
		respondWith(PLAUSIBLE_ONLY);

		JevVerdict verdict = searchJudge().judge(JevJudgeInput.builder()
			.question("Weather in Dublin?")
			.answer("Rain, 12C.")
			.field("search_required", true)
			.toolCall(new JevJudgeInput.ToolCall("web_search", "{\"q\":\"Dublin\"}", "Rain, 12C"))
			.build());

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.findings()).extracting(JevFinding::outcome)
			.containsExactly(JevFinding.Outcome.PASSED, JevFinding.Outcome.PASSED);
	}

	@Test
	void letsACheckThatThrowsPropagateWithoutSpendingACall() {
		// A throwing check is a bug in the check, not a verdict on the answer.
		JevJudge judge = JevJudge.builder(this.mock.client())
			.check("broken", input -> {
				throw new IllegalStateException("boom");
			}, "never")
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.build();

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> judge.judge("q", "a"))
			.withMessage("boom");
		this.mock.server().verify();
	}

	@Test
	void readsAnArbitraryStateThroughTheInputForCodeChecks() {
		respondWith(PLAUSIBLE_ONLY);

		JevVerdict verdict = searchJudge().judge(JsonContent.of(Map.of("search_required", false)));

		assertThat(verdict.passed()).isTrue();
	}

	@Test
	void rejectsANonObjectStateWhenTheJudgeHasCodeChecks() {
		assertThatIllegalArgumentException().isThrownBy(() -> searchJudge().judge(JsonContent.of("plain text")))
			.withMessageContaining("JSON object");
	}

	@Test
	void rejectsAJudgeOfCodeChecksAlone() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> JevJudge.builder(this.mock.client()).check("always", input -> true, "never").build())
			.withMessageContaining("at least one question criterion");
	}

	@Test
	void rejectsACheckNamedLikeAQuestion() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> JevJudge.builder(this.mock.client())
				.noul("is_plausible", PLAUSIBLE, 0.7d)
				.check("is_plausible", input -> true, "never"))
			.withMessageContaining("already declared");
	}

	@Test
	void sendsEveryTypedInputFieldUnderItsDocumentedName() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.user_question").value("Weather in Paris?"))
			.andExpect(jsonPath("$.state.assistant_answer").value("15C"))
			.andExpect(jsonPath("$.state.expected_output.search_required").value(true))
			.andExpect(jsonPath("$.state.supporting_context[0]").value("Paris is mild in spring."))
			.andExpect(jsonPath("$.state.tool_calls[0].name").value("currentWeather"))
			.andExpect(jsonPath("$.state.tool_calls[0].result").value("15"))
			.andRespond(MockTypeSafeServer.jsonResponse(PLAUSIBLE_ONLY));

		JevJudge.builder(this.mock.client())
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.build()
			.judge(JevJudgeInput.builder()
				.question("Weather in Paris?")
				.answer("15C")
				.expected(Map.of("search_required", true))
				.context("Paris is mild in spring.")
				.toolCall(new JevJudgeInput.ToolCall("currentWeather", "{}", "15"))
				.build());

		this.mock.server().verify();
	}

	@Test
	void passesAScoreSplitBetweenTwoPassingLevelsDespiteLowConfidence() {
		// Recorded live: 93% of the mass sits on passing levels, but it is split between
		// "Mostly helpful" and "Excellent", so the distribution's own confidence is ~0.5.
		// The pass/fail decision is not in doubt, and must not be reported as undecided.
		respondWith("""
				{"model":"jev-1.13.0","answers":{
				  "helpfulness":{"type":"score","score":2.34,
				    "legend":{"0":"Terrible","1":"Mostly unhelpful","2":"Mostly helpful","3":"Excellent"},
				    "probabilities":{"0":0.0,"1":0.07,"2":0.52,"3":0.41},"confidence":0.51},
				  "is_plausible":{"type":"noul","noul":0.97}
				},"usage":{}}""");

		JevVerdict verdict = judge().judge("What is the weather in Paris?", "It is 15 degrees Celsius in Paris.");

		assertThat(verdict.summary()).isEqualTo("passed=true [helpfulness=PASSED, is_plausible=PASSED]");
	}

	@Test
	void countsOnlyWholeLevelsAtOrAboveAFractionalMinimumAsPassing() {
		// minimum 2.5: level 2 is below it, so only level 3 supports a pass.
		QuestionCriterion helpfulness = JevCriterion.score("helpfulness", HELPFULNESS, 2.5d);
		ScoreAnswer answer = new ScoreAnswer(2.5d, Map.of(), Map.of(2, 0.5d, 3, 0.5d), 0.5d);

		assertThat(JevJudge.scoreSupport(helpfulness, answer, true)).isEqualTo(0.5d);
		assertThat(JevJudge.scoreSupport(helpfulness, answer, false)).isEqualTo(0.5d);
	}

	@Test
	void fallsBackToTheAnswersConfidenceWithoutProbabilities() {
		QuestionCriterion helpfulness = JevCriterion.score("helpfulness", HELPFULNESS, 2.0d);

		assertThat(JevJudge.scoreSupport(helpfulness, new ScoreAnswer(2.8d, Map.of(), Map.of(), 0.42d), true)).isEqualTo(0.42d);
	}

	@Test
	void sumsTheAcceptedOptionsWhenJudgingAChoice() {
		// No single option is confident, but helpful + neutral together clearly pass.
		QuestionCriterion tone = JevCriterion.choice("tone",
				Choice.of("What tone?", "helpful", "neutral", "dismissive"), "helpful", "neutral");
		ChoiceAnswer answer = new ChoiceAnswer("neutral", Map.of("helpful", 0.35d, "neutral", 0.40d, "dismissive", 0.25d),
				0.30d);

		assertThat(JevJudge.choiceSupport(tone, answer, true)).isCloseTo(0.75d, within(1e-9));
	}

	@Test
	void canBeConfiguredToRejectACriterionTheServiceCouldNotAnswer() {
		respondWith("""
				{"model":"jev-1.13.0","answers":{
				  "helpfulness":{"type":"score","score":3.0,
				    "legend":{"0":"Terrible","1":"Unhelpful","2":"Helpful","3":"Excellent"},
				    "probabilities":{"3":1.0},"confidence":0.9}
				},"usage":{}}""");

		JevJudge strict = JevJudge.builder(this.mock.client())
			.score("helpfulness", HELPFULNESS, 2.0d)
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.failOnError(true)
			.build();

		JevVerdict verdict = strict.judge("q", "a");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.failures()).singleElement().satisfies(finding -> assertThat(finding.name()).isEqualTo("is_plausible"));
	}

	@Test
	void doesNotAskACriterionThatDoesNotApply() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.questions.is_grounded").doesNotExist())
			.andExpect(jsonPath("$.questions.is_plausible.type").value("noul"))
			.andRespond(MockTypeSafeServer.jsonResponse(PLAUSIBLE_ONLY));

		JevJudge judge = JevJudge.builder(this.mock.client())
			.criterion(JevCriterion.noul("is_grounded", PLAUSIBLE, 0.7d).appliesWhen(input -> !input.context().isEmpty()))
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.build();

		JevVerdict verdict = judge.judge("q", "a");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.summary()).isEqualTo("passed=true [is_grounded=NOT_APPLICABLE, is_plausible=PASSED]");
		assertThat(verdict.notApplicable()).singleElement().satisfies(finding -> assertThat(finding.answer()).isNull());
		this.mock.server().verify();
	}

	@Test
	void makesNoCallWhenNoQuestionApplies() {
		JevJudge judge = JevJudge.builder(this.mock.client())
			.criterion(JevCriterion.noul("is_grounded", PLAUSIBLE, 0.7d).appliesWhen(input -> false))
			.build();

		JevVerdict verdict = judge.judge("q", "a");

		assertThat(verdict.passed()).isTrue();
		assertThat(verdict.response()).isNull();
		this.mock.server().verify();
	}

	@Test
	void skipsTheCallWhenACodeCheckFailsUnderFailFast() {
		JevJudge judge = JevJudge.builder(this.mock.client())
			.check("searched", input -> !input.toolCalls().isEmpty(), "no search was made")
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.failFast(true)
			.build();

		JevVerdict verdict = judge.judge("q", "a");

		assertThat(verdict.passed()).isFalse();
		assertThat(verdict.response()).isNull();
		assertThat(verdict.summary()).isEqualTo("passed=false [searched=FAILED, is_plausible=NOT_APPLICABLE]");
		assertThat(verdict.feedback()).isEqualTo("- searched: no search was made");
		this.mock.server().verify();
	}

	@Test
	void stillAsksTheQuestionsUnderFailFastWhenEveryCheckPasses() {
		respondWith(PLAUSIBLE_ONLY);

		JevJudge judge = JevJudge.builder(this.mock.client())
			.check("always", input -> true, "never")
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.failFast(true)
			.build();

		assertThat(judge.judge("q", "a").passed()).isTrue();
		this.mock.server().verify();
	}

	private JevJudge searchJudge() {
		return JevJudge.builder(this.mock.client())
			.check("searched",
					input -> input.toolCalls().isEmpty() != Boolean.TRUE.equals(input.field("search_required", Boolean.class)),
					"a required search was skipped")
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.build();
	}

	private JevJudge judge() {
		return JevJudge.builder(this.mock.client())
			.score("helpfulness", HELPFULNESS, 2.0d)
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.build();
	}

	private void respondWith(String body) {
		this.mock.server().expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL)).andRespond(MockTypeSafeServer.jsonResponse(body));
	}

}
