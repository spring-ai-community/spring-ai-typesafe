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
package org.springaicommunity.typesafe.demo.judge;

import java.util.Map;
import java.util.Random;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.advisor.JevSelfRefineAdvisor;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Model-as-a-judge with Jev doing the judging.
 *
 * <p>
 * The weather tool deliberately returns an absurd temperature part of the time. A judge
 * model asked for an overall rating tends to wave that through, because the answer reads
 * fluently and is on topic. The {@code is_plausible} noul does not: it is a single
 * question with a single job, so the advisor sees the defect, feeds it back and the model
 * tries again.
 *
 * <p>
 * Needs {@code TYPESAFE_API_KEY} and {@code ANTHROPIC_API_KEY} in the environment.
 *
 * @author Christian Tzolov
 */
@SpringBootApplication
public class ModelJudgeDemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(ModelJudgeDemoApplication.class, args);
	}

	@Bean
	CommandLineRunner cli(ChatModel chatModel, TypeSafeClient typeSafeClient) {
		return args -> {
			// @formatter:off
			// Ordered before the tool-calling advisor (HIGHEST_PRECEDENCE + 300) on purpose:
			// a rejected answer is best fixed by calling the weather tool again, and only
			// from out here does a retry re-run the tool loop. The price is that the judge
			// does not see `tool_calls`; see the advisor docs for the trade-off.
			ChatClient chatClient = ChatClient.builder(chatModel)
					.defaultTools(new WeatherTools())
					.defaultAdvisors(JevSelfRefineAdvisor.builder()
							.order(Ordered.HIGHEST_PRECEDENCE + 100)
							.judge(WeatherJudge.create(typeSafeClient))
							.maxRepeatAttempts(10)
							.build())
					.build();

			String answer = chatClient.prompt("What is current weather in Paris?")
					.call()
					.content();
			// @formatter:on

			System.out.println("\nANSWER: " + answer);
		};
	}

	/**
	 * A weather tool that is wrong most of the time, on purpose.
	 */
	static class WeatherTools {

		private static final int[] TEMPERATURES = { -125, 15 };

		private final Random random = new Random();

		@Tool(description = "Get the current weather for a given location")
		String weather(String location) {
			int temperature = TEMPERATURES[this.random.nextInt(TEMPERATURES.length)];
			String toolResult = "The weather in %s is %d degrees Celsius.".formatted(location, temperature);
			System.out.println("\n\nTool output: " + toolResult);
			return toolResult;
		}

	}

	/**
	 * The judge used by the demo, written the way the TypeSafe documentation recommends:
	 * several narrow questions rather than one broad rubric, each thresholded on its own.
	 *
	 * <p>
	 * The hand-rolled LLM-as-a-judge this replaces asked a second chat model for a single
	 * 1 to 4 rating and a paragraph of prose. Splitting that into three questions means
	 * an answer that is fluent and on topic but quotes an impossible temperature fails on
	 * {@code is_plausible} alone, and the feedback says exactly that instead of averaging
	 * the problem away into a middling score.
	 */
	final class WeatherJudge {

		private WeatherJudge() {
		}

		static JevJudge create(TypeSafeClient typeSafeClient) {
			return JevJudge.builder(typeSafeClient)
				.score("helpfulness",
						Score.builder()
							.instructions("How well does `assistant_answer` address the question in `user_question`?")
							.level("Terrible: irrelevant to the question, or almost entirely missing")
							.level("Mostly unhelpful: misses key aspects of the question")
							.level("Mostly helpful: answers the question but could be improved")
							.level("Excellent: relevant, direct and addresses every concern raised")
							.build(),
						2.0d)
				.noul("is_plausible", Noul.builder()
					.instructions(
							"Are all the numeric values in `assistant_answer` physically plausible for their units?")
					.whenTrue("Every value is within a range that can actually occur")
					.whenFalse("At least one value is impossible, such as a temperature below absolute zero "
							+ "or far outside anything ever recorded on Earth")
					.build(), 0.7d)
				.noul("is_grounded",
						Noul.builder()
							.instructions(Map.of("question",
									"Does `assistant_answer` stay within what `user_question` asked, without asserting "
											+ "unrelated facts?",
									"note",
									"The assistant has a weather tool, so specific weather values for the place asked "
											+ "about are expected and are not themselves unsupported."))
							.whenTrue("Answers the question asked, adding no unrelated factual claims")
							.whenFalse("Introduces facts that appear nowhere in the question")
							.build(),
						0.5d)
				// minConfidence keeps its default: a clear majority (60%) of a score's
				// probability must support the verdict. Below that the rubric did not
				// settle pass or fail for this answer, which is reported as undecided
				// rather than as a failure.
				.build();
		}

	}

}
