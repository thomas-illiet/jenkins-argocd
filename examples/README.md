# Usage Examples

This directory contains copyable examples for the shared library.

## Layout

```text
examples
|-- application-repo
|   |-- Jenkinsfile
|   `-- Jenkinsfile.with-buildkit-secret
`-- deployment-repo
    |-- Jenkinsfile
    `-- helm
        `-- values.yaml
```

## Non-root values file

The deployment example intentionally stores Helm values under `helm/` instead of the repository root:

```text
helm/values.yaml
```

This is handled by:

```groovy
valuesPath: 'helm/values.yaml'
```

The image fields are also nested:

```yaml
apps:
  myService:
    image:
      repository: docker-dev-local.artifactory-dev.example.com/my-service
      tag: 1.2.3-20260522143015
```

The application pipeline updates both nested image fields:

```groovy
imageRepositoryYqPath: '.apps.myService.image.repository'
imageTagYqPath: '.apps.myService.image.tag'
```

The promotion pipeline only needs the image name and the tag field:

```groovy
imageName: 'my-service'
imageTagYqPath: '.apps.myService.image.tag'
```

## How to use

1. Copy `examples/application-repo/Jenkinsfile` into an application repository.
2. Copy `examples/deployment-repo/Jenkinsfile` into the ArgoCD deployment repository.
3. Keep or adapt the `helm/values.yaml` layout.
4. Replace placeholder registry, Git, and credential values with your real Jenkins setup. Both dev and prod Docker logins use the shared `artifactoryCredentialsId` configured in the Jenkinsfile.
5. Run the application build with an `IMAGE_VERSION`. The Docker build receives it as `--build-arg imageVersion`, while the image tag written to values is `IMAGE_VERSION-yyyyMMddHHmmss`.
6. Run the deployment promotion job from a workspace containing the deployment repository. The promotion uploads the configured image name with the tag from values to prod Artifactory and fails if that image already exists there.

## Non-secret Docker build args

Application examples include:

```groovy
dockerBuildArgs: '''
NODE_ENV=production
PUBLIC_PATH=/my-service
'''
```

Use this for non-sensitive values only. Use `DOCKER_BUILD_SECRETS` for tokens, passwords, or private files.
