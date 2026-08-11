package com.androidagent.app.privileged;

interface IMusePrivilegedService {
    void destroy() = 16777114;
    String identity() = 1;
    String execute(String command, long timeoutMillis) = 2;
    String installEnvironment(String archivePath, String mirrorId, String toolIds) = 3;
}
