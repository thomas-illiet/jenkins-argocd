# Jenkins Shared Library for Docker, Artifactory, and ArgoCD

This repository provides a Jenkins Shared Library plus two thin Jenkinsfile examples for a Docker delivery flow:

1. Build a Docker image from an application repository.
2. Push the image to the dev Artifactory Docker repository.
3. Update the ArgoCD deployment repository on the `devel` branch.
4. Promote the Docker image from dev Artifactory to prod Artifactory when a Bitbucket Data Center PR from `devel` to `main` is approved.
5. Update production Helm values before the PR is merged into `main`.

## Repository Layout

```text
.
|-- Jenkinsfile.app
|-- Jenkinsfile.deployment
`-- vars
    |-- dockerBuildAndDeployToDev.groovy
    |-- dockerBuildAndDeployToDev.txt
    |-- dockerPromoteToProd.groovy
    `-- dockerPromoteToProd.txt
```

## Jenkinsfiles

The Jenkinsfiles are intentionally small. They only load the shared library and call a reusable pipeline.

Application repository:

```groovy
@Library('ci-shared-library') _

dockerBuildAndDeployToDev()
```

Deployment repository:

```groovy
@Library('ci-shared-library') _

dockerPromoteToProd()
```

Replace `ci-shared-library` with the name configured in Jenkins under **Manage Jenkins > System > Global Trusted Pipeline Libraries** or your folder-level shared library configuration.

## Execution Flow

```mermaid
flowchart TD
    A["Application repo: build with VERSION"] --> B["dockerBuildAndDeployToDev"]

    subgraph APP["Application Pipeline"]
        B --> C["Validate parameters and tools"]
        C --> D["docker build with optional BuildKit secrets"]
        D --> E["docker login to dev Artifactory"]
        E --> F["docker push image:VERSION to dev"]
        F --> G["Clone deployment repo on devel"]
        G --> H["Update values-dev.yaml"]
        H --> I["Commit and push to devel if changed"]
    end

    I --> J["ArgoCD detects values-dev.yaml"]
    J --> K["Deploy to dev"]

    I --> L["Deployment PR: devel to main"]
    L --> M["dockerPromoteToProd"]

    subgraph DEPLOY["Deployment / Promotion Pipeline"]
        M --> N["Validate PR source=devel target=main"]
        N --> O["Call Bitbucket Data Center API"]
        O --> P{"At least 1 approval?"}
        P -->|No| Q["Stop: promotion refused"]
        P -->|Yes| R["Read values-dev.yaml"]
        R --> S["Compute dev image and prod image"]
        S --> T["docker login to dev and prod"]
        T --> U["docker pull dev image"]
        U --> V["docker tag prod image"]
        V --> W["docker push prod image"]
        W --> X["Update values-prod.yaml"]
        X --> Y["Commit and push to devel if changed"]
    end

    Y --> Z["PR contains production values"]
    Z --> AA["Merge PR to main"]
    AA --> AB["ArgoCD prod detects main"]
    AB --> AC["Deploy to prod"]
```

## Jenkins Agent Requirements

The Jenkins agents running these pipelines must provide:

- `docker`
- `git`
- `curl`
- `jq`
- `yq v4`

The application pipeline also enables Docker BuildKit during the image build:

```sh
DOCKER_BUILDKIT=1 docker build ...
```

## Jenkins Credentials

The pipelines expect Jenkins `username/password` credentials for Git, Bitbucket, and Artifactory:

| Credential parameter | Purpose |
| --- | --- |
| `ARTIFACTORY_DEV_CREDENTIALS_ID` | Docker login to the dev Artifactory repository. |
| `ARTIFACTORY_PROD_CREDENTIALS_ID` | Docker login to the prod Artifactory repository. |
| `BITBUCKET_CREDENTIALS_ID` | Git clone/push and Bitbucket Data Center API calls. |

The application pipeline can also inject Jenkins `Secret text` credentials into Docker BuildKit secrets:

| Parameter | Purpose |
| --- | --- |
| `DOCKER_SECRET_TEXT_CREDENTIALS` | Maps Jenkins secret text credentials to environment variables. |
| `DOCKER_BUILD_SECRETS` | Passes those variables or files to `docker build --secret`. |

## Helm Values Convention

The pipelines update YAML files using this shape:

```yaml
image:
  repository: artifactory.example.com/docker-repo/my-service
  tag: 1.2.3
```

