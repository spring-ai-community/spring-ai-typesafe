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

import java.lang.reflect.AnnotatedElement;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeConstants;
import org.springaicommunity.typesafe.judge.JevVerdict;

/**
 * The JUnit Jupiter extension behind {@link DecisionTest}; see there for what it does.
 *
 * <p>
 * The client is built once per test run, from the environment exactly as
 * {@code TypeSafeClient.builder().build()} reads it, and only when a test first asks for
 * it.
 *
 * @author Christian Tzolov
 */
public class DecisionExtension implements ExecutionCondition, ParameterResolver, BeforeEachCallback, AfterEachCallback {

	private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(DecisionExtension.class);

	/** The report entry key each verdict's summary is published under. */
	public static final String REPORT_ENTRY_KEY = "jev.verdict";

	/**
	 * Decides from the nearest {@link DecisionTest}: the method's own, then the test class's,
	 * then an enclosing class's for a {@code @Nested} one.
	 *
	 * <p>
	 * JUnit asks about the class before any of its methods, and a disabled class never
	 * gets to ask them. So a class that needs the key is still enabled when a method or
	 * {@code @Nested} class inside it opts out, and the decision falls to each test.
	 */
	@Override
	public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
		String apiKey = System.getenv(TypeSafeConstants.API_KEY_ENV);
		if (context.getTestMethod().isPresent()) {
			boolean requiresApiKey = AnnotationSupport.findAnnotation(context.getTestMethod(), DecisionTest.class)
				.or(() -> classAnnotation(context))
				.map(DecisionTest::requiresApiKey)
				.orElse(true);
			return evaluate(requiresApiKey, apiKey);
		}
		boolean requiresApiKey = classAnnotation(context).map(DecisionTest::requiresApiKey).orElse(true);
		ConditionEvaluationResult result = evaluate(requiresApiKey, apiKey);
		if (result.isDisabled() && context.getTestClass().filter(DecisionExtension::containsOptOut).isPresent()) {
			return ConditionEvaluationResult.enabled("some tests inside do not require Jev; each decides for itself");
		}
		return result;
	}

	private static Optional<DecisionTest> classAnnotation(ExtensionContext context) {
		return context.getTestClass()
			.flatMap(type -> AnnotationSupport.findAnnotation(type, DecisionTest.class, context.getEnclosingTestClasses()));
	}

	/**
	 * @return whether a test method or {@code @Nested} class inside this class, at any
	 * depth, declares {@code @DecisionTest(requiresApiKey = false)}
	 */
	static boolean containsOptOut(Class<?> testClass) {
		boolean methodOptsOut = AnnotationSupport
			.findAnnotatedMethods(testClass, DecisionTest.class, HierarchyTraversalMode.TOP_DOWN)
			.stream()
			.anyMatch(method -> optsOut(method));
		return methodOptsOut || ReflectionSupport
			.streamNestedClasses(testClass, nested -> AnnotationSupport.isAnnotated(nested, Nested.class))
			.anyMatch(nested -> optsOut(nested) || containsOptOut(nested));
	}

	private static boolean optsOut(AnnotatedElement element) {
		return AnnotationSupport.findAnnotation(element, DecisionTest.class).filter(test -> !test.requiresApiKey()).isPresent();
	}

	static ConditionEvaluationResult evaluate(boolean requiresApiKey, @Nullable String apiKey) {
		if (!requiresApiKey || (apiKey != null && !apiKey.isBlank())) {
			return ConditionEvaluationResult.enabled("Jev is reachable");
		}
		return ConditionEvaluationResult
			.disabled("Set " + TypeSafeConstants.API_KEY_ENV + " to run tests that call Jev");
	}

	@Override
	public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
		return parameterContext.getParameter().getType() == TypeSafeClient.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
		return client(extensionContext);
	}

	@Override
	public void beforeEach(ExtensionContext context) {
		DecisionTestContext.open(() -> client(context));
	}

	@Override
	public void afterEach(ExtensionContext context) {
		DecisionTestContext.State state = DecisionTestContext.close();
		if (state == null) {
			return;
		}
		for (JevVerdict verdict : state.verdicts()) {
			context.publishReportEntry(REPORT_ENTRY_KEY, verdict.summary());
		}
		List<DecisionAnswerAssert> unjudged = state.unjudged();
		// A test that already failed is reported by its own exception; an unjudged chain
		// is then most likely the consequence, not the cause.
		if (!unjudged.isEmpty() && context.getExecutionException().isEmpty()) {
			throw new AssertionError(unjudged.stream()
				.map(chain -> "  - " + chain)
				.collect(Collectors.joining(System.lineSeparator(),
						"Nothing was asserted: these chains never reached judge(), judgeOrAbort() or evaluate():"
								+ System.lineSeparator(),
						"")));
		}
	}

	private static TypeSafeClient client(ExtensionContext context) {
		return context.getRoot()
			.getStore(NAMESPACE)
			.computeIfAbsent(TypeSafeClient.class, key -> TypeSafeClient.builder().build(), TypeSafeClient.class);
	}

}
