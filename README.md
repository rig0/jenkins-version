<div align="center">

# Jenkins Version Management Library

![CI/CD](https://img.shields.io/badge/CI%2FCD-239120?logo=gitlab&logoColor=white)
![Jenkins](https://img.shields.io/badge/Jenkins-D24939?logo=jenkins&logoColor=white)
![Groovy](https://img.shields.io/badge/Groovy-5a92a7?logo=apachegroovy&logoColor=white)

![Release](https://img.shields.io/github/v/release/rig0/jenkins-pushover?labelColor=222&color=80ff63)
![Stability](https://img.shields.io/badge/stability-stable-80ff63?labelColor=222)
![Maintained](https://img.shields.io/badge/maintained-yes-80ff63?labelColor=222)
![GitHub last commit](https://img.shields.io/github/last-commit/rig0/jenkins-pushover?labelColor=222&color=80ff63)


**A Jenkins shared library for automated semantic versioning in CI/CD pipelines. This library provides intelligent version bumping based on commit messages, bot loop prevention, and comprehensive Git operations for version management workflows.**

</div>

## Features

- **Semantic Versioning**: Full support for major.minor.patch versioning
- **Commit Message Hints**: Automatic version bumping based on `bump-major`, `bump-minor`, `bump-patch` keywords
- **Bot Loop Prevention**: Prevents infinite build loops from automated commits
- **Build Metadata**: Tracks which Jenkins build produced each version (X.Y.Z+build.N)
- **GiteaSCM Integration**: Native Gitea support with automatic Git fallback
- **Automated Tagging**: Creates annotated Git tags for releases
- **Flexible Configuration**: Use global parameters or instance-based configuration
- **Security First**: Secure credential handling with automatic URL sanitization
- **Comprehensive Logging**: Emoji-enhanced logs for easy troubleshooting

## Table of Contents

- [Installation](#installation)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [API Reference](#api-reference)
- [Complete Examples](#complete-examples)
- [Best Practices](#best-practices)

## Installation

### Method 1: Jenkins Shared Library (Recommended)

1. In Jenkins, go to **Manage Jenkins** → **Configure System**
2. Scroll to **Global Pipeline Libraries**
3. Add a new library with:
   - **Name**: `jenkins-version`
   - **Default version**: `main` (or your preferred branch)
   - **Retrieval method**: Modern SCM
   - **Source Code Management**: Git
   - **Project Repository**: https://github.com/rig0/jenkins-version
4. Optionally check **Load implicitly** to make it available to all pipelines

### Method 2: Direct Repository Reference

In your Jenkinsfile:

```groovy
library identifier: 'jenkins-version@main',
        retriever: modernSCM([
          $class: 'GitSCMSource',
          remote: 'https://github.com/rig0/jenkins-version'
        ])
```

## Prerequisites

- Jenkins with Pipeline support
- Git installed on Jenkins agents
- A VERSION file in your repository root (format: `X.Y.Z`)
- Jenkins credentials configured for Git operations

### Required Tools

- `git` command-line tool
- `bash` shell (for script execution)

## Quick Start

### 1. Create VERSION File

In your repository root, create a `VERSION` file:

```
0.1.0
```

### 2. Basic Pipeline

```groovy
@Library('jenkins-version') _

pipeline {
  agent any

  parameters {
    string(name: 'REPO_URL', defaultValue: 'https://gitea.example.com/owner/repo.git')
    string(name: 'DEFAULT_BRANCH', defaultValue: 'main')
    string(name: 'BOT_EMAIL', defaultValue: 'jenkins-bot@example.com')
    string(name: 'BOT_NAME', defaultValue: 'jenkins-bot')
  }

  environment {
    REPO_URL = "${params.REPO_URL}"
    DEFAULT_BRANCH = "${params.DEFAULT_BRANCH}"
    BOT_EMAIL = "${params.BOT_EMAIL}"
    BOT_NAME = "${params.BOT_NAME}"
    GIT_CREDENTIALS_ID = 'GITEA_TOKEN'
  }

  stages {
    stage('Checkout') {
      steps {
        script {
          versionLib.checkoutRepository()
        }
      }
    }

    stage('Version Management') {
      steps {
        script {
          // Check if triggered by bot to prevent loops
          if (versionLib.isBotTriggered()) {
            echo "Skipping - build triggered by bot"
            currentBuild.result = 'SUCCESS'
            return
          }

          // Determine version from commit message
          def versionInfo = versionLib.determineVersion()
          echo "Current version: ${versionInfo.version}"
          echo "Bump type: ${versionInfo.bumpType}"

          // If a bump was requested, update and commit
          if (versionInfo.bumpType) {
            versionLib.writeVersionFile(versionInfo.version)
            versionLib.commitAndPush("Update version to ${versionInfo.version}")
            versionLib.createAndPushTag("v${versionInfo.cleanVersion}", "Release ${versionInfo.cleanVersion}")
          }
        }
      }
    }
  }
}
```

### 3. Using a Commit Message

Commit with a version bump hint:

```bash
git commit -m "Add new feature bump-minor"
git push
```

This will automatically:
- Bump the version from `0.1.0` to `0.2.0`
- Add build metadata: `0.2.0+build.42`
- Commit the updated VERSION file
- Create and push tag `v0.2.0`

## Configuration

### Required Jenkins Credentials

Create a Jenkins credential for Git operations:

#### For Gitea/Generic Git Token:

1. Generate a token in your Git service
2. In Jenkins, create a **Username with password** credential:
   - **ID**: `GITEA_TOKEN` (or your preferred ID)
   - **Username**: Your Git username or token name
   - **Password**: Your Git token/password

### Pipeline Parameters

When using the library in backwards compatible mode (without `create()`), define these parameters:

```groovy
parameters {
  // Required Parameters
  string(name: 'REPO_URL',
         defaultValue: 'https://gitea.example.com/owner/repo.git',
         description: 'Full HTTPS Git URL of the project repository')

  string(name: 'DEFAULT_BRANCH',
         defaultValue: 'main',
         description: 'Branch that should receive the VERSION bump')

  string(name: 'BOT_EMAIL',
         defaultValue: 'jenkins-bot@example.com',
         description: 'Email used when the bot commits changes')

  string(name: 'BOT_NAME',
         defaultValue: 'jenkins-bot',
         description: 'Display name used when the bot commits changes')

  // Optional: For GiteaSCM Integration
  string(name: 'GITEA_SERVER_URL',
         defaultValue: 'https://gitea.example.com/',
         description: 'Base URL of the Gitea server (include trailing slash)')

  string(name: 'REPOSITORY_OWNER',
         defaultValue: 'owner',
         description: 'Owner or organization of the repository')

  string(name: 'REPOSITORY_NAME',
         defaultValue: 'repo',
         description: 'Repository name')
}

environment {
  REPO_URL = "${params.REPO_URL}"
  DEFAULT_BRANCH = "${params.DEFAULT_BRANCH}"
  BOT_EMAIL = "${params.BOT_EMAIL}"
  BOT_NAME = "${params.BOT_NAME}"
  GIT_CREDENTIALS_ID = 'GITEA_TOKEN'

  // Optional: For GiteaSCM
  GITEA_SERVER_URL = "${params.GITEA_SERVER_URL}"
  REPOSITORY_OWNER = "${params.REPOSITORY_OWNER}"
  REPOSITORY_NAME = "${params.REPOSITORY_NAME}"
}
```

## API Reference

### Core Version Management Functions

#### `checkoutRepository()`

Checkout repository using GiteaSCM with fallback to standard Git.

**Example:**
```groovy
versionLib.checkoutRepository()
```

**Why GiteaSCM?**
- Better integration with Gitea webhooks
- Automatic PR building support
- Enhanced commit status reporting
- Falls back to standard Git if unavailable

---

#### `isBotTriggered()`

Check if build was triggered by bot to prevent infinite loops.

**Returns:** `Boolean` - true if triggered by bot, false otherwise

**Example:**
```groovy
if (versionLib.isBotTriggered()) {
  echo "Skipping version bump - triggered by bot"
  return
}
```

**Important:** Always call this at the start of your version management stage to prevent loops.

---

#### `determineVersion()`

Determine version based on VERSION file and commit message hints.

**Returns:** `Map` with keys:
- `version`: Full version with build metadata (e.g., "1.2.3+build.42")
- `bumpType`: The type of bump applied ('major', 'minor', 'patch', or empty string)
- `cleanVersion`: Version without metadata (e.g., "1.2.3")

**Commit Message Hints:**
- `bump-major` → 2.0.0 (breaking changes, resets minor and patch to 0)
- `bump-minor` → 1.1.0 (new features, resets patch to 0)
- `bump-patch` → 1.0.1 (bug fixes)

**Example:**
```groovy
def versionInfo = versionLib.determineVersion()
echo "Version: ${versionInfo.version}"
echo "Bump Type: ${versionInfo.bumpType}"
echo "Clean Version: ${versionInfo.cleanVersion}"

// Example output for commit "Add feature bump-minor" with VERSION file "1.0.0":
// Version: 1.1.0+build.42
// Bump Type: minor
// Clean Version: 1.1.0
```

---

#### `commitAndPush(commitMessage, filesToAdd = 'VERSION')`

Commit and push changes to repository.

**Parameters:**
- `commitMessage` (String, required): Commit message to use
- `filesToAdd` (String, optional): Files to add (space-separated), default: 'VERSION'

**Example:**
```groovy
// Commit only VERSION file
versionLib.commitAndPush('Update version to 1.2.3')

// Commit multiple files
versionLib.commitAndPush('Update version and changelog', 'VERSION CHANGELOG.md')
```

**Security Notes:**
- Uses Jenkins credentials for authentication
- Credentials are masked in logs automatically
- Remote URL is restored after push for security

---

#### `createAndPushTag(tagName, tagMessage)`

Create and push an annotated Git tag.

**Parameters:**
- `tagName` (String, required): Tag name (e.g., "v1.2.3")
- `tagMessage` (String, required): Tag annotation message

**Example:**
```groovy
versionLib.createAndPushTag('v1.2.3', 'Release version 1.2.3')

// With version info
def versionInfo = versionLib.determineVersion()
versionLib.createAndPushTag(
  "v${versionInfo.cleanVersion}",
  "Release ${versionInfo.cleanVersion} - Build ${env.BUILD_NUMBER}"
)
```

**Why Annotated Tags?**
- Include author information and timestamp
- Can be signed with GPG for verification
- Show up in 'git describe' output
- Better for release management

---

### Helper Functions

#### `writeVersionFile(version)`

Write version string to VERSION file.

**Parameters:**
- `version` (String, required): Version string to write

**Example:**
```groovy
versionLib.writeVersionFile('1.2.3+build.42')
```

---

#### `validateVersionFile()`

Validate that VERSION file exists and has correct format.

**Returns:** `Boolean` - true if valid, false otherwise

**Example:**
```groovy
if (!versionLib.validateVersionFile()) {
  error "VERSION file is invalid"
}
```

---

#### `getCommitHash(abbreviated = false)`

Get current Git commit hash.

**Parameters:**
- `abbreviated` (Boolean, optional): If true, return abbreviated hash (7 chars), default: false

**Returns:** `String` - Git commit hash

**Example:**
```groovy
def fullHash = versionLib.getCommitHash()        // abc123def456...
def shortHash = versionLib.getCommitHash(true)   // abc123d
```

---

#### `getBranch()`

Get current Git branch name.

**Returns:** `String` - Git branch name

**Example:**
```groovy
def branch = versionLib.getBranch()
echo "Current branch: ${branch}"
```

---

#### `buildAuthUrl(baseUrl, username, password)`

Build authenticated Git URL from base URL and credentials.

**Parameters:**
- `baseUrl` (String, required): Repository URL
- `username` (String, required): Git username
- `password` (String, required): Git password/token

**Returns:** `String` - Authenticated URL with embedded credentials

**Example:**
```groovy
// Usually called internally by commitAndPush and createAndPushTag
// Manual usage:
withCredentials([usernamePassword(credentialsId: 'git-creds', usernameVariable: 'USER', passwordVariable: 'PASS')]) {
  def authUrl = versionLib.buildAuthUrl('https://gitea.example.com/owner/repo.git', USER, PASS)
}
```

---

### Convenience Functions

#### `bumpVersion(commitMessageTemplate = 'Update version to {version}', tagMessageTemplate = 'Release {version}')`

Complete version bump workflow in a single function call.

**Parameters:**
- `commitMessageTemplate` (String, optional): Template for commit message, use `{version}` placeholder
- `tagMessageTemplate` (String, optional): Template for tag message, use `{version}` placeholder

**Returns:** `Map` - Version information, or `null` if skipped

**Workflow:**
1. Checks if build was triggered by bot (prevents loops)
2. Determines new version from commit message
3. Writes new version to VERSION file
4. Commits and pushes changes
5. Creates and pushes a Git tag

**Example:**
```groovy
// Using defaults
def result = versionLib.bumpVersion()

// Custom messages
def result = versionLib.bumpVersion(
  '[skip ci] Bump version to {version}',
  'Release {version} - Automated by Jenkins'
)

if (result) {
  echo "New version: ${result.version}"
  echo "Bump type: ${result.bumpType}"
} else {
  echo "Version bump skipped"
}
```

---

### Instance-Based Configuration

Create a custom instance for managing specific repositories:

#### `create(customConfig)`

Create a new Version Management instance with custom configuration.

**Parameters:**
- `customConfig` (Map, required): Configuration map with keys:
  - `repoUrl`: Full HTTPS Git URL (required)
  - `giteaServerUrl`: Base URL of Gitea server with trailing slash (optional)
  - `repositoryOwner`: Owner/organization name (optional)
  - `repositoryName`: Repository name (optional)
  - `defaultBranch`: Branch name (default: 'main')
  - `botEmail`: Email for bot commits (default: 'jenkins-bot@example.com')
  - `botName`: Display name for bot commits (default: 'jenkins-bot')
  - `credentialsId`: Jenkins credential ID (default: 'GITEA_TOKEN')

**Returns:** Instance for method chaining

**Example:**
```groovy
def myVersion = versionLib.create([
  repoUrl: 'https://gitea.example.com/myorg/myrepo.git',
  giteaServerUrl: 'https://gitea.example.com/',
  repositoryOwner: 'myorg',
  repositoryName: 'myrepo',
  defaultBranch: 'main',
  botEmail: 'bot@example.com',
  botName: 'jenkins-bot',
  credentialsId: 'my-git-token'
])

myVersion.checkoutRepository()
def versionInfo = myVersion.determineVersion()
```

## Complete Examples

### Example 1: Simple Version Management Pipeline

```groovy
@Library('jenkins-version') _

pipeline {
  agent any

  parameters {
    string(name: 'REPO_URL', defaultValue: 'https://gitea.example.com/owner/repo.git')
    string(name: 'DEFAULT_BRANCH', defaultValue: 'main')
    string(name: 'BOT_EMAIL', defaultValue: 'jenkins-bot@example.com')
    string(name: 'BOT_NAME', defaultValue: 'jenkins-bot')
  }

  environment {
    REPO_URL = "${params.REPO_URL}"
    DEFAULT_BRANCH = "${params.DEFAULT_BRANCH}"
    BOT_EMAIL = "${params.BOT_EMAIL}"
    BOT_NAME = "${params.BOT_NAME}"
    GIT_CREDENTIALS_ID = 'GITEA_TOKEN'
  }

  stages {
    stage('Checkout') {
      steps {
        script {
          versionLib.checkoutRepository()
        }
      }
    }

    stage('Version Bump') {
      steps {
        script {
          // Use convenience function for complete workflow
          def result = versionLib.bumpVersion(
            'Update version to {version} [skip ci]',
            'Release {version}'
          )

          if (result) {
            echo "✅ Version bumped to ${result.version}"
            env.NEW_VERSION = result.version
            env.CLEAN_VERSION = result.cleanVersion
          } else {
            echo "⏭️  Version bump skipped"
          }
        }
      }
    }
  }

  post {
    success {
      echo "Pipeline completed successfully"
      echo "Version: ${env.NEW_VERSION ?: 'unchanged'}"
    }
  }
}
```

### Example 2: Version Management with Build Steps

```groovy
@Library('jenkins-version') _

pipeline {
  agent any

  parameters {
    string(name: 'REPO_URL', defaultValue: 'https://gitea.example.com/owner/myapp.git')
    string(name: 'DEFAULT_BRANCH', defaultValue: 'main')
    string(name: 'BOT_EMAIL', defaultValue: 'jenkins-bot@example.com')
    string(name: 'BOT_NAME', defaultValue: 'jenkins-bot')
  }

  environment {
    REPO_URL = "${params.REPO_URL}"
    DEFAULT_BRANCH = "${params.DEFAULT_BRANCH}"
    BOT_EMAIL = "${params.BOT_EMAIL}"
    BOT_NAME = "${params.BOT_NAME}"
    GIT_CREDENTIALS_ID = 'GITEA_TOKEN'
  }

  stages {
    stage('Checkout') {
      steps {
        script {
          versionLib.checkoutRepository()
        }
      }
    }

    stage('Determine Version') {
      steps {
        script {
          // Check for bot loop first
          if (versionLib.isBotTriggered()) {
            echo "🔄 Build triggered by bot, skipping version management"
            env.SKIP_VERSION = 'true'
            return
          }

          // Get version information
          def versionInfo = versionLib.determineVersion()

          env.VERSION = versionInfo.version
          env.CLEAN_VERSION = versionInfo.cleanVersion
          env.BUMP_TYPE = versionInfo.bumpType

          echo "📦 Version: ${env.VERSION}"
          echo "🔼 Bump Type: ${env.BUMP_TYPE}"

          // Check if we should skip build based on commit message
          def commitMsg = sh(script: "git log -1 --pretty=%B", returnStdout: true).trim()
          if (commitMsg.toLowerCase().contains('skip-build')) {
            echo "⏭️  Build skipped per commit message"
            env.SKIP_BUILD = 'true'
          }
        }
      }
    }

    stage('Build') {
      when {
        expression { env.SKIP_VERSION != 'true' && env.SKIP_BUILD != 'true' }
      }
      steps {
        script {
          echo "🔨 Building version ${env.VERSION}"

          // Your build steps here
          sh """
            echo "Building application..."
            # npm install
            # npm run build
            # docker build -t myapp:${env.CLEAN_VERSION} .
          """
        }
      }
    }

    stage('Test') {
      when {
        expression { env.SKIP_VERSION != 'true' && env.SKIP_BUILD != 'true' }
      }
      steps {
        script {
          echo "🧪 Testing version ${env.VERSION}"

          // Your test steps here
          sh """
            echo "Running tests..."
            # npm test
            # docker run myapp:${env.CLEAN_VERSION} npm test
          """
        }
      }
    }

    stage('Update Version') {
      when {
        expression { env.SKIP_VERSION != 'true' && env.BUMP_TYPE != '' }
      }
      steps {
        script {
          echo "📝 Updating VERSION file to ${env.VERSION}"

          // Write new version
          versionLib.writeVersionFile(env.VERSION)

          // Commit and push
          versionLib.commitAndPush("Update version to ${env.VERSION} [skip ci]")

          // Create tag
          versionLib.createAndPushTag(
            "v${env.CLEAN_VERSION}",
            "Release ${env.CLEAN_VERSION} - Build #${env.BUILD_NUMBER}"
          )

          echo "✅ Version management complete"
        }
      }
    }
  }

  post {
    success {
      echo "✅ Pipeline completed successfully"
    }
    failure {
      echo "❌ Pipeline failed"
    }
  }
}
```


## Best Practices

### 1. Always Prevent Bot Loops

**Always** check for bot-triggered builds at the start of your version management stage:

```groovy
stage('Version Management') {
  steps {
    script {
      if (versionLib.isBotTriggered()) {
        echo "Skipping - bot triggered"
        return
      }
      // ... rest of version management
    }
  }
}
```

### 2. Use Semantic Version Hints Consistently

Establish a team convention for commit messages:

- **Major bumps** (`bump-major`): Breaking changes, API changes
  ```bash
  git commit -m "Redesign API authentication bump-major"
  ```

- **Minor bumps** (`bump-minor`): New features, backwards compatible
  ```bash
  git commit -m "Add user profile endpoints bump-minor"
  ```

- **Patch bumps** (`bump-patch`): Bug fixes, minor improvements
  ```bash
  git commit -m "Fix login validation bug bump-patch"
  ```

### 3. Add [skip ci] to Version Commits

Prevent version update commits from triggering new builds:

```groovy
versionLib.commitAndPush("Update version to ${version} [skip ci]")
```

### 4. Use Instance-Based Config for Multiple Repos

When managing multiple repositories, use instance-based configuration:

```groovy
def repo1 = versionLib.create([repoUrl: '...', ...])
def repo2 = versionLib.create([repoUrl: '...', ...])

repo1.bumpVersion()
repo2.bumpVersion()
```

### 5. Validate VERSION File Format

Add validation at the start of your pipeline:

```groovy
stage('Validate') {
  steps {
    script {
      if (!versionLib.validateVersionFile()) {
        error "VERSION file is invalid or missing"
      }
    }
  }
}
```

## Version Format Details

### VERSION File Format

The library expects a VERSION file in your repository root with this format:

```
X.Y.Z
```

Where:
- `X` = Major version (breaking changes)
- `Y` = Minor version (new features)
- `Z` = Patch version (bug fixes)

The file may optionally include build metadata after a `+`:
```
X.Y.Z+anything
```

The library strips metadata before parsing and adds its own build metadata.

### Generated Version Format

When `determineVersion()` is called, it generates:

```
X.Y.Z+build.N
```

Where `N` is the Jenkins `BUILD_NUMBER`.

Example transformations:
- Input: `1.0.0`, Commit: `bump-minor`, Build: `42`
  - Output: `1.1.0+build.42`

- Input: `2.3.5+old-metadata`, Commit: `bump-patch`, Build: `100`
  - Output: `2.3.6+build.100`

## Integration with Other Tools

### Docker Image Tagging

```groovy
def versionInfo = versionLib.determineVersion()
sh "docker build -t myapp:${versionInfo.cleanVersion} ."
sh "docker tag myapp:${versionInfo.cleanVersion} myapp:latest"
```

### NPM Package Version

```groovy
def versionInfo = versionLib.determineVersion()
sh "npm version ${versionInfo.cleanVersion} --no-git-tag-version"
versionLib.commitAndPush("Update package version to ${versionInfo.version}", "package.json package-lock.json VERSION")
```

### Helm Chart Version

```groovy
def versionInfo = versionLib.determineVersion()
sh """
  sed -i 's/^version:.*/version: ${versionInfo.cleanVersion}/' Chart.yaml
  sed -i 's/^appVersion:.*/appVersion: ${versionInfo.cleanVersion}/' Chart.yaml
"""
versionLib.commitAndPush("Update chart version to ${versionInfo.version}", "Chart.yaml VERSION")
```
