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

import com.netflix.nebula.lint.GradleLintInfoBrokerAction
import com.netflix.nebula.lint.GradleLintPatchAction
import com.netflix.nebula.lint.GradleLintViolationAction
import com.netflix.nebula.lint.GradleViolation
import com.netflix.nebula.lint.rule.dependency.DependencyService
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.internal.deprecation.DeprecationLogger
import org.gradle.internal.impldep.kotlinx.serialization.Transient
import org.gradle.internal.logging.text.StyledTextOutput
import org.gradle.internal.logging.text.StyledTextOutputFactory

import javax.inject.Inject
import java.util.function.Function
import java.util.function.Supplier

class ProjectInfo implements Serializable{
    @Input String name
    @Input String path
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) File projectDir
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE) File rootDir
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) File buildFile
    @Input List<String> effectiveRuleNames = []
    @Input List<String> effectiveExcludedRuleNames = []
    @Input List<String> criticalRuleNamesForThisProject = []
    transient Project rawProject



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
             //   project: project,
                name: project.name,
                path: project.path,
                projectDir: project.projectDir,
                rootDir: project.rootDir,
                buildFile: project.buildFile,
                effectiveRuleNames: calculatedEffectiveRules,
                effectiveExcludedRuleNames: calculatedEffectiveExcludedRules,
                criticalRuleNamesForThisProject: actualCriticalRulesForThisProject,
                rawProject: project
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
    @Input @Optional List<GradleLintViolationAction> listeners = []
    @Input @Optional abstract Property<Boolean> getFailOnWarning()
    @Input @Optional abstract Property<Boolean> getOnlyCriticalRules()
    @Input abstract Property<File> getProjectRootDir()
    @InputDirectory @PathSensitive(PathSensitivity.ABSOLUTE)
    abstract DirectoryProperty getRootDir()
    @Input
    @Optional
    abstract Property<ProjectTree> getProjectTree();


    protected ProjectTree computeProjectTree(Project project) {
        GradleLintExtension rootExt = project.extensions.findByType(GradleLintExtension.class)
        if (rootExt == null) {
            throw new IllegalStateException("GradleLintExtension not found on root project '${project.path}'. Please ensure the lint plugin is applied to the root project.")
        }
        List<ProjectInfo> projectInfos = ([project] + project.getSubprojects().asList()).collect {Project p -> ProjectInfo.from(p, rootExt) }
        return new ProjectTree(projectInfos)
    }
    @Inject
    LintGradleTask() {
        failOnWarning.convention(false)
        onlyCriticalRules.convention(false)
        //getRootDir().convention(project.rootProject.layout.projectDirectory)
        //lintService = new LintService(() -> getProject())
        getRootDir().convention(project.layout.projectDirectory);
        getProjectTree().convention(project.providers.provider { computeProjectTree(project) })
        group = 'lint'
        listeners.add(consoleOutputAction)

    }

    @TaskAction
    void lint() {

        File rootDir = projectRootDir.getOrNull()
        if (rootDir == null || !rootDir.exists()) {
            throw new GradleException("Root directory is not set or does not exist.")
        }

        def violations = new LintService().lint( projectTree.get(), onlyCriticalRules.get()).violations
                .unique { v1, v2 -> v1.is(v2) ? 0 : 1 }

        listeners.each { it.lintFinished(violations) }
    }

    @Internal
    final def consoleOutputAction = new GradleLintViolationAction() {
        @Override
        void lintFinished(Collection<GradleViolation> violations) {
            int errors = violations.count { it.rule.priority == 1 }
            int warnings = violations.count { it.rule.priority != 1 }

            StyledTextOutput textOutput = getServices().get(StyledTextOutputFactory.class).create("nebula-lint")

            if (!violations.empty) {
                textOutput.text('\nThis project contains lint violations. ')
                textOutput.println('A complete listing of the violations follows. ')

                if (errors) {
                    textOutput.text('Because some were serious, the overall build status has been changed to ')
                            .println("FAILED\n")
                } else {
                    textOutput.println('Because none were serious, the build\'s overall status was unaffected.\n')
                }
            }
            violations.groupBy { it.file }.each { buildFile, violationsByFile ->

                violationsByFile.each { v ->
                    String buildFilePath = projectRootDir.get().toURI().relativize(v.file.toURI()).toString()
                    if (v.rule.priority == 1) {
                        textOutput.text('error'.padRight(10))
                    } else {
                        textOutput.text('warning'.padRight(10))
                    }

                    textOutput.text(v.rule.name.padRight(35))

                    textOutput.text(v.message)
                    if (v.fixes.empty) {
                        textOutput.text(' (no auto-fix available)')
                    }
                    if (v.documentationUri != GradleViolation.DEFAULT_DOCUMENTATION_URI) {
                        textOutput.text(". See $v.documentationUri for more details")
                    }
                    textOutput.println()

                    if (v.lineNumber) {
                        textOutput.println(buildFilePath + ':' + v.lineNumber)
                    }
                    if (v.sourceLine) {
                        textOutput.println("$v.sourceLine")
                    }

                    textOutput.println() // extra space between violations
                }
            }

            if (!violations.empty) {
                textOutput.println("\u2716 ${errors + warnings} problem${errors + warnings == 1 ? '' : 's'} ($errors error${errors == 1 ? '' : 's'}, $warnings warning${warnings == 1 ? '' : 's'})\n".toString())
                textOutput.text("To apply fixes automatically, run ").text("fixGradleLint")
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
