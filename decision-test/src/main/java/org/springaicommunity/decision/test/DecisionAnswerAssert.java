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
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.judge.JevCriterion;
import org.springaicommunity.typesafe.judge.JevJudge;
import org.springaicommunity.typesafe.judge.JevJudgeInput;
import org.springaicommunity.typesafe.judge.JevVerdict;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Score;

import org.springframework.util.Assert;

/**
 * Judges an answer against criteria written in plain language, in one Jev call:
 *
 * <pre>{@code
 * assertThatAnswer(answer)
 *     .givenQuestion("Can I get a refund for last month?")
 *     .satisfies("Does `assistant_answer` address `user_question`?", 0.8)
 *     .satisfies("Is `assistant_answer` free of refund terms not stated in `supporting_context`?")
 *     .isClassifiedAs("What is the customer's intent?", "billing").among("billing", "technical", "sales")
 *     .scores("How polite is `assistant_answer`?", "Rude", "Neutral", "Polite", "Very polite").atLeast("Polite")
 *     .judge();
 * }</pre>
 *
 * <p>
 * Nothing is sent until a terminal method: {@link #judge()} asserts that the verdict
 * passed, {@link #judgeOrAbort()} aborts rather than fails when Jev could not decide, and
 * {@link #evaluate()} returns the verdict to assert on yourself. Every criterion declared
 * before it is answered in that single call. Under {@link DecisionTest}, a chain that never
 * reaches a terminal method fails the test, because nothing was asserted.
 *
 * <p>
 * Each plain-language criterion is sent to Jev as written, and named after its own words
 * in the verdict. The state carries the answer as {@code assistant_answer}, the question
 * as {@code user_question}, and so on, as {@link JevJudgeInput} documents; a criterion
 * that names the field it is about is answered more reliably. Keep each criterion to one
 * thing: two criteria in one sentence cannot say which of them failed.
 *
 * @author Christian Tzolov
 */
public final class DecisionAnswerAssert {

	/**
	 * The minimum of a {@link #satisfies(String)} criterion that states none: a clear
	 * yes rather than a lean.
	 */
	public static final double DEFAULT_MINIMUM = 0.7d;

	private static final int MAX_NAME_LENGTH = 48;

	private final JevJudgeInput.Builder input = JevJudgeInput.builder();

	private final String answer;

	private final List<JevCriterion> criteria = new ArrayList<>();

	private @Nullable TypeSafeClient client;

	private @Nullable JevJudge judge;

	private double minConfidence = JevJudge.DEFAULT_MIN_CONFIDENCE;

	private boolean judged;

	DecisionAnswerAssert(String answer) {
		Assert.notNull(answer, "answer must not be null");
		this.answer = answer;
		this.input.answer(answer);
		DecisionTestContext.started(this);
	}

	/**
	 * @param client the client to judge with; optional under {@link DecisionTest}, which
	 * provides one
	 * @return this assertion
	 */
	public DecisionAnswerAssert usingClient(TypeSafeClient client) {
		Assert.notNull(client, "client must not be null");
		this.client = client;
		return this;
	}

	/**
	 * Judges with an existing judge instead of criteria declared on this chain.
	 * @param judge the judge
	 * @return this assertion
	 */
	public DecisionAnswerAssert usingJudge(JevJudge judge) {
		Assert.notNull(judge, "judge must not be null");
		this.judge = judge;
		return this;
	}

	/**
	 * @param minConfidence the share of a choice's or score's probability that must
	 * support its verdict; see {@link JevJudge.Builder#minConfidence(double)}
	 * @return this assertion
	 */
	public DecisionAnswerAssert minConfidence(double minConfidence) {
		Assert.isTrue(minConfidence >= 0.0d && minConfidence <= 1.0d, "minConfidence must be between 0 and 1");
		this.minConfidence = minConfidence;
		return this;
	}

	/**
	 * @param question what was asked, sent as {@code user_question}
	 * @return this assertion
	 */
	public DecisionAnswerAssert givenQuestion(String question) {
		this.input.question(question);
		return this;
	}

	/**
	 * @param documents supporting documents, sent as {@code supporting_context}
	 * @return this assertion
	 */
	public DecisionAnswerAssert givenContext(String... documents) {
		this.input.context(List.of(documents));
		return this;
	}

