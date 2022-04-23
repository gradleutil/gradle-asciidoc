package net.gradleutil.asciidoc

import org.asciidoctor.gradle.jvm.AsciidoctorJPlugin
import org.asciidoctor.gradle.jvm.AsciidoctorTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.process.ExecSpec
import org.gradle.tooling.*

import java.awt.*
import java.util.List

class GradleUtilAsciidocPlugin implements Plugin<Project> {

    Project project

    void apply(Project project) {

        this.project = project

        project.pluginManager.apply(AsciidoctorJPlugin)

        AsciidoctorTask asciidoctor = project.tasks.getByName('asciidoctor') as AsciidoctorTask

        if(!project.repositories.size()){
            project.repositories.mavenCentral()
        }

        asciidoctor.doFirst {
            if(!asciidoctor.attributes.get('docinfodir')){
                def docInfoDir = project.file(project.buildDir.path + '/docinfo')
                docInfoDir.mkdirs()
                asciidoctor.attributes docinfodir: docInfoDir.path
                ['/docinfo/docinfo.html','/docinfo/docinfo-footer.html'].each{
                    String resource = GradleUtilAsciidocPlugin.class.getResourceAsStream( it ).text;
                    File output = new File( docInfoDir, it.split( '/' ).last() ); output << resource
                }
            }
        }

        /**
         * replaces placeholder header content with needed header references
         **/
        def docsUpdateHeaders = project.tasks.register("docsUpdateHeaders") {
            description = 'Update the asciidoc headers'
            def headerSourceFile = project.file('docinfo/include-header.adoc')
            onlyIf { headerSourceFile.exists() }
            doFirst {
                logger.lifecycle "Updating any header content if different"
            }
            doLast {
                project.fileTree('../') { PatternFilterable p ->
                    p.include '**/*.adoc'
                    p.exclude '**/_out/**', '**/out/**'
                }.each { adocFile ->
                    def adocText = adocFile.text
                    def headerSplit = headerSourceFile.text.split('// references')
                    assert headerSplit.size() == 2
                    def headerText = "// auto-update-header:begin\n${headerSplit[0]}// references\n"
                    def references = headerSplit[1]
                    adocText.findAll(/\/\/ auto-update-header:begin([\s\S]+?)\/\/ auto-update-header:end/).each { docHeader ->
                        references.eachLine { line ->
                            if (line.startsWith(':')) {
                                def reference = line.split(':')[1]
                                if (adocText.contains("{${reference}")) {
                                    logger.trace "${adocFile} contains reference: `{${reference}}`"
                                    headerText += line + '\n'
                                }
                            }
                        }
                        headerText += '// auto-update-header:end'
                        if (headerText.trim() == docHeader.trim()) {
                            logger.info "Matching header content for ${adocFile.path}"
                        } else {
                            def newText = adocText.replace(docHeader, headerText)
                            adocFile.text = newText
                            logger.lifecycle "Updated header content of ${adocFile.path}"
                        }
                    }
                }
            }
        }
        asciidoctor.shouldRunAfter(docsUpdateHeaders)


        def docsUpdate = project.tasks.register("docsUpdate") {
            description = 'Update the generated sections of documentation'
            dependsOn docsUpdateHeaders
            doFirst {
                logger.lifecycle "Updating any generated content if different"
            }
            doLast {
                List<String> projectPaths = project.rootProject.allprojects.collect { it.project.path }.flatten() as List<String>
                project.fileTree('../') { PatternFilterable p ->
                    p.include '**/*.adoc'
                    p.exclude '**/_out/**', '**/out/**'
                }.each { adocFile ->
                    def fileText = adocFile.text
                    projectPaths.each { projectPath ->
                        if (fileText.contains(".${projectPath} tasks")) {
                            replaceProjectTaskTables(projectPath, adocFile)
                        }
                    }
                    projectGroups.each { group ->
                        if (fileText.contains(".${group} tasks")) {
                            replaceGroupTaskTables(group, adocFile)
                        }
                    }
                    if (fileText.matches(/(?sm).*\n.?Output of `.*/)) {
                        replaceOutputTables(adocFile)
                    }
                    if (fileText.matches(/(?sm).*groovyScript.*/)) {
                        replaceGroovyScript(adocFile)
                    }
                }
            }
            asciidoctor.shouldRunAfter(it)
        }

        def docs = project.tasks.register("docs") {
            description = 'Generate the docs'
            dependsOn asciidoctor
            if (project.findProperty('update') == 'true') {
                dependsOn docsUpdate
            }
            doLast {
                def readmeFile = project.file("${asciidoctor.outputDir.path}/book.html")
                logger.lifecycle "Generated docbook file://${readmeFile.path}"
            }
        }

        project.tasks.register("docsView") {
            description = 'Single-page HTML is exported and opened with the default browser'
            dependsOn docs
            doLast {
                def readmeFile = project.file("${asciidoctor.outputDir.path}/book.html")
                logger.lifecycle "Opening browser to file://${readmeFile.path}"
                /*
                        project.exec{
                            commandLine "xdg-open", readmeFile.toURI()
                        }
                */
                try {
                    Desktop.desktop.open readmeFile
                } catch (Exception e) {
                    e.printStackTrace()
                }
            }
        }

    }

    /**
     * replaces placeholder content with list of tasks linked to source build files
     **/
    def asciidocTaskTable(String path) {
        def ignoreTasks = ['idea', 'ideaModule', 'cleanIdea', 'cleanIdeaModule', 'taskTree', 'populateECRCredentials', 'buildEnvironment',]
        def projectTasks = project.project(path).tasks.findAll { !ignoreTasks.contains(it.name) && it.description && it.group }
        if (!projectTasks.size()) {
            return ''
        }
        StringBuilder sb = new StringBuilder()
        sb.append(".${path} tasks\n[%header,format=csv,]\n|===\nGradle Task,Description")
        projectTasks.each { task ->
            def relPath = project.rootProject.projectDir.absoluteFile.toURI().relativize(task.project.buildFile.toURI())
            sb.append("\nlink:{baseUrl}../${relPath}[${task.name}], ${task.description.replaceAll(',', '')}")
        }
        sb.append("\n|===")
    }

    List<String> getProjectGroups() {
        List<String> groups = project.rootProject.allprojects.tasks.flatten().collect { it?.group }.unique().findAll { it }
        groups.collect { groupNameRegex(it) } as List<String>
    }

    static String groupNameRegex(String groupName) {
        (groupName ?: '').replaceAll(/(\s*\d[.) ]*)?/, '')
    }

    /**
     * replaces placeholder content with list of tasks linked to source build files
     **/
    def asciidocTaskGroupTable(String group) {
        def groupTasks = project.rootProject.getAllTasks(true).values()*.
                findAll { it.group && groupNameRegex(it.group) == group }.flatten()
        if (!groupTasks.size()) {
            return ''
        }
        StringBuilder sb = new StringBuilder()
        sb.append(".${group} tasks\n[%header,format=csv,]\n|===\nGradle Task,Description")
        groupTasks.each { task -> sb.append("\n*${task.name}*, _${task.description?.replaceAll(',', '') ?: ''}_")
        }
        sb.append("\n|===")
    }


    /**
     * replaces placeholder content with list of projects and tasks linked to source build files
     **/
    def replaceProjectTaskTables(String path, File adocFile) {
        replaceTaskTables(path, adocFile, { asciidocTaskTable(path) })
    }


    /**
     * replaces placeholder content with list of projects and tasks linked to source build files
     **/
    def replaceGroupTaskTables(String group, File adocFile) {
        replaceTaskTables(group, adocFile, { asciidocTaskGroupTable(group) })
    }


    /**
     * replaces placeholder content with list of projects and tasks linked to source build files
     **/
    def replaceTaskTables(String string, File adocFile, Closure tableTextClosure) {
        def docText = adocFile.text
        def match = (docText =~ /(\.${string} tasks\s+\[%header.+\n\|===\n[\s\S]+?\|===)/)
        if (match.find()) {
            def docTable = match.group(1).trim()
            def taskTable = tableTextClosure() as String
            if (docTable == taskTable) {
                project.logger.info "Matching asciiDoc task table: ${adocFile.path} - ${string}"
            } else {
                if (taskTable) {
                    adocFile.text = docText.replaceAll(/\.${string} tasks\s+\[%header.+\n\|===\n[\s\S]+?\|===/, taskTable)
                    project.logger.lifecycle "Updated asciiDoc task table: ${adocFile.path} - ${string}"
                }
            }
        }
    }

    /**
     * replaces placeholder content with output from command
     **/
    def replaceOutputTables(File adocFile) {
        def docText = adocFile.text
        def matcher = (docText =~ /((\.Output of `([^`]+)`[\s\S]+?)(```[^\n]*)([\s\S]+?)```)/)
        if (matcher.getCount()) {
            matcher.each {
                def outputOfLine = it[2] as String
                def line = it[3] as String
                def gradlePath = project.gradle.gradleHomeDir.path + '/bin/gradle'
                def cleanedLine = line.toString().replace('{gradle}', gradlePath + " -q")
                def docCommandOutput = it[5].toString().trim()
                def commandOutput = executeLine(cleanedLine).trim()
                if (commandOutput == docCommandOutput) {
                    project.logger.info "Matching output of command ${adocFile.path} - ${line}"
                } else {
                    def newText = docText.replace(it[0] as String, "${outputOfLine}${it[4]}\n${commandOutput}\n```")
                    adocFile.text = newText
                    docText = newText
                    project.logger.lifecycle "Updated output of command ${adocFile.path} - ${line}"
                }
            }
        }
    }


    /**
     * replaces placeholder content with list of projects and tasks linked to source build files
     **/
    def projectToAsciidoc(Project project, prefix = '==') {
        def sb = new StringBuilder()
        def path = project.buildscript.sourceFile.path.replace(project.rootDir.path, '')
        def projectPath = project.getPath() == ':' ? '' : project.getPath()
        def link = project.buildscript.sourceFile.exists() ? "${projectPath} link:{baseUrl}..${path}[${path}, window=\"gitsrc\"]::\n" : ''
        sb.append("[[${project.name}_project]]\n// tag::gradle_${project.name}[]\n")
        sb.append("${prefix} ${project.name}\n${link}${project.description ? project.description + '\n' : ''}")
        def tasksTable = asciidocTaskTable(project.getPath())
        if (tasksTable) {
            sb.append('\n' + tasksTable + '\n')
        }
        sb.append(" - - - \n// end::gradle_${project.name}[]\n")
        project.childProjects.each { subKey, subName -> sb.append('\n' + projectToAsciidoc(project.project(subKey), prefix + prefix.take(1)))
        }
        return sb.toString()
    }

    /**
     * replaces placeholder content with output from groovy closure
     **/
    def replaceGroovyScript(File adocFile) {
        def docText = adocFile.text
        def matcher = (docText =~ /\/\/\/\/\s*groovyScript ((.+)=([\s\S]+?))\/\/\/\/([\s\S]+?)\/\/([\s]+groovyScript)/)
        if (matcher.getCount()) {
            matcher.each {
                def closureName = it[2] as String
                def closureContent = it[1] as String
                def docClosureOutput = it[4] as String
                def script = new GroovyClassLoader().parseClass(closureContent).newInstance()
                script.binding = new Binding([project: project, caller: this])
                script.run()
                def closureOutput = script.invokeMethod(closureName, null)
                if (closureOutput.trim() == docClosureOutput.trim()) {
                    project.logger.info "Matching closure output of ${adocFile.path} - ${closureName}"
                } else {
                    def newText = docText.replace(it[0] as String, it[0].toString().replace(docClosureOutput, '\n' + closureOutput + '\n'))
                    adocFile.text = newText
                    docText = newText
                    project.logger.lifecycle "Updated closure output of ${adocFile.path} - ${closureName}"
                }

            }
        }
    }


    /**
     * Execute a full command line, versus an array of args
     * @param line
     * @return execution output
     */
    String executeLine(String line, File fromDir = project.rootProject.file('.')) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        project.logger.info "Executing: ${line}"
        try {
            project.exec { ExecSpec s ->
                s.commandLine = ['/bin/sh', '-c', line]
                s.setStandardOutput(outputStream)
                s.setErrorOutput(outputStream)
                s.workingDir fromDir
            }
        } catch (Exception e) {
            e.printStackTrace()
            throw new GradleException(outputStream.toString())
        }
        return outputStream.toString()
    }


}
