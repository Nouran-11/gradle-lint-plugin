package com.netflix.nebula.lint


import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class ConfigurationCacheCompatibilitySpec extends Specification {
    @Rule
    final TemporaryFolder temp = new TemporaryFolder()

    def "lint task is configuration cache compatible with standard rules"() {
        setup:
        // 1. Create a "safe" lint rule that is not model-aware
        def buildSrc = new File(temp.root, 'buildSrc')
        def ruleSourceFile = new File(buildSrc, 'src/main/groovy/com/example/SafeRule.groovy')
        ruleSourceFile.parentFile.mkdirs()
        ruleSourceFile.text = """
            package com.example
            import com.netflix.nebula.lint.rule.gradle.GradleLintRule
            import org.gradle.api.Project
            
            class SafeRule extends GradleLintRule {
                String description = "A simple, safe rule."
                @Override
                void Veto(Project project) {
                    // This rule does nothing, it's just here to be enabled
                }
            }
        """

        // 2. Register the safe rule via META-INF/lint-rules
        def rulePropertiesFile = new File(buildSrc, 'src/main/resources/META-INF/lint-rules/my-safe-rule.properties')
        rulePropertiesFile.parentFile.mkdirs()
        rulePropertiesFile.text = "implementation-class=com.example.SafeRule"

        // ===================================================================
        // THE FIX IS HERE
        // ===================================================================
        // 3. Add a build.gradle file for buildSrc to declare its dependencies
        def buildSrcBuildFile = new File(buildSrc, 'build.gradle')
        buildSrcBuildFile.text = """
            plugins {
                id 'groovy'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                // This line makes your plugin's classes (like GradleLintRule)
                // available inside buildSrc.
                implementation project(':')
            }
        """
        // ===================================================================

        // 4. Create the main build file that uses this rule
        def buildFile = new File(temp.root, 'build.gradle')
        buildFile.text = """
            plugins {
                id 'nebula.lint'
            }
            
            gradleLint {
                rules = ['my-safe-rule'] // Only enable our safe rule
            }
        """

        // 5. Create a settings file
        new File(temp.root, 'settings.gradle').text = "rootProject.name = 'cc-test'"

        when:
        // 6. Run the build twice
        def runner = GradleRunner.create()
                .withProjectDir(temp.root)
                .withPluginClasspath()
                .withArguments('lintGradle', '--configuration-cache', '--stacktrace')

        def firstResult = runner.build()
        def secondResult = runner.build()

        then:
        // 7. Assert that everything succeeded and the cache was used
        firstResult.task(":lintGradle").outcome == TaskOutcome.SUCCESS
        secondResult.task(":lintGradle").outcome == TaskOutcome.FROM_CACHE

        // 8. THE CRITICAL ASSERTION: Prove no configuration cache problems were found
        !firstResult.output.contains("Configuration cache problems found")
        !secondResult.output.contains("Configuration cache problems found")

        firstResult.output.contains("Configuration cache entry stored")
        secondResult.output.contains("Reusing configuration cache")
    }
}