package main

import (
	"context"
	"errors"
	"net"
	"strings"
	"testing"
)

func TestResolveUserspaceConfigEndpointsKeepsIPAddresses(t *testing.T) {
	config := "public_key=test\nendpoint=192.0.2.10:51820\nendpoint=[2001:db8::10]:51821\nallowed_ip=0.0.0.0/0\n"
	lookupCalls := 0

	got, err := resolveUserspaceConfigEndpoints(config, func(context.Context, string) ([]net.IPAddr, error) {
		lookupCalls++
		return nil, errors.New("unexpected lookup")
	})
	if err != nil {
		t.Fatalf("resolveUserspaceConfigEndpoints failed: %v", err)
	}
	if got != config {
		t.Fatalf("unexpected config:\ngot  %q\nwant %q", got, config)
	}
	if lookupCalls != 0 {
		t.Fatalf("unexpected lookup calls: %d", lookupCalls)
	}
}

func TestResolveUserspaceConfigEndpointsResolvesDomainEndpoint(t *testing.T) {
	config := "public_key=test\nendpoint=wg.example.com:51820\nallowed_ip=0.0.0.0/0\n"

	got, err := resolveUserspaceConfigEndpoints(config, func(ctx context.Context, host string) ([]net.IPAddr, error) {
		if host != "wg.example.com" {
			t.Fatalf("unexpected lookup host: %s", host)
		}
		return []net.IPAddr{{IP: net.ParseIP("203.0.113.10")}}, nil
	})
	if err != nil {
		t.Fatalf("resolveUserspaceConfigEndpoints failed: %v", err)
	}

	want := "public_key=test\nendpoint=203.0.113.10:51820\nallowed_ip=0.0.0.0/0\n"
	if got != want {
		t.Fatalf("unexpected config:\ngot  %q\nwant %q", got, want)
	}
}

func TestResolveUserspaceConfigEndpointsReportsLookupFailure(t *testing.T) {
	_, err := resolveUserspaceConfigEndpoints("endpoint=wg.example.com:51820\n", func(context.Context, string) ([]net.IPAddr, error) {
		return nil, errors.New("dns unavailable")
	})
	if err == nil {
		t.Fatal("expected lookup error")
	}
	if !strings.Contains(err.Error(), "wg.example.com") {
		t.Fatalf("expected host in error, got: %v", err)
	}
}

func TestResolveUserspaceConfigEndpointsReportsEmptyLookupResult(t *testing.T) {
	_, err := resolveUserspaceConfigEndpoints("endpoint=wg.example.com:51820\n", func(context.Context, string) ([]net.IPAddr, error) {
		return nil, nil
	})
	if err == nil {
		t.Fatal("expected empty lookup result error")
	}
	if !strings.Contains(err.Error(), "no IP addresses") {
		t.Fatalf("unexpected error: %v", err)
	}
}
