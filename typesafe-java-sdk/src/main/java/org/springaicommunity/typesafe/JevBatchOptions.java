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

package org.springaicommunity.typesafe;



import java.util.concurrent.Executor;

import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * How a batch of requests is run.
 *
 * <p>
 * Most of the Jev API is used by packing many questions into a single call, which the
 * service answers in parallel for one state. A batch is for the other shape: the same
 * question asked about many different states, which is genuinely one call per item.
 *
 * @param concurrency how many requests may be in flight at once
 * @param failFast whether to skip work that has not started after the first failure. A
 * failure does not guarantee that no further task was already submitted
 * @param executor the executor to run on, or {@code null} to create and dispose a pool per
 * batch
 * @author Christian Tzolov
 */
public record JevBatchOptions(int concurrency, boolean failFast, @Nullable Executor executor) {

	/**
	 * The default width of a batch. Matches the pooling the TypeSafe passage-classification
	 * cookbook uses, and stays far below the published limit of 1,200 requests per minute.
	 */
	public static final int DEFAULT_CONCURRENCY = 4;

	public JevBatchOptions {
		Assert.isTrue(concurrency >= 1, "concurrency must be at least 1");
	}

	/**
	 * @return four requests at a time, collecting failures rather than aborting
	 */
	public static JevBatchOptions defaults() {
		return new JevBatchOptions(DEFAULT_CONCURRENCY, false, null);
	}

	/**
	 * @param concurrency how many requests may be in flight at once
	 * @return options with that width, collecting failures
	 */
	public static JevBatchOptions ofConcurrency(int concurrency) {
		return new JevBatchOptions(concurrency, false, null);
	}

	/**
	 * @param failFast whether to skip work that has not started after the first failure. Work
	 * already submitted still runs
	 * @return a copy with that setting
	 */
	public JevBatchOptions withFailFast(boolean failFast) {
		return new JevBatchOptions(this.concurrency, failFast, this.executor);
	}

	/**
	 * Runs the batch on a caller-supplied executor, which is never shut down by the batch.
	 * Use this to share one pool across many batches, or to supply a virtual-thread executor
	 * on a runtime that has them — this SDK targets Java 17 and so cannot create one itself.
	 * @param executor the executor
	 * @return a copy using that executor
	 */
	public JevBatchOptions withExecutor(@Nullable Executor executor) {
		return new JevBatchOptions(this.concurrency, this.failFast, executor);
	}

}
