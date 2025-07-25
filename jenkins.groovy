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
    }

    options {
        skipDefaultCheckout(true) // Prevents automatic checkout
    }

    stages {
        stage('Checkout') {
            steps {
                // Clean workspace to avoid "already exists" error
                cleanWs()
                // Explicit checkout
                checkout([$class: 'GitSCM',
                    branches: [[name: '*/Docker']],
                    userRemoteConfigs: [[url: 'https://github.com/alwinraja-devops/Jenkins.git']]
                ])
            }
        }
stage('Docker Build & Tag') {
    steps {
        script {
            sh """
                docker build -t ${ECR_REPO}:${IMAGE_TAG} .
                docker tag ${ECR_REPO}:${IMAGE_TAG} ${ECR_URI}
            """
        }
    }
}
        stage('Login to ECR') {
            steps {
                script {
                    sh """
                        aws ecr get-login-password --region ${AWS_REGION} | \
                        docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
                    """
                }
            }
        }

stage('Push Image to ECR') {
    steps {
        script {
            sh """
                export PATH=\$PATH:/usr/local/bin
                docker push ${ECR_URI}
            """
        }
    }
}

stage('Deploy to ECS') {
    steps {
        script {
            sh """
                export PATH=\$PATH:/usr/local/bin
                aws ecs update-service \
                  --cluster ${CLUSTER_NAME} \
                  --service ${SERVICE_NAME} \
                  --force-new-deployment \
                  --region ${AWS_REGION}
            """
        }
    }
}
