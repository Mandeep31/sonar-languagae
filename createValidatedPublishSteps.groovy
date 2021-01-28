def call(String a_service_name) {
  Boolean is_docker_app = (find_DocApps(a_service_name) != 0)

  return {
    stage("${a_service_name} Validated Publish") {
      stage("Veracode Scan ${a_service_name}") {
        if (!env.DO_VERACODE_SCAN.toBoolean()) {
          catchError(buildResult: 'SUCCESS', stageResult: 'NOT_BUILT') {
            error("Skipping stage")
          }
          return
        }

        Boolean retry_tracker = false
        retry(10) {
          // wait 15 minutes between each of 10 retries
          if (retry_tracker) {
            sleep time: 900, unit: 'SECONDS'
          } else {
            retry_tracker = true
          }
          if (env.MODE_3M in ['enabled', 'required']) {
            try {
              println("Make target-> veracode")
              sh 'make veracode'
            } catch (e) {
              println("Could not execute: make veracode")
              if (env.MODE_3M == 'required') {
                throw e
              }
              println("Fall-back to non-3M function")
              veracodes(a_service_name)
            }
          } else {
            veracodes(a_service_name)
          }
        }
      }

      stage("Publish ${a_service_name}") {
        if (!env.DO_PUBLISH.toBoolean()) {
          catchError(buildResult: 'SUCCESS', stageResult: 'NOT_BUILT') {
            error("Skipping stage")
          }
          return
        }

        if (env.MODE_3M in ['enabled', 'required']) {
          try {
            println("Make target-> publish")
            sh 'make publish'
          } catch (e) {
            println("Could not execute: make publish")
            if (env.MODE_3M == 'required') {
              throw e
            }
            println("Fall-back to non-3M function")
            retry(3) {
              if (is_docker_app) {
                helmPackagePublish(a_service_name)
                dockerPublish(a_service_name)
              } else {
                gradlePublish(a_service_name)
              }
            }
          }
        } else {
          retry(3) {
            if (is_docker_app) {
              helmPackagePublish(a_service_name)
              dockerPublish(a_service_name)
            } else {
              gradlePublish(a_service_name)
            }
          }
        }
      }
      stage("Verify Artifact ${a_service_name}") {
        if (!env.DO_VERIFY.toBoolean()) {
          catchError(buildResult: 'SUCCESS', stageResult: 'NOT_BUILT') {
            error("Skipping stage")
          }
          return
        }

        if (env.MODE_3M in ['enabled', 'required']) {
          try {
            println("Make target-> verify")
            sh 'make verify'
          } catch (e) {
            println("Could not execute: make verify")
            if (MODE_3M == 'required') {
              throw e
            }
            println("Fall-back to non-3M function")
            verify(a_service_name)
          }
        } else {
          verify(a_service_name)
        }
      }
      stage("Destroy Veracode Sandbox ${a_service_name}") {
        if (!env.DO_DESTROY_VERACODE_SANDBOX.toBoolean()) {
          catchError(buildResult: 'SUCCESS', stageResult: 'NOT_BUILT') {
            error("Skipping stage")
          }
          return
        }

        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
          if (env.MODE_3M in ['enabled', 'required']) {
            try {
              println("Make target-> veracodeClean")
              sh 'make veracodeClean'
            } catch (e) {
              println("Could not execute: make veracodeClean")
              if (MODE_3M == 'required') {
                throw e
              }
              println("Fall-back to non-3M function")
              destroyVeraSandbox(a_service_name)
            }
          } else {
            destroyVeraSandbox(a_service_name)
          }
        }
      }
    }
  }
}
