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

package org.springaicommunity.typesafe.demo.ollama;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.judge.JevVerdict;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

/**
 * System One decisions from a local Ollama instead of the hosted Jev API.
 *
 * <p>
 * Ollama 0.35 serves Jev-style decision models on the same {@code POST /v1/systemone}
 * protocol, so the starter's {@link TypeSafeClient} works against it unchanged: the
 * {@code ollama} profile only points {@code spring.ai.typesafe.base-url} at
 * {@code http://localhost:11434}. Nothing here is Ollama-specific code.
 *
 * <p>
 * Pull a decision model first, {@code ollama pull nimble}, and run with no API key at all.
 * Set {@code OLLAMA_SYSTEMONE_MODEL} to try {@code tev1} or {@code tev1:0.8b} instead.
 * Thresholds tuned on Jev need checking again on another model; the second half of this
 * demo shows why.
 *
 * @author Christian Tzolov
 * @see <a href="https://ollama.com/blog/ollama-now-supports-jev-style-decision-models">Ollama
 * now supports Jev-style decision models</a>
 */
@SpringBootApplication
public class OllamaSystemOneDemoApplication {

	private static final String TICKET = "The deploy failed twice and customers are seeing 500 errors. "
			+ "We need someone on this now.";

	public static void main(String[] args) {
		new SpringApplicationBuilder(OllamaSystemOneDemoApplication.class).profiles("ollama").run(args);
	}

	@Bean
	CommandLineRunner cli(TypeSafeClient typeSafeClient) {
		return args -> {
			System.out.printf("Ollama System One, model %s%n", typeSafeClient.defaultModel());

			// The three primitives in one call. The first call may also load the model, if
			// Ollama does not have it in memory yet, so it is timed apart from a warm one.
			SystemOneRequest triage = triage();
			long cold = System.nanoTime();
			typeSafeClient.systemOne(triage);
			long coldMillis = (System.nanoTime() - cold) / 1_000_000;

			long warm = System.nanoTime();
			SystemOneResponse response = typeSafeClient.systemOne(triage);
			long warmMillis = (System.nanoTime() - warm) / 1_000_000;

			ChoiceAnswer team = response.choice("team");
			ScoreAnswer severity = response.score("severity");
			System.out.println("─".repeat(80));
			System.out.println("Ticket: " + TICKET);
			System.out.printf("  urgent   : %.2f%n", response.noulValue("urgent"));
			System.out.printf("  team     : %s (confidence %.2f) %s%n", team.value(), team.confidence(),
					rounded(team.probabilities()));
			System.out.printf("  severity : %.2f -> %s (confidence %.2f)%n", severity.value(),
					severity.nearestLabel(), severity.confidence());
			System.out.printf("  time     : %d ms first call, %d ms warm%n", coldMillis, warmMillis);

			// Model-as-a-judge, locally: the same judge the hosted examples use.
			JevJudge judge = weatherJudge(typeSafeClient);
			System.out.println("─".repeat(80));
			for (String answer : new String[] { "It is 15 degrees Celsius in Paris.",
					"It is -255 degrees Celsius in Paris." }) {
				JevVerdict verdict = judge.judge("What is the weather in Paris?", answer);
				System.out.println("Answer : " + answer);
				System.out.println("Verdict: " + verdict.summary());
				if (!verdict.feedback().isEmpty()) {
					System.out.println(verdict.feedback().indent(2).stripTrailing());
				}
			}
			System.out.println("─".repeat(80));
			System.out.println("Same client, same questions, a local model. Compare the answers with the");
			System.out.println("hosted Jev before relying on thresholds tuned there.");
		};
	}

	private static Map<String, String> rounded(Map<String, Double> probabilities) {
		Map<String, String> rounded = new LinkedHashMap<>();
		probabilities.forEach((label, p) -> rounded.put(label, "%.2f".formatted(p)));
		return rounded;
	}

	/**
	 * A noul, a choice and a score about one ticket, sent in the order they are written.
	 */
	private static SystemOneRequest triage() {
		return SystemOneRequest.builder()
			.state(JsonContent.object("ticket", TICKET))
			.question("urgent",
					Noul.builder()
						.instructions("Does the `ticket` need attention right now?")
						.whenTrue("Customers are affected now")
						.whenFalse("It can wait for normal working hours")
						.build())
			.question("team",
					Choice.builder()
						.instructions("Which team should handle the `ticket`?")
						.option("infra", "Deploys, outages and production errors")
						.option("billing", "Invoices, payments and refunds")
						.option("support", "How-to questions and account help")
						.build())
			.question("severity",
					Score.of("How severe is the impact described in the `ticket`?", "Cosmetic",
							"Degraded for some users", "Full outage"))
			.build();
	}

	private static JevJudge weatherJudge(TypeSafeClient typeSafeClient) {
		return JevJudge.builder(typeSafeClient)
			.noul("is_plausible",
					Noul.builder()
						.instructions("Is every temperature in `assistant_answer` between -90 and +60 degrees Celsius?")
						.whenTrue("Every temperature is within a range that occurs on Earth")
						.whenFalse("A temperature is impossible, such as below absolute zero or far outside "
								+ "anything ever recorded on Earth")
						.build(),
					0.7d)
			.score("helpfulness",
					Score.builder()
						.instructions("How well does `assistant_answer` address `user_question`?")
						.level("Terrible: irrelevant or almost entirely missing")
						.level("Mostly unhelpful: misses key aspects")
						.level("Mostly helpful: answers the question but could be improved")
						.level("Excellent: relevant, direct and complete")
						.build(),
					2.0d)
			.build();
	}

}
