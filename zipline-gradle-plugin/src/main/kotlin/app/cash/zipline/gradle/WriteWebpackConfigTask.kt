/*
 * Copyright (C) 2023 Square, Inc.
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

package app.cash.zipline.gradle

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Write Zipline's required webpack config to a file in the project directory. (Unfortunately
 * there's no API to tell Webpack to load its configuration files from a build directory.)
 */
internal abstract class WriteWebpackConfigTask : DefaultTask() {

  @get:OutputFile
  val webpackConfigFile: File by lazy {
    project.projectDir.resolve(generatedFilePath)
  }

  @TaskAction
  fun task() {
    webpackConfigFile.parentFile.mkdirs()
    webpackConfigFile.writeText(
      """
      |// DO NOT EDIT.
      |//
      |// This file is generated by the Zipline Gradle plugin. You may ignore it
      |// by adding this line to your .gitignore:
      |//
      |// generated-zipline-webpack-config.js
      |//
      |config.optimization = config.optimization || {};
      |const TerserPlugin = require("terser-webpack-plugin");
      |config.optimization.minimizer = [
      |  new TerserPlugin({
      |    terserOptions: {
      |      compress: {
      |        sequences: false,
      |      },
      |      mangle: {
      |      },
      |      format: {
      |        beautify: true,
      |        braces: true,
      |      }
      |    },
      |  }),
      |];
      |
      """.trimMargin(),
    )
  }

  companion object {
    val generatedFilePath = "webpack.config.d/generated-zipline-webpack-config.js"
  }
}