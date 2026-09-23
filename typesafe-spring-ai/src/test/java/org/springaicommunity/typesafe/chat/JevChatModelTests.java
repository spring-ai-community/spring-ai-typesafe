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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.MockTypeSafeServer;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * A Jev classifier behind the {@code ChatModel} interface.
 *
 * @author Christian Tzolov
 */
class JevChatModelTests {

	private static final String TICKET = "The deploy failed twice and customers are seeing 500s.";

	private static final String TRIAGE_RESPONSE = """
			{"model":"jev-1.13.0","answers":{
			  "team":{"type":"choice","choice":"infra",
			    "probabilities":{"infra":0.91,"billing":0.04,"support":0.05},"confidence":0.88},
			  "urgent":{"type":"noul","noul":0.97},
			  "severity":{"type":"score","score":1.8,
			    "legend":{"0":"Cosmetic","1":"Degraded","2":"Outage"},
			    "probabilities":{"0":0.02,"1":0.16,"2":0.82},"confidence":0.7}
			},"usage":{"input_tokens":210,"output_tokens":18}}""";

	record Triage(String team, double urgent, double severity) {
	}

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	@Test
	void mapsTheAnswersOntoARecordThroughChatClientEntity() {
		// entity(...) appends JSON format instructions to the user message; they are for
		// a generator, and must not leak into the state Jev judges.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.messages[0].role").value("user"))
			.andExpect(jsonPath("$.state.messages[0].content").value(TICKET))
			.andExpect(jsonPath("$.questions.team.type").value("choice"))
			.andExpect(jsonPath("$.questions.urgent.type").value("noul"))
			.andExpect(jsonPath("$.questions.severity.type").value("score"))
			.andRespond(MockTypeSafeServer.jsonResponse(TRIAGE_RESPONSE));

		Triage triage = ChatClient.create(triageModel()).prompt().user(TICKET).call().entity(Triage.class);

		assertThat(triage).isEqualTo(new Triage("infra", 0.97d, 1.8d));
		this.mock.server().verify();
	}

	@Test
	void takesTheSchemaNativelyAndLeavesTheUserMessageUntouched() {
		// With native structured output the schema travels in the options, so there are
		// no format instructions in the prompt to strip.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.messages[0].content").value(TICKET))
			.andRespond(MockTypeSafeServer.jsonResponse(TRIAGE_RESPONSE));

		Triage triage = ChatClient.create(triageModel())
			.prompt()
			.user(TICKET)
			.call()
			.entity(Triage.class, spec -> spec.useProviderStructuredOutput());

		assertThat(triage).isEqualTo(new Triage("infra", 0.97d, 1.8d));
		this.mock.server().verify();
	}

	record Mistyped(double team) {
	}

	record UnknownField(String team, String priority) {
	}

	enum Team {

		infra, billing

	}

	record NarrowEnum(Team team) {
	}

