package io.github.jaqlig.wiflight;

interface IGrantPermissionService {
    String grantWriteSecureSettings(int userId) = 0;
    void destroy() = 16777114;
}
