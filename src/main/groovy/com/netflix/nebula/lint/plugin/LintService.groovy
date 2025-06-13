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


import com.netflix.nebula.lint.GradleViolation
import com.netflix.nebula.lint.rule.BuildFiles
import com.netflix.nebula.lint.rule.GradleLintRule
import com.netflix.nebula.lint.rule.ModelAwareGradleLintRule
import com.netflix.nebula.lint.rule.dependency.DependencyService
import org.codenarc.analyzer.AbstractSourceAnalyzer
import org.codenarc.results.DirectoryResults
import org.codenarc.results.FileResults
import org.codenarc.results.Results
import org.codenarc.rule.Rule
import org.codenarc.ruleset.CompositeRuleSet
import org.codenarc.ruleset.ListRuleSet
import org.codenarc.ruleset.RuleSet
import org.codenarc.source.SourceString
import org.gradle.api.Project
import org.gradle.api.provider.Provider

import java.util.function.Function
import java.util.function.Supplier
class LintService {
    private final Project rootProject
   // private final DirectoryResults resultsForRootProject
    def registry = new LintRuleRegistry()

   /* LintService(File rootDir) {
        resultsForRootProject = new DirectoryResults(rootDir.absolutePath)
    }/*

    /**
     * An analyzer that can be used over and over again against multiple subprojects, compiling the results, and recording
     * the affected files according to which files the violation fixes touch
     */
    class ReportableAnalyzer extends AbstractSourceAnalyzer implements Serializable {
        DirectoryResults resultsForRootProject

        ReportableAnalyzer(File rootDir) {
            resultsForRootProject = new DirectoryResults(rootDir.absolutePath)
        }

        Results analyze(ProjectInfo analyzedProject, String source, RuleSet ruleSet) {
            DirectoryResults results
            if (resultsForRootProject.path != analyzedProject.projectDir.absolutePath) {
                results = new DirectoryResults(analyzedProject.projectDir.absolutePath)
                resultsForRootProject.addChild(results)
            } else {
                results = resultsForRootProject
            }

            def violations = (collectViolations(new SourceString(source), ruleSet) as List<GradleViolation>)

            violations.groupBy { it.file }.each { file, fileViolations ->
                results.addChild(new FileResults(file.absolutePath, fileViolations))
                results.numberOfFilesInThisDirectory++
            }

            resultsForRootProject
        }

        @Override
        Results analyze(RuleSet ruleSet) {
            throw new UnsupportedOperationException('use the two argument form instead')
        }

        List getSourceDirectories() {
            []
        }
    }

    private RuleSet ruleSetForProject(ProjectInfo p, boolean onlyCriticalRules) {
       // Supplier<Project> projectSupplier = { -> null }   //TODO need to change this null
     //   Supplier<Project> projectSupplier = { -> projectResolver.apply(p.path) } as Supplier<Project>
        Function<String, Project> projectResolver = { path -> rootProject.project(p.path) }


        if (p.buildFile == null || !p.buildFile.exists()) {
            LOGGER.warn("Build file for project '{}' (path: '{}') is null or does not exist. Returning empty ruleset.", projectInfo.name, projectInfo.path)
            return new ListRuleSet([])
        }

        List<String> rulesToConsider = p.effectiveRuleNames ?: []
       // Supplier<Project> projectSupplier = { -> rootProject.project(p.path) } as Supplier<Project>


        List<Rule> includedRules = rulesToConsider.unique()
                .collect { String ruleName ->
                    this.registry.buildRules(ruleName,projectResolver, p)
                }
                .flatten() as List<Rule>

        if (onlyCriticalRules) {
            includedRules = includedRules.findAll { Rule rule -> rule.isCritical() }
        }
        List<String> excludedRuleNames = p.effectiveExcludedRuleNames ?: []

        if (!excludedRuleNames.isEmpty()) {
            includedRules.retainAll { Rule rule -> !excludedRuleNames.contains(rule.getName()) }
        }

        return RuleSetFactory.configureRuleSet(includedRules)
    }


    RuleSet ruleSet(Function<String, Project> projectResolver,ProjectTree projectTree) {
        def ruleSet = new CompositeRuleSet()
        projectTree.allProjects.each { p ->
            ruleSet.addRuleSet(ruleSetForProject(projectResolver,p, false))
        }
        return ruleSet
    }

    Results lint(Function<String, Project> projectResolver,ProjectTree projectTree ,boolean onlyCriticalRules) {
        if (projectTree.allProjects.isEmpty()) {
            return new DirectoryResults("empty_project_tree_results") // Return empty results
        }

        File rootDir = projectTree.allProjects.first().rootDir
        def analyzer = new ReportableAnalyzer(rootDir)

        projectTree.allProjects.each { p ->
          //  Supplier<Project> projectSupplier = { -> null }
            def files = SourceCollector.getAllFiles(p.buildFile, p)
            def buildFiles = new BuildFiles(files)
            def ruleSet = ruleSetForProject( p, onlyCriticalRules)

            if (!ruleSet.rules.isEmpty()) {
                boolean containsModelAwareRule = false
                // establish which file we are linting for each rule
                ruleSet.rules.each { rule ->
                    if (rule instanceof GradleLintRule)
                        rule.buildFiles = buildFiles
                    containsModelAwareRule = containsModelAwareRule || rule instanceof ModelAwareGradleLintRule
                }

                analyzer.analyze(p, buildFiles.text, ruleSet)  //here
                def projectToEvaluate = projectResolver.apply(p.path)
                projectToEvaluate.afterEvaluate { evaluatedProject ->
                    if (containsModelAwareRule) {
                        DependencyService.removeForProject(evaluatedProject)
                    }
                }
            }
        }
            return analyzer.rootResults
        }
    }