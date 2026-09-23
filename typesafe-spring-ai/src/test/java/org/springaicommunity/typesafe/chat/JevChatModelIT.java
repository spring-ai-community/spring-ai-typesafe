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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeConstants;
import org.springaicommunity.typesafe.TypeSafeModels;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;

import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classifier behind {@code ChatClient}, against the real Jev API.
 *
 * @author Christian Tzolov
 */
@EnabledIfEnvironmentVariable(named = TypeSafeConstants.API_KEY_ENV, matches = ".+",
		disabledReason = "Set TYPESAFE_API_KEY to run against the real Jev API")
class JevChatModelIT {

	record Triage(String team, double urgent, double severity) {
	}

	private final ChatClient chatClient = ChatClient.create(JevChatModel
		.builder(TypeSafeClient.builder()
			.baseUrl(TypeSafeConstants.DEFAULT_BASE_URL)
			.defaultModel(TypeSafeModels.JEV_LATEST)
			.build())
		.question("team",
				Choice.builder()
					.instructions("Which team should handle the user's ticket?")
					.option("infra", "Deploys, outages, servers and errors in production")
					.option("billing", "Invoices, payments, refunds and pricing")
					.option("support", "How-to questions and account help")
					.build())
		.question("urgent",
				Noul.builder()
					.instructions("Does the user's ticket need attention right now?")
					.whenTrue("Customers are affected now")
					.whenFalse("It can wait for normal working hours")
					.build())
		.question("severity",
				Score.of("How severe is the impact described in the ticket?", "Cosmetic", "Degraded for some users",
						"Full outage"))
		.build());

	@Test
	void triagesAnOutageAsUrgentInfra() {
		Triage triage = this.chatClient.prompt()
			.user("Production is down after the 14:00 deploy; every customer gets HTTP 500.")
			.call()
			.entity(Triage.class);

		assertThat(triage.team()).isEqualTo("infra");
		assertThat(triage.urgent()).isGreaterThan(0.7d);
		assertThat(triage.severity()).isGreaterThan(1.0d);
	}

	@Test
	void triagesAnInvoiceQuestionAsCalmBilling() {
		Triage triage = this.chatClient.prompt()
			.user("Could you resend last month's invoice with our new VAT number on it? No rush.")
			.call()
			.entity(Triage.class);

		assertThat(triage.team()).isEqualTo("billing");
		assertThat(triage.urgent()).isLessThan(0.5d);
		assertThat(triage.severity()).isLessThan(1.0d);
	}

}
