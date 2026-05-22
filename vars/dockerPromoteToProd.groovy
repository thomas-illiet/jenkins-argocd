def call(Map config = [:]) {
    def cfg = [
        artifactoryDevRegistry: 'artifactory-dev.example.com',
        artifactoryDevRepository: 'docker-dev-local',
        artifactoryCredentialsId: 'artifactory-docker',
        artifactoryProdRegistry: 'artifactory-prod.example.com',
        artifactoryProdRepository: 'docker-prod-local',
        imageName: '',
        valuesPath: 'values.yaml',
        imageTagYqPath: '.image.tag'
    ] + config
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

            string(name: 'IMAGE_NAME', defaultValue: "${cfg.imageName}", description: 'Docker image name, for example my-service.')
            string(name: 'VALUES_PATH', defaultValue: "${cfg.valuesPath}", description: 'Relative path to the values file in the checked-out deployment repository, for example helm/values.yaml.')
            string(name: 'IMAGE_TAG_YQ_PATH', defaultValue: "${cfg.imageTagYqPath}", description: 'yq path to the image tag field in the values file.')
        }

        environment {
            ARTIFACTORY_CREDENTIALS = credentials("${cfg.artifactoryCredentialsId}")
            ARTIFACTORY_DEV_REGISTRY = "${params.ARTIFACTORY_DEV_REGISTRY.trim()}"
            ARTIFACTORY_DEV_REPOSITORY = "${params.ARTIFACTORY_DEV_REPOSITORY.trim()}"
            ARTIFACTORY_PROD_REGISTRY = "${params.ARTIFACTORY_PROD_REGISTRY.trim()}"
            ARTIFACTORY_PROD_REPOSITORY = "${params.ARTIFACTORY_PROD_REPOSITORY.trim()}"
            IMAGE_NAME_CLEAN = "${cleanDockerPath(params.IMAGE_NAME)}"
            VALUES_PATH = "${params.VALUES_PATH.trim()}"
            IMAGE_TAG_YQ_PATH = "${params.IMAGE_TAG_YQ_PATH.trim()}"
            ARTIFACTORY_DEV_REGISTRY_CLEAN = "${joinArtifactoryDockerPath([params.ARTIFACTORY_DEV_REGISTRY, params.ARTIFACTORY_DEV_REPOSITORY])}"
            ARTIFACTORY_PROD_REGISTRY_CLEAN = "${joinArtifactoryDockerPath([params.ARTIFACTORY_PROD_REGISTRY, params.ARTIFACTORY_PROD_REPOSITORY])}"
            DEV_IMAGE_PREFIX = "${joinArtifactoryDockerPath([params.ARTIFACTORY_DEV_REGISTRY, params.ARTIFACTORY_DEV_REPOSITORY])}"
            PROD_IMAGE_PREFIX = "${joinArtifactoryDockerPath([params.ARTIFACTORY_PROD_REGISTRY, params.ARTIFACTORY_PROD_REPOSITORY])}"
        }

        stages {
            stage('Promote Docker image to prod Artifactory') {
                steps {
                    script {
                        env.PROMOTED_IMAGE_TAG = sh(
                            returnStdout: true,
                            script: 'yq -r \'eval(strenv(IMAGE_TAG_YQ_PATH)) // ""\' "$VALUES_PATH"'
                        ).trim()

                        if (!env.IMAGE_NAME_CLEAN || env.IMAGE_NAME_CLEAN == 'null') {
                            error('Missing required parameter: IMAGE_NAME')
                        }
                        if (!env.PROMOTED_IMAGE_TAG || env.PROMOTED_IMAGE_TAG == 'null') {
                            error("Missing value at ${env.IMAGE_TAG_YQ_PATH} in ${params.VALUES_PATH}")
                        }

                        env.DEV_IMAGE_REPOSITORY = "${env.DEV_IMAGE_PREFIX}/${env.IMAGE_NAME_CLEAN}"
                        env.PROD_IMAGE_REPOSITORY = "${env.PROD_IMAGE_PREFIX}/${env.IMAGE_NAME_CLEAN}"
                        env.DEV_IMAGE = "${env.DEV_IMAGE_REPOSITORY}:${env.PROMOTED_IMAGE_TAG}"
                        env.PROD_IMAGE = "${env.PROD_IMAGE_REPOSITORY}:${env.PROMOTED_IMAGE_TAG}"

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
