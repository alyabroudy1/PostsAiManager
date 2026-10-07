/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified by PostsAiManager: adapted from google-ai-edge/gallery SkillManager.loadBuiltInSkills. Reads the bundled skills only
// (app assets, skills/<name>/SKILL.md); the Gallery's protos, DataStore selection state, URL and local-folder import are not taken.

package com.postsaimanager.core.data.skills

import android.content.Context
import android.util.Log
import com.postsaimanager.core.domain.skills.Skill
import com.postsaimanager.core.domain.skills.SkillCatalog
import com.postsaimanager.core.domain.skills.SkillParseResult
import com.postsaimanager.core.domain.skills.SkillParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The skills bundled in the app: every `skills/<name>/SKILL.md` of the assets, read once and kept. A file that does not parse is
 * logged and left out; nothing is fetched from anywhere.
 */
@Singleton
class AssetSkillCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) : SkillCatalog {

    private val mutex = Mutex()
    private var loaded: List<Skill>? = null

    override suspend fun skills(): List<Skill> = mutex.withLock {
        loaded ?: withContext(Dispatchers.IO) { readAll() }.also { loaded = it }
    }

    private fun readAll(): List<Skill> {
        val folders = runCatching { context.assets.list(ROOT).orEmpty().sorted() }.getOrElse {
            Log.e(TAG, "Cannot list assets/$ROOT", it)
            emptyList()
        }
        return folders.mapNotNull { folder ->
            val text = runCatching { context.assets.open("$ROOT/$folder/$FILE").use { it.bufferedReader().readText() } }.getOrElse {
                Log.w(TAG, "No readable $FILE for skill $folder", it)
                return@mapNotNull null
            }
            when (val parsed = SkillParser.parse(text)) {
                is SkillParseResult.Parsed -> parsed.skill
                is SkillParseResult.Invalid -> {
                    Log.w(TAG, "Skill $folder does not parse: ${parsed.errors.joinToString()}")
                    null
                }
            }
        }
    }

    private companion object {
        const val TAG = "AssetSkillCatalog"
        const val ROOT = "skills"
        const val FILE = "SKILL.md"
    }
}
