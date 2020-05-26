package org.ods.orchestration

import org.ods.PipelineScript
import org.ods.orchestration.scheduler.LeVADocumentScheduler
import org.ods.services.JenkinsService
import org.ods.services.ServiceRegistry
import org.ods.orchestration.usecase.JUnitTestReportsUseCase
import org.ods.orchestration.usecase.JiraUseCase
import org.ods.util.IPipelineSteps
import org.ods.orchestration.util.MROPipelineUtil
import org.ods.util.PipelineSteps
import org.ods.orchestration.util.Context
import util.SpecHelper

import static util.FixtureHelper.createContext

class TestStageSpec extends SpecHelper {
    Context context
    TestStage testStage
    IPipelineSteps steps
    PipelineScript script
    MROPipelineUtil util
    JiraUseCase jira
    JUnitTestReportsUseCase junit
    JenkinsService jenkins
    LeVADocumentScheduler levaDocScheduler

    def phase = MROPipelineUtil.PipelinePhases.TEST

    def setup() {
        script = new PipelineScript()
        steps = Mock(PipelineSteps)
        levaDocScheduler = Mock(LeVADocumentScheduler)
        context = Spy(createContext())
        util = Mock(MROPipelineUtil)
        jira = Mock(JiraUseCase)
        junit = Mock(JUnitTestReportsUseCase)
        jenkins = Mock(JenkinsService)
        createService()
        testStage = Spy(new TestStage(script, context, context.repositories))
    }

    ServiceRegistry createService() {
        def registry = ServiceRegistry.instance

        registry.add(PipelineSteps, steps)
        registry.add(LeVADocumentScheduler, levaDocScheduler)
        registry.add(MROPipelineUtil, util)
        registry.add(JiraUseCase, jira)
        registry.add(JUnitTestReportsUseCase, junit)
        registry.add(JenkinsService, jenkins)

        return registry
    }

    def "succesful execution"() {
        given:
        junit.parseTestReportFiles(*_) >> [:]

        when:
        testStage.run()

        then:
        1 * levaDocScheduler.run(phase, MROPipelineUtil.PipelinePhaseLifecycleStage.POST_START)
        1 * util.prepareExecutePhaseForReposNamedJob(*_)
        1 * levaDocScheduler.run(phase, MROPipelineUtil.PipelinePhaseLifecycleStage.PRE_END, [:], _)
    }

    def "in TEST repo only one call per test types to report test results in Jira"() {
        given:
        steps.env >> [WORKSPACE: "", BUILD_ID: 1]
        jenkins.unstashFilesIntoPath(_, _, "JUnit XML Report") >> true
        junit.loadTestReportsFromPath(_) >> []
        junit.parseTestReportFiles(_) >> [:]

        when:
        testStage.run()

        then:
        1 * util.prepareExecutePhaseForReposNamedJob(MROPipelineUtil.PipelinePhases.TEST, context.repositories, _, _) >> { phase_, repos_, preExecuteRepo_, postExecuteRepo_ ->
            postExecuteRepo_.call(steps, [type: MROPipelineUtil.PipelineConfig.REPO_TYPE_ODS_TEST] as Map)
            return []
        }
        1 * jira.reportTestResultsForProject([Context.TestType.INSTALLATION], _)
        1 * jira.reportTestResultsForProject([Context.TestType.INTEGRATION], _)
        1 * jira.reportTestResultsForProject([Context.TestType.ACCEPTANCE], _)
    }

    def "get test results from file"() {
        given:
        steps.env >> [WORKSPACE : "", BUILD_ID : 1]
        jenkins.unstashFilesIntoPath(_, _, "JUnit XML Report") >> true
        junit.loadTestReportsFromPath(_) >> []
        junit.parseTestReportFiles(_) >> [:]

        when:
        testStage.getTestResults(steps, context.repositories.first(), "acceptance")

        then:
        1 * junit.loadTestReportsFromPath(_)
    }
}
