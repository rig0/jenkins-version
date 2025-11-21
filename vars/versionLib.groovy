/**
 * Version Management Library for Jenkins Pipelines
 *
 * Provides comprehensive semantic versioning functionality for Jenkins pipelines,
 * including VERSION file management, Git operations, and automated version bumping.
 *
 * Features:
 * - Semantic version parsing and bumping (major.minor.patch)
 * - Commit message-based version hints (bump-major, bump-minor, bump-patch)
 * - Bot loop prevention for automated commits
 * - GiteaSCM integration with Git fallback
 * - Build metadata tracking (version+build.N)
 * - Automated Git tagging
 * - Secure credential handling
 *
 * Usage:
 *   @Library('jenkins-version') _
 *
 *   // Use with global params (backwards compatible)
 *   versionLib.checkoutRepository()
 *   def versionInfo = versionLib.determineVersion()
 *
 *   // Or create instance with custom config
 *   def version = versionLib.create([
 *     repoUrl: 'https://gitea.example.com/owner/repo.git',
 *     giteaServerUrl: 'https://gitea.example.com/',
 *     repositoryOwner: 'owner',
 *     repositoryName: 'repo',
 *     defaultBranch: 'main',
 *     botEmail: 'bot@example.com',
 *     botName: 'jenkins-bot',
 *     credentialsId: 'gitea-token'
 *   ])
 *   version.checkoutRepository()
 */

import groovy.transform.Field

@Field Map config = [:]

/**
 * Create a new Version Management instance with custom configuration
 *
 * This allows you to manage multiple repositories or use custom settings
 * without relying on global pipeline parameters.
 *
 * @param customConfig Map with keys:
 *   - repoUrl: Full HTTPS Git URL (required)
 *   - giteaServerUrl: Base URL of Gitea server with trailing slash (optional)
 *   - repositoryOwner: Owner/organization name (optional, for GiteaSCM)
 *   - repositoryName: Repository name (optional, for GiteaSCM)
 *   - defaultBranch: Branch name (default: 'main')
 *   - botEmail: Email for bot commits (default: 'jenkins-bot@example.com')
 *   - botName: Display name for bot commits (default: 'jenkins-bot')
 *   - credentialsId: Jenkins credential ID (default: 'GITEA_TOKEN')
 * @return This instance for method chaining
 *
 * @example
 * def version = versionLib.create([
 *   repoUrl: 'https://gitea.example.com/owner/repo.git',
 *   defaultBranch: 'main',
 *   botEmail: 'bot@example.com',
 *   botName: 'jenkins-bot',
 *   credentialsId: 'git-token'
 * ])
 * version.checkoutRepository()
 */
def create(Map customConfig) {
  config = customConfig
  return this
}

/**
 * Get configuration value with fallback to params/env
 *
 * Priority: instance config > params > env > default
 *
 * @param key Configuration key
 * @param defaultValue Default value if not found
 * @return Configuration value
 */
private String getConfig(String key, String defaultValue = null) {
  // Check instance config first
  if (config[key]) {
    return config[key]
  }

  // Map config keys to param/env names
  def paramMap = [
    repoUrl: 'REPO_URL',
    giteaServerUrl: 'GITEA_SERVER_URL',
    repositoryOwner: 'REPOSITORY_OWNER',
    repositoryName: 'REPOSITORY_NAME',
    defaultBranch: 'DEFAULT_BRANCH',
    botEmail: 'BOT_EMAIL',
    botName: 'BOT_NAME',
    credentialsId: 'GIT_CREDENTIALS_ID'
  ]

  def paramName = paramMap[key]

  // Try params first (if in pipeline context)
  try {
    if (params && params."${paramName}") {
      return params."${paramName}"
    }
  } catch (Exception e) {
    // params may not be available in all contexts
  }

  // Try env next
  try {
    if (env && env."${paramName}") {
      return env."${paramName}"
    }
  } catch (Exception e) {
    // env may not be available in all contexts
  }

  // Return default
  return defaultValue
}

/**
 * Checkout repository using GiteaSCM with fallback to standard Git
 *
 * This function attempts to use the Gitea SCM plugin for enhanced integration
 * with Gitea servers. If the plugin is unavailable or fails, it automatically
 * falls back to standard Git checkout.
 *
 * Why use GiteaSCM?
 * - Better integration with Gitea webhooks
 * - Automatic PR building support
 * - Enhanced commit status reporting
 *
 * @example
 * // Using global params
 * versionLib.checkoutRepository()
 *
 * @example
 * // Using instance config
 * def version = versionLib.create([...])
 * version.checkoutRepository()
 */