	@Test
	void rejectsARecordFieldNoQuestionAnswersBeforeCallingJev() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChatClient.create(triageModel())
				.prompt()
				.user(TICKET)
				.call()
				.entity(UnknownField.class, spec -> spec.useProviderStructuredOutput()))
			.withMessageContaining("field 'priority' that no question answers")
			.withMessageContaining("[team, urgent, severity]");
		this.mock.server().verify();
	}

	@Test
	void rejectsAChoiceMappedOntoANumber() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChatClient.create(triageModel())
				.prompt()
				.user(TICKET)
				.call()
				.entity(Mistyped.class, spec -> spec.useProviderStructuredOutput()))
			.withMessageContaining("Field 'team'")
			.withMessageContaining("a choice's label");
		this.mock.server().verify();
	}

	@Test
	void rejectsAnEnumThatCannotHoldEveryChoiceOption() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> ChatClient.create(triageModel())
				.prompt()
				.user(TICKET)
				.call()
				.entity(NarrowEnum.class, spec -> spec.useProviderStructuredOutput()))
			.withMessageContaining("cannot hold the choice option 'support'");
		this.mock.server().verify();
	}

	@Test
	void offersStructuredOutputOptionsButNotToolCallingOnes() {
		assertThat(triageModel().getOptions()).isInstanceOf(StructuredOutputChatOptions.class)
			.isNotInstanceOf(ToolCallingChatOptions.class);
	}

	@Test
	void repliesWithTheAnswersAsJson() {
		respondWith(TRIAGE_RESPONSE);

		String content = ChatClient.create(triageModel()).prompt().user(TICKET).call().content();

		assertThat(content).isEqualTo("{\"team\":\"infra\",\"urgent\":0.97,\"severity\":1.8}");
	}

	@Test
	void carriesTheFullResponseUsageAndModelInTheMetadata() {
		respondWith(TRIAGE_RESPONSE);

		ChatResponse response = triageModel().call(new Prompt(TICKET));

		SystemOneResponse jev = response.getResult().getMetadata().get(JevChatModel.RESPONSE_METADATA_KEY);
		assertThat(jev.choice("team").probabilityOf("infra")).isEqualTo(0.91d);
		assertThat(response.getMetadata().getModel()).isEqualTo("jev-1.13.0");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(210);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(18);
		assertThat(response.getMetadata().getId()).isEqualTo("req_0123456789");
	}

	@Test
	void putsTheSystemMessageAndTheConversationIntoTheState() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.system").value("Tickets come from enterprise customers."))
			.andExpect(jsonPath("$.state.messages.length()").value(1))
			.andRespond(MockTypeSafeServer.jsonResponse(TRIAGE_RESPONSE));

		triageModel().call(new Prompt(
				List.of(new SystemMessage("Tickets come from enterprise customers."), new UserMessage(TICKET))));

		this.mock.server().verify();
	}

	@Test
	void usesTheModelNamedInThePromptOptions() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.model").value("jev-1.13.0"))
			.andRespond(MockTypeSafeServer.jsonResponse(TRIAGE_RESPONSE));

		triageModel().call(new Prompt(TICKET, ChatOptions.builder().model("jev-1.13.0").build()));

		this.mock.server().verify();
	}

	@Test
	void acceptsACustomStateConverter() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.ticket").value(TICKET))
			.andRespond(MockTypeSafeServer.jsonResponse(TRIAGE_RESPONSE));

		JevChatModel model = JevChatModel.builder(this.mock.client())
			.questions(triageModel().questions())
			.stateConverter(prompt -> JsonContent.of(Map.of("ticket", prompt.getUserMessage().getText())))
			.build();

		model.call(new Prompt(TICKET));

		this.mock.server().verify();
	}

	@Test
	void doesNotRunTheToolLoopEvenWhenChatClientIsGivenTools() {
		// Plain ChatOptions, so ChatClient keeps no tools and never enters its tool loop.
		respondWith(TRIAGE_RESPONSE);

		String content = ChatClient.builder(triageModel()).defaultTools(new Object() {
			@org.springframework.ai.tool.annotation.Tool(description = "never called")
			String lookup(String id) {
				throw new AssertionError("a classifier must not call tools");
			}
		}).build().prompt().user(TICKET).call().content();

		assertThat(content).contains("\"team\":\"infra\"");
		assertThat(triageModel().getOptions()).isNotInstanceOf(ToolCallingChatOptions.class);
	}

	@Test
	void rejectsAPromptThatCarriesToolCallbacksDirectly() {
		ToolCallingChatOptions withTools = ToolCallingChatOptions.builder()
			.toolCallbacks(FunctionToolCallback.builder("lookup", (String id) -> id)
				.description("lookup")
				.inputType(String.class)
				.build())
			.build();

		assertThatIllegalArgumentException().isThrownBy(() -> triageModel().call(new Prompt(TICKET, withTools)))
			.withMessageContaining("cannot call tools");
	}

	@Test
	void refusesToStream() {
		assertThatExceptionOfType(UnsupportedOperationException.class)
			.isThrownBy(() -> triageModel().stream(new Prompt(TICKET)).blockLast());
	}

	@Test
	void stripsOnlyTheFormatInstructionsFromTheUserMessage() {
		assertThat(JevChatModel
			.withoutFormatInstructions("Classify this.\n" + JevChatModel.FORMAT_INSTRUCTIONS_START + "\nschema..."))
			.isEqualTo("Classify this.");
		assertThat(JevChatModel.withoutFormatInstructions("No instructions here.")).isEqualTo("No instructions here.");
	}

	@Test
	void observesEachCallAsAGenAiClientOperation() {
		respondWith(TRIAGE_RESPONSE);
		List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStop(Observation.Context context) {
				stopped.add(context);
			}
		});

		observedModel(registry).call(new Prompt(TICKET, ChatOptions.builder().model("jev-latest").build()));

		assertThat(stopped).singleElement().satisfies(context -> {
			assertThat(context).isInstanceOf(ChatModelObservationContext.class);
			assertThat(context.getName()).isEqualTo("gen_ai.client.operation");
			assertThat(context.getContextualName()).isEqualTo("chat jev-latest");
			assertThat(context.getLowCardinalityKeyValues()).extracting(KeyValue::getKey, KeyValue::getValue)
				.contains(tuple("gen_ai.operation.name", "chat"), tuple("gen_ai.system", JevChatModel.PROVIDER),
						tuple("gen_ai.request.model", "jev-latest"), tuple("gen_ai.response.model", "jev-1.13.0"));
			assertThat(context.getHighCardinalityKeyValues()).extracting(KeyValue::getKey, KeyValue::getValue)
				.contains(tuple("gen_ai.response.id", "req_0123456789"), tuple("gen_ai.usage.input_tokens", "210"),
						tuple("gen_ai.usage.output_tokens", "18"));
		});
	}

	@Test
	void recordsTokenUsageThroughTheStandardMeterHandler() {
		respondWith(TRIAGE_RESPONSE);
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ChatModelMeterObservationHandler(meters));

		observedModel(registry).call(new Prompt(TICKET));

		assertThat(meters.get("gen_ai.client.token.usage").tag("gen_ai.token.type", "input").counter().count())
			.isEqualTo(210.0d);
		assertThat(meters.get("gen_ai.client.token.usage").tag("gen_ai.token.type", "output").counter().count())
			.isEqualTo(18.0d);
	}

	@Test
	void recordsAFailedCallAsAnErroredObservation() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(422, "malformed question"));
		List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStop(Observation.Context context) {
				stopped.add(context);
			}
		});

		assertThatExceptionOfType(TypeSafeException.class)
			.isThrownBy(() -> observedModel(registry).call(new Prompt(TICKET)));
		assertThat(stopped).singleElement().satisfies(context -> assertThat(context.getError()).isNotNull());
	}

	private JevChatModel observedModel(ObservationRegistry registry) {
		return JevChatModel.builder(this.mock.client())
			.questions(triageModel().questions())
			.observationRegistry(registry)
			.build();
	}

	@Test
	void requiresAtLeastOneQuestion() {
		assertThatIllegalArgumentException().isThrownBy(() -> JevChatModel.builder(this.mock.client()).build())
			.withMessageContaining("at least one question");
	}

	private JevChatModel triageModel() {
		return JevChatModel.builder(this.mock.client())
			.question("team", Choice.of("Which team should handle this?", "infra", "billing", "support"))
			.question("urgent", Noul.of("Does this need attention right now?"))
			.question("severity", Score.of("How severe is the impact?", "Cosmetic", "Degraded", "Outage"))
			.build();
	}

	private void respondWith(String body) {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(body));
	}

}
