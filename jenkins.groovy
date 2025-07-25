pipeline {
    agent any

    environment {
        AWS_REGION     = 'ap-south-1'
        AWS_ACCOUNT_ID = '340752818053'
        ECR_REPO       = 'node-api'
        IMAGE_TAG      = 'latest'
        ECR_URI        = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_REPO}"
        CLUSTER_NAME   = 'node-api-cluster'
        SERVICE_NAME   = 'node-api-service'
        CONTAINER_NAME = 'node-api'
        TASK_FAMILY    = 'node-api-task'
        SUBNETS        = 'subnet-0610380c4b701ba61' // Replace with your subnet ID
        SECURITY_GROUP = 'sg-0bd3580461331e484'     // Replace with your security group
        CPU            = '256'
        MEMORY         = '512'
    }

    options {
        skipDefaultCheckout(true)
    }

    stages {
        stage('Checkout') {
            steps {
                cleanWs()
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/Docker']],
                    userRemoteConfigs: [[url: 'https://github.com/alwinraja-devops/Jenkins.git']]
                ])
            }
        }

        stage('AWS Login & ECR Setup') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-credentials', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    script {
                        sh '''
                            export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
                            export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
                            export AWS_DEFAULT_REGION=${AWS_REGION}

                            if ! aws ecr describe-repositories --repository-names ${ECR_REPO} > /dev/null 2>&1; then
                              aws ecr create-repository --repository-name ${ECR_REPO}
                              echo "✅ Created ECR repo"
                            else
                              echo "ℹ️ ECR repo exists"
                            fi

                            aws ecr get-login-password | docker login --username AWS --password-stdin ${ECR_URI}
                        '''
                    }
                }
            }
        }

        stage('Docker Build & Push') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-credentials', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    script {
                        sh '''
                            export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
                            export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
                            export AWS_DEFAULT_REGION=${AWS_REGION}

                            docker build -t ${ECR_REPO}:${IMAGE_TAG} .
                            docker tag ${ECR_REPO}:${IMAGE_TAG} ${ECR_URI}
                            docker push ${ECR_URI}
                        '''
                    }
                }
            }
        }

stage('Register ECS Task') {
    steps {
        withCredentials([usernamePassword(credentialsId: 'aws-credentials', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
            script {
                sh '''
                    export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
                    export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
                    export AWS_DEFAULT_REGION=${AWS_REGION}

                    aws ecs register-task-definition \
                      --family ${TASK_FAMILY} \
                      --requires-compatibilities FARGATE \
                      --network-mode awsvpc \
                      --cpu ${CPU} \
                      --memory ${MEMORY} \
                      --execution-role-arn arn:aws:iam::${AWS_ACCOUNT_ID}:role/ecsTaskExecutionRole \
                      --container-definitions "[{
                        \\"name\\": \\"${CONTAINER_NAME}\\",
                        \\"image\\": \\"${ECR_URI}\\",
                        \\"portMappings\\": [{
                          \\"containerPort\\": 3000,
                          \\"hostPort\\": 3000,
                          \\"protocol\\": \\"tcp\\"
                        }],
                        \\"essential\\": true
                      }]"
                '''
            }
        }
    }
}


        stage('Create/Update ECS Cluster & Service') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'aws-credentials', usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    script {
                        sh '''
                            export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID}
                            export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY}
                            export AWS_DEFAULT_REGION=${AWS_REGION}

                            if ! aws ecs describe-clusters --clusters ${CLUSTER_NAME} | grep -q '"status": "ACTIVE"'; then
                              aws ecs create-cluster --cluster-name ${CLUSTER_NAME}
                              echo "✅ Cluster created"
                            else
                              echo "ℹ️ Cluster exists"
                            fi

                            SERVICE_EXISTS=$(aws ecs describe-services \
                                --cluster ${CLUSTER_NAME} \
                                --services ${SERVICE_NAME} \
                                --query "services[0].status" \
                                --output text)

                            if [ "$SERVICE_EXISTS" = "ACTIVE" ]; then
                                echo "🔄 Updating service"
                                aws ecs update-service \
                                  --cluster ${CLUSTER_NAME} \
                                  --service ${SERVICE_NAME} \
                                  --force-new-deployment
                            else
                                echo "🚀 Creating service"
                                aws ecs create-service \
                                  --cluster ${CLUSTER_NAME} \
                                  --service-name ${SERVICE_NAME} \
                                  --task-definition ${TASK_FAMILY} \
                                  --desired-count 1 \
                                  --launch-type FARGATE \
                                  --network-configuration "awsvpcConfiguration={subnets=[${SUBNETS}],securityGroups=[${SECURITY_GROUP}],assignPublicIp=ENABLED}"
                            fi
                        '''
                    }
                }
            }
        }
    }

    post {
        success {
            echo '✅ Deployment successful!'
        }
        failure {
            echo '❌ Deployment failed. Check logs above.'
        }
    }
}
