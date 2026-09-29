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

package org.springaicommunity.typesafe.demo;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeConstants;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.ModelMetadata;
import org.springaicommunity.typesafe.response.SystemOneResponse;

/**
 * The three primitives in one call, against the support ticket from the TypeSafe
 * documentation. Run it with {@code TYPESAFE_API_KEY} set; no Spring context involved.
 *
 * <p>
 * The point of the single call is that Jev reads the state once and answers every
 * question against it in parallel, so a third question costs far less than a third call
 * would.
 *
 * @author Christian Tzolov
 */
public final class JevQuickstart {

	private JevQuickstart() {
	}

	public static void main(String[] args) {

		// The key is passed explicitly here to name the variable in the demo; an empty
		// builder() would read the same one from the environment.
		TypeSafeClient client = TypeSafeClient.builder().apiKey(System.getenv(TypeSafeConstants.API_KEY_ENV)).build();

		System.out.println("Available models:");
		for (ModelMetadata model : client.listModels()) {
			System.out.printf("  %-14s %s (%s)%n", model.name(), model.description(), model.releaseDate());
		}

		// @formatter:off
		// The builder keeps the questions in the order they are added; Map.of would not.
		SystemOneResponse response = client.systemOne(SystemOneRequest.builder()
				.state("Help! My payouts have been failing for 3 days.")
				.question("is_urgent", Noul.builder()
						.instructions("Does this convey urgency?")
						.whenTrue("Explicitly time-sensitive")
						.whenFalse("No urgency expressed")
						.build())
				.question("department", Choice.builder()
						.instructions("Which team should handle this?")
						.option("billing", "Payments, invoicing, refunds")
						.option("technical", "Bugs, outages, integrations")
						.option("sales", "Pricing, upgrades, new accounts")
						.build())
				.question("frustration", Score.of("How frustrated is the customer?",
						"Calm", "Frustrated", "Very angry"))
				.build());
		// @formatter:on

		System.out.println();
		System.out.printf("urgent      : %.2f%n", response.noulValue("is_urgent"));
		System.out.printf("department  : %s (confidence %.2f) %s%n", response.choiceValue("department"),
				response.choice("department").confidence(), response.choice("department").probabilities());
		System.out.printf("frustration : %.2f -> %s (confidence %.2f)%n", response.scoreValue("frustration"),
				response.score("frustration").nearestLabel(), response.score("frustration").confidence());
		// %s rather than %d: the counts are nullable, and %d would NPE on unboxing.
		System.out.printf("tokens      : %s in, %s out%n", response.usage().inputTokens(),
				response.usage().outputTokens());
		System.out.println("request id  : " + response.requestId());

		// Confidence gating: act automatically only where the distribution is
		// concentrated.
		if (response.choice("department").confidence() >= 0.8d) {
			System.out.println("=> routing automatically to " + response.choiceValue("department"));
		}
		else {
			System.out.println("=> confidence too low to route automatically, sending to a human");
		}
	}

}