Defaults:

- `values-dev.yaml` is used for dev.
- `values-prod.yaml` is used for prod.

Both paths are configurable with `VALUES_DEV_PATH` and `VALUES_PROD_PATH`.

## Shared Library API

### `dockerBuildAndDeployToDev(Map config = [:])`

Runs the application pipeline.

Main behavior:

- validates required parameters;
- builds the Docker image;
- supports optional Docker BuildKit `--secret` entries;
- pushes the image to dev Artifactory;
- clones the deployment repository on `devel`;
- updates `values-dev.yaml`;
- commits and pushes only when the values file changes.

Example with defaults:

```groovy
@Library('ci-shared-library') _

dockerBuildAndDeployToDev()
```

Example with per-repository defaults:

```groovy
@Library('ci-shared-library') _

dockerBuildAndDeployToDev(
    imageNameDefault: 'my-service',
    deploymentRepoUrlDefault: 'https://bitbucket.example.com/scm/platform/deployment.git',
    artifactoryDevRegistryDefault: 'artifactory-dev.example.com',
    artifactoryDevRepositoryDefault: 'docker-dev-local',
    dockerBuildSecretsDefault: '''
id=npm_token,env=NPM_TOKEN
''',
    dockerSecretTextCredentialsDefault: '''
NPM_TOKEN=npm-token-credential-id
'''
)
```

### `dockerPromoteToProd(Map config = [:])`

Runs the deployment promotion pipeline.

Main behavior:

- validates that the PR source branch is `devel`;
- validates that the PR target branch is `main`;
- checks Bitbucket Data Center for at least one approval;
- reads `image.repository` and `image.tag` from `values-dev.yaml`;
- promotes the image with `docker pull`, `docker tag`, and `docker push`;
- updates `values-prod.yaml`;
- commits and pushes only when the production values file changes.

Example:

```groovy
@Library('ci-shared-library') _

dockerPromoteToProd(
    bitbucketBaseUrlDefault: 'https://bitbucket.example.com',
    bitbucketProjectKeyDefault: 'PLATFORM',
    bitbucketRepoSlugDefault: 'deployment',
    artifactoryDevRegistryDefault: 'artifactory-dev.example.com',
    artifactoryDevRepositoryDefault: 'docker-dev-local',
    artifactoryProdRegistryDefault: 'artifactory-prod.example.com',
    artifactoryProdRepositoryDefault: 'docker-prod-local'
)
```

## Application Pipeline Parameters

| Parameter | Description |
| --- | --- |
| `VERSION` | Required Docker image tag. |
| `IMAGE_NAME` | Docker image name, for example `my-service`. |
| `DOCKERFILE_PATH` | Dockerfile path. Default: `Dockerfile`. |
| `DOCKER_BUILD_CONTEXT` | Docker build context. Default: `.`. |
| `DOCKER_BUILD_SECRETS` | Optional Docker BuildKit `--secret` entries, one per line. |
| `DOCKER_SECRET_TEXT_CREDENTIALS` | Optional Jenkins secret text mappings, one per line. |
| `ARTIFACTORY_DEV_REGISTRY` | Dev Docker registry host, without protocol. |
| `ARTIFACTORY_DEV_REPOSITORY` | Dev Artifactory Docker repository. |
| `ARTIFACTORY_DEV_CREDENTIALS_ID` | Jenkins credentials for dev Artifactory. |
| `DEPLOYMENT_REPO_URL` | HTTPS URL of the ArgoCD deployment repository. |
| `DEPLOYMENT_BRANCH` | Deployment branch to update. Default: `devel`. |
| `VALUES_DEV_PATH` | Path to the dev values file. Default: `values-dev.yaml`. |
| `BITBUCKET_CREDENTIALS_ID` | Jenkins credentials for Git/Bitbucket. |

## Docker BuildKit Secrets

Use Docker BuildKit secrets when the Docker build needs sensitive values, such as npm tokens, Maven settings, private package registry tokens, or license keys.

### Secret text credential example

In Jenkins, create a `Secret text` credential:

```text
ID: npm-token-credential-id
Secret: <your npm token>
```

Configure the build parameters:

```text
DOCKER_SECRET_TEXT_CREDENTIALS:
NPM_TOKEN=npm-token-credential-id

DOCKER_BUILD_SECRETS:
id=npm_token,env=NPM_TOKEN
```

