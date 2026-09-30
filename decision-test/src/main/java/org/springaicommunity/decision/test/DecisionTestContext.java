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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.judge.JevVerdict;

/**
 * What {@link DecisionExtension} shares with the {@link DecisionAnswerAssert} chains a test creates:
 * the client to judge with when a chain names none, the chains that were started, and
 * the verdicts they produced.
 *
 * <p>
 * Held per thread, and only between the extension's {@code beforeEach} and
 * {@code afterEach}; outside a {@code @DecisionTest} nothing is recorded, so plain use of the
 * assertions keeps no state.
 *
 * @author Christian Tzolov
 */
final class DecisionTestContext {

	private static final ThreadLocal<State> STATE = new ThreadLocal<>();

	private DecisionTestContext() {
	}

	static void open(Supplier<TypeSafeClient> client) {
		STATE.set(new State(client));
	}

	static @Nullable State close() {
		State state = STATE.get();
		STATE.remove();
		return state;
	}

	static @Nullable TypeSafeClient defaultClient() {
		State state = STATE.get();
		return state == null ? null : state.client.get();
	}

	static void started(DecisionAnswerAssert chain) {
		State state = STATE.get();
		if (state != null) {
			state.chains.add(chain);
		}
	}

	static void judged(JevVerdict verdict) {
		State state = STATE.get();
		if (state != null) {
			state.verdicts.add(verdict);
		}
	}

	static final class State {

		private final Supplier<TypeSafeClient> client;

		private final List<DecisionAnswerAssert> chains = new ArrayList<>();

		private final List<JevVerdict> verdicts = new ArrayList<>();

		private State(Supplier<TypeSafeClient> client) {
			this.client = client;
		}

		List<DecisionAnswerAssert> unjudged() {
			return this.chains.stream().filter(chain -> !chain.isJudged()).toList();
		}

		List<JevVerdict> verdicts() {
			return this.verdicts;
		}

	}

}
