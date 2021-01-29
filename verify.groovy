def call(String a_service_name){

  String service_type = (find_DocApps(a_service_name) == 0) ? "non-docker" : "docker"
  withSonarQubeEnv('SonarQubeEE') {
    echo "Create verification step-> service: ${a_service_name}, type: ${service_type}"
    createVerificationStep(a_service_name, service_type)()
  }
}
