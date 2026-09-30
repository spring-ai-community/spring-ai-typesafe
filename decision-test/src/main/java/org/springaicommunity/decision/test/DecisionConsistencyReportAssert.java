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
import java.util.stream.Collectors;

import org.assertj.core.api.AbstractObjectAssert;
import org.springaicommunity.typesafe.judge.JevConsistency;
import org.springaicommunity.typesafe.judge.JevConsistency.Statistics;

import static org.springaicommunity.decision.test.Messages.format;

/**
 * Assertions on a {@link JevConsistency.Report}: whether a question's answers stay on one
 * side of the threshold your code applies to them.
 *
 * @author Christian Tzolov
 */
public class DecisionConsistencyReportAssert extends AbstractObjectAssert<DecisionConsistencyReportAssert, JevConsistency.Report> {

	public DecisionConsistencyReportAssert(JevConsistency.Report actual) {
		super(actual, DecisionConsistencyReportAssert.class);
	}

	/**
	 * Verifies that no question's samples fall on both sides of the threshold, so the
	 * decision it drives is reproducible.
	 * @param threshold the threshold applied to the answers
	 * @return this assertion
	 */
	public DecisionConsistencyReportAssert isStableAt(double threshold) {
		isNotNull();
		List<String> unstable = this.actual.unstableAt(threshold);
		if (!unstable.isEmpty()) {
			String detail = unstable.stream()
				.map(name -> describe(this.actual.statistics().get(name)))
				.collect(Collectors.joining(System.lineSeparator()));
			failWithMessage("%s", format("%nExpected every question to stay on one side of %.2f, but %s straddled it:%n%s",
					threshold, unstable, detail));
		}
		return this;
	}

	/**
	 * @param name the question name
	 * @param maximum the inclusive upper bound on the population standard deviation
	 * @return this assertion
	 */
	public DecisionConsistencyReportAssert hasStandardDeviationAtMost(String name, double maximum) {
		isNotNull();
		Statistics statistics = this.actual.statistics().get(name);
		if (statistics == null) {
			failWithMessage("%s", format("%nExpected statistics for \"%s\", but the report has %s", name,
					this.actual.statistics().keySet()));
		}
		else if (statistics.standardDeviation() > maximum) {
			failWithMessage("%s", format("%nExpected a standard deviation of at most %.3f, but was:%n%s", maximum,
					describe(statistics)));
		}
		return this;
	}

	/**
	 * @return this assertion
	 */
	public DecisionConsistencyReportAssert hasNoFailedSamples() {
		isNotNull();
		if (!this.actual.failures().isEmpty()) {
			failWithMessage("%s", format("%nExpected every sample to come back, but %d of %d failed",
					this.actual.failures().size(), this.actual.failures().size() + this.actual.samples().size()));
		}
		return this;
	}

	private static String describe(Statistics statistics) {
		return format("  - %s: min %.3f, max %.3f, mean %.3f, sd %.3f over %d samples", statistics.name(),
				statistics.min(), statistics.max(), statistics.mean(), statistics.standardDeviation(),
				statistics.values().size());
	}

}
