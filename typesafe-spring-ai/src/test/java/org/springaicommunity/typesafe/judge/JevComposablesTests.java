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
import org.springaicommunity.typesafe.MockTypeSafeServer;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * The small composables built on the judge: the confidence gate, the composite score and the
 * Spring AI {@code Evaluator} adapter.
 *
 * @author Christian Tzolov
 */
class JevComposablesTests {

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	// --- JevConfidenceGate -------------------------------------------------------------

	@Test
	void escalatesAnythingBelowTheFloorWhateverTheAction() {
		JevConfidenceGate gate = JevConfidenceGate.builder().floor(0.6d).require("transfer", 0.85d).build();

		assertThat(gate.decide("check_balance", 0.59d)).isEqualTo(JevConfidenceGate.Decision.ESCALATE);
		assertThat(gate.decide("transfer", 0.59d)).isEqualTo(JevConfidenceGate.Decision.ESCALATE);
	}

	@Test
	void asksForConfirmationOnlyWhereTheActionDemandsMoreThanTheFloor() {
		JevConfidenceGate gate = JevConfidenceGate.builder().floor(0.6d).require("transfer", 0.85d).build();

		// Same confidence, different stakes, different answer.
		assertThat(gate.decide("check_balance", 0.7d)).isEqualTo(JevConfidenceGate.Decision.EXECUTE);
		assertThat(gate.decide("transfer", 0.7d)).isEqualTo(JevConfidenceGate.Decision.CONFIRM);
		assertThat(gate.decide("transfer", 0.86d)).isEqualTo(JevConfidenceGate.Decision.EXECUTE);
	}

