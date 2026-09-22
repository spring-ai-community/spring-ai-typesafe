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
 * {@link JevCriterion.CodeCriterion}, which is answered in code
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
	 * The three ways a criterion can land.
	 */
	public enum Outcome {

		/** The answer met the criterion. */
		PASSED,

		/** The answer did not meet the criterion. */
		FAILED,

		/**
		 * The model's distribution was too flat to act on. Confidence is a statistic over
		 * the answer's own probability distribution, so a low value means the options or
		 * levels were not well separated for this state, not that the answer was bad.
		 */
		INCONCLUSIVE

	}

}