	/**
	 * @param toolCall a tool call the answer was produced with, sent in
	 * {@code tool_calls}
	 * @return this assertion
	 */
	public DecisionAnswerAssert givenToolCall(JevJudgeInput.ToolCall toolCall) {
		this.input.toolCall(toolCall);
		return this;
	}

	/**
	 * @param expected the reference output, sent as {@code expected_output}
	 * @return this assertion
	 */
	public DecisionAnswerAssert givenExpected(Object expected) {
		this.input.expected(expected);
		return this;
	}

	/**
	 * @param name a field of your own
	 * @param value its value
	 * @return this assertion
	 */
	public DecisionAnswerAssert givenField(String name, Object value) {
		this.input.field(name, value);
		return this;
	}

	/**
	 * A yes/no criterion that passes at {@link #DEFAULT_MINIMUM}.
	 * @param criterion the question, answered yes when the criterion holds
	 * @return this assertion
	 */
	public DecisionAnswerAssert satisfies(String criterion) {
		return satisfies(criterion, DEFAULT_MINIMUM);
	}

	/**
	 * A yes/no criterion, sent as a {@link Noul}.
	 * @param criterion the question, answered yes when the criterion holds
	 * @param minimum the truth value it must reach, between {@code 0} and {@code 1}
	 * @return this assertion
	 */
	public DecisionAnswerAssert satisfies(String criterion, double minimum) {
		Assert.hasText(criterion, "criterion must not be empty");
		return criterion(JevCriterion.noul(nameOf(criterion), Noul.of(criterion), minimum));
	}

	/**
	 * @param name the criterion name
	 * @param noul the question
	 * @param minimum the truth value it must reach
	 * @return this assertion
	 */
	public DecisionAnswerAssert satisfies(String name, Noul noul, double minimum) {
		return criterion(JevCriterion.noul(name, noul, minimum));
	}

	/**
	 * A classification criterion. Jev spreads its probability over a fixed set of
	 * options, so name them with {@link Classification#among(String...)}.
	 * @param question what to classify, for example {@code "What is the customer's intent?"}
	 * @param accepted the options that pass
	 * @return the step that names every option
	 */
	public Classification isClassifiedAs(String question, String... accepted) {
		Assert.hasText(question, "question must not be empty");
		Assert.notEmpty(accepted, "at least one accepted option is required");
		return new Classification(question, accepted);
	}

	/**
	 * @param name the criterion name
	 * @param choice the question
	 * @param accepted the options that pass
	 * @return this assertion
	 */
	public DecisionAnswerAssert isClassifiedAs(String name, Choice choice, String... accepted) {
		return criterion(JevCriterion.choice(name, choice, accepted));
	}

	/**
	 * A rubric criterion, sent as a {@link Score}.
	 * @param question what to rate, for example {@code "How polite is `assistant_answer`?"}
	 * @param levels the rubric levels, lowest first
	 * @return the step that names the lowest passing level
	 */
	public Rating scores(String question, String... levels) {
		Assert.hasText(question, "question must not be empty");
		Assert.isTrue(levels.length >= 2, "a rubric needs at least two levels");
		return new Rating(question, levels);
	}

	/**
	 * @param name the criterion name
	 * @param score the rubric
	 * @param minimum the level it must reach
	 * @return this assertion
	 */
	public DecisionAnswerAssert scores(String name, Score score, double minimum) {
		return criterion(JevCriterion.score(name, score, minimum));
	}

	/**
	 * Adds any criterion, including a code check or one that depends on another.
	 * @param criterion the criterion
	 * @return this assertion
	 */
	public DecisionAnswerAssert criterion(JevCriterion criterion) {
		Assert.notNull(criterion, "criterion must not be null");
		this.criteria.add(criterion);
		return this;
	}

	/**
	 * Judges the answer and asserts that the verdict passed: no criterion failed, was
	 * inconclusive or errored.
	 * @return an assertion on the verdict, for further checks
	 */
	public DecisionVerdictAssert judge() {
		return evaluate().passed();
	}

	/**
	 * Judges the answer, fails the test on a failed criterion, and aborts it when the
	 * only thing standing in the way is a criterion Jev could not decide.
	 * @return an assertion on the verdict, for further checks
	 */
	public DecisionVerdictAssert judgeOrAbort() {
		return evaluate().passedOrAborted();
	}

