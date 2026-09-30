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

import org.assertj.core.api.AbstractDoubleAssert;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.Assertions;
import org.springaicommunity.typesafe.response.ScoreAnswer;

import static org.springaicommunity.decision.test.Messages.format;

/**
 * Assertions on a {@link ScoreAnswer}. A score is a position on the rubric's own levels,
 * {@code 0} to the number of levels minus one, not a value between {@code 0} and
 * {@code 1}.
 *
 * @author Christian Tzolov
 */
public class ScoreAnswerAssert extends AbstractObjectAssert<ScoreAnswerAssert, ScoreAnswer> {

	public ScoreAnswerAssert(ScoreAnswer actual) {
		super(actual, ScoreAnswerAssert.class);
	}

	/**
	 * Verifies the expected value of the score. A judge decides a score from the
	 * probability on the passing levels instead; the two agree unless the distribution
	 * is spread.
	 * @param minimum the inclusive lower bound, in levels
	 * @return this assertion
	 */
	public ScoreAnswerAssert isAtLeast(double minimum) {
		isNotNull();
		if (this.actual.value() < minimum) {
			failWithMessage("%s", format("%nExpected the score to be at least %.2f, but was %.2f (\"%s\")", minimum,
					this.actual.value(), this.actual.nearestLabel()));
		}
		return this;
	}

	/**
	 * @param maximum the inclusive upper bound, in levels
	 * @return this assertion
	 */
	public ScoreAnswerAssert isAtMost(double maximum) {
		isNotNull();
		if (this.actual.value() > maximum) {
			failWithMessage("%s", format("%nExpected the score to be at most %.2f, but was %.2f (\"%s\")", maximum,
					this.actual.value(), this.actual.nearestLabel()));
		}
		return this;
	}

	/**
	 * @param minimum the inclusive lower bound, in levels
	 * @param maximum the inclusive upper bound, in levels
	 * @return this assertion
	 */
	public ScoreAnswerAssert isBetween(double minimum, double maximum) {
		return isAtLeast(minimum).isAtMost(maximum);
	}

	/**
	 * Verifies the level carrying the highest probability.
	 * @param level the expected level
	 * @return this assertion
	 */
	public ScoreAnswerAssert hasNearestLevel(int level) {
		isNotNull();
		if (this.actual.nearestLevel() != level) {
			failWithMessage("%s", format("%nExpected the most probable level to be %d, but was %d (\"%s\")%n  probabilities: %s",
					level, this.actual.nearestLevel(), this.actual.nearestLabel(), this.actual.probabilities()));
		}
		return this;
	}

	/**
	 * @param minimum the inclusive lower bound on how concentrated the distribution is
	 * @return this assertion
	 */
	public ScoreAnswerAssert hasConfidenceAtLeast(double minimum) {
		isNotNull();
		if (this.actual.confidence() < minimum) {
			failWithMessage("%s", format("%nExpected a confidence of at least %.2f, but was %.2f%n  probabilities: %s",
					minimum, this.actual.confidence(), this.actual.probabilities()));
		}
		return this;
	}

	/**
	 * @return an assertion on the expected value itself
	 */
	public AbstractDoubleAssert<?> value() {
		isNotNull();
		return Assertions.assertThat(this.actual.value()).as(descriptionText());
	}

}
