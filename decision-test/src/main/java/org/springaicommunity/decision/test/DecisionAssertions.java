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

import org.springaicommunity.typesafe.judge.JevConsistency;
import org.springaicommunity.typesafe.judge.JevFinding;
import org.springaicommunity.typesafe.judge.JevVerdict;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.Assert;

/**
 * Entry point for the Jev assertions. Statically import it next to AssertJ's own
 * {@code Assertions}; the overloads here only take Jev types, so the two do not clash.
 *
 * <p>
 * Two kinds of assertion live here:
 * <ul>
 * <li>{@code assertThat(...)} on a result you already have — a {@link JevVerdict}, a
 * {@link SystemOneResponse} or one of its answers, a {@link JevConsistency.Report}. These
 * never call Jev.</li>
 * <li>{@link #assertThatAnswer(String)}, which judges a model's answer against criteria
 * written in plain language, in a single Jev call.</li>
 * </ul>
 *
 * @author Christian Tzolov
 */
public final class DecisionAssertions {

	private DecisionAssertions() {
	}

	/**
	 * Starts judging an answer. Declare criteria, then call {@link DecisionAnswerAssert#judge()}.
	 * @param answer the answer under test
	 * @return the assertion
	 */
	public static DecisionAnswerAssert assertThatAnswer(String answer) {
		return new DecisionAnswerAssert(answer);
	}

	/**
	 * Starts judging the text of a chat response's first generation.
	 * @param response the chat response under test
	 * @return the assertion
	 */
	public static DecisionAnswerAssert assertThatAnswer(ChatResponse response) {
		Assert.notNull(response, "response must not be null");
		Generation generation = response.getResult();
		Assert.notNull(generation, "response must carry a generation");
		String text = generation.getOutput().getText();
		Assert.notNull(text, "the generation must carry text");
		return new DecisionAnswerAssert(text);
	}

	public static DecisionVerdictAssert assertThat(JevVerdict actual) {
		return new DecisionVerdictAssert(actual);
	}

	public static DecisionFindingAssert assertThat(JevFinding actual) {
		return new DecisionFindingAssert(actual);
	}

	public static SystemOneResponseAssert assertThat(SystemOneResponse actual) {
		return new SystemOneResponseAssert(actual);
	}

	public static NoulAnswerAssert assertThat(NoulAnswer actual) {
		return new NoulAnswerAssert(actual);
	}

	public static ChoiceAnswerAssert assertThat(ChoiceAnswer actual) {
		return new ChoiceAnswerAssert(actual);
	}

	public static ScoreAnswerAssert assertThat(ScoreAnswer actual) {
		return new ScoreAnswerAssert(actual);
	}

	public static DecisionConsistencyReportAssert assertThat(JevConsistency.Report actual) {
		return new DecisionConsistencyReportAssert(actual);
	}

}
