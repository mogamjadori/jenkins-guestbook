import java.text.SimpleDateFormat
def TODAY = (new SimpleDateFormat("yyMMddHHmm")).format(new Date())

pipeline {
    agent any
    environment {
        strDockerTag = "${TODAY}_${BUILD_ID}"
        strDockerImage = "mogamjadori/guestbook:${strDockerTag}"
    }
    stages {
        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/mogamjadori/jenkins-gusetbook-.git'
            }
        }
        stage('Build') {
            steps {
                sh "chmod +x ./mvnw; ./mvnw clean package"
            }
        }
        stage('Unit Test') {
            steps {
                sh "./mvnw test"
            }
            post {
                always {
                    junit "**/target/surefire-reports/TEST-*.xml"
                }
            }
        }
        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQube-Server') {
                    sh '''
                        ./mvnw sonar:sonar \
                            -Dsonar.projectKey=guestbook2 \
                            -Dsonar.host.url=http://51.20.138.36:9000 \
                            -Dsonar.login=de2b74963f2f44f8cff8db0cf7ba55d245cc5d1d
                    '''
                }
            }
        }
        stage('SonarQube Quality Gate') {
            steps {
                echo 'SonarBube Quality Gate'
                timeout(time: 2, unit: 'MINUTES') {
                    script {
                        def qg = waitForQualityGate()
                        if(qg.status != 'OK') {
                            echo "NOT OK Status: ${qg.status}"
                            error "Pipeline aborted due to quality gate failure: ${qg.status}"
                        } else {
                            echo "${TODAY} OK Status: ${qg.status}"
                        }
                    }
                }
            }
        }
        stage('Docker Image Build') {
            steps {
                script {
                    oDockImage = docker.build(strDockerImage, "--build-arg VERSION=${strDockerTag} -f Dockerfile .")
                }
            }
        }
        //stage('Docker Image Push') {
        //    steps {
        //        script {
        //            docker.withRegistry('', 'dockerhub-token') {
        //                oDockerImage.push()
        //            }
        //        }
        //    }
        //}
        stage('SSH guestbook') {
            steps {
                sshagent(credentials: ['ssh-token']) {
                    sh "ssh -o StrictHostKeyChecking=no ec2-user@172.31.32.110 docker image pull ${strDockerImage}"
                    sh "ssh -o StrictHostKeyChecking=no ec2-user@172.31.32.110 docker container rm -f guestbook"
                    sh "ssh -o StrictHostKeyChecking=no ec2-user@172.31.32.110 docker container run \
                        -d \
                        -p 38080:80 \
                        --name guestbook \
                        -e MYSQL_IP=172.31.32.110 \
                        -e MYSQL_PORT=3306 \
                        -e MYSQL_USER=root \
                        -e MYSQL_PASSWORD=education \
                        -e MYSQL_DATABASE=guestbook \
                        ${strDockerImage}"
                }
            }
        }
        stage('jMeter Load Test') {
            steps {
                sh 'home/ec2-user/jmeter/bin/jmeter.sh -Jjmeter.save.saveservice.output_format=xml -n -t guestbook_loadtest.jmx -l loadtest_result.jtl'
                perfReport filterRegex: '', showTrendGraphs: true, sourceDataFiles: 'loadtest_result.jtl'            
                
            }
        }
    }
    post {
        success {
            slackSend(tokenCredentialId: 'slack-token',
                channel: '#cicd',
                color: 'good',
                message: "${JOB_NAME} (${BUILD_NUMBER}) 빌드가 성공적으로 끝났습니다. (<${BUILD_URL} : 여기>)")
        }
        failure {
            slackSend(tokenCredentialId: 'slack-token',
                channel: '#cicd',
                color: 'danger',
                message: "${JOB_NAME} (${BUILD_NUMBER}) 빌드가 실패하였습니다. (<${BUILD_URL} : 여기>)")
        }
    }
}