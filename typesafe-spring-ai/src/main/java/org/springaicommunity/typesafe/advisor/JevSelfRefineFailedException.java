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

package org.springaicommunity.typesafe.advisor;

import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.judge.JevVerdict;

/**
 * Raised by {@link JevSelfRefineAdvisor} when the model could not produce an acceptable
 * answer within the allowed attempts and the advisor is configured to fail rather than
 * return the best effort.
 *
 * @author Christian Tzolov
 */
public class JevSelfRefineFailedException extends TypeSafeException {

	private final transient JevVerdict verdict;

	public JevSelfRefineFailedException(int attempts, JevVerdict verdict) {
		super("The response still failed evaluation after %d retries. %s%n%s".formatted(attempts, verdict.summary(),
				verdict.feedback()));
		this.verdict = verdict;
	}

	/**
	 * @return the verdict of the final attempt, including every answer and probability
	 */
	public JevVerdict verdict() {
		return this.verdict;
	}

}
