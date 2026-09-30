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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.judge.JevConsistency;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springaicommunity.decision.test.DecisionAssertions.assertThat;

/**
 * Assertions on raw responses, answers and consistency reports.
 *
 * @author Christian Tzolov
 */
class ResponseAssertTests {

	private final SystemOneResponse response = new SystemOneResponse("jev-1.13.0", Map.of( //
			"is_urgent", new NoulAnswer(0.95), //
			"department", new ChoiceAnswer("billing", Map.of("billing", 0.82, "technical", 0.18), 0.64), //
			"frustration", new ScoreAnswer(1.1, Map.of(0, JsonContent.of("Calm"), 1, JsonContent.of("Frustrated"), 2,
					JsonContent.of("Very angry")), Map.of(0, 0.1, 1, 0.7, 2, 0.2), 0.5)),
			null);

	@Test
	void assertsOnEachKindOfAnswer() {
		assertThat(this.response).hasAnswers("is_urgent", "department", "frustration");
		assertThat(this.response).noul("is_urgent").isAtLeast(0.9).isTrue();
		assertThat(this.response).choice("department")
			.chose("billing")
			.choseOneOf("billing", "sales")
			.hasProbabilityAtLeast("billing", 0.8)
			.probabilityOf("technical")
			.isLessThan(0.2);
		assertThat(this.response).score("frustration").isBetween(1.0, 1.5).hasNearestLevel(1);
	}

	@Test
	void aWrongChoiceShowsTheDistribution() {
		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> assertThat(this.response).choice("department").chose("technical"))
			.withMessageContaining("[choice \"department\"]")
			.withMessageContaining("Expected the choice \"technical\", but was \"billing\"")
			.withMessageContaining("billing=0.82");
	}

	@Test
	void aMissingOrMistypedAnswerSaysWhatThereIs() {
		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(this.response).noul("tone"))
			.withMessageContaining("Expected an answer named \"tone\", but the response only has");
		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(this.response).noul("department"))
			.withMessageContaining("Expected \"department\" to be a NoulAnswer, but was ChoiceAnswer");
	}

	@Test
	void aScoreBelowTheMinimumNamesItsLevel() {
		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> assertThat(this.response).score("frustration").isAtLeast(2))
			.withMessageContaining("Expected the score to be at least 2.00, but was 1.10 (\"Frustrated\")");
	}

	@Test
	void aConsistencyReportIsStableOnlyWhenNoQuestionStraddlesTheThreshold() {
		JevConsistency.Report report = new JevConsistency.Report(List.of(), List.of(), Map.of("is_urgent",
				new JevConsistency.Statistics("is_urgent", List.of(0.6, 0.75, 0.8), 0.717, 0.085, 0.6, 0.8)));

		assertThat(report).isStableAt(0.5).hasNoFailedSamples().hasStandardDeviationAtMost("is_urgent", 0.1);
		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(report).isStableAt(0.7))
			.withMessageContaining("[is_urgent] straddled it")
			.withMessageContaining("is_urgent: min 0.600, max 0.800");
	}

}
