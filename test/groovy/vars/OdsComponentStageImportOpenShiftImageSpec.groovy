package vars

import org.ods.component.Context
import org.ods.component.IContext
import org.ods.services.OpenShiftService
import org.ods.services.JenkinsService
import org.ods.services.ServiceRegistry
import org.ods.util.Logger
import vars.test_helper.PipelineSpockTestBase
import util.PipelineSteps
import spock.lang.*

class OdsComponentStageImportOpenShiftImageSpec extends PipelineSpockTestBase {

  private Logger logger = new Logger (new PipelineSteps(), true)

  @Shared
  def config = [
      gitUrl: 'https://example.com/scm/foo/bar.git',
      gitCommit: 'cd3e9082d7466942e1de86902bb9e663751dae8e',
      gitCommitMessage: """Foo\n\nSome "explanation".""",
      gitCommitAuthor: "John O'Hare",
      gitCommitTime: '2020-03-23 12:27:08 +0100',
      gitBranch: 'master',
      buildUrl: 'https://jenkins.example.com/job/foo-cd/job/foo-cd-bar-master/11/console',
      buildTime: '2020-03-23 12:27:08 +0100',
      odsSharedLibVersion: '2.x',
      projectId: 'foo',
      componentId: 'bar'
  ]

  def "run successfully"() {
    given:
    def c = config + [environment: 'test', targetProject: 'foo-test']
    IContext context = new Context(null, c, logger)
    OpenShiftService openShiftService = Mock(OpenShiftService.class)
    openShiftService.importImageTagFromProject(*_) >> {}
    ServiceRegistry.instance.add(OpenShiftService, openShiftService)

    when:
    def script = loadScript('vars/odsComponentStageImportOpenShiftImage.groovy')
//    helper.registerAllowedMethod('echo', [ String ]) {String args -> }
    def buildInfo = script.call(context, [sourceProject: 'foo-dev'])

    then:
    printCallStack()
    assertCallStackContains('''Imported image 'foo-dev/bar:cd3e9082' into 'foo-test/bar:cd3e9082'.''')
    assertJobStatusSuccess()
    //1 * openShiftService.importImageTagFromProject(*_)
  }

  def "skip when no environment given"() {
    given:
    def config = [environment: null, gitCommit: 'cd3e9082d7466942e1de86902bb9e663751dae8e']
    def context = new Context(null, config, logger)

    when:
    def script = loadScript('vars/odsComponentStageImportOpenShiftImage.groovy')
    script.call(context)

    then:
    printCallStack()
    assertCallStackContains("WARN: Skipping image import because of empty (target) environment ...")
    assertJobStatusSuccess()
  }

}
