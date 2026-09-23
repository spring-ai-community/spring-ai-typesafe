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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.JevBatchResult;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.ScoreAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.util.Assert;

/**
 * Asks the same questions repeatedly and reports how much the answers move.
 *
 * <p>
 * A single number tells you where an answer sits; it does not tell you whether it would
 * sit there again. That matters when a value lands near a threshold, because there the
 * difference between acting and not acting can be noise rather than judgement. Sampling a
 * question a few times and looking at the spread is how you find out which of your
 * thresholds are resting on solid ground.
 *
 * <p>
 * Each sample carries a fresh throwaway {@code uid} in its state, which is what makes the
 * draws independent rather than a repeat of one cached computation. The state is
 * otherwise untouched, and the questions are identical across samples — this measures the
 * service's own stability, not the effect of rewording.
 *
 * <pre>{@code
 * JevConsistency.Report report = JevConsistency.sample(client, state, questions, 15);
 * report.statistics().forEach((name, stats) ->
 *         System.out.printf("%-20s mean %.3f  sd %.3f  range %.3f%n",
 *                 name, stats.mean(), stats.standardDeviation(), stats.range()));
 * }</pre>
 *
 * @author Christian Tzolov
 */
public final class JevConsistency {

	/** The number of samples the TypeSafe self-consistency cookbook draws. */
	public static final int DEFAULT_SAMPLES = 15;

	/** The state field carrying the throwaway id that keeps the draws independent. */
	public static final String UID_FIELD = "uid";

	private JevConsistency() {
	}

	/**
	 * How one question's value moved across the samples.
	 *
	 * @param name the question name
	 * @param values every value observed, in sample order
	 * @param mean the arithmetic mean
	 * @param standardDeviation the population standard deviation
	 * @param min the smallest value observed
	 * @param max the largest value observed
	 */
	public record Statistics(String name, List<Double> values, double mean, double standardDeviation, double min,
			double max) {

		public Statistics {
			values = values == null ? List.of() : List.copyOf(values);
		}

		/**
		 * @return the spread between the extremes, the quickest read on whether a
		 * threshold sitting inside it would flip between runs
		 */
		public double range() {
			return this.max - this.min;
		}

		/**
		 * @param threshold a threshold applied to this question
		 * @return {@code true} when the samples fall on both sides of it, so the decision
		 * this question drives is not reproducible
		 */
		public boolean straddles(double threshold) {
			return this.min < threshold && this.max >= threshold;
		}
	}

	/**
	 * The outcome of a sampling run.
	 *
	 * @param samples the successful responses, in sample order
	 * @param failures the samples that did not come back
	 * @param statistics per-question statistics, keyed by question name
	 */
	public record Report(List<SystemOneResponse> samples, List<JevBatchResult<SystemOneResponse>> failures,
			Map<String, Statistics> statistics) {

		public Report {
			samples = samples == null ? List.of() : List.copyOf(samples);
			failures = failures == null ? List.of() : List.copyOf(failures);
			statistics = statistics == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(statistics));
		}

		/**
		 * @param threshold a threshold
		 * @return the questions whose samples fall on both sides of it
		 */
		public List<String> unstableAt(double threshold) {
			return this.statistics.values()
				.stream()
				.filter(statistics -> statistics.straddles(threshold))
				.map(Statistics::name)
				.toList();
		}
	}

	/**
	 * Draws {@link #DEFAULT_SAMPLES} samples.
	 * @param client the client
	 * @param state the state to ask about
	 * @param questions the questions
	 * @return the report
	 */
	public static Report sample(TypeSafeClient client, Map<String, ?> state,
			Map<String, ? extends Question> questions) {
		return sample(client, state, questions, DEFAULT_SAMPLES, JevBatchOptions.defaults());
	}

	/**
	 * Draws a given number of samples.
	 * @param client the client
	 * @param state the state to ask about
	 * @param questions the questions
	 * @param samples how many times to ask
	 * @return the report
	 */
	public static Report sample(TypeSafeClient client, Map<String, ?> state, Map<String, ? extends Question> questions,
			int samples) {
		return sample(client, state, questions, samples, JevBatchOptions.defaults());
	}

	/**
	 * Draws a given number of samples with control over how widely they run.
	 * @param client the client
	 * @param state the state to ask about; a {@code uid} is added to each sample's copy
	 * @param questions the questions, identical for every sample
	 * @param samples how many times to ask
	 * @param options how widely to fan the samples out
	 * @return the report
	 */
	public static Report sample(TypeSafeClient client, Map<String, ?> state, Map<String, ? extends Question> questions,
			int samples, JevBatchOptions options) {
		Assert.notNull(client, "client must not be null");
		Assert.notNull(state, "state must not be null");
		Assert.notEmpty(questions, "questions must declare at least one question");
		Assert.isTrue(samples >= 2, "sampling needs at least 2 samples to say anything about spread");

		List<SystemOneRequest> requests = new ArrayList<>(samples);
		for (int i = 0; i < samples; i++) {
			Map<String, Object> sampleState = new LinkedHashMap<>(state);
			sampleState.put(UID_FIELD, i + ":" + UUID.randomUUID());
			requests.add(SystemOneRequest.builder()
				.state(JsonContent.of(sampleState))
				.model(client.defaultModel())
				.questions(questions)
				.build());
		}

		List<JevBatchResult<SystemOneResponse>> results = client.systemOneAll(requests, options);
		List<SystemOneResponse> responses = new ArrayList<>();
		List<JevBatchResult<SystemOneResponse>> failures = new ArrayList<>();
		for (JevBatchResult<SystemOneResponse> result : results) {
			if (result.succeeded()) {
				responses.add(result.orThrow());
			}
			else {
				failures.add(result);
			}
		}

		return new Report(responses, failures, summarise(questions.keySet(), responses));
	}

	private static Map<String, Statistics> summarise(Iterable<String> names, List<SystemOneResponse> responses) {
		Map<String, Statistics> statistics = new LinkedHashMap<>();
		for (String name : names) {
			List<Double> values = new ArrayList<>();
			for (SystemOneResponse response : responses) {
				Answer answer = response.answers().get(name);
				// A choice has no single number to track, so only nouls and scores are
				// summarised; a choice's stability is a question about its distribution.
				if (answer instanceof NoulAnswer noul) {
					values.add(noul.value());
				}
				else if (answer instanceof ScoreAnswer score) {
					values.add(score.value());
				}
			}
			if (!values.isEmpty()) {
				statistics.put(name, describe(name, values));
			}
		}
		return statistics;
	}

	private static Statistics describe(String name, List<Double> values) {
		double sum = 0.0d;
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		for (double value : values) {
			sum += value;
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		double mean = sum / values.size();
		double variance = 0.0d;
		for (double value : values) {
			variance += (value - mean) * (value - mean);
		}
		return new Statistics(name, values, mean, Math.sqrt(variance / values.size()), min, max);
	}

}
