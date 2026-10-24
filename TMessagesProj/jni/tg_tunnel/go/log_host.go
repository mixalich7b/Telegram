//go:build !android || !cgo

package main

import "log"

func logDebug(format string, args ...any) {
	if tunnelLogsEnabled == "true" {
		log.Printf(format, args...)
	}
}

func logError(format string, args ...any) {
	if tunnelLogsEnabled == "true" {
		log.Printf(format, args...)
	}
}