	/**
	 * Judges the answer without asserting anything about the verdict, for a test that
	 * expects a criterion to fail.
	 * @return an assertion on the verdict
	 */
	public DecisionVerdictAssert evaluate() {
		// Marked first: a call that throws has still been attempted, and the test should
		// report the exception rather than an unjudged chain.
		this.judged = true;
		JevVerdict verdict = buildJudge().judge(this.input.build());
		DecisionTestContext.judged(verdict);
		return new DecisionVerdictAssert(verdict);
	}

	private JevJudge buildJudge() {
		if (this.judge != null) {
			Assert.state(this.criteria.isEmpty(), "usingJudge(...) cannot be combined with criteria declared here");
			return this.judge;
		}
		Assert.state(!this.criteria.isEmpty(), "declare at least one criterion before judging");
		TypeSafeClient resolved = this.client != null ? this.client : DecisionTestContext.defaultClient();
		Assert.state(resolved != null, "no TypeSafeClient to judge with: call usingClient(...) or run the test with @DecisionTest");
		JevJudge.Builder builder = JevJudge.builder(resolved).minConfidence(this.minConfidence);
		this.criteria.forEach(builder::criterion);
		return builder.build();
	}

	boolean isJudged() {
		return this.judged;
	}

	@Override
	public String toString() {
		String excerpt = this.answer.length() > 40 ? this.answer.substring(0, 40) + "…" : this.answer;
		return "assertThatAnswer(\"" + excerpt + "\") with criteria "
				+ this.criteria.stream().map(JevCriterion::name).toList();
	}

	/**
	 * Names a criterion after its own words, so the verdict and the feedback read like
	 * the assertion that declared it: {@code "Is it polite?"} becomes {@code is_it_polite}.
	 * A name already taken gets a numeric suffix.
	 */
	String nameOf(String text) {
		String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		if (slug.length() > MAX_NAME_LENGTH) {
			slug = slug.substring(0, MAX_NAME_LENGTH).replaceAll("_+$", "");
		}
		if (slug.isEmpty()) {
			slug = "criterion";
		}
		String name = slug;
		for (int suffix = 2; isTaken(name); suffix++) {
			name = slug + "_" + suffix;
		}
		return name;
	}

	private boolean isTaken(String name) {
		return this.criteria.stream().anyMatch(criterion -> criterion.name().equals(name));
	}

	/**
	 * The second half of {@link #isClassifiedAs(String, String...)}: every option Jev
	 * chooses between.
	 */
	public final class Classification {

		private final String question;

		private final String[] accepted;

		private Classification(String question, String[] accepted) {
			this.question = question;
			this.accepted = accepted;
		}

		/**
		 * @param options every option, the accepted ones included
		 * @return the assertion the criterion was added to
		 */
		public DecisionAnswerAssert among(String... options) {
			List<String> all = List.of(options);
			for (String label : this.accepted) {
				Assert.isTrue(all.contains(label), "accepted option '" + label + "' is not one of " + all);
			}
			return criterion(
					JevCriterion.choice(nameOf(this.question), Choice.of(this.question, options), this.accepted));
		}

	}

	/**
	 * The second half of {@link #scores(String, String...)}: the lowest level that
	 * passes.
	 */
	public final class Rating {

		private final String question;

		private final String[] levels;

		private Rating(String question, String[] levels) {
			this.question = question;
			this.levels = levels;
		}

		/**
		 * @param level the lowest passing level, by its wording
		 * @return the assertion the criterion was added to
		 */
		public DecisionAnswerAssert atLeast(String level) {
			int index = List.of(this.levels).indexOf(level);
			Assert.isTrue(index >= 0, "'" + level + "' is not one of the levels " + List.of(this.levels));
			return atLeast(index);
		}

		/**
		 * @param level the lowest passing level, by its index from {@code 0}
		 * @return the assertion the criterion was added to
		 */
		public DecisionAnswerAssert atLeast(int level) {
			Assert.isTrue(level >= 0 && level < this.levels.length,
					"level must be between 0 and " + (this.levels.length - 1));
			return criterion(JevCriterion.score(nameOf(this.question), Score.of(this.question, this.levels), level));
		}

	}

}
