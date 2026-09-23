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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.judge.JevCriterion.CodeCriterion;
import org.springaicommunity.typesafe.judge.JevCriterion.QuestionCriterion;
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
 * <p>
 * A criterion whose answer is already in the input — was a tool called, does the answer
 * parse — is better declared as a {@link #check code check} than asked of Jev. Checks run
 * locally before the call and land in the same verdict as the questions.
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

	/**
	 * The default {@code minConfidence}: a clear majority, 60%, of a score's or choice's
	 * probability has to support the verdict before it is acted on. A coin flip between
	 * pass and fail supports it at 50%.
	 */
	public static final double DEFAULT_MIN_CONFIDENCE = 0.6d;

	/**
	 * The state field carrying what was asked; see {@link JevJudgeInput#QUESTION_FIELD}.
	 */
	public static final String QUESTION_FIELD = JevJudgeInput.QUESTION_FIELD;

	/**
	 * The state field carrying what the model replied; see
	 * {@link JevJudgeInput#ANSWER_FIELD}.
	 */
	public static final String ANSWER_FIELD = JevJudgeInput.ANSWER_FIELD;

	private final TypeSafeClient typeSafeClient;

	private final List<JevCriterion> criteria;

	private final double minConfidence;

	private final boolean failOnInconclusive;

	private final boolean failOnError;

	private final boolean failFast;

	private final Function<List<JevFinding>, String> feedbackRenderer;

	/** Whether any criterion reads the input, so a raw state must be wrapped for it. */
	private final boolean needsInput;

	private JevJudge(TypeSafeClient typeSafeClient, List<JevCriterion> criteria, double minConfidence,
			boolean failOnInconclusive, boolean failOnError, boolean failFast,
			Function<List<JevFinding>, String> feedbackRenderer) {
		this.typeSafeClient = typeSafeClient;
		this.criteria = List.copyOf(criteria);
		this.minConfidence = minConfidence;
		this.failOnInconclusive = failOnInconclusive;
		this.failOnError = failOnError;
		this.failFast = failFast;
		this.feedbackRenderer = feedbackRenderer;
		this.needsInput = this.criteria.stream()
			.anyMatch(criterion -> criterion instanceof CodeCriterion
					|| (criterion instanceof QuestionCriterion question && question.appliesWhen() != null));
	}

	/**
	 * Judges an answer against the question that produced it.
	 * @param question what was asked, including any system instructions and history
	 * @param answer what the model replied
	 * @return the verdict
	 */
	public JevVerdict judge(String question, String answer) {
		return judge(JevJudgeInput.builder().question(question).answer(answer).build());
	}

	/**
	 * Judges a typed input. Its fields become the judged state under the names
	 * {@link JevJudgeInput} documents, and code checks read it directly.
	 * @param input the input to judge
	 * @return the verdict
	 */
	public JevVerdict judge(JevJudgeInput input) {
		Assert.notNull(input, "input must not be null");
		return judge(input.toState(), input);
	}

	/**
	 * Judges an arbitrary state. Use this when the thing under judgement is not a
	 * question and answer pair, for example a retrieved passage or a tool result. The
	 * state is sent as given; code checks and {@code appliesWhen} predicates, if the
	 * judge has any, read it through {@link JevJudgeInput#of(JsonContent)}, which
	 * requires a JSON object.
	 * @param state the content to evaluate
	 * @return the verdict
	 */
	public JevVerdict judge(JsonContent state) {
		Assert.notNull(state, "state must not be null");
		return judge(state, this.needsInput ? JevJudgeInput.of(state) : null);
	}

	private JevVerdict judge(JsonContent state, @Nullable JevJudgeInput input) {
		// Checks run before the call: they are free, and a check that throws is a bug
		// that should surface without first spending a request.
		Map<String, JevFinding> decided = new LinkedHashMap<>();
		for (JevCriterion criterion : this.criteria) {
			if (criterion instanceof CodeCriterion code) {
				Assert.state(input != null, "code criteria need an input to read");
				decided.put(code.name(), evaluateCheck(code, input));
			}
		}
		boolean checkFailed = decided.values().stream().anyMatch(JevFinding::isFailure);

		// Questions that need no asking are settled here too, so they never reach Jev.
		Map<String, Question> questions = new LinkedHashMap<>();
		for (JevCriterion criterion : this.criteria) {
			if (criterion instanceof QuestionCriterion question) {
				if (this.failFast && checkFailed) {
					decided.put(question.name(), notApplicable(question, "skipped, a code check already failed"));
				}
				else if (question.appliesWhen() != null && !question.appliesWhen().test(requireInput(input))) {
					decided.put(question.name(), notApplicable(question, "does not apply to this input"));
				}
				else {
					questions.put(question.name(), question.question());
				}
			}
		}

		SystemOneResponse response = questions.isEmpty() ? null : this.typeSafeClient.systemOne(state, questions);

		// Declaration order: a dependency is always declared before its dependents, so
		// its finding is final by the time a dependent reads it.
		// Criteria whose answer did not settle the question, recorded before failOnInconclusive
		// or failOnError turn them into FAILED: a dependent must not follow an undecided choice.
		Set<String> undecided = new HashSet<>();
		Map<String, JevFinding> byName = new LinkedHashMap<>();
		for (JevCriterion criterion : this.criteria) {
			JevFinding finding = decided.get(criterion.name());
			if (finding == null && response != null) {
				// Read through the map rather than answer(), which throws. A partial
				// response should degrade that one criterion, not abort the caller's
				// whole chat call.
				finding = evaluate((QuestionCriterion) criterion, response.answers().get(criterion.name()), undecided);
			}
			if (criterion instanceof QuestionCriterion question && question.dependsOn() != null
					&& finding.outcome() != JevFinding.Outcome.NOT_APPLICABLE) {
				String unmet = unmetDependency(question.dependsOn(), byName.get(question.dependsOn().criterion()),
						undecided.contains(question.dependsOn().criterion()));
				if (unmet != null) {
					finding = notApplicable(question, "does not apply, " + unmet);
				}
			}
			byName.put(criterion.name(), finding);
		}
		List<JevFinding> findings = List.copyOf(byName.values());

		boolean passed = findings.stream().noneMatch(JevFinding::isFailure);
		String feedback = passed ? "" : this.feedbackRenderer.apply(findings);

		return new JevVerdict(passed, findings, response, feedback);
	}

	/**
	 * @return why the dependency is not met, or {@code null} when it is
	 */
	private static @Nullable String unmetDependency(JevCriterion.Dependency dependency, JevFinding target,
			boolean targetUndecided) {
		if (dependency.chosen().isEmpty()) {
			return target.outcome() == JevFinding.Outcome.PASSED ? null
					: format("%s was %s", dependency.criterion(), target.outcome());
		}
		// A choice that was not decided — inconclusive, errored, not asked — selected
		// nothing a dependent could rely on.
		boolean decided = !targetUndecided && (target.outcome() == JevFinding.Outcome.PASSED
				|| target.outcome() == JevFinding.Outcome.FAILED);
		if (decided && target.answer() instanceof ChoiceAnswer choice && dependency.chosen().contains(choice.value())) {
			return null;
		}
		return target.answer() instanceof ChoiceAnswer choice && decided
				? format("%s was \"%s\"", dependency.criterion(), choice.value())
				: format("%s was %s", dependency.criterion(), target.outcome());
	}

	private static JevJudgeInput requireInput(@Nullable JevJudgeInput input) {
		Assert.state(input != null, "appliesWhen needs an input to read");
		return input;
	}

	private static JevFinding notApplicable(QuestionCriterion criterion, String reason) {
		return new JevFinding(criterion, null, JevFinding.Outcome.NOT_APPLICABLE,
				format("%s: %s", criterion.name(), reason));
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

	private static JevFinding evaluateCheck(CodeCriterion criterion, JevJudgeInput input) {
		if (criterion.check().test(input)) {
			return new JevFinding(criterion, null, JevFinding.Outcome.PASSED, "");
		}
		return new JevFinding(criterion, null, JevFinding.Outcome.FAILED,
				format("%s: %s", criterion.name(), criterion.defect()));
	}

	private JevFinding evaluate(QuestionCriterion criterion, @Nullable Answer answer, Set<String> undecided) {
		if (answer == null) {
			undecided.add(criterion.name());
			return error(criterion, new UnknownAnswer(null, Map.of()),
					format("%s: the service returned no answer for this criterion", criterion.name()));
		}
		if (answer instanceof NoulAnswer noul) {
			return evaluateNoul(criterion, noul);
		}
		if (answer instanceof ScoreAnswer score) {
			return evaluateScore(criterion, score, undecided);
		}
		if (answer instanceof ChoiceAnswer choice) {
			return evaluateChoice(criterion, choice, undecided);
		}
		undecided.add(criterion.name());
		return error(criterion, answer,
				format("%s: the model returned an answer kind this SDK does not understand", criterion.name()));
	}

	/**
	 * The instrument failed for this criterion, which says nothing about the answer. It
	 * blocks only when the caller chose {@code failOnError}.
	 */
	private JevFinding error(QuestionCriterion criterion, Answer answer, String detail) {
		return new JevFinding(criterion, answer,
				this.failOnError ? JevFinding.Outcome.FAILED : JevFinding.Outcome.ERROR, detail);
	}

	private JevFinding evaluateNoul(QuestionCriterion criterion, NoulAnswer answer) {
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

	private JevFinding evaluateScore(QuestionCriterion criterion, ScoreAnswer answer, Set<String> undecided) {
		boolean passes = scorePasses(criterion, answer);
		double support = scoreSupport(criterion, answer, passes);
		JevFinding.Outcome inconclusive = checkConfidence(support);
		if (inconclusive != null) {
			undecided.add(criterion.name());
			return new JevFinding(criterion, answer, inconclusive, format(
					"%s: the rubric did not settle whether this reaches %.2f (%.2f of the probability supports the verdict, needs at least %.2f)",
					criterion.name(), criterion.minimum(), support, this.minConfidence));
		}
		if (passes) {
			return new JevFinding(criterion, answer, JevFinding.Outcome.PASSED, "");
		}

		// Describe the level the score itself lands on, not the most probable level: the
		// two can disagree on a spread distribution, and quoting a label that contradicts
		// the number would be worse than useless as feedback.
		// Clamped below the first passing level: a failing 1.8 rounds to 2, and telling the
		// model it reached the level it is asked to reach would contradict itself.
		int reachedLevel = Math.min(roundToLevel(criterion, answer.value()),
				Math.max(0, (int) Math.ceil(criterion.minimum()) - 1));
		String reached = describeLevel(criterion, answer, reachedLevel);
		String required = describeLevel(criterion, answer, (int) Math.ceil(criterion.minimum()));
		String detail = format("%s: rated \"%s\" (%.2f), needs to reach %.2f", criterion.name(), reached,
				answer.value(), criterion.minimum());
		if (!required.isEmpty()) {
			detail += format(" which is \"%s\"", required);
		}
		return new JevFinding(criterion, answer, JevFinding.Outcome.FAILED, detail);
	}

	private JevFinding evaluateChoice(QuestionCriterion criterion, ChoiceAnswer answer, Set<String> undecided) {
		boolean passes = choicePasses(criterion, answer);
		double support = choiceSupport(criterion, answer, passes);
		JevFinding.Outcome inconclusive = checkConfidence(support);
		if (inconclusive != null) {
			undecided.add(criterion.name());
			return new JevFinding(criterion, answer, inconclusive, format(
					"%s: the options did not settle whether this is one of %s (%.2f of the probability supports the verdict, needs at least %.2f)",
					criterion.name(), criterion.acceptedOptions(), support, this.minConfidence));
		}
		if (passes) {
			return new JevFinding(criterion, answer, JevFinding.Outcome.PASSED, "");
		}
		return new JevFinding(criterion, answer, JevFinding.Outcome.FAILED, format(
				"%s: %.2f of the probability is on options outside %s (most likely \"%s\")",
				criterion.name(), support, criterion.acceptedOptions(), answer.value()));
	}

	/**
	 * Whether a score passes: when at least half of its probability sits on levels at or
	 * above the first passing level. Deciding from the same split that
	 * {@link #scoreSupport} measures keeps the verdict and its support in agreement; the
	 * expected value alone can land on one side while most of the probability sits on the
	 * other. Without probabilities, the value decides.
	 */
	static boolean scorePasses(QuestionCriterion criterion, ScoreAnswer answer) {
		if (answer.probabilities().isEmpty() || total(answer.probabilities().values()) <= 0.0d) {
			return answer.value() >= criterion.minimum();
		}
		return scoreSupport(criterion, answer, true) >= 0.5d;
	}

	/**
	 * Whether a choice passes: when at least half of its probability sits on the accepted
	 * options, even if the single most probable label is not one of them. Without
	 * probabilities, the selected label decides.
	 */
	static boolean choicePasses(QuestionCriterion criterion, ChoiceAnswer answer) {
		if (answer.probabilities().isEmpty() || total(answer.probabilities().values()) <= 0.0d) {
			return criterion.acceptedOptions().contains(answer.value());
		}
		return choiceSupport(criterion, answer, true) >= 0.5d;
	}

	private static double total(java.util.Collection<Double> probabilities) {
		return probabilities.stream().mapToDouble(Double::doubleValue).sum();
	}

	/**
	 * How much of a score's probability supports the pass or fail decision: the mass on
	 * the levels at or above the first passing level when it passes, the mass below it
	 * when it fails. That, not {@link ScoreAnswer#confidence()}, is what decides whether
	 * a verdict can be acted on — a distribution split between two passing levels is not
	 * peaked, yet it leaves no doubt the criterion passed.
	 */
	static double scoreSupport(QuestionCriterion criterion, ScoreAnswer answer, boolean passes) {
		if (answer.probabilities().isEmpty()) {
			return answer.confidence();
		}
		int firstPassingLevel = (int) Math.ceil(criterion.minimum());
		double total = 0.0d;
		double passing = 0.0d;
		for (Map.Entry<Integer, Double> entry : answer.probabilities().entrySet()) {
			total += entry.getValue();
			if (entry.getKey() >= firstPassingLevel) {
				passing += entry.getValue();
			}
		}
		if (total <= 0.0d) {
			return answer.confidence();
		}
		return (passes ? passing : total - passing) / total;
	}

	/**
	 * How much of a choice's probability supports the decision: the summed mass on the
	 * accepted options when it passes, on the rest when it fails.
	 */
	static double choiceSupport(QuestionCriterion criterion, ChoiceAnswer answer, boolean passes) {
		if (answer.probabilities().isEmpty()) {
			return answer.confidence();
		}
		double total = 0.0d;
		double accepted = 0.0d;
		for (Map.Entry<String, Double> entry : answer.probabilities().entrySet()) {
			total += entry.getValue();
			if (criterion.acceptedOptions().contains(entry.getKey())) {
				accepted += entry.getValue();
			}
		}
		if (total <= 0.0d) {
			return answer.confidence();
		}
		return (passes ? accepted : total - accepted) / total;
	}

	/**
	 * Nouls carry no confidence statistic by design, so only choices and scores can land
	 * here.
	 * @param support the probability that supports the verdict
	 * @return the outcome to report, or {@code null} when the verdict is decisive enough
	 */
	private JevFinding.@Nullable Outcome checkConfidence(double support) {
		if (support >= this.minConfidence) {
			return null;
		}
		return this.failOnInconclusive ? JevFinding.Outcome.FAILED : JevFinding.Outcome.INCONCLUSIVE;
	}

	private String describeFalseSide(QuestionCriterion criterion) {
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

	private int roundToLevel(QuestionCriterion criterion, double value) {
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
	private String describeLevel(QuestionCriterion criterion, ScoreAnswer answer, int level) {
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
			undecided.forEach(finding -> feedback.append("- ").append(finding.detail()).append(System.lineSeparator()));
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

		private double minConfidence = DEFAULT_MIN_CONFIDENCE;

		private boolean failOnInconclusive = false;

		private boolean failOnError = false;

		private boolean failFast = false;

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

		/**
		 * Adds a check answered in code rather than by Jev. Use it for anything the input
		 * already settles — whether a tool was called, whether the answer parses — so the
		 * model is only asked what genuinely needs judgement.
		 * @param name the name the finding will carry
		 * @param check passes when it returns {@code true}
		 * @param defect what went wrong when it returns {@code false}, handed back as
		 * feedback
		 * @return this builder
		 */
		public Builder check(String name, Predicate<JevJudgeInput> check, String defect) {
			return criterion(JevCriterion.check(name, check, defect));
		}

		/**
		 * Adds a pre-built criterion of either kind.
		 * @param criterion the criterion
		 * @return this builder
		 */
		public Builder criterion(JevCriterion criterion) {
			Assert.notNull(criterion, "criterion must not be null");
			if (criterion instanceof QuestionCriterion question && question.dependsOn() != null) {
				validateDependency(question.name(), question.dependsOn());
			}
			Assert.isTrue(this.criteria.stream().noneMatch(existing -> existing.name().equals(criterion.name())),
					"a criterion named '" + criterion.name() + "' is already declared");
			this.criteria.add(criterion);
			return this;
		}

		private void validateDependency(String name, JevCriterion.Dependency dependency) {
			// Only an earlier criterion can be depended on: that rules out cycles, and it
			// is the order findings are resolved in.
			JevCriterion target = this.criteria.stream()
				.filter(existing -> existing.name().equals(dependency.criterion()))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("criterion '" + name + "' depends on '"
						+ dependency.criterion() + "', which must be declared before it"));
			if (!dependency.chosen().isEmpty()) {
				Assert.isTrue(target instanceof QuestionCriterion question && question.question() instanceof Choice,
						"criterion '" + name + "' uses whenChosen on '" + dependency.criterion()
								+ "', which is not a choice");
				Choice choice = (Choice) ((QuestionCriterion) target).question();
				dependency.chosen()
					.forEach(label -> Assert.isTrue(choice.criteria().containsKey(label),
							"whenChosen label '" + label + "' is not one of the options of '" + dependency.criterion()
									+ "' " + choice.criteria().keySet()));
			}
		}

		/**
		 * Sets how much of a choice's or score's probability must support its verdict
		 * before the verdict is acted on; below it the criterion is
		 * {@link JevFinding.Outcome#INCONCLUSIVE}. For a score that is the mass on the
		 * verdict's side of {@code minimum}, for a choice the mass on the accepted (or
		 * the rejected) options — not the answer's own {@code confidence}, which is low
		 * whenever the mass is split, even between two passing levels. Raise it for
		 * consequential decisions and lower it for reversible ones; nouls are unaffected.
		 * @param minConfidence the inclusive lower bound, between {@code 0} and
		 * {@code 1}; {@link #DEFAULT_MIN_CONFIDENCE} by default
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
		 * Whether a criterion the service could not answer blocks the response. Off by
		 * default: a missing or unreadable answer is a failure of the instrument, not of
		 * the answer, and is reported as {@link JevFinding.Outcome#ERROR}. Turn it on
		 * where an unjudged criterion must never pass.
		 * @param failOnError whether to treat an error as failed
		 * @return this builder
		 */
		public Builder failOnError(boolean failOnError) {
			this.failOnError = failOnError;
			return this;
		}

		/**
		 * Whether a failed code check skips the Jev call. When on and any {@link #check
		 * check} fails, the verdict already fails, so the question criteria are reported
		 * {@link JevFinding.Outcome#NOT_APPLICABLE} and no request is made. Off by
		 * default, so every criterion is still answered and the feedback names every
		 * defect at once.
		 * @param failFast whether to skip the call after a failed check
		 * @return this builder
		 */
		public Builder failFast(boolean failFast) {
			this.failFast = failFast;
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
			// A judge of checks alone never needs Jev; it is a predicate, and would leave
			// the verdict without a response to carry.
			Assert.isTrue(this.criteria.stream().anyMatch(QuestionCriterion.class::isInstance),
					"a judge must declare at least one question criterion; plain code checks need no judge");
			return new JevJudge(this.typeSafeClient, this.criteria, this.minConfidence, this.failOnInconclusive,
					this.failOnError, this.failFast, this.feedbackRenderer);
		}

	}

}