def checkoutRepository() {
  def repoUrl = getConfig('repoUrl')
  def giteaServerUrl = getConfig('giteaServerUrl')
  def repositoryOwner = getConfig('repositoryOwner')
  def repositoryName = getConfig('repositoryName')
  def defaultBranch = getConfig('defaultBranch', 'main')
  def credentialsId = getConfig('credentialsId', 'GITEA_TOKEN')

  // Try GiteaSCM if we have the required parameters
  if (giteaServerUrl && repositoryOwner && repositoryName) {
    try {
      checkout([
        $class: 'GiteaSCM',
        giteaServerUrl: giteaServerUrl,
        repositoryOwner: repositoryOwner,
        repository: repositoryName,
        credentialsId: credentialsId,
        branches: [[name: defaultBranch]]
      ])
      echo "✅ Checked out using GiteaSCM"
      return
    } catch (Exception e) {
      echo "⚠️  GiteaSCM unavailable (${e.message}), falling back to plain Git"
    }
  }

  // Fallback to standard Git checkout
  if (!repoUrl) {
    error "❌ Repository URL is required for checkout. Set REPO_URL parameter or use create() with repoUrl."
  }

  git branch: defaultBranch,
      url: repoUrl,
      credentialsId: credentialsId
  echo "✅ Checked out using Git"
}

/**
 * Check if build was triggered by bot to prevent infinite loops
 *
 * When a bot commits version changes, it triggers a new build. Without loop
 * prevention, this creates an infinite cycle of builds. This function checks
 * the last commit author's email against the configured bot email.
 *
 * Security Note:
 * This relies on Git commit metadata which can be spoofed. For production
 * environments, consider additional verification mechanisms.
 *
 * @return true if triggered by bot, false otherwise
 *
 * @example
 * if (versionLib.isBotTriggered()) {
 *   echo "Skipping version bump - triggered by bot"
 *   return
 * }
 */
def isBotTriggered() {
  def botEmail = getConfig('botEmail', 'jenkins-bot@example.com')

  def authorEmail = sh(
    script: "git log -1 --pretty=format:'%ae'",
    returnStdout: true
  ).trim()

  if (authorEmail == botEmail) {
    echo "🔄 Build triggered by bot (${authorEmail}), stopping to prevent loop"
    return true
  }

  echo "✅ Build triggered by human: ${authorEmail}"
  return false
}

/**
 * Determine version based on VERSION file and commit message hints
 *
 * This function implements semantic versioning with intelligent bump detection:
 * 1. Reads current version from VERSION file
 * 2. Parses the version into major.minor.patch components
 * 3. Checks the last commit message for bump hints:
 *    - "bump-major" -> 2.0.0 (breaking changes)
 *    - "bump-minor" -> 1.1.0 (new features)
 *    - "bump-patch" -> 1.0.1 (bug fixes)
 * 4. Applies the bump and adds build metadata
 *
 * Version Format:
 * - Input:  X.Y.Z or X.Y.Z+anything
 * - Output: X.Y.Z+build.N (where N is BUILD_NUMBER)
 *
 * Why Build Metadata?
 * Build metadata (+build.N) helps track which Jenkins build produced each
 * version, making it easier to trace deployments back to specific builds.
 *
 * @return Map with keys:
 *   - version: Full version with build metadata (e.g., "1.2.3+build.42")
 *   - bumpType: The type of bump applied ('major', 'minor', 'patch', or '')
 *   - cleanVersion: Version without metadata (e.g., "1.2.3")
 *
 * @throws Error if VERSION file is missing or malformed
 *
 * @example
 * def versionInfo = versionLib.determineVersion()
 * echo "Version: ${versionInfo.version}"
 * echo "Bump Type: ${versionInfo.bumpType}"
 * echo "Clean Version: ${versionInfo.cleanVersion}"
 *
 * @example
 * // Commit message: "Add new feature bump-minor"
 * // VERSION file: "1.0.0"
 * // Result: [version: "1.1.0+build.42", bumpType: "minor", cleanVersion: "1.1.0"]
 */
