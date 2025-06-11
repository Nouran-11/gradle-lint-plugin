package com.netflix.nebula.lint.plugin

import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codenarc.source.SourceCode
import org.codenarc.source.SourceString
import org.gradle.api.Project

class SourceCollector {

    /**
     * It scans given build file for possible `apply from: 'another.gradle'` and recursively
     * collect all build files which are present.
     */

       //just to pass SourceCollectorTest
    static List<File> getAllFiles(File buildFile, Project project) {
        def rootProjectExtension = project.rootProject.extensions.findByType(GradleLintExtension.class)
        if (rootProjectExtension == null) {
            return []
        } else {
            def projectInfo = ProjectInfo.from(project, rootProjectExtension)
            return getAllFiles(buildFile, projectInfo)
        }
    }

    static List<File> getAllFiles(File buildFile, ProjectInfo projectInfo) {
        if (buildFile.exists()) {
            List<File> result = new ArrayList<>()
            result.add(buildFile)
            SourceCode sourceCode = new SourceString(buildFile.text)
            ModuleNode ast = sourceCode.getAst()
            if (ast != null && ast.getClasses() != null) {
                for (ClassNode classNode : ast.getClasses()) {
                    AppliedFilesAstVisitor visitor = new AppliedFilesAstVisitor(projectInfo)
                    visitor.visitClass(classNode)
                    result.addAll(visitor.appliedFiles)
                }
            }
            return result
        } else {
            return Collections.emptyList()
        }
    }
}
