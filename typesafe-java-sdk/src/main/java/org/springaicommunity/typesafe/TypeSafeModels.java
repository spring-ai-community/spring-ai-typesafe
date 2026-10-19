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



/**
 * Known Jev model names. Aliases are resolved server side, so pinning a version is only
 * necessary when an application must not move underneath itself.
 *
 * @author Christian Tzolov
 */
public final class TypeSafeModels {

	/** Alias tracking the current flagship release. */
	public static final String JEV_LATEST = "jev-latest";

	/** Alias tracking the current preview release. */
	public static final String JEV_PREVIEW = "jev-preview";

	/** Pinned version behind the {@link #JEV_LATEST} alias at the time of writing. */
	public static final String JEV_1_13_0 = "jev-1.13.0";

	/** Pinned version behind the {@link #JEV_PREVIEW} alias at the time of writing. */
	public static final String JEV_1_14_0 = "jev-1.14.0";

	private TypeSafeModels() {
	}

}
