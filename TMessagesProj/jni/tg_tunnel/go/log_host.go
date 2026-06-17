//go:build !android || !cgo

package main

import "log"

func logDebug(format string, args ...any) {
	log.Printf(format, args...)
}

func logError(format string, args ...any) {
	log.Printf(format, args...)
}