def determineVersion() {
  // Validate VERSION file exists
  if (!fileExists('VERSION')) {
    error "❌ VERSION file not found in repository root. Please create one with format: X.Y.Z"
  }

  def version = readFile('VERSION').trim()

  // Validate version format (semver with optional metadata)
  if (!version.matches(/^\d+\.\d+\.\d+(\+.*)?$/)) {
    error "❌ Invalid VERSION file format: '${version}'. Expected: X.Y.Z or X.Y.Z+metadata"
  }

  echo "📦 Current VERSION file: ${version}"

  def commitMsg = sh(
    script: "git log -1 --pretty=%B",
    returnStdout: true
  ).trim()

  echo "📝 Last commit message: ${commitMsg}"

  // Determine bump type from commit message (case-insensitive)
  def bumpType = ''
  def commitLower = commitMsg.toLowerCase()
  if (commitLower.contains('bump-major')) {
    bumpType = 'major'
    echo "🔼 Detected MAJOR version bump (breaking changes)"
  } else if (commitLower.contains('bump-minor')) {
    bumpType = 'minor'
    echo "🔼 Detected MINOR version bump (new features)"
  } else if (commitLower.contains('bump-patch')) {
    bumpType = 'patch'
    echo "🔼 Detected PATCH version bump (bug fixes)"
  } else {
    echo "➡️  No version bump hint found in commit message"
  }

  // Strip off any +build metadata before parsing
  def baseVersion = version.contains('+') ? version.split('\\+')[0] : version

  try {
    def (major, minor, patch) = baseVersion.tokenize('.').collect { it as int }

    echo "📊 Parsed version: major=${major}, minor=${minor}, patch=${patch}"

    // Apply version bump
    if (bumpType == 'major') {
      major++
      minor = 0
      patch = 0
      echo "   → Bumping to: ${major}.0.0"
    } else if (bumpType == 'minor') {
      minor++
      patch = 0
      echo "   → Bumping to: ${major}.${minor}.0"
    } else if (bumpType == 'patch') {
      patch++
      echo "   → Bumping to: ${major}.${minor}.${patch}"
    }

    // Always include build metadata for traceability
    def newVersion = "${major}.${minor}.${patch}+build.${env.BUILD_NUMBER}"
    def cleanVersion = "${major}.${minor}.${patch}"

    echo "✅ Computed version: ${newVersion}"

    return [
      version: newVersion,
      bumpType: bumpType,
      cleanVersion: cleanVersion
    ]
  } catch (Exception e) {
    error "❌ Failed to parse version '${baseVersion}': ${e.message}"
  }
}

/**
 * Build authenticated Git URL from base URL and credentials
 *
 * This function embeds credentials directly into the Git URL for authenticated
 * operations (push, tag). This is necessary because Jenkins' Git plugin doesn't
 * always pass credentials correctly for all operations.
 *
 * Security Note:
 * - Credentials are embedded in the URL temporarily during Git operations
 * - The URL is immediately reset after the operation
 * - Credentials are never logged (use Jenkins credential masking)
 * - For maximum security, use tokens instead of passwords
 *
 * Supported URL formats:
 * - https://example.com/repo.git
 * - http://example.com/repo.git
 *
 * @param baseUrl The repository URL (https://example.com/repo.git)
 * @param username Git username or token name
 * @param password Git password/token value
 * @return Authenticated URL with embedded credentials
 *
 * @example
 * def authUrl = versionLib.buildAuthUrl(
 *   'https://gitea.example.com/owner/repo.git',
 *   'myuser',
 *   'mytoken'
 * )
 * // Returns: https://myuser:mytoken@gitea.example.com/owner/repo.git
 */
def buildAuthUrl(baseUrl, username, password) {
  def authUrl = baseUrl
  if (authUrl.startsWith('https://')) {
    authUrl = "https://${username}:${password}@${authUrl.substring(8)}"
  } else if (authUrl.startsWith('http://')) {
    authUrl = "http://${username}:${password}@${authUrl.substring(7)}"
  } else {
    echo "⚠️  URL scheme not recognized: ${authUrl}. Expected http:// or https://"
  }
  return authUrl
}

