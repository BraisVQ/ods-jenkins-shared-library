import java.nio.file.Paths
import java.lang.reflect.*
import java.lang.ClassLoader
import java.util.List

import org.ods.orchestration.util.PipelineUtil
import org.ods.orchestration.util.MROPipelineUtil
import org.ods.orchestration.util.Project
import org.ods.orchestration.usecase.OpenIssuesException
import org.ods.orchestration.InitStage
import org.ods.orchestration.BuildStage
import org.ods.orchestration.DeployStage
import org.ods.orchestration.TestStage
import org.ods.orchestration.ReleaseStage
import org.ods.orchestration.FinalizeStage
import org.ods.services.OpenShiftService
import org.ods.services.ServiceRegistry
import org.ods.services.GitService
import org.ods.util.Logger
import org.ods.util.ILogger
import org.ods.util.IPipelineSteps
import org.ods.util.PipelineSteps
import org.ods.util.UnirestConfig

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;


def call (Map config) {
    echo "${env.JOB_NAME} / ${env.BUILD_NUMBER} "
    List<WorkflowJob> jobs = Jenkins.getActiveInstance().getAllItems(WorkflowJob.class);
    jobs.each { job -> 
        echo "Job: ${job} - ${job.getFullName()} - ${job.getBuilds().size()}"
        if (job.getFullName() == 'testbug-cd/testbug-cd-release-master') {
            job.getBuilds().each { build ->
                echo "---> Build: ${build}"
            }
        }
    }
}

@SuppressWarnings('AbcMetric')
def XXXcall(Map config) {
    UnirestConfig.init()
    def steps = new PipelineSteps(this)

    def debug = config.get('debug', false)
    ServiceRegistry.instance.add(Logger, new Logger(this, debug))
    ILogger logger = ServiceRegistry.instance.get(Logger)
  	logger.dumpCurrentStopwatchSize()
    def git = new GitService(steps, logger)

    def odsImageTag = config.odsImageTag
    if (!odsImageTag) {
        error "You must set 'odsImageTag' in the config map"
    }
    def versionedDevEnvsEnabled = config.get('versionedDevEnvs', false)
    def alwaysPullImage = !!config.get('alwaysPullImage', true)
    boolean startAgentEarly = config.get('startOrchestrationAgentOnInit', true)
    def startAgentStage = startAgentEarly ? MROPipelineUtil.PipelinePhases.INIT : null

    logger.debug ("Start agent stage: ${startAgentStage}")
    Project project = new Project(steps, logger)
    def repos = []

    logger.startClocked('orchestration-master-node')

  	try {
      node ('master') {
          logger.debugClocked('orchestration-master-node')
          // Clean workspace from previous runs
          [
              PipelineUtil.ARTIFACTS_BASE_DIR,
              PipelineUtil.SONARQUBE_BASE_DIR,
              PipelineUtil.XUNIT_DOCUMENTS_BASE_DIR,
              MROPipelineUtil.REPOS_BASE_DIR,
          ].each { name ->
              logger.debug("Cleaning workspace directory '${name}' from previous runs")
              Paths.get(env.WORKSPACE, name).toFile().deleteDir()
          }

          logger.startClocked('pipeline-git-releasemanager')
          def scmBranches = scm.branches
          def branch = scmBranches[0]?.name
          if (branch && !branch.startsWith('*/')) {
              scmBranches = [[name: "*/${branch}"]]
          }

          // checkout local branch
          git.checkout(
              scmBranches,
              [[$class: 'LocalBranch', localBranch: '**']],
              scm.userRemoteConfigs,
              scm.doGenerateSubmoduleConfigurations
              )
          logger.debugClocked('pipeline-git-releasemanager')

          def envs = Project.getBuildEnvironment(steps, debug, versionedDevEnvsEnabled)

          logger.startClocked('pod-template')
          withPodTemplate(odsImageTag, steps, alwaysPullImage) {
              logger.debugClocked('pod-template')
              withEnv (envs) {
                def result
                def cannotContinueAsHasOpenIssuesInClosingRelease = false
                try {
                    result = new InitStage(this, project, repos, startAgentStage).execute()
                } catch (OpenIssuesException ex) {
                    cannotContinueAsHasOpenIssuesInClosingRelease = true
                }
                if (cannotContinueAsHasOpenIssuesInClosingRelease) {
                    logger.warn('Cannot continue as it has open issues in the release.')
                    return
                }
                if (result) {
                    project = result.project
                    repos = result.repos
                    if (!startAgentStage) {
                        startAgentStage = result.startAgent
                    }
                } else {
                    logger.warn('Skip pipeline as no project/repos computed')
                    return
                }

                new BuildStage(this, project, repos, startAgentStage).execute()

                new DeployStage(this, project, repos, startAgentStage).execute()

                new TestStage(this, project, repos, startAgentStage).execute()

                new ReleaseStage(this, project, repos).execute()

                new FinalizeStage(this, project, repos).execute()
              }
          }
      }
    } finally {
      logger.debug('-- SHUTTING DOWN RM (.. incl classloader HACK!!!!!) --')
      logger.resetStopwatch()
      project.clear()
      ServiceRegistry.instance.clear()
      ServiceRegistry.removeInstance()
      UnirestConfig.shutdown()
      project = null
      git = null
      repos = null
      steps = null
      // HACK!!!!!
      GroovyClassLoader classloader = (GroovyClassLoader)this.class.getClassLoader()
      logger.debug("${classloader} - parent ${classloader.getParent()}")
      logger.debug("Currently loaded classpath ${classloader.getClassPath()}")
      logger.debug("Currently loaded classes ${classloader.getLoadedClasses()}")
      classloader.clearCache()
      classloader.close()
      logger.debug("After closing: loaded classes ${classloader.getLoadedClasses().size()}")
        try {
            logger.debug("current (CleanGroovyCl): ${classloader}")
            Field loaderF = ClassLoader.class.getDeclaredField("classes")
            loaderF.setAccessible(true);
            logger.debug("current size ${loaderF.get(classloader).size()}")
            loaderF.get(classloader).clear()
            logger.debug("current cleared, now kicking parent CL")

            Field loaderParentF = ClassLoader.class.getDeclaredField("parent")
            loaderParentF.setAccessible(true);

            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(loaderParentF, loaderParentF.getModifiers() & ~Modifier.FINAL);

            loaderParentF.set(classloader, null);
            logger.debug("Current CL classloader removed")
        } catch (Exception e) {
            logger.debug("e: ${e}")
        }
        try {
            logger.debug("current parent (timingClassloader): ${classloader.getParent()}")
            if (classloader.getParent() != null) {
                Field loaderFP = ClassLoader.class.getDeclaredField("classes")
                loaderFP.setAccessible(true);
                logger.debug("current parent size ${loaderFP.get(classloader.getParent()).size()}")
                loaderFP.get(classloader.getParent()).clear()
                logger.debug("current parent cleared")
            }
        } catch (Exception e) {
            logger.debug("eParrent: ${e}")
        }
    }
}

