package org.ods.util

interface IPipelineSteps {

    void archiveArtifacts(String artifacts)

    def checkout(Map config)

    void dir(String path, Closure block)

    void echo(String message)

    def getCurrentBuild()

    Map getEnv()

    void junit(String path)

    void junit(Map config)

    def load(String path)

    def sh(def args)

    def bat(def args)

    void stage(String name, Closure block)

    void stash(String name)

    void stash(Map config)

    void unstash(String name)

    def fileExists(String file)

    def readFile(String file, String encoding)

    def readFile(Map args)

    def writeFile(String file, String text, String encoding)

    def writeFile(Map args)

    def readJSON(Map args)

    def writeJSON(Map args)

    def timeout(Map args, Closure block)

    def deleteDir()

    def sleep(int seconds)

    def withEnv(List<String> env, Closure block)

    def unstable(String message)

    def usernamePassword(Map credentialsData)

    def sshUserPrivateKey(Map credentialsData)

    def withCredentials(List credentialsList, Closure block)

    def unwrap()

    def zip(String zipFile, boolean archive, String dir, String glob)

    def unzip(String zipFile, String charset, String dir, String glob, boolean quiet, boolean read, boolean test)

    def findFiles(String excludes, String glob, Closure block)

    def isUnix()
}
