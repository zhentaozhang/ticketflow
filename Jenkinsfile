// =============================================================================
// TicketFlow Jenkins 声明式流水线（与 .github/workflows/cd.yml 同构）
//
// 阶段：Checkout → Test → Static analysis → Build images → Scan → Push → Deploy → Release verify
// 说明：推送与部署需要凭据，需在 Jenkins 配置：
//   * registry-cred     : username/password（镜像仓库）
//   * kubeconfig-cred   : secret file（目标集群 kubeconfig）
//   部署为手动参数化（DEPLOY=true）触发。
// =============================================================================
pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    parameters {
        booleanParam(name: 'DEPLOY', defaultValue: false, description: '是否执行部署（需 kubeconfig-cred）')
        string(name: 'REGISTRY', defaultValue: 'ghcr.io/your-org', description: '镜像仓库前缀')
        string(name: 'NAMESPACE', defaultValue: 'ticketflow', description: '目标命名空间')
        string(name: 'SERVICES', defaultValue: 'gateway order program', description: '部署的服务（空格分隔）')
    }

    environment {
        IMAGE_TAG  = "${env.GIT_COMMIT?.take(8)}"
        REGISTRY   = "${params.REGISTRY}"
        NAMESPACE  = "${params.NAMESPACE}"
        SERVICES   = "${params.SERVICES}"
    }

    stages {
        stage('Checkout') {
            steps { checkout scm }
        }

        stage('Test') {
            steps { sh 'mvn -B test' }
        }

        stage('Static analysis') {
            steps {
                sh 'mvn -B checkstyle:check'
                sh 'mvn -B -DskipTests compile spotbugs:check'
            }
        }

        stage('Build images') {
            steps { sh 'bash ./docker/build.sh images' }
        }

        stage('Scan images') {
            steps {
                // 阻断门禁：命中 HIGH/CRITICAL 即失败（残留豁免见 .trivyignore）
                sh '''
                  if ! command -v trivy >/dev/null 2>&1; then
                    curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin
                  fi
                  IMAGE_TAG=0.0.1-SNAPSHOT bash scripts/scan-images.sh
                '''
            }
        }

        stage('Push images') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'registry-cred', usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
                    sh '''
                      echo "$REG_PASS" | docker login "$REGISTRY" -u "$REG_USER" --password-stdin
                      REGISTRY="$REGISTRY" GIT_SHA="$IMAGE_TAG" bash scripts/push-images.sh
                    '''
                }
            }
        }

        stage('Deploy') {
            when { expression { return params.DEPLOY } }
            steps {
                withCredentials([file(credentialsId: 'kubeconfig-cred', variable: 'KUBECONFIG')]) {
                    sh '''
                      for svc in $SERVICES; do
                        kubectl -n "$NAMESPACE" set image "deploy/$svc" "$svc=$REGISTRY/ticketflow-${svc}:$IMAGE_TAG"
                      done
                    '''
                }
            }
        }

        stage('Release verification') {
            when { expression { return params.DEPLOY } }
            steps {
                withCredentials([file(credentialsId: 'kubeconfig-cred', variable: 'KUBECONFIG')]) {
                    sh 'EXPECTED_TAG="$IMAGE_TAG" bash scripts/release-verify.sh'
                }
            }
        }
    }

    post {
        failure {
            script {
                if (params.DEPLOY) {
                    withCredentials([file(credentialsId: 'kubeconfig-cred', variable: 'KUBECONFIG')]) {
                        sh 'bash scripts/rollback.sh'
                    }
                }
            }
        }
    }
}
