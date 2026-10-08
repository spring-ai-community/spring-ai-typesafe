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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.RetryPolicy;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeApiException;
import org.springaicommunity.typesafe.exception.TypeSafeException;
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
 * The two compose. At the default order this advisor sits nearer the model than both the
 * self-refine advisor and Spring AI's tool loop (a higher order value runs later, closer to
 * the call), so it screens every model call: each retry attempt is screened before it is
 * judged, and the input battery runs on each call too. Order it before the self-refine
 * advisor, for example at {@code HIGHEST_PRECEDENCE + 150}, to screen only the original
 * request and the final answer, once per turn.
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
 * <p>
 * A battery that could not reach a verdict has not found the turn clean, and the two
 * directions are not equally placed to act on that. An input battery that cannot run
 * fails the turn ({@link ScreenErrorPolicy#FAIL_CLOSED}, the default): the model has not
 * been called yet, nothing has been spent, and an unscreened prompt is the one thing this
 * advisor exists to prevent. An output battery that cannot run lets the answer through
 * ({@link ScreenErrorPolicy#FAIL_OPEN}, the default): the model has already run and been
 * paid for, so discarding its answer over a screening outage costs more than it saves.
 * Neither default is a silent pass — the turn is logged at WARN and the response carries
 * {@link #SCREENING_FAILURE_CONTEXT_KEY}, which is the difference between "not screened"
 * and "screened and clean". A refused turn carries no such mark: a refusal is the
 * guardrail's own words, not an unscreened answer. A failure the service caused by
 * rejecting our own request — an unknown hazard name, a revoked key — rethrows whatever
 * the policy says, because that is a battery to fix rather than an outage to ride out.
 *
 * @author Christian Tzolov
 */
public class JevGuardrailAdvisor implements CallAdvisor, StreamAdvisor {

	/** What a blocked turn says when the caller has not supplied wording. */
	public static final String DEFAULT_REFUSAL = "I can't help with that.";

	/**
	 * The response-context key a refused turn carries, holding the name of the
	 * {@link JevGuardrail.Outcome} that refused it. It marks the response as the
	 * guardrail's own words rather than the model's answer, so an advisor further out,
	 * such as {@link JevSelfRefineAdvisor}, does not judge a refusal or retry it.
	 */
	public static final String OUTCOME_CONTEXT_KEY = "jev.guardrail.outcome";

	/**
	 * The response-context key a turn carries when a battery could not screen it and the
	 * policy let it through. Its value is the direction whose screening did not run,
	 * {@code "input"} or {@code "output"}; a response without the key has been screened.
	 * It exists so that "not screened" cannot be mistaken for "screened and clean".
	 */
	public static final String SCREENING_FAILURE_CONTEXT_KEY = "jev.guardrail.screening_failure";

	/** The value of {@link #SCREENING_FAILURE_CONTEXT_KEY} when the input battery failed. */
	private static final String INPUT_DIRECTION = "input";

	/** The value of {@link #SCREENING_FAILURE_CONTEXT_KEY} when the output battery failed. */
	private static final String OUTPUT_DIRECTION = "output";

	/** What a turn routed to support says when the caller has not supplied wording. */
	public static final String DEFAULT_SUPPORT_MESSAGE = "It sounds like you may be going through something "
			+ "difficult. I can't help with this here, but people who can are available — please consider "
			+ "reaching out to a local support line.";

	private static final Logger logger = LoggerFactory.getLogger(JevGuardrailAdvisor.class);

	/**
	 * What to do about a battery that could not reach a verdict, which is not the same as
	 * a battery that found nothing. The direction decides the default; see the class
	 * javadoc for why the two differ.
	 */
	public enum ScreenErrorPolicy {

		/** Let the turn through unscreened, logged and marked on the response. */
		FAIL_OPEN,

		/** Rethrow the screening failure, failing the turn. */
		FAIL_CLOSED

	}

	private final TypeSafeClient typeSafeClient;

	private final @Nullable JevGuardrail inputBattery;

	private final @Nullable JevGuardrail outputBattery;

	private final String refusal;

	private final String supportMessage;

	private final boolean blockOnReview;

	private final int advisorOrder;

	private final ScreenErrorPolicy inputErrorPolicy;

	private final ScreenErrorPolicy outputErrorPolicy;

	private JevGuardrailAdvisor(TypeSafeClient typeSafeClient, @Nullable JevGuardrail inputBattery,
								@Nullable JevGuardrail outputBattery, String refusal, String supportMessage, boolean blockOnReview,
								int advisorOrder, ScreenErrorPolicy inputErrorPolicy, ScreenErrorPolicy outputErrorPolicy) {
		this.typeSafeClient = typeSafeClient;
		this.inputBattery = inputBattery;
		this.outputBattery = outputBattery;
		this.refusal = refusal;
		this.supportMessage = supportMessage;
		this.blockOnReview = blockOnReview;
		this.advisorOrder = advisorOrder;
		this.inputErrorPolicy = inputErrorPolicy;
		this.outputErrorPolicy = outputErrorPolicy;
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

		// The direction whose battery could not run, if any. Reported on the response so
		// that a caller can tell an unscreened turn from a screened-and-clean one.
		String unscreened = null;

		if (this.inputBattery != null) {
			UserMessage userMessage = chatClientRequest.prompt().getUserMessage();
			String text = userMessage == null ? "" : userMessage.getText();
			if (text != null && !text.isBlank()) {
				JevGuardrail.Verdict verdict = screen(this.inputBattery, text, INPUT_DIRECTION, this.inputErrorPolicy);
				if (verdict == null) {
					unscreened = INPUT_DIRECTION;
				}
				else if (shouldBlock(verdict)) {
					logger.warn("Jev guardrail blocked the request: {}", verdict.summary());
					// Refused before the chain runs, so the model is never called.
					return refuse(chatClientRequest, verdict);
				}
				else {
					logInconclusive(verdict);
				}
			}
		}

		ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);

		if (this.outputBattery != null) {
			String answer = answerOf(response);
			if (!answer.isBlank()) {
				JevGuardrail.Verdict verdict = screen(this.outputBattery, answer, OUTPUT_DIRECTION,
						this.outputErrorPolicy);
				if (verdict == null) {
					unscreened = OUTPUT_DIRECTION;
				}
				else if (shouldBlock(verdict)) {
					logger.warn("Jev guardrail blocked the response: {}", verdict.summary());
					return refuse(chatClientRequest, verdict);
				}
				else {
					logInconclusive(verdict);
				}
			}
		}

		return unscreened == null ? response : withUnscreened(response, unscreened);
	}

	/**
	 * Runs one battery, applying the policy when the call that backs it fails.
	 * @param battery the battery to run
	 * @param text the text to screen
	 * @param direction {@code "input"} or {@code "output"}, for the log and the marker
	 * @param policy what to do when the battery cannot reach a verdict
	 * @return the verdict, or {@code null} when the policy let an unscreened turn through
	 * @throws TypeSafeException when the policy is {@code FAIL_CLOSED}, or always when the
	 * failure is our own request being rejected
	 */
	private JevGuardrail.@Nullable Verdict screen(JevGuardrail battery, String text, String direction,
												  ScreenErrorPolicy policy) {
		try {
			return battery.screen(this.typeSafeClient, text);
		}
		catch (TypeSafeException ex) {
			if (policy == ScreenErrorPolicy.FAIL_CLOSED || isConfigurationMistake(ex)) {
				throw ex;
			}
			logger.warn("Jev guardrail screening of the {} could not run, so the turn proceeds unscreened: {}",
					direction, ex.getMessage(), ex);
			return null;
		}
	}

	/**
	 * Whether the service rejected our own request rather than failing us. The reading is
	 * the same one {@code JevSelfRefineAdvisor.isTransient} makes, inverted, and it is
	 * deliberately independent of the policy: an unknown hazard name or a revoked key
	 * fails identically on a retry, so swallowing it would leave a battery that never
	 * screens anything and never says so.
	 * @param ex the screening failure
	 * @return {@code true} for a client-error status other than the two a retry can fix
	 */
	private static boolean isConfigurationMistake(TypeSafeException ex) {
		if (!(ex instanceof TypeSafeApiException apiException)) {
			return false;
		}
		int status = apiException.status();
		return status >= 400 && status < 500 && !RetryPolicy.DEFAULT_RETRYABLE_STATUSES.contains(status);
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
		Map<String, Object> context = new HashMap<>(request.context());
		context.put(OUTCOME_CONTEXT_KEY, verdict.outcome().name());
		return ChatClientResponse.builder().chatResponse(chatResponse).context(context).build();
	}

	/**
	 * Marks a response whose turn was not screened, so that a caller, or an advisor
	 * further out, can tell it from one that passed. {@code mutate()} rather than
	 * {@code builder()}: the response is a record of two components and only the context
	 * has to change.
	 * @param response the model's response
	 * @param direction the battery that could not run
	 * @return a copy carrying {@link #SCREENING_FAILURE_CONTEXT_KEY}
	 */
	private static ChatClientResponse withUnscreened(ChatClientResponse response, String direction) {
		Map<String, Object> context = new HashMap<>(response.context());
		context.put(SCREENING_FAILURE_CONTEXT_KEY, direction);
		return response.mutate().context(context).build();
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

		// Nearer the model than the self-refine advisor's default, so every attempt is
		// screened before it is judged; see the class javadoc for screening once per turn.
		private int advisorOrder = BaseAdvisor.LOWEST_PRECEDENCE - 1000;

		private ScreenErrorPolicy inputErrorPolicy = ScreenErrorPolicy.FAIL_CLOSED;

		private ScreenErrorPolicy outputErrorPolicy = ScreenErrorPolicy.FAIL_OPEN;

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

		/**
		 * What to do when the input battery cannot reach a verdict. {@code FAIL_CLOSED} by
		 * default: the model has not run yet, so the turn can be failed without losing
		 * anything, and an unscreened prompt is what this advisor exists to stop.
		 * @param inputErrorPolicy the policy
		 * @return this builder
		 */
		public Builder inputErrorPolicy(ScreenErrorPolicy inputErrorPolicy) {
			Assert.notNull(inputErrorPolicy, "inputErrorPolicy must not be null");
			this.inputErrorPolicy = inputErrorPolicy;
			return this;
		}

		/**
		 * What to do when the output battery cannot reach a verdict. {@code FAIL_OPEN} by
		 * default: the model has already run and its tokens are already spent, so an
		 * unscreened answer is returned rather than turned into an error, marked with
		 * {@link JevGuardrailAdvisor#SCREENING_FAILURE_CONTEXT_KEY} and logged at WARN.
		 * @param outputErrorPolicy the policy
		 * @return this builder
		 */
		public Builder outputErrorPolicy(ScreenErrorPolicy outputErrorPolicy) {
			Assert.notNull(outputErrorPolicy, "outputErrorPolicy must not be null");
			this.outputErrorPolicy = outputErrorPolicy;
			return this;
		}

		public JevGuardrailAdvisor build() {
			Assert.isTrue(this.inputBattery != null || this.outputBattery != null,
					"a guardrail that screens neither direction does nothing");
			return new JevGuardrailAdvisor(this.typeSafeClient, this.inputBattery, this.outputBattery, this.refusal,
					this.supportMessage, this.blockOnReview, this.advisorOrder, this.inputErrorPolicy,
					this.outputErrorPolicy);
		}

	}

}
