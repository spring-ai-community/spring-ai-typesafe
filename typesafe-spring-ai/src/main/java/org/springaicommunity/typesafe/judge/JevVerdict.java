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

package org.springaicommunity.typesafe.judge;



import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springaicommunity.typesafe.response.SystemOneResponse;

/**
 * The outcome of one judging round: every criterion's finding, whether the response is
 * acceptable, and the feedback to hand back to the model when it is not.
 *
 * <p>
 * Jev never returns prose, so {@link #feedback()} is synthesised from the structured
 * answers. That makes it deterministic, and more specific than a judge model's own
 * commentary: it names the criterion, the level reached and the level required.
 *
 * @param passed whether every criterion was met
 * @param findings one finding per criterion, in declaration order
 * @param response the raw response, kept so callers can read probabilities, confidence,
 * usage and the request id; {@code null} when no question was asked — every question
 * criterion was not applicable, or {@code failFast} skipped the call
 * @param feedback the defects as text, empty when the verdict passed
 * @author Christian Tzolov
 */
public record JevVerdict(boolean passed, List<JevFinding> findings, @Nullable SystemOneResponse response,
		String feedback) {

	public JevVerdict {
		findings = List.copyOf(findings);
	}

	/**
	 * @return only the findings that blocked the response
	 */
	public List<JevFinding> failures() {
		return this.findings.stream().filter(JevFinding::isFailure).toList();
	}

	/**
	 * @return the findings the model could not decide with enough confidence
	 */
	public List<JevFinding> inconclusive() {
		return this.findings.stream()
			.filter(finding -> finding.outcome() == JevFinding.Outcome.INCONCLUSIVE)
			.toList();
	}

	/**
	 * @return the findings the service could not answer
	 */
	public List<JevFinding> errors() {
		return this.findings.stream().filter(finding -> finding.outcome() == JevFinding.Outcome.ERROR).toList();
	}

	/**
	 * @return the findings that were not asked
	 */
	public List<JevFinding> notApplicable() {
		return this.findings.stream()
			.filter(finding -> finding.outcome() == JevFinding.Outcome.NOT_APPLICABLE)
			.toList();
	}

	/**
	 * A one line summary suitable for a log, for example
	 * {@code passed=false [helpfulness=FAILED, is_plausible=FAILED]}.
	 * @return the summary
	 */
	public String summary() {
		String perCriterion = this.findings.stream()
			.map(finding -> "%s=%s".formatted(finding.name(), finding.outcome()))
			.reduce((a, b) -> a + ", " + b)
			.orElse("");
		return "passed=%s [%s]".formatted(this.passed, perCriterion);
	}

}
