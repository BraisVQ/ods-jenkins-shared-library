package org.ods

class OdsContext implements Context {

  def script
  Logger logger
  Map config

  boolean environmentCreated
  boolean branchUpdated

  OdsContext(script, config, logger) {
    this.script = script
    this.config = config
    this.logger = logger
  }

  def assemble() {
    logger.verbose "Validating input ..."
    if (!config.projectId) {
      logger.error "Param 'projectId' is required"
    }
    if (!config.componentId) {
      logger.error "Param 'componentId' is required"
    }
    if (!config.image) {
      logger.error "Param 'image' is required"
    }

    logger.verbose "Collecting environment variables ..."
    config.jobName = script.env.JOB_NAME
    config.buildNumber = script.env.BUILD_NUMBER
    config.nexusHost = script.env.NEXUS_HOST
    config.nexusUsername = script.env.NEXUS_USERNAME
    config.nexusPassword = script.env.NEXUS_PASSWORD
    config.branchName = script.env.BRANCH_NAME // may be empty
    config.openshiftHost = script.env.OPENSHIFT_API_URL
    config.bitbucketHost = script.env.BITBUCKET_HOST

    logger.verbose "Validating environment variables ..."
    if (!config.jobName) {
      logger.error 'JOB_NAME is required, but not set (usually provided by Jenkins)'
    }
    if (!config.buildNumber) {
      logger.error 'BUILD_NUMBER is required, but not set (usually provided by Jenkins)'
    }
    if (!config.nexusHost) {
      logger.error 'NEXUS_HOST is required, but not set'
    }
    if (!config.nexusUsername) {
      logger.error 'NEXUS_USERNAME is required, but not set'
    }
    if (!config.nexusPassword) {
      logger.error 'NEXUS_PASSWORD is required, but not set'
    }
    if (!config.openshiftHost) {
      logger.error 'OPENSHIFT_API_URL is required, but not set'
    }
    if (!config.bitbucketHost) {
      logger.error 'BITBUCKET_HOST is required, but not set'
    }

    logger.verbose "Deriving configuration from input ..."
    config.openshiftProjectId = "${config.projectId}-cd"
    config.credentialsId = config.openshiftProjectId + '-cd-user-with-password'

    logger.verbose "Setting defaults ..."
    config.autoCreateEnvironment = config.autoCreateEnvironment ?: false
    config.updateBranch = config.updateBranch ?: false
    if (!config.containsKey('notifyNotGreen')) {
      config.notifyNotGreen = true
    }
    if (!config.containsKey('environmentLimit')) {
      config.environmentLimit = 5
    }
    if (!config.containsKey('openshiftBuildTimeout')) {
      config.openshiftBuildTimeout = 15
    }
    if (!config.groupId) {
      config.groupId = "org.opendevstack.${config.projectId}"
    }
    if (!config.testProjectBranch) {
      config.testProjectBranch = "master"
    }
    if (!config.podVolumes) {
      config.podVolumes = []
    }
    if (!config.containsKey('podAlwaysPullImage')) {
      config.podAlwaysPullImage = true
    }

    logger.verbose "Validating configuration ..."
    if (config.autoCreateEnvironment && !config.branchName) {
      logger.error 'Aborting! autoCreateEnvironment=true and branchName=null. ' +
                   'You will need to configure a "Bitbucket Team/Project" item.'
    }

    logger.verbose "Retrieving Git information ..."
    config.gitUrl = retrieveGitUrl()

    // BRANCH_NAME is only given for "Bitbucket Team/Project" items. If
    // autoCreateEnvironment is disabled, we need to determine the Git branch to
    // build from the last push. In that case, we also skip the pipeline if
    // it is triggered but not responsible.
    if (config.autoCreateEnvironment) {
      // For "Bitbucket Team/Project" items, the initial checkout is already
      // of the branch we want to build for, so just get HEAD now.
      config.gitCommit = retrieveGitCommit()
      if (config.branchName.startsWith("PR-")){
        config.gitBranch = getBranchOfPullRequest()
        config.jobName = config.branchName
      } else {
        config.gitBranch = config.branchName
        config.jobName = extractBranchCode(config.branchName)
      }
    } else {
      config.gitBranch = determineBranchToBuild(config.credentialsId, config.gitUrl)
      checkoutBranch(config.gitBranch)
      config.gitCommit = retrieveGitCommit()
      if (!isResponsible()) {
        def previousBuild = script.currentBuild.getPreviousBuild()
        if (previousBuild) {
          logger.verbose "Setting status of previous build"
          script.currentBuild.result = previousBuild.getResult()
        } else {
          logger.verbose "No previous build, setting status ABORTED"
          script.currentBuild.result = 'ABORTED'
        }
        script.currentBuild.displayName = "${config.buildNumber}/skipping-not-responsible"
        logger.error "This job: ${config.jobName} is not responsible for building: ${config.gitBranch}"
      }
    }

    config.shortBranchName = extractShortBranchName(config.gitBranch)
    config.tagversion = "${config.buildNumber}-${config.gitCommit.take(8)}"

    logger.verbose "Setting environment ..."
    config.environment = determineEnvironment(config.gitBranch, config.projectId, config.autoCreateEnvironment)
    if (config.environment) {
      config.targetProject = "${config.projectId}-${config.environment}"
    }

    logger.verbose "Assembled configuration: ${config}"
  }

