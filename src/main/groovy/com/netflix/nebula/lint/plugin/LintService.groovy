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
import org.gradle.api.UnknownDomainObjectException
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

import javax.inject.Inject
import java.util.stream.Collectors

class LintService {

    private final ObjectFactory objectFactory
    private final LintRuleRegistry registry

    @Inject
    LintService(ObjectFactory objectFactory) {
        this.objectFactory = objectFactory
        this.registry = new LintRuleRegistry()
    }

    static class ReportableAnalyzer extends AbstractSourceAnalyzer implements Serializable {
        private final Property<String> rootProjectPath

        ReportableAnalyzer(Project project, ObjectFactory objectFactory) {
            this.rootProjectPath = objectFactory.property(String.class)
            this.rootProjectPath.set(project.getProjectDir().getAbsolutePath())
        }

        Results analyze(Project analyzedProject, String source, RuleSet ruleSet) {
            DirectoryResults results = new DirectoryResults(analyzedProject.getProjectDir().getAbsolutePath())

            List<GradleViolation> violations = collectViolations(new SourceString(source), ruleSet) as List<GradleViolation>

            violations.stream()
                    .collect(Collectors.groupingBy(GradleViolation::getFile))
                    .forEach((file, fileViolations) -> {
                        results.addChild(new FileResults(file.getAbsolutePath(), fileViolations))
                        results.setNumberOfFilesInThisDirectory(results.getNumberOfFilesInThisDirectory() + 1)
                    })

            return results
        }

        @Override
        Results analyze(RuleSet ruleSet) {
            throw new UnsupportedOperationException("Use the two-argument form instead")
        }

        @Override
        List<String> getSourceDirectories() {
            return List.of()
        }
    }

    private RuleSet ruleSetForProject(Project project, boolean onlyCriticalRules) {
        if (!project.getBuildFile().exists()) {
            return new ListRuleSet(List.of())
        }

        GradleLintExtension extension
        try {
            extension = project.getExtensions().getByType(GradleLintExtension.class)
        } catch (UnknownDomainObjectException ignored) {
            extension = project.getRootProject().getExtensions().getByType(GradleLintExtension.class)
        }

        List<String> rules = project.hasProperty("gradleLint.rules") ?
                List.of(project.property("gradleLint.rules").toString().split(",")) :
                extension.getRules()

        List<Rule> includedRules = rules.stream()
                .distinct()
                .flatMap(rule -> registry.buildRules(rule, project, extension.getCriticalRules().contains(rule)).stream())
                .collect(Collectors.toList())

        if (onlyCriticalRules) {
            includedRules = includedRules.stream()
                    .filter(rule -> rule instanceof GradleLintRule && ((GradleLintRule) rule).isCritical())
                    .collect(Collectors.toList())
        }

        List<String> excludedRules = project.hasProperty("gradleLint.excludedRules") ?
                List.of(project.property("gradleLint.excludedRules").toString().split(",")) :
                extension.getExcludedRules()

        includedRules.removeIf(rule -> excludedRules.contains(rule.getName()))

        return RuleSetFactory.configureRuleSet(includedRules)
    }

     RuleSet ruleSet(Project project) {
        CompositeRuleSet ruleSet = new CompositeRuleSet()

        List<Project> allProjects = List.of(project, project.getSubprojects().toArray(new Project[0]))

        for (Project p : allProjects) {
            ruleSet.addRuleSet(ruleSetForProject(p, false))
        }

        return ruleSet
    }

     Results lint(Project project, boolean onlyCriticalRules) {
        ReportableAnalyzer analyzer = new ReportableAnalyzer(project, objectFactory)

        List<Project> allProjects = List.of(project, project.getSubprojects().toArray(new Project[0]))

        for (Project p : allProjects) {
            FileCollection files = project.files(p.getBuildFile())
            BuildFiles buildFiles = new BuildFiles(files.getAsFileTree().getFiles() as List<File>)
            RuleSet ruleSet = ruleSetForProject(p, onlyCriticalRules)

            if (!ruleSet.getRules().isEmpty()) {
                for (Rule rule : (ruleSet.getRules() as List<Rule>)) {
                    if (rule instanceof GradleLintRule) {
                        ((GradleLintRule) rule).setBuildFiles(buildFiles)
                    }
                }

                analyzer.analyze(p, buildFiles.getText(), ruleSet)
                DependencyService.removeForProject(p)
            }
        }

        return analyzer.analyze(ruleSet(project))
    }
}
