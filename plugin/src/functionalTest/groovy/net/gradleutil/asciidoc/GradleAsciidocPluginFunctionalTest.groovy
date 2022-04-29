package net.gradleutil.asciidoc

import spock.lang.Specification
import spock.lang.TempDir
import org.gradle.testkit.runner.GradleRunner

/**
 * A simple functional test.
 */
class GradleAsciidocPluginFunctionalTest extends Specification {
    @TempDir
    private File projectDir

    private getBuildFile() {
        new File(projectDir, "build.gradle")
    }

    private getSettingsFile() {
        new File(projectDir, "settings.gradle")
    }

    private getReadmeFile() {
        new File(projectDir.path + '/src/docs/asciidoc/').with{
            mkdirs()
            return new File(it, "README.adoc")
        }
    }

    private getBookFile() {
        new File(projectDir.path + '/src/docs/asciidoc/').with{
            mkdirs()
            return new File(it, "book.adoc")
        }
    }

    def "can run docsUpdate task"() {
        given:
        settingsFile << ""
        buildFile << """
plugins {
    id('net.gradleutil.gradle-asciidoc')
}

tasks.register('1someOtherTask'){
  group = '3. Some Group'
  description = 'Some Other Description'
}
tasks.register('3someThirdTask'){
  group = '3. Some Group'
  description = 'Some Third Description'
}
tasks.register('2someTask'){
  group = '4. Some Group'
  description = 'Some Description'
  dependsOn '3someThirdTask'
}
"""
        readmeFile << """
= Configuration

.Some Group tasks
[%header,format=csv,]
|===
Gradle Task,Description
|===

.Output of `{gradle} -version | head -12`
```
```
"""

        when:
        def runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments("docsUpdate", '-S')
        runner.withProjectDir(projectDir)
        def result = runner.build()

        then:
        result.output.contains("Updated asciiDoc task table")
    }


    def "can run docs task"() {
        given:
        settingsFile << ""
        buildFile << """
plugins {
    id('net.gradleutil.gradle-asciidoc')
}
version = '1.0.0'

docs{
    outputDir = file('.')
    sourceDir = file('src/docs/asciidoc/')
    addVersionSuffix.set true
}
"""
        readmeFile << """
= BIG Ol README FILE

Lots of info goes here

"""

        bookFile << """
= Documentation
:doctype: book
:toc: left

:leveloffset: +1

include::README.adoc[]

:leveloffset: -1

"""

        when:
        def runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments("docs", '-S')
        runner.withProjectDir(projectDir)
        def result = runner.build()

        then:
        result.output.contains("Generated docbook")
        // the book should exist but the readme should not
        new File(projectDir,"book-1.0.0.html").exists()
        !new File(projectDir,"README.html").exists()
    }

    def "can run docs pdf"() {
        given:
        settingsFile << ""
        buildFile << """
plugins {
    id('net.gradleutil.gradle-asciidoc')
}
version = '1.0.0'

docs{
    outputDir = file('.')
    sourceDir = file('src/docs/asciidoc/')
    addVersionSuffix.set true
}
"""
        readmeFile << """
= BIG Ol README FILE

Lots of info goes here

"""

        bookFile << """
= Documentation
:doctype: book
:toc: left

:leveloffset: +1

include::README.adoc[]

:leveloffset: -1

"""

        when:
        def runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments("docsPdf", '-S')
        runner.withProjectDir(projectDir)
        def result = runner.build()

        then:
        result.output.contains("Generated docbook")
        // the book should exist but the readme should not
        new File(projectDir,"book-1.0.0.pdf").exists()
        !new File(projectDir,"README.html").exists()
    }
}