  boolean getVerbose() {
      config.verbose
  }

  boolean getUpdateBranch() {
      config.updateBranch
  }

  String getGitBranch() {
      config.gitBranch
  }

  String getCredentialsId() {
      config.credentialsId
  }

  String getImage() {
    config.image
  }

  String getPodLabel() {
    "pod-${simpleHash(config.image)}"
  }

  Object getPodVolumes() {
    config.podVolumes
  }

  boolean getPodAlwaysPullImage() {
    config.podAlwaysPullImage
  }

  boolean getBranchUpdated() {
      branchUpdated
  }

  String getGitUrl() {
      config.gitUrl
  }

  String getShortBranchName() {
      config.shortBranchName
  }

  String getTagversion() {
      config.tagversion
  }

  boolean getNotifyNotGreen() {
      config.notifyNotGreen
  }

  String getNexusHost() {
      config.nexusHost
  }

  String getNexusUsername() {
      config.nexusUsername
  }

  String getNexusPassword() {
      config.nexusPassword
  }

  String getEnvironment() {
      config.environment
  }

  String getTestProjectBranch() {
      config.testProjectBranch
  }

  String getGroupId() {
      config.groupId
  }

  String getProjectId() {
      config.projectId
  }

  String getComponentId() {
      config.componentId
  }

  String getGitCommit() {
      config.gitCommit
  }

  String getTargetProject() {
      config.targetProject
  }

  int getEnvironmentLimit() {
      config.environmentLimit
  }

  boolean getAdmins() {
      config.admins
  }

  String getOpenshiftHost() {
      config.openshiftHost
  }

  String getBitbucketHost() {
      config.bitbucketHost
  }

  boolean getEnvironmentCreated() {
      this.environmentCreated
  }

  int getOpenshiftBuildTimeout() {
      config.openshiftBuildTimeout
  }

  def setEnvironmentCreated(boolean created) {
      this.environmentCreated = created
  }

  boolean shouldUpdateBranch() {
    config.updateBranch && config.testProjectBranch != config.gitBranch
  }

  def setBranchUpdated(boolean branchUpdated) {
      this.branchUpdated = branchUpdated
  }

  // We cannot use java.security.MessageDigest.getInstance("SHA-256")
  // nor hashCode() due to sandbox restrictions ...
  private int simpleHash(String str) {
    int hash = 7;
    for (int i = 0; i < str.length(); i++) {
      hash = hash*31 + str.charAt(i);
    }
    return hash
  }

  private String retrieveGitUrl() {
    script.sh(
      returnStdout: true, script: 'git config --get remote.origin.url'
    ).trim().replace('https://bitbucket', 'https://cd_user@bitbucket')
  }

  private String retrieveGitCommit() {
      script.sh(
        returnStdout: true, script: 'git rev-parse HEAD'
      ).trim()
  }

  // If BRANCH_NAME is not given, we need to check whether the pipeline is
  // responsible for building this commit at all.
  private boolean isResponsible() {
    if (config.jobName.endsWith("-test")) {
      return config.testProjectBranch.equals(config.gitBranch)
    }

    if (config.jobName.endsWith("-dev")) {
      return !config.testProjectBranch.equals(config.gitBranch)
    }

    return true
  }

