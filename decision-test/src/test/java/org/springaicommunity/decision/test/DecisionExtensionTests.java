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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import org.springaicommunity.typesafe.MockTypeSafeServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedSuccessfully;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;
import static org.springaicommunity.decision.test.DecisionAssertions.assertThatAnswer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Runs small test classes through the Jupiter engine to observe what {@link DecisionExtension}
 * does around them. The nested classes are not discovered on their own: Surefire skips
 * inner classes.
 *
 * @author Christian Tzolov
 */
class DecisionExtensionTests {

	@Test
	void skipsWithoutAnApiKeyUnlessToldNotTo() {
		ConditionEvaluationResult withoutKey = DecisionExtension.evaluate(true, null);
		assertThat(withoutKey.isDisabled()).isTrue();
		assertThat(withoutKey.getReason()).hasValue("Set TYPESAFE_API_KEY to run tests that call Jev");

		assertThat(DecisionExtension.evaluate(true, " ").isDisabled()).isTrue();
		assertThat(DecisionExtension.evaluate(true, "key").isDisabled()).isFalse();
		assertThat(DecisionExtension.evaluate(false, null).isDisabled()).isFalse();
	}

	@Test
	void failsATestWhoseChainNeverReachedJudge() {
		Events tests = run(UnjudgedChain.class);

		tests.assertStatistics(stats -> stats.started(1).failed(1));
		tests.assertThatEvents()
			.haveExactly(1, event(test("forgetsToJudge"), finishedWithFailure(instanceOf(AssertionError.class),
					message(text -> text.startsWith("Nothing was asserted")
							&& text.contains("assertThatAnswer(\"It is 15 degrees.\") with criteria [is_it_polite]")))));
	}

	@Test
	void publishesEachVerdictAsAReportEntry() {
		Events tests = run(JudgedChain.class);

		tests.assertStatistics(stats -> stats.started(1).succeeded(1).reportingEntryPublished(1));
		assertThat(tests.reportingEntryPublished().stream()
			.map(event -> event.getPayload(org.junit.platform.engine.reporting.ReportEntry.class).orElseThrow())
			.map(entry -> entry.getKeyValuePairs().get(DecisionExtension.REPORT_ENTRY_KEY))).containsExactly("passed=true [is_it_polite=PASSED]");
	}

	@Test
	void keepsNoStateOutsideATest() {
		run(JudgedChain.class);

		assertThat(DecisionTestContext.close()).isNull();
	}

	@Test
	@DisabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+", disabledReason = "asserts what is skipped without a key")
	void aMethodCanOptOutOfTheKeyItsClassRequires() {
		Events tests = run(MethodOptsOut.class);

		tests.assertStatistics(stats -> stats.succeeded(1).skipped(1).failed(0));
		tests.assertThatEvents().haveExactly(1, event(test("optsOut"), finishedSuccessfully()));
	}

	@Test
	@DisabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+", disabledReason = "asserts what is skipped without a key")
	void aNestedClassFollowsItsEnclosingClassesOptOut() {
		Events tests = run(OuterOptsOut.class);

		tests.assertStatistics(stats -> stats.succeeded(2).skipped(0).failed(0));
	}

	@Test
	@DisabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+", disabledReason = "asserts what is skipped without a key")
	void aNestedClassCanOptOutOfTheKeyItsEnclosingClassRequires() {
		Events tests = run(NestedOptsOut.class);

		tests.assertStatistics(stats -> stats.succeeded(1).skipped(1).failed(0));
		tests.assertThatEvents().haveExactly(1, event(test("runsWithoutKey"), finishedSuccessfully()));
	}

	@Test
	@DisabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = ".+", disabledReason = "asserts what is skipped without a key")
	void aClassThatNeedsTheKeyThroughoutIsSkippedWhole() {
		Events containers = EngineTestKit.engine("junit-jupiter")
			.selectors(selectClass(NeedsKey.class))
			.execute()
			.containerEvents();

		containers.assertStatistics(stats -> stats.skipped(1));
		assertThat(DecisionExtension.containsOptOut(NeedsKey.class)).isFalse();
	}

	private static Events run(Class<?> testClass) {
		return EngineTestKit.engine("junit-jupiter").selectors(selectClass(testClass)).execute().testEvents();
	}

	@DecisionTest(requiresApiKey = false)
	static class UnjudgedChain {

		@Test
		void forgetsToJudge() {
			assertThatAnswer("It is 15 degrees.").satisfies("Is it polite?");
		}

	}

	@DecisionTest(requiresApiKey = false)
	static class JudgedChain {

		@Test
		void judges() {
			MockTypeSafeServer mock = MockTypeSafeServer.create();
			mock.server()
				.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
				.andRespond(MockTypeSafeServer.jsonResponse("""
						{"model":"jev-1.13.0","answers":{"is_it_polite":{"type":"noul","noul":0.9}},"usage":{}}"""));

			assertThatAnswer("It is 15 degrees.").usingClient(mock.client()).satisfies("Is it polite?").judge();
		}

	}

	@DecisionTest
	static class MethodOptsOut {

		@Test
		@DecisionTest(requiresApiKey = false)
		void optsOut() {
		}

		@Test
		void needsKey() {
			fail("should have been skipped without a key");
		}

	}

	@DecisionTest(requiresApiKey = false)
	static class OuterOptsOut {

		@Test
		void outer() {
		}

		@Nested
		class Inner {

			@Test
			void inner() {
			}

		}

	}

	@DecisionTest
	static class NestedOptsOut {

		@Test
		void needsKey() {
			fail("should have been skipped without a key");
		}

		@Nested
		@DecisionTest(requiresApiKey = false)
		class Inner {

			@Test
			void runsWithoutKey() {
			}

		}

	}

	@DecisionTest
	static class NeedsKey {

		@Test
		void needsKey() {
			fail("should have been skipped without a key");
		}

	}

}
