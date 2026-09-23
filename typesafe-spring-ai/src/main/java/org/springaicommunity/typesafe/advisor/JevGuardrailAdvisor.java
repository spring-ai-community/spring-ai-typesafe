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

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.TypeSafeClient;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.Assert;

/**
 * Screens what the user sends and what the model answers, refusing the turn when either
 * crosses a line.
 *
 * <p>
 * Both directions are checked because they fail differently. An input battery catches the
 * request that should never have been made; an output battery catches the reply that
 * should never have been given, which is the only one of the two that notices a jailbreak
 * that actually worked. Each battery is a single call carrying all its hazards.
 *
 * <p>
 * A blocked input never reaches the model at all — the refusal is returned in place of
 * the call, so nothing is spent and nothing is generated. A blocked output replaces the
 * generated text after the fact.
 *
 * <p>
 * This is a different job from {@link JevSelfRefineAdvisor}, which judges quality and
 * retries to improve it. Retrying does not help here: an unsafe answer is not a draft.
 * Place this one nearer the model (a higher order value runs later, closer to the call)
 * so it sees the final text, and note that the two compose — evaluation can retry while
 * the guardrail still has the last word.
 *
 * <pre>{@code
 * ChatClient.builder(chatModel)
 *     .defaultAdvisors(JevGuardrailAdvisor.builder(typeSafeClient).build())
 *     .build();
 * }</pre>
 *
 * Streaming is unsupported, for the same reason as the self-refine advisor: an output
 * battery needs the whole reply before it can judge it, by which point it has already
 * been emitted.
 *
 * @author Christian Tzolov
 */
public class JevGuardrailAdvisor implements CallAdvisor, StreamAdvisor {

	/** What a blocked turn says when the caller has not supplied wording. */
	public static final String DEFAULT_REFUSAL = "I can't help with that.";

	/** What a turn routed to support says when the caller has not supplied wording. */
	public static final String DEFAULT_SUPPORT_MESSAGE = "It sounds like you may be going through something "
			+ "difficult. I can't help with this here, but people who can are available — please consider "
			+ "reaching out to a local support line.";

	private static final Logger logger = LoggerFactory.getLogger(JevGuardrailAdvisor.class);

	private final TypeSafeClient typeSafeClient;

	private final @Nullable JevGuardrail inputBattery;

	private final @Nullable JevGuardrail outputBattery;

	private final String refusal;

	private final String supportMessage;

	private final boolean blockOnReview;

	private final int advisorOrder;

	private JevGuardrailAdvisor(TypeSafeClient typeSafeClient, @Nullable JevGuardrail inputBattery,
			@Nullable JevGuardrail outputBattery, String refusal, String supportMessage, boolean blockOnReview,
			int advisorOrder) {
		this.typeSafeClient = typeSafeClient;
		this.inputBattery = inputBattery;
		this.outputBattery = outputBattery;
		this.refusal = refusal;
		this.supportMessage = supportMessage;
		this.blockOnReview = blockOnReview;
		this.advisorOrder = advisorOrder;
	}

	@Override
	public String getName() {
		return "Jev Guardrail Advisor";
	}

	@Override
	public int getOrder() {
		return this.advisorOrder;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		Assert.notNull(chatClientRequest, "chatClientRequest must not be null");
		Assert.notNull(callAdvisorChain, "callAdvisorChain must not be null");

		if (this.inputBattery != null) {
			UserMessage userMessage = chatClientRequest.prompt().getUserMessage();
			String text = userMessage == null ? "" : userMessage.getText();
			if (text != null && !text.isBlank()) {
				JevGuardrail.Verdict verdict = this.inputBattery.screen(this.typeSafeClient, text);
				if (shouldBlock(verdict)) {
					logger.warn("Jev guardrail blocked the request: {}", verdict.summary());
					// Refused before the chain runs, so the model is never called.
					return refuse(chatClientRequest, verdict);
				}
				logInconclusive(verdict);
			}
		}

		ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);

		if (this.outputBattery != null) {
			String answer = answerOf(response);
			if (!answer.isBlank()) {
				JevGuardrail.Verdict verdict = this.outputBattery.screen(this.typeSafeClient, answer);
				if (shouldBlock(verdict)) {
					logger.warn("Jev guardrail blocked the response: {}", verdict.summary());
					return refuse(chatClientRequest, verdict);
				}
				logInconclusive(verdict);
			}
		}

