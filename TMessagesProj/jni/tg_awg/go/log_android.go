//go:build android && cgo

package main

// #cgo LDFLAGS: -llog
// #include <stdlib.h>
// #include <android/log.h>
import "C"

import (
	"fmt"
	"unsafe"
)

const logTag = "Telegram/AmneziaWG"

func logDebug(format string, args ...any) {
	logAndroid(C.ANDROID_LOG_DEBUG, format, args...)
}

func logError(format string, args ...any) {
	logAndroid(C.ANDROID_LOG_ERROR, format, args...)
}

func logAndroid(level C.int, format string, args ...any) {
	tag := C.CString(logTag)
	message := C.CString(fmt.Sprintf(format, args...))
	C.__android_log_write(level, tag, message)
	C.free(unsafe.Pointer(tag))
	C.free(unsafe.Pointer(message))
}
