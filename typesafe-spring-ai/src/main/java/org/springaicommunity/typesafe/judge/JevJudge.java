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



import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.NoulCriteria;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springaicommunity.typesafe.response.UnknownAnswer;

import org.springframework.util.Assert;

/**
 * A Model-as-a-judge built out of atomic questions rather than one rubric prompt.
 *
 * <p>
 * Every criterion is answered against the same state in a single call, in parallel, so
 * relevance, groundedness and plausibility can each be asked and thresholded separately
 * instead of being collapsed into one number by a judge model. Composing the pass
 * condition stays in Java, where it can be read and tested.
 *
 * <pre>{@code
 * JevJudge judge = JevJudge.builder(typeSafeClient)
 *     .score("helpfulness", Score.builder()
 *             .instructions("How well does `assistant_answer` address `user_question`?")
 *             .level("Terrible: irrelevant or off-topic")
 *             .level("Mostly unhelpful: misses the main point")
 *             .level("Mostly helpful: minor gaps")
 *             .level("Excellent: fully and correctly addressed")
 *             .build(), 2.0)
 *     .noul("is_plausible",
 *             Noul.builder()
 *                 .instructions("Are the values in `assistant_answer` physically plausible?")
 *                 .whenFalse("Contains an impossible or absurd value")
 *                 .build(),
 *             0.7)
 *     .build();
 *
 * JevVerdict verdict = judge.judge(question, answer);
 * }</pre>
 *
 * @author Christian Tzolov
 */
public class JevJudge {

	/** The state field carrying what was asked. */
	public static final String QUESTION_FIELD = "user_question";

	/** The state field carrying what the model replied. */
	public static final String ANSWER_FIELD = "assistant_answer";

	private final TypeSafeClient typeSafeClient;

	private final List<JevCriterion> criteria;

	private final double minConfidence;

	private final boolean failOnInconclusive;

	private final Function<List<JevFinding>, String> feedbackRenderer;

	private JevJudge(TypeSafeClient typeSafeClient, List<JevCriterion> criteria, double minConfidence,
			boolean failOnInconclusive, Function<List<JevFinding>, String> feedbackRenderer) {
		this.typeSafeClient = typeSafeClient;
		this.criteria = List.copyOf(criteria);
		this.minConfidence = minConfidence;
		this.failOnInconclusive = failOnInconclusive;
		this.feedbackRenderer = feedbackRenderer;
	}

	/**
	 * Judges an answer against the question that produced it.
	 * @param question what was asked, including any system instructions and history
	 * @param answer what the model replied
	 * @return the verdict
	 */
	public JevVerdict judge(String question, String answer) {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put(QUESTION_FIELD, question);
		state.put(ANSWER_FIELD, answer);
		return judge(JsonContent.of(state));
	}

	/**
	 * Judges an arbitrary state. Use this when the thing under judgement is not a
	 * question and answer pair, for example a retrieved passage or a tool result.
	 * @param state the content to evaluate
	 * @return the verdict
	 */
	public JevVerdict judge(JsonContent state) {
		Assert.notNull(state, "state must not be null");

		Map<String, Question> questions = new LinkedHashMap<>();
		this.criteria.forEach(criterion -> questions.put(criterion.name(), criterion.question()));

		SystemOneResponse response = this.typeSafeClient.systemOne(state, questions);

		List<JevFinding> findings = new ArrayList<>();
		for (JevCriterion criterion : this.criteria) {
			// Read through the map rather than answer(), which throws. A partial response
			// should degrade that one criterion the same way an unrecognised answer kind
			// does, not abort the caller's whole chat call.
			findings.add(evaluate(criterion, response.answers().get(criterion.name())));
		}

		boolean passed = findings.stream().noneMatch(JevFinding::isFailure);
		String feedback = passed ? "" : this.feedbackRenderer.apply(findings);

		return new JevVerdict(passed, findings, response, feedback);
	}

	/**
	 * @return the criteria this judge checks, in declaration order
	 */
	public List<JevCriterion> criteria() {
		return this.criteria;
	}

	/**
	 * Formats with {@link Locale#ROOT} rather than the ambient default. This text is fed
	 * back to the model as the defect to correct, so a comma-decimal JVM must not turn
	 * {@code "needs at least 0.70"} into {@code "needs at least 0,70"}.
	 */
	private static String format(String template, Object... args) {
		return String.format(Locale.ROOT, template, args);
	}

	private JevFinding evaluate(JevCriterion criterion, @Nullable Answer answer) {
		if (answer == null) {
			return new JevFinding(criterion, new UnknownAnswer(null, Map.of()),
					this.failOnInconclusive ? JevFinding.Outcome.FAILED : JevFinding.Outcome.INCONCLUSIVE,
					format("%s: the service returned no answer for this criterion", criterion.name()));
		}
		if (answer instanceof NoulAnswer noul) {
			return evaluateNoul(criterion, noul);
		}
		if (answer instanceof ScoreAnswer score) {
			return evaluateScore(criterion, score);
		}
		if (answer instanceof ChoiceAnswer choice) {
			return evaluateChoice(criterion, choice);
		}
		return new JevFinding(criterion, answer, JevFinding.Outcome.INCONCLUSIVE,
				format("%s: the model returned an answer kind this SDK does not understand", criterion.name()));
	}

