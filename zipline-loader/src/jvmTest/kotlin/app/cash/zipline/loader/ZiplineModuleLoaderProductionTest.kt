/*
 * Copyright (C) 2022 Square, Inc.
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
import app.cash.zipline.loader.testing.LoaderTestFixtures
import app.cash.zipline.loader.testing.LoaderTestFixtures.Companion.alphaFilePath
import app.cash.zipline.loader.testing.LoaderTestFixtures.Companion.bravoFilePath
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.TestCoroutineDispatcher
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Before
import org.junit.Test

class ZiplineModuleLoaderProductionTest {
  private val httpClient = FakeZiplineHttpClient()
  private val dispatcher = TestCoroutineDispatcher()
  private val cacheDbDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
  private val cacheMaxSizeInBytes = 100 * 1024 * 1024
  private val cacheDirectory = "/zipline/cache".toPath()
  private var nowMillis = 1_000L

  private var concurrentDownloadsSemaphore = Semaphore(3)
  private lateinit var zipline: Zipline
  private lateinit var cache: ZiplineCache

  private lateinit var fileSystem: FileSystem
  private lateinit var embeddedFileSystem: FileSystem
  private val embeddedDir = "/zipline".toPath()
  private lateinit var quickJs: QuickJs
  private lateinit var testFixtures: LoaderTestFixtures
  private lateinit var moduleLoader: ZiplineModuleLoader

  @Before
  fun setUp() {
    Database.Schema.create(cacheDbDriver)
    quickJs = QuickJs.create()
    testFixtures = LoaderTestFixtures(quickJs)
    fileSystem = FakeFileSystem()
    embeddedFileSystem = FakeFileSystem()
    cache = createZiplineCache(
      driver = cacheDbDriver,
      fileSystem = fileSystem,
      directory = cacheDirectory,
      maxSizeInBytes = cacheMaxSizeInBytes.toLong(),
      nowMs = { nowMillis }
    )
    zipline = Zipline.create(dispatcher)
    moduleLoader = ZiplineModuleLoader.createProduction(
      dispatcher = dispatcher,
      httpClient = httpClient,
      concurrentDownloadsSemaphore = concurrentDownloadsSemaphore,
      embeddedDir = embeddedDir,
      embeddedFileSystem = embeddedFileSystem,
      cache = cache,
      zipline = zipline,
    )
  }

  @After
  fun tearDown() {
    quickJs.close()
    cacheDbDriver.close()
  }

  @Test
  fun getFromEmbeddedFileSystemNoNetworkCall(): Unit = runBlocking {
    embeddedFileSystem.createDirectories(embeddedDir)
    embeddedFileSystem.write(embeddedDir / testFixtures.alphaSha256Hex) {
      write(testFixtures.alphaByteString)
    }
    embeddedFileSystem.write(embeddedDir / testFixtures.bravoSha256Hex) {
      write(testFixtures.bravoByteString)
    }

    httpClient.filePathToByteString = mapOf()

    moduleLoader.load(testFixtures.manifest)

    assertEquals(
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin(),
      zipline.quickJs.evaluate("globalThis.log", "assert.js")
    )
  }

  @Test
  fun getFromWarmCacheNoNetworkCall(): Unit = runBlocking {
    cache.getOrPut(testFixtures.alphaSha256) {
      testFixtures.alphaByteString
    }
    assertEquals(testFixtures.alphaByteString, cache.read(testFixtures.alphaSha256))
    cache.getOrPut(testFixtures.bravoSha256) {
      testFixtures.bravoByteString
    }
    assertEquals(testFixtures.bravoByteString, cache.read(testFixtures.bravoSha256))

    httpClient.filePathToByteString = mapOf()

    moduleLoader.load(testFixtures.manifest)

    assertEquals(
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin(),
      zipline.quickJs.evaluate("globalThis.log", "assert.js")
    )
  }

  @Test
  fun getFromNetworkPutInCache(): Unit = runBlocking {
    assertNull(cache.read(testFixtures.alphaSha256))
    assertNull(cache.read(testFixtures.bravoSha256))

    httpClient.filePathToByteString = mapOf(
      alphaFilePath to testFixtures.alphaByteString,
      bravoFilePath to testFixtures.bravoByteString,
    )

    moduleLoader.load(testFixtures.manifest)

    assertEquals(
      """
      |alpha loaded
      |bravo loaded
      |""".trimMargin(),
      zipline.quickJs.evaluate("globalThis.log", "assert.js")
    )

    val ziplineFileFromCache = cache.getOrPut(testFixtures.alphaSha256) {
      "fake".encodeUtf8()
    }
    assertEquals(testFixtures.alphaByteString, ziplineFileFromCache)
  }
}
