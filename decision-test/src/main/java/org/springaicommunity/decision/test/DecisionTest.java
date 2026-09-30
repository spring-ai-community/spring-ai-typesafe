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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class or method that judges with Jev, and registers {@link DecisionExtension}:
 * <ul>
 * <li>the test is skipped, not failed, when {@code TYPESAFE_API_KEY} is not set, so an
 * ordinary build never makes a billed call;</li>
 * <li>a {@code TypeSafeClient} parameter is resolved, and
 * {@link DecisionAssertions#assertThatAnswer(String)} judges with it unless told otherwise;</li>
 * <li>an {@code assertThatAnswer(...)} chain that never reaches {@code judge()} fails the
 * test;</li>
 * <li>each verdict's summary is published as a report entry.</li>
 * </ul>
 * Tagged {@code jev}, so a build can include or exclude these tests as a group.
 *
 * @author Christian Tzolov
 */
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Tag("jev")
@ExtendWith(DecisionExtension.class)
public @interface DecisionTest {

	/**
	 * @return whether to skip the test when {@code TYPESAFE_API_KEY} is not set; turn it
	 * off for tests that judge with a client of their own, such as one bound to a mock
	 * server
	 */
	boolean requiresApiKey() default true;

}
