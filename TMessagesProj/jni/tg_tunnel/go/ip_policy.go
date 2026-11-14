package main

import (
	"errors"
	"net/netip"
	"sync/atomic"
)

type ipPolicy struct{ allowIPv6 bool }

var currentIPPolicy atomic.Pointer[ipPolicy]
var errIPv6Disabled = errors.New("IPv6 is disabled")

func init() { currentIPPolicy.Store(&ipPolicy{allowIPv6: true}) }

// Atomically retires the old policy, including its socket handles, before the
// lifecycle queue restarts the runtime. A reverse toggle cannot revive it.
func setIPv6Enabled(enabled bool) {
	for {
		previous := currentIPPolicy.Load()
		if previous.allowIPv6 == enabled {
			return
		}
		if currentIPPolicy.CompareAndSwap(previous, &ipPolicy{allowIPv6: enabled}) {
			return
		}
	}
}
func ipv6Enabled() bool                   { return currentIPPolicy.Load().allowIPv6 }
func addressAllowed(addr netip.Addr) bool { return ipv6Enabled() || addr.Is4() }

func policyAddresses(addrs []netip.Addr) []netip.Addr {
	result := make([]netip.Addr, 0, len(addrs))
	for _, addr := range addrs {
		if addressAllowed(addr) {
			result = append(result, addr)
		}
	}
	return result
}
