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
import app.cash.zipline.loader.ZiplineDownloader.Companion.PREBUILT_MANIFEST_FILE_NAME
import app.cash.zipline.loader.testing.LoaderTestFixtures
import app.cash.zipline.loader.testing.LoaderTestFixtures.Companion.alphaFilePath
import app.cash.zipline.loader.testing.LoaderTestFixtures.Companion.bravoFilePath
import app.cash.zipline.loader.testing.LoaderTestFixtures.Companion.manifestPath
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Before
import org.junit.Test

@Suppress("UnstableApiUsage")
@ExperimentalCoroutinesApi
class ZiplineLoaderTest {
  private val httpClient = FakeZiplineHttpClient()
  private val dispatcher = TestCoroutineDispatcher()
  private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
  private val cacheSize = 1024 * 1024
  private var nowMillis = 1_000L

  private val cacheFileSystem = FakeFileSystem()
  private val embeddedDir = "/zipline".toPath()
  private val embeddedFileSystem = FakeFileSystem()
  private lateinit var quickJs: QuickJs
  private lateinit var testFixtures: LoaderTestFixtures
  private lateinit var loader: ZiplineLoader

  @Before
  fun setUp() {
    Database.Schema.create(driver)
    quickJs = QuickJs.create()
    testFixtures = LoaderTestFixtures(quickJs)
    loader = ZiplineLoader(
      dispatcher = dispatcher,
      httpClient = httpClient,
      embeddedDir = embeddedDir,
      embeddedFileSystem = embeddedFileSystem,
      cacheDir = "/zipline/cache".toPath(),
      cacheFileSystem = cacheFileSystem,
      cacheDbDriver = driver,
      cacheMaxSizeInBytes = cacheSize,
      nowMs = { nowMillis },
    )
  }

  @After
  fun tearDown() {
    quickJs.close()
    driver.close()
  }

  @Test
  fun happyPath(): Unit = runBlocking(dispatcher) {
    httpClient.filePathToByteString = mapOf(
      alphaFilePath to testFixtures.alphaByteString,
      bravoFilePath to testFixtures.bravoByteString
    )
    val zipline = Zipline.create(dispatcher)
    loader.load(zipline, testFixtures.manifest)
    assertEquals(
      zipline.quickJs.evaluate("globalThis.log", "assert.js"),
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin()
    )
  }

  @Test
  fun loadManifestFromUrl(): Unit = runBlocking(dispatcher) {
    httpClient.filePathToByteString = mapOf(
      manifestPath to Json.encodeToString(testFixtures.manifest).encodeUtf8(),
      alphaFilePath to testFixtures.alphaByteString,
      bravoFilePath to testFixtures.bravoByteString
    )
    val zipline = Zipline.create(dispatcher)
    loader.load(zipline, manifestPath)
    assertEquals(
      zipline.quickJs.evaluate("globalThis.log", "assert.js"),
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin()
    )
  }

  @Test
  fun loaderUsesCache(): Unit = runBlocking(dispatcher) {
    // load, no cache hit, download
    httpClient.filePathToByteString = mapOf(
      manifestPath to Json.encodeToString(testFixtures.manifest).encodeUtf8(),
      alphaFilePath to testFixtures.alphaByteString,
      bravoFilePath to testFixtures.bravoByteString
    )
    val ziplineColdCache = Zipline.create(dispatcher)
    loader.load(ziplineColdCache, manifestPath)
    assertEquals(
      ziplineColdCache.quickJs.evaluate("globalThis.log", "assert.js"),
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin()
    )

    // load, cache hit, no download
    httpClient.filePathToByteString = mapOf(
      manifestPath to Json.encodeToString(testFixtures.manifest).encodeUtf8(),
      // Note no actual alpha/bravo files are available on the network
    )
    val ziplineWarmedCache = Zipline.create(dispatcher)
    loader.load(ziplineWarmedCache, manifestPath)
    assertEquals(
      ziplineWarmedCache.quickJs.evaluate("globalThis.log", "assert.js"),
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin()
    )
  }

