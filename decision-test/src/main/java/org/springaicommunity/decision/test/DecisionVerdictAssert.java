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
import java.util.Set;

import org.assertj.core.api.AbstractObjectAssert;
import org.assertj.core.api.AbstractStringAssert;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.Assumptions;
import org.springaicommunity.typesafe.judge.JevCriterion.QuestionCriterion;
import org.springaicommunity.typesafe.judge.JevFinding;
import org.springaicommunity.typesafe.judge.JevFinding.Outcome;
import org.springaicommunity.typesafe.judge.JevVerdict;

import static org.springaicommunity.decision.test.Messages.format;

/**
 * Assertions on a {@link JevVerdict}. A failure message lists every criterion that did not
 * pass with the detail the judge synthesised for it, so a red build says which criterion
 * failed and by how much, not just {@code expected true but was false}.
 *
 * <p>
 * {@link #passed()} is stricter than {@link JevVerdict#passed()}: in a test, an
 * inconclusive criterion or one the service could not answer is not a pass, because the
 * oracle never decided it. Use {@link #passedOrAborted()} to report those as aborted
 * rather than failed.
 *
 * @author Christian Tzolov
 */
public class DecisionVerdictAssert extends AbstractObjectAssert<DecisionVerdictAssert, JevVerdict> {

	private static final Set<Outcome> UNDECIDED = Set.of(Outcome.INCONCLUSIVE, Outcome.ERROR);

	public DecisionVerdictAssert(JevVerdict actual) {
		super(actual, DecisionVerdictAssert.class);
	}

	/**
	 * Verifies that every criterion passed or did not apply: none failed, none was
	 * inconclusive and none errored.
	 * @return this assertion
	 */
	public DecisionVerdictAssert passed() {
		isNotNull();
		List<JevFinding> blocking = this.actual.findings()
			.stream()
			.filter(finding -> finding.outcome() == Outcome.FAILED || UNDECIDED.contains(finding.outcome()))
			.toList();
		if (!blocking.isEmpty()) {
			failWithMessage("%s", format("%nExpected the Jev verdict to pass, but %s did not:%n%s",
					Messages.names(blocking), Messages.report(this.actual)));
		}
		return this;
	}

	/**
	 * Like {@link #passed()}, but a verdict held back only by inconclusive or errored
	 * criteria aborts the test instead of failing it: the oracle could not decide, which
	 * says nothing about the answer. A failed criterion still fails the test.
	 * @return this assertion
	 */
	public DecisionVerdictAssert passedOrAborted() {
		isNotNull();
		List<JevFinding> failed = this.actual.failures();
		if (!failed.isEmpty()) {
			failWithMessage("%s", format("%nExpected the Jev verdict to pass, but %s failed:%n%s",
					Messages.names(failed), Messages.report(this.actual)));
		}
		List<JevFinding> undecided = this.actual.findings()
			.stream()
			.filter(finding -> UNDECIDED.contains(finding.outcome()))
			.toList();
		// AssertJ picks the aborting exception of whichever test framework is present.
		Assumptions.assumeThat(undecided.isEmpty())
			.as(format("Jev could not decide %s:%n%s", Messages.names(undecided), Messages.report(this.actual)))
			.isTrue();
		return this;
	}

	/**
	 * Verifies that the verdict did not pass, as {@link JevVerdict#passed()} reports it.
	 * @return this assertion
	 */
	public DecisionVerdictAssert failed() {
		isNotNull();
		if (this.actual.passed()) {
			failWithMessage("%s", format("%nExpected the Jev verdict to fail, but it passed:%n%s",
					Messages.report(this.actual)));
		}
		return this;
	}

	/**
	 * @param names criteria that must have failed, by name or by their question as
	 * written
	 * @return this assertion
	 */
	public DecisionVerdictAssert failedOn(String... names) {
		for (String name : names) {
			finding(name).hasOutcome(Outcome.FAILED);
		}
		return this;
	}

	/**
	 * @param names criteria that must have passed, by name or by their question as
	 * written
	 * @return this assertion
	 */
	public DecisionVerdictAssert passedOn(String... names) {
		for (String name : names) {
			finding(name).hasOutcome(Outcome.PASSED);
		}
		return this;
	}

	/**
	 * @return this assertion
	 */
	public DecisionVerdictAssert hasNoInconclusive() {
		return hasNone(Outcome.INCONCLUSIVE);
	}

	/**
	 * @return this assertion
	 */
	public DecisionVerdictAssert hasNoErrors() {
		return hasNone(Outcome.ERROR);
	}

	private DecisionVerdictAssert hasNone(Outcome outcome) {
		isNotNull();
		List<JevFinding> matching = this.actual.findings()
			.stream()
			.filter(finding -> finding.outcome() == outcome)
			.toList();
		if (!matching.isEmpty()) {
			failWithMessage("%s", format("%nExpected no %s criteria, but found %s:%n%s", outcome,
					Messages.names(matching), Messages.report(this.actual)));
		}
		return this;
	}

	/**
	 * Finds a criterion by its name or, for a criterion declared in plain language, by
	 * the words it was declared with.
	 * @param name the criterion name, or its question as written
	 * @return an assertion on that criterion's finding
	 */
	public DecisionFindingAssert finding(String name) {
		isNotNull();
		JevFinding finding = this.actual.findings()
			.stream()
			.filter(candidate -> candidate.name().equals(name))
			.findFirst()
			.or(() -> this.actual.findings().stream().filter(candidate -> asks(candidate, name)).findFirst())
			.orElse(null);
		if (finding == null) {
			failWithMessage("%s", format("%nExpected a criterion named \"%s\", but the verdict has %s", name,
					Messages.names(this.actual.findings())));
		}
		return new DecisionFindingAssert(finding);
	}

	private static boolean asks(JevFinding finding, String question) {
		return finding.criterion() instanceof QuestionCriterion criterion
				&& criterion.question().instructions() != null
				&& question.equals(criterion.question().instructions().toDisplayString());
	}

	/**
	 * @return an assertion on the synthesised feedback, empty when the verdict passed
	 */
	public AbstractStringAssert<?> feedback() {
		isNotNull();
		return Assertions.assertThat(this.actual.feedback()).as("Jev feedback");
	}

	/**
	 * @return an assertion on the raw response, for probabilities and confidence
	 */
	public SystemOneResponseAssert response() {
		isNotNull();
		if (this.actual.response() == null) {
			failWithMessage("%s", format("%nExpected a response, but no question was asked:%n%s",
					Messages.report(this.actual)));
		}
		return new SystemOneResponseAssert(this.actual.response());
	}

}
