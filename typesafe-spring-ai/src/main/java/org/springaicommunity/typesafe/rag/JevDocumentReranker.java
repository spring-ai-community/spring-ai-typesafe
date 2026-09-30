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



import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.JevBatchResult;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.exception.TypeSafeException;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.util.Assert;

/**
 * Reorders retrieved documents by asking Jev, one document at a time, whether each could
 * actually answer the query.
 *
 * <p>
 * Vector search ranks by embedding proximity, which is a good way to find candidates and a
 * poor way to order them: a passage can be about the right subject and still not contain the
 * answer. Asking a direct question about each candidate is a different measurement, and it
 * is the one the final ordering should use. The TypeSafe reranking cookbook reports top-1
 * accuracy moving from 5% to 18% on its evaluation set from exactly this step.
 *
 * <p>
 * This is genuinely one call per document — the pairs are independent, and there is no way
 * to phrase "score each of these forty passages" as a single question that stays honest. The
 * calls are fanned out concurrently; {@link JevBatchOptions} controls how widely.
 *
 * <p>
 * Each surviving document carries its score under {@link #SCORE_METADATA_KEY}, so a later
 * stage or a log can see why the order came out as it did. A document whose call failed is
 * kept rather than dropped, because a transport failure is not evidence about relevance —
 * but it is placed after every document this reranker actually scored, so a failure can
 * never evict a known-good passage under {@code topK}.
 *
 * <pre>{@code
 * RetrievalAugmentationAdvisor.builder()
 *     .documentRetriever(retriever)
 *     .documentPostProcessors(JevDocumentReranker.builder(typeSafeClient).topK(5).build())
 *     .build();
 * }</pre>
 *
 * @author Christian Tzolov
 */
public class JevDocumentReranker implements DocumentPostProcessor {

	private static final Logger logger = LoggerFactory.getLogger(JevDocumentReranker.class);

	/** Metadata key holding the score this reranker gave a document. */
	public static final String SCORE_METADATA_KEY = "jev.rerank.score";

	/** The name of the question asked about each document. */
	public static final String QUESTION_NAME = "answers_query";

	/** The query field of the state. */
	public static final String QUERY_FIELD = "query";

	/** The passage field of the state. */
	public static final String PASSAGE_FIELD = "passage";

	private final TypeSafeClient typeSafeClient;

	private final Noul question;

	private final @Nullable Integer topK;

	private final double minimumScore;

	private final JevBatchOptions batchOptions;

	private JevDocumentReranker(TypeSafeClient typeSafeClient, Noul question, @Nullable Integer topK,
			double minimumScore, JevBatchOptions batchOptions) {
		this.typeSafeClient = typeSafeClient;
		this.question = question;
		this.topK = topK;
		this.minimumScore = minimumScore;
		this.batchOptions = batchOptions;
	}

	@Override
	public List<Document> process(Query query, List<Document> documents) {
		Assert.notNull(query, "query must not be null");
		Assert.notNull(documents, "documents must not be null");
		if (documents.isEmpty()) {
			return List.of();
		}

		List<org.springaicommunity.typesafe.question.SystemOneRequest> requests = new ArrayList<>(documents.size());
		for (Document document : documents) {
			requests.add(org.springaicommunity.typesafe.question.SystemOneRequest.builder()
				.state(JsonContent.object(QUERY_FIELD, query.text(), PASSAGE_FIELD, text(document)))
				.model(this.typeSafeClient.defaultModel())
				.question(QUESTION_NAME, this.question)
				.build());
		}

		List<JevBatchResult<SystemOneResponse>> results = this.typeSafeClient.systemOneAll(requests,
				this.batchOptions);

		List<Scored> scored = new ArrayList<>(documents.size());
		int unscored = 0;
		TypeSafeException firstFailure = null;
		for (int i = 0; i < documents.size(); i++) {
			JevBatchResult<SystemOneResponse> result = results.get(i);
			// A failed call says nothing about the document, so it is recorded as unknown
			// rather than as zero, which would read as "judged irrelevant". A response
			// without a usable answer counts the same: it must not fail the whole request.
			Double score = null;
			TypeSafeException failure = result.failure();
			if (result.succeeded()) {
				try {
					score = result.orThrow().noulValue(QUESTION_NAME);
				}
				catch (TypeSafeException ex) {
					failure = ex;
				}
			}
			if (score == null) {
				unscored++;
				if (firstFailure == null) {
					firstFailure = failure;
				}
			}
			scored.add(new Scored(i, documents.get(i), score));
		}
		if (unscored > 0) {
			// Not silently: when every call fails this is the retriever's order again.
			logger.warn("Jev could not score {} of {} passages, keeping them unscored after the scored ones: {}",
					unscored, documents.size(), firstFailure == null ? "unknown failure" : firstFailure.getMessage());
		}

		List<Scored> kept = scored.stream()
			.filter(entry -> entry.score == null || entry.score >= this.minimumScore)
			.toList();

		// Scored documents first, in descending order; then the unscored ones, keeping the
		// order they arrived in. A document whose call failed is kept — a transport error
		// is not evidence about a passage — but it does not outrank one this reranker
		// actually judged, so under topK a failure can never evict a known-good passage.
		List<Document> reordered = new ArrayList<>(kept.size());
		kept.stream()
			.filter(entry -> entry.score() != null)
			.sorted(Comparator.comparingDouble((Scored entry) -> entry.score())
				.reversed()
				.thenComparingInt(Scored::originalIndex))
			.forEach(entry -> reordered.add(entry.withScoreMetadata()));
		kept.stream().filter(entry -> entry.score() == null).forEach(entry -> reordered.add(entry.document()));

		int limit = (this.topK == null) ? reordered.size() : Math.min(this.topK, reordered.size());
		return List.copyOf(reordered.subList(0, limit));
	}