  // Given a branch like "feature/HUGO-4-brown-bag-lunch", it extracts
  // "HUGO-4-brown-bag-lunch" from it.
  private String extractShortBranchName(String branch) {
    if (config.testProjectBranch.equals(branch)) {
      branch
    } else if (branch.startsWith("feature/")) {
      branch.drop("feature/".length())
    } else if (branch.startsWith("bugfix/")) {
      branch.drop("bugfix/".length())
    } else if (branch.startsWith("hotfix/")) {
      branch.drop("hotfix/".length())
    } else if (branch.startsWith("release/")) {
      branch.drop("release/".length())
    } else {
      branch
    }
  }

  // Given a branch like "feature/HUGO-4-brown-bag-lunch", it extracts
  // "HUGO-4" from it.
  private String extractBranchCode(String branch) {
      if (config.testProjectBranch.equals(branch)) {
          branch
      } else if (branch.startsWith("feature/")) {
          def list = branch.drop("feature/".length()).tokenize("-")
          "${list[0]}-${list[1]}"
      } else if (branch.startsWith("bugfix/")) {
          def list = branch.drop("bugfix/".length()).tokenize("-")
          "${list[0]}-${list[1]}"
      } else if (branch.startsWith("hotfix/")) {
          def list = branch.drop("hotfix/".length()).tokenize("-")
          "${list[0]}-${list[1]}"
      } else if (branch.startsWith("release/")) {
          def list = branch.drop("release/".length()).tokenize("-")
          "${list[0]}-${list[1]}"
      } else {
          branch
      }
  }

  // For pull requests, the branch name environment variable is not the actual
  // git branch, which we need.
  private String retrieveBranchOfPullRequest(credentialsId, gitUrl, gitCommit) {
    def branch
    script.withCredentials([script.usernameColonPassword(credentialsId: credentialsId, variable: 'USERPASS')]) {
      branch = script.sh(
        returnStdout: true,
        script: "git ls-remote -q --heads ${gitUrl} | grep '${gitCommit}' | awk '{print \$2}'"
      ).trim().drop("refs/heads/".length())
    }
    return branch
  }

  // If BRANCH_NAME is not given, we need to figure out the branch from the last
  // commit to the repository.
  private String determineBranchToBuild(credentialsId, gitUrl) {
    script.withCredentials([script.usernameColonPassword(credentialsId: credentialsId, variable: 'USERPASS')]) {
      def url = constructCredentialBitbucketURL(gitUrl, script.USERPASS)
      script.withEnv(["BITBUCKET_URL=${url}"]) {
        return script.sh(returnStdout: true, script: '''
          git config user.name "Jenkins CD User"
          git config user.email "cd_user@opendevstack.org"
          git config credential.helper store
          echo ${BITBUCKET_URL} > ~/.git-credentials
          git fetch
          git for-each-ref --sort=-committerdate refs/remotes/origin | cut -c69- | head -1
        ''').trim()
      }
    }
  }

  private String constructCredentialBitbucketURL(String url, String userPass) {
      return url.replace("cd_user", userPass)
  }

  private String buildGitUrl(credentialsId) {
    def token
    script.withCredentials([script.usernameColonPassword(credentialsId: credentialsId, variable: 'USERPASS')]) {
      token = 'https://' + script.USERPASS + '@bitbucket'
    }
    return script.sh(
      returnStdout: true, script: 'git config --get remote.origin.url'
    ).trim().replace('https://bitbucket', token)
  }

  private String determineEnvironment(String gitBranch, String projectId, boolean autoCreateEnvironment) {
    if (config.testProjectBranch.equals(gitBranch)) {
      return "test"
    }

    def tokens = extractBranchCode(gitBranch).tokenize("-")
    def pId = tokens[0]
    if (!pId || !pId.toLowerCase().equals(projectId.toLowerCase())) {
      logger.echo "No project ID found in branch name => deploying to dev environment"
      return "dev"
    }

    def code = tokens[1]
    if (!code) {
      logger.echo "No branch code extractable => deploying to dev environment"
      return "dev"
    }

    def env = code.toLowerCase() + "-dev"
    if (autoCreateEnvironment || environmentExists("${projectId}-${env}")) {
      return env
    }

    return "dev"
  }

  private boolean environmentExists(String name) {
    def statusCode = sh(
      script:"oc project ${name} &> /dev/null",
      returnStatus: true
    )
    return statusCode == 0
  }

  private String checkoutBranch(String branchName) {
    script.git url: config.gitUrl, branch: branchName, credentialsId: config.credentialsId
  }
}
