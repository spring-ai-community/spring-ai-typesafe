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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeConstants;
import org.springaicommunity.typesafe.TypeSafeModels;

import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool selection against the real Jev API.
 *
 * <p>
 * The offline tests pin how a response is turned into a ranking. This checks the premise
 * they rest on: that the real service matches a request to a tool on meaning rather than
 * wording, and — the part no ranking mechanism can do — that it declines when the toolset
 * does not cover the request.
 *
 * @author Christian Tzolov
 */
@EnabledIfEnvironmentVariable(named = TypeSafeConstants.API_KEY_ENV, matches = ".+",
		disabledReason = "Set TYPESAFE_API_KEY to run tool search against the real Jev API")
class JevToolIndexIT {

	private static final String SESSION = "it-session";

	private final TypeSafeClient client = TypeSafeClient.builder()
		.defaultModel(TypeSafeModels.JEV_LATEST)
		.build();

	private JevToolIndex index;

	@BeforeEach
	void indexTools() {
		this.index = JevToolIndex.builder(this.client).build();
		this.index.indexTools(SESSION,
				List.of(tool("currentWeather", "Returns the current temperature and conditions for a named place"),
						tool("sendEmail", "Sends an email message to one or more recipients"),
						tool("createInvoice", "Creates and issues an invoice for a customer"),
						tool("bookMeeting", "Schedules a meeting in the calendar with a set of attendees")));
	}

	@Test
	void matchesOnMeaningRatherThanSharedVocabulary() {
		// Nothing in this query appears in the tool's name or description.
		ToolSearchResponse response = this.index
			.search(new ToolSearchRequest(SESSION, "is it chilly outside in Oslo", 1, null));

		assertThat(response.toolReferences()).isNotEmpty();
		assertThat(response.toolReferences().get(0).toolName()).isEqualTo("currentWeather");
		assertThat(response.toolReferences().get(0).relevanceScore()).isNotNull().isGreaterThan(0.5d);
	}

	@Test
	void declinesWhenNoToolCoversTheRequest() {
		// The behaviour that justifies this class: a ranking alone would still name a
		// winner here, because the probabilities have to sum to one.
		ToolSearchResponse response = this.index
			.search(new ToolSearchRequest(SESSION, "what is the capital of Peru", null, null));

		assertThat(response.toolReferences()).isEmpty();
		assertThat(response.totalMatches()).isZero();
	}

	@Test
	void ranksTheRightToolFirstAcrossSeveralPhrasings() {
		assertThat(best("bill Acme Corp for last month")).isEqualTo("createInvoice");
		assertThat(best("drop a line to the finance team")).isEqualTo("sendEmail");
		assertThat(best("put half an hour in the diary with Sam on Tuesday")).isEqualTo("bookMeeting");
	}

	@Test
	void honoursMaxResultsAgainstTheRealService() {
		ToolSearchResponse response = this.index
			.search(new ToolSearchRequest(SESSION, "send a note to accounting", 2, null));

		assertThat(response.toolReferences()).hasSizeLessThanOrEqualTo(2);
	}

	private String best(String query) {
		ToolSearchResponse response = this.index.search(new ToolSearchRequest(SESSION, query, 1, null));
		assertThat(response.toolReferences()).as("expected a tool for '%s'", query).isNotEmpty();
		return response.toolReferences().get(0).toolName();
	}

	private static ToolReference tool(String name, String summary) {
		return ToolReference.builder().toolName(name).summary(summary).build();
	}

}
