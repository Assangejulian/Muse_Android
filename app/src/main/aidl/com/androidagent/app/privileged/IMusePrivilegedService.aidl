package com.androidagent.app.privileged;

interface IMusePrivilegedService {
    void destroy() = 16777114;
    String identity() = 1;
    String execute(String command, long timeoutMillis) = 2;
}