/**
 * Commit and push changes to repository
 *
 * This function handles the complete workflow for committing version changes:
 * 1. Configures Git user identity (bot name and email)
 * 2. Stages specified files
 * 3. Creates a commit with the provided message
 * 4. Temporarily sets an authenticated remote URL
 * 5. Pushes changes to the remote branch
 * 6. Restores the original remote URL (for security)
 *
 * Security Best Practices:
 * - Uses Jenkins credentials for authentication
 * - Credentials are masked in logs automatically
 * - Remote URL is restored immediately after push
 * - Uses --set-upstream to ensure correct branch tracking
 *
 * Error Handling:
 * - If no changes exist, continues without error
 * - Uses 'set -e' to fail fast on any Git error
 *
 * @param commitMessage Commit message to use
 * @param filesToAdd Files to add (space-separated), default: 'VERSION'
 *
 * @example
 * versionLib.commitAndPush('Update version to 1.2.3')
 *
 * @example
 * versionLib.commitAndPush('Update version and changelog', 'VERSION CHANGELOG.md')
 */
def commitAndPush(String commitMessage, String filesToAdd = 'VERSION') {
  def repoUrl = getConfig('repoUrl')
  def defaultBranch = getConfig('defaultBranch', 'main')
  def botName = getConfig('botName', 'jenkins-bot')
  def botEmail = getConfig('botEmail', 'jenkins-bot@example.com')
  def credentialsId = getConfig('credentialsId', 'GITEA_TOKEN')

  if (!repoUrl) {
    error "❌ Repository URL is required for commit operations. Set REPO_URL parameter or use create() with repoUrl."
  }

  echo "📤 Committing and pushing changes..."
  echo "   Files: ${filesToAdd}"
  echo "   Branch: ${defaultBranch}"

  withCredentials([usernamePassword(
    credentialsId: credentialsId,
    usernameVariable: 'GIT_USER',
    passwordVariable: 'GIT_PASS'
  )]) {
    def authUrl = buildAuthUrl(repoUrl, GIT_USER, GIT_PASS)

    sh """#!/bin/bash
set -euo pipefail

# Configure Git identity
git config user.name "${botName}"
git config user.email "${botEmail}"

# Stage and commit
git add ${filesToAdd}
if git diff --staged --quiet; then
  echo "⚠️  No changes to commit"
else
  git commit -m "${commitMessage}"
  echo "✅ Created commit: ${commitMessage}"
fi

# Push with authentication
git remote set-url origin "${authUrl}"
git push origin HEAD:"${defaultBranch}"
echo "✅ Pushed to ${defaultBranch}"

# Restore original URL for security
git remote set-url origin "${repoUrl}"
"""
  }

  echo "✅ Changes committed and pushed successfully"
}

/**
 * Create and push a Git tag
 *
 * This function creates an annotated Git tag and pushes it to the remote
 * repository. Annotated tags are recommended over lightweight tags because
 * they include tagger information, date, and message.
 *
 * Why Annotated Tags?
 * - Include author information and timestamp
 * - Can be signed with GPG for verification
 * - Show up in 'git describe' output
 * - Better for release management
 *
 * Tag Naming Convention:
 * - Use 'v' prefix for version tags (e.g., v1.2.3)
 * - Use semantic versioning format
 * - Keep tags immutable (don't reuse tag names)
 *
 * Error Handling:
 * - If tag already exists, logs warning and continues
 * - Uses 'set -e' to fail fast on other Git errors
 *
 * @param tagName Tag name (e.g., "v1.2.3")
 * @param tagMessage Tag annotation message
 *
 * @example
 * versionLib.createAndPushTag('v1.2.3', 'Release version 1.2.3')
 *
 * @example
 * def versionInfo = versionLib.determineVersion()
 * versionLib.createAndPushTag(
 *   "v${versionInfo.cleanVersion}",
 *   "Release ${versionInfo.cleanVersion} - Build ${env.BUILD_NUMBER}"
 * )
 */
def createAndPushTag(String tagName, String tagMessage) {
  def repoUrl = getConfig('repoUrl')
  def botName = getConfig('botName', 'jenkins-bot')
  def botEmail = getConfig('botEmail', 'jenkins-bot@example.com')
  def credentialsId = getConfig('credentialsId', 'GITEA_TOKEN')

  if (!repoUrl) {
    error "❌ Repository URL is required for tag operations. Set REPO_URL parameter or use create() with repoUrl."
  }

  echo "🏷️  Creating and pushing tag: ${tagName}"
  echo "   Message: ${tagMessage}"

  withCredentials([usernamePassword(
    credentialsId: credentialsId,
    usernameVariable: 'GIT_USER',
    passwordVariable: 'GIT_PASS'
  )]) {
    def authUrl = buildAuthUrl(repoUrl, GIT_USER, GIT_PASS)

    sh """#!/bin/bash
set -euo pipefail

# Configure Git identity
git config user.name "${botName}"
git config user.email "${botEmail}"

# Create annotated tag
if git tag -a "${tagName}" -m "${tagMessage}"; then
  echo "✅ Created tag: ${tagName}"
else
  echo "⚠️  Tag '${tagName}' already exists"
fi

# Push tag with authentication
git remote set-url origin "${authUrl}"
if git push origin "${tagName}"; then
  echo "✅ Pushed tag to remote"
else
  echo "⚠️  Tag may already exist on remote"
fi

# Restore original URL for security
git remote set-url origin "${repoUrl}"
"""
  }

  echo "✅ Tag created and pushed successfully"
}

