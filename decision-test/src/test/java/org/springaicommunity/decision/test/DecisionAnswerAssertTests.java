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

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.MockTypeSafeServer;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.question.Noul;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.hamcrest.Matchers.hasKey;
import static org.springaicommunity.decision.test.DecisionAssertions.assertThatAnswer;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * The plain-language judging chain: what it sends, and that it sends it once.
 *
 * @author Christian Tzolov
 */
class DecisionAnswerAssertTests {

	private static final String ANSWER = "Refunds for last month are processed within 5 days.";

	private static final String ANSWERS_THE_QUESTION = "Does `assistant_answer` address `user_question`?";

	private static final String INTENT = "What is the customer's intent?";

	private static final String POLITENESS = "How polite is `assistant_answer`?";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	@Test
	void sendsEveryCriterionInOneCallAndPassesWhenAllHold() {
		this.mock.server()
			.expect(once(), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.user_question").value("Can I get a refund for last month?"))
			.andExpect(jsonPath("$.state.assistant_answer").value(ANSWER))
			.andExpect(jsonPath("$.questions.does_assistant_answer_address_user_question.type").value("noul"))
			.andExpect(jsonPath("$.questions.what_is_the_customer_s_intent.type").value("choice"))
			.andExpect(jsonPath("$.questions.what_is_the_customer_s_intent.criteria").value(hasKey("technical")))
			.andExpect(jsonPath("$.questions.how_polite_is_assistant_answer.type").value("score"))
			.andRespond(MockTypeSafeServer.jsonResponse("""
					{"model":"jev-1.13.0","answers":{
					  "does_assistant_answer_address_user_question":{"type":"noul","noul":0.92},
					  "what_is_the_customer_s_intent":{"type":"choice","choice":"billing",
					    "probabilities":{"billing":0.9,"technical":0.07,"sales":0.03},"confidence":0.8},
					  "how_polite_is_assistant_answer":{"type":"score","score":2.3,
					    "probabilities":{"1":0.05,"2":0.6,"3":0.35},"confidence":0.6}
					},"usage":{}}"""));

		assertThatAnswer(ANSWER).usingClient(this.mock.client())
			.givenQuestion("Can I get a refund for last month?")
			.satisfies(ANSWERS_THE_QUESTION, 0.8)
			.isClassifiedAs(INTENT, "billing")
			.among("billing", "technical", "sales")
			.scores(POLITENESS, "Rude", "Neutral", "Polite", "Very polite")
			.atLeast("Polite")
			.judge()
			.finding("what_is_the_customer_s_intent")
			.hasConfidenceAtLeast(0.7)
			.choiceAnswer()
			.chose("billing");

		this.mock.server().verify();
	}

	@Test
	void judgeFailsWithTheCriterionsOwnWordsWhenItDoesNotHold() {
		respondWith("""
				{"does_assistant_answer_address_user_question":{"type":"noul","noul":0.2}}""");

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> assertThatAnswer(ANSWER).usingClient(this.mock.client())
				.satisfies(ANSWERS_THE_QUESTION)
				.judge())
			.withMessageContaining("[does_assistant_answer_address_user_question] did not")
			.withMessageContaining("the answer to \"" + ANSWERS_THE_QUESTION + "\" was no (scored 0.20, needs at least 0.70)");
	}

	@Test
	void evaluateReturnsAFailedVerdictWithoutAsserting() {
		respondWith("""
				{"does_assistant_answer_address_user_question":{"type":"noul","noul":0.2}}""");

		assertThatAnswer(ANSWER).usingClient(this.mock.client())
			.satisfies(ANSWERS_THE_QUESTION)
			.evaluate()
			.failed()
			.failedOn("does_assistant_answer_address_user_question")
			.failedOn(ANSWERS_THE_QUESTION);
	}

	@Test
	void judgesTheTextOfAChatResponse() {
		respondWith("""
				{"is_polite":{"type":"noul","noul":0.9}}""");
		ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage(ANSWER))));

		assertThatAnswer(response).usingClient(this.mock.client())
			.satisfies("is_polite", Noul.of("Is `assistant_answer` polite?"), 0.7)
			.judge();
	}

	@Test
	void usesAnExistingJudge() {
		respondWith("""
				{"is_polite":{"type":"noul","noul":0.9}}""");
		JevJudge judge = JevJudge.builder(this.mock.client())
			.noul("is_polite", Noul.of("Is `assistant_answer` polite?"), 0.7)
			.build();

		assertThatAnswer(ANSWER).usingJudge(judge).judge().passedOn("is_polite");
	}

	@Test
	void anExistingJudgeCannotBeCombinedWithCriteriaDeclaredOnTheChain() {
		JevJudge judge = JevJudge.builder(this.mock.client()).noul("x", Noul.of("x?"), 0.7).build();

		assertThatIllegalStateException()
			.isThrownBy(() -> assertThatAnswer(ANSWER).usingJudge(judge).satisfies("y?").judge())
			.withMessageContaining("cannot be combined");
	}

	@Test
	void refusesToJudgeWithoutAClient() {
		assertThatIllegalStateException().isThrownBy(() -> assertThatAnswer(ANSWER).satisfies("y?").judge())
			.withMessageContaining("call usingClient(...) or run the test with @DecisionTest");
	}

	@Test
	void refusesToJudgeWithoutACriterion() {
		assertThatIllegalStateException()
			.isThrownBy(() -> assertThatAnswer(ANSWER).usingClient(this.mock.client()).judge())
			.withMessageContaining("declare at least one criterion");
	}

	@Test
	void anAcceptedOptionMustBeOneOfTheOptions() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> assertThatAnswer(ANSWER).isClassifiedAs(INTENT, "refunds").among("billing", "sales"))
			.withMessageContaining("accepted option 'refunds' is not one of [billing, sales]");
	}

	@Test
	void aPassingLevelMustBeOneOfTheLevels() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> assertThatAnswer(ANSWER).scores(POLITENESS, "Rude", "Polite").atLeast("Warm"))
			.withMessageContaining("'Warm' is not one of the levels [Rude, Polite]");
	}

	@Test
	void namesACriterionAfterItsWordsAndKeepsNamesUnique() {
		DecisionAnswerAssert chain = assertThatAnswer(ANSWER);

		assertThat(chain.nameOf("Is it polite?")).isEqualTo("is_it_polite");
		chain.satisfies("Is it polite?");
		assertThat(chain.nameOf("Is it polite?")).isEqualTo("is_it_polite_2");
		assertThat(chain.nameOf("???")).isEqualTo("criterion");
		assertThat(chain.nameOf("x".repeat(80))).hasSize(48);
	}

	private void respondWith(String answers) {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer
				.jsonResponse("{\"model\":\"jev-1.13.0\",\"answers\":" + answers + ",\"usage\":{}}"));
	}

}
