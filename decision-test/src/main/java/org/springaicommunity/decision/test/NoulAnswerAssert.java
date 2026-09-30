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
import org.springaicommunity.typesafe.response.NoulAnswer;

import static org.springaicommunity.decision.test.Messages.format;

/**
 * Assertions on a {@link NoulAnswer}, a truth value between {@code 0} and {@code 1}.
 *
 * @author Christian Tzolov
 */
public class NoulAnswerAssert extends AbstractObjectAssert<NoulAnswerAssert, NoulAnswer> {

	public NoulAnswerAssert(NoulAnswer actual) {
		super(actual, NoulAnswerAssert.class);
	}

	/**
	 * Verifies that the truth value is at least the given minimum, the same test
	 * {@link NoulAnswer#isTrue(double)} and a judge's noul criterion apply.
	 * @param minimum the inclusive lower bound
	 * @return this assertion
	 */
	public NoulAnswerAssert isAtLeast(double minimum) {
		isNotNull();
		if (!this.actual.isTrue(minimum)) {
			failWithMessage("%s", format("%nExpected the noul to be at least %.2f, but was %.2f", minimum,
					this.actual.value()));
		}
		return this;
	}

	/**
	 * @param maximum the inclusive upper bound
	 * @return this assertion
	 */
	public NoulAnswerAssert isAtMost(double maximum) {
		isNotNull();
		if (this.actual.value() > maximum) {
			failWithMessage("%s", format("%nExpected the noul to be at most %.2f, but was %.2f", maximum,
					this.actual.value()));
		}
		return this;
	}

	/**
	 * Verifies that the answer leans yes: {@link NoulAnswer#isTrue()}.
	 * @return this assertion
	 */
	public NoulAnswerAssert isTrue() {
		isNotNull();
		if (!this.actual.isTrue()) {
			failWithMessage("%s", format("%nExpected the noul to lean true (at least 0.50), but was %.2f",
					this.actual.value()));
		}
		return this;
	}

	/**
	 * Verifies that the answer leans no: the negation of {@link NoulAnswer#isTrue()}.
	 * @return this assertion
	 */
	public NoulAnswerAssert isFalse() {
		isNotNull();
		if (this.actual.isTrue()) {
			failWithMessage("%s", format("%nExpected the noul to lean false (below 0.50), but was %.2f",
					this.actual.value()));
		}
		return this;
	}

	/**
	 * @return an assertion on the truth value itself
	 */
	public AbstractDoubleAssert<?> value() {
		isNotNull();
		return Assertions.assertThat(this.actual.value()).as(descriptionText());
	}

}
