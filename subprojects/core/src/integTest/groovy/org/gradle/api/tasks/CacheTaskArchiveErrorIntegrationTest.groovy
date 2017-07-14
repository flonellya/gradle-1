/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.api.tasks

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.DirectoryBuildCacheFixture
import org.gradle.test.fixtures.file.TestFile
import org.gradle.util.TextUtil

import static org.gradle.util.Matchers.containsText
import static org.gradle.util.Matchers.matchesRegexp

class CacheTaskArchiveErrorIntegrationTest extends AbstractIntegrationSpec implements DirectoryBuildCacheFixture {

    def setup() {
        executer.beforeExecute { it.withBuildCacheEnabled() }
    }

    def remoteCacheDir = file("remote-cache-dir")

    def "describes error while packing archive"() {
        when:
        file("input.txt") << "data"

        // Just a way to induce a packing error, i.e. corrupt/partial archive
        buildFile << """
            apply plugin: "base"
            task customTask {
                inputs.file "input.txt"
                outputs.file "build/output" withPropertyName "output"
                outputs.cacheIf { true }
                doLast {
                  mkdir('build/output')
                  file('build/output/output.txt').text = file('input.txt').text
                }
            }
        """

        executer.withStackTraceChecksDisabled()

        then:
        fails "customTask"
        failure.assertThatDescription(matchesRegexp("Failed to store cache entry .+ for task :customTask"))
        failure.assertThatCause(containsText("Could not pack property 'output'"))
        listCacheFiles().empty
        listCacheTempFiles().empty

        when:
        buildFile << """
            customTask {
                actions = []
                doLast {
                    mkdir("build") 
                    file("build/output").text = "text" 
                }
            }
        """

        then:
        succeeds("clean", "customTask")
        ":customTask" in executedTasks
    }

    def "archive is not pushed to remote when packing fails"() {
        when:
        file("input.txt") << "data"
        enableRemote()

        // Just a way to induce a packing error, i.e. corrupt/partial archive
        buildFile << """
            apply plugin: "base"
            task customTask {
                inputs.file "input.txt"
                outputs.file "build/output" withPropertyName "output"
                outputs.cacheIf { true }
                doLast {
                  mkdir('build/output')
                  file('build/output/output.txt').text = file('input.txt').text
                }
            }
        """

        executer.withStackTraceChecksDisabled()

        then:
        fails "customTask"
        listCacheFiles(remoteCacheDir).empty
    }

    TestFile enableRemote() {
        settingsFile << """
            buildCache {
                remote(DirectoryBuildCache) {
                    push = true
                    directory = '${TextUtil.escapeString(remoteCacheDir.absolutePath)}'
                }
            }
        """
    }

    def "corrupt archive loaded from remote cache is not copied into local cache"() {
        when:
        file("input.txt") << "data"
        enableRemote()
        buildFile << """
            apply plugin: "base"
            task customTask {
                inputs.file "input.txt"
                outputs.file "build/output" withPropertyName "output"
                outputs.cacheIf { true }
                doLast {
                  mkdir('build')
                  file('build/output').text = file('input.txt').text
                }
            }
        """
        succeeds("customTask")

        then:
        listCacheFiles(remoteCacheDir).size() == 1

        when:
        listCacheFiles(remoteCacheDir).first().text = "corrupt"
        listCacheFiles()*.delete()

        then:
        fails("clean", "customTask")
        failure.assertThatDescription(matchesRegexp("Build cache entry .+ from remote build cache is invalid"))
        failure.assertThatCause(containsText("java.util.zip.ZipException: Not in GZIP format"))

        and:
        listCacheFiles().empty

        when:
        settingsFile << """
            buildCache.remote.enabled = false
        """

        then:
        succeeds("clean", "customTask")
        ":customTask" in executedTasks
    }

    def "corrupt archive loaded from local cache is purged"() {
        when:
        file("input.txt") << "data"
        buildFile << """
            apply plugin: "base"
            task customTask {
                inputs.file "input.txt"
                outputs.file "build/output" withPropertyName "output"
                outputs.cacheIf { true }
                doLast {
                  mkdir('build')
                  file('build/output').text = file('input.txt').text
                }
            }
        """
        succeeds("customTask")

        then:
        listCacheFiles().size() == 1

        when:
        listCacheFiles().first().text = "corrupt"

        then:
        fails("clean", "customTask")
        failure.assertThatDescription(matchesRegexp("Build cache entry .+ from local build cache is invalid"))
        failure.assertThatCause(containsText("java.util.zip.ZipException: Not in GZIP format"))

        and:
        listCacheFiles().empty
        listCacheFailedFiles().size() == 1

        and:
        succeeds("clean", "customTask")
        ":customTask" in executedTasks
    }

    def "corrupted cache provides useful error message"() {
        when:
        buildFile << """
            @CacheableTask
            class CustomTask extends DefaultTask {
                @OutputDirectory File outputDir = temporaryDir
                @TaskAction
                void generate() {
                    new File(outputDir, "output").text = "OK"
                }
            }

            task cacheable(type: CustomTask)
        """
        withBuildCache().succeeds("cacheable")

        then:
        def cacheFiles = listCacheFiles()
        cacheFiles.size() == 1

        when:
        file("build").deleteDir()

        and:
        corruptMetadata({ metadata -> metadata.text = "corrupt" })
        withBuildCache().fails("cacheable")

        then:
        failure.assertHasCause("Cached result format error, corrupted origin metadata.")
        listCacheFailedFiles().size() == 1

        when:
        file("build").deleteDir()

        then:
        withBuildCache().succeeds("cacheable")
    }


    def corruptMetadata(Closure corrupter) {
        def cacheFiles = listCacheFiles()
        assert cacheFiles.size() == 1
        def cacheEntry = cacheFiles[0]
        def tgzCacheEntry = temporaryFolder.file("cache.tgz")
        cacheEntry.copyTo(tgzCacheEntry)
        cacheEntry.delete()
        def extractDir = temporaryFolder.file("extract")
        tgzCacheEntry.untarTo(extractDir)
        corrupter(extractDir.file("METADATA"))
        extractDir.tgzTo(cacheEntry)
    }

}
