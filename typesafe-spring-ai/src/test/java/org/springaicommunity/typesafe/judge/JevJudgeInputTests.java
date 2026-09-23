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



import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.JsonContent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The typed view and the judged state are one map, so they cannot drift apart.
 *
 * @author Christian Tzolov
 */
class JevJudgeInputTests {

	@Test
	void buildsTheStateUnderTheDocumentedFieldNames() {
		JevJudgeInput input = JevJudgeInput.builder()
			.question("q")
			.answer("a")
			.expected("e")
			.context(List.of("d1", "d2"))
			.toolCall(new JevJudgeInput.ToolCall("search", "{}", "r"))
			.field("locale", "en")
			.build();

		assertThat(input.toState().asMap()).containsOnlyKeys(JevJudgeInput.QUESTION_FIELD,
				JevJudgeInput.ANSWER_FIELD, JevJudgeInput.EXPECTED_FIELD, JevJudgeInput.CONTEXT_FIELD,
				JevJudgeInput.TOOL_CALLS_FIELD, "locale");
		assertThat(input.question()).isEqualTo("q");
		assertThat(input.answer()).isEqualTo("a");
		assertThat(input.expected()).isEqualTo("e");
		assertThat(input.context()).containsExactly("d1", "d2");
		assertThat(input.toolCalls()).containsExactly(new JevJudgeInput.ToolCall("search", "{}", "r"));
		assertThat(input.field("locale", String.class)).isEqualTo("en");
	}

	@Test
	void leavesEmptyEvidenceOutOfTheState() {
		// "No evidence" and "evidence that says nothing" must stay distinguishable.
		JevJudgeInput input = JevJudgeInput.builder().question("q").answer("a").context(List.of()).build();

		assertThat(input.toState().asMap()).containsOnlyKeys(JevJudgeInput.QUESTION_FIELD, JevJudgeInput.ANSWER_FIELD);
		assertThat(input.context()).isEmpty();
		assertThat(input.toolCalls()).isEmpty();
	}

	@Test
	void readsToolCallsFromAStateBuiltElsewhere() {
		JevJudgeInput input = JevJudgeInput.of(JsonContent.of(Map.of(JevJudgeInput.TOOL_CALLS_FIELD,
				List.of("web_search", Map.of("name", "weather", "result", "15")))));

		assertThat(input.toolCalls()).containsExactly(new JevJudgeInput.ToolCall("web_search", null, null),
				new JevJudgeInput.ToolCall("weather", null, "15"));
	}

	@Test
	void namesTheFieldWhenItsValueHasTheWrongType() {
		JevJudgeInput input = JevJudgeInput.builder().field("search_required", "yes").build();

		assertThatIllegalStateException().isThrownBy(() -> input.field("search_required", Boolean.class))
			.withMessageContaining("search_required");
	}

	@Test
	void rejectsAStateThatIsNotAnObject() {
		assertThatIllegalArgumentException().isThrownBy(() -> JevJudgeInput.of(JsonContent.of(List.of("a"))));
	}

	@Test
	void keepsTheListFieldsBehindTheirOwnBuilderMethods() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> JevJudgeInput.builder().field(JevJudgeInput.TOOL_CALLS_FIELD, List.of()));
	}

}
