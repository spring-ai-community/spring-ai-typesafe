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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.JsonContent;

import org.springframework.util.Assert;

/**
 * What a {@link JevJudge} judges: the question, the answer, and whatever evidence the
 * criteria need, under field names that are the same wherever the judge is used.
 *
 * <p>
 * The input is backed by the very map that becomes the judged state, so the typed view a
 * {@link JevCriterion.CodeCriterion} reads and the state Jev answers against cannot drift
 * apart. Write question instructions against the field names below —
 * {@code `tool_calls`}, {@code `expected_output`} — and code checks against the typed
 * readers.
 *
 * <pre>{@code
 * JevJudgeInput input = JevJudgeInput.builder()
 *     .question("Will I need an umbrella in Dublin tomorrow?")
 *     .answer("No rain is expected.")
 *     .expected("Rain is likely; bring an umbrella.")
 *     .field("search_required", true)
 *     .toolCall(new JevJudgeInput.ToolCall("web_search", "{\"q\":\"Dublin weather\"}", "Rain, 12C"))
 *     .build();
 * }</pre>
 *
 * Empty context and tool-call lists are left out of the state rather than sent as empty
 * arrays, so a criterion can tell "no evidence" from "evidence that says nothing".
 *
 * @author Christian Tzolov
 */
public final class JevJudgeInput {

	/** The state field carrying what was asked. */
	public static final String QUESTION_FIELD = "user_question";

	/** The state field carrying what the model replied. */
	public static final String ANSWER_FIELD = "assistant_answer";

	/** The state field carrying the reference output to judge against. */
	public static final String EXPECTED_FIELD = "expected_output";

	/** The state field carrying the supporting documents, one entry per document. */
	public static final String CONTEXT_FIELD = "supporting_context";

	/** The state field carrying the tool calls made while producing the answer. */
	public static final String TOOL_CALLS_FIELD = "tool_calls";

	/**
	 * One tool call and what it returned.
	 *
	 * @param name the tool name
	 * @param arguments the arguments the model passed, as sent; {@code null} when unknown
	 * @param result what the tool returned; {@code null} when it has not returned
	 */
	public record ToolCall(String name, @Nullable String arguments, @Nullable String result) {

		public ToolCall {
			Assert.hasText(name, "name must not be empty");
		}

	}

	private final Map<String, Object> fields;

	private JevJudgeInput(Map<String, Object> fields) {
		this.fields = Collections.unmodifiableMap(fields);
	}

	/**
	 * Wraps a state built elsewhere, so code criteria can read it with
	 * {@link #field(String, Class)}.
	 * @param state the state, which must be a JSON object
	 * @return the input
	 * @throws IllegalArgumentException when the state is not an object
	 */
	@SuppressWarnings("unchecked")
	public static JevJudgeInput of(JsonContent state) {
		Assert.notNull(state, "state must not be null");
		Assert.isTrue(state.value() instanceof Map,
				"the judged state must be a JSON object to be read as a JevJudgeInput");
		return new JevJudgeInput(new LinkedHashMap<>((Map<String, Object>) state.value()));
	}

	/**
	 * @return the question, or {@code null} when the input has none
	 */
	public @Nullable String question() {
		return field(QUESTION_FIELD, String.class);
	}

	/**
	 * @return the answer, or {@code null} when the input has none
	 */
	public @Nullable String answer() {
		return field(ANSWER_FIELD, String.class);
	}

	/**
	 * @return the reference output, or {@code null} when the input has none
	 */
	public @Nullable Object expected() {
		return this.fields.get(EXPECTED_FIELD);
	}

	/**
	 * @return the supporting documents, empty when there are none
	 */
	public List<String> context() {
		Object value = this.fields.get(CONTEXT_FIELD);
		if (value == null) {
			return List.of();
		}
		if (value instanceof String text) {
			return List.of(text);
		}
		if (value instanceof List<?> list) {
			return list.stream().map(String::valueOf).toList();
		}
		throw mismatch(CONTEXT_FIELD, "a string or a list of strings", value);
	}

