// vim: set filetype=groovy:

def jdkVersion = 'jdk-8-latest'
def mavenVersion = 'maven-3.3-latest'
def mavenSettingsConfig = 'camunda-maven-settings'

def joinJmhResults = '''\
#!/bin/bash -x
cat **/*/jmh-result.json | jq -s add > target/jmh-result.json
'''

pipeline {
    agent { node { label 'ubuntu-large' } }

    options {
        buildDiscarder(logRotator(daysToKeepStr:'14', numToKeepStr:'50'))
            timestamps()
            timeout(time: 30, unit: 'MINUTES')
    }

    stages {
        stage('Tests') {
            withMaven(jdk: jdkVersion, maven: mavenVersion, mavenSettingsConfig: mavenSettingsConfig) {
                steps {
                    sh 'mvn clean generate-sources license:check source:jar javadoc:jar deploy -B -P jmh'
                }
            }
        }
    }

    post {
        always {
            sh joinJmhResults
            jmhReport 'target/jmh-result.json'
            junit testResults: '**/target/*-reports/**/*.xml', allowEmptyResults: true
        }
        changed {
            sendBuildStatusNotificationToDevelopers(currentBuild.result)
        }
    }
}

void sendBuildStatusNotificationToDevelopers(String buildStatus = 'SUCCESS') {
    def buildResult = buildStatus ?: 'SUCCESS'
    def subject = "${buildResult}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]'"
    def details = """<p>${buildResult}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]':</p>
    <p>Check console output at &QUOT;<a href='${env.BUILD_URL}'>${env.JOB_NAME} [${env.BUILD_NUMBER}]</a>&QUOT;</p>"""

    emailext (
        subject: subject,
        body: details,
        recipientProviders: [[$class: 'DevelopersRecipientProvider'], [$class: 'RequesterRecipientProvider']]
    )
}
