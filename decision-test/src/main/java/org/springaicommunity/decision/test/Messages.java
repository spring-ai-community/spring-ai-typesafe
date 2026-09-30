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
import java.util.Locale;
import java.util.stream.Collectors;

import org.springaicommunity.typesafe.judge.JevFinding;
import org.springaicommunity.typesafe.judge.JevVerdict;

/**
 * Failure-message rendering shared by the assertions.
 *
 * <p>
 * Every message is formatted here with {@link Locale#ROOT} and handed to
 * {@code failWithMessage("%s", message)}. AssertJ would otherwise format with the default
 * locale, and would read any {@code %} in a criterion's own wording as a format specifier.
 *
 * @author Christian Tzolov
 */
final class Messages {

	private Messages() {
	}

	static String format(String template, Object... args) {
		return String.format(Locale.ROOT, template, args);
	}

	/**
	 * Lists every finding that did not pass, one per line, with its outcome and detail,
	 * followed by the verdict's one line summary. Not-applicable findings are left out;
	 * they are expected, and the summary still names them.
	 */
	static String report(JevVerdict verdict) {
		String findings = verdict.findings()
			.stream()
			.filter(finding -> finding.outcome() != JevFinding.Outcome.PASSED
					&& finding.outcome() != JevFinding.Outcome.NOT_APPLICABLE)
			.map(Messages::line)
			.collect(Collectors.joining(System.lineSeparator()));
		return (findings.isEmpty() ? "" : findings + System.lineSeparator()) + "  " + verdict.summary();
	}

	static String line(JevFinding finding) {
		String detail = finding.detail().isEmpty() ? finding.name() : finding.detail();
		return format("  - [%s] %s", finding.outcome(), detail);
	}

	static String names(List<JevFinding> findings) {
		return findings.stream().map(JevFinding::name).collect(Collectors.joining(", ", "[", "]"));
	}

}