/**
 * Get current Git commit hash
 *
 * @param short If true, return short hash (7 chars), otherwise full hash
 * @return Git commit hash
 *
 * @example
 * def commitHash = versionLib.getCommitHash()
 * def shortHash = versionLib.getCommitHash(true)
 */
def getCommitHash(Boolean short = false) {
  def format = short ? '%h' : '%H'
  return sh(
    script: "git log -1 --pretty=format:'${format}'",
    returnStdout: true
  ).trim()
}

/**
 * Get current Git branch name
 *
 * @return Git branch name
 *
 * @example
 * def branch = versionLib.getBranch()
 * echo "Current branch: ${branch}"
 */
def getBranch() {
  return sh(
    script: "git rev-parse --abbrev-ref HEAD",
    returnStdout: true
  ).trim()
}

/**
 * Validate that VERSION file exists and has correct format
 *
 * @return true if valid, false otherwise
 *
 * @example
 * if (!versionLib.validateVersionFile()) {
 *   error "VERSION file is invalid"
 * }
 */
def validateVersionFile() {
  if (!fileExists('VERSION')) {
    echo "❌ VERSION file does not exist"
    return false
  }

  def version = readFile('VERSION').trim()

  if (!version.matches(/^\d+\.\d+\.\d+(\+.*)?$/)) {
    echo "❌ VERSION file has invalid format: ${version}"
    return false
  }

  echo "✅ VERSION file is valid: ${version}"
  return true
}

/**
 * Write version to VERSION file
 *
 * @param version Version string to write
 *
 * @example
 * versionLib.writeVersionFile('1.2.3+build.42')
 */
def writeVersionFile(String version) {
  writeFile file: 'VERSION', text: version
  echo "✅ Written version to VERSION file: ${version}"
}

/**
 * Complete version bump workflow
 *
 * This is a convenience function that combines all steps of the version bump
 * process into a single call. It:
 * 1. Checks if build was triggered by bot (prevents loops)
 * 2. Determines new version from commit message
 * 3. Writes new version to VERSION file
 * 4. Commits and pushes changes
 * 5. Creates and pushes a Git tag
 *
 * Use this when you want the standard version bump workflow with minimal code.
 *
 * @param commitMessageTemplate Template for commit message, use {version} placeholder
 * @param tagMessageTemplate Template for tag message, use {version} placeholder
 * @return Map with version information, or null if skipped
 *
 * @example
 * def result = versionLib.bumpVersion(
 *   'Update version to {version}',
 *   'Release {version}'
 * )
 * if (result) {
 *   echo "New version: ${result.version}"
 * }
 */
def bumpVersion(
  String commitMessageTemplate = 'Update version to {version}',
  String tagMessageTemplate = 'Release {version}'
) {
  // Prevent bot loops
  if (isBotTriggered()) {
    echo "⏭️  Skipping version bump - triggered by bot"
    return null
  }

  // Determine new version
  def versionInfo = determineVersion()

  // Check if version bump is needed
  if (!versionInfo.bumpType) {
    echo "⏭️  No version bump needed - no bump hint in commit message"
    return versionInfo
  }

  // Write new version
  writeVersionFile(versionInfo.version)

  // Prepare messages
  def commitMessage = commitMessageTemplate.replace('{version}', versionInfo.version)
  def tagMessage = tagMessageTemplate.replace('{version}', versionInfo.cleanVersion)
  def tagName = "v${versionInfo.cleanVersion}"

  // Commit and push
  commitAndPush(commitMessage)

  // Create and push tag
  createAndPushTag(tagName, tagMessage)

  echo "🎉 Version bump complete: ${versionInfo.version}"

  return versionInfo
}

// Make this library callable
return this
