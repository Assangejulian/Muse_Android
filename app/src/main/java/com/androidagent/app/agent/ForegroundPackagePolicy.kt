package com.androidagent.app.agent

/**
 * When the goal does not name an app, lock onto the live foreground package
 * if it is a real installed app. Launchers and protected surfaces stay unlocked
 * so the Actor can still see the screen and choose launch_app itself.
 */
internal object ForegroundPackagePolicy {
    fun adopt(
        currentPackage: String,
        installedPackages: Set<String>,
        musePackage: String = "com.androidagent.app",
    ): String? {
        val packageName = currentPackage.trim()
        if (packageName.isBlank()) return null
        if (packageName.equals(musePackage, ignoreCase = true)) return null
        if (isHomeShell(packageName)) return null
        if (PackagePolicy.isSystemUiPackage(packageName) || packageName.equals("android", true)) return null
        if (isInstallerOrPermission(packageName)) return null
        if (installedPackages.none { it.equals(packageName, ignoreCase = true) }) return null
        return packageName
    }

    fun isHomeShell(packageName: String): Boolean {
        val value = packageName.lowercase()
        return value.contains("launcher") ||
            value.contains("trebuchet") ||
            value.endsWith(".home") ||
            value.contains(".home.")
    }

    private fun isInstallerOrPermission(packageName: String): Boolean {
        val value = packageName.lowercase()
        return value.contains("permissioncontroller") || value.contains("packageinstaller")
    }
}
