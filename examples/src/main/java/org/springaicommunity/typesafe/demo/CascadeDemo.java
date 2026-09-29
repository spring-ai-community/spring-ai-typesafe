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

package org.springaicommunity.typesafe.demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springaicommunity.typesafe.JevBatchOptions;
import org.springaicommunity.typesafe.JevBatchResult;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeConstants;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;

/**
 * Using Jev as the gate in a cheap-model-first cascade.
 *
 * <p>
 * A small model extracts structured data well enough most of the time and confidently
 * invents the rest, which is the whole problem: the failures do not announce themselves.
 * Running everything through a large model fixes that at many times the price. A cascade
 * pays the large price only where something is actually wrong, and the question is how you
 * decide where that is.
 *
 * <p>
 * Verification is a better fit for Jev than extraction is. "Is every value in this record
 * supported by the source text" decomposes into a handful of yes/no questions with
 * thresholds, and the answers are numbers rather than prose to parse. The gate takes the
 * maximum rather than the mean — one confident red flag is enough, and averaging four
 * checks would let a single serious error hide behind three clean ones.
 *
 * <p>
 * The extraction tier here is <strong>simulated</strong>: the records below are canned,
 * including their mistakes, so that the demo needs only {@code TYPESAFE_API_KEY} and so
 * that the same errors appear on every run. The verification is real. Substituting a real
 * cheap model means replacing {@link #extractedRecords()} and nothing else.
 *
 * @author Christian Tzolov
 */
public final class CascadeDemo {

	/** Escalate when any single check fires this hard. */
	private static final double FIRE_THRESHOLD = 0.70d;

	/** Illustrative per-record prices, in the ratio the TypeSafe cascade cookbook uses. */
	private static final double MINI_COST = 0.0008d;

	private static final double JEV_COST = 0.00004d;

	private static final double REASONING_COST = 0.0090d;

	/** The schema the extraction tier is asked for; anything else in the source is detail. */
	private static final List<String> REQUIRED_FIELDS = List.of("invoice", "vendor", "date", "total");

	private CascadeDemo() {
	}

	/**
	 * One invoice, the text it came from, and what the cheap model claims it says.
	 *
	 * @param id the record id
	 * @param source the source document the extraction must be supported by
	 * @param extracted the cheap model's structured output
	 */
	private record Record(String id, String source, Map<String, String> extracted) {
	}

	public static void main(String[] args) {
		TypeSafeClient client = TypeSafeClient.builder()
			.apiKey(System.getenv(TypeSafeConstants.API_KEY_ENV))
			.build();

		List<Record> records = extractedRecords();

		// One verification call per record, fanned out rather than sequential.
		List<SystemOneRequest> requests = new ArrayList<>();
		for (Record record : records) {
			Map<String, Object> state = new LinkedHashMap<>();
			state.put("source_text", record.source());
			state.put("extracted", record.extracted());
			// Without the target schema in the state, "is anything missing?" is unanswerable
			// and the check fires on every line item the extraction was never meant to keep.
			state.put("required_fields", REQUIRED_FIELDS);
			requests.add(SystemOneRequest.builder()
				.state(state)
				.model(client.defaultModel())
				.questions(checks())
				.build());
		}
		List<JevBatchResult<SystemOneResponse>> verdicts = client.systemOneAll(requests,
				JevBatchOptions.ofConcurrency(4));

		int escalated = 0;
		System.out.println("─".repeat(92));
		for (int i = 0; i < records.size(); i++) {
			Record record = records.get(i);
			JevBatchResult<SystemOneResponse> verdict = verdicts.get(i);

			if (!verdict.succeeded()) {
				// Unverifiable is not the same as verified. Escalate rather than assume.
				System.out.printf("%-6s  ESCALATE  verification failed: %s%n", record.id(),
						verdict.failure().getMessage());
				escalated++;
				continue;
			}

			SystemOneResponse response = verdict.orThrow();
			// -1 rather than 0, so a record where every check returns exactly 0.0 still
			// names the check it scored worst on instead of printing null.
			String worstCheck = null;
			double worst = -1.0d;
			for (String check : checks().keySet()) {
				double value = response.noulValue(check);
				if (value > worst) {
					worst = value;
					worstCheck = check;
				}
			}

			boolean fires = worst > FIRE_THRESHOLD;
			if (fires) {
				escalated++;
			}
			System.out.printf("%-6s  %-8s  worst flag %-22s %.2f   %s%n", record.id(),
					fires ? "ESCALATE" : "ACCEPT", worstCheck, worst, record.extracted());
		}
		System.out.println("─".repeat(92));

		int total = records.size();
		double cascade = total * MINI_COST + total * JEV_COST + escalated * REASONING_COST;
		double allLarge = total * REASONING_COST;
		System.out.printf("%d of %d records escalated.%n", escalated, total);
		System.out.printf("cascade: %d x mini + %d x jev + %d x reasoning = $%.5f%n", total, total, escalated,
				cascade);
		System.out.printf("everything through the large model            = $%.5f%n", allLarge);
		System.out.printf("=> %.0f%% of the cost, with the errors still caught%n", 100.0d * cascade / allLarge);
		System.out.println();
		System.out.println("The gate is a maximum, not a mean: one confident red flag escalates a record");
		System.out.println("even when the other checks are clean, because that is what a red flag means.");
	}

