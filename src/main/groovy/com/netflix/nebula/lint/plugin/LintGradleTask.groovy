/*
 * Copyright 2015-2019 Netflix, Inc.
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
package com.netflix.nebula.lint.plugin

import com.netflix.nebula.lint.*
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.internal.deprecation.DeprecationLogger
//import static com.netflix.nebula.lint.StyledTextService.Styling.*

class ProjectInfo implements Serializable{
    @Input String name
    @Input String path
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) File projectDir
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) File rootDir
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) File buildFile
    @Input List<String> effectiveRuleNames = []
    @Input List<String> effectiveExcludedRuleNames = []
    @Input List<String> criticalRuleNamesForThisProject = []


    static ProjectInfo from(Project project ,GradleLintExtension rootProjectExtension) {
        if (rootProjectExtension == null) {
            throw new IllegalStateException("Root project GradleLintExtension is required but not found.")
        }

        GradleLintExtension projectSpecificExtension = project.extensions.findByType(GradleLintExtension.class) ?: rootProjectExtension
        List<String> calculatedEffectiveRules
        if (project.hasProperty('gradleLint.rules')) {
            calculatedEffectiveRules = project.property('gradleLint.rules').toString().split(',').collect { it.trim() }.unique()
        } else {
            calculatedEffectiveRules = ((projectSpecificExtension.getRules() ?: []) + (projectSpecificExtension.getCriticalRules() ?: [])).unique()
        }

        List<String> propertyExcludedRules = []
        if (project.hasProperty('gradleLint.excludedRules')) {
            propertyExcludedRules = project.property('gradleLint.excludedRules').toString().split(',').collect { it.trim() }
        }

        List<String> calculatedEffectiveExcludedRules = (propertyExcludedRules + (projectSpecificExtension.getExcludedRules() ?: [])).unique()
        List<String> actualCriticalRulesForThisProject = new ArrayList<>(projectSpecificExtension.getCriticalRules() ?: [])


        return new ProjectInfo(
                name: project.name,
                path: project.path,
                projectDir: project.projectDir,
                rootDir: project.rootDir,
                buildFile: project.buildFile,
                effectiveRuleNames: calculatedEffectiveRules,
                effectiveExcludedRuleNames: calculatedEffectiveExcludedRules,
                criticalRuleNamesForThisProject: actualCriticalRulesForThisProject
        )
    }
}

class ProjectTree {
    @Nested
    List<ProjectInfo> allProjects

    ProjectTree(List<ProjectInfo> allProjects) {
        this.allProjects = allProjects
    }
}

abstract class LintGradleTask extends DefaultTask {
    @Input
    @Optional
    List<GradleLintViolationAction> listeners = []

    @Input
    @Optional
    abstract Property<Boolean> getFailOnWarning()

    @Input
    @Optional
    abstract Property<Boolean> getOnlyCriticalRules()

    @Input
    abstract Property<File> getProjectRootDir()

    @Nested
    abstract Property<ProjectTree> getProjectTree()

    protected ProjectTree computeProjectTree(Project project) {
        // TODO-Nouran: collect project and subproject information
       // def projectInfos = ([project] + project.subprojects).collect {ProjectInfo.from(it)}
       // return new ProjectTree( projectInfos)
        GradleLintExtension rootExt = project.extensions.findByType(GradleLintExtension.class)
        if (rootExt == null) {
            throw new IllegalStateException("GradleLintExtension not found on root project '${project.path}'. Please ensure the lint plugin is applied to the root project.")
        }
        List<ProjectInfo> projectInfos = ([project] + project.getSubprojects().asList()).collect {Project p -> ProjectInfo.from(p, rootExt) }
        return new ProjectTree(projectInfos)
    }

    LintGradleTask() {
        failOnWarning.convention(false)
        onlyCriticalRules.convention(false)
        getProjectRootDir().set(getProject().getRootProject().getProjectDir())
        projectTree.set(getProject().getProviders().provider(() -> computeProjectTree(getProject().getRootProject())))
       // projectTree.set(getProject().getProviders().provider(() -> computeProjectTree(getProject())))
        group = 'lint'
       /* try {
            def method = Task.getMethod("notCompatibleWithConfigurationCache")
            method.invoke(this)
        } catch (NoSuchMethodException ignore) {
        }*/
    }

    @TaskAction
    void lint() {

        DeprecationLogger.whileDisabled {
            def violations = new LintService().lint(projectTree.get(),onlyCriticalRules.get()).violations
                    .unique { v1, v2 -> v1.is(v2) ? 0 : 1 }
            File rootDirFile = projectRootDir.get()
            def patchAction = new GradleLintPatchAction(rootDirFile)
            def infoAction = new GradleLintInfoBrokerAction(rootDirFile)

            (getListeners() + patchAction + infoAction + consoleOutputAction).each {
                it.lintFinished(violations)
            }
        }

    }

    @Internal
    final def consoleOutputAction = new GradleLintViolationAction() {
        @Override
        void lintFinished(Collection<GradleViolation> violations) {
            int errors = violations.count { it.rule.priority == 1 }
            int warnings = violations.count { it.rule.priority != 1 }

            def textOutput = new StyledTextService(getServices())

            if (!violations.empty) {
                textOutput.withStyle(Bold).text('\nThis project contains lint violations. ')
                textOutput.println('A complete listing of the violations follows. ')

                if (errors) {
                    textOutput.text('Because some were serious, the overall build status has been changed to ')
                            .withStyle(Red).println("FAILED\n")
                } else {
                    textOutput.println('Because none were serious, the build\'s overall status was unaffected.\n')
                }
            }
            violations.groupBy { it.file }.each { buildFile, violationsByFile ->

                violationsByFile.each { v ->
                    String buildFilePath = projectRootDir.get().toURI().relativize(v.file.toURI()).toString()
                    if (v.rule.priority == 1) {
                        textOutput.withStyle(Red).text('error'.padRight(10))
                    } else {
                        textOutput.withStyle(Red).text('warning'.padRight(10))
                    }

                    textOutput.text(v.rule.name.padRight(35))

                    textOutput.withStyle(Yellow).text(v.message)
                    if (v.fixes.empty) {
                        textOutput.withStyle(Yellow).text(' (no auto-fix available)')
                    }
                    if (v.documentationUri != GradleViolation.DEFAULT_DOCUMENTATION_URI) {
                        textOutput.text(". See $v.documentationUri for more details")
                    }
                    textOutput.println()

                    if (v.lineNumber) {
                        textOutput.withStyle(Bold).println(buildFilePath + ':' + v.lineNumber)
                    }
                    if (v.sourceLine) {
                        textOutput.println("$v.sourceLine")
                    }

                    textOutput.println() // extra space between violations
                }
            }

            if (!violations.empty) {
                textOutput.withStyle(Red).println("\u2716 ${errors + warnings} problem${errors + warnings == 1 ? '' : 's'} ($errors error${errors == 1 ? '' : 's'}, $warnings warning${warnings == 1 ? '' : 's'})\n".toString())
                textOutput.text("To apply fixes automatically, run ").withStyle(Bold).text("fixGradleLint")
                textOutput.println(", review, and commit the changes.\n")

                if (errors > 0) {
                    throw new GradleException("This build contains $errors critical lint violation${errors == 1 ? '' : 's'}")
                }

                if (failOnWarning.get()) {
                    throw new GradleException("This build contains $warnings lint violation${warnings == 1 ? '' : 's'}")
                }
            }
        }
    }
}