	private JevFinding evaluateNoul(JevCriterion criterion, NoulAnswer answer) {
		if (answer.isTrue(criterion.minimum())) {
			return new JevFinding(criterion, answer, JevFinding.Outcome.PASSED, "");
		}
		// Falling below the threshold means the "true" condition did not hold, so the
		// defect is whatever the question said the "false" side looks like.
		String defect = describeFalseSide(criterion);
		return new JevFinding(criterion, answer, JevFinding.Outcome.FAILED,
				format("%s: %s (scored %.2f, needs at least %.2f)", criterion.name(), defect, answer.value(),
						criterion.minimum()));
	}

	private JevFinding evaluateScore(JevCriterion criterion, ScoreAnswer answer) {
		JevFinding.Outcome inconclusive = checkConfidence(answer.confidence());
		if (inconclusive != null) {
			return new JevFinding(criterion, answer, inconclusive,
					format("%s: the rubric levels were not well separated for this answer (confidence %.2f, needs at least %.2f)", criterion.name(), answer.confidence(), this.minConfidence));
		}
		if (answer.value() >= criterion.minimum()) {
			return new JevFinding(criterion, answer, JevFinding.Outcome.PASSED, "");
		}

		// Describe the level the score itself lands on, not the most probable level: the
		// two can disagree on a spread distribution, and quoting a label that contradicts
		// the number would be worse than useless as feedback.
		String reached = describeLevel(criterion, answer, roundToLevel(criterion, answer.value()));
		String required = describeLevel(criterion, answer, (int) Math.ceil(criterion.minimum()));
		String detail = format("%s: rated \"%s\" (%.2f), needs to reach %.2f", criterion.name(), reached,
				answer.value(), criterion.minimum());
		if (!required.isEmpty()) {
			detail += format(" which is \"%s\"", required);
		}
		return new JevFinding(criterion, answer, JevFinding.Outcome.FAILED, detail);
	}

	private JevFinding evaluateChoice(JevCriterion criterion, ChoiceAnswer answer) {
		JevFinding.Outcome inconclusive = checkConfidence(answer.confidence());
		if (inconclusive != null) {
			return new JevFinding(criterion, answer, inconclusive,
					format("%s: the options were not well separated for this answer (confidence %.2f, needs at least %.2f)", criterion.name(), answer.confidence(), this.minConfidence));
		}
		if (criterion.acceptedOptions().contains(answer.value())) {
			return new JevFinding(criterion, answer, JevFinding.Outcome.PASSED, "");
		}
		return new JevFinding(criterion, answer, JevFinding.Outcome.FAILED,
				format("%s: classified as \"%s\" (confidence %.2f), acceptable values are %s", criterion.name(),
						answer.value(), answer.confidence(), criterion.acceptedOptions()));
	}

	/**
	 * Nouls carry no confidence statistic by design, so only choices and scores can land
	 * here.
	 * @return the outcome to report, or {@code null} when confidence is good enough
	 */
	private JevFinding.@Nullable Outcome checkConfidence(double confidence) {
		if (confidence >= this.minConfidence) {
			return null;
		}
		return this.failOnInconclusive ? JevFinding.Outcome.FAILED : JevFinding.Outcome.INCONCLUSIVE;
	}

	private String describeFalseSide(JevCriterion criterion) {
		if (criterion.question() instanceof Noul noul) {
			NoulCriteria criteria = noul.criteria();
			if (criteria != null && criteria.whenFalse() != null) {
				return criteria.whenFalse().toDisplayString();
			}
			if (noul.instructions() != null) {
				return format("the answer to \"%s\" was no", noul.instructions().toDisplayString());
			}
		}
		return "the criterion was not met";
	}

	private int roundToLevel(JevCriterion criterion, double value) {
		int level = (int) Math.round(value);
		if (criterion.question() instanceof Score score) {
			return Math.max(0, Math.min(level, score.maxLevel()));
		}
		return Math.max(0, level);
	}

	/**
	 * Names a rubric level, preferring the caller's own wording over the legend the
	 * response echoes back, so the feedback speaks the vocabulary the rubric was written
	 * in.
	 */
	private String describeLevel(JevCriterion criterion, ScoreAnswer answer, int level) {
		if (criterion.question() instanceof Score score) {
			JsonContent description = score.levelAt(level);
			if (description != null) {
				return description.toDisplayString();
			}
		}
		JsonContent fromLegend = answer.labelOf(level);
		return fromLegend == null ? "" : fromLegend.toDisplayString();
	}

