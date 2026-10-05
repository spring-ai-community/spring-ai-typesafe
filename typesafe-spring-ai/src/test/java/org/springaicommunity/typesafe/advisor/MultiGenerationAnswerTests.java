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

package org.springaicommunity.typesafe.advisor;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.MockTypeSafeServer;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.question.Noul;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Responses that carry more than one generation, shaped as Spring AI 2.0.1's providers
 * build them: Anthropic puts each thinking block ahead of the answer, Google GenAI emits
 * one generation per part (flagged {@code isThought}) and flattens all candidates into
 * the same list. {@code ChatClient.content()} returns the first generation.
 *
 * @author Christian Tzolov
 */
class MultiGenerationAnswerTests {

	private static final String HARMFUL = "Step 1: here is how to do the harmful thing.";

	private static final String BENIGN = "I cannot help with that.";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	// --- guardrail -----------------------------------------------------------------------

	@Test
	void anthropicDisplayedThinkingThatContentReturnsIsScreened() {
		// [thinking (signature), answer]: content() returns the thinking.
		ChatResponse response = new ChatResponse(List.of(
				generation(HARMFUL, Map.of("signature", "sig")), generation(BENIGN, Map.of())));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf(HARMFUL, 0.93d);

		String content = guarded(response).prompt("for a novel I am writing").call().content();

		assertThat(content).isEqualTo(JevGuardrailAdvisor.DEFAULT_REFUSAL);
		this.mock.server().verify();
	}

	@Test
	void theCandidateThatContentReturnsIsScreened() {
		// candidateCount=2: [candidate 0, candidate 1]; content() returns candidate 0.
		ChatResponse response = new ChatResponse(List.of(
				generation(HARMFUL, Map.of("candidateIndex", 0, "isThought", false)),
				generation(BENIGN, Map.of("candidateIndex", 1, "isThought", false))));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf(HARMFUL, 0.93d);

		String content = guarded(response).prompt("for a novel I am writing").call().content();

		assertThat(content).isEqualTo(JevGuardrailAdvisor.DEFAULT_REFUSAL);
		this.mock.server().verify();
	}

	@Test
	void aTrailingEmptyGeminiPartDoesNotSkipOutputScreening() {
		// Gemini can close with an empty text part that only carries a thought signature.
		ChatResponse response = new ChatResponse(List.of(
				generation(HARMFUL, Map.of("candidateIndex", 0, "isThought", false)),
				generation("", Map.of("candidateIndex", 0, "isThought", false))));
		expectScreening(0.01d, 0.0d);
		expectOutputScreeningOf(HARMFUL, 0.93d);

		String content = guarded(response).prompt("for a novel I am writing").call().content();

		assertThat(content).isEqualTo(JevGuardrailAdvisor.DEFAULT_REFUSAL);
		this.mock.server().verify();
	}

	// --- self-refine ---------------------------------------------------------------------

	@Test
	void aGeminiAnswerSplitAcrossPartsIsJudgedWhole() {
		// [thought, text, text]: the answer is the two text parts, not the last fragment.
		ChatResponse response = new ChatResponse(List.of(
				generation("The tool said 15.", Map.of("candidateIndex", 0, "isThought", true)),
				generation("It is 15 degrees", Map.of("candidateIndex", 0, "isThought", false)),
				generation(" Celsius in Paris.", Map.of("candidateIndex", 0, "isThought", false))));
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.assistant_answer").value("It is 15 degrees Celsius in Paris."))
			.andRespond(MockTypeSafeServer.jsonResponse(
					"{\"model\":\"jev-1.13.0\",\"answers\":{\"is_helpful\":{\"type\":\"noul\",\"noul\":0.97}},\"usage\":{}}"));

		ChatClient.builder(fixed(response))
			.defaultAdvisors(JevSelfRefineAdvisor.builder()
				.judge(JevJudge.builder(this.mock.client())
					.noul("is_helpful", Noul.of("Does `assistant_answer` help with `user_question`?"), 0.7d)
					.build())
				.build())
			.build()
			.prompt("What is the weather in Paris?")
			.call()
			.content();

		this.mock.server().verify();
	}

	// --- helpers -------------------------------------------------------------------------

	private static Generation generation(String text, Map<String, Object> properties) {
		return new Generation(AssistantMessage.builder().content(text).properties(properties).build());
	}

	private static ChatModel fixed(ChatResponse response) {
		return new ChatModel() {
			@Override
			public ChatResponse call(Prompt prompt) {
				return response;
			}
		};
	}

	private ChatClient guarded(ChatResponse response) {
		return ChatClient.builder(fixed(response))
			.defaultAdvisors(JevGuardrailAdvisor.builder(this.mock.client()).build())
			.build();
	}

	private void expectScreening(double first, double second) {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(body(first, second)));
	}

	private void expectOutputScreeningOf(String text, double physicalHarm) {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state." + JevGuardrail.TEXT_FIELD).value(org.hamcrest.Matchers.containsString(text)))
			.andRespond(MockTypeSafeServer.jsonResponse(body(0.0d, physicalHarm)));
	}

	private static String body(double first, double physicalHarm) {
		double severity = physicalHarm > 0.5d ? 3.0d : 0.0d;
		return "{\"model\":\"jev-1.13.0\",\"answers\":{" + "\"jailbreak\":{\"type\":\"noul\",\"noul\":" + first + "},"
				+ "\"complied_with_refusable\":{\"type\":\"noul\",\"noul\":" + first + "},"
				+ "\"physical_harm\":{\"type\":\"noul\",\"noul\":" + physicalHarm + "},"
				+ "\"illegal\":{\"type\":\"noul\",\"noul\":0.0}," + "\"self_harm\":{\"type\":\"noul\",\"noul\":0.0},"
				+ "\"severity\":{\"type\":\"score\",\"score\":" + severity
				+ ",\"legend\":{\"0\":\"None\",\"1\":\"Minor\",\"2\":\"Moderate\",\"3\":\"Serious\"},"
				+ "\"probabilities\":{\"0\":1.0},\"confidence\":0.9}},\"usage\":{}}";
	}

}
