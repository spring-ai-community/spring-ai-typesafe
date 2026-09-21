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




import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.judge.JevVerdict;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * A self-refine advisor that judges every model response with Jev and, when the response
 * falls short, feeds the defect back into the prompt and tries again.
 *
 * <p>
 * This is the same loop as an LLM-as-a-judge advisor, with the judging step swapped. A
 * judge model has to be prompted, has to generate text and has to be coerced into a
 * schema; Jev is asked typed questions and answers them directly, so the verdict needs no
 * parsing and cannot come back malformed. Several independent checks also ride in one
 * call, which a single rubric prompt cannot do.
 *
 * <pre>{@code
 * ChatClient chatClient = ChatClient.builder(chatModel)
 *     .defaultAdvisors(JevSelfRefineAdvisor.builder()
 *             .judge(judge)
 *             .maxRepeatAttempts(3)
 *             .build())
 *     .build();
 * }</pre>
 *
 * Streaming is not supported: a verdict can only be reached once the whole answer exists,
 * so there is nothing useful to emit incrementally.
 *
 * <h3>What the judge can see</h3>
 *
 * The judged state is the prompt on one side and the final answer on the other. Tool
 * results are included when they are present in the prompt, but with Spring AI's default
 * <em>internal</em> tool execution the model loop runs inside the {@code ChatModel} and the
 * intermediate {@link ToolResponseMessage}s never reach an advisor at all — the advisor
 * sees the original prompt and the finished answer, and nothing in between.
 *
 * <p>
 * This matters when writing criteria. A groundedness question phrased as "every claim must
 * trace back to the question" will fail a correct tool-using answer, because the value the
 * tool returned legitimately appears nowhere in the question. Either phrase such a
 * criterion against what is actually visible, or disable internal tool execution so the
 * tool messages land in the prompt.
 *
 * @author Christian Tzolov
 */
public class JevSelfRefineAdvisor implements CallAdvisor, StreamAdvisor {

	/**
	 * The largest accepted {@code maxRepeatAttempts}. Every attempt is a model call plus a
	 * judging call, so an unbounded retry budget is a runaway cost rather than a feature.
	 */
	public static final int MAX_REPEAT_ATTEMPTS_LIMIT = 100;

	private static final Logger logger = LoggerFactory.getLogger(JevSelfRefineAdvisor.class);

	private static final String DEFAULT_FEEDBACK_TEMPLATE = """
			%s

			Your previous answer was rejected by an automated evaluation for these reasons:
			%s

			Answer again, correcting every point above.
			""";

	private final JevJudge judge;

	private final int advisorOrder;

	private final int maxRepeatAttempts;

	private final boolean failOnExhaustedAttempts;

	private final BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate;

	private JevSelfRefineAdvisor(JevJudge judge, int advisorOrder, int maxRepeatAttempts,
			boolean failOnExhaustedAttempts,
			BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate) {
		this.judge = judge;
		this.advisorOrder = advisorOrder;
		this.maxRepeatAttempts = maxRepeatAttempts;
		this.failOnExhaustedAttempts = failOnExhaustedAttempts;
		this.skipEvaluationPredicate = skipEvaluationPredicate;
	}

	@Override
	public String getName() {
		return "Jev Self-Refine Advisor";
	}

	@Override
	public int getOrder() {
		return this.advisorOrder;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		Assert.notNull(chatClientRequest, "chatClientRequest must not be null");
		Assert.notNull(callAdvisorChain, "callAdvisorChain must not be null");

		ChatClientRequest request = chatClientRequest;

		// Unbounded on purpose: the exit is the attempt > maxRepeatAttempts check below,
		// which always returns or throws. A `attempt <= maxRepeatAttempts + 1` bound would
		// overflow to Integer.MIN_VALUE for a large maxRepeatAttempts and skip the body
		// entirely, failing the call without ever reaching the model.
		for (int attempt = 1;; attempt++) {

			ChatClientResponse response = callAdvisorChain.copy(this).nextCall(request);

			// A tool call is not an answer yet, so there is nothing to judge. The
			// predicate is given the request that actually produced this response, which
			// from the second attempt onward is the feedback-augmented one.
			if (this.skipEvaluationPredicate.test(request, response)) {
				if (logger.isDebugEnabled()) {
					logger.debug("Skipping evaluation because skipEvaluationPredicate returned true.");
				}
				return response;
			}

			JevVerdict verdict = this.judge.judge(getPromptQuestion(chatClientRequest), getAssistantAnswer(response));

			if (verdict.passed()) {
				if (logger.isInfoEnabled()) {
					logger.info("Jev judgement passed on attempt {}: {}", attempt, verdict.summary());
				}
				return response;
			}

			if (attempt > this.maxRepeatAttempts) {
				if (this.failOnExhaustedAttempts) {
					throw new JevSelfRefineFailedException(this.maxRepeatAttempts, verdict);
				}
				if (logger.isWarnEnabled()) {
					logger.warn("Jev judgement still failing after {} attempts, returning the last response. {}{}{}",
							this.maxRepeatAttempts, verdict.summary(), System.lineSeparator(), verdict.feedback());
				}
				return response;
			}

			if (logger.isWarnEnabled()) {
				logger.warn("Jev judgement failed on attempt {}: {}{}{}", attempt, verdict.summary(),
						System.lineSeparator(), verdict.feedback());
			}

			request = addEvaluationFeedback(chatClientRequest, verdict);
		}
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		return Flux
			.error(new UnsupportedOperationException("The Jev Self-Refine Advisor does not support streaming."));
	}