	/**
	 * Renders the failing and inconclusive findings as the feedback handed back to the
	 * model.
	 * @param findings every finding of the round
	 * @return the feedback text
	 */
	public static String defaultFeedback(List<JevFinding> findings) {
		StringBuilder feedback = new StringBuilder();
		findings.stream()
			.filter(finding -> finding.outcome() == JevFinding.Outcome.FAILED)
			.forEach(finding -> feedback.append("- ").append(finding.detail()).append(System.lineSeparator()));

		List<JevFinding> undecided = findings.stream()
			.filter(finding -> finding.outcome() == JevFinding.Outcome.INCONCLUSIVE)
			.toList();
		if (!undecided.isEmpty()) {
			feedback.append("The following checks were inconclusive and may need a clearer answer:")
				.append(System.lineSeparator());
			undecided
				.forEach(finding -> feedback.append("- ").append(finding.detail()).append(System.lineSeparator()));
		}
		return feedback.toString().stripTrailing();
	}

	/**
	 * @param typeSafeClient the client used to ask the questions
	 * @return a new builder
	 */
	public static Builder builder(TypeSafeClient typeSafeClient) {
		return new Builder(typeSafeClient);
	}

	/**
	 * Builder for {@link JevJudge}.
	 */
	public static final class Builder {

		private final TypeSafeClient typeSafeClient;

		private final List<JevCriterion> criteria = new ArrayList<>();

		private double minConfidence = 0.5d;

		private boolean failOnInconclusive;

		private Function<List<JevFinding>, String> feedbackRenderer = JevJudge::defaultFeedback;

		private Builder(TypeSafeClient typeSafeClient) {
			Assert.notNull(typeSafeClient, "typeSafeClient must not be null");
			this.typeSafeClient = typeSafeClient;
		}

		/**
		 * Adds a noul that passes when its truth value reaches {@code minimum}.
		 * @param name the name the answer will carry
		 * @param noul the question
		 * @param minimum the inclusive lower bound
		 * @return this builder
		 */
		public Builder noul(String name, Noul noul, double minimum) {
			return criterion(JevCriterion.noul(name, noul, minimum));
		}

		/**
		 * Adds a score that passes when it reaches {@code minimum}.
		 * @param name the name the answer will carry
		 * @param score the question
		 * @param minimum the inclusive lower bound
		 * @return this builder
		 */
		public Builder score(String name, Score score, double minimum) {
			return criterion(JevCriterion.score(name, score, minimum));
		}

		/**
		 * Adds a choice that passes when the selected label is accepted.
		 * @param name the name the answer will carry
		 * @param choice the question
		 * @param acceptedOptions the labels that count as passing
		 * @return this builder
		 */
		public Builder choice(String name, Choice choice, String... acceptedOptions) {
			return criterion(JevCriterion.choice(name, choice, acceptedOptions));
		}

		public Builder criterion(JevCriterion criterion) {
			Assert.notNull(criterion, "criterion must not be null");
			Assert.isTrue(this.criteria.stream().noneMatch(existing -> existing.name().equals(criterion.name())),
					"a criterion named '" + criterion.name() + "' is already declared");
			this.criteria.add(criterion);
			return this;
		}

		/**
		 * Sets the confidence below which a choice or score answer is treated as
		 * undecided. Raise it for consequential decisions and lower it for reversible
		 * ones; nouls are unaffected, since they carry no confidence.
		 * @param minConfidence the inclusive lower bound, between {@code 0} and {@code 1}
		 * @return this builder
		 */
		public Builder minConfidence(double minConfidence) {
			Assert.isTrue(minConfidence >= 0.0d && minConfidence <= 1.0d, "minConfidence must be between 0 and 1");
			this.minConfidence = minConfidence;
			return this;
		}

		/**
		 * Whether an undecided criterion blocks the response. Off by default: a flat
		 * distribution says the question did not separate well for this state, which is
		 * not the same as the answer being wrong.
		 * @param failOnInconclusive whether to treat undecided as failed
		 * @return this builder
		 */
		public Builder failOnInconclusive(boolean failOnInconclusive) {
			this.failOnInconclusive = failOnInconclusive;
			return this;
		}

		/**
		 * Overrides how findings are rendered into the feedback text.
		 * @param feedbackRenderer the renderer
		 * @return this builder
		 */
		public Builder feedbackRenderer(Function<List<JevFinding>, String> feedbackRenderer) {
			Assert.notNull(feedbackRenderer, "feedbackRenderer must not be null");
			this.feedbackRenderer = feedbackRenderer;
			return this;
		}

		public JevJudge build() {
			Assert.notEmpty(this.criteria, "a judge must declare at least one criterion");
			return new JevJudge(this.typeSafeClient, this.criteria, this.minConfidence, this.failOnInconclusive,
					this.feedbackRenderer);
		}

	}

}
