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

package org.springaicommunity.typesafe.toolsearch;



import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * A {@link ToolIndex} that picks tools by asking Jev, rather than by keyword or embedding
 * proximity.
 *
 * <p>
 * The interesting difference is not accuracy, it is being able to say <em>no</em>. A Lucene
 * or vector index ranks whatever it holds and hands back a best match; nothing in either
 * mechanism can report that the query is about something the toolset does not do. The same
 * is true of the {@link Choice} here on its own, since choice probabilities are a
 * distribution over the options and therefore always sum to one — something is always
 * ranked first. So this index asks a second, independent question: <em>does any of these
 * tools serve the request at all?</em> Below {@link Builder#applicabilityThreshold(double)}
 * the result is empty, and the model is told about no tools instead of a plausible wrong
 * one.
 *
 * <p>
 * Both questions ride in a single request, so a search costs one call regardless of how many
 * tools are indexed.
 *
 * <p>
 * Every indexed tool becomes an option on the choice, and the whole set is sent on each
 * search. That is fine for the tens of tools a session realistically holds and wrong for
 * thousands; past a few hundred, put a cheap index in front of this one and let Jev choose
 * among its shortlist.
 *
 * @author Christian Tzolov
 */
public class JevToolIndex implements ToolIndex {

	/** The question name carrying the tool selection. */
	public static final String SELECTION_QUESTION = "best_tool";

	/** The question name carrying the "any tool at all" check. */
	public static final String APPLICABILITY_QUESTION = "any_tool_applies";

	/** The request field of the state. */
	public static final String REQUEST_FIELD = "user_request";

	/**
	 * The state field listing what the tools do. The applicability question cannot be
	 * answered without it: "does any tool serve this request" is meaningless unless the
	 * tools are in the state, and a model asked it blind will guess.
	 */
	public static final String TOOLS_FIELD = "available_tools";

	private static final Logger logger = LoggerFactory.getLogger(JevToolIndex.class);

	private final TypeSafeClient typeSafeClient;

	private final double applicabilityThreshold;

	private final double minimumRelevance;

	private final Map<String, Map<String, ToolReference>> sessions = new ConcurrentHashMap<>();

	private JevToolIndex(TypeSafeClient typeSafeClient, double applicabilityThreshold, double minimumRelevance) {
		this.typeSafeClient = typeSafeClient;
		this.applicabilityThreshold = applicabilityThreshold;
		this.minimumRelevance = minimumRelevance;
	}

	@Override
	public void indexTool(String sessionId, ToolReference toolReference) {
		Assert.hasText(sessionId, "sessionId must not be empty");
		Assert.notNull(toolReference, "toolReference must not be null");
		Assert.hasText(toolReference.toolName(), "toolReference must name a tool");
		// A ToolIndex is a shared singleton: the advisor re-indexes a session while the
		// search tool reads it, possibly on another thread. Both maps have to be concurrent,
		// and the inner one is iterated by candidates() as well as written here.
		this.sessions.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>())
			.put(toolReference.toolName(), toolReference);
	}

	@Override
	public void clearIndex(String sessionId) {
		Assert.hasText(sessionId, "sessionId must not be empty");
		this.sessions.remove(sessionId);
	}

	@Override
	public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
		Assert.notNull(toolSearchRequest, "toolSearchRequest must not be null");

		List<ToolReference> candidates = candidates(toolSearchRequest);
		if (candidates.isEmpty()) {
			return empty();
		}
		if (candidates.size() == 1) {
			// A choice needs something to choose between, so with one candidate the only
			// meaningful question is whether it applies at all.
			return searchSingleCandidate(toolSearchRequest, candidates.get(0));
		}

		Choice.Builder selection = Choice.builder()
			.instructions("Which of these tools best serves the `user_request`?");
		for (ToolReference candidate : candidates) {
			selection.option(candidate.toolName(), describe(candidate));
		}

		Map<String, Question> questions = new LinkedHashMap<>();
		questions.put(SELECTION_QUESTION, selection.build());
		questions.put(APPLICABILITY_QUESTION, applicabilityQuestion());

		SystemOneResponse response = this.typeSafeClient
			.systemOne(Map.of(REQUEST_FIELD, toolSearchRequest.query(), TOOLS_FIELD, catalogue(candidates)),
					questions);

		double applicability = response.noulValue(APPLICABILITY_QUESTION);
		if (applicability < this.applicabilityThreshold) {
			if (logger.isDebugEnabled()) {
				logger.debug("Jev tool search found nothing applicable for '{}' (applicability {} < {})",
						toolSearchRequest.query(), applicability, this.applicabilityThreshold);
			}
			return empty();
		}

		Map<String, ToolReference> byName = new LinkedHashMap<>();
		candidates.forEach(candidate -> byName.put(candidate.toolName(), candidate));

		// optionsAbove returns the labels in descending probability, which is the ranking.
		List<String> ranked = response.choice(SELECTION_QUESTION).optionsAbove(this.minimumRelevance);
		if (ranked.isEmpty()) {
			// probabilities defaults to empty when the response omits the field, which would
			// otherwise discard a selection the service did make. The chosen label is the
			// answer either way, so fall back to it rather than reporting no tools.
			ranked = List.of(response.choiceValue(SELECTION_QUESTION));
		}
		List<ToolReference> matches = new ArrayList<>();
		int limit = limit(toolSearchRequest, candidates.size());
		for (String name : ranked) {
			if (matches.size() >= limit) {
				break;
			}
			ToolReference reference = byName.get(name);
			if (reference != null) {
				matches.add(scored(reference, response.choice(SELECTION_QUESTION).probabilityOf(name)));
			}
		}
		return response(matches);
	}

	private ToolSearchResponse searchSingleCandidate(ToolSearchRequest toolSearchRequest, ToolReference only) {
		SystemOneResponse response = this.typeSafeClient.systemOne(
				Map.of(REQUEST_FIELD, toolSearchRequest.query(), "tool", describe(only)),
				Map.of(APPLICABILITY_QUESTION,
						Noul.builder()
							.instructions("Does the `tool` serve the `user_request`?")
							.whenTrue("The tool does what the request needs")
							.whenFalse("The request needs something this tool does not do")
							.build()));

		double applicability = response.noulValue(APPLICABILITY_QUESTION);
		return applicability < this.applicabilityThreshold ? empty()
				: response(List.of(scored(only, applicability)));
	}

	private List<ToolReference> candidates(ToolSearchRequest toolSearchRequest) {
		Map<String, ToolReference> session = this.sessions.get(toolSearchRequest.sessionId());
		if (session == null || session.isEmpty()) {
			return List.of();
		}
		List<ToolReference> candidates = new ArrayList<>(session.values());
		String category = toolSearchRequest.categoryFilter();
		if (StringUtils.hasText(category)) {
			candidates = candidates.stream().filter(candidate -> matchesCategory(candidate, category)).toList();
		}
		return candidates;
	}

	private static boolean matchesCategory(ToolReference candidate, String category) {
		String summary = candidate.summary();
		return candidate.toolName().toLowerCase().contains(category.toLowerCase())
				|| (summary != null && summary.toLowerCase().contains(category.toLowerCase()));
	}

	private static int limit(ToolSearchRequest toolSearchRequest, int candidateCount) {
		Integer maxResults = toolSearchRequest.maxResults();
		return (maxResults == null || maxResults <= 0) ? candidateCount : maxResults;
	}

	private static String describe(ToolReference reference) {
		String summary = reference.summary();
		return StringUtils.hasText(summary) ? summary : reference.toolName();
	}

	private static ToolReference scored(ToolReference reference, double relevance) {
		return ToolReference.builder()
			.toolName(reference.toolName())
			.summary(reference.summary())
			.relevanceScore(relevance)
			.build();
	}

	/**
	 * The tools as data in the state, so the applicability question has something to judge
	 * the request against.
	 */
	private static List<Map<String, String>> catalogue(List<ToolReference> candidates) {
		List<Map<String, String>> catalogue = new ArrayList<>(candidates.size());
		for (ToolReference candidate : candidates) {
			Map<String, String> entry = new LinkedHashMap<>();
			entry.put("name", candidate.toolName());
			entry.put("does", describe(candidate));
			catalogue.add(entry);
		}
		return catalogue;
	}

	private static Noul applicabilityQuestion() {
		return Noul.builder()
			.instructions(Map.of("question",
					"Does any tool listed in `available_tools` do what the `user_request` needs?", "inspect",
					"available_tools", "focus",
					"Whether some listed tool performs the action the request calls for. A request that is "
							+ "merely about a similar subject is not the same as one a listed tool can carry out."))
			.whenTrue("Some tool in the list performs what the request asks for")
			.whenFalse("The request needs an action that no tool in the list performs, or needs no tool at all")
			.build();
	}

	private static ToolSearchResponse response(List<ToolReference> matches) {
		return ToolSearchResponse.builder().toolReferences(matches).totalMatches(matches.size()).build();
	}

	private static ToolSearchResponse empty() {
		return response(List.of());
	}

	/**
	 * @param typeSafeClient the client
	 * @return a new builder
	 */
	public static Builder builder(TypeSafeClient typeSafeClient) {
		return new Builder(typeSafeClient);
	}

	/**
	 * Builder for {@link JevToolIndex}.
	 */
	public static final class Builder {

		private final TypeSafeClient typeSafeClient;

		private double applicabilityThreshold = 0.5d;

		private double minimumRelevance = 0.0d;

		private Builder(TypeSafeClient typeSafeClient) {
			Assert.notNull(typeSafeClient, "typeSafeClient must not be null");
			this.typeSafeClient = typeSafeClient;
		}

		/**
		 * How sure the service must be that <em>some</em> tool fits before any is returned.
		 * Raise it where calling the wrong tool is worse than calling none.
		 * @param applicabilityThreshold the threshold, between 0 and 1
		 * @return this builder
		 */
		public Builder applicabilityThreshold(double applicabilityThreshold) {
			Assert.isTrue(applicabilityThreshold >= 0.0d && applicabilityThreshold <= 1.0d,
					"applicabilityThreshold must be between 0 and 1");
			this.applicabilityThreshold = applicabilityThreshold;
			return this;
		}

		/**
		 * Drops tools whose share of the selection distribution is below this. Left at zero,
		 * every candidate is returned in ranked order.
		 * @param minimumRelevance the threshold, between 0 and 1
		 * @return this builder
		 */
		public Builder minimumRelevance(double minimumRelevance) {
			Assert.isTrue(minimumRelevance >= 0.0d && minimumRelevance <= 1.0d,
					"minimumRelevance must be between 0 and 1");
			this.minimumRelevance = minimumRelevance;
			return this;
		}

		public JevToolIndex build() {
			return new JevToolIndex(this.typeSafeClient, this.applicabilityThreshold, this.minimumRelevance);
		}

	}

}
