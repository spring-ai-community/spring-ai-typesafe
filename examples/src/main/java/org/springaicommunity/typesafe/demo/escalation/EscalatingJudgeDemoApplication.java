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

package org.springaicommunity.typesafe.demo.escalation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.judge.ChatModelEscalation;
import org.springaicommunity.typesafe.judge.JevEscalation;
import org.springaicommunity.typesafe.judge.JevFinding;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.judge.JevVerdict;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Accept when confident, escalate when unsure.
 *
 * <p>
 * Jev judges four answers to arithmetic questions on three criteria. Where its
 * probability clearly supports a verdict, that verdict stands. Where it does not — on the
 * answers whose working reads well but ends wrong — the criterion is escalated to a chat
 * model, which decides it and says why. The same answers are judged by Jev alone
 * alongside, so each line shows what escalating changed.
 *
 * <p>
 * The pattern follows Li et al., <em>JEV-as-a-Judge: Accept When Confident, Escalate When
 * Unsure</em> (arXiv:2609.26550). The escalation threshold is the default, 0.9; the right
 * value depends on the workload.
 *
 * <p>
 * Needs {@code TYPESAFE_API_KEY} and {@code ANTHROPIC_API_KEY} in the environment.
 *
 * @author Christian Tzolov
 */
@SpringBootApplication
public class EscalatingJudgeDemoApplication {

	private static final String PENS = "A shop sells pens at 3 for 2.40. How much do 7 pens cost?";

	private static final String TRAIN = "A train leaves at 14:35 and the journey takes 2 h 50 min. When does it arrive?";

	private static final String PERCENT = "What is 15% of 240?";

	/**
	 * One terse, right answer Jev is sure of; three whose working reads well and ends
	 * wrong.
	 */
	private static final List<Case> CASES = List.of(new Case(PENS, "Each pen is 0.80, so 7 pens cost 5.60."),
			new Case(PENS,
					"Three pens cost 2.40, so six pens cost 4.80. One more pen at the single price of 0.80 "
							+ "makes 5.60. However, shops usually only apply the bundle price to full bundles, so the "
							+ "seventh pen is charged at the standard unit price of 0.90, giving a total of 5.70."),
			new Case(TRAIN, "Let's work through it carefully. Start with the hours: 14:35 plus 2 hours brings us to "
					+ "16:35. Now the minutes: 35 + 50 = 85 minutes, and 85 minutes is one hour and 25 minutes, "
					+ "so we carry the hour and land on 17:25. But note that most European timetables round "
					+ "departures to the nearest five minutes, so the scheduled arrival would be shown as 17:15. "
					+ "Final answer: 17:15."),
			new Case(PERCENT, "10% of 240 is 24 and 5% is 12, so 15% is 34."));

	public static void main(String[] args) {
		SpringApplication.run(EscalatingJudgeDemoApplication.class, args);
	}

	@Bean
	CommandLineRunner cli(TypeSafeClient typeSafeClient, ChatModel chatModel) {
		return args -> {
			RecordingEscalation escalation = new RecordingEscalation(ChatModelEscalation.builder(chatModel).build());
			JevJudge jevAlone = criteria(JevJudge.builder(typeSafeClient)).build();
			JevJudge cascade = criteria(JevJudge.builder(typeSafeClient)).escalateTo(escalation).build();

			int escalated = 0;
			int changed = 0;
			for (Case c : CASES) {
				escalation.reasons.clear();
				JevVerdict alone = jevAlone.judge(c.question(), c.answer());
				JevVerdict withEscalation = cascade.judge(c.question(), c.answer());

				System.out.println("─".repeat(100));
				System.out.println("Q: " + c.question());
				System.out.println("A: " + abbreviate(c.answer(), 94));
				for (int i = 0; i < alone.findings().size(); i++) {
					JevFinding before = alone.findings().get(i);
					JevFinding after = withEscalation.findings().get(i);
					String how = "kept";
					if (after.escalated()) {
						escalated++;
						how = "escalated: " + escalation.reasons.getOrDefault(after.name(), "");
					}
					if (before.outcome() != after.outcome()) {
						changed++;
					}
					System.out.printf("  %-12s jev %-8s %-5s  cascade %-8s %s%n", after.name(), before.outcome(),
							value(before), after.outcome(), abbreviate(how, 60));
				}
			}
			System.out.println("─".repeat(100));
			System.out.printf("%d criteria: %d escalated to the chat model, %d verdicts changed.%n", 3 * CASES.size(),
					escalated, changed);
					
			System.out.println("""
					Jev is sure of the terse right answer and of the plainly wrong parts, at no extra cost.
					What it escalates is the fluent working that ends wrong: the case a stronger
					judge earns its fee on.""");
		};
	}

	/**
	 * Correctness is asked without a reference answer on purpose: that is where Jev has
	 * to check the working itself, and where it is least sure.
	 */
	private static JevJudge.Builder criteria(JevJudge.Builder builder) {
		return builder
			.noul("is_correct",
					Noul.builder()
						.instructions("Is the final result stated in `assistant_answer` the right answer to "
								+ "`user_question`? Check the arithmetic yourself rather than trusting the "
								+ "answer's own working.")
						.whenTrue("The final result is right")
						.whenFalse("The final result is wrong, whatever the working looks like")
						.build(),
					0.7d)
			.noul("is_grounded",
					Noul.builder()
						.instructions("Does `assistant_answer` stay within what `user_question` asked, without "
								+ "asserting unrelated facts?")
						.whenTrue("Answers only what was asked")
						.whenFalse("Introduces facts that appear nowhere in the question")
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
					2.0d);
	}

	private static String value(JevFinding finding) {
		if (finding.answer() instanceof NoulAnswer noul) {
			return "%.2f".formatted(noul.value());
		}
		if (finding.answer() instanceof ScoreAnswer score) {
			return "%.2f".formatted(score.value());
		}
		return "";
	}

	private static String abbreviate(String text, int width) {
		return text.length() <= width ? text : text.substring(0, width - 1) + "…";
	}

	record Case(String question, String answer) {
	}

	/**
	 * Keeps the chat model's reason for each decision so it can be printed. A
	 * {@link JevEscalation} is a functional interface, so wrapping one is a few lines.
	 */
	static final class RecordingEscalation implements JevEscalation {

		private final JevEscalation delegate;

		final Map<String, String> reasons = new LinkedHashMap<>();

		RecordingEscalation(JevEscalation delegate) {
			this.delegate = delegate;
		}

		@Override
		public Decision decide(String name, Question question, JsonContent state) {
			Decision decision = this.delegate.decide(name, question, state);
			if (decision.reason() != null) {
				this.reasons.put(name, decision.reason());
			}
			return decision;
		}

	}

}