  @Test
  fun loaderUsesResourcesBeforeCacheButManifestOverNetwork(): Unit = runBlocking(dispatcher) {
    // seed the resources FS with zipline files
    embeddedFileSystem.createDirectories(embeddedDir)
    embeddedFileSystem.write(embeddedDir / testFixtures.alphaSha256Hex) {
      write(testFixtures.alphaByteString)
    }
    embeddedFileSystem.write(embeddedDir / testFixtures.bravoSha256Hex) {
      write(testFixtures.bravoByteString)
    }

    // load, resources hit, no download via cache
    httpClient.filePathToByteString = mapOf(
      manifestPath to Json.encodeToString(testFixtures.manifest).encodeUtf8(),
      // Note no actual alpha/bravo files are available on the cache / network
    )
    val ziplineWarmedCache = Zipline.create(dispatcher)
    loader.load(ziplineWarmedCache, manifestPath)
    assertEquals(
      ziplineWarmedCache.quickJs.evaluate("globalThis.log", "assert.js"),
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin()
    )
  }

  @Test
  fun loaderUsesResourcesForPrebuiltManifestWhenNetworkOffline(): Unit = runBlocking(dispatcher) {
    // seed the embedded FS with zipline manifest and files
    embeddedFileSystem.createDirectories(embeddedDir)
    embeddedFileSystem.write(embeddedDir / PREBUILT_MANIFEST_FILE_NAME) {
      write(Json.encodeToString(testFixtures.manifest).encodeUtf8())
    }
    embeddedFileSystem.write(embeddedDir / testFixtures.alphaSha256Hex) {
      write(testFixtures.alphaByteString)
    }
    embeddedFileSystem.write(embeddedDir / testFixtures.bravoSha256Hex) {
      write(testFixtures.bravoByteString)
    }

    // load, resources hit, no download via cache
    httpClient.filePathToByteString = mapOf(
      // Note no actual manifest/alpha/bravo files are available on the cache / network
    )
    val ziplineWarmedCache = Zipline.create(dispatcher)
    loader.load(ziplineWarmedCache, manifestPath)
    assertEquals(
      ziplineWarmedCache.quickJs.evaluate("globalThis.log", "assert.js"),
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin()
    )
  }

  @Test
  fun downloadToDirectoryThenLoadFromAsEmbedded(): Unit = runBlocking(dispatcher) {
    val downloadDir = "/downloads/latest".toPath()
    val downloadFileSystem = cacheFileSystem
    val downloader = ZiplineDownloader(
      dispatcher = dispatcher,
      httpClient = httpClient,
      downloadDir = downloadDir,
      downloadFileSystem = downloadFileSystem,
    )

    assertFalse(downloadFileSystem.exists(downloadDir / PREBUILT_MANIFEST_FILE_NAME))
    assertFalse(downloadFileSystem.exists(downloadDir / testFixtures.alphaSha256Hex))
    assertFalse(downloadFileSystem.exists(downloadDir / testFixtures.bravoSha256Hex))

    val manifest = testFixtures.manifest
    httpClient.filePathToByteString = mapOf(
      manifestPath to Json.encodeToString(manifest).encodeUtf8(),
      alphaFilePath to testFixtures.alphaByteString,
      bravoFilePath to testFixtures.bravoByteString
    )
    downloader.download(manifestPath)

    // check that files have been downloaded to downloadDir as expected
    assertTrue(downloadFileSystem.exists(downloadDir / PREBUILT_MANIFEST_FILE_NAME))
    assertEquals(
      Json.encodeToString(manifest).encodeUtf8(),
      downloadFileSystem.read(downloadDir / PREBUILT_MANIFEST_FILE_NAME) { readByteString() })
    assertTrue(downloadFileSystem.exists(downloadDir / testFixtures.alphaSha256Hex))
    assertEquals(
      testFixtures.alphaByteString,
      downloadFileSystem.read(downloadDir / testFixtures.alphaSha256Hex) { readByteString() })
    assertTrue(downloadFileSystem.exists(downloadDir / testFixtures.bravoSha256Hex))
    assertEquals(
      testFixtures.bravoByteString,
      downloadFileSystem.read(downloadDir / testFixtures.bravoSha256Hex) { readByteString() })

    // Load into Zipline
    val zipline = Zipline.create(dispatcher)
    loader = ZiplineLoader(
      dispatcher = dispatcher,
      httpClient = httpClient,
      embeddedDir = downloadDir,
      embeddedFileSystem = downloadFileSystem,
      cacheDir = "/zipline/cache".toPath(),
      cacheFileSystem = cacheFileSystem,
      cacheDbDriver = driver,
      cacheMaxSizeInBytes = cacheSize,
      nowMs = { nowMillis },
    )
    loader.load(zipline, manifest)
    assertEquals(
      zipline.quickJs.evaluate("globalThis.log", "assert.js"),
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin()
    )
  }
}