	/**
	 * Reads the tool calls. Elements of a state built elsewhere are accepted as
	 * {@link ToolCall}s, as objects with {@code name}, {@code arguments} and
	 * {@code result} keys, or as bare tool names.
	 * @return the tool calls, empty when there are none
	 */
	public List<ToolCall> toolCalls() {
		Object value = this.fields.get(TOOL_CALLS_FIELD);
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> list)) {
			throw mismatch(TOOL_CALLS_FIELD, "a list", value);
		}
		List<ToolCall> toolCalls = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			Object element = list.get(i);
			if (element instanceof ToolCall toolCall) {
				toolCalls.add(toolCall);
			}
			else if (element instanceof String name) {
				toolCalls.add(new ToolCall(name, null, null));
			}
			else if (element instanceof Map<?, ?> map && map.get("name") != null) {
				toolCalls.add(new ToolCall(String.valueOf(map.get("name")), stringOrNull(map.get("arguments")),
						stringOrNull(map.get("result"))));
			}
			else {
				throw mismatch(TOOL_CALLS_FIELD + "[" + i + "]", "a tool call, an object with a name, or a tool name",
						element);
			}
		}
		return List.copyOf(toolCalls);
	}

	/**
	 * Reads any field of the state.
	 * @param name the field name
	 * @param type the type the value must have
	 * @param <T> the value type
	 * @return the value, or {@code null} when the field is absent
	 * @throws IllegalStateException when the value is present but of another type
	 */
	public <T> @Nullable T field(String name, Class<T> type) {
		Assert.hasText(name, "name must not be empty");
		Assert.notNull(type, "type must not be null");
		Object value = this.fields.get(name);
		if (value == null) {
			return null;
		}
		if (!type.isInstance(value)) {
			throw mismatch(name, type.getSimpleName(), value);
		}
		return type.cast(value);
	}

	/**
	 * @return every field, in insertion order
	 */
	public Map<String, Object> fields() {
		return this.fields;
	}

	/**
	 * @return the state handed to Jev
	 */
	public JsonContent toState() {
		return JsonContent.of(this.fields);
	}

	@Override
	public String toString() {
		return "JevJudgeInput" + this.fields;
	}

	private static @Nullable String stringOrNull(@Nullable Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private static IllegalStateException mismatch(String name, String expected, Object value) {
		return new IllegalStateException(
				"field '" + name + "' was expected to be " + expected + " but was " + value.getClass().getSimpleName());
	}

	/**
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link JevJudgeInput}.
	 */
	public static final class Builder {

		private final Map<String, Object> fields = new LinkedHashMap<>();

		private final List<String> context = new ArrayList<>();

		private final List<ToolCall> toolCalls = new ArrayList<>();

		private Builder() {
		}

		/**
		 * @param question what was asked, including any system instructions and history
		 * @return this builder
		 */
		public Builder question(@Nullable String question) {
			return put(QUESTION_FIELD, question);
		}

		/**
		 * @param answer what the model replied
		 * @return this builder
		 */
		public Builder answer(@Nullable String answer) {
			return put(ANSWER_FIELD, answer);
		}

		/**
		 * @param expected the reference output to judge against: a string, or any value
		 * that serialises to JSON
		 * @return this builder
		 */
		public Builder expected(@Nullable Object expected) {
			return put(EXPECTED_FIELD, expected);
		}

		/**
		 * @param document one supporting document
		 * @return this builder
		 */
		public Builder context(String document) {
			Assert.notNull(document, "document must not be null");
			this.context.add(document);
			return this;
		}

		/**
		 * @param documents supporting documents, one entry each
		 * @return this builder
		 */
		public Builder context(List<String> documents) {
			Assert.notNull(documents, "documents must not be null");
			Assert.noNullElements(documents, "documents must not contain null");
			this.context.addAll(documents);
			return this;
		}

		/**
		 * @param toolCall one tool call
		 * @return this builder
		 */
		public Builder toolCall(ToolCall toolCall) {
			Assert.notNull(toolCall, "toolCall must not be null");
			this.toolCalls.add(toolCall);
			return this;
		}

		/**
		 * @param toolCalls tool calls, in the order they were made
		 * @return this builder
		 */
		public Builder toolCalls(List<ToolCall> toolCalls) {
			Assert.notNull(toolCalls, "toolCalls must not be null");
			Assert.noNullElements(toolCalls, "toolCalls must not contain null");
			this.toolCalls.addAll(toolCalls);
			return this;
		}

		/**
		 * Adds a field of your own, for evidence the standard fields do not cover.
		 * @param name the field name
		 * @param value the value; {@code null} leaves the field out
		 * @return this builder
		 */
		public Builder field(String name, @Nullable Object value) {
			Assert.hasText(name, "name must not be empty");
			Assert.isTrue(!CONTEXT_FIELD.equals(name) && !TOOL_CALLS_FIELD.equals(name),
					"use context(...) or toolCalls(...) for '" + name + "'");
			return put(name, value);
		}

		private Builder put(String name, @Nullable Object value) {
			if (value == null) {
				this.fields.remove(name);
			}
			else {
				this.fields.put(name, value);
			}
			return this;
		}

		public JevJudgeInput build() {
			Map<String, Object> state = new LinkedHashMap<>(this.fields);
			if (!this.context.isEmpty()) {
				state.put(CONTEXT_FIELD, List.copyOf(this.context));
			}
			if (!this.toolCalls.isEmpty()) {
				state.put(TOOL_CALLS_FIELD, List.copyOf(this.toolCalls));
			}
			return new JevJudgeInput(state);
		}

	}

}
