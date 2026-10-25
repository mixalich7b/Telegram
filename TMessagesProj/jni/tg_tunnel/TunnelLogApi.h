#pragma once

// One process-wide sink shared by Java, tgnet, JNI, Go and tgcalls.
#ifdef __cplusplus
extern "C" {
#endif
__attribute__((visibility("default"))) void tgTunnelLogWrite(int priority, const char *message);
__attribute__((visibility("default"))) void tgTunnelLogEnqueue(int priority, const char *message);
__attribute__((visibility("default"))) int tgTunnelLogInit(const char *directory);
__attribute__((visibility("default"))) int tgTunnelLogSnapshot(const char *directory);
__attribute__((visibility("default"))) void tgTunnelLogClear(void);
#ifdef __cplusplus
}
#endif
