def call(Map config = [:]) {
    def cfg = [
        imageVersion: '',
        imageName: '',
        dockerfilePath: 'Dockerfile',
        dockerBuildContext: '.',
        dockerBuildArgs: '',
        dockerBuildSecrets: '',
        dockerSecretTextCredentials: '',
        artifactoryDevRegistry: 'artifactory-dev.example.com',
        artifactoryDevRepository: 'docker-dev-local',
        artifactoryCredentialsId: 'artifactory-docker',
        deploymentRepoUrl: '',
        deploymentBranch: 'devel',
        valuesPath: 'values.yaml',
        imageRepositoryYqPath: '.image.repository',
        imageTagYqPath: '.image.tag',
        gitCredentialsId: 'deployment-git-ssh',
        deploymentGitSshHost: '',
        deploymentGitSshPort: '22',
        deploymentGitSshKeyscanTypes: 'rsa,ecdsa,ed25519',
        gitAuthorName: 'jenkins',
        gitAuthorEmail: 'jenkins@example.com'
    ] + config

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
        }

        parameters {
            string(name: 'IMAGE_VERSION', defaultValue: "${cfg.imageVersion}", description: 'Required image version. It is forwarded to Docker as build arg imageVersion and used as the image tag prefix.')
            string(name: 'IMAGE_NAME', defaultValue: "${cfg.imageName}", description: 'Docker image name, for example my-service.')
            string(name: 'DOCKERFILE_PATH', defaultValue: "${cfg.dockerfilePath}", description: 'Path to the Dockerfile in the application repository.')
            string(name: 'DOCKER_BUILD_CONTEXT', defaultValue: "${cfg.dockerBuildContext}", description: 'Docker build context.')
            text(name: 'DOCKER_BUILD_ARGS', defaultValue: "${cfg.dockerBuildArgs}", description: 'Optional non-secret Docker --build-arg entries, one per line. Example: NODE_ENV=production. imageVersion is injected automatically.')
            text(name: 'DOCKER_BUILD_SECRETS', defaultValue: "${cfg.dockerBuildSecrets}", description: 'Optional Docker BuildKit --secret entries, one per line. Example: id=npm_token,env=NPM_TOKEN or id=npmrc,src=.npmrc.')
            text(name: 'DOCKER_SECRET_TEXT_CREDENTIALS', defaultValue: "${cfg.dockerSecretTextCredentials}", description: 'Optional Jenkins secret text mappings, one per line: ENV_VAR=credential-id. Use with DOCKER_BUILD_SECRETS entries using env=ENV_VAR.')

            string(name: 'ARTIFACTORY_DEV_REGISTRY', defaultValue: "${cfg.artifactoryDevRegistry}", description: 'Dev Docker registry host, without protocol.')
            string(name: 'ARTIFACTORY_DEV_REPOSITORY', defaultValue: "${cfg.artifactoryDevRepository}", description: 'Dev Artifactory Docker repository.')

            string(name: 'DEPLOYMENT_REPO_URL', defaultValue: "${cfg.deploymentRepoUrl}", description: 'SSH URL of the ArgoCD/deployment Git repository.')
            string(name: 'DEPLOYMENT_BRANCH', defaultValue: "${cfg.deploymentBranch}", description: 'Deployment branch to update.')
            string(name: 'VALUES_PATH', defaultValue: "${cfg.valuesPath}", description: 'Relative path to the values file inside the deployment repository, for example helm/values.yaml.')
            string(name: 'IMAGE_REPOSITORY_YQ_PATH', defaultValue: "${cfg.imageRepositoryYqPath}", description: 'yq path to the image repository field.')
            string(name: 'IMAGE_TAG_YQ_PATH', defaultValue: "${cfg.imageTagYqPath}", description: 'yq path to the image tag field.')
            string(name: 'GIT_CREDENTIALS_ID', defaultValue: "${cfg.gitCredentialsId}", description: 'Jenkins SSH username/private key credentials for deployment Git clone and push.')
            string(name: 'DEPLOYMENT_GIT_SSH_HOST', defaultValue: "${cfg.deploymentGitSshHost}", description: 'Optional Git SSH host for ssh-keyscan. If empty, it is inferred from DEPLOYMENT_REPO_URL.')
            string(name: 'DEPLOYMENT_GIT_SSH_PORT', defaultValue: "${cfg.deploymentGitSshPort}", description: 'Git SSH port used by ssh-keyscan.')
            string(name: 'DEPLOYMENT_GIT_SSH_KEYSCAN_TYPES', defaultValue: "${cfg.deploymentGitSshKeyscanTypes}", description: 'Comma-separated ssh-keyscan key types.')

            string(name: 'GIT_AUTHOR_NAME', defaultValue: "${cfg.gitAuthorName}", description: 'Git author name used for deployment commits.')
            string(name: 'GIT_AUTHOR_EMAIL', defaultValue: "${cfg.gitAuthorEmail}", description: 'Git author email used for deployment commits.')
        }

        environment {
            DEPLOYMENT_WORKDIR = 'deployment-repo'
            ARTIFACTORY_CREDENTIALS = credentials("${cfg.artifactoryCredentialsId}")
        }

        stages {
            stage('Validate parameters and tools') {
                steps {
                    script {
                        [
                            'IMAGE_VERSION',
                            'IMAGE_NAME',
                            'DOCKERFILE_PATH',
                            'DOCKER_BUILD_CONTEXT',
                            'ARTIFACTORY_DEV_REGISTRY',
                            'ARTIFACTORY_DEV_REPOSITORY',
                            'DEPLOYMENT_REPO_URL',
                            'DEPLOYMENT_BRANCH',
                            'VALUES_PATH',
                            'IMAGE_REPOSITORY_YQ_PATH',
                            'IMAGE_TAG_YQ_PATH',
                            'GIT_CREDENTIALS_ID',
                            'DEPLOYMENT_GIT_SSH_PORT',
                            'DEPLOYMENT_GIT_SSH_KEYSCAN_TYPES'
                        ].each { requireParam(it) }

                        validateDockerBuildSecrets(params.DOCKER_BUILD_SECRETS)
                        validateDockerBuildArgs(params.DOCKER_BUILD_ARGS)
                        parseSecretTextCredentialBindings(params.DOCKER_SECRET_TEXT_CREDENTIALS)

                        env.IMAGE_VERSION = params.IMAGE_VERSION.trim()
                        env.IMAGE_TIMESTAMP = new Date(currentBuild.startTimeInMillis).format('yyyyMMddHHmmss')
                        env.IMAGE_TAG = "${env.IMAGE_VERSION}-${env.IMAGE_TIMESTAMP}"
                        env.IMAGE_NAME_CLEAN = cleanDockerPath(params.IMAGE_NAME)
                        env.IMAGE_REPOSITORY_YQ_PATH = params.IMAGE_REPOSITORY_YQ_PATH.trim()
                        env.IMAGE_TAG_YQ_PATH = params.IMAGE_TAG_YQ_PATH.trim()
                        env.DOCKER_BUILD_ARGS_EFFECTIVE = normalizeMultiline(params.DOCKER_BUILD_ARGS)
                        env.DOCKER_BUILD_SECRETS_EFFECTIVE = normalizeMultiline(params.DOCKER_BUILD_SECRETS)
                        env.ARTIFACTORY_DEV_REGISTRY_CLEAN = joinArtifactoryDockerPath([
                            params.ARTIFACTORY_DEV_REGISTRY,
                            params.ARTIFACTORY_DEV_REPOSITORY
                        ])
                        env.DEV_IMAGE_REPOSITORY = joinArtifactoryDockerPath([
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
                        command -v ssh >/dev/null
                        command -v ssh-keyscan >/dev/null
                        command -v yq >/dev/null
                        yq --version | grep -q 'version v4'
                        test -f "$DOCKERFILE_PATH"
                    '''
                }
            }

            stage('Check dev image does not exist') {
                steps {
                    sh '''
                        set -eu
                        printf '%s' "$ARTIFACTORY_CREDENTIALS_PSW" | docker login "$ARTIFACTORY_DEV_REGISTRY_CLEAN" \
                            --username "$ARTIFACTORY_CREDENTIALS_USR" \
                            --password-stdin

                        if docker manifest inspect "$DEV_IMAGE" >/dev/null 2>&1; then
                            echo "Image already exists in dev Artifactory and cannot be overwritten: $DEV_IMAGE" >&2
                            exit 1
                        fi
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
                                printf '%s\n' "$DOCKER_BUILD_ARGS_EFFECTIVE" > "$WORKSPACE/.docker-build-args"
                                printf '%s\n' "$DOCKER_BUILD_SECRETS_EFFECTIVE" > "$WORKSPACE/.docker-build-secrets"

                                while IFS= read -r docker_build_arg || [ -n "$docker_build_arg" ]; do
                                    [ -z "$docker_build_arg" ] && continue
                                    first_char="$(printf '%s' "$docker_build_arg" | cut -c 1)"
                                    [ "$first_char" = "#" ] && continue
                                    set -- "$@" --build-arg "$docker_build_arg"
                                done < "$WORKSPACE/.docker-build-args"

                                while IFS= read -r docker_secret || [ -n "$docker_secret" ]; do
                                    [ -z "$docker_secret" ] && continue
                                    first_char="$(printf '%s' "$docker_secret" | cut -c 1)"
                                    [ "$first_char" = "#" ] && continue
                                    set -- "$@" --secret "$docker_secret"
                                done < "$WORKSPACE/.docker-build-secrets"

                                DOCKER_BUILDKIT=1 docker build \
                                    "$@" \
                                    --build-arg "imageVersion=$IMAGE_VERSION" \
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
                    sh '''
                        set -eu
                        printf '%s' "$ARTIFACTORY_CREDENTIALS_PSW" | docker login "$ARTIFACTORY_DEV_REGISTRY_CLEAN" \
                            --username "$ARTIFACTORY_CREDENTIALS_USR" \
                            --password-stdin
                        docker push "$DEV_IMAGE"
                    '''
                }
            }

            stage('Update ArgoCD values') {
                steps {
                    withCredentials([
                        sshUserPrivateKey(
                            credentialsId: params.GIT_CREDENTIALS_ID,
                            keyFileVariable: 'GIT_SSH_KEY',
                            usernameVariable: 'GIT_SSH_USERNAME'
                        )
                    ]) {
                        sh '''
                            set -eu
                            rm -rf "$DEPLOYMENT_WORKDIR"
                            previous_core_ssh_command="$(git config --global --get core.sshCommand || true)"
                            restore_core_ssh_command() {
                                if [ -n "$previous_core_ssh_command" ]; then
                                    git config --global core.sshCommand "$previous_core_ssh_command"
                                else
                                    git config --global --unset core.sshCommand >/dev/null 2>&1 || true
                                fi
                            }
                            trap restore_core_ssh_command EXIT

                            git_ssh_host="$DEPLOYMENT_GIT_SSH_HOST"
                            git_ssh_port="$DEPLOYMENT_GIT_SSH_PORT"
                            if [ -z "$git_ssh_host" ]; then
                                case "$DEPLOYMENT_REPO_URL" in
                                    ssh://*)
                                        repo_without_scheme="${DEPLOYMENT_REPO_URL#ssh://}"
                                        repo_without_user="${repo_without_scheme#*@}"
                                        host_port="${repo_without_user%%/*}"
                                        git_ssh_host="${host_port%%:*}"
                                        case "$host_port" in
                                            *:*) git_ssh_port="${host_port##*:}" ;;
                                        esac
                                        ;;
                                    *@*:*)
                                        repo_without_user="${DEPLOYMENT_REPO_URL#*@}"
                                        git_ssh_host="${repo_without_user%%:*}"
                                        ;;
                                esac
                            fi
                            if [ -z "$git_ssh_host" ]; then
                                echo "Unable to infer Git SSH host. Set DEPLOYMENT_GIT_SSH_HOST." >&2
                                exit 1
                            fi

                            mkdir -p "$HOME/.ssh"
                            chmod 0700 "$HOME/.ssh"
                            touch "$HOME/.ssh/known_hosts"
                            chmod 0600 "$HOME/.ssh/known_hosts"
                            ssh-keyscan \
                                -p "$git_ssh_port" \
                                -t "$DEPLOYMENT_GIT_SSH_KEYSCAN_TYPES" \
                                "$git_ssh_host" >> "$HOME/.ssh/known_hosts"
                            sort -u "$HOME/.ssh/known_hosts" -o "$HOME/.ssh/known_hosts"

                            git config --global user.email "$GIT_AUTHOR_EMAIL"
                            git config --global user.name "$GIT_AUTHOR_NAME"
                            git config --global core.sshCommand "ssh -i '$GIT_SSH_KEY' -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile='$HOME/.ssh/known_hosts'"
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
                    rm -f "$WORKSPACE/.docker-build-args" "$WORKSPACE/.docker-build-secrets"
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

def validateDockerBuildArgs(String value) {
    (value ?: '').readLines().eachWithIndex { rawLine, index ->
        def line = rawLine.trim()
        if (!line || line.startsWith('#')) {
            return
        }

        def key = line.contains('=') ? line.substring(0, line.indexOf('=')) : line
        if (!(key ==~ /[A-Za-z_][A-Za-z0-9_]*/)) {
            error("DOCKER_BUILD_ARGS line ${index + 1} has an invalid build arg name: ${key}.")
        }
        if (key == 'imageVersion' || key == 'IMAGE_VERSION') {
            error('DOCKER_BUILD_ARGS must not define imageVersion because imageVersion is injected automatically from the Jenkins IMAGE_VERSION parameter.')
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
