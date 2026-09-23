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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * A {@link JevJudge} behind Spring AI's {@link Evaluator} interface.
 *
 * <p>
 * Spring AI's own evaluators — {@code RelevancyEvaluator} and
 * {@code FactCheckingEvaluator} — prompt a second chat model and read a verdict out of
 * its prose, which comes down to {@code "yes".equalsIgnoreCase(response)}. That fails in
 * two directions: the model may answer in a form the parser does not expect, and one
 * yes-or-no cannot say which of several things was wrong. This evaluator asks typed
 * questions instead, so the verdict cannot come back malformed and every criterion keeps
 * its own threshold.
 *
 * <p>
 * The mapping is lossy in one place and the loss is worth naming:
 * {@link EvaluationResponse} carries a single {@code score}, while a judge holds one per
 * criterion. The single score is the fraction of criteria that passed, and the
 * per-criterion detail is preserved under {@link #FINDINGS_METADATA_KEY} for callers who
 * want it. If the per-criterion view is what you are after, use {@link JevJudge#judge}
 * directly and read the {@link JevVerdict}.
 *
 * <pre>{@code
 * Evaluator evaluator = new JevEvaluator(judge);
 * EvaluationResponse result = evaluator.evaluate(
 *         new EvaluationRequest(userQuestion, retrievedDocuments, assistantAnswer));
 * }</pre>
 *
 * @author Christian Tzolov
 */
public class JevEvaluator implements Evaluator {

	/** Metadata key holding the {@link JevVerdict} the evaluation came from. */
	public static final String VERDICT_METADATA_KEY = "jevVerdict";

	/** Metadata key holding a per-criterion outcome map. */
	public static final String FINDINGS_METADATA_KEY = "jevFindings";

	/**
	 * The state field carrying the supporting documents, one entry per document, when the
	 * request has any; see {@link JevJudgeInput#CONTEXT_FIELD}.
	 */
	public static final String CONTEXT_FIELD = JevJudgeInput.CONTEXT_FIELD;

	private final JevJudge judge;

	/**
	 * @param judge the judge to evaluate with
	 */
	public JevEvaluator(JevJudge judge) {
		Assert.notNull(judge, "judge must not be null");
		this.judge = judge;
	}

	@Override
	public EvaluationResponse evaluate(EvaluationRequest evaluationRequest) {
		Assert.notNull(evaluationRequest, "evaluationRequest must not be null");

		// Retrieved documents are the evidence a groundedness criterion needs, so they go
		// into the judged state as their own field, one entry per document, rather than
		// being flattened into the question.
		List<String> context = evaluationRequest.getDataList() == null ? List.of()
				: evaluationRequest.getDataList().stream().map(Document::getText).filter(StringUtils::hasText).toList();

		JevVerdict verdict = this.judge.judge(JevJudgeInput.builder()
			.question(evaluationRequest.getUserText())
			.answer(evaluationRequest.getResponseContent())
			.context(context)
			.build());

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put(VERDICT_METADATA_KEY, verdict);
		Map<String, String> findings = new LinkedHashMap<>();
		verdict.findings().forEach(finding -> findings.put(finding.name(), finding.outcome().name()));
		metadata.put(FINDINGS_METADATA_KEY, findings);

		return new EvaluationResponse(verdict.passed(), passRate(verdict), verdict.feedback(), metadata);
	}

	/**
	 * @return the judge behind this evaluator
	 */
	public JevJudge judge() {
		return this.judge;
	}

	/**
	 * Collapses the verdict onto the single float {@link EvaluationResponse} has room
	 * for: the fraction of the criteria that applied which passed. An inconclusive or
	 * errored criterion counts as neither passed nor failed, so it lowers the rate
	 * without being treated as a failure — the same stance {@link JevVerdict#passed()}
	 * takes. A criterion that did not apply is left out altogether.
	 */
	private static float passRate(JevVerdict verdict) {
		long applicable = verdict.findings()
			.stream()
			.filter(finding -> finding.outcome() != JevFinding.Outcome.NOT_APPLICABLE)
			.count();
		if (applicable == 0) {
			return verdict.passed() ? 1.0f : 0.0f;
		}
		long passed = verdict.findings()
			.stream()
			.filter(finding -> finding.outcome() == JevFinding.Outcome.PASSED)
			.count();
		return (float) passed / applicable;
	}

}
