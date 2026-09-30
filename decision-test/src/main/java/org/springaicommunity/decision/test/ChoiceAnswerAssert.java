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

import java.util.List;

import org.assertj.core.api.AbstractDoubleAssert;
import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.Assertions;
import org.springaicommunity.typesafe.response.ChoiceAnswer;

import static org.springaicommunity.decision.test.Messages.format;

/**
 * Assertions on a {@link ChoiceAnswer}: the selected label, the probability on each
 * option and the confidence.
 *
 * @author Christian Tzolov
 */
public class ChoiceAnswerAssert extends AbstractObjectAssert<ChoiceAnswerAssert, ChoiceAnswer> {

	public ChoiceAnswerAssert(ChoiceAnswer actual) {
		super(actual, ChoiceAnswerAssert.class);
	}

	/**
	 * Verifies the selected label, the single most probable option.
	 * @param label the expected label
	 * @return this assertion
	 */
	public ChoiceAnswerAssert chose(String label) {
		isNotNull();
		if (!label.equals(this.actual.value())) {
			failWithMessage("%s", format("%nExpected the choice \"%s\", but was \"%s\"%n  probabilities: %s", label,
					this.actual.value(), this.actual.probabilities()));
		}
		return this;
	}

	/**
	 * @param labels the acceptable labels
	 * @return this assertion
	 */
	public ChoiceAnswerAssert choseOneOf(String... labels) {
		isNotNull();
		if (!List.of(labels).contains(this.actual.value())) {
			failWithMessage("%s", format("%nExpected the choice to be one of %s, but was \"%s\"%n  probabilities: %s",
					List.of(labels), this.actual.value(), this.actual.probabilities()));
		}
		return this;
	}

	/**
	 * Verifies how concentrated the distribution is. A judge decides a choice from the
	 * probability on the accepted options, not from this statistic; assert on
	 * {@link #hasProbabilityAtLeast(String, double)} to follow the same rule.
	 * @param minimum the inclusive lower bound
	 * @return this assertion
	 */
	public ChoiceAnswerAssert hasConfidenceAtLeast(double minimum) {
		isNotNull();
		if (this.actual.confidence() < minimum) {
			failWithMessage("%s", format("%nExpected a confidence of at least %.2f, but was %.2f%n  probabilities: %s",
					minimum, this.actual.confidence(), this.actual.probabilities()));
		}
		return this;
	}

	/**
	 * @param option the option label
	 * @param minimum the inclusive lower bound
	 * @return this assertion
	 */
	public ChoiceAnswerAssert hasProbabilityAtLeast(String option, double minimum) {
		isNotNull();
		double probability = this.actual.probabilityOf(option);
		if (probability < minimum) {
			failWithMessage("%s", format("%nExpected at least %.2f of the probability on \"%s\", but was %.2f%n  probabilities: %s",
					minimum, option, probability, this.actual.probabilities()));
		}
		return this;
	}

	/**
	 * @param option the option label
	 * @return an assertion on the probability of that option, {@code 0} when absent
	 */
	public AbstractDoubleAssert<?> probabilityOf(String option) {
		isNotNull();
		return Assertions.assertThat(this.actual.probabilityOf(option)).as("probability of \"%s\"", option);
	}

}