		return response;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		return Flux.error(new UnsupportedOperationException("The Jev Guardrail Advisor does not support streaming."));
	}

	private boolean shouldBlock(JevGuardrail.Verdict verdict) {
		return verdict.blocked() || (this.blockOnReview && verdict.outcome() == JevGuardrail.Outcome.REVIEW);
	}

	private void logInconclusive(JevGuardrail.Verdict verdict) {
		if (verdict.outcome() == JevGuardrail.Outcome.REVIEW) {
			logger.info("Jev guardrail flagged a turn for review but let it through: {}", verdict.summary());
		}
	}

	private ChatClientResponse refuse(ChatClientRequest request, JevGuardrail.Verdict verdict) {
		String text = verdict.outcome() == JevGuardrail.Outcome.SUPPORT ? this.supportMessage : this.refusal;
		ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
		return ChatClientResponse.builder().chatResponse(chatResponse).context(request.context()).build();
	}

	private static String answerOf(ChatClientResponse response) {
		if (response.chatResponse() == null || response.chatResponse().getResult() == null) {
			return "";
		}
		String text = response.chatResponse().getResult().getOutput().getText();
		return text == null ? "" : text;
	}

	/**
	 * @param typeSafeClient the client
	 * @return a new builder, screening both directions with the default batteries
	 */
	public static Builder builder(TypeSafeClient typeSafeClient) {
		return new Builder(typeSafeClient);
	}

	/**
	 * Builder for {@link JevGuardrailAdvisor}.
	 */
	public static final class Builder {

		private final TypeSafeClient typeSafeClient;

		private @Nullable JevGuardrail inputBattery = JevGuardrail.defaultInputBattery();

		private @Nullable JevGuardrail outputBattery = JevGuardrail.defaultOutputBattery();

		private String refusal = DEFAULT_REFUSAL;

		private String supportMessage = DEFAULT_SUPPORT_MESSAGE;

		private boolean blockOnReview;

		// Later than the self-refine advisor's default, so a guardrail sees the answer
		// that
		// self-refinement settled on rather than an intermediate draft.
		private int advisorOrder = BaseAdvisor.LOWEST_PRECEDENCE - 1000;

		private Builder(TypeSafeClient typeSafeClient) {
			Assert.notNull(typeSafeClient, "typeSafeClient must not be null");
			this.typeSafeClient = typeSafeClient;
		}

		/**
		 * @param inputBattery what to ask of the user message, or {@code null} to skip
		 * screening the input
		 * @return this builder
		 */
		public Builder inputBattery(@Nullable JevGuardrail inputBattery) {
			this.inputBattery = inputBattery;
			return this;
		}

		/**
		 * @param outputBattery what to ask of the reply, or {@code null} to skip
		 * screening the output
		 * @return this builder
		 */
		public Builder outputBattery(@Nullable JevGuardrail outputBattery) {
			this.outputBattery = outputBattery;
			return this;
		}

		/**
		 * @param refusal what to say when a turn is blocked
		 * @return this builder
		 */
		public Builder refusal(String refusal) {
			Assert.hasText(refusal, "refusal must not be empty");
			this.refusal = refusal;
			return this;
		}

		/**
		 * @param supportMessage what to say when a turn is routed to support
		 * @return this builder
		 */
		public Builder supportMessage(String supportMessage) {
			Assert.hasText(supportMessage, "supportMessage must not be empty");
			this.supportMessage = supportMessage;
			return this;
		}

		/**
		 * Treats a flagged-for-review turn as a block. Off by default: review exists so a
		 * borderline case reaches a human instead of being refused by a threshold.
		 * @param blockOnReview whether to refuse on review
		 * @return this builder
		 */
		public Builder blockOnReview(boolean blockOnReview) {
			this.blockOnReview = blockOnReview;
			return this;
		}

		/**
		 * @param advisorOrder where in the chain this runs
		 * @return this builder
		 */
		public Builder order(int advisorOrder) {
			Assert.isTrue(advisorOrder > BaseAdvisor.HIGHEST_PRECEDENCE && advisorOrder < BaseAdvisor.LOWEST_PRECEDENCE,
					"advisorOrder must be between HIGHEST_PRECEDENCE and LOWEST_PRECEDENCE");
			this.advisorOrder = advisorOrder;
			return this;
		}

		public JevGuardrailAdvisor build() {
			Assert.isTrue(this.inputBattery != null || this.outputBattery != null,
					"a guardrail that screens neither direction does nothing");
			return new JevGuardrailAdvisor(this.typeSafeClient, this.inputBattery, this.outputBattery, this.refusal,
					this.supportMessage, this.blockOnReview, this.advisorOrder);
		}

	}

}
