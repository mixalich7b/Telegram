//go:build !android || !cgo

package main

import (
	"bytes"
	"log"
	"strings"
	"testing"
)

func TestTunnelLoggingSwitch(t *testing.T) {
	previousSetting := tunnelLogsEnabled
	previousOutput := log.Writer()
	defer func() {
		tunnelLogsEnabled = previousSetting
		log.SetOutput(previousOutput)
	}()
	for _, enabled := range []string{"true", "false"} {
		t.Run(enabled, func(t *testing.T) {
			tunnelLogsEnabled = enabled
			var output bytes.Buffer
			log.SetOutput(&output)
			logDebug("connected generation=%d", 7)
			logError("failed status=%d", -10)
			if enabled == "false" {
				if output.Len() != 0 {
					t.Fatalf("disabled tunnel logger emitted %q", output.String())
				}
			} else if !strings.Contains(output.String(), "connected generation=7") || !strings.Contains(output.String(), "failed status=-10") {
				t.Fatalf("missing diagnostics: %q", output.String())
			}
		})
	}
}
