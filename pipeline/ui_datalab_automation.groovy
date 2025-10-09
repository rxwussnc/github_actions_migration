pipeline {
    agent {
        label {
            label 'advsgdpalma901'
            customWorkspace "/jnkns/$BUILD_NUMBER"
        }
    }

    options {
        timestamps()
        ansiColor('xterm')
        buildDiscarder(logRotator(numToKeepStr:'10'))
        timeout(time: 300, unit: 'MINUTES')
    }

    parameters {
        string(name: 'ENVIRONMENT', defaultValue: 'https://feature-51406-qa-dev-genesis.ssnc-corp.cloud', description: 'Enter an platform environment, default is main environment: https://dev-genesis.ssnc-corp.cloud')
        string(name: 'PARALLEL_SCHEME', defaultValue: "feature", description: 'Enter parallel scheme: run tests parallel at feature or scenario level. Default is feature')
        string(name: 'PARALLEL_PROCESSES', defaultValue: '8', description: 'Enter a process number to run. Default is 8')
        string(name: 'TEST_BRANCH', defaultValue: 'AOCWIT-50012', description: 'Enter test branch for ui automation repo to test against. Default is master branch')
        string(name: 'TAGS', defaultValue: '@datalab', description: 'Enter tags for test scope: datalab ui full regression tests: @datalab')
    }

    environment{
        github = credentials('98d6dff7-4214-4faa-be06-fb952e90fc29')
    }

    stages {
        stage('Clone Repo') {
            steps {
                script {
                    sh "git clone https://${github_USR}:${github_PSW}@code.ssnc.dev/ssc-advent/genesis-falcon-tests-ui.git"
                    sh "git branch -a"
                    sh "git checkout ${TEST_BRANCH}"
                    sh "git branch --show-current"
                    sh "pip3 install -r requirements.txt"
                    sh "pip3 list"
                    sh "sed -i 's,base_url = '.*',base_url = \"${ENVIRONMENT}\",g' assets/config/config.py"
                    println "base_url set to: ${ENVIRONMENT}"
                    sh "cat assets/config/config.py"
                    currentBuild.displayName = "${env.BUILD_NUMBER}-UITest-[${ENVIRONMENT}]-[${TEST_BRANCH}]-[${TAGS}]"
                }
            }
        }

        stage('Run Tests') {
            steps {
                script {
                    try {
                            sh "python3 -m behavex --log-capture -t ${TAGS} --parallel-processes ${PARALLEL_PROCESSES} --parallel-scheme ${PARALLEL_SCHEME} -o output"
                    }
                    catch (error) {
                            println "Run Tests - Found errors in testing"
                            currentBuild.result = 'FAILURE'
                    }
                }
            }
            post {
                always {
                    script {
                        publishReport('output', 'DataLab UI Automation Report')
                        archiveArtifacts(artifacts: 'output/**', allowEmptyArchive: true)
                    }
                }
            }
        }

        stage('Re-run Tests') {
            steps {
                script {
                        if (fileExists('output/failing_scenarios.txt') ) {
                            try {
                                sh "python3 -m behavex --log-capture -rf output/failing_scenarios.txt --parallel-processes ${PARALLEL_PROCESSES} --parallel-scheme ${PARALLEL_SCHEME} -o output_rerun"
                            } catch (error) {
                                println "Re-run Test - Found errors in testing"
                                currentBuild.result = 'FAILURE'
                            }
                        } else {
                            println "all tests passed - no failing_scenarios.txt file generated"
                        }
                }
            }

            post {
                always {
                    publishReport('output_rerun', 'DataLab UI Automation Re-run Report')
                    archiveArtifacts(artifacts: 'output_rerun/**', allowEmptyArchive: true)
                }
            }
        }


        stage('Second Re-run Tests') {
            steps {
                script {
                        if (fileExists('output_rerun/failing_scenarios.txt') ) {
                            try {
                                sh "python3 -m behavex --log-capture -rf output_rerun/failing_scenarios.txt --parallel-processes ${Process_Number} -o output_rerun_second"
                            } catch (error) {
                                println "Second Re-run Test - Found errors in testing"
                                currentBuild.result = 'FAILURE'
                            }
                        } else {
                            println "all tests passed - no failing_scenarios.txt file generated"
                        }
                }
            }

            post {
                always {
                    publishReport('output_rerun_second', 'DataLab UI Automation Second Time Re-run Report')
                    archiveArtifacts(artifacts: 'output_rerun_second/**', allowEmptyArchive: true)
                }
            }
        }

        stage('Publish Videos/Traces') {
            steps {
                script {
                    println('Publish Videos/Traces Reports')
                }
            }
            post {
                always {
                    publishHTML (target: [
                            allowMissing: true,
                            alwaysLinkToLastBuild: false,
                            keepAll: true,
                            reportDir: 'videos',
                            reportFiles: '**/*',
                            reportName: "UI Videos"
                    ])
                    publishHTML (target: [
                            allowMissing: true,
                            alwaysLinkToLastBuild: false,
                            keepAll: true,
                            reportDir: 'trace',
                            reportFiles: '**/*',
                            reportName: "UI Traces"
                    ])
                }
            }
        }

        stage('Upload Results to Report Portal') {
            when {
                expression {
                    params.ENVIRONMENT.equals('https://feature-51406-qa-dev-genesis.ssnc-corp.cloud')
                }
            }
            steps {
                script {
                    // Determine environment
                    def launch_environment = ""
                    if (params.ENVIRONMENT.contains("-qa-dev-genesis.ssnc-corp.cloud")) {
                      launch_environment = "QA_BRANCH_ENV"
                    } else if (params.ENVIRONMENT.equals("https://dev-genesis.ssnc-corp.cloud")) {
                      launch_environment = "MAIN_ENV"
                    }

                    // Define attributes
                    def attributes = "Entitlement:DataLab AutoType:UI Environment:${launch_environment} Branch:${TEST_BRANCH}"
                    def launchName = "Falcon_DataLab_UI"
                    def zip_file_name = "GENESIS_PM#${launchName}.zip"

                    // Apply updates using sed
                    sh """
                        sed -i 's/launch_name = Falcon_Gate/launch_name = ${launchName}/g' behave.ini || exit 1
                        sed -i 's/launch_attributes = Tenant:201/launch_attributes = ${attributes}/g' behave.ini || exit 1
                    """
                    sh "cat behave.ini"

                    // Print variables for debugging
                    println "Launch Name: ${launchName}"
                    println "Attributes: ${attributes}"

                    // Execute the Python script
                    try {
                        sh "python3 report_portal_integration.py --result_folder output --jenkins_build_link '${env.BUILD_URL}'"
                    }
                    catch (error) {
                        println "Error processing the report.json objects"
                    }

                    withCredentials([string(credentialsId: 'REPORT_PORTAL_S3_ACCESS_ID', variable: 'RP_S3_ACCESS_ID'),
                        string(credentialsId: 'REPORT_PORTAL_S3_ACCESS_KEY', variable: 'RP_S3_ACCESS_KEY')])
                    {
                        try {
                            sh "aws configure set aws_access_key_id ${env.RP_S3_ACCESS_ID} --profile RP_CREDS"
                            sh "aws configure set aws_secret_access_key ${env.RP_S3_ACCESS_KEY} --profile RP_CREDS"
                        }
                        catch (error) {
                            println "Error while configuring aws profile ${error}"
                        }

                        try {
                            sh "aws s3 cp output s3://terraform-state-report-portal/RP_ReportsToUpload --recursive --exclude '*' --include '*.zip' --endpoint-url http://wdc-ecs-01-dtn.dstcorp.net:9020 --profile RP_CREDS"
                        }
                        catch (error) {
                            println "Error while uploading to S3 ${error}"
                        }

                        try {
                            sh "aws configure set aws_access_key_id None --profile RP_CREDS"
                            sh "aws configure set aws_secret_access_key None --profile RP_CREDS"
                        }
                        catch (error) {
                            println "Error while resetting aws profile: ${error}"
                        }
                    }
                }
            }
        }
    }

    post {
        cleanup {
            /* clean up our workspace */
            cleanWs()
            /* clean up tmp directory */
            dir("${workspace}@tmp"){
                deleteDir()
            }
            /* clean up script directory */
            dir("${workspace}@script") {
                deleteDir()
            }
            /* clean up script directory */
            dir("${workspace}@libs"){
                deleteDir()
            }
        }
        failure {
            script {
                println "Failed"
            }
        }
        success {
            script {
                println "Success"
            }
        }
    }
}

def publishReport(path, name) {
    publishHTML (target: [
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: path,
            reportFiles: 'report.html',
            reportName: name
    ])
}
