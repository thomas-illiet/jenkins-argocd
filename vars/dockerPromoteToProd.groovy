def call(Map config = [:]) {
    def cfg = [
        artifactoryDevRegistryDefault: 'artifactory-dev.example.com',
        artifactoryDevRepositoryDefault: 'docker-dev-local',
        artifactoryDevCredentialsIdDefault: 'artifactory-dev-docker',
        artifactoryProdRegistryDefault: 'artifactory-prod.example.com',
        artifactoryProdRepositoryDefault: 'docker-prod-local',
        artifactoryProdCredentialsIdDefault: 'artifactory-prod-docker',
        valuesPathDefault: 'values.yaml',
        imageRepositoryYqPathDefault: '.image.repository',
        imageTagYqPathDefault: '.image.tag'
    ] + config

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
        }

        parameters {
            string(name: 'ARTIFACTORY_DEV_REGISTRY', defaultValue: "${cfg.artifactoryDevRegistryDefault}", description: 'Dev Docker registry host, without protocol.')
            string(name: 'ARTIFACTORY_DEV_REPOSITORY', defaultValue: "${cfg.artifactoryDevRepositoryDefault}", description: 'Dev Artifactory Docker repository.')
            string(name: 'ARTIFACTORY_DEV_CREDENTIALS_ID', defaultValue: "${cfg.artifactoryDevCredentialsIdDefault}", description: 'Jenkins username/password credentials for dev Artifactory.')
            string(name: 'ARTIFACTORY_PROD_REGISTRY', defaultValue: "${cfg.artifactoryProdRegistryDefault}", description: 'Production Docker registry host, without protocol.')
            string(name: 'ARTIFACTORY_PROD_REPOSITORY', defaultValue: "${cfg.artifactoryProdRepositoryDefault}", description: 'Production Artifactory Docker repository.')
            string(name: 'ARTIFACTORY_PROD_CREDENTIALS_ID', defaultValue: "${cfg.artifactoryProdCredentialsIdDefault}", description: 'Jenkins username/password credentials for prod Artifactory.')

            string(name: 'VALUES_PATH', defaultValue: "${cfg.valuesPathDefault}", description: 'Relative path to the values file in the checked-out deployment repository, for example helm/values.yaml.')
            string(name: 'IMAGE_REPOSITORY_YQ_PATH', defaultValue: "${cfg.imageRepositoryYqPathDefault}", description: 'yq path to the image repository field in the values file.')
            string(name: 'IMAGE_TAG_YQ_PATH', defaultValue: "${cfg.imageTagYqPathDefault}", description: 'yq path to the image tag field in the values file.')
        }

        stages {
            stage('Validate parameters and tools') {
                steps {
                    script {
                        [
                            'ARTIFACTORY_DEV_REGISTRY',
                            'ARTIFACTORY_DEV_REPOSITORY',
                            'ARTIFACTORY_DEV_CREDENTIALS_ID',
                            'ARTIFACTORY_PROD_REGISTRY',
                            'ARTIFACTORY_PROD_REPOSITORY',
                            'ARTIFACTORY_PROD_CREDENTIALS_ID',
                            'VALUES_PATH',
                            'IMAGE_REPOSITORY_YQ_PATH',
                            'IMAGE_TAG_YQ_PATH'
                        ].each { requireParam(it) }

                        env.IMAGE_REPOSITORY_YQ_PATH = params.IMAGE_REPOSITORY_YQ_PATH.trim()
                        env.IMAGE_TAG_YQ_PATH = params.IMAGE_TAG_YQ_PATH.trim()
                        env.ARTIFACTORY_DEV_REGISTRY_CLEAN = cleanDockerPath(params.ARTIFACTORY_DEV_REGISTRY)
                        env.ARTIFACTORY_PROD_REGISTRY_CLEAN = cleanDockerPath(params.ARTIFACTORY_PROD_REGISTRY)
                        env.DEV_IMAGE_PREFIX = joinDockerPath([
                            params.ARTIFACTORY_DEV_REGISTRY,
                            params.ARTIFACTORY_DEV_REPOSITORY
                        ])
                        env.PROD_IMAGE_PREFIX = joinDockerPath([
                            params.ARTIFACTORY_PROD_REGISTRY,
                            params.ARTIFACTORY_PROD_REPOSITORY
                        ])
                    }

                    sh '''
                        set -eu
                        command -v docker >/dev/null
                        command -v yq >/dev/null
                        yq --version | grep -q 'version v4'
                        test -f "$VALUES_PATH"
                    '''
                }
            }

            stage('Read image and calculate prod image') {
                steps {
                    script {
                        env.DEV_IMAGE_REPOSITORY = sh(
                            returnStdout: true,
                            script: 'yq -r \'eval(strenv(IMAGE_REPOSITORY_YQ_PATH)) // ""\' "$VALUES_PATH"'
                        ).trim()
                        env.PROMOTED_IMAGE_TAG = sh(
                            returnStdout: true,
                            script: 'yq -r \'eval(strenv(IMAGE_TAG_YQ_PATH)) // ""\' "$VALUES_PATH"'
                        ).trim()

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
                    }
                }
            }

            stage('Promote Docker image to prod Artifactory') {
                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: params.ARTIFACTORY_DEV_CREDENTIALS_ID,
                            usernameVariable: 'ARTIFACTORY_DEV_USERNAME',
                            passwordVariable: 'ARTIFACTORY_DEV_PASSWORD'
                        ),
                        usernamePassword(
                            credentialsId: params.ARTIFACTORY_PROD_CREDENTIALS_ID,
                            usernameVariable: 'ARTIFACTORY_PROD_USERNAME',
                            passwordVariable: 'ARTIFACTORY_PROD_PASSWORD'
                        )
                    ]) {
                        sh '''
                            set -eu
                            printf '%s' "$ARTIFACTORY_PROD_PASSWORD" | docker login "$ARTIFACTORY_PROD_REGISTRY_CLEAN" \
                                --username "$ARTIFACTORY_PROD_USERNAME" \
                                --password-stdin

                            if docker manifest inspect "$PROD_IMAGE" >/dev/null 2>&1; then
                                echo "Image already exists in prod Artifactory and cannot be overwritten: $PROD_IMAGE" >&2
                                exit 1
                            fi

                            printf '%s' "$ARTIFACTORY_DEV_PASSWORD" | docker login "$ARTIFACTORY_DEV_REGISTRY_CLEAN" \
                                --username "$ARTIFACTORY_DEV_USERNAME" \
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

def joinDockerPath(List<String> parts) {
    return parts.collect { cleanDockerPath(it) }
        .findAll { it }
        .join('/')
}

def requireParam(String name) {
    if (!params[name]?.trim()) {
        error("Missing required parameter: ${name}")
    }
}