	private static String text(Document document) {
		String text = document.getText();
		return text == null ? "" : text;
	}

	private record Scored(int originalIndex, Document document, @Nullable Double score) {

		/**
		 * A copy carrying the score, rather than a write into the retriever's own
		 * instance: a document dropped by {@code topK} must not be left wearing a score,
		 * and a cached document reused across two runs must not keep the first run's.
		 *
		 * <p>
		 * The map is rebuilt explicitly because {@code Document.mutate()} hands the
		 * builder the <em>same</em> metadata map, so {@code metadata(key, value)} would
		 * write straight back into the original document.
		 */
		Document withScoreMetadata() {
			if (this.score == null) {
				return this.document;
			}
			Map<String, Object> metadata = new LinkedHashMap<>(this.document.getMetadata());
			metadata.put(SCORE_METADATA_KEY, this.score);
			return this.document.mutate().metadata(metadata).build();
		}
	}

	/**
	 * @param typeSafeClient the client
	 * @return a new builder
	 */
	public static Builder builder(TypeSafeClient typeSafeClient) {
		return new Builder(typeSafeClient);
	}

	/**
	 * Builder for {@link JevDocumentReranker}.
	 */
	public static final class Builder {

		private static final Noul DEFAULT_QUESTION = Noul.builder()
			.instructions(JsonContent.object("question", "Could the `passage` answer the `query`?", "focus",
					"Whether the passage states information that answers the query, not merely whether "
							+ "it covers the same subject."))
			.whenTrue("The passage contains information that answers the query")
			.whenFalse("The passage is about something else, or mentions the subject without answering")
			.build();

		private final TypeSafeClient typeSafeClient;

		private Noul question = DEFAULT_QUESTION;

		private @Nullable Integer topK;

		private double minimumScore = 0.0d;

		private JevBatchOptions batchOptions = JevBatchOptions.defaults();

		private Builder(TypeSafeClient typeSafeClient) {
			Assert.notNull(typeSafeClient, "typeSafeClient must not be null");
			this.typeSafeClient = typeSafeClient;
		}

		/**
		 * Replaces the relevance question. Write it about {@code query} and {@code passage},
		 * which are the two fields of the state.
		 * @param question the question
		 * @return this builder
		 */
		public Builder question(Noul question) {
			Assert.notNull(question, "question must not be null");
			this.question = question;
			return this;
		}

		/**
		 * @param topK how many documents to keep after reordering
		 * @return this builder
		 */
		public Builder topK(int topK) {
			Assert.isTrue(topK >= 1, "topK must be at least 1");
			this.topK = topK;
			return this;
		}

		/**
		 * Drops documents scoring below a bar. Left at zero, nothing is dropped and this is
		 * purely a reordering.
		 * @param minimumScore the lowest score worth keeping
		 * @return this builder
		 */
		public Builder minimumScore(double minimumScore) {
			Assert.isTrue(minimumScore >= 0.0d && minimumScore <= 1.0d, "minimumScore must be between 0 and 1");
			this.minimumScore = minimumScore;
			return this;
		}

		/**
		 * @param batchOptions how widely to fan the per-document calls out
		 * @return this builder
		 */
		public Builder batchOptions(JevBatchOptions batchOptions) {
			Assert.notNull(batchOptions, "batchOptions must not be null");
			this.batchOptions = batchOptions;
			return this;
		}

		public JevDocumentReranker build() {
			return new JevDocumentReranker(this.typeSafeClient, this.question, this.topK, this.minimumScore,
					this.batchOptions);
		}

	}

}
