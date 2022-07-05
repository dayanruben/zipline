/*
 * Copyright (C) 2022 Block, Inc.
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

import app.cash.zipline.EventListener
import app.cash.zipline.Zipline
import app.cash.zipline.loader.ZiplineLoader.Companion.getApplicationManifestFileName
import app.cash.zipline.loader.testing.LoaderTestFixtures
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class LoaderTester(
  private val eventListener: EventListener = EventListener.NONE,
): TestRule {
  /** Delegate to JUnit's temporary folder rule. */
  private val temporaryFolder = TemporaryFolder()

  val httpClient = FakeZiplineHttpClient()
  private val dispatcher = TestCoroutineDispatcher()
  private val cacheMaxSizeInBytes = 100 * 1024 * 1024
  private var nowMillis = 1_000L

  private var zipline = Zipline.create(dispatcher)

  val embeddedFileSystem = FakeFileSystem()
  val embeddedDir = "/zipline".toPath()
  private var testFixtures = LoaderTestFixtures()

  private val baseUrl = "https://example.com/files"

  internal lateinit var loader: ZiplineLoader
  internal lateinit var cache: ZiplineCache

  override fun apply(base: Statement, description: Description): Statement {
    val statement = object : Statement() {
      override fun evaluate() {
        loader = ZiplineLoader(
          dispatcher = dispatcher,
          httpClient = httpClient,
          eventListener = eventListener,
        ).withEmbedded(
          embeddedDir = embeddedDir,
          embeddedFileSystem = embeddedFileSystem,
        ).withCache(
          directory = temporaryFolder.root.toOkioPath(),
          fileSystem = FileSystem.SYSTEM,
          maxSizeInBytes = cacheMaxSizeInBytes.toLong(),
          nowMs = { nowMillis },
        )
        cache = loader.cache!!
        base.evaluate()
        loader.close()
      }
    }
    return temporaryFolder.apply(statement, description)
  }

  fun seedEmbedded(applicationName: String, seed: String) {
    embeddedFileSystem.createDirectories(embeddedDir)
    val ziplineFileByteString =
      testFixtures.createZiplineFile(LoaderTestFixtures.createJs(seed), "$seed.js")
    val sha256 = ziplineFileByteString.sha256()
    val manifest = LoaderTestFixtures.createRelativeManifest(seed, sha256)
    val manifestJsonString = Json.encodeToString(ZiplineManifest.serializer(), manifest)
    embeddedFileSystem.write(embeddedDir / sha256.hex()) {
      write(ziplineFileByteString)
    }
    embeddedFileSystem.write(
      embeddedDir / getApplicationManifestFileName(
        applicationName
      )
    ) {
      write(manifestJsonString.encodeUtf8())
    }
  }

  suspend fun success(applicationName: String, seed: String): String {
    val manifestUrl = "$baseUrl/$applicationName/${getApplicationManifestFileName(applicationName)}"
    val ziplineFileByteString =
      testFixtures.createZiplineFile(LoaderTestFixtures.createJs(seed), "$seed.js")
    val manifest = LoaderTestFixtures.createRelativeManifest(seed, ziplineFileByteString.sha256())
    val manifestJsonString = Json.encodeToString(ZiplineManifest.serializer(), manifest)
    httpClient.filePathToByteString = mapOf(
      manifestUrl to manifestJsonString.encodeUtf8(),
      "$baseUrl/$applicationName/$seed.zipline" to ziplineFileByteString
    )
    zipline = loader.loadOrFallBack(applicationName, manifestUrl)
    return (zipline.quickJs.evaluate("globalThis.log", "assert.js") as String).removeSuffix(
      " loaded\n"
    )
  }

  /** Returns the delta in the number of files, like -3 if 3 files were deleted. */
  suspend fun prune(): Int {
    return countFiles {
      cache.prune(0L)
    }
  }

  /** Returns the delta in the number of files, like 3 if 3 files were added. */
  suspend fun countFiles(block: suspend () -> Unit): Int {
    val before = cache.countFiles()
    block()
    val after = cache.countFiles()
    return after - before
  }

  suspend fun failureManifestFetchFails(applicationName: String): String {
    val seed = "fail"
    val manifestUrl = "$baseUrl/$applicationName/${getApplicationManifestFileName(applicationName)}"
    val ziplineFileByteString = testFixtures.createZiplineFile(
      LoaderTestFixtures.createJs(seed), "$seed.js"
    )
    httpClient.filePathToByteString = mapOf(
      "$baseUrl/$applicationName/$seed.zipline" to ziplineFileByteString
    )
    zipline = loader.loadOrFallBack(applicationName, manifestUrl)
    return (zipline.quickJs.evaluate("globalThis.log", "assert.js") as String).removeSuffix(
      " loaded\n"
    )
  }

  suspend fun failureCodeFetchFails(applicationName: String): String {
    val seed = "unreachable"
    val ziplineFileByteString =
      testFixtures.createZiplineFile(LoaderTestFixtures.createJs(seed), "$seed.js")
    val manifest = LoaderTestFixtures.createRelativeManifest(seed, ziplineFileByteString.sha256())
    val manifestJsonString = Json.encodeToString(ZiplineManifest.serializer(), manifest)

    val manifestUrl = "$baseUrl/$applicationName/${getApplicationManifestFileName(applicationName)}"

    httpClient.filePathToByteString = mapOf(
      manifestUrl to manifestJsonString.encodeUtf8(),
    )
    zipline = loader.loadOrFallBack(applicationName, manifestUrl)
    return (zipline.quickJs.evaluate("globalThis.log", "assert.js") as String).removeSuffix(
      " loaded\n"
    )
  }

  suspend fun failureCodeLoadFails(applicationName: String): String {
    val seed = "broken"
    val ziplineFileByteString = testFixtures.createZiplineFile(
      LoaderTestFixtures.createFailureJs(
        seed
      ), "$seed.js"
    )
    val manifest = LoaderTestFixtures.createRelativeManifest(seed, ziplineFileByteString.sha256())
    val manifestJsonString = Json.encodeToString(ZiplineManifest.serializer(), manifest)

    val manifestUrl = "$baseUrl/$applicationName/${getApplicationManifestFileName(applicationName)}"

    httpClient.filePathToByteString = mapOf(
      manifestUrl to manifestJsonString.encodeUtf8(),
      "$baseUrl/$applicationName/$seed.zipline" to ziplineFileByteString
    )
    zipline = loader.loadOrFallBack(applicationName, manifestUrl)
    return (zipline.quickJs.evaluate("globalThis.log", "assert.js") as String).removeSuffix(
      " loaded\n"
    )
  }

  suspend fun failureCodeRunFails(applicationName: String): String {
    val seed = "crashes"
    val ziplineFileByteString =
      testFixtures.createZiplineFile(LoaderTestFixtures.createJs(seed), "$seed.js")
    val manifest = LoaderTestFixtures.createRelativeManifest(seed, ziplineFileByteString.sha256())
    val manifestJsonString = Json.encodeToString(ZiplineManifest.serializer(), manifest)

    val manifestUrl = "$baseUrl/$applicationName/${getApplicationManifestFileName(applicationName)}"

    httpClient.filePathToByteString = mapOf(
      manifestUrl to manifestJsonString.encodeUtf8(),
      "$baseUrl/$applicationName/$seed.zipline" to ziplineFileByteString
    )
    zipline = loader.loadOrFallBack(applicationName, manifestUrl) {
      val loadedSeed =
        (it.quickJs.evaluate("globalThis.log", "assert.js") as String).removeSuffix(
          " loaded\n"
        )
      if (loadedSeed == seed) throw IllegalArgumentException("Zipline code run failed")
    }
    return (zipline.quickJs.evaluate("globalThis.log", "assert.js") as String).removeSuffix(
      " loaded\n"
    )
  }
}