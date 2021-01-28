def call(String service_name, String type) {
  def version = getVersion(service_name)
  String artifact_path = ""
  String artifactory_repo = ""
  if (type == "docker") {
    artifactory_repo = "docker-dev-local"
    artifact_path = "${IMAGE_NAMESPACE}/${service_name}/${version}"
  } else if (type == "non-docker") {
    artifactory_repo = "maven-snapshots-local"
    def group = sh(script: "grep -Po '(?<=^group: )[^\$]*' ${service_name}.properties | tr '.' '/'", returnStdout: true).trim()
    artifact_path = "${group}/${service_name}/${version}/${service_name}-${version}.jar"
  } else { // bad type given
    error("bad type found for service ${service_name}")
  }
  return {
    echo "artifact_path -> ${artifact_path}"
    withEnv([
      "ARTIFACT_PATH=${artifact_path}",
      "ARTIFACTORY_REPO=${artifactory_repo}"
    ]) {
      dir("${service_name}/verification-rules") {
        try {
          git url: 'https://github.theocc.com/good-software-delivery-org/verification-rules',
            branch: "${VERIFICATION_BRANCH}",
            credentialsId: 'Jenkins_GitHub_PIPELINE',
            poll: false
          withCredentials([usernamePassword(credentialsId: 'PUBLISH_TO_ARTIFACTORY', passwordVariable: 'ARTIFACTORY_CREDENTIALS_PSW', usernameVariable: 'ARTIFACTORY_CREDENTIALS_USR')]) {
            sh 'make verify || true'
            sh "cp result.json result-${service_name}.json"
            archiveArtifacts artifacts: "result-${service_name}.json"
            sh "cp verification-report.txt verification-report-${service_name}.txt"
            archiveArtifacts artifacts: "verification-report-${service_name}.txt"
          }
        } finally {
          sh 'make clean || true'
        }
      }
    }
  }
}
