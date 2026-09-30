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

package org.springaicommunity.typesafe.rag;



import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.MockTypeSafeServer;

import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springaicommunity.typesafe.response.Usage;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * The two RAG post-processors. Both score one document per call, so the responses here are
 * derived from the request that asked for them rather than queued in a fixed order.
 *
 * @author Christian Tzolov
 */
@ExtendWith(OutputCaptureExtension.class)
class JevRagPostProcessorTests {

	private static final Pattern DOC = Pattern.compile("doc-(\\d+)");

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	private final Query query = new Query("How are refresh tokens rotated?");

	@Test
	void reordersDocumentsByHowWellTheyAnswerTheQuery() {
		// doc-1 scores 0.1, doc-7 scores 0.7, doc-9 scores 0.9.
		respondPerDocument(3, index -> "{\"model\":\"jev-1.13.0\",\"answers\":{\"answers_query\":"
				+ "{\"type\":\"noul\",\"noul\":0." + index + "}},\"usage\":{}}");

		List<Document> reranked = JevDocumentReranker.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(3))
			.build()
			.process(this.query, documents(1, 7, 9));

		assertThat(reranked).extracting(Document::getId).containsExactly("doc-9", "doc-7", "doc-1");
		assertThat(reranked.get(0).getMetadata()).containsEntry(JevDocumentReranker.SCORE_METADATA_KEY, 0.9d);
	}

	@Test
	void keepsOnlyTheTopK() {
		respondPerDocument(3, index -> "{\"model\":\"jev-1.13.0\",\"answers\":{\"answers_query\":"
				+ "{\"type\":\"noul\",\"noul\":0." + index + "}},\"usage\":{}}");

		List<Document> reranked = JevDocumentReranker.builder(this.mock.client())
			.topK(2)
			.batchOptions(JevBatchOptions.ofConcurrency(3))
			.build()
			.process(this.query, documents(1, 7, 9));

		assertThat(reranked).extracting(Document::getId).containsExactly("doc-9", "doc-7");
	}

	@Test
	void aDocumentWhoseCallFailedIsKeptBelowTheScoredOnesRatherThanDropped() {
		// A transport failure is not evidence that a passage is irrelevant, so losing it
		// would silently shrink the context on an unrelated error.
		this.mock.server()
			.expect(ExpectedCount.times(2), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(request -> {
				int index = indexOf(request);
				if (index == 7) {
					return MockTypeSafeServer.errorResponse(500, "boom").createResponse(request);
				}
				return MockTypeSafeServer
					.jsonResponse("{\"model\":\"jev-1.13.0\",\"answers\":{\"answers_query\":"
							+ "{\"type\":\"noul\",\"noul\":0.1}},\"usage\":{}}")
					.createResponse(request);
			});

		List<Document> reranked = JevDocumentReranker.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, documents(1, 7));

		// Order, not just membership. doc-7's call failed, so it sits below the document
		// this reranker actually judged. Asserting only contains(...) hid a bug where an
		// unscored document sorted to the very top and, under topK, evicted a better one.
		assertThat(reranked).extracting(Document::getId).containsExactly("doc-1", "doc-7");
		assertThat(reranked.get(1).getMetadata()).doesNotContainKey(JevDocumentReranker.SCORE_METADATA_KEY);
	}

	@Test
	void unscoredDocumentsSinkBelowEveryScoredOne() {
		// doc-2 fails; doc-1 and doc-9 score 0.1 and 0.9. The two scored documents come
		// first in score order, and the unjudged one follows — even though it outranked
		// doc-1 on the way in, nothing here measured it, so it cannot claim a rank.
		respondPerDocument(3, index -> index == 2 ? null
				: "{\"model\":\"jev-1.13.0\",\"answers\":{\"answers_query\":"
						+ "{\"type\":\"noul\",\"noul\":0." + index + "}},\"usage\":{}}");

		List<Document> reranked = JevDocumentReranker.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, documents(1, 2, 9));

		assertThat(reranked).extracting(Document::getId).containsExactly("doc-9", "doc-1", "doc-2");
	}

	@Test
	void anUnscoredDocumentDoesNotEvictABetterOneUnderTopK() {
		// The failure mode that made this worth fixing: with topK(1) a failed call used to
		// win the only slot, throwing away the passage that actually answered the query.
		respondPerDocument(2, index -> index == 2 ? null
				: "{\"model\":\"jev-1.13.0\",\"answers\":{\"answers_query\":"
						+ "{\"type\":\"noul\",\"noul\":0.9}},\"usage\":{}}");

		List<Document> reranked = JevDocumentReranker.builder(this.mock.client())
			.topK(1)
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, documents(2, 9));

		assertThat(reranked).extracting(Document::getId).containsExactly("doc-9");
	}

	@Test
	void scoringDoesNotMutateTheRetrieversOwnDocuments() {
		respondPerDocument(1, index -> "{\"model\":\"jev-1.13.0\",\"answers\":{\"answers_query\":"
				+ "{\"type\":\"noul\",\"noul\":0.9}},\"usage\":{}}");

		List<Document> original = documents(9);
		List<Document> reranked = JevDocumentReranker.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, original);

		assertThat(reranked.get(0).getMetadata()).containsKey(JevDocumentReranker.SCORE_METADATA_KEY);
		assertThat(original.get(0).getMetadata()).doesNotContainKey(JevDocumentReranker.SCORE_METADATA_KEY);
	}

	@Test
	void aRerankingResponseWithoutTheAnswerLeavesTheDocumentUnscoredRatherThanFailingTheRequest(
			CapturedOutput output) {
		// The filter already tolerated this; the reranker threw out of process().
		respondPerDocument(2, index -> index == 7
				? "{\"model\":\"jev-1.13.0\",\"answers\":{\"something_else\":{\"type\":\"noul\",\"noul\":0.9}},"
						+ "\"usage\":{}}"
				: "{\"model\":\"jev-1.13.0\",\"answers\":{\"answers_query\":{\"type\":\"noul\",\"noul\":0.4}},"
						+ "\"usage\":{}}");

		List<Document> reranked = JevDocumentReranker.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, documents(7, 1));

		assertThat(reranked).extracting(Document::getId).containsExactly("doc-1", "doc-7");
		assertThat(reranked.get(1).getMetadata()).doesNotContainKey(JevDocumentReranker.SCORE_METADATA_KEY);
		assertThat(output).contains("Jev could not score 1 of 2 passages");
	}

	@Test
	void sayingSoWhenPassagesCouldNotBeScreened(CapturedOutput output) {
		// Failing open is the right call, but not silently: with a bad key or an outage the
		// injection screen would otherwise look as if it had run.
		respondPerDocument(2, index -> null);

		List<Document> kept = JevDocumentFilter.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, documents(1, 7));

		assertThat(kept).extracting(Document::getId).containsExactly("doc-1", "doc-7");
		assertThat(output).contains("Jev could not screen 2 of 2 passages, passing them through unscreened");
	}

	@Test
	void aPartialScreeningResponsePassesTheDocumentThroughRatherThanFailingTheRequest() {
		// classify() reads four fixed answers. A response carrying only some of them used to
		// throw out of process() and fail the whole RAG request — worse than the transport
		// error the class already fails open on.
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse("{\"model\":\"jev-1.13.0\",\"answers\":{"
					+ "\"is_relevant\":{\"type\":\"noul\",\"noul\":0.9}},\"usage\":{}}"));

		List<Document> kept = JevDocumentFilter.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, documents(1));

		assertThat(kept).hasSize(1);
		assertThat(kept.get(0).getMetadata()).doesNotContainKey(JevDocumentFilter.CLASSIFICATION_METADATA_KEY);
	}

	@Test
	void classifyingDoesNotMutateTheRetrieversOwnDocuments() {
		respondPerDocument(1, index -> classification(0.01d, 0.1d, 0.9d, 0.9d));

		List<Document> original = documents(1);
		List<Document> kept = JevDocumentFilter.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, original);

		assertThat(kept.get(0).getMetadata()).containsKey(JevDocumentFilter.CLASSIFICATION_METADATA_KEY);
		assertThat(original.get(0).getMetadata()).doesNotContainKey(JevDocumentFilter.CLASSIFICATION_METADATA_KEY);
	}

	@Test
	void excludesAPassageThatTriesToInstructTheAnsweringSystem() {
		respondPerDocument(2, index -> index == 9 ? classification(0.99d, 0.1d, 0.9d, 0.9d)
				: classification(0.01d, 0.1d, 0.9d, 0.9d));

		List<Document> kept = JevDocumentFilter.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, documents(1, 9));

		assertThat(kept).extracting(Document::getId).containsExactly("doc-1");
		assertThat(kept.get(0).getMetadata()).containsEntry(JevDocumentFilter.CLASSIFICATION_METADATA_KEY,
				JevDocumentFilter.Classification.INCLUDED.name());
	}

	@Test
	void sendsTheScreeningRequestInTheSameOrderEveryRun() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(MockTypeSafeServer.keyOrder("/state", "query", "passage"))
			.andExpect(MockTypeSafeServer.keyOrder("/questions", "is_relevant", "contains_answer_evidence",
					"contradicts_query_premise", "contains_prompt_injection"))
			.andExpect(MockTypeSafeServer.keyOrder("/questions/contains_prompt_injection/instructions", "question",
					"focus"))
			.andRespond(MockTypeSafeServer.jsonResponse(classification(0.01d, 0.9d, 0.9d, 0.1d)));

		JevDocumentFilter.builder(this.mock.client()).build().process(this.query, documents(1));
	}

	@Test
	void sendsTheRerankingRequestInTheSameOrderEveryRun() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(MockTypeSafeServer.keyOrder("/state", "query", "passage"))
			.andExpect(MockTypeSafeServer.keyOrder("/questions/answers_query/instructions", "question", "focus"))
			.andRespond(MockTypeSafeServer.jsonResponse("{\"model\":\"jev-1.13.0\",\"answers\":{\"answers_query\":"
					+ "{\"type\":\"noul\",\"noul\":0.9}},\"usage\":{}}"));

		JevDocumentReranker.builder(this.mock.client()).build().process(this.query, documents(1));
	}

	@Test
	void keepsAContradictingPassageButMarksIt() {
		// A passage that disagrees with the query's premise is the most useful thing in the
		// result set, not noise to drop.
		respondPerDocument(1, index -> classification(0.01d, 0.95d, 0.9d, 0.9d));

		List<Document> kept = JevDocumentFilter.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, documents(1));

		assertThat(kept).hasSize(1);
		assertThat(kept.get(0).getMetadata()).containsEntry(JevDocumentFilter.CLASSIFICATION_METADATA_KEY,
				JevDocumentFilter.Classification.CONFLICTING.name());
	}

	@Test
	void appliesEveryBranchOfThePolicyInPrecedenceOrder() {
		// classify() is pure policy over four numbers, so it is exercised directly rather
		// than through HTTP. Precedence matters: safety outranks usefulness, so an
		// injecting passage is excluded even when it is relevant and full of evidence.
		JevDocumentFilter filter = JevDocumentFilter.builder(this.mock.client()).build();

		assertThat(filter.classify(answers(0.99d, 0.0d, 0.99d, 0.99d)))
			.isEqualTo(JevDocumentFilter.Classification.EXCLUDED);
		assertThat(filter.classify(answers(0.0d, 0.95d, 0.99d, 0.99d)))
			.isEqualTo(JevDocumentFilter.Classification.CONFLICTING);
		assertThat(filter.classify(answers(0.0d, 0.0d, 0.20d, 0.99d)))
			.isEqualTo(JevDocumentFilter.Classification.EXCLUDED);
		assertThat(filter.classify(answers(0.0d, 0.0d, 0.99d, 0.10d)))
			.isEqualTo(JevDocumentFilter.Classification.EXCLUDED);
		assertThat(filter.classify(answers(0.0d, 0.0d, 0.99d, 0.99d)))
			.isEqualTo(JevDocumentFilter.Classification.INCLUDED);
	}

	@Test
	void screeningFailureLeavesThePassageInPlaceRatherThanSilentlyDroppingIt() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(500, "boom"));

		List<Document> kept = JevDocumentFilter.builder(this.mock.client())
			.batchOptions(JevBatchOptions.ofConcurrency(1))
			.build()
			.process(this.query, documents(1));

		assertThat(kept).hasSize(1);
		assertThat(kept.get(0).getMetadata()).doesNotContainKey(JevDocumentFilter.CLASSIFICATION_METADATA_KEY);
	}

	@Test
	void anEmptyResultSetIsNotACall() {
		assertThat(JevDocumentReranker.builder(this.mock.client()).build().process(this.query, List.of())).isEmpty();
		assertThat(JevDocumentFilter.builder(this.mock.client()).build().process(this.query, List.of())).isEmpty();
		this.mock.server().verify();
	}

	private static SystemOneResponse answers(double injection, double contradicts, double relevant, double evidence) {
		return new SystemOneResponse("jev-1.13.0",
				java.util.Map.of("is_relevant", new NoulAnswer(relevant), "contains_answer_evidence",
						new NoulAnswer(evidence), "contradicts_query_premise", new NoulAnswer(contradicts),
						"contains_prompt_injection", new NoulAnswer(injection)),
				Usage.EMPTY, null);
	}

	private static String classification(double injection, double contradicts, double relevant, double evidence) {
		return "{\"model\":\"jev-1.13.0\",\"answers\":{" + "\"is_relevant\":{\"type\":\"noul\",\"noul\":" + relevant
				+ "},\"contains_answer_evidence\":{\"type\":\"noul\",\"noul\":" + evidence
				+ "},\"contradicts_query_premise\":{\"type\":\"noul\",\"noul\":" + contradicts
				+ "},\"contains_prompt_injection\":{\"type\":\"noul\",\"noul\":" + injection + "}},\"usage\":{}}";
	}

	/**
	 * @param bodyForIndex the response body for the document that request is about, or
	 * {@code null} to fail that one call — which is how the unscored-document cases are set
	 * up without depending on which order the batch happens to issue requests in
	 */
	private void respondPerDocument(int count, java.util.function.IntFunction<String> bodyForIndex) {
		this.mock.server()
			.expect(ExpectedCount.times(count), requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(request -> {
				String body = bodyForIndex.apply(indexOf(request));
				return (body == null) ? MockTypeSafeServer.errorResponse(500, "boom").createResponse(request)
						: MockTypeSafeServer.jsonResponse(body).createResponse(request);
			});
	}

	private static int indexOf(org.springframework.http.client.ClientHttpRequest request) {
		Matcher matcher = DOC.matcher(((MockClientHttpRequest) request).getBodyAsString());
		assertThat(matcher.find()).isTrue();
		return Integer.parseInt(matcher.group(1));
	}

	private static List<Document> documents(int... ids) {
		return java.util.Arrays.stream(ids)
			.mapToObj(id -> Document.builder().id("doc-" + id).text("passage doc-" + id).build())
			.toList();
	}

}
