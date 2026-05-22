# Usage Examples

This directory contains copyable examples for the shared library.

## Examples

```text
examples
|-- application-repo
|   |-- Jenkinsfile
|   `-- Jenkinsfile.with-buildkit-secret
`-- deployment-repo
    |-- Jenkinsfile
    `-- helm
        |-- values-dev.yaml
        `-- values-prod.yaml
```

## Non-root values files

The deployment example intentionally stores Helm values under `helm/` instead of the repository root:

```text
helm/values-dev.yaml
helm/values-prod.yaml
```

This is handled by:

```groovy
valuesDevPathDefault: 'helm/values-dev.yaml'
valuesProdPathDefault: 'helm/values-prod.yaml'
```

The image fields are also nested:

```yaml
apps:
  myService:
    image:
      repository: artifactory-dev.example.com/docker-dev-local/my-service
      tag: 1.2.3
```

This is handled by:

```groovy
devImageRepositoryYqPathDefault: '.apps.myService.image.repository'
devImageTagYqPathDefault: '.apps.myService.image.tag'
prodImageRepositoryYqPathDefault: '.apps.myService.image.repository'
prodImageTagYqPathDefault: '.apps.myService.image.tag'
```

## How to use

1. Copy `examples/application-repo/Jenkinsfile` into an application repository.
2. Copy `examples/deployment-repo/Jenkinsfile` into the ArgoCD deployment repository.
3. Keep or adapt the `helm/values-*.yaml` layout.
4. Replace placeholder registry, Bitbucket, and credential values with your real Jenkins setup.
5. Run the application build with a `VERSION`, then open a Bitbucket PR from `devel` to `main` in the deployment repository.
