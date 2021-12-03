/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.loader

import app.cash.zipline.QuickJs
import app.cash.zipline.Zipline
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.runBlockingTest
import okio.ByteString.Companion.toByteString

@Suppress("UnstableApiUsage")
@ExperimentalCoroutinesApi
class ZiplineLoaderTest {
  private val httpClient = FakeZiplineHttpClient()
  private val loader = ZiplineLoader(
    client = httpClient
  )
  private val dispatcher = TestCoroutineDispatcher()
  private lateinit var quickJs: QuickJs

  @BeforeTest
  fun setUp() {
    quickJs = QuickJs.create()
  }

  @AfterTest
  fun tearDown() {
    quickJs.close()
  }

  @Test
  fun `happy path`() {
    val alphaJs = """
      |globalThis.log = globalThis.log || "";
      |globalThis.log += "alpha loaded\n"
    """.trimMargin()
    val alphaBytecode = quickJs.compile(alphaJs, "alpha.js")
    val alphaFilePath = "/alpha.zipline"
    val bravoJs = """
      |globalThis.log = globalThis.log || "";
      |globalThis.log += "bravo loaded\n"
    """.trimMargin()
    val bravoBytecode = quickJs.compile(bravoJs, "bravo.js")
    val bravoFilePath = "/bravo.zipline"

    val manifest = ZiplineManifest.create(
      modules = mapOf(
        "bravo" to ZiplineModule(
          url = bravoFilePath,
          sha256 = bravoBytecode.toByteString().sha256(),
          dependsOnIds = listOf("alpha"),
        ),
        "alpha" to ZiplineModule(
          url = alphaFilePath,
          sha256 = alphaBytecode.toByteString().sha256(),
          dependsOnIds = listOf(),
        ),
      )
    )
    httpClient.filePathToByteString = mapOf(
      alphaFilePath to alphaBytecode.toByteString(),
      bravoFilePath to bravoBytecode.toByteString()
    )
    val zipline = Zipline.create(dispatcher)
    dispatcher.runBlockingTest {
      coroutineScope {
        loader.load(this@coroutineScope, zipline, manifest)
      }
    }
    assertEquals(
      zipline.quickJs.evaluate("globalThis.log", "assert.js"),
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin()
    )
  }
}