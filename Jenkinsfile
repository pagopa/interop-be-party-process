//common helper for invoking SBT tasks
void sbtAction(String task) {
  echo "Executing ${task} on SBT"
  sh '''
      echo "realm=Sonatype Nexus Repository Manager\nhost=${NEXUS}\nuser=${NEXUS_CREDENTIALS_USR}\npassword=${NEXUS_CREDENTIALS_PSW}" > ~/.sbt/.credentials
     '''
  //using both interpolation and string concatenation to avoid Jenkins security warnings
  sh 'sbt -Dsbt.log.noformat=true -Djavax.net.ssl.trustStore=./PDNDTrustStore -Djavax.net.ssl.trustStorePassword=${PDND_TRUST_STORE_PSW} generateCode "project root" ' + "${task}"
}

pipeline {

  agent none

  stages {
    stage('Initializing build') {
      agent { label 'sbt-template' }
      environment {
        PDND_TRUST_STORE_PSW = credentials('pdnd-interop-trust-psw')
      }
      steps {
        withCredentials([file(credentialsId: 'pdnd-interop-trust-cert', variable: 'pdnd_certificate')]) {
          sh '''
             cat \$pdnd_certificate > gateway.interop.pdnd.dev.cer
             keytool -import -file gateway.interop.pdnd.dev.cer -alias pdnd-interop-gateway -keystore PDNDTrustStore -storepass ${PDND_TRUST_STORE_PSW} -noprompt
             cp $JAVA_HOME/jre/lib/security/cacerts main_certs
             keytool -importkeystore -srckeystore main_certs -destkeystore PDNDTrustStore -srcstorepass ${PDND_TRUST_STORE_PSW} -deststorepass ${PDND_TRUST_STORE_PSW}
           '''
          stash includes: "PDNDTrustStore", name: "pdnd_trust_store"
        }
      }
    }

    stage('Test and Publish on GitHub') {
      agent { label 'sbt-template' }
      environment {
        NEXUS = "${env.NEXUS}"
        DOCKER_REPO = 'ghcr.io/pagopa'
        MAVEN_REPO = "${env.MAVEN_REPO}"
        NEXUS_CREDENTIALS = credentials('pdnd-nexus')
        GITHUB_PAT = credentials('github-pat')
        PDND_TRUST_STORE_PSW = credentials('pdnd-interop-trust-psw')
      }
      steps {
        container('sbt-container') {
          unstash "pdnd_trust_store"
          script {
            sh '''echo $GITHUB_PAT_PSW | docker login $DOCKER_REPO  -u $GITHUB_PAT_USR --password-stdin'''
            sbtAction 'docker:publish'
          }
        }
      }
    }

    stage('Publish on ECR') {
      agent { label 'sbt-template' }
      environment {
        NEXUS = "${env.NEXUS}"
        NEXUS_CREDENTIALS = credentials('pdnd-nexus')
        DOCKER_REPO = "${env.DOCKER_REPO}"
        MAVEN_REPO = "${env.MAVEN_REPO}"
        ECR_RW = credentials('ecr-rw')
        PDND_TRUST_STORE_PSW = credentials('pdnd-interop-trust-psw')
      }
      steps {
        container('sbt-container') {
          unstash "pdnd_trust_store"
          script {
            withCredentials([usernamePassword(credentialsId: 'ecr-rw', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
              sh '''
              aws ecr get-login-password --region eu-central-1 | docker login --username AWS --password-stdin $DOCKER_REPO
              '''
            }
            sbtAction 'docker:publish'
          }
        }
      }
    }

    stage('Apply Kubernetes files') {
      agent { label 'sbt-template' }
      environment {
        DOCKER_REPO = 'ghcr.io/pagopa'
        AWS = credentials('jenkins-aws')
        STORAGE_USR="${AWS_USR}"
        STORAGE_PSW="${AWS_PSW}"
        SMTP = credentials('smtp')
        USER_REGISTRY_API_KEY = credentials('userRegistryApiKey')
        MAIN_AUDIENCE = "${env.MAIN_AUDIENCE}"
        SIGNATURE_VALIDATION_ENABLED = false
        REPLICAS_NR = 1
      }
      steps {
        container('sbt-container') {
          withKubeConfig([credentialsId: 'kube-config']) {
            sh '''
              cd kubernetes
              chmod u+x undeploy.sh
              chmod u+x deploy.sh
              ./undeploy.sh
              ./deploy.sh
            '''
          }
        }
      }
    }
  }
}
