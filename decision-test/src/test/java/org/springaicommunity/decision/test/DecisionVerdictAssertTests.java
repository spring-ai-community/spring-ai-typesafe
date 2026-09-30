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

package org.springaicommunity.decision.test;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;
import org.springaicommunity.typesafe.MockTypeSafeServer;
import org.springaicommunity.typesafe.judge.JevFinding.Outcome;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.judge.JevVerdict;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springaicommunity.decision.test.DecisionAssertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Verdict assertions, run against verdicts a real {@link JevJudge} produced from canned
 * responses, so the messages asserted on carry the judge's own feedback.
 *
 * @author Christian Tzolov
 */
class DecisionVerdictAssertTests {

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

	private static final String LEGEND = """
			"legend":{"0":"Terrible","1":"Mostly unhelpful","2":"Mostly helpful","3":"Excellent"}""";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	@Test
	void passesAVerdictWhereEveryCriterionPassed() {
		JevVerdict verdict = verdict(helpfulness("3.2", "\"3\":0.9,\"2\":0.1"), 0.95);

		assertThat(verdict).passed().passedOn("helpfulness", "is_plausible").hasNoInconclusive().hasNoErrors();
		assertThat(verdict).feedback().isEmpty();
		assertThat(verdict).response().noul("is_plausible").isAtLeast(0.9);
		// AssertJ's own overloads still resolve beside ours.
		assertThat(verdict.findings()).hasSize(2);
	}

	@Test
	void aFailedVerdictNamesTheCriterionAndQuotesTheJudgesFeedback() {
		JevVerdict verdict = verdict(helpfulness("3.0", "\"3\":1.0"), 0.04);

		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(verdict).passed())
			.withMessageContaining("Expected the Jev verdict to pass, but [is_plausible] did not")
			.withMessageContaining(
					"[FAILED] is_plausible: Contains an impossible or absurd value (scored 0.04, needs at least 0.70)")
			.withMessageContaining("passed=false [helpfulness=PASSED, is_plausible=FAILED]");

		assertThat(verdict).failed().failedOn("is_plausible").passedOn("helpfulness");
		assertThat(verdict).finding("is_plausible").isFailed().hasDetailContaining("absurd").noulAnswer().isFalse();
	}

	@Test
	void anInconclusiveCriterionFailsPassedEvenThoughTheVerdictReportsPassed() {
		// Split evenly between levels 1 and 2: neither side holds the 60% the judge needs.
		JevVerdict verdict = verdict(helpfulness("1.5", "\"1\":0.5,\"2\":0.5"), 0.95);

		assertThat(verdict.passed()).isTrue();
		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(verdict).passed())
			.withMessageContaining("[INCONCLUSIVE] helpfulness: the rubric did not settle");
		assertThat(verdict).finding("helpfulness").hasOutcome(Outcome.INCONCLUSIVE);
	}

	@Test
	void passedOrAbortedAbortsWhenJevCouldNotDecide() {
		JevVerdict verdict = verdict(helpfulness("1.5", "\"1\":0.5,\"2\":0.5"), 0.95);

		assertThatExceptionOfType(TestAbortedException.class)
			.isThrownBy(() -> assertThat(verdict).passedOrAborted())
			.withMessageContaining("Jev could not decide [helpfulness]");
	}

	@Test
	void passedOrAbortedStillFailsOnAFailedCriterion() {
		JevVerdict verdict = verdict(helpfulness("1.5", "\"1\":0.5,\"2\":0.5"), 0.04);

		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(verdict).passedOrAborted())
			.withMessageContaining("[is_plausible] failed");
	}

	@Test
	void anUnknownCriterionListsTheOnesThatExist() {
		JevVerdict verdict = verdict(helpfulness("3.0", "\"3\":1.0"), 0.95);

		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(verdict).finding("tone"))
			.withMessageContaining("Expected a criterion named \"tone\", but the verdict has [helpfulness, is_plausible]");
	}

	@Test
	void aConfidenceBelowTheMinimumIsReportedWithTheDistribution() {
		JevVerdict verdict = verdict(helpfulness("3.0", "\"3\":0.8,\"2\":0.2", 0.55), 0.95);

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> assertThat(verdict).finding("helpfulness").hasConfidenceAtLeast(0.7))
			.withMessageContaining("Expected a confidence of at least 0.70, but was 0.55");
		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> assertThat(verdict).finding("is_plausible").hasConfidenceAtLeast(0.7))
			.withMessageContaining("its answer is a NoulAnswer, which has none");
	}

	private String helpfulness(String score, String probabilities) {
		return helpfulness(score, probabilities, 0.9);
	}

	private String helpfulness(String score, String probabilities, double confidence) {
		return """
				"helpfulness":{"type":"score","score":%s,%s,"probabilities":{%s},"confidence":%s}"""
			.formatted(score, LEGEND, probabilities, confidence);
	}

	private JevVerdict verdict(String helpfulness, double plausible) {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse("""
					{"model":"jev-1.13.0","answers":{%s,"is_plausible":{"type":"noul","noul":%s}},"usage":{}}"""
				.formatted(helpfulness, plausible)));
		return JevJudge.builder(this.mock.client())
			.score("helpfulness", HELPFULNESS, 2.0d)
			.noul("is_plausible", PLAUSIBLE, 0.7d)
			.build()
			.judge("What is the weather in Paris?", "It is 15 degrees Celsius in Paris.");
	}

}