	private static Map<String, Question> checks() {
		Map<String, Question> checks = new LinkedHashMap<>();
		checks.put("invented_value", Noul.builder()
			.instructions(ordered("question",
					"Does `extracted` contain a value that does not appear in, and cannot be derived from, "
							+ "`source_text`?",
					"inspect", "extracted", "focus",
					"A value the source never states. Reformatting a value the source does state, such as "
							+ "writing a date differently, is not an invention."))
			.whenTrue("At least one field states something the source does not support")
			.whenFalse("Every field traces back to the source")
			.build());
		checks.put("wrong_total", Noul.builder()
			.instructions("Does the `total` in `extracted` disagree with the amounts in `source_text`?")
			.whenTrue("The total does not match the source, or does not equal the sum of its line items")
			.whenFalse("The total matches the source")
			.build());
		checks.put("missing_field", Noul.builder()
			.instructions(ordered("question",
					"Is any field named in `required_fields` absent or empty in `extracted`?", "focus",
					"Only the fields listed in `required_fields` matter. Detail in the source that the "
							+ "schema does not ask for, such as individual line items or payment terms, is "
							+ "not missing data."))
			.whenTrue("A required field is absent or blank")
			.whenFalse("Every required field is present and filled")
			.build());
		return checks;
	}

	/**
	 * Stands in for a cheap extraction model. Three of these are right; the others carry
	 * the kinds of mistake a small model makes without flagging them — an invented
	 * purchase-order number, arithmetic that does not add up, and a dropped field.
	 */
	private static List<Record> extractedRecords() {
		return List.of(
				new Record("INV-01", "Invoice 4471 from Acme Corp, dated 3 March 2026. Two line items: "
						+ "consulting 1,200.00 and travel 300.00. Total due 1,500.00.",
						ordered("invoice", "4471", "vendor", "Acme Corp", "date", "2026-03-03", "total", "1500.00")),
				new Record("INV-02", "Invoice 4472 from Beaver Dam Builders, dated 11 March 2026. "
						+ "Materials 800.00, labour 450.00. Total due 1,250.00.",
						ordered("invoice", "4472", "vendor", "Beaver Dam Builders", "date", "2026-03-11", "total",
								"1350.00")),
				new Record("INV-03", "Invoice 4473 from Cobalt Services, dated 19 March 2026. "
						+ "Support retainer 2,000.00. Total due 2,000.00.",
						ordered("invoice", "4473", "vendor", "Cobalt Services", "date", "2026-03-19", "total",
								"2000.00", "purchase_order", "PO-88231")),
				new Record("INV-04", "Invoice 4474 from Delta Print, dated 2 April 2026. "
						+ "Printing 175.50. Total due 175.50. Payment terms net 30.",
						ordered("invoice", "4474", "vendor", "Delta Print", "date", "2026-04-02", "total", "175.50")),
				new Record("INV-05", "Invoice 4475 from Everline Logistics, dated 14 April 2026. "
						+ "Freight 640.00, insurance 60.00. Total due 700.00.",
						ordered("invoice", "4475", "vendor", "Everline Logistics", "date", "2026-04-14", "total",
								"700.00")));
	}

	/**
	 * A map that keeps the order its entries are written in. Unlike {@code Map.of}, whose
	 * order changes with every JVM run, it sends the same JSON every time.
	 */
	private static Map<String, String> ordered(String... keysAndValues) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put(keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

}
