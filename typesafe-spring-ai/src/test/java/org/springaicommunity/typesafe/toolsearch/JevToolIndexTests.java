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

package org.springaicommunity.typesafe.toolsearch;



import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.MockTypeSafeServer;

import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Selecting a tool by asking rather than by matching text.
 *
 * @author Christian Tzolov
 */
class JevToolIndexTests {

	private static final String SESSION = "session-1";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	private JevToolIndex index;

	@BeforeEach
	void indexThreeTools() {
		this.index = JevToolIndex.builder(this.mock.client()).build();
		this.index.indexTools(SESSION,
				List.of(tool("currentWeather", "Returns the current weather for a place"),
						tool("sendEmail", "Sends an email to a recipient"),
						tool("createInvoice", "Creates an invoice for a customer")));
	}

	@Test
	void ranksTheToolsBySelectionProbability() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.user_request").value("what is it like outside in Paris"))
			.andExpect(jsonPath("$.questions.best_tool.type").value("choice"))
			.andExpect(jsonPath("$.questions.any_tool_applies.type").value("noul"))
			// Same order on every JVM run: the request before the catalogue, the question
			// before its hints.
			.andExpect(MockTypeSafeServer.keyOrder("/state", "user_request", "available_tools"))
			.andExpect(MockTypeSafeServer.keyOrder("/questions/any_tool_applies/instructions", "question", "inspect",
					"focus"))
			// The tools in the order they were indexed, both as the catalogue and as options.
			.andExpect(jsonPath("$.state.available_tools[*].name").value(
					contains("currentWeather", "sendEmail", "createInvoice")))
			.andExpect(MockTypeSafeServer.keyOrder("/questions/best_tool/criteria", "currentWeather", "sendEmail",
					"createInvoice"))
			.andRespond(MockTypeSafeServer.jsonResponse(response(0.95d, 0.90d, 0.07d, 0.03d)));

		ToolSearchResponse response = this.index
			.search(new ToolSearchRequest(SESSION, "what is it like outside in Paris", null, null));

