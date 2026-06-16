package main

import (
	"errors"
	"testing"
)

func TestOnNetworkChangedNoRuntimeSucceeds(t *testing.T) {
	withRuntimeState(t, nil)

	if status := onNetworkChangedRuntime(); status != 0 {
		t.Fatalf("unexpected status: %d", status)
	}
}

func TestOnNetworkChangedCallsBindUpdate(t *testing.T) {
	device := &fakeWireGuardDevice{}
	withRuntimeState(t, &runtimeState{device: device})

	if status := onNetworkChangedRuntime(); status != 0 {
		t.Fatalf("unexpected status: %d", status)
	}
	if device.bindUpdateCalls != 1 {
		t.Fatalf("unexpected BindUpdate calls: %d", device.bindUpdateCalls)
	}
}

func TestOnNetworkChangedReportsBindUpdateFailure(t *testing.T) {
	device := &fakeWireGuardDevice{bindUpdateError: errors.New("refresh failed")}
	withRuntimeState(t, &runtimeState{device: device})

	if status := onNetworkChangedRuntime(); status >= 0 {
		t.Fatalf("expected failure status, got %d", status)
	}
	if device.bindUpdateCalls != 1 {
		t.Fatalf("unexpected BindUpdate calls: %d", device.bindUpdateCalls)
	}
}

func withRuntimeState(t *testing.T, next *runtimeState) {
	t.Helper()

	stateMu.Lock()
	previous := state
	state = next
	stateMu.Unlock()

	t.Cleanup(func() {
		stateMu.Lock()
		state = previous
		stateMu.Unlock()
	})
}

type fakeWireGuardDevice struct {
	bindUpdateCalls int
	bindUpdateError error
	closeCalls      int
}

func (d *fakeWireGuardDevice) BindUpdate() error {
	d.bindUpdateCalls++
	return d.bindUpdateError
}

func (d *fakeWireGuardDevice) Close() {
	d.closeCalls++
}