	@Test
	void gatesDirectlyOnAChoiceAnswer() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse("""
					{"model":"jev-1.13.0","answers":{"intent":{"type":"choice","choice":"transfer",
					"probabilities":{"transfer":0.7,"balance":0.3},"confidence":0.7}},"usage":{}}"""));

		SystemOneResponse response = this.mock.client()
			.systemOne("move 500 to savings",
					Map.of("intent", org.springaicommunity.typesafe.question.Choice.of("What is the intent?", "transfer",
							"balance")));

		JevConfidenceGate gate = JevConfidenceGate.builder().floor(0.6d).require("transfer", 0.85d).build();
		assertThat(gate.decide(response.choice("intent"))).isEqualTo(JevConfidenceGate.Decision.CONFIRM);
	}

	@Test
	void rejectsAnActionThatDemandsLessThanTheFloor() {
		// Such an action could never be reached, so it is a configuration mistake.
		assertThatIllegalArgumentException()
			.isThrownBy(() -> JevConfidenceGate.builder().floor(0.7d).require("trivial", 0.4d).build())
			.withMessageContaining("can never reach");
	}

	// --- JevCompositeScore -------------------------------------------------------------

	@Test
	void weighsNormalisedDimensionsIntoOneNumber() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(scoresBody(4.0d, 2.0d)));

		SystemOneResponse response = evaluateTwoScores();

		// depth 4/4 = 1.0 weighted .75, leadership 2/4 = 0.5 weighted .25 -> 0.875
		JevCompositeScore composite = JevCompositeScore.builder()
			.weight("depth", 0.75d)
			.weight("leadership", 0.25d)
			.build();

		assertThat(composite.of(response)).isEqualTo(0.875d, org.assertj.core.data.Offset.offset(1e-9d));
	}

	@Test
	void theSameDimensionsRankDifferentlyUnderDifferentWeights() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(scoresBody(4.0d, 0.0d)));
		SystemOneResponse response = evaluateTwoScores();

		JevCompositeScore engineering = JevCompositeScore.builder()
			.weight("depth", 0.9d)
			.weight("leadership", 0.1d)
			.build();
		JevCompositeScore management = JevCompositeScore.builder()
			.weight("depth", 0.1d)
			.weight("leadership", 0.9d)
			.build();

		assertThat(engineering.of(response)).isGreaterThan(management.of(response));
	}

	@Test
	void rejectsWeightsThatDoNotSumToOneUnlessToldTo() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> JevCompositeScore.builder().weight("a", 0.5d).weight("b", 0.2d).build())
			.withMessageContaining("must sum to 1");

		assertThat(JevCompositeScore.builder()
			.weight("a", 0.5d)
			.weight("b", 0.2d)
			.allowUnnormalisedWeights(true)
			.build()).isNotNull();
	}

	// --- JevEvaluator ------------------------------------------------------------------

	@Test
	void adaptsAVerdictOntoSpringAiEvaluationResponse() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.user_question").value("What is the weather in Paris?"))
			.andExpect(jsonPath("$.state.assistant_answer").value("It is -400 degrees."))
			.andRespond(MockTypeSafeServer.jsonResponse("""
					{"model":"jev-1.13.0","answers":{
					  "is_plausible":{"type":"noul","noul":0.02},
					  "is_relevant":{"type":"noul","noul":0.95}
					},"usage":{}}"""));

		EvaluationResponse result = new JevEvaluator(twoNoulJudge())
			.evaluate(new EvaluationRequest("What is the weather in Paris?", "It is -400 degrees."));

		assertThat(result.isPass()).isFalse();
		// One of two criteria passed.
		assertThat(result.getScore()).isEqualTo(0.5f);
		assertThat(result.getFeedback()).contains("is_plausible");
		assertThat(result.getMetadata()).containsKey(JevEvaluator.VERDICT_METADATA_KEY);
		assertThat(result.getMetadata().get(JevEvaluator.FINDINGS_METADATA_KEY))
			.isEqualTo(Map.of("is_plausible", "FAILED", "is_relevant", "PASSED"));
	}

	@Test
	void passesRetrievedDocumentsIntoTheJudgedState() {
		// The context is the evidence a groundedness criterion needs, so it must reach the
		// state rather than being dropped on the floor.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state." + JevEvaluator.CONTEXT_FIELD + "[0]").value("Paris is in France."))
			.andRespond(MockTypeSafeServer.jsonResponse("""
					{"model":"jev-1.13.0","answers":{
					  "is_plausible":{"type":"noul","noul":0.99},
					  "is_relevant":{"type":"noul","noul":0.99}
					},"usage":{}}"""));

		EvaluationResponse result = new JevEvaluator(twoNoulJudge()).evaluate(new EvaluationRequest("Where is Paris?",
				java.util.List.of(new Document("Paris is in France.")), "In France."));

		assertThat(result.isPass()).isTrue();
		assertThat(result.getScore()).isEqualTo(1.0f);
	}

	private JevJudge twoNoulJudge() {
		return JevJudge.builder(this.mock.client())
			.noul("is_plausible",
					Noul.builder()
						.instructions("Are the values plausible?")
						.whenFalse("Contains an impossible value")
						.build(),
					0.7d)
			.noul("is_relevant", Noul.builder().instructions("Is it relevant?").build(), 0.5d)
			.build();
	}

	private SystemOneResponse evaluateTwoScores() {
		return this.mock.client()
			.systemOne("anything",
					Map.of("depth", Score.of("How deep?", "none", "some", "good", "strong", "expert"), "leadership",
							Score.of("How much leadership?", "none", "some", "good", "strong", "expert")));
	}

	@Test
	void refusesToNormaliseAScoreWhoseRubricHeightIsUnknown() {
		// A legend-less answer has no scale to divide by. Returning 0 would have been a
		// plausible-looking number that scores every candidate identically, silently
		// collapsing the ranking this class exists to produce.
		ScoreAnswer noLegend = new ScoreAnswer(3.0d, Map.of(), Map.of(3, 1.0d), 0.9d);

		assertThatIllegalStateException().isThrownBy(() -> JevCompositeScore.normalise(noLegend))
			.withMessageContaining("rubric height is unknown");
	}

	private static String scoresBody(double depth, double leadership) {
		String legend = "{\"0\":\"none\",\"1\":\"some\",\"2\":\"good\",\"3\":\"strong\",\"4\":\"expert\"}";
		return "{\"model\":\"jev-1.13.0\",\"answers\":{" + "\"depth\":{\"type\":\"score\",\"score\":" + depth
				+ ",\"legend\":" + legend + ",\"probabilities\":{\"4\":1.0},\"confidence\":0.9},"
				+ "\"leadership\":{\"type\":\"score\",\"score\":" + leadership + ",\"legend\":" + legend
				+ ",\"probabilities\":{\"2\":1.0},\"confidence\":0.9}},\"usage\":{}}";
	}

}
