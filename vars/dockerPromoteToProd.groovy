def call(Map config = [:]) {
    def cfg = [
        sourceBranchDefault: 'devel',
        targetBranchDefault: 'main',
        bitbucketBaseUrlDefault: 'https://bitbucket.example.com',
        bitbucketProjectKeyDefault: '',
        bitbucketRepoSlugDefault: '',
        bitbucketPrIdDefault: '',
        bitbucketCredentialsIdDefault: 'bitbucket-git',
        deploymentRepoUrlDefault: '',
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
            string(name: 'SOURCE_BRANCH', defaultValue: "${cfg.sourceBranchDefault}", description: 'Allowed PR source branch.')
            string(name: 'TARGET_BRANCH', defaultValue: "${cfg.targetBranchDefault}", description: 'Allowed PR target branch.')
            string(name: 'BITBUCKET_BASE_URL', defaultValue: "${cfg.bitbucketBaseUrlDefault}", description: 'Bitbucket Data Center base URL.')
            string(name: 'BITBUCKET_PROJECT_KEY', defaultValue: "${cfg.bitbucketProjectKeyDefault}", description: 'Bitbucket project key.')
            string(name: 'BITBUCKET_REPO_SLUG', defaultValue: "${cfg.bitbucketRepoSlugDefault}", description: 'Bitbucket repository slug.')
            string(name: 'BITBUCKET_PR_ID', defaultValue: "${cfg.bitbucketPrIdDefault}", description: 'Optional PR id. If empty, Jenkins CHANGE_ID is used.')
            string(name: 'BITBUCKET_CREDENTIALS_ID', defaultValue: "${cfg.bitbucketCredentialsIdDefault}", description: 'Jenkins username/password credentials for Bitbucket API and Git push.')
            string(name: 'DEPLOYMENT_REPO_URL', defaultValue: "${cfg.deploymentRepoUrlDefault}", description: 'Optional HTTPS repo URL. If empty, the pipeline uses current origin.')

            string(name: 'ARTIFACTORY_DEV_REGISTRY', defaultValue: "${cfg.artifactoryDevRegistryDefault}", description: 'Dev Docker registry host, without protocol.')
            string(name: 'ARTIFACTORY_DEV_REPOSITORY', defaultValue: "${cfg.artifactoryDevRepositoryDefault}", description: 'Dev Artifactory Docker repository.')
            string(name: 'ARTIFACTORY_DEV_CREDENTIALS_ID', defaultValue: "${cfg.artifactoryDevCredentialsIdDefault}", description: 'Jenkins username/password credentials for dev Artifactory.')
            string(name: 'ARTIFACTORY_PROD_REGISTRY', defaultValue: "${cfg.artifactoryProdRegistryDefault}", description: 'Production Docker registry host, without protocol.')
            string(name: 'ARTIFACTORY_PROD_REPOSITORY', defaultValue: "${cfg.artifactoryProdRepositoryDefault}", description: 'Production Artifactory Docker repository.')
            string(name: 'ARTIFACTORY_PROD_CREDENTIALS_ID', defaultValue: "${cfg.artifactoryProdCredentialsIdDefault}", description: 'Jenkins username/password credentials for prod Artifactory.')

            string(name: 'VALUES_PATH', defaultValue: "${cfg.valuesPathDefault}", description: 'Relative path to the values file, for example helm/values.yaml.')
            string(name: 'IMAGE_REPOSITORY_YQ_PATH', defaultValue: "${cfg.imageRepositoryYqPathDefault}", description: 'yq path to the image repository field in the values file.')
            string(name: 'IMAGE_TAG_YQ_PATH', defaultValue: "${cfg.imageTagYqPathDefault}", description: 'yq path to the image tag field in the values file.')
        }

        environment {
            PROMOTION_WORKDIR = 'deployment-promotion-repo'
        }

        stages {
            stage('Validate parameters, tools, and PR approval') {
                steps {
                    script {
                        [
                            'SOURCE_BRANCH',
                            'TARGET_BRANCH',
                            'BITBUCKET_BASE_URL',
                            'BITBUCKET_PROJECT_KEY',
                            'BITBUCKET_REPO_SLUG',
                            'BITBUCKET_CREDENTIALS_ID',
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

                        def prId = params.BITBUCKET_PR_ID?.trim() ?: env.CHANGE_ID?.trim()
                        if (!prId) {
                            error('Missing PR id: set BITBUCKET_PR_ID or run this from a Jenkins PR build exposing CHANGE_ID.')
                        }
                        env.PR_ID = prId

                        env.CHANGE_SOURCE_BRANCH = (env.CHANGE_BRANCH ?: params.SOURCE_BRANCH).trim()
                        env.CHANGE_TARGET_BRANCH = (env.CHANGE_TARGET ?: params.TARGET_BRANCH).trim()
                        if (env.CHANGE_SOURCE_BRANCH != params.SOURCE_BRANCH.trim() || env.CHANGE_TARGET_BRANCH != params.TARGET_BRANCH.trim()) {
                            error("This promotion only runs for PRs ${params.SOURCE_BRANCH} -> ${params.TARGET_BRANCH}. Current PR is ${env.CHANGE_SOURCE_BRANCH} -> ${env.CHANGE_TARGET_BRANCH}.")
                        }

                        env.BITBUCKET_BASE_URL_CLEAN = params.BITBUCKET_BASE_URL.trim().replaceAll(/\/+$/, '')
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
                        command -v git >/dev/null
                        command -v curl >/dev/null
                        command -v jq >/dev/null
                        command -v yq >/dev/null
                        yq --version | grep -q 'version v4'
                    '''

                    withCredentials([
                        usernamePassword(
                            credentialsId: params.BITBUCKET_CREDENTIALS_ID,
                            usernameVariable: 'BITBUCKET_USERNAME',
                            passwordVariable: 'BITBUCKET_PASSWORD'
                        )
                    ]) {
                        sh '''
                            set -eu
                            api_url="${BITBUCKET_BASE_URL_CLEAN}/rest/api/1.0/projects/${BITBUCKET_PROJECT_KEY}/repos/${BITBUCKET_REPO_SLUG}/pull-requests/${PR_ID}"
                            curl -fsS -u "${BITBUCKET_USERNAME}:${BITBUCKET_PASSWORD}" "$api_url" -o bitbucket-pr.json

                            api_source="$(jq -r '.fromRef.displayId // .fromRef.id // ""' bitbucket-pr.json | sed 's#^refs/heads/##')"
                            api_target="$(jq -r '.toRef.displayId // .toRef.id // ""' bitbucket-pr.json | sed 's#^refs/heads/##')"
                            approval_count="$(jq '[((.reviewers // []) + (.participants // []))[] | select((.approved == true) or ((.status // "") | ascii_upcase == "APPROVED"))] | length' bitbucket-pr.json)"

                            if [ "$api_source" != "$SOURCE_BRANCH" ] || [ "$api_target" != "$TARGET_BRANCH" ]; then
                                echo "Bitbucket PR must be ${SOURCE_BRANCH} -> ${TARGET_BRANCH}; got ${api_source} -> ${api_target}." >&2
                                exit 1
                            fi

                            if [ "$approval_count" -lt 1 ]; then
                                echo "PR #${PR_ID} has no approval yet; promotion is refused." >&2
                                exit 1
                            fi
                        '''
                    }
                }
            }

            stage('Checkout source branch') {
                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: params.BITBUCKET_CREDENTIALS_ID,
                            usernameVariable: 'GIT_USERNAME',
                            passwordVariable: 'GIT_PASSWORD'
                        )
                    ]) {
                        sh '''
                            set -eu
                            rm -rf "$PROMOTION_WORKDIR"

                            repo_url="$DEPLOYMENT_REPO_URL"
                            if [ -z "$repo_url" ]; then
                                repo_url="$(git config --get remote.origin.url || true)"
                            fi
                            if [ -z "$repo_url" ]; then
                                echo "Unable to determine deployment repository URL." >&2
                                exit 1
                            fi

                            cat > "$WORKSPACE/.git-askpass" <<'EOF'
#!/bin/sh
case "$1" in
    *Username*|*username*) printf '%s\n' "$GIT_USERNAME" ;;
    *) printf '%s\n' "$GIT_PASSWORD" ;;
esac
EOF
                            chmod 700 "$WORKSPACE/.git-askpass"
                            export GIT_ASKPASS="$WORKSPACE/.git-askpass"
                            export GIT_TERMINAL_PROMPT=0

                            git clone \
                                --branch "$SOURCE_BRANCH" \
                                --single-branch \
                                "$repo_url" \
                                "$PROMOTION_WORKDIR"
                        '''
                    }
                }
            }

            stage('Read image and calculate prod image') {
                steps {
                    dir("${env.PROMOTION_WORKDIR}") {
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
                            printf '%s' "$ARTIFACTORY_DEV_PASSWORD" | docker login "$ARTIFACTORY_DEV_REGISTRY_CLEAN" \
                                --username "$ARTIFACTORY_DEV_USERNAME" \
                                --password-stdin
                            printf '%s' "$ARTIFACTORY_PROD_PASSWORD" | docker login "$ARTIFACTORY_PROD_REGISTRY_CLEAN" \
                                --username "$ARTIFACTORY_PROD_USERNAME" \
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
                    if [ -n "${ARTIFACTORY_DEV_REGISTRY_CLEAN:-}" ]; then
                        docker logout "$ARTIFACTORY_DEV_REGISTRY_CLEAN" >/dev/null 2>&1
                    fi
                    if [ -n "${ARTIFACTORY_PROD_REGISTRY_CLEAN:-}" ]; then
                        docker logout "$ARTIFACTORY_PROD_REGISTRY_CLEAN" >/dev/null 2>&1
                    fi
                    rm -f "$WORKSPACE/.git-askpass" bitbucket-pr.json
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
