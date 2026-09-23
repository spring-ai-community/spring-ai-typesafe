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

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.response.Answer;

/**
 * What one {@link JevCriterion} concluded about a response.
 *
 * @param criterion the criterion that was checked
 * @param answer the Jev model's answer; {@code null} for a
 * {@link JevCriterion.CodeCriterion}, which is answered in code, and for a criterion that
 * was {@link Outcome#NOT_APPLICABLE}
 * @param outcome whether the criterion passed, failed or could not be decided
 * @param detail a sentence naming the defect, ready to be handed back to the model as
 * feedback; empty when the criterion passed
 * @author Christian Tzolov
 */
public record JevFinding(JevCriterion criterion, @Nullable Answer answer, Outcome outcome, String detail) {

	/**
	 * @return the name of the criterion
	 */
	public String name() {
		return this.criterion.name();
	}

	/**
	 * @return {@code true} when this finding blocks the response
	 */
	public boolean isFailure() {
		return this.outcome == Outcome.FAILED;
	}

	/**
	 * The ways a criterion can land. Only {@link #FAILED} blocks the response; the
	 * judge's {@code failOnInconclusive} and {@code failOnError} turn the undecided and
	 * errored cases into {@code FAILED} when the caller wants them to block.
	 */
	public enum Outcome {

		/** The answer met the criterion. */
		PASSED,

		/** The answer did not meet the criterion. */
		FAILED,

		/**
		 * Too little of the answer's probability supported the verdict to act on it: the
		 * question did not separate pass from fail for this state, which is not the same
		 * as the answer being bad.
		 */
		INCONCLUSIVE,

		/**
		 * The instrument failed: the service returned no answer for this criterion, or an
		 * answer kind this SDK does not understand. Says nothing about the answer.
		 */
		ERROR,

		/**
		 * The criterion did not apply. Either it was not asked — its {@code appliesWhen}
		 * predicate did not hold, or a failed code check skipped the call under
		 * {@code failFast} — or it was asked in the same call but the criterion it depends
		 * on ({@code whenChosen}, {@code whenPassed}) was not met, and its answer was set
		 * aside.
		 */
		NOT_APPLICABLE

	}

}
