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
import org.eclipse.jgit.api.ApplyCommand
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.internal.deprecation.DeprecationLogger
import org.gradle.internal.logging.text.StyledTextOutput
import org.gradle.internal.logging.text.StyledTextOutputFactory

import javax.inject.Inject

import static com.netflix.nebula.lint.StyledTextService.Styling.*
import static org.gradle.internal.logging.text.StyledTextOutput.Style
abstract class FixGradleLintTask extends DefaultTask implements VerificationTask {
    @Input
    @Optional
    abstract ListProperty<GradleLintViolationAction> getUserDefinedListeners()
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getRootDirectory()

    @Internal
    abstract DirectoryProperty getBuildDirectory()

    @Nested
    abstract Property<ProjectTree> getProjectTree()

    @Inject
    protected abstract StyledTextOutputFactory getStyledTextOutputFactory()


    /**
     * Special listener tied into nebula.metrics via nebula.info to ship violation information to a
     * metrics endpoint
     */
    @Internal
    GradleLintInfoBrokerAction infoBrokerAction


    FixGradleLintTask() {
        infoBrokerAction = new GradleLintInfoBrokerAction(project)
        userDefinedListeners.convention([])
        outputs.upToDateWhen { false }
        group = 'lint'
        Project project = getProject()
        rootDirectory.set(project.getRootDir())
        buildDirectory.set(project.getLayout().getBuildDirectory())
        projectTree.set(project.getProviders().provider(() -> computeProjectTree(project.getRootProject())))
        doNotTrackState("Bypassing persistent file locking issue in the build environment.")
    }

    protected ProjectTree computeProjectTree(Project project) {
        GradleLintExtension rootExt = project.extensions.findByType(GradleLintExtension.class)
        if (rootExt == null) {
            throw new IllegalStateException("GradleLintExtension not found on root project. Please ensure the lint plugin is applied to the root project.")
        }
        List<ProjectInfo> projectInfos = ([project] + project.getSubprojects().asList()).collect {Project p -> ProjectInfo.from(p, rootExt) }
        return new ProjectTree(projectInfos)
    }

    @TaskAction
    void lintCorrections() {
        //TODO: address Invocation of Task.project at execution time has been deprecated.
        DeprecationLogger.whileDisabled {
            Project project = getProject()
            def violations = new LintService(project.getRootProject()).lint(projectTree.get(), false).violations
                    .unique { v1, v2 -> v1.is(v2) ? 0 : 1 }

            (userDefinedListeners.get() + infoBrokerAction + new GradleLintPatchAction(project)).each {
                it.lintFinished(violations)
            }

            def patchFile = new File(project.layout.buildDirectory.asFile.get(), GradleLintPatchAction.PATCH_NAME)
            if (patchFile.exists()) {
                new ApplyCommand(new NotNecessarilyGitRepository(project.projectDir)).setPatch(patchFile.newInputStream()).call()
            }

            (userDefinedListeners.get() + infoBrokerAction + consoleOutputAction).each {
                it.lintFixesApplied(violations)
            }
        }
    }
    @Internal
  final GradleLintViolationAction consoleOutputAction = new GradleLintViolationAction() {
            @Override
            void lintFixesApplied(Collection<GradleViolation> violations) {
                StyledTextOutputFactory styledTextOutputFactory = getStyledTextOutputFactory()
                File rootDir = getRootDirectory().get().asFile

                StyledTextOutput textOutput = styledTextOutputFactory.create("nebula-fix-lint")
                if (violations.empty) {
                    textOutput.println("Passed lint check with 0 violations; no corrections necessary")
                } else {
                    textOutput.withStyle(Style.Header).text('\nThis project contains lint violations. ')
                    textOutput.println('A complete listing of my attempt to fix them follows. Please review and commit the changes.\n')
                }

                int completelyFixed = 0
                int unfixedCriticalViolations = 0

                violations.groupBy { it.file }.each { buildFile, projectViolations ->

                    projectViolations.each { v ->
                        String buildFilePath = rootDir.toURI().relativize(v.file.toURI()).toString()
                        def unfixed = v.fixes.findAll { it.reasonForNotFixing != null }
                        if (v.fixes.empty) {
                            textOutput.text('needs fixing'.padRight(15))
                            if (v.rule.priority == 1) {
                                unfixedCriticalViolations++
                            }
                        } else if (unfixed.empty) {
                            textOutput.text('fixed'.padRight(15))
                            completelyFixed++
                        } else if (unfixed.size() == v.fixes.size()) {
                            textOutput.text('unfixed'.padRight(15))
                            if (v.rule.priority == 1) {
                                unfixedCriticalViolations++
                            }
                        } else {
                            textOutput.text('semi-fixed'.padRight(15))
                            if (v.rule.priority == 1) {
                                unfixedCriticalViolations++
                            }
                        }

                        textOutput.text(v.rule.name.padRight(35))
                        textOutput.println(v.message)

                        if (v.lineNumber) {
                            textOutput.println(buildFilePath + ':' + v.lineNumber)
                        }
                        if (v.sourceLine) {
                            textOutput.println(v.sourceLine)
                        }

                        if (!unfixed.empty) {
                            textOutput.println('reason not fixed: ')
                            unfixed.collect { it.reasonForNotFixing }.unique().each { textOutput.println(it.message) }
                        }

                        textOutput.println() // extra space between violations
                    }
                }

                textOutput.println("Corrected $completelyFixed lint problems\n")

                if (unfixedCriticalViolations > 0) {
                    throw new GradleException("This build contains $unfixedCriticalViolations critical lint violation" +
                            "${unfixedCriticalViolations == 1 ? '' : 's'} that could not be automatically fixed")
                }
            }
        }
    }

