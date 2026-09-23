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

package org.springaicommunity.typesafe.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * <strong>Experimental.</strong> A Jev classifier behind Spring AI's {@link ChatModel}
 * interface, so {@code ChatClient} can return typed classifications:
 *
 * <pre>{@code
 * record Triage(String team, double urgent, double severity) {}
 *
 * ChatModel triage = JevChatModel.builder(typeSafeClient)
 *     .question("team", Choice.of("Which team should handle this?", "infra", "billing", "support"))
 *     .question("urgent", Noul.of("Does this need attention right now?"))
 *     .question("severity", Score.of("How severe is the impact?", "Cosmetic", "Degraded", "Outage"))
 *     .build();
 *
 * Triage t = ChatClient.create(triage).prompt().user(ticketText).call().entity(Triage.class);
 * }</pre>
 *
 * <p>
 * This is a classifier, not a generator. The questions are fixed when the model is built;
 * the prompt only supplies the state they are answered against. The reply is the answers
 * as a JSON object, one field per question: a noul's truth value, a choice's label, a
 * score's value. That is the shape {@code entity(...)} maps onto a record. The full
 * {@link SystemOneResponse}, with probabilities and confidence, rides in the generation
 * metadata under {@link #RESPONSE_METADATA_KEY}.
 *
 * <p>
 * Prefer {@code ChatClient}'s native structured output,
 * {@code .entity(Triage.class, spec -> spec.useProviderStructuredOutput())}. The record's
 * JSON schema then arrives in the options rather than as instructions appended to the user
 * message, so the state stays exactly what the caller wrote. The schema is also checked
 * against the questions before any call: a record field with no question, or with a type
 * the answer cannot fill, fails with a message naming the field. Without it, the
 * prompt-based format instructions are recognised by their opening sentence and removed.
 *
 * <p>
 * What a chat model normally does and this one cannot is refused rather than faked:
 * streaming is unsupported; {@link #getOptions()} are not tool-calling options, so
 * {@code ChatClient} never runs its tool loop and drops any tools it was given; and a
 * prompt that carries tool callbacks directly is rejected. Memory or retrieval advisors
 * do work, but only as a way to put more text into the state.
 *
 * <p>
 * Each call is observed like any other Spring AI chat model: a
 * {@code gen_ai.client.operation} observation with provider {@value #PROVIDER}, the
 * requested and returned model, the response id and the token usage, so the standard
 * {@code ChatModelMeterObservationHandler} records {@code gen_ai.client.token.usage}. Pass
 * an {@link ObservationRegistry} to the builder to enable it; the SDK's retries happen
 * inside the one observation.
 *
 * @author Christian Tzolov
 */
public final class JevChatModel implements ChatModel {

	/** The provider name reported on observations; Spring AI has no constant for it. */
	public static final String PROVIDER = "typesafe";

	/** Generation metadata key holding the full {@link SystemOneResponse}. */
	public static final String RESPONSE_METADATA_KEY = "jevResponse";

	/** State field holding the system message, when there is one. */
	public static final String SYSTEM_FIELD = "system";

	/** State field holding the conversation, as {@code {role, content}} entries. */
	public static final String MESSAGES_FIELD = "messages";

	/**
	 * How {@code BeanOutputConverter} starts the format instructions it appends to the
	 * user message when {@code ChatClient.entity(...)} is used. They tell a generator how
	 * to reply; Jev's reply shape is fixed, so the default state converter drops them.
	 */
	static final String FORMAT_INSTRUCTIONS_START = "Your response should be in JSON format.";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private final TypeSafeClient typeSafeClient;

	private final Map<String, Question> questions;

	private final Function<Prompt, JsonContent> stateConverter;

	private final Function<SystemOneResponse, String> answerRenderer;

	private final ObservationRegistry observationRegistry;

	private final @Nullable ChatModelObservationConvention observationConvention;

	private JevChatModel(TypeSafeClient typeSafeClient, Map<String, Question> questions,
			Function<Prompt, JsonContent> stateConverter, Function<SystemOneResponse, String> answerRenderer,
			ObservationRegistry observationRegistry, @Nullable ChatModelObservationConvention observationConvention) {
		this.typeSafeClient = typeSafeClient;
		this.questions = questions;
		this.stateConverter = stateConverter;
		this.answerRenderer = answerRenderer;
		this.observationRegistry = observationRegistry;
		this.observationConvention = observationConvention;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		Assert.notNull(prompt, "prompt must not be null");
		rejectTools(prompt.getOptions());
		checkOutputSchema(prompt.getOptions());

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt)
			.provider(PROVIDER)
			.build();
		ChatResponse chatResponse = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				ChatResponse response = classify(prompt);
				observationContext.setResponse(response);
				return response;
			});
		Assert.state(chatResponse != null, "the observed call returned no response");
		return chatResponse;
	}

	private ChatResponse classify(Prompt prompt) {
		SystemOneResponse response = this.typeSafeClient.systemOne(SystemOneRequest.builder()
			.state(this.stateConverter.apply(prompt))
			.model(modelOf(prompt.getOptions()))
			.questions(this.questions)
			.build());

		ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
			.finishReason("STOP")
			.metadata(RESPONSE_METADATA_KEY, response)
			.build();
		Generation generation = new Generation(new AssistantMessage(this.answerRenderer.apply(response)),
				generationMetadata);

		ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
			.model(response.model())
			.usage(new DefaultUsage(countOf(response.usage().inputTokens()), countOf(response.usage().outputTokens())));
		if (response.requestId() != null) {
			metadata.id(response.requestId());
		}
		return new ChatResponse(List.of(generation), metadata.build());
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.error(new UnsupportedOperationException(
				"JevChatModel does not stream: Jev answers every question at once, as numbers, not as generated text"));
	}

	/**
	 * Structured-output options, so {@code ChatClient} can hand over the record's schema
	 * natively; deliberately not {@code ToolCallingChatOptions}: Jev cannot call tools, so
	 * {@code ChatClient} must not start its tool loop.
	 */
	@Override
	public ChatOptions getOptions() {
		return StructuredOutputChatOptions.builder().model(this.typeSafeClient.defaultModel()).build();
	}

	/**
	 * @return the questions every call answers, in declaration order
	 */
	public Map<String, Question> questions() {
		return this.questions;
	}

	private String modelOf(@Nullable ChatOptions options) {
		return options != null && StringUtils.hasText(options.getModel()) ? options.getModel()
				: this.typeSafeClient.defaultModel();
	}

	/**
	 * Checks the schema of the requested record against the questions, when the schema
	 * arrived natively. Every field needs a question of the same name whose answer fits
	 * its type: a choice's label into a string (and into every value of an enum), a noul's
	 * or score's value into a number. Questions the record does not ask for are fine.
	 */
	private void checkOutputSchema(@Nullable ChatOptions options) {
		if (!(options instanceof StructuredOutputChatOptions structured)
				|| !StringUtils.hasText(structured.getOutputSchema())) {
			return;
		}
		JsonNode properties = JSON.readTree(structured.getOutputSchema()).path("properties");
		for (Map.Entry<String, JsonNode> property : properties.properties()) {
			String field = property.getKey();
			Question question = this.questions.get(field);
			if (question == null) {
				throw new IllegalArgumentException("The requested type has a field '" + field
						+ "' that no question answers; the questions are " + this.questions.keySet());
			}
			JsonNode schema = property.getValue();
			if (question instanceof Choice choice) {
				requireType(field, schema, "string", "a choice's label");
				JsonNode allowed = schema.path("enum");
				if (allowed.isArray()) {
					List<String> values = new ArrayList<>();
					allowed.forEach(value -> values.add(value.asString()));
					choice.criteria().keySet().forEach(option -> {
						if (!values.contains(option)) {
							throw new IllegalArgumentException("Field '" + field + "' cannot hold the choice option '"
									+ option + "'; its values are " + values);
						}
					});
				}
			}
			else {
				requireType(field, schema, "number", "a number");
			}
		}
	}

	/**
	 * Requires the field's schema type to include {@code expected}. An {@code integer}
	 * field is rejected for a noul or score: a value such as 0.97 or 1.8 would not fit.
	 */
	private static void requireType(String field, JsonNode schema, String expected, String answer) {
		List<String> types = new ArrayList<>();
		JsonNode type = schema.path("type");
		if (type.isArray()) {
			type.forEach(value -> types.add(value.asString()));
		}
		else if (type.isString()) {
			types.add(type.asString());
		}
		if (!types.isEmpty() && !types.contains(expected)) {
			throw new IllegalArgumentException("Field '" + field + "' is of type " + types + " but receives " + answer
					+ ", which needs " + expected);
		}
	}

	private static void rejectTools(@Nullable ChatOptions options) {
		if (options instanceof ToolCallingChatOptions toolOptions && toolOptions.getToolCallbacks() != null
				&& !toolOptions.getToolCallbacks().isEmpty()) {
			throw new IllegalArgumentException("JevChatModel cannot call tools; it classifies, it does not generate");
		}
	}

	private static int countOf(@Nullable Integer count) {
		return count == null ? 0 : count;
	}

	/**
	 * The default state: {@code {"system": ..., "messages": [{"role", "content"}, ...]}},
	 * with the system message left out when there is none, and with the format
	 * instructions {@code ChatClient.entity(...)} appends to the user message removed.
	 * @param prompt the prompt
	 * @return the state
	 */
	public static JsonContent defaultState(Prompt prompt) {
		Map<String, Object> state = new LinkedHashMap<>();
		List<Map<String, String>> messages = new ArrayList<>();
		for (Message message : prompt.getInstructions()) {
			String text = message.getText();
			if (!StringUtils.hasText(text)) {
				continue;
			}
			if (message.getMessageType() == MessageType.SYSTEM) {
				state.put(SYSTEM_FIELD, text);
			}
			else if (message.getMessageType() == MessageType.USER
					|| message.getMessageType() == MessageType.ASSISTANT) {
				String content = message.getMessageType() == MessageType.USER ? withoutFormatInstructions(text) : text;
				messages.add(Map.of("role", message.getMessageType().getValue(), "content", content));
			}
		}
		state.put(MESSAGES_FIELD, messages);
		return JsonContent.of(state);
	}

	/**
	 * The default reply: the answers as a JSON object, one field per question — a noul's
	 * truth value, a choice's label, a score's value. An answer of a kind this SDK does
	 * not understand is {@code null}.
	 * @param response the response
	 * @return the JSON text
	 */
	public static String answersAsJson(SystemOneResponse response) {
		Map<String, @Nullable Object> answers = new LinkedHashMap<>();
		response.answers().forEach((name, answer) -> answers.put(name, valueOf(answer)));
		return JSON.writeValueAsString(answers);
	}

	private static @Nullable Object valueOf(Answer answer) {
		if (answer instanceof NoulAnswer noul) {
			return noul.value();
		}
		if (answer instanceof ChoiceAnswer choice) {
			return choice.value();
		}
		if (answer instanceof ScoreAnswer score) {
			return score.value();
		}
		return null;
	}

	static String withoutFormatInstructions(String text) {
		int start = text.indexOf(FORMAT_INSTRUCTIONS_START);
		return start < 0 ? text : text.substring(0, start).stripTrailing();
	}

	/**
	 * @param typeSafeClient the client used to ask the questions
	 * @return a new builder
	 */
	public static Builder builder(TypeSafeClient typeSafeClient) {
		return new Builder(typeSafeClient);
	}

	/**
	 * Builder for {@link JevChatModel}.
	 */
	public static final class Builder {

		private final TypeSafeClient typeSafeClient;

		private final Map<String, Question> questions = new LinkedHashMap<>();

		private Function<Prompt, JsonContent> stateConverter = JevChatModel::defaultState;

		private Function<SystemOneResponse, String> answerRenderer = JevChatModel::answersAsJson;

		private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

		private @Nullable ChatModelObservationConvention observationConvention;

		private Builder(TypeSafeClient typeSafeClient) {
			Assert.notNull(typeSafeClient, "typeSafeClient must not be null");
			this.typeSafeClient = typeSafeClient;
		}

		/**
		 * Adds a question every call answers. Its name is the field of the reply, so name
		 * it after the record component it should land in.
		 * @param name the answer's name
		 * @param question the question
		 * @return this builder
		 */
		public Builder question(String name, Question question) {
			Assert.hasText(name, "name must not be empty");
			Assert.notNull(question, "question must not be null");
			Assert.isTrue(!this.questions.containsKey(name), "a question named '" + name + "' is already declared");
			this.questions.put(name, question);
			return this;
		}

		/**
		 * @param questions questions every call answers, keyed by name
		 * @return this builder
		 */
		public Builder questions(Map<String, ? extends Question> questions) {
			Assert.notNull(questions, "questions must not be null");
			questions.forEach(this::question);
			return this;
		}

		/**
		 * Replaces how a prompt becomes the state the questions are answered against.
		 * @param stateConverter the converter; {@link JevChatModel#defaultState} by
		 * default
		 * @return this builder
		 */
		public Builder stateConverter(Function<Prompt, JsonContent> stateConverter) {
			Assert.notNull(stateConverter, "stateConverter must not be null");
			this.stateConverter = stateConverter;
			return this;
		}

		/**
		 * Replaces how the answers become the reply's text.
		 * @param answerRenderer the renderer; {@link JevChatModel#answersAsJson} by
		 * default
		 * @return this builder
		 */
		public Builder answerRenderer(Function<SystemOneResponse, String> answerRenderer) {
			Assert.notNull(answerRenderer, "answerRenderer must not be null");
			this.answerRenderer = answerRenderer;
			return this;
		}

		/**
		 * Observes each call as a {@code gen_ai.client.operation}. Not observed by default.
		 * @param observationRegistry the registry
		 * @return this builder
		 */
		public Builder observationRegistry(ObservationRegistry observationRegistry) {
			Assert.notNull(observationRegistry, "observationRegistry must not be null");
			this.observationRegistry = observationRegistry;
			return this;
		}

		/**
		 * Replaces the observation convention, for example to add Jev-specific key values.
		 * @param observationConvention the convention; Spring AI's
		 * {@link DefaultChatModelObservationConvention} by default
		 * @return this builder
		 */
		public Builder observationConvention(ChatModelObservationConvention observationConvention) {
			Assert.notNull(observationConvention, "observationConvention must not be null");
			this.observationConvention = observationConvention;
			return this;
		}

		public JevChatModel build() {
			Assert.notEmpty(this.questions, "a JevChatModel must declare at least one question");
			return new JevChatModel(this.typeSafeClient,
					java.util.Collections.unmodifiableMap(new LinkedHashMap<>(this.questions)), this.stateConverter,
					this.answerRenderer, this.observationRegistry, this.observationConvention);
		}

	}

}