		assertThat(response.toolReferences()).extracting(ToolReference::toolName)
			.containsExactly("currentWeather", "sendEmail", "createInvoice");
		assertThat(response.toolReferences().get(0).relevanceScore()).isEqualTo(0.90d);
		assertThat(response.totalMatches()).isEqualTo(3);
	}

	@Test
	void returnsNothingWhenNoToolAppliesEvenThoughOneStillRanksFirst() {
		// This is the whole argument for the class. Choice probabilities sum to 1, so
		// createInvoice "wins" a query about none of these tools; only the independent
		// applicability question can say that the winner should not be used.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(response(0.04d, 0.20d, 0.25d, 0.55d)));

		ToolSearchResponse response = this.index
			.search(new ToolSearchRequest(SESSION, "translate this into Welsh", null, null));

		assertThat(response.toolReferences()).isEmpty();
		assertThat(response.totalMatches()).isZero();
	}

	@Test
	void honoursMaxResults() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(response(0.95d, 0.90d, 0.07d, 0.03d)));

		ToolSearchResponse response = this.index
			.search(new ToolSearchRequest(SESSION, "weather in Paris", 1, null));

		assertThat(response.toolReferences()).extracting(ToolReference::toolName).containsExactly("currentWeather");
	}

	@Test
	void neverPadsMaxResultsWithToolsJevGaveNoProbability() {
		// A clear-cut selection: every other tool at zero. With the default relevance floor
		// of zero they must not come back as filler up to maxResults.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(response(0.95d, 1.0d, 0.0d, 0.0d)));

		ToolSearchResponse response = this.index.search(new ToolSearchRequest(SESSION, "weather in Paris", 5, null));

		assertThat(response.toolReferences()).extracting(ToolReference::toolName).containsExactly("currentWeather");
		assertThat(response.totalMatches()).isEqualTo(1);
	}

	@Test
	void dropsToolsBelowTheRelevanceFloor() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(response(0.95d, 0.90d, 0.07d, 0.03d)));

		JevToolIndex picky = JevToolIndex.builder(this.mock.client()).minimumRelevance(0.5d).build();
		picky.indexTools(SESSION, List.of(tool("currentWeather", "Returns the current weather for a place"),
				tool("sendEmail", "Sends an email to a recipient"),
				tool("createInvoice", "Creates an invoice for a customer")));

		ToolSearchResponse response = picky.search(new ToolSearchRequest(SESSION, "weather in Paris", null, null));

		assertThat(response.toolReferences()).extracting(ToolReference::toolName).containsExactly("currentWeather");
	}

	@Test
	void asksOnlyAboutApplicabilityWhenTheSessionHoldsASingleTool() {
		// A choice between one option is not a question worth asking.
		JevToolIndex single = JevToolIndex.builder(this.mock.client()).build();
		single.indexTool("solo", tool("currentWeather", "Returns the current weather for a place"));

		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.questions.best_tool").doesNotExist())
			.andExpect(jsonPath("$.questions.any_tool_applies.type").value("noul"))
			.andExpect(MockTypeSafeServer.keyOrder("/state", "user_request", "tool"))
			.andRespond(MockTypeSafeServer.jsonResponse(
					"{\"model\":\"jev-1.13.0\",\"answers\":{\"any_tool_applies\":{\"type\":\"noul\",\"noul\":0.93}},"
							+ "\"usage\":{}}"));

		ToolSearchResponse response = single.search(new ToolSearchRequest("solo", "weather in Paris", null, null));

		assertThat(response.toolReferences()).extracting(ToolReference::toolName).containsExactly("currentWeather");
		assertThat(response.toolReferences().get(0).relevanceScore()).isEqualTo(0.93d);
	}

	@Test
	void anUnknownOrClearedSessionCostsNoCall() {
		assertThat(this.index.search(new ToolSearchRequest("no-such-session", "anything", null, null))
			.toolReferences()).isEmpty();

		this.index.clearIndex(SESSION);
		assertThat(this.index.search(new ToolSearchRequest(SESSION, "anything", null, null)).toolReferences())
			.isEmpty();

		this.mock.server().verify();
	}

	@Test
	void returnsNothingWhenNoToolClearsTheRelevanceFloor() {
		// Some tool applies, but none stands out. The fallback for a response without
		// probabilities must not hand back the top label here.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(response(0.95d, 0.40d, 0.35d, 0.25d)));

		JevToolIndex picky = JevToolIndex.builder(this.mock.client()).minimumRelevance(0.5d).build();
		picky.indexTools(SESSION, List.of(tool("currentWeather", "Returns the current weather for a place"),
				tool("sendEmail", "Sends an email to a recipient"),
				tool("createInvoice", "Creates an invoice for a customer")));

		ToolSearchResponse response = picky.search(new ToolSearchRequest(SESSION, "do something", null, null));

		assertThat(response.toolReferences()).isEmpty();
	}

	@Test
	void narrowsCandidatesByCategoryBeforeAsking() {
		JevToolIndex billing = JevToolIndex.builder(this.mock.client()).build();
		billing.indexTools(SESSION,
				List.of(tool("currentWeather", "Returns the current weather for a place"),
						tool("createInvoice", "Creates an invoice for a customer"),
						tool("remindCustomer", "Emails a reminder about an unpaid invoice")));

		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			// Only the two tools matching "invoice" reach the choice.
			.andExpect(MockTypeSafeServer.keyOrder("/questions/best_tool/criteria", "createInvoice", "remindCustomer"))
			.andRespond(MockTypeSafeServer.jsonResponse(
					"{\"model\":\"jev-1.13.0\",\"answers\":{\"any_tool_applies\":{\"type\":\"noul\",\"noul\":0.9},"
							+ "\"best_tool\":{\"type\":\"choice\",\"choice\":\"createInvoice\","
							+ "\"probabilities\":{\"createInvoice\":0.8,\"remindCustomer\":0.2},\"confidence\":0.8}},"
							+ "\"usage\":{}}"));

		ToolSearchResponse response = billing
			.search(new ToolSearchRequest(SESSION, "bill the customer", null, "invoice"));

		assertThat(response.toolReferences()).extracting(ToolReference::toolName)
			.containsExactly("createInvoice", "remindCustomer");
	}

	@Test
	void ignoresACategoryThatMatchesNoTool() {
		// The category is free text the model writes; one it invents must not hide every tool.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(MockTypeSafeServer.keyOrder("/questions/best_tool/criteria", "currentWeather", "sendEmail",
					"createInvoice"))
			.andRespond(MockTypeSafeServer.jsonResponse(response(0.95d, 0.05d, 0.05d, 0.90d)));

		ToolSearchResponse response = this.index
			.search(new ToolSearchRequest(SESSION, "bill the customer", null, "finance"));

		assertThat(response.toolReferences().get(0).toolName()).isEqualTo("createInvoice");
	}

	@Test
	void aSelectionWithoutProbabilitiesStillYieldsTheChosenTool() {
		// The ranking comes from the probability map, which the response may omit. Ranking
		// an empty map returns nothing, so without a fallback a selection the service
		// plainly did make would be reported as "no tool matched".
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse("{\"model\":\"jev-1.13.0\",\"answers\":{"
					+ "\"any_tool_applies\":{\"type\":\"noul\",\"noul\":0.95},"
					+ "\"best_tool\":{\"type\":\"choice\",\"choice\":\"currentWeather\","
					+ "\"confidence\":0.88}},\"usage\":{}}"));

		ToolSearchResponse response = this.index
			.search(new ToolSearchRequest(SESSION, "what is it like outside in Paris", null, null));

		assertThat(response.toolReferences()).extracting(ToolReference::toolName).containsExactly("currentWeather");
	}

	private static ToolReference tool(String name, String summary) {
		return ToolReference.builder().toolName(name).summary(summary).build();
	}

	private static String response(double applicability, double weather, double email, double invoice) {
		return "{\"model\":\"jev-1.13.0\",\"answers\":{" + "\"any_tool_applies\":{\"type\":\"noul\",\"noul\":"
				+ applicability + "}," + "\"best_tool\":{\"type\":\"choice\",\"choice\":\"currentWeather\","
				+ "\"probabilities\":{\"currentWeather\":" + weather + ",\"sendEmail\":" + email
				+ ",\"createInvoice\":" + invoice + "},\"confidence\":0.88}},\"usage\":{}}";
	}

}