The shared library will run Docker with:

```sh
DOCKER_BUILDKIT=1 docker build --secret id=npm_token,env=NPM_TOKEN ...
```

Inside the Dockerfile:

```dockerfile
# syntax=docker/dockerfile:1.4
RUN --mount=type=secret,id=npm_token \
    NPM_TOKEN="$(cat /run/secrets/npm_token)" && \
    npm config set //registry.npmjs.org/:_authToken "$NPM_TOKEN" && \
    npm ci
```

### File secret example

If the Jenkins workspace already contains a secret file generated by another step, pass it directly:

```text
DOCKER_BUILD_SECRETS:
id=maven_settings,src=.jenkins/settings.xml
```

Inside the Dockerfile:

```dockerfile
# syntax=docker/dockerfile:1.4
RUN --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    mvn -B package
```

Do not use Docker build arguments for sensitive values. Build args are easier to leak through image metadata, logs, or layer history. BuildKit secrets are mounted only for the `RUN` instruction that requests them.

## Deployment Pipeline Parameters

| Parameter | Description |
| --- | --- |
| `SOURCE_BRANCH` | Allowed PR source branch. Default: `devel`. |
| `TARGET_BRANCH` | Allowed PR target branch. Default: `main`. |
| `BITBUCKET_BASE_URL` | Bitbucket Data Center base URL. |
| `BITBUCKET_PROJECT_KEY` | Bitbucket project key. |
| `BITBUCKET_REPO_SLUG` | Bitbucket repository slug. |
| `BITBUCKET_PR_ID` | Optional PR id. If empty, Jenkins uses `CHANGE_ID`. |
| `BITBUCKET_CREDENTIALS_ID` | Jenkins credentials for Bitbucket API and Git push. |
| `DEPLOYMENT_REPO_URL` | Optional HTTPS repo URL. If empty, current `origin` is used. |
| `ARTIFACTORY_DEV_REGISTRY` | Dev Docker registry host. |
| `ARTIFACTORY_DEV_REPOSITORY` | Dev Artifactory Docker repository. |
| `ARTIFACTORY_DEV_CREDENTIALS_ID` | Jenkins credentials for dev Artifactory. |
| `ARTIFACTORY_PROD_REGISTRY` | Prod Docker registry host. |
| `ARTIFACTORY_PROD_REPOSITORY` | Prod Artifactory Docker repository. |
| `ARTIFACTORY_PROD_CREDENTIALS_ID` | Jenkins credentials for prod Artifactory. |
| `VALUES_DEV_PATH` | Path to the dev values file. |
| `VALUES_PROD_PATH` | Path to the prod values file. |

## Promotion Rules

The production promotion is refused when:

- the PR source branch is not `devel`;
- the PR target branch is not `main`;
- the PR has no approval in Bitbucket Data Center;
- `values-dev.yaml` does not contain `image.repository` or `image.tag`;
- the dev image repository does not start with the expected dev Artifactory prefix.

The promotion copies the image to prod Artifactory. It does not delete the image from dev Artifactory.

## Recommended Tests

### Application pipeline

Run a build with:

```text
VERSION=1.2.3-test
IMAGE_NAME=my-service
```

Check that:

- the image exists in dev Artifactory;
- `values-dev.yaml` contains the expected `image.repository`;
- `values-dev.yaml` contains the expected `image.tag`;
- rerunning the same version does not create a useless Git commit.

### Application pipeline with BuildKit secret

Run a build with:

```text
DOCKER_SECRET_TEXT_CREDENTIALS:
NPM_TOKEN=npm-token-credential-id

DOCKER_BUILD_SECRETS:
id=npm_token,env=NPM_TOKEN
```

Check that:

- the Docker build can access the secret during the mounted `RUN` step;
- the secret value is not printed in Jenkins logs;
- the secret value is not present in the final image layers.

### Deployment pipeline without approval

Create a PR from `devel` to `main` without approval.

Check that:

- the pipeline stops before promotion;
- no image is pushed to prod Artifactory;
- `values-prod.yaml` is not modified.

### Deployment pipeline with approval

Approve the PR and rerun the pipeline.

Check that:

- the image is copied from dev Artifactory to prod Artifactory;
- `values-prod.yaml` contains the prod repository;
- `values-prod.yaml` contains the tag read from `values-dev.yaml`;
- rerunning the same promotion does not create a useless Git commit.
