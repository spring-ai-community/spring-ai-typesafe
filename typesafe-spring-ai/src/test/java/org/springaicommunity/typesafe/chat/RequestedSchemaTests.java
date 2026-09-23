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

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.Score;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The requested type's schema, checked against the questions without a chat call.
 *
 * @author Christian Tzolov
 */
class RequestedSchemaTests {

	private static final Map<String, Question> QUESTIONS = new LinkedHashMap<>();

	static {
		QUESTIONS.put("team", Choice.of("Which team?", "infra", "billing", "support"));
		QUESTIONS.put("urgent", Noul.of("Urgent?"));
		QUESTIONS.put("severity", Score.of("How severe?", "Cosmetic", "Degraded", "Outage"));
	}

	@Test
	void isAbsentWithoutASchemaInTheOptions() {
		assertThat(RequestedSchema.of(null, QUESTIONS)).isNull();
		assertThat(RequestedSchema.of(ChatOptions.builder().build(), QUESTIONS)).isNull();
		assertThat(RequestedSchema.of(StructuredOutputChatOptions.builder().build(), QUESTIONS)).isNull();
	}

	@Test
	void readsTheSchemaFromStructuredOutputOptions() {
		ChatOptions options = StructuredOutputChatOptions.builder()
			.outputSchema(object("\"team\":{\"type\":\"string\"}"))
			.build();

		assertThat(RequestedSchema.of(options, QUESTIONS).fields()).containsExactly("team");
	}

	@Test
	void keepsTheFieldsInTheSchemasOrder() {
		RequestedSchema schema = RequestedSchema.parse(
				object("\"severity\":{\"type\":\"number\"},\"team\":{\"type\":\"string\"}"), QUESTIONS);

		assertThat(schema.fields()).containsExactly("severity", "team");
	}

	@Test
	void rejectsAFieldNoQuestionAnswers() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestedSchema.parse(object("\"priority\":{\"type\":\"string\"}"), QUESTIONS))
			.withMessageContaining("field 'priority' that no question answers")
			.withMessageContaining("[team, urgent, severity]");
	}

	@Test
	void rejectsAChoiceOntoANumberAndANoulOntoAnIntegerOrBoolean() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestedSchema.parse(object("\"team\":{\"type\":\"number\"}"), QUESTIONS))
			.withMessageContaining("a choice's label");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestedSchema.parse(object("\"urgent\":{\"type\":\"integer\"}"), QUESTIONS))
			.withMessageContaining("a floating-point number");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestedSchema.parse(object("\"urgent\":{\"type\":\"boolean\"}"), QUESTIONS))
			.withMessageContaining("a floating-point number");
	}

	@Test
	void acceptsNullableTypesAndUntypedFields() {
		RequestedSchema schema = RequestedSchema.parse(object(
				"\"team\":{\"type\":[\"string\",\"null\"]},\"urgent\":{\"anyOf\":[{\"type\":\"number\"},{\"type\":\"null\"}]},\"severity\":{}"),
				QUESTIONS);

		assertThat(schema.fields()).containsExactly("team", "urgent", "severity");
	}

	@Test
	void followsARefIntoDefsAndChecksItsEnum() {
		String narrow = "{\"$defs\":{\"Team\":{\"type\":\"string\",\"enum\":[\"infra\",\"billing\"]}},"
				+ "\"type\":\"object\",\"properties\":{\"team\":{\"$ref\":\"#/$defs/Team\"}}}";
		assertThatIllegalArgumentException().isThrownBy(() -> RequestedSchema.parse(narrow, QUESTIONS))
			.withMessageContaining("cannot hold the choice option 'support'");

		String full = narrow.replace("\"billing\"]", "\"billing\",\"support\"]");
		assertThat(RequestedSchema.parse(full, QUESTIONS).fields()).containsExactly("team");
	}

	@Test
	void checksEnumValuesAcrossOneOfAlternatives() {
		String split = object(
				"\"team\":{\"oneOf\":[{\"type\":\"string\",\"enum\":[\"infra\"]},{\"type\":\"string\",\"enum\":[\"billing\",\"support\"]},{\"type\":\"null\"}]}");

		assertThat(RequestedSchema.parse(split, QUESTIONS).fields()).containsExactly("team");
	}

	@Test
	void rejectsATopLevelThatIsNotAnObject() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RequestedSchema.parse("{\"type\":\"array\",\"items\":{\"type\":\"object\"}}", QUESTIONS))
			.withMessageContaining("cannot produce a [array]");
	}

	@Test
	void stopsOnACyclicRef() {
		String cyclic = "{\"$defs\":{\"A\":{\"$ref\":\"#/$defs/A\"}},\"type\":\"object\",\"properties\":{\"team\":{\"$ref\":\"#/$defs/A\"}}}";

		assertThat(RequestedSchema.parse(cyclic, QUESTIONS).fields()).containsExactly("team");
	}

	private static String object(String properties) {
		return "{\"type\":\"object\",\"properties\":{" + properties + "}}";
	}

}
