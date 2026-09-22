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




import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.judge.JevJudgeInput;
import org.springaicommunity.typesafe.judge.JevVerdict;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
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
 * The judged state is a {@link JevJudgeInput}: the system message and the conversation as
 * {@code user_question}, the final answer as {@code assistant_answer}, and any tool calls
 * found in the prompt as {@code tool_calls} — each assistant tool call paired, in order, with
 * the result its {@link ToolResponseMessage} carried: by id, or by tool name when the
 * provider leaves the id blank. Write a groundedness criterion against
 * {@code `tool_calls`}, since the value a tool returned legitimately appears nowhere in the
 * question.
 *
 * <p>
 * Tool calls only reach an advisor when they are in the prompt. With Spring AI's default
 * internal tool execution the tool loop runs below the advisor chain, and this advisor sees
 * the original prompt and the finished answer, with nothing in between. Place the advisor
 * so that the tool messages are already in the prompt it receives, or phrase such criteria
 * against what is actually visible.
 *
 * <h3>When judging itself fails</h3>
 *
 * A TypeSafe outage or timeout says nothing about the answer. By default the advisor
 * {@linkplain JudgeErrorPolicy#FAIL_OPEN fails open}: it logs the error and returns the
 * response it could not judge, rather than failing a chat call whose answer may be fine.
 * Choose {@link JudgeErrorPolicy#FAIL_CLOSED} where an unjudged answer must never ship.
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

	private final JudgeErrorPolicy judgeErrorPolicy;

	/**
	 * What to do when the judging call itself fails — the TypeSafe service is unreachable,
	 * times out or answers with an error. Such a failure says nothing about the answer.
	 */
	public enum JudgeErrorPolicy {

		/** Log the error and return the response that could not be judged. */
		FAIL_OPEN,

		/** Rethrow the error, failing the chat call. */
		FAIL_CLOSED

	}

	private JevSelfRefineAdvisor(JevJudge judge, int advisorOrder, int maxRepeatAttempts,
			boolean failOnExhaustedAttempts,
			BiPredicate<ChatClientRequest, ChatClientResponse> skipEvaluationPredicate,
			JudgeErrorPolicy judgeErrorPolicy) {
		this.judge = judge;
		this.advisorOrder = advisorOrder;
		this.maxRepeatAttempts = maxRepeatAttempts;
		this.failOnExhaustedAttempts = failOnExhaustedAttempts;
		this.skipEvaluationPredicate = skipEvaluationPredicate;
		this.judgeErrorPolicy = judgeErrorPolicy;
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
				logger.debug("Skipping evaluation because skipEvaluationPredicate returned true.");
				return response;
			}

			JevVerdict verdict;
			try {
				verdict = this.judge.judge(judgeInput(chatClientRequest, response));
			}
			catch (TypeSafeException ex) {
				if (this.judgeErrorPolicy == JudgeErrorPolicy.FAIL_CLOSED) {
					throw ex;
				}
				logger.warn("Jev judgement could not be made on attempt {}, returning the response unjudged: {}",
						attempt, ex.getMessage());
				return response;
			}

			if (verdict.passed()) {
				logger.info("Jev judgement passed on attempt {}: {}", attempt, verdict.summary());
				return response;
			}

			if (attempt > this.maxRepeatAttempts) {
				if (this.failOnExhaustedAttempts) {
					throw new JevSelfRefineFailedException(this.maxRepeatAttempts, verdict);
				}
				logger.warn("Jev judgement still failing after {} attempts, returning the last response. {}{}{}",
						this.maxRepeatAttempts, verdict.summary(), System.lineSeparator(), verdict.feedback());
				return response;
			}

			logger.warn("Jev judgement failed on attempt {}: {}{}{}", attempt, verdict.summary(),
					System.lineSeparator(), verdict.feedback());

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
	 * Builds the judged input from the original request and the answer it produced. The
	 * original request, not the feedback-augmented one, so the judge never sees its own
	 * previous feedback as part of the question.
	 */
	private JevJudgeInput judgeInput(ChatClientRequest chatClientRequest, ChatClientResponse response) {
		return JevJudgeInput.builder()
			.question(getPromptQuestion(chatClientRequest))
			.answer(getAssistantAnswer(response))
			.toolCalls(getToolCalls(chatClientRequest))
			.build();
	}

	/**
	 * Flattens the system message and the conversation into the text handed to the judge
	 * as {@code user_question}. Tool traffic is left out: it goes into {@code tool_calls}.
	 */
	private String getPromptQuestion(ChatClientRequest chatClientRequest) {
		String conversation = chatClientRequest.prompt()
			.getInstructions()
			.stream()
			.filter(message -> message.getMessageType() == MessageType.USER
					|| message.getMessageType() == MessageType.ASSISTANT)
			.filter(message -> StringUtils.hasText(message.getText()))
			.map(message -> message.getMessageType() + ":" + message.getText())
			.collect(Collectors.joining(System.lineSeparator()));

		SystemMessage systemMessage = chatClientRequest.prompt().getSystemMessage();
		if (systemMessage == null || !StringUtils.hasText(systemMessage.getText())) {
			return conversation;
		}
		return systemMessage.getMessageType() + ":" + systemMessage.getText() + System.lineSeparator() + conversation;
	}

	/**
	 * Pairs every tool call an assistant message made with the result its
	 * {@link ToolResponseMessage} carried. A {@link ToolResponseMessage} carries no text at
	 * all — its content is the tool results, which are exactly the evidence a groundedness
	 * criterion has to be judged against — so those are unpacked rather than dropped.
	 *
	 * <p>
	 * Pairing is positional, not a map lookup: some providers leave the id blank and others
	 * reuse it across turns, and keying by id would collapse such calls into one and hand
	 * it the wrong result. Each response goes to the earliest still-open call with the same
	 * id, or with the same tool name when the id is blank. A response that matches no call
	 * is kept with its tool name.
	 */
	private static List<JevJudgeInput.ToolCall> getToolCalls(ChatClientRequest chatClientRequest) {
		List<PendingToolCall> pending = new ArrayList<>();
		List<JevJudgeInput.ToolCall> unmatched = new ArrayList<>();
		for (Message message : chatClientRequest.prompt().getInstructions()) {
			if (message instanceof AssistantMessage assistantMessage) {
				assistantMessage.getToolCalls().forEach(call -> pending.add(new PendingToolCall(call)));
			}
			else if (message instanceof ToolResponseMessage toolResponseMessage) {
				for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
					PendingToolCall match = pending.stream()
						.filter(call -> call.result == null && call.answeredBy(toolResponse))
						.findFirst()
						.orElse(null);
					if (match != null) {
						match.result = toolResponse.responseData();
					}
					else {
						unmatched.add(new JevJudgeInput.ToolCall(toolResponse.name(), null, toolResponse.responseData()));
					}
				}
			}
		}

		List<JevJudgeInput.ToolCall> toolCalls = new ArrayList<>(pending.size() + unmatched.size());
		pending.forEach(call -> toolCalls
			.add(new JevJudgeInput.ToolCall(call.call.name(), call.call.arguments(), call.result)));
		toolCalls.addAll(unmatched);
		return toolCalls;
	}

	/**
	 * A tool call waiting for its result while the prompt is walked.
	 */
	private static final class PendingToolCall {

		private final AssistantMessage.ToolCall call;

		private @Nullable String result;

		private PendingToolCall(AssistantMessage.ToolCall call) {
			this.call = call;
		}

		private boolean answeredBy(ToolResponseMessage.ToolResponse response) {
			if (StringUtils.hasText(this.call.id())) {
				return this.call.id().equals(response.id());
			}
			return !StringUtils.hasText(response.id()) && this.call.name().equals(response.name());
		}

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

		private JudgeErrorPolicy judgeErrorPolicy = JudgeErrorPolicy.FAIL_OPEN;

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

		/**
		 * What to do when the judging call itself fails. {@link JudgeErrorPolicy#FAIL_OPEN}
		 * by default, matching the advisor's best-effort stance: a TypeSafe outage says
		 * nothing about the answer, so it should not fail the chat call.
		 * @param judgeErrorPolicy the policy
		 * @return this builder
		 */
		public Builder judgeErrorPolicy(JudgeErrorPolicy judgeErrorPolicy) {
			Assert.notNull(judgeErrorPolicy, "judgeErrorPolicy must not be null");
			this.judgeErrorPolicy = judgeErrorPolicy;
			return this;
		}

		public JevSelfRefineAdvisor build() {
			Assert.notNull(this.judge, "judge must be set");
			return new JevSelfRefineAdvisor(this.judge, this.advisorOrder, this.maxRepeatAttempts,
					this.failOnExhaustedAttempts, this.skipEvaluationPredicate, this.judgeErrorPolicy);
		}

	}

}
