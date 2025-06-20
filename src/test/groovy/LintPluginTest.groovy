import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import java.nio.file.Files
import java.nio.file.Paths

class LintPluginTest extends Specification {

    def "runs lint task on a test build"() {
        given:
        def testProjectDir = Files.createTempDirectory("test-project").toFile()
        testProjectDir.deleteOnExit()

        new File(testProjectDir, "settings.gradle") << ""

        new File(testProjectDir, "build.gradle") << """
            plugins {
                id 'com.example.lint'
            }

            gradleLint.rules = ['all-dependency-configuration']
        """

        when:
        BuildResult result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withPluginClasspath()
                .withArguments("lint", "--stacktrace")
                .forwardOutput()
                .build()

        then:
        result.output.contains("This project contains lint violations.") ||
                result.output.contains("problem")
    }
}