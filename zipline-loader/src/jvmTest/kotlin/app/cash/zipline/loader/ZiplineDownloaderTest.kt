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
import app.cash.zipline.loader.testing.LoaderTestFixtures.Companion.alphaFilePath
import app.cash.zipline.loader.testing.LoaderTestFixtures.Companion.bravoFilePath
import app.cash.zipline.loader.testing.LoaderTestFixtures.Companion.manifestPath
import app.cash.zipline.loader.ZiplineDownloader.Companion.PREBUILT_MANIFEST_FILE_NAME
import app.cash.zipline.loader.testing.LoaderTestFixtures
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.After
import org.junit.Before
import org.junit.Test

@Suppress("UnstableApiUsage")
@ExperimentalCoroutinesApi
class ZiplineDownloaderTest {
  private val dispatcher = TestCoroutineDispatcher()
  private val httpClient = FakeZiplineHttpClient()
  private val fileSystem = FakeFileSystem()
  private val downloadDir = "/zipline/download".toPath()
  private lateinit var quickJs: QuickJs
  private lateinit var testFixtures: LoaderTestFixtures
  private lateinit var downloader: ZiplineDownloader

  private fun alphaBytecode(quickJs: QuickJs) = testFixtures.alphaByteString
  private fun bravoBytecode(quickJs: QuickJs) = testFixtures.bravoByteString
  private fun manifest(quickJs: QuickJs) = testFixtures.manifest

  @Before
  fun setUp() {
    quickJs = QuickJs.create()
    testFixtures = LoaderTestFixtures(quickJs)
    downloader = ZiplineDownloader(
      dispatcher = dispatcher,
      httpClient = httpClient,
      downloadDir = downloadDir,
      downloadFileSystem = fileSystem,
    )
  }

  @After
  fun tearDown() {
    quickJs.close()
  }

  @Test
  fun downloadToDirectory(): Unit = runBlocking(dispatcher) {
    assertFalse(fileSystem.exists(downloadDir / PREBUILT_MANIFEST_FILE_NAME))
    assertFalse(fileSystem.exists(downloadDir / testFixtures.alphaSha256Hex))
    assertFalse(fileSystem.exists(downloadDir / testFixtures.bravoSha256Hex))

    httpClient.filePathToByteString = mapOf(
      manifestPath to testFixtures.manifestByteString,
      alphaFilePath to testFixtures.alphaByteString,
      bravoFilePath to testFixtures.bravoByteString
    )
    downloader.download(manifestPath)

    // check that files have been downloaded to downloadDir as expected
    assertTrue(fileSystem.exists(downloadDir / PREBUILT_MANIFEST_FILE_NAME))
    assertEquals(
      testFixtures.manifestByteString,
      fileSystem.read(downloadDir / PREBUILT_MANIFEST_FILE_NAME) { readByteString() })
    assertTrue(fileSystem.exists(downloadDir / testFixtures.alphaSha256Hex))
    assertEquals(
      testFixtures.alphaByteString,
      fileSystem.read(downloadDir / testFixtures.alphaSha256Hex) { readByteString() })
    assertTrue(fileSystem.exists(downloadDir / testFixtures.bravoSha256Hex))
    assertEquals(
      testFixtures.bravoByteString,
      fileSystem.read(downloadDir / testFixtures.bravoSha256Hex) { readByteString() })
  }
}
