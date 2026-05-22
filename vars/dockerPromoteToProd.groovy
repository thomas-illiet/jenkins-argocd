def call(Map config = [:]) {
    def cfg = [
        artifactoryDevRegistry: 'artifactory-dev.example.com',
        artifactoryDevRepository: 'docker-dev-local',
        artifactoryCredentialsId: 'artifactory-docker',
        artifactoryProdRegistry: 'artifactory-prod.example.com',
        artifactoryProdRepository: 'docker-prod-local',
        valuesPath: 'values.yaml',
        imageRepositoryYqPath: '.image.repository',
        imageTagYqPath: '.image.tag'
    ] + config
    def runtimeEnv = []

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
        }

        parameters {
            string(name: 'ARTIFACTORY_DEV_REGISTRY', defaultValue: "${cfg.artifactoryDevRegistry}", description: 'Dev Docker registry host, without protocol.')
            string(name: 'ARTIFACTORY_DEV_REPOSITORY', defaultValue: "${cfg.artifactoryDevRepository}", description: 'Dev Artifactory Docker repository.')
            string(name: 'ARTIFACTORY_PROD_REGISTRY', defaultValue: "${cfg.artifactoryProdRegistry}", description: 'Production Docker registry host, without protocol.')
            string(name: 'ARTIFACTORY_PROD_REPOSITORY', defaultValue: "${cfg.artifactoryProdRepository}", description: 'Production Artifactory Docker repository.')

            string(name: 'VALUES_PATH', defaultValue: "${cfg.valuesPath}", description: 'Relative path to the values file in the checked-out deployment repository, for example helm/values.yaml.')
            string(name: 'IMAGE_REPOSITORY_YQ_PATH', defaultValue: "${cfg.imageRepositoryYqPath}", description: 'yq path to the image repository field in the values file.')
            string(name: 'IMAGE_TAG_YQ_PATH', defaultValue: "${cfg.imageTagYqPath}", description: 'yq path to the image tag field in the values file.')
        }

        environment {
            ARTIFACTORY_CREDENTIALS = credentials("${cfg.artifactoryCredentialsId}")
        }

        stages {
            stage('Validate parameters and tools') {
                steps {
                    script {
                        [
                            'ARTIFACTORY_DEV_REGISTRY',
                            'ARTIFACTORY_DEV_REPOSITORY',
                            'ARTIFACTORY_PROD_REGISTRY',
                            'ARTIFACTORY_PROD_REPOSITORY',
                            'VALUES_PATH',
                            'IMAGE_REPOSITORY_YQ_PATH',
                            'IMAGE_TAG_YQ_PATH'
                        ].each { requireParam(it) }

                        env.IMAGE_REPOSITORY_YQ_PATH = params.IMAGE_REPOSITORY_YQ_PATH.trim()
                        env.IMAGE_TAG_YQ_PATH = params.IMAGE_TAG_YQ_PATH.trim()
                        env.ARTIFACTORY_DEV_REGISTRY_CLEAN = joinArtifactoryDockerPath([
                            params.ARTIFACTORY_DEV_REGISTRY,
                            params.ARTIFACTORY_DEV_REPOSITORY
                        ])
                        env.ARTIFACTORY_PROD_REGISTRY_CLEAN = joinArtifactoryDockerPath([
                            params.ARTIFACTORY_PROD_REGISTRY,
                            params.ARTIFACTORY_PROD_REPOSITORY
                        ])
                        env.DEV_IMAGE_PREFIX = env.ARTIFACTORY_DEV_REGISTRY_CLEAN
                        env.PROD_IMAGE_PREFIX = joinArtifactoryDockerPath([
                            params.ARTIFACTORY_PROD_REGISTRY,
                            params.ARTIFACTORY_PROD_REPOSITORY
                        ])

                        runtimeEnv = [
                            "ARTIFACTORY_DEV_REGISTRY=${params.ARTIFACTORY_DEV_REGISTRY.trim()}",
                            "ARTIFACTORY_DEV_REPOSITORY=${params.ARTIFACTORY_DEV_REPOSITORY.trim()}",
                            "ARTIFACTORY_PROD_REGISTRY=${params.ARTIFACTORY_PROD_REGISTRY.trim()}",
                            "ARTIFACTORY_PROD_REPOSITORY=${params.ARTIFACTORY_PROD_REPOSITORY.trim()}",
                            "VALUES_PATH=${params.VALUES_PATH.trim()}",
                            "IMAGE_REPOSITORY_YQ_PATH=${env.IMAGE_REPOSITORY_YQ_PATH}",
                            "IMAGE_TAG_YQ_PATH=${env.IMAGE_TAG_YQ_PATH}",
                            "ARTIFACTORY_DEV_REGISTRY_CLEAN=${env.ARTIFACTORY_DEV_REGISTRY_CLEAN}",
                            "ARTIFACTORY_PROD_REGISTRY_CLEAN=${env.ARTIFACTORY_PROD_REGISTRY_CLEAN}",
                            "DEV_IMAGE_PREFIX=${env.DEV_IMAGE_PREFIX}",
                            "PROD_IMAGE_PREFIX=${env.PROD_IMAGE_PREFIX}"
                        ]

                        withEnv(runtimeEnv) {
                            sh '''
                                set -eu
                                command -v docker >/dev/null
                                command -v yq >/dev/null
                                yq --version | grep -q 'version v4'
                                test -f "$VALUES_PATH"
                            '''
                        }
                    }
                }
            }

            stage('Read image and calculate prod image') {
                steps {
                    script {
                        withEnv(runtimeEnv) {
                            env.DEV_IMAGE_REPOSITORY = sh(
                                returnStdout: true,
                                script: 'yq -r \'eval(strenv(IMAGE_REPOSITORY_YQ_PATH)) // ""\' "$VALUES_PATH"'
                            ).trim()
                            env.PROMOTED_IMAGE_TAG = sh(
                                returnStdout: true,
                                script: 'yq -r \'eval(strenv(IMAGE_TAG_YQ_PATH)) // ""\' "$VALUES_PATH"'
                            ).trim()
                        }

                        if (!env.DEV_IMAGE_REPOSITORY || env.DEV_IMAGE_REPOSITORY == 'null') {
                            error("Missing value at ${env.IMAGE_REPOSITORY_YQ_PATH} in ${params.VALUES_PATH}")
                        }
                        if (!env.PROMOTED_IMAGE_TAG || env.PROMOTED_IMAGE_TAG == 'null') {
                            error("Missing value at ${env.IMAGE_TAG_YQ_PATH} in ${params.VALUES_PATH}")
                        }
                        if (!env.DEV_IMAGE_REPOSITORY.startsWith("${env.DEV_IMAGE_PREFIX}/")) {
                            error("Dev image repository '${env.DEV_IMAGE_REPOSITORY}' does not start with expected prefix '${env.DEV_IMAGE_PREFIX}'.")
                        }

                        env.PROD_IMAGE_REPOSITORY = "${env.PROD_IMAGE_PREFIX}${env.DEV_IMAGE_REPOSITORY.substring(env.DEV_IMAGE_PREFIX.length())}"
                        env.DEV_IMAGE = "${env.DEV_IMAGE_REPOSITORY}:${env.PROMOTED_IMAGE_TAG}"
                        env.PROD_IMAGE = "${env.PROD_IMAGE_REPOSITORY}:${env.PROMOTED_IMAGE_TAG}"

                        runtimeEnv = runtimeEnv + [
                            "DEV_IMAGE_REPOSITORY=${env.DEV_IMAGE_REPOSITORY}",
                            "PROMOTED_IMAGE_TAG=${env.PROMOTED_IMAGE_TAG}",
                            "PROD_IMAGE_REPOSITORY=${env.PROD_IMAGE_REPOSITORY}",
                            "DEV_IMAGE=${env.DEV_IMAGE}",
                            "PROD_IMAGE=${env.PROD_IMAGE}"
                        ]
                    }
                }
            }

            stage('Promote Docker image to prod Artifactory') {
                steps {
                    withEnv(runtimeEnv) {
                        sh '''
                            set -eu
                            printf '%s' "$ARTIFACTORY_CREDENTIALS_PSW" | docker login "$ARTIFACTORY_PROD_REGISTRY_CLEAN" \
                                --username "$ARTIFACTORY_CREDENTIALS_USR" \
                                --password-stdin

                            if docker manifest inspect "$PROD_IMAGE" >/dev/null 2>&1; then
                                echo "Image already exists in prod Artifactory and cannot be overwritten: $PROD_IMAGE" >&2
                                exit 1
                            fi

                            printf '%s' "$ARTIFACTORY_CREDENTIALS_PSW" | docker login "$ARTIFACTORY_DEV_REGISTRY_CLEAN" \
                                --username "$ARTIFACTORY_CREDENTIALS_USR" \
                                --password-stdin

                            docker pull "$DEV_IMAGE"
                            docker tag "$DEV_IMAGE" "$PROD_IMAGE"
                            docker push "$PROD_IMAGE"
                        '''
                    }
                }
            }
        }

        post {
            always {
                sh '''
                    set +e
                    if command -v docker >/dev/null 2>&1; then
                        if [ -n "${ARTIFACTORY_DEV_REGISTRY_CLEAN:-}" ]; then
                            docker logout "$ARTIFACTORY_DEV_REGISTRY_CLEAN" >/dev/null 2>&1
                        fi
                        if [ -n "${ARTIFACTORY_PROD_REGISTRY_CLEAN:-}" ]; then
                            docker logout "$ARTIFACTORY_PROD_REGISTRY_CLEAN" >/dev/null 2>&1
                        fi
                    fi
                '''
            }
        }
    }
}

def cleanDockerPath(String value) {
    return (value ?: '').trim()
        .replaceFirst(/^https?:\/\//, '')
        .replaceAll(/\/+$/, '')
}

def joinArtifactoryDockerPath(List<String> parts) {
    def cleanedParts = parts.collect { cleanDockerPath(it) }.findAll { it }
    if (cleanedParts.size() < 2) {
        return cleanedParts.join('/')
    }

    def registryHost = cleanedParts[0]
    def repositorySubdomain = cleanedParts[1]
    def imagePathParts = cleanedParts.drop(2)
    return (["${repositorySubdomain}.${registryHost}"] + imagePathParts).join('/')
}

def requireParam(String name) {
    if (!params[name]?.trim()) {
        error("Missing required parameter: ${name}")
    }
}
