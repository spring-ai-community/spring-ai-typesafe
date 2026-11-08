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

package org.springaicommunity.typesafe;



import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.api.TypeSafeApi;
import org.springaicommunity.typesafe.question.Noul;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * The endpoint-path knobs: what the builders reject, the defaults that apply when an
 * application says nothing, and the constants that publish those defaults.
 *
 * @author Tiago Santos
 */
class TypeSafeClientBuilderTests {

	private static final String OK_BODY = """
			{"model":"jev-1.13.0","answers":{"probe":{"type":"noul","noul":0.5}},"usage":{}}""";

	private static final String MODELS_BODY = """
			{"models":[{"name":"jev-latest","description":"The latest Jev","release_date":"2026-09-10T18:38:01.391457+00:00"}]}""";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	@Test
	void callsTheDocumentedPathsWhenNothingIsConfigured() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(OK_BODY));
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.MODELS_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(MODELS_BODY));

		TypeSafeClient client = this.mock.client();
		client.systemOne("anything", Map.of("probe", Noul.of("Is this a probe?")));
		client.listModels();

		this.mock.server().verify();
	}

	@Test
	void rejectsABlankPathRatherThanBuildingAClientThatCannotCallIt() {
		// A blank path resolves against the base URL, so the request would land on the API
		// root and fail there with a confusing 404 instead of here with a clear message.
		assertThatIllegalArgumentException().isThrownBy(() -> TypeSafeClient.builder().systemOnePath(" "))
			.withMessageContaining("systemOnePath");
		assertThatIllegalArgumentException().isThrownBy(() -> TypeSafeClient.builder().modelsPath(""))
			.withMessageContaining("modelsPath");
		assertThatIllegalArgumentException().isThrownBy(() -> TypeSafeApi.builder().apiKey("k").systemOnePath("  "))
			.withMessageContaining("systemOnePath");
		assertThatIllegalArgumentException().isThrownBy(() -> TypeSafeApi.builder().apiKey("k").modelsPath(null))
			.withMessageContaining("modelsPath");
	}

	@Test
	void exposesThePathsItWasBuiltWith() {
		// The starter reads these to publish the endpoints in use, so an unset path has to
		// report the default rather than null.
		TypeSafeApi defaults = TypeSafeApi.builder().apiKey("k").build();
		assertThat(defaults.getSystemOnePath()).isEqualTo(TypeSafeApi.DEFAULT_SYSTEM_ONE_PATH);
		assertThat(defaults.getModelsPath()).isEqualTo(TypeSafeApi.DEFAULT_MODELS_PATH);

		TypeSafeApi configured = TypeSafeApi.builder()
			.apiKey("k")
			.systemOnePath("/api/v2/evaluate")
			.modelsPath("/api/v2/models")
			.build();
		assertThat(configured.getSystemOnePath()).isEqualTo("/api/v2/evaluate");
		assertThat(configured.getModelsPath()).isEqualTo("/api/v2/models");
	}

	@Test
	void publishesTheEndpointDefaultsAlongsideTheOtherClientDefaults() {
		// Ported code and the starter both read these names from TypeSafeConstants, so they
		// must be the paths the transport actually uses.
		assertThat(TypeSafeConstants.DEFAULT_SYSTEM_ONE_PATH).isEqualTo("/v1/systemone")
			.isEqualTo(TypeSafeApi.DEFAULT_SYSTEM_ONE_PATH);
		assertThat(TypeSafeConstants.DEFAULT_MODELS_PATH).isEqualTo("/v1/models")
			.isEqualTo(TypeSafeApi.DEFAULT_MODELS_PATH);
	}

}
