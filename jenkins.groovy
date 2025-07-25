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
        SUBNETS        = 'subnet-0610380c4b701ba61' // Replace with your actual private subnets
        SECURITY_GROUP = 'sg-0bd3580461331e484'             // Replace with your actual security group
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

        stage('Ensure ECR Exists') {
            steps {
                script {
                    sh """
                        if ! aws ecr describe-repositories --repository-names ${ECR_REPO} --region ${AWS_REGION} > /dev/null 2>&1; then
                          aws ecr create-repository --repository-name ${ECR_REPO} --region ${AWS_REGION}
                          echo "✅ ECR repository created: ${ECR_REPO}"
                        else
                          echo "ℹ️ ECR repository exists: ${ECR_REPO}"
                        fi
                    """
                }
            }
        }

        stage('Build & Push Docker Image') {
            steps {
                script {
                    sh """
                        docker build -t ${ECR_REPO}:${IMAGE_TAG} .
                        docker tag ${ECR_REPO}:${IMAGE_TAG} ${ECR_URI}
                        aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_URI}
                        docker push ${ECR_URI}
                    """
                }
            }
        }

        stage('Register ECS Task Definition') {
            steps {
                script {
                    sh """
                        echo "📦 Registering task definition: ${TASK_FAMILY}"
                        aws ecs register-task-definition \
                          --family ${TASK_FAMILY} \
                          --requires-compatibilities FARGATE \
                          --network-mode awsvpc \
                          --cpu ${CPU} \
                          --memory ${MEMORY} \
                          --execution-role-arn arn:aws:iam::${AWS_ACCOUNT_ID}:role/ecsTaskExecutionRole \
                          --container-definitions '[
                              {
                                "name": "${CONTAINER_NAME}",
                                "image": "${ECR_URI}",
                                "portMappings": [
                                  {
                                    "containerPort": 3000,
                                    "hostPort": 3000,
                                    "protocol": "tcp"
                                  }
                                ],
                                "essential": true
                              }
                          ]' \
                          --region ${AWS_REGION}
                    """
                }
            }
        }

        stage('Create or Update ECS Cluster and Service') {
            steps {
                script {
                    sh """
                        echo "🔍 Checking ECS cluster: ${CLUSTER_NAME}"
                        if ! aws ecs describe-clusters --clusters ${CLUSTER_NAME} --region ${AWS_REGION} | grep -q 'ACTIVE'; then
                          aws ecs create-cluster --cluster-name ${CLUSTER_NAME} --region ${AWS_REGION}
                          echo "✅ Cluster created: ${CLUSTER_NAME}"
                        else
                          echo "ℹ️ Cluster exists: ${CLUSTER_NAME}"
                        fi

                        echo "🔍 Checking ECS service: ${SERVICE_NAME}"
                        SERVICE_EXISTS=\$(aws ecs describe-services \
                            --cluster ${CLUSTER_NAME} \
                            --services ${SERVICE_NAME} \
                            --region ${AWS_REGION} \
                            --query "services[0].status" \
                            --output text)

                        if [ "\$SERVICE_EXISTS" = "ACTIVE" ]; then
                          echo "🔄 Updating ECS service..."
                          aws ecs update-service \
                            --cluster ${CLUSTER_NAME} \
                            --service ${SERVICE_NAME} \
                            --force-new-deployment \
                            --region ${AWS_REGION}
                        else
                          echo "🚀 Creating ECS service..."
                          aws ecs create-service \
                            --cluster ${CLUSTER_NAME} \
                            --service-name ${SERVICE_NAME} \
                            --task-definition ${TASK_FAMILY} \
                            --desired-count 1 \
                            --launch-type FARGATE \
                            --network-configuration "awsvpcConfiguration={subnets=[${SUBNETS}],securityGroups=[${SECURITY_GROUP}],assignPublicIp=ENABLED}" \
                            --region ${AWS_REGION}
                        fi
                    """
                }
            }
        }
    }

    post {
        success {
            echo '✅ Deployment successful!'
        }
        failure {
            echo '❌ Deployment failed. Please check logs.'
        }
    }
}