	/**
	 * Flattens the system message and the conversation into the text handed to the judge
	 * as {@code user_question}.
	 */
	private String getPromptQuestion(ChatClientRequest chatClientRequest) {
		String conversation = chatClientRequest.prompt()
			.getInstructions()
			.stream()
			.filter(message -> message.getMessageType() == MessageType.USER
					|| message.getMessageType() == MessageType.ASSISTANT
					|| message.getMessageType() == MessageType.TOOL)
			.map(JevSelfRefineAdvisor::renderMessage)
			.filter(StringUtils::hasText)
			.collect(Collectors.joining(System.lineSeparator()));

		SystemMessage systemMessage = chatClientRequest.prompt().getSystemMessage();
		if (systemMessage == null || !StringUtils.hasText(systemMessage.getText())) {
			return conversation;
		}
		return systemMessage.getMessageType() + ":" + systemMessage.getText() + System.lineSeparator() + conversation;
	}

	/**
	 * Renders one message for the judge. A {@link ToolResponseMessage} carries no text at
	 * all — its content is the tool results, which are exactly the evidence a groundedness
	 * criterion has to be judged against — so those are unpacked rather than dropped.
	 */
	private static String renderMessage(Message message) {
		if (message instanceof ToolResponseMessage toolResponseMessage) {
			return toolResponseMessage.getResponses()
				.stream()
				.map(response -> message.getMessageType() + ":" + response.name() + "=" + response.responseData())
				.collect(Collectors.joining(System.lineSeparator()));
		}
		String text = message.getText();
		return StringUtils.hasText(text) ? message.getMessageType() + ":" + text : "";
	}

	private String getAssistantAnswer(ChatClientResponse chatClientResponse) {
		if (chatClientResponse.chatResponse() == null || chatClientResponse.chatResponse().getResult() == null) {
			return "";
		}
		String text = chatClientResponse.chatResponse().getResult().getOutput().getText();
		return text == null ? "" : text;
	}

	/**
	 * Appends the synthesised feedback to the user message so the next attempt knows what
	 * to fix. The feedback names the criterion, the level reached and the level required,
	 * which is more actionable than a judge model's prose.
	 */
	private ChatClientRequest addEvaluationFeedback(ChatClientRequest originalRequest, JevVerdict verdict) {
		Prompt augmentedPrompt = originalRequest.prompt()
			.augmentUserMessage(userMessage -> userMessage.mutate()
				.text(DEFAULT_FEEDBACK_TEMPLATE.formatted(userMessage.getText(), verdict.feedback()))
				.build());

		return originalRequest.mutate().prompt(augmentedPrompt).build();
	}

	/**
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link JevSelfRefineAdvisor}.
	 */
	public static final class Builder {

		private @Nullable JevJudge judge;

		private int advisorOrder = BaseAdvisor.LOWEST_PRECEDENCE - 2000;

		private int maxRepeatAttempts = 3;

		private boolean failOnExhaustedAttempts;

		private BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate = (request,
				response) -> response.chatResponse() == null || response.chatResponse().hasToolCalls();

		private Builder() {
		}

		public Builder judge(JevJudge judge) {
			Assert.notNull(judge, "judge must not be null");
			this.judge = judge;
			return this;
		}

		public Builder order(int advisorOrder) {
			Assert.isTrue(advisorOrder > BaseAdvisor.HIGHEST_PRECEDENCE && advisorOrder < BaseAdvisor.LOWEST_PRECEDENCE,
					"advisorOrder must be between HIGHEST_PRECEDENCE and LOWEST_PRECEDENCE");
			this.advisorOrder = advisorOrder;
			return this;
		}

		/**
		 * How many times to retry a rejected answer. Each retry costs another model call
		 * and another judging call, so the value is capped at
		 * {@link #MAX_REPEAT_ATTEMPTS_LIMIT}: "retry until it passes" is not a supported
		 * mode, because a criterion the model cannot satisfy would loop forever.
		 * @param maxRepeatAttempts the number of retries, between 1 and
		 * {@link #MAX_REPEAT_ATTEMPTS_LIMIT}
		 * @return this builder
		 */
		public Builder maxRepeatAttempts(int maxRepeatAttempts) {
			Assert.isTrue(maxRepeatAttempts >= 1 && maxRepeatAttempts <= MAX_REPEAT_ATTEMPTS_LIMIT,
					"maxRepeatAttempts must be between 1 and " + MAX_REPEAT_ATTEMPTS_LIMIT);
			this.maxRepeatAttempts = maxRepeatAttempts;
			return this;
		}

		/**
		 * Whether to raise {@link JevSelfRefineFailedException} once the attempts are
		 * exhausted. Off by default, matching the self-refine behaviour of returning the
		 * best effort; turn it on where shipping a rejected answer is worse than failing.
		 * @param failOnExhaustedAttempts whether to fail instead of returning
		 * @return this builder
		 */
		public Builder failOnExhaustedAttempts(boolean failOnExhaustedAttempts) {
			this.failOnExhaustedAttempts = failOnExhaustedAttempts;
			return this;
		}

		public Builder skipEvaluationPredicate(
				BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate) {
			Assert.notNull(skipEvaluationPredicate, "skipEvaluationPredicate must not be null");
			this.skipEvaluationPredicate = skipEvaluationPredicate;
			return this;
		}

		public JevSelfRefineAdvisor build() {
			Assert.notNull(this.judge, "judge must be set");
			return new JevSelfRefineAdvisor(this.judge, this.advisorOrder, this.maxRepeatAttempts,
					this.failOnExhaustedAttempts, this.skipEvaluationPredicate);
		}

	}

}
