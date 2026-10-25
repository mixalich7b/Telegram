//go:build android && cgo

package main

// #cgo LDFLAGS: -ltg-tunnel-log
// #include <stdlib.h>
// #include <android/log.h>
// #include "../TunnelLogApi.h"
import "C"

import (
	"fmt"
	"unsafe"
)

func logDebug(format string, args ...any) {
	logAndroid(C.ANDROID_LOG_DEBUG, format, args...)
}

func logError(format string, args ...any) {
	logAndroid(C.ANDROID_LOG_ERROR, format, args...)
}

func logAndroid(level C.int, format string, args ...any) {
	if tunnelLogsEnabled != "true" {
		return
	}
	message := C.CString(fmt.Sprintf(format, args...))
	C.tgTunnelLogWrite(level, message)
	C.free(unsafe.Pointer(message))
}
