void sbtAction(String task) {
    sh '''
        echo "
        realm=Sonatype Nexus Repository Manager
        host=${NEXUS}
        user=${NEXUS_CREDENTIALS_USR}
        password=${NEXUS_CREDENTIALS_PSW}" > ~/.sbt/.credentials
        '''
    sh "sbt -Dsbt.log.noformat=true ${task}"
} 

void updateGithubCommit(String status) {
  def token = '${GITHUB_PAT_PSW}'
  sh """
    curl --silent --show-error \
      "https://api.github.com/repos/pagopa/${REPO_NAME}/statuses/${GIT_COMMIT}" \
      --header "Content-Type: application/json" \
      --header "Authorization: token ${token}" \
      --request POST \
      --data "{\\"state\\": \\"${status}\\",\\"context\\": \\"Jenkins Continuous Integration\\", \\"description\\": \\"Build ${BUILD_DISPLAY_NAME}\\"}" &> /dev/null
  """
}

void ecrLogin() {
  withCredentials([usernamePassword(credentialsId: 'ecr-rw', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
    sh '''
    aws ecr get-login-password --region eu-central-1 | docker login --username AWS --password-stdin $DOCKER_REPO
    '''
  }
}

void githubRegistryLogin() {
  sh '''echo $GITHUB_PAT_PSW | docker login $DOCKER_REPO  -u $GITHUB_PAT_USR --password-stdin'''
}

pipeline {
  agent { label 'sbt-template' }
  environment {
    GITHUB_PAT = credentials('github-pat')
    NEXUS_CREDENTIALS = credentials('pdnd-nexus')
    // GIT_URL has the shape git@github.com:pagopa/REPO_NAME.git so we extract from it
    REPO_NAME="""${sh(returnStdout:true, script: 'echo ${GIT_URL} | sed "s_https://github.com/pagopa/\\(.*\\)\\.git_\\1_g"')}""".trim()
  }
  stages {
    stage('Test') {
      steps {
        container('sbt-container') {
          updateGithubCommit 'pending'
          sbtAction 'test'
        }
      }
    }
    stage('Publish Docker Image on ECR') {
      when {
        anyOf {
          branch pattern: "[0-9]+\\.[0-9]+\\.x", comparator: "REGEXP"
          buildingTag()
        }
      }
      steps {
        container('sbt-container') {
          script {
            ecrLogin()
            sbtAction 'docker:publish "project client" publish'
          }
        }
      }
    }
    stage('Publish Docker Image on GitHub') {
      when {
        buildingTag()
      }
      environment {
        DOCKER_REPO = "ghcr.io/pagopa"
      }
      steps {
        container('sbt-container') {
          script {
            githubRegistryLogin()
            sbtAction 'docker:publish'
          }
        }
      }
    }
  }
  post {
    success { 
      updateGithubCommit 'success'
    }
    failure { 
      updateGithubCommit 'failure'
    }
    aborted { 
      updateGithubCommit 'error'
    }
  }
}
