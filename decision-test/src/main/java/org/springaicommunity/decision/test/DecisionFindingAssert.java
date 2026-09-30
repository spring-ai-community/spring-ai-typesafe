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
import org.springaicommunity.typesafe.judge.JevFinding;
import org.springaicommunity.typesafe.judge.JevFinding.Outcome;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;

import static org.springaicommunity.decision.test.Messages.format;

/**
 * Assertions on one criterion's {@link JevFinding}.
 *
 * @author Christian Tzolov
 */
public class DecisionFindingAssert extends AbstractObjectAssert<DecisionFindingAssert, JevFinding> {

	public DecisionFindingAssert(JevFinding actual) {
		super(actual, DecisionFindingAssert.class);
	}

	/**
	 * @param outcome the expected outcome
	 * @return this assertion
	 */
	public DecisionFindingAssert hasOutcome(Outcome outcome) {
		isNotNull();
		if (this.actual.outcome() != outcome) {
			failWithMessage("%s", format("%nExpected \"%s\" to be %s, but was:%n%s", this.actual.name(), outcome,
					Messages.line(this.actual)));
		}
		return this;
	}

	public DecisionFindingAssert isPassed() {
		return hasOutcome(Outcome.PASSED);
	}

	public DecisionFindingAssert isFailed() {
		return hasOutcome(Outcome.FAILED);
	}

	public DecisionFindingAssert isInconclusive() {
		return hasOutcome(Outcome.INCONCLUSIVE);
	}

	public DecisionFindingAssert isNotApplicable() {
		return hasOutcome(Outcome.NOT_APPLICABLE);
	}

	/**
	 * Verifies the confidence of a choice or score answer. Nouls carry no confidence;
	 * their value already says how sure the model is.
	 * @param minimum the inclusive lower bound
	 * @return this assertion
	 */
	public DecisionFindingAssert hasConfidenceAtLeast(double minimum) {
		isNotNull();
		Answer answer = this.actual.answer();
		if (answer instanceof ChoiceAnswer choice) {
			new ChoiceAnswerAssert(choice).as("\"%s\"", this.actual.name()).hasConfidenceAtLeast(minimum);
		}
		else if (answer instanceof ScoreAnswer score) {
			new ScoreAnswerAssert(score).as("\"%s\"", this.actual.name()).hasConfidenceAtLeast(minimum);
		}
		else {
			failWithMessage("%s", format("%nExpected \"%s\" to carry a confidence, but its answer is %s",
					this.actual.name(), answer == null ? "absent (the criterion was not asked)"
							: "a " + answer.getClass().getSimpleName() + ", which has none"));
		}
		return this;
	}

	/**
	 * @param text text the detail must contain
	 * @return this assertion
	 */
	public DecisionFindingAssert hasDetailContaining(String text) {
		isNotNull();
		if (!this.actual.detail().contains(text)) {
			failWithMessage("%s", format("%nExpected the detail of \"%s\" to contain \"%s\", but was \"%s\"",
					this.actual.name(), text, this.actual.detail()));
		}
		return this;
	}

	/**
	 * @return an assertion on the answer, which must be a noul
	 */
	public NoulAnswerAssert noulAnswer() {
		return new NoulAnswerAssert(answer(NoulAnswer.class)).as("\"%s\"", this.actual.name());
	}

	/**
	 * @return an assertion on the answer, which must be a choice
	 */
	public ChoiceAnswerAssert choiceAnswer() {
		return new ChoiceAnswerAssert(answer(ChoiceAnswer.class)).as("\"%s\"", this.actual.name());
	}

	/**
	 * @return an assertion on the answer, which must be a score
	 */
	public ScoreAnswerAssert scoreAnswer() {
		return new ScoreAnswerAssert(answer(ScoreAnswer.class)).as("\"%s\"", this.actual.name());
	}

	private <T extends Answer> T answer(Class<T> type) {
		isNotNull();
		Answer answer = this.actual.answer();
		if (!type.isInstance(answer)) {
			failWithMessage("%s", format("%nExpected \"%s\" to have a %s, but its answer is %s", this.actual.name(),
					type.getSimpleName(), answer == null ? "absent" : answer.getClass().getSimpleName()));
		}
		return type.cast(answer);
	}

}
