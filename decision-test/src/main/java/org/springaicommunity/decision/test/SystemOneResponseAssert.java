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

import org.assertj.core.api.AbstractObjectAssert;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import static org.springaicommunity.decision.test.Messages.format;

/**
 * Assertions on a raw {@link SystemOneResponse}, for pinning down how your own questions
 * are answered for a known state:
 *
 * <pre>{@code
 * assertThat(response).choice("department").chose("billing").hasConfidenceAtLeast(0.8);
 * assertThat(response).noul("is_urgent").isAtLeast(0.7);
 * }</pre>
 *
 * @author Christian Tzolov
 */
public class SystemOneResponseAssert extends AbstractObjectAssert<SystemOneResponseAssert, SystemOneResponse> {

	public SystemOneResponseAssert(SystemOneResponse actual) {
		super(actual, SystemOneResponseAssert.class);
	}

	/**
	 * @param names the question names that must have an answer
	 * @return this assertion
	 */
	public SystemOneResponseAssert hasAnswers(String... names) {
		isNotNull();
		for (String name : names) {
			if (!this.actual.answers().containsKey(name)) {
				failWithMessage("%s", format("%nExpected an answer named \"%s\", but the response only has %s", name,
						this.actual.answers().keySet()));
			}
		}
		return this;
	}

	/**
	 * @param name the question name
	 * @return an assertion on that noul answer
	 */
	public NoulAnswerAssert noul(String name) {
		return new NoulAnswerAssert(answer(name, NoulAnswer.class)).as("noul \"%s\"", name);
	}

	/**
	 * @param name the question name
	 * @return an assertion on that choice answer
	 */
	public ChoiceAnswerAssert choice(String name) {
		return new ChoiceAnswerAssert(answer(name, ChoiceAnswer.class)).as("choice \"%s\"", name);
	}

	/**
	 * @param name the question name
	 * @return an assertion on that score answer
	 */
	public ScoreAnswerAssert score(String name) {
		return new ScoreAnswerAssert(answer(name, ScoreAnswer.class)).as("score \"%s\"", name);
	}

	private <T extends Answer> T answer(String name, Class<T> type) {
		hasAnswers(name);
		Answer answer = this.actual.answers().get(name);
		if (!type.isInstance(answer)) {
			failWithMessage("%s", format("%nExpected \"%s\" to be a %s, but was %s", name, type.getSimpleName(),
					answer.getClass().getSimpleName()));
		}
		return type.cast(answer);
	}

}
