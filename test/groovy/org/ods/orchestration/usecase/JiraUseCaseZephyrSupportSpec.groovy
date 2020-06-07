package org.ods.orchestration.usecase


import org.ods.orchestration.service.*
import org.ods.orchestration.util.*
import org.ods.services.ServiceRegistry
import org.ods.util.IPipelineSteps

import java.nio.file.Files
import java.nio.file.Paths

import static util.FixtureHelper.*

import util.*

class JiraUseCaseZephyrSupportSpec extends SpecHelper {

    JiraService jira
    Project project
    IPipelineSteps steps
    JiraUseCase usecase
    MROPipelineUtil util
    JiraZephyrService zephyr
    JiraUseCaseZephyrSupport support

    def setup() {
        project = Spy(createProject())
        def steps = Spy(FakePipelineSteps)
        def tmpDir = getClass().getSimpleName()
        def tmpPath = Paths.get(steps.env.WORKSPACE, tmpDir)
        Files.createDirectories(tmpPath)
        steps.env.WORKSPACE = tmpPath.toString()
        this.steps = steps
        ServiceRegistry.instance.add(IPipelineSteps, steps)
        util = Mock(MROPipelineUtil)
        jira = Mock(JiraService)
        usecase = Spy(new JiraUseCase(project, steps, util, jira))

        zephyr = Mock(JiraZephyrService)
        support = Spy(new JiraUseCaseZephyrSupport(project, steps, usecase, zephyr, util))
        usecase.setSupport(support)
    }

    def "apply xUnit test results as test execution statii"() {
        given:
        def testIssues = createJiraTestIssues()
        def testResults = createTestResults()

        when:
        support.applyXunitTestResultsAsTestExecutionStatii(testIssues, testResults)

        then:
        1 * zephyr.getTestCycles(project.id, "11100")
        1 * zephyr.createTestCycle(project.id, "11100", project.buildParams.targetEnvironmentToken + ": Build " + steps.env.BUILD_ID, steps.env.BUILD_URL, project.buildParams.targetEnvironment) >> [id: "111"]

        then:
        1 * zephyr.createTestExecutionForIssue("1", project.id, "111") >> ["11": []]
        1 * zephyr.updateTestExecutionForIssuePass("11")
        0 * zephyr./^updateTestExecutionForIssue.*/("11")

        then:
        1 * zephyr.createTestExecutionForIssue("2", project.id, "111") >> ["12": []]
        1 * zephyr.updateTestExecutionForIssueFail("12")
        0 * zephyr./^updateTestExecutionForIssue.*/("12")

        then:
        1 * zephyr.createTestExecutionForIssue("3", project.id, "111") >> ["13": []]
        1 * zephyr.updateTestExecutionForIssueFail("13")
        0 * zephyr./^updateTestExecutionForIssue.*/("13")

        then:
        1 * zephyr.createTestExecutionForIssue("4", project.id, "111") >> ["14": []]
        1 * zephyr.updateTestExecutionForIssueBlocked("14")
        0 * zephyr./^updateTestExecutionForIssue.*/("14")

        then:
        // Leave test execution at initial status UNEXECUTED otherwise
        1 * zephyr.createTestExecutionForIssue("5", project.id, "111") >> ["15": []]
        0 * zephyr./^updateTestExecutionForIssue.*/("15")
    }

    def "apply xUnit test results as test execution statii without test issues"() {
        given:
        def testIssues = []
        def testResults = [:]

        when:
        support.applyXunitTestResultsAsTestExecutionStatii(testIssues, testResults)

        then:
        0 * zephyr.createTestCycle(project.id, "-1", null, steps.env.BUILD_URL, project.buildParams.targetEnvironment)
    }

    def "apply xUnit test results"() {
        given:
        def testIssues = createJiraTestIssues()
        def testResults = createTestResults()

        when:
        support.applyXunitTestResults(testIssues, testResults)

        then:
        1 * usecase.applyXunitTestResultsAsTestIssueLabels(testIssues, testResults)
        _ * zephyr.createTestCycle(*_) >> [id: "1"]
        _ * zephyr.createTestExecutionForIssue(*_) >> ["1": []]
        1 * support.applyXunitTestResultsAsTestExecutionStatii(testIssues, testResults)
    }
}
