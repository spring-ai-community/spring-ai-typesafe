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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.ai.content.MediaContent;
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
 * Triage t = ChatClient.create(triage)
 *     .prompt()
 *     .user(ticketText)
 *     .call()
 *     .entity(Triage.class, spec -> spec.useProviderStructuredOutput());
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
 * the answer cannot fill, fails with a message naming the field, and the reply then holds
 * exactly the fields the record declares. Without it, the format instructions Spring AI
 * appends to the last user message are recognised and removed; only JSON-object output is
 * supported, so list output ({@code ListOutputConverter}) is rejected.
 *
 * <p>
 * What a chat model normally does and this one cannot is refused rather than faked:
 * streaming is unsupported; {@link #getOptions()} are not tool-calling options, so
 * {@code ChatClient} never runs its tool loop and drops any tools it was given; and a
 * prompt that carries tool callbacks directly is rejected. A message carrying media is
 * rejected too: Jev classifies text.
 *
 * <p>
 * Do not declare a {@code JevChatModel} as a Spring bean next to the application's real
 * chat model: with two {@code ChatModel} beans, injecting Spring AI's
 * {@code ChatClient.Builder} or a bare {@code ChatModel} fails with
 * {@code NoUniqueBeanDefinitionException}. Build it where its {@code ChatClient} is
 * created, and expose that client instead. Memory or retrieval advisors
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

	/**
	 * A phrase every JSON format block Spring AI appends contains ({@code BeanOutputConverter},
	 * {@code MapOutputConverter}). A suffix is only removed when it starts with
	 * {@link #FORMAT_INSTRUCTIONS_START} on its own line and contains this, so a user
	 * message that merely quotes the opening sentence is left alone.
	 */
	static final String FORMAT_INSTRUCTIONS_MARKER = "RFC8259 compliant JSON response";

	/** How {@code ListOutputConverter}'s instructions start; a JSON reply cannot satisfy them. */
	static final String LIST_FORMAT_INSTRUCTIONS_START = "Respond with only a list of comma-separated values";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

	private final TypeSafeClient typeSafeClient;

	private final Map<String, Question> questions;

	private final Function<Prompt, JsonContent> stateConverter;

	private final Function<SystemOneResponse, String> answerRenderer;

	private final ObservationRegistry observationRegistry;

	private final @Nullable ChatModelObservationConvention observationConvention;

	/** Whether the reply is the default JSON object, whose fields a schema can be checked against. */
	private final boolean defaultRenderer;

	private JevChatModel(TypeSafeClient typeSafeClient, Map<String, Question> questions,
			Function<Prompt, JsonContent> stateConverter, Function<SystemOneResponse, String> answerRenderer,
			boolean defaultRenderer, ObservationRegistry observationRegistry,
			@Nullable ChatModelObservationConvention observationConvention) {
		this.typeSafeClient = typeSafeClient;
		this.questions = questions;
		this.stateConverter = stateConverter;
		this.answerRenderer = answerRenderer;
		this.defaultRenderer = defaultRenderer;
		this.observationRegistry = observationRegistry;
		this.observationConvention = observationConvention;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		Assert.notNull(prompt, "prompt must not be null");
		rejectTools(prompt.getOptions());
		rejectListOutput(prompt);
		Set<String> requestedFields = requestedFields(prompt.getOptions());
		// The model actually used goes into the observed prompt too, so a direct call with
		// no options still reports it as the request model.
		Prompt requested = withModel(prompt);

		ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(requested)
			.provider(PROVIDER)
			.build();
		ChatResponse chatResponse = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				ChatResponse response = classify(requested, requestedFields);
				observationContext.setResponse(response);
				return response;
			});
		Assert.state(chatResponse != null, "the observed call returned no response");
		return chatResponse;
	}

	private ChatResponse classify(Prompt prompt, @Nullable Set<String> requestedFields) {
		SystemOneResponse response = this.typeSafeClient.systemOne(SystemOneRequest.builder()
			.state(this.stateConverter.apply(prompt))
			.model(modelOf(prompt.getOptions()))
			.questions(this.questions)
			.build());

		ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
			.finishReason("STOP")
			.metadata(RESPONSE_METADATA_KEY, response)
			.build();
		// With a schema, reply with exactly the fields it asks for: a schema that forbids
		// additional properties would otherwise reject the answers the record does not use.
		String reply = (this.defaultRenderer && requestedFields != null) ? answersAsJson(response, requestedFields)
				: this.answerRenderer.apply(response);
		Generation generation = new Generation(new AssistantMessage(reply), generationMetadata);

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
	/**
	 * Reads the requested type's schema when it arrived natively, checks it against the
	 * questions and returns its field names; {@code null} without a schema, or when a custom
	 * {@code answerRenderer} owns the reply's shape. Every field needs a question of the same
	 * name whose answer fits its type: a choice's label into a string (and into every value of
	 * an enum), a noul's or score's value into a floating-point number. Local {@code $ref}s and
	 * {@code anyOf}/{@code oneOf} alternatives are followed; a field whose schema declares no
	 * type is accepted unchecked. Questions the type does not ask for are left out of the reply.
	 */
	private @Nullable Set<String> requestedFields(@Nullable ChatOptions options) {
		if (!this.defaultRenderer || !(options instanceof StructuredOutputChatOptions structured)
				|| !StringUtils.hasText(structured.getOutputSchema())) {
			return null;
		}
		JsonNode root = JSON.readTree(structured.getOutputSchema());
		Set<String> rootTypes = typesOf(alternatives(root, root, 0));
		if (!rootTypes.isEmpty() && !rootTypes.contains("object")) {
			throw new IllegalArgumentException("JevChatModel replies with a JSON object, one field per question, "
					+ "so it cannot produce a " + rootTypes + "; request a record or a class instead");
		}
		Set<String> fields = new LinkedHashSet<>();
		for (Map.Entry<String, JsonNode> property : root.path("properties").properties()) {
			String field = property.getKey();
			Question question = this.questions.get(field);
			if (question == null) {
				throw new IllegalArgumentException("The requested type has a field '" + field
						+ "' that no question answers; the questions are " + this.questions.keySet());
			}
			List<JsonNode> alternatives = alternatives(property.getValue(), root, 0);
			if (question instanceof Choice choice) {
				requireType(field, alternatives, "string", "a choice's label");
				List<String> values = enumValuesOf(alternatives);
				if (values != null) {
					choice.criteria().keySet().forEach(option -> {
						if (!values.contains(option)) {
							throw new IllegalArgumentException("Field '" + field + "' cannot hold the choice option '"
									+ option + "'; its values are " + values);
						}
					});
				}
			}
			else {
				requireType(field, alternatives, "number", "a floating-point number");
			}
			fields.add(field);
		}
		return fields;
	}

	/**
	 * Flattens a schema into the alternatives a value may match: follows a local
	 * {@code $ref} and expands {@code anyOf} and {@code oneOf}.
	 */
	private static List<JsonNode> alternatives(JsonNode schema, JsonNode root, int depth) {
		if (depth > 16) {
			return List.of(schema);
		}
		JsonNode ref = schema.path("$ref");
		if (ref.isString() && ref.asString().startsWith("#")) {
			JsonNode target = root.at(ref.asString().substring(1));
			return target.isMissingNode() ? List.of(schema) : alternatives(target, root, depth + 1);
		}
		List<JsonNode> flattened = new ArrayList<>();
		for (String keyword : List.of("anyOf", "oneOf")) {
			JsonNode options = schema.path(keyword);
			if (options.isArray()) {
				options.forEach(option -> flattened.addAll(alternatives(option, root, depth + 1)));
			}
		}
		if (flattened.isEmpty()) {
			flattened.add(schema);
		}
		return flattened;
	}

	/** The declared types across the alternatives, without {@code null}. */
	private static Set<String> typesOf(List<JsonNode> alternatives) {
		Set<String> types = new LinkedHashSet<>();
		for (JsonNode alternative : alternatives) {
			JsonNode type = alternative.path("type");
			if (type.isArray()) {
				type.forEach(value -> types.add(value.asString()));
			}
			else if (type.isString()) {
				types.add(type.asString());
			}
		}
		types.remove("null");
		return types;
	}

	/**
	 * The enum values across the alternatives, or {@code null} when any non-null
	 * alternative accepts any value.
	 */
	private static @Nullable List<String> enumValuesOf(List<JsonNode> alternatives) {
		List<String> values = new ArrayList<>();
		for (JsonNode alternative : alternatives) {
			JsonNode allowed = alternative.path("enum");
			if (allowed.isArray()) {
				allowed.forEach(value -> values.add(value.asString()));
			}
			else if (!"null".equals(alternative.path("type").asString(""))) {
				return null;
			}
		}
		return values;
	}

	/**
	 * Requires the field's declared types to include {@code expected}. An {@code integer}
	 * or {@code boolean} field is rejected for a noul or score: a value such as 0.97 or 1.8
	 * would not fit.
	 */
	private static void requireType(String field, List<JsonNode> alternatives, String expected, String answer) {
		Set<String> types = typesOf(alternatives);
		if (!types.isEmpty() && !types.contains(expected)) {
			throw new IllegalArgumentException("Field '" + field + "' is of type " + types + " but receives " + answer
					+ ", which needs " + expected);
		}
	}

	/**
	 * {@code ListOutputConverter} asks for comma-separated values; the reply is a JSON
	 * object, which it would split into nonsense.
	 */
	private static void rejectListOutput(Prompt prompt) {
		String last = lastUserText(prompt);
		if (last != null && last.contains("\n" + LIST_FORMAT_INSTRUCTIONS_START)) {
			throw new IllegalArgumentException("JevChatModel replies with a JSON object, one field per question; "
					+ "list output (ListOutputConverter) is not supported, request a record or a class instead");
		}
	}

	private Prompt withModel(Prompt prompt) {
		ChatOptions options = prompt.getOptions();
		if (options != null && StringUtils.hasText(options.getModel())) {
			return prompt;
		}
		ChatOptions withModel = options == null ? getOptions()
				: options.mutate().model(this.typeSafeClient.defaultModel()).build();
		return prompt.mutate().chatOptions(withModel).build();
	}

	private static @Nullable String lastUserText(Prompt prompt) {
		List<Message> instructions = prompt.getInstructions();
		for (int i = instructions.size() - 1; i >= 0; i--) {
			if (instructions.get(i).getMessageType() == MessageType.USER) {
				return instructions.get(i).getText();
			}
		}
		return null;
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
		List<Message> instructions = prompt.getInstructions();
		int lastUser = -1;
		for (int i = 0; i < instructions.size(); i++) {
			if (instructions.get(i).getMessageType() == MessageType.USER) {
				lastUser = i;
			}
		}
		Map<String, Object> state = new LinkedHashMap<>();
		List<String> system = new ArrayList<>();
		List<Map<String, String>> messages = new ArrayList<>();
		for (int i = 0; i < instructions.size(); i++) {
			Message message = instructions.get(i);
			if (message instanceof MediaContent media && !media.getMedia().isEmpty()) {
				throw new IllegalArgumentException(
						"JevChatModel classifies text; a " + message.getMessageType().getValue() + " message carries media");
			}
			String text = message.getText();
			if (!StringUtils.hasText(text)) {
				continue;
			}
			if (message.getMessageType() == MessageType.SYSTEM) {
				system.add(text);
			}
			else if (message.getMessageType() == MessageType.USER
					|| message.getMessageType() == MessageType.ASSISTANT) {
				// Format instructions are only ever appended to the latest user message.
				String content = (i == lastUser) ? withoutFormatInstructions(text) : text;
				messages.add(Map.of("role", message.getMessageType().getValue(), "content", content));
			}
		}
		if (!system.isEmpty()) {
			state.put(SYSTEM_FIELD, String.join("\n\n", system));
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
		return answersAsJson(response, null);
	}

	/**
	 * The answers as a JSON object, limited to {@code fields} when given.
	 * @param response the response
	 * @param fields the fields to include, in their order; {@code null} for every answer
	 * @return the JSON text
	 */
	public static String answersAsJson(SystemOneResponse response, @Nullable Set<String> fields) {
		Map<String, @Nullable Object> answers = new LinkedHashMap<>();
		if (fields == null) {
			response.answers().forEach((name, answer) -> answers.put(name, valueOf(answer)));
		}
		else {
			fields.forEach(field -> {
				Answer answer = response.answers().get(field);
				answers.put(field, answer == null ? null : valueOf(answer));
			});
		}
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

	/**
	 * Removes the JSON format instructions {@code ChatClient.entity(...)} appends to a user
	 * message on the prompt-based path: a suffix that starts with
	 * {@value #FORMAT_INSTRUCTIONS_START} on its own line and contains
	 * {@value #FORMAT_INSTRUCTIONS_MARKER}. Anything else, including a message that merely
	 * quotes the opening sentence, is returned unchanged. The default state applies it to
	 * the last user message; a custom {@code stateConverter} can call it too.
	 * @param text a user message's text
	 * @return the text without the appended instructions
	 */
	public static String withoutFormatInstructions(String text) {
		int start = text.lastIndexOf(FORMAT_INSTRUCTIONS_START);
		if (start < 0 || (start > 0 && text.charAt(start - 1) != '\n')
				|| text.indexOf(FORMAT_INSTRUCTIONS_MARKER, start) < 0) {
			return text;
		}
		return text.substring(0, start).stripTrailing();
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

		private boolean defaultRenderer = true;

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
			this.defaultRenderer = false;
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
					this.answerRenderer, this.defaultRenderer, this.observationRegistry, this.observationConvention);
		}

	}

}
