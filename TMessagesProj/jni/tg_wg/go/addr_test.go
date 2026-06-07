package main

import (
	"net/netip"
	"testing"
)

func TestParseAddrsAcceptsAddressesAndPrefixes(t *testing.T) {
	addrs, err := parseAddrs([]string{"10.0.0.2", "10.0.0.3/32", "fd00::2", "fd00::3/128"})
	if err != nil {
		t.Fatalf("parseAddrs failed: %v", err)
	}

	expected := []netip.Addr{
		netip.MustParseAddr("10.0.0.2"),
		netip.MustParseAddr("10.0.0.3"),
		netip.MustParseAddr("fd00::2"),
		netip.MustParseAddr("fd00::3"),
	}
	if len(addrs) != len(expected) {
		t.Fatalf("unexpected address count: got %d want %d", len(addrs), len(expected))
	}
	for i := range expected {
		if addrs[i] != expected[i] {
			t.Fatalf("unexpected address at %d: got %s want %s", i, addrs[i], expected[i])
		}
	}
}

func TestParseAddrsSkipsEmptyValues(t *testing.T) {
	addrs, err := parseAddrs([]string{"", "10.0.0.2", ""})
	if err != nil {
		t.Fatalf("parseAddrs failed: %v", err)
	}
	if len(addrs) != 1 || addrs[0] != netip.MustParseAddr("10.0.0.2") {
		t.Fatalf("unexpected parsed addresses: %v", addrs)
	}
}

func TestParseAddrsRejectsInvalidValues(t *testing.T) {
	if _, err := parseAddrs([]string{"not-an-ip"}); err == nil {
		t.Fatal("expected invalid address error")
	}
}