@SuppressWarnings('GStringExpressionWithinString')
private withPodTemplate(String odsImageTag, IPipelineSteps steps, boolean alwaysPullImage, Closure block) {
    ILogger logger = ServiceRegistry.instance.get(Logger)
    def dockerRegistry = steps.env.DOCKER_REGISTRY ?: 'docker-registry.default.svc:5000'
    def podLabel = "mro-jenkins-agent-${env.BUILD_NUMBER}"
    def odsNamespace = env.ODS_NAMESPACE ?: 'ods'
    if (!OpenShiftService.envExists(steps, odsNamespace)) {
        logger.warn("Could not find ods namespace '${odsNamespace}' - defaulting to legacy namespace: 'cd'!\r" +
            "Please configure 'env.ODS_NAMESPACE' to point to the ODS Openshift namespace")
        odsNamespace = 'cd'
    }
    podTemplate(
        label: podLabel,
        cloud: 'openshift',
        containers: [
            containerTemplate(
                name: 'jnlp',
                image: "${dockerRegistry}/${odsNamespace}/jenkins-agent-base:${odsImageTag}",
                workingDir: '/tmp',
                resourceRequestMemory: '512Mi',
                resourceLimitMemory: '1Gi',
                resourceRequestCpu: '200m',
                resourceLimitCpu: '1',
                alwaysPullImage: "${alwaysPullImage}",
                args: '${computer.jnlpmac} ${computer.name}',
                envVars: []
            )
        ],
        volumes: [],
        serviceAccount: 'jenkins',
        idleMinutes: 10,
    ) {
        logger.startClocked('ods-mro-pipeline')
        try {
            block()
        } finally {
            logger.infoClocked('ods-mro-pipeline', '**** ENDED orchestration pipeline ****')
        }
    }
}

return this

