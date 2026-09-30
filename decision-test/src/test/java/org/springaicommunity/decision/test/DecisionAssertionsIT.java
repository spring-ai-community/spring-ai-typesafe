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

package org.springaicommunity.decision.test;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springaicommunity.decision.test.DecisionAssertions.assertThatAnswer;

/**
 * The plain-language assertions against the real Jev API. Skipped by {@link DecisionTest}
 * unless {@code TYPESAFE_API_KEY} is set, and run only under
 * {@code -Pintegration-tests}.
 *
 * @author Christian Tzolov
 */
@DecisionTest
class DecisionAssertionsIT {

	private static final String QUESTION = "What is the weather in Paris?";

	private static final String ADDRESSES_THE_QUESTION = "Does `assistant_answer` directly answer `user_question`?";

	private static final String PLAUSIBLE = "Are all the numeric values in `assistant_answer` physically plausible for their units?";

	@Test
	void passesAGoodAnswer() {
		assertThatAnswer("It is 15 degrees Celsius and overcast in Paris.").givenQuestion(QUESTION)
			.satisfies(ADDRESSES_THE_QUESTION)
			.satisfies(PLAUSIBLE)
			.scores("How helpful is `assistant_answer` for `user_question`?", "Unhelpful", "Partly helpful",
					"Helpful")
			.atLeast("Partly helpful")
			.judge();
	}

	@Test
	void catchesAFluentButImpossibleAnswer() {
		assertThatAnswer("It is currently -455 degrees Celsius in Paris.").givenQuestion(QUESTION)
			.satisfies(ADDRESSES_THE_QUESTION)
			.satisfies(PLAUSIBLE)
			.evaluate()
			.failed()
			.failedOn(PLAUSIBLE);
	}

	@Test
	void classifiesTheIntentOfATicket() {
		String team = "Which team should handle the request in `assistant_answer`?";
		assertThatAnswer("Help! My payouts have been failing for 3 days and I was charged twice.")
			.isClassifiedAs(team, "billing")
			.among("billing", "technical", "sales")
			.judge()
			.finding(team)
			.choiceAnswer()
			.chose("billing");
	}

	@Test
	void resolvesTheClientTheAssertionsJudgeWith(TypeSafeClient client) {
		assertThat(client).isNotNull();
	}

}
