def call(args) {
    if (!args || args instanceof Closure) {
        args = [:]
    }

    pipeline {

        agent {
            label args.get('jenkins_agent', 'linux01||linux02||linux03||linux04||linux05||linux06')
        }

        parameters {
            text(name: 'module_name', defaultValue: "", description: 'Enter Module Name(s), It should be comma separated in case of more than one Module')
            booleanParam(name: 'unit_test_enable', defaultValue: true, description: 'Uncheck to disable unit tests')
            booleanParam(name: 'sonar_scan_enable', defaultValue: true, description: 'Uncheck to disable Sonar scan')
            booleanParam(name: 'veracode_scan_enable', defaultValue: false, description: 'Check to enable Veracode scan')
            booleanParam(name: 'blackduck_scan_enable', defaultValue: false, description: 'Check to enable Blackduck scan')
            booleanParam(name: 'verify_gsd_rules', defaultValue: false, description: 'Check to verify GSD rules')
            booleanParam(name: 'promote_artifacts', defaultValue: false, description: 'Check to promote verified artifacts')
        }

        environment {
            BRANCH_NAME = "${GIT_BRANCH}".replace("origin/", "")
            DOCKER_TAG = "dev"
            GRADLE_HOME = tool('gradle-5.4')
            PYTHONENV = tool('python-3.6.3')
            SONAR = credentials('sonar')
            SONAREE = credentials('SONAREE')
            VERACODE = credentials('VERACODE_SCAN')
            VERACODE_IMAGE_NAME = "goo-images/utility-scanner-veracode:0.1.41"
            ARTIFACT_TAGGING_IMAGE_NAME = "goo-images/tag-artifact:latest"
            PROXY = credentials('PROXY')
            PROXY_HOST = "prodproxy.theocc.com"
            PROXY_PORT = "8060"
            PROXY_URL = "http://$PROXY_USR:$PROXY_PSW@$PROXY_HOST:$PROXY_PORT"
            NO_PROXY = ".theocc.com"
            TF_DOCKER_IMAGE = "application/sre/k8s-validator:stable"
            DELETE_SANDBOX_IMAGE = "application/risk-framework/devops/images/veracode-delete-sandbox:0.1.8"
            HELM_WRITE_REPO = "https://artifactory.theocc.com:8443/artifactory/helm-snapshots-local"
            HELM_READ_REPO = "https://artifactory.theocc.com:8443/artifactory/helm-virtual-local"
            SHA_SHORT = "${GIT_COMMIT[0..7]}"
            ARTIFACTORY_DOCKER_DEV_WRITE = 'https://svnserver.theocc.com:8443/artifactory/api/storage/docker-dev-local'
            SONAR_PROJECT_VERSION = "${GIT_BRANCH}_${BUILD_NUMBER}"
            DOCKER_COMPOSE_LOCATION = tool('docker-compose')
            PATH = "${DOCKER_COMPOSE_LOCATION}:${PATH}"
            MODE_3M = 'enabled' // enabled,disabled,required
        }

        options {
            buildDiscarder(logRotator(daysToKeepStr: '100', numToKeepStr: '100'))
            disableConcurrentBuilds()
        }

        stages {

            stage('Pre-flight') {
                steps {
                    nodejs(nodeJSInstallationName: 'node-v12.14.1', configId: 'NPMRCDEV') {
                        script {
                            println("===> 3M Mode: ${MODE_3M} <===")
                            if (MODE_3M in ['enabled', 'required']) {
                                try {
                                    println("Make target-> preFlight")
                                    withEnv([
                                            "PREVIOUS_GIT_REF=${getLastSuccessfulCommit()}",
                                            "JENKINS_BUILD_CAUSES=${currentBuild.buildCauses}",
                                            "PARAM_SERVICES=${params.module_name}",
                                            "PARAM_UNITTEST=${params.unit_test_enable}",
                                            "PARAM_SONAR=${params.sonar_scan_enable}",
                                            "PARAM_VERACODE=${params.veracode_scan_enable}",
                                            "PARAM_BLACKDUCK=${params.blackduck_scan_enable}",
                                            "PARAM_VERIFY=${params.verify_gsd_rules}",
                                            "PARAM_PROMOTE=${params.promote_artifacts}"
                                    ]) {
                                        sh 'make preFlight'
                                        def props = readProperties file: 'pre_flight.env'
                                        def keys = props.keySet()
                                        for (key in keys) {
                                            env."${key}" = props[key]
                                        }
                                    }
                                } catch (e) {
                                    println("Could not execute: make preFlight")
                                    if (MODE_3M == 'required') {
                                        throw e
                                    }
                                    println("Fall-back to non-3M function")
                                    env.BRANCH_BUILD_TYPE = getBuildType(BRANCH_NAME)
                                    pre_flight()
                                }
                            } else {
                                env.BRANCH_BUILD_TYPE = getBuildType(BRANCH_NAME)
                                pre_flight()
                            }
                        }
                    }
                }
            }

            stage('Run Pipe') {
                when { expression { env.DO_RUN_PIPE.toBoolean() } }

                stages {
                    stage('Helm Lint') {
                        when { expression { env.DO_HELM_LINT.toBoolean() } }
                        steps {
                            script {
                                if (MODE_3M in ['enabled', 'required']) {
                                    try {
                                        println("Make target-> helmLint")
                                        parallel env.DOC_APPS.split(",").collectEntries {
                                            ["helmLint ${it}": { withEnv(["MODULE=${it}"]) { sh "make helmLint" } }]
                                        }
                                    } catch (e) {
                                        // this falls back if any of the parallel modules fail
                                        println("Could not execute: make helmLint")
                                        if (MODE_3M == 'required') {
                                            throw e
                                        }
                                        println("Fall-back to non-3M function")
                                        helm_lint(env.DOC_APPS)
                                        k8s_validator_check(env.DOC_APPS)
                                    }
                                } else {
                                    helm_lint(env.DOC_APPS)
                                    k8s_validator_check(env.DOC_APPS)
                                }
                            }
                        }
                    }

                    stage('Build') {
                        when { expression { env.DO_BUILD.toBoolean() } }
                        steps {
                            nodejs(nodeJSInstallationName: 'node-v12.14.1', configId: 'NPMRCDEV') {
                                script {
                                    if (MODE_3M in ['enabled', 'required']) {
                                        try {
                                            println("Make target-> build")
                                            sh 'make build'
                                        } catch (e) {
                                            println("Could not execute: make build")
                                            if (MODE_3M == 'required') {
                                                throw e
                                            }
                                            println("Fall-back to non-3M function")
                                            build_parallel(env.IMAGE_NAMES)
                                        }
                                    } else {
                                        build_parallel(env.IMAGE_NAMES)
                                    }
                                }
                            }
                        }
                    }

                    stage('Unit Test') {
                        when { expression { env.DO_UNIT_TEST.toBoolean() } }
                        steps {
                            nodejs(nodeJSInstallationName: 'node-v12.14.1', configId: 'NPMRCDEV') {
                                script {
                                    if (MODE_3M in ['enabled', 'required']) {
                                        try {
                                            println("Make target-> unitTest")
                                            sh 'make unitTest'
                                        } catch (e) {
                                            println("Could not execute: make unitTest")
                                            if (MODE_3M == 'required') {
                                                throw e
                                            }
                                            println("Fall-back to non-3M function")
                                            test_parallel(env.IMAGE_NAMES)
                                        }
                                    } else {
                                        test_parallel(env.IMAGE_NAMES)
                                    }
                                }
                            }
                        }
                    }

                    stage('Sonar QualityGate') {
                        when { expression { env.DO_SONAR_QUALITYGATE.toBoolean() } }
                        environment {
                            SONAR_METRICS = "bugs,coverage,code_smells"
                            SONAR_BRANCH_NAME = "${GIT_BRANCH}"
                        }
                        steps {
                            nodejs(nodeJSInstallationName: 'node-v12.14.1', configId: 'NPMRCDEV') {
                                script {
                                    if (MODE_3M in ['enabled', 'required']) {
                                        try {
                                            println("Make target-> sonar")
                                            parallel env.IMAGE_NAMES.split(",").collectEntries {
                                                ["Sonar ${it}": {
                                                    withSonarQubeEnv('SonarQubeEE') {
                                                        def sonarParams = readJSON text: SONARQUBE_SCANNER_PARAMS
                                                        def sonarLogin = sonarParams["sonar.login"]
                                                        withEnv([
                                                                "MODULE=${it}",
                                                                "SONAR=${sonarLogin}"
                                                        ]) {
                                                            sh "make sonar"
                                                        }
                                                    }
                                                    println("Checking Quality Gate status for service-> ${it}")
                                                    withSonarQubeEnv('SonarQubeEE') {
                                                        sleep(5) // Make sure Sonar's last scan results are available
                                                        timeout(time: 15, unit: 'SECONDS') {
                                                            def qg = waitForQualityGate()
                                                            if (qg.status != 'OK') {
                                                                throw new Exception("Pipeline aborted due to quality gate failure: ${qg.status}")
                                                            }
                                                        }
                                                    }
                                                    sh "cp ${it}/report-task.txt sonar-report-${it}.txt"
                                                    archiveArtifacts artifacts: "sonar-report-${it}.txt"
                                                }]
                                            }
                                        } catch (e) {
                                            // there is no makefile/make target/QG error
                                            println("Could not execute: make sonar")
                                            if (MODE_3M == 'required') {
                                                throw e
                                            }
                                            println("Fall-back to non-3M function")
                                            sonarAnalysisAndQualityGate(env.IMAGE_NAMES)
                                        }
                                    } else {
                                        sonarAnalysisAndQualityGate(env.IMAGE_NAMES)
                                    }
                                }
                            }
                        }
                    }

                    stage('Package') {
                        when { expression { env.DO_PACKAGE.toBoolean() } }
                        steps {
                            nodejs(nodeJSInstallationName: 'node-v12.14.1', configId: 'NPMRCDEV') {
                                script {
                                    if (MODE_3M in ['enabled', 'required']) {
                                        try {
                                            println("Make target-> package")
                                            withDockerRegistry([credentialsId: 'PUBLISH_TO_ARTIFACTORY', url: 'http://svnserver.theocc.com:8443']) {
                                                withEnv([
                                                        "MODULE=${env.DOC_APPS}"
                                                ]) {
                                                    sh "make package"
                                                }
                                            }
                                        } catch (e) {
                                            println("Could not execute: make package")
                                            if (MODE_3M == 'required') {
                                                throw e
                                            }
                                            println("Fall-back to non-3M function")
                                            docbuild(env.DOC_APPS)
                                        }
                                    } else {
                                        docbuild(env.DOC_APPS)
                                    }
                                }
                            }
                        }
                    }

                    stage('Validated Publish') {
                        when { expression { env.DO_VALIDATED_PUBLISH.toBoolean() } }
                        environment {
                            BUILD_ENV = "dev" // this controls which rules get run.  For CI pipeline, just dev rules
                            VULNERABILITY_REPORT = "veracode-vulnerability-report.txt"
                            VULNERABILITY_REPORT_TYPE = "txt"
                            VULNERABILITY_REPORT_PATH = "${WORKSPACE}/verification-rules"
                            WORKDAY_CREDS = credentials('WORKDAY')
                            SNOW_USER = "${SNOW_CREDS_USR}"
                            SNOW_PASS = "${SNOW_CREDS_PSW}"
                            ARTIFACTORY_URL = "https://artifactory.theocc.com:8443/artifactory"
                            ARTIFACTORY = credentials('PUBLISH_TO_ARTIFACTORY')
                            DOCKER_REGISTRY_URL = "https://svnserver.theocc.com:8443"
                            GITHUB_CREDS = credentials('Jenkins_GitHub_PIPELINE')
                            JIRA_CREDS = credentials('JIRA-REST-API')
                            SNOW_CREDS = credentials('SERVICENOW-DEV')
                            PROPERTIES_FILE = "${WORKSPACE}/build.properties"
                            SONAR_METRICS = "bugs,coverage,code_smells"
                            SONAR_BRANCH_NAME = "${GIT_BRANCH}"
                            STRICT_MODE = "false"
                            VERIFICATION_BRANCH = "master"
                            JIRA_USER = "${JIRA_CREDS_USR}"
                            JIRA_PASS = "${JIRA_CREDS_PSW}"
                            CREATE_JIRA_TICKETS = "${CREATE_JIRA_TICKETS}"
                        }
                        steps {
                            nodejs(nodeJSInstallationName: 'node-v12.14.1', configId: 'NPMRCDEV') {
                                script {
                                    parallel env.IMAGE_NAMES.split(",").collectEntries {
                                        ["${it} Validated Publish": createValidatedPublishSteps(it)]
                                    }
                                }
                            }
                        }
                    }

                    stage('Promote Artifacts') {
                        when { expression { env.DO_PROMOTE_ARTIFACTS.toBoolean() } }
                        steps {
                            script {
                                if (MODE_3M in ['enabled', 'required']) {
                                    try {
                                        println("Make target-> verifyAndPromote")
                                        sh 'make verifyAndPromote'
                                    } catch (e) {
                                        println("Could not execute: make verifyAndPromote")
                                        if (MODE_3M == 'required') {
                                            throw e
                                        }
                                        println("Fall-back to non-3M function")
                                        verifyAndPromote(env.DOC_APPS)
                                    }
                                } else {
                                    verifyAndPromote(env.DOC_APPS)
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                println("Post: Always")
                script {
                    if (env.DO_CLEAN.toBoolean()) {
                        if (MODE_3M in ['enabled', 'required']) {
                            try {
                                println("Make target-> clean")
                                sh 'make clean'
                            } catch (e) {
                                println("Could not execute: make clean")
                                if (MODE_3M == 'required') {
                                    throw e
                                }
                                println("Fall-back to non-3M function")
                                docker_clean(env.IMAGE_NAMES)
                                sh 'docker run --rm -v ${PWD}:/tmp -w="/tmp" platform-dev/base/node:10.15.0-master.4 sh -c "rm -rf ..?* .[!.]* *"'
                            }
                        } else {
                            docker_clean(env.IMAGE_NAMES)
                            sh 'docker run --rm -v ${PWD}:/tmp -w="/tmp" platform-dev/base/node:10.15.0-master.4 sh -c "rm -rf ..?* .[!.]* *"'
                        }
                    }
                }
                deleteDir()
                cleanWs()
            }
            success {
                println("Post: Success")
                script {
                    if (env.DO_NOTIFY.toBoolean()) {
                        emailext body: '$PROJECT_NAME - Build # $BUILD_NUMBER - SUCCESS!', subject: '$PROJECT_NAME - Build # $BUILD_NUMBER - SUCCESS', to: '$NOTIFICATION_RECIPIENTS'
                    }
                }
            }
            failure {
                println("Post: Failure")
                script {
                    if (env.DO_NOTIFY.toBoolean()) {
                        emailext body: '$PROJECT_NAME - Build # $BUILD_NUMBER - FAILED!', subject: '$PROJECT_NAME - Build # $BUILD_NUMBER - FAILED', to: '$NOTIFICATION_RECIPIENTS'
                    }
                }
            }
        }
    }
}
