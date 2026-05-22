def call(Map config = [:]) {
    def cfg = [
        versionDefault: '',
        imageNameDefault: '',
        dockerfilePathDefault: 'Dockerfile',
        dockerBuildContextDefault: '.',
        dockerBuildSecretsDefault: '',
        dockerSecretTextCredentialsDefault: '',
        artifactoryDevRegistryDefault: 'artifactory-dev.example.com',
        artifactoryDevRepositoryDefault: 'docker-dev-local',
        artifactoryDevCredentialsIdDefault: 'artifactory-dev-docker',
        deploymentRepoUrlDefault: '',
        deploymentBranchDefault: 'devel',
        valuesPathDefault: 'values.yaml',
        imageRepositoryYqPathDefault: '.image.repository',
        imageTagYqPathDefault: '.image.tag',
        bitbucketCredentialsIdDefault: 'bitbucket-git',
        gitAuthorNameDefault: 'jenkins',
        gitAuthorEmailDefault: 'jenkins@example.com'
    ] + config

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
        }

        parameters {
            string(name: 'VERSION', defaultValue: "${cfg.versionDefault}", description: 'Required Docker image tag to build and deploy to dev.')
            string(name: 'IMAGE_NAME', defaultValue: "${cfg.imageNameDefault}", description: 'Docker image name, for example my-service.')
            string(name: 'DOCKERFILE_PATH', defaultValue: "${cfg.dockerfilePathDefault}", description: 'Path to the Dockerfile in the application repository.')
            string(name: 'DOCKER_BUILD_CONTEXT', defaultValue: "${cfg.dockerBuildContextDefault}", description: 'Docker build context.')
            text(name: 'DOCKER_BUILD_SECRETS', defaultValue: "${cfg.dockerBuildSecretsDefault}", description: 'Optional Docker BuildKit --secret entries, one per line. Example: id=npm_token,env=NPM_TOKEN or id=npmrc,src=.npmrc.')
            text(name: 'DOCKER_SECRET_TEXT_CREDENTIALS', defaultValue: "${cfg.dockerSecretTextCredentialsDefault}", description: 'Optional Jenkins secret text mappings, one per line: ENV_VAR=credential-id. Use with DOCKER_BUILD_SECRETS entries using env=ENV_VAR.')

            string(name: 'ARTIFACTORY_DEV_REGISTRY', defaultValue: "${cfg.artifactoryDevRegistryDefault}", description: 'Dev Docker registry host, without protocol.')
            string(name: 'ARTIFACTORY_DEV_REPOSITORY', defaultValue: "${cfg.artifactoryDevRepositoryDefault}", description: 'Dev Artifactory Docker repository.')
            string(name: 'ARTIFACTORY_DEV_CREDENTIALS_ID', defaultValue: "${cfg.artifactoryDevCredentialsIdDefault}", description: 'Jenkins username/password credentials for dev Artifactory.')

            string(name: 'DEPLOYMENT_REPO_URL', defaultValue: "${cfg.deploymentRepoUrlDefault}", description: 'HTTPS URL of the ArgoCD/deployment Git repository.')
            string(name: 'DEPLOYMENT_BRANCH', defaultValue: "${cfg.deploymentBranchDefault}", description: 'Deployment branch to update.')
            string(name: 'VALUES_PATH', defaultValue: "${cfg.valuesPathDefault}", description: 'Relative path to the values file inside the deployment repository, for example helm/values.yaml.')
            string(name: 'IMAGE_REPOSITORY_YQ_PATH', defaultValue: "${cfg.imageRepositoryYqPathDefault}", description: 'yq path to the image repository field.')
            string(name: 'IMAGE_TAG_YQ_PATH', defaultValue: "${cfg.imageTagYqPathDefault}", description: 'yq path to the image tag field.')
            string(name: 'BITBUCKET_CREDENTIALS_ID', defaultValue: "${cfg.bitbucketCredentialsIdDefault}", description: 'Jenkins username/password credentials for Bitbucket Git push.')

            string(name: 'GIT_AUTHOR_NAME', defaultValue: "${cfg.gitAuthorNameDefault}", description: 'Git author name used for deployment commits.')
            string(name: 'GIT_AUTHOR_EMAIL', defaultValue: "${cfg.gitAuthorEmailDefault}", description: 'Git author email used for deployment commits.')
        }

        environment {
            DEPLOYMENT_WORKDIR = 'deployment-repo'
        }

        stages {
            stage('Validate parameters and tools') {
                steps {
                    script {
                        [
                            'VERSION',
                            'IMAGE_NAME',
                            'DOCKERFILE_PATH',
                            'DOCKER_BUILD_CONTEXT',
                            'ARTIFACTORY_DEV_REGISTRY',
                            'ARTIFACTORY_DEV_REPOSITORY',
                            'ARTIFACTORY_DEV_CREDENTIALS_ID',
                            'DEPLOYMENT_REPO_URL',
                            'DEPLOYMENT_BRANCH',
                            'VALUES_PATH',
                            'IMAGE_REPOSITORY_YQ_PATH',
                            'IMAGE_TAG_YQ_PATH',
                            'BITBUCKET_CREDENTIALS_ID'
                        ].each { requireParam(it) }

                        validateDockerBuildSecrets(params.DOCKER_BUILD_SECRETS)
                        parseSecretTextCredentialBindings(params.DOCKER_SECRET_TEXT_CREDENTIALS)

                        env.IMAGE_TAG = params.VERSION.trim()
                        env.IMAGE_NAME_CLEAN = cleanDockerPath(params.IMAGE_NAME)
                        env.IMAGE_REPOSITORY_YQ_PATH = params.IMAGE_REPOSITORY_YQ_PATH.trim()
                        env.IMAGE_TAG_YQ_PATH = params.IMAGE_TAG_YQ_PATH.trim()
                        env.DOCKER_BUILD_SECRETS_EFFECTIVE = normalizeMultiline(params.DOCKER_BUILD_SECRETS)
                        env.ARTIFACTORY_DEV_REGISTRY_CLEAN = cleanDockerPath(params.ARTIFACTORY_DEV_REGISTRY)
                        env.DEV_IMAGE_REPOSITORY = joinDockerPath([
                            params.ARTIFACTORY_DEV_REGISTRY,
                            params.ARTIFACTORY_DEV_REPOSITORY,
                            env.IMAGE_NAME_CLEAN
                        ])
                        env.DEV_IMAGE = "${env.DEV_IMAGE_REPOSITORY}:${env.IMAGE_TAG}"
                    }

                    sh '''
                        set -eu
                        command -v docker >/dev/null
                        command -v git >/dev/null
                        command -v yq >/dev/null
                        yq --version | grep -q 'version v4'
                        test -f "$DOCKERFILE_PATH"
                    '''
                }
            }

            stage('Build Docker image') {
                steps {
                    script {
                        def secretTextBindings = parseSecretTextCredentialBindings(params.DOCKER_SECRET_TEXT_CREDENTIALS)
                        def buildImage = {
                            sh '''
                                set -eu
                                set --
                                printf '%s\n' "$DOCKER_BUILD_SECRETS_EFFECTIVE" > "$WORKSPACE/.docker-build-secrets"

                                while IFS= read -r docker_secret || [ -n "$docker_secret" ]; do
                                    [ -z "$docker_secret" ] && continue
                                    first_char="$(printf '%s' "$docker_secret" | cut -c 1)"
                                    [ "$first_char" = "#" ] && continue
                                    set -- "$@" --secret "$docker_secret"
                                done < "$WORKSPACE/.docker-build-secrets"

                                DOCKER_BUILDKIT=1 docker build \
                                    "$@" \
                                    -f "$DOCKERFILE_PATH" \
                                    -t "$DEV_IMAGE" \
                                    "$DOCKER_BUILD_CONTEXT"
                            '''
                        }

                        if (secretTextBindings) {
                            withCredentials(secretTextBindings) {
                                buildImage()
                            }
                        } else {
                            buildImage()
                        }
                    }
                }
            }

            stage('Push image to dev Artifactory') {
                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: params.ARTIFACTORY_DEV_CREDENTIALS_ID,
                            usernameVariable: 'ARTIFACTORY_DEV_USERNAME',
                            passwordVariable: 'ARTIFACTORY_DEV_PASSWORD'
                        )
                    ]) {
                        sh '''
                            set -eu
                            printf '%s' "$ARTIFACTORY_DEV_PASSWORD" | docker login "$ARTIFACTORY_DEV_REGISTRY_CLEAN" \
                                --username "$ARTIFACTORY_DEV_USERNAME" \
                                --password-stdin
                            docker push "$DEV_IMAGE"
                        '''
                    }
                }
            }

            stage('Update ArgoCD values') {
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
                            rm -rf "$DEPLOYMENT_WORKDIR"

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
                                --branch "$DEPLOYMENT_BRANCH" \
                                --single-branch \
                                "$DEPLOYMENT_REPO_URL" \
                                "$DEPLOYMENT_WORKDIR"

                            cd "$DEPLOYMENT_WORKDIR"
                            test -f "$VALUES_PATH"

                            export IMAGE_REPOSITORY_VALUE="$DEV_IMAGE_REPOSITORY"
                            export IMAGE_TAG_VALUE="$IMAGE_TAG"
                            yq -i 'eval(strenv(IMAGE_REPOSITORY_YQ_PATH)) = strenv(IMAGE_REPOSITORY_VALUE) | eval(strenv(IMAGE_TAG_YQ_PATH)) = strenv(IMAGE_TAG_VALUE)' "$VALUES_PATH"

                            if git diff --quiet -- "$VALUES_PATH"; then
                                echo "No values change to commit."
                                exit 0
                            fi

                            git config user.name "$GIT_AUTHOR_NAME"
                            git config user.email "$GIT_AUTHOR_EMAIL"
                            git add "$VALUES_PATH"
                            git commit -m "Deploy ${IMAGE_NAME_CLEAN}:${IMAGE_TAG}"
                            git push origin "HEAD:${DEPLOYMENT_BRANCH}"
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
                    rm -f "$WORKSPACE/.git-askpass" "$WORKSPACE/.docker-build-secrets"
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

def normalizeMultiline(String value) {
    return (value ?: '').readLines()
        .collect { it.trim() }
        .findAll { it }
        .join('\n')
}

def validateDockerBuildSecrets(String value) {
    (value ?: '').readLines().eachWithIndex { rawLine, index ->
        def line = rawLine.trim()
        if (!line || line.startsWith('#')) {
            return
        }
        if (!line.contains('id=')) {
            error("DOCKER_BUILD_SECRETS line ${index + 1} must include id=.")
        }
        if (!line.contains(',env=') && !line.contains(',src=')) {
            error("DOCKER_BUILD_SECRETS line ${index + 1} must include either ,env= or ,src=.")
        }
    }
}

def parseSecretTextCredentialBindings(String value) {
    def bindings = []

    (value ?: '').readLines().eachWithIndex { rawLine, index ->
        def line = rawLine.trim()
        if (!line || line.startsWith('#')) {
            return
        }

        def separator = line.indexOf('=')
        if (separator < 0) {
            separator = line.indexOf(':')
        }
        if (separator <= 0 || separator == line.length() - 1) {
            error("DOCKER_SECRET_TEXT_CREDENTIALS line ${index + 1} must use ENV_VAR=credential-id.")
        }

        def variableName = line.substring(0, separator).trim()
        def credentialId = line.substring(separator + 1).trim()
        if (!(variableName ==~ /[A-Za-z_][A-Za-z0-9_]*/)) {
            error("DOCKER_SECRET_TEXT_CREDENTIALS line ${index + 1} has an invalid environment variable name: ${variableName}.")
        }
        if (!credentialId) {
            error("DOCKER_SECRET_TEXT_CREDENTIALS line ${index + 1} has an empty credential id.")
        }

        bindings << string(credentialsId: credentialId, variable: variableName)
    }

    return bindings
}
