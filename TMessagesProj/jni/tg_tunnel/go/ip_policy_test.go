package main

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestIPv4OnlyEndpointPolicy(t *testing.T) {
	setIPv6Enabled(false)
	t.Cleanup(func() { setIPv6Enabled(true) })
	for _, endpoint := range []string{"[2001:db8::1]:51820", "[::ffff:192.0.2.1]:51820"} {
		if _, err := resolveUserspaceEndpoint(endpoint, nil); err == nil {
			t.Fatalf("IPv6 endpoint accepted: %s", endpoint)
		}
	}
	lookup := func(context.Context, string) ([]net.IPAddr, error) {
		return []net.IPAddr{{IP: net.ParseIP("2001:db8::1")}, {IP: net.ParseIP("192.0.2.1")}}, nil
	}
	if got, err := resolveUserspaceEndpoint("example.test:51820", lookup); err != nil || got != "192.0.2.1:51820" {
		t.Fatalf("IPv4 answer was not selected: %q, %v", got, err)
	}
	_, err := resolveUserspaceEndpoint("example.test:51820", func(context.Context, string) ([]net.IPAddr, error) {
		return []net.IPAddr{{IP: net.ParseIP("2001:db8::1")}}, nil
	})
	if err == nil {
		t.Fatal("IPv6-only DNS accepted")
	}
}

func TestPolicyChangeInvalidatesOldRuntime(t *testing.T) {
	setIPv6Enabled(true)
	t.Cleanup(func() { setIPv6Enabled(true) })
	restore := setTestRuntimeState(&runtimeState{network: fakeTunnelNetwork{}, generation: 61})
	defer restore()
	id := sockets.registerTCP(&partialWriteConn{maxWrite: 4}, 61)
	defer closeTunnelTCP(id)
	setIPv6Enabled(false)
	if _, _, err := activeTunnelNetwork(); !errors.Is(err, errTunnelRuntimeUnavailable) {
		t.Fatalf("old runtime still accepts new sockets: %v", err)
	}
	if _, err := tunnelTCPWrite(id, []byte("data")); !errors.Is(err, errTunnelSocketNotFound) {
		t.Fatalf("old runtime handle still usable: %v", err)
	}
	// Reversing the toggle must not revive a retired runtime generation.
	setIPv6Enabled(true)
	if _, _, err := activeTunnelNetwork(); !errors.Is(err, errTunnelRuntimeUnavailable) {
		t.Fatalf("retired runtime revived by reverse toggle: %v", err)
	}
}

func TestIPv4OnlyTunnelAddresses(t *testing.T) {
	setIPv6Enabled(false)
	t.Cleanup(func() { setIPv6Enabled(true) })
	addrs := policyAddresses([]netip.Addr{netip.MustParseAddr("fd00::1"), netip.MustParseAddr("10.0.0.1")})
	if len(addrs) != 1 || addrs[0].String() != "10.0.0.1" {
		t.Fatalf("unexpected policy addresses: %v", addrs)
	}
	for _, host := range []string{"::", "2001:db8::1", "::ffff:192.0.2.1"} {
		if _, err := resolveListenUDPAddr("udp6", net.JoinHostPort(host, "0")); err == nil {
			t.Fatalf("IPv6 UDP bind accepted: %s", host)
		}
		if _, err := resolveRemoteUDPAddr(host, 443); err == nil {
			t.Fatalf("IPv6 UDP destination accepted: %s", host)
		}
	}
}

func TestIPv4OnlyTunnelDNSAndTCP(t *testing.T) {
	setIPv6Enabled(false)
	t.Cleanup(func() { setIPv6Enabled(true) })
	network := &policyTestNetwork{fakeTunnelNetwork: fakeTunnelNetwork{lookupHosts: []string{"fd00::2", "10.8.0.2"}}}
	restore := setTestRuntimeState(&runtimeState{network: network, generation: 41})
	defer restore()
	addrs, err := tunnelLookupHost("telegram.test")
	if err != nil || len(addrs) != 1 || addrs[0].String() != "10.8.0.2" {
		t.Fatalf("DNS: %v %v", addrs, err)
	}
	remote, err := resolveRemoteUDPAddr("telegram.test", 443)
	if err != nil || remote.IP.String() != "10.8.0.2" {
		t.Fatalf("UDP DNS: %v %v", remote, err)
	}
	for _, host := range []string{"fd00::2", "::ffff:192.0.2.1"} {
		if _, err := openTunnelTCP(host, 443); !errors.Is(err, errIPv6Disabled) {
			t.Fatalf("TCP literal: %v", err)
		}
	}
	if network.dialNetwork != "" {
		t.Fatal("IPv6 reached tunnel dialer")
	}
	openTunnelTCP("telegram.test", 443)
	if network.dialNetwork != "tcp4" {
		t.Fatalf("TCP DNS could choose IPv6: %q", network.dialNetwork)
	}
}

type policyTestNetwork struct {
	fakeTunnelNetwork
	dialNetwork string
}

func (n *policyTestNetwork) DialContext(_ context.Context, network, _ string) (net.Conn, error) {
	n.dialNetwork = network
	return nil, errors.New("test dial")
}

func TestIPv4OnlyRuntimeConfiguration(t *testing.T) {
	setIPv6Enabled(false)
	t.Cleanup(func() { setIPv6Enabled(true) })
	withRuntimeState(t, nil)
	t.Cleanup(stopRuntime)
	factory := func(local, dns []netip.Addr, _ int, _ string) (wireGuardDevice, tunnelNetwork, int, error) {
		if len(local) != 1 || local[0].String() != "10.0.0.2" {
			t.Fatalf("local: %v", local)
		}
		if len(dns) != 1 || dns[0].String() != "192.0.2.53" {
			t.Fatalf("DNS: %v", dns)
		}
		return &fakeWireGuardDevice{}, fakeTunnelNetwork{}, 0, nil
	}
	if status := startRuntime("test", "endpoint=192.0.2.1:51820", []string{"fd00::2", "10.0.0.2"}, []string{"fd00::53", "192.0.2.53"}, 1420, factory); status != 0 {
		t.Fatalf("start: %d", status)
	}
	if status := startRuntime("test", "endpoint=192.0.2.1:51820", []string{"fd00::2"}, nil, 1420, factory); status >= 0 {
		t.Fatal("IPv6-only profile started")
	}
}

func TestIPv4WireGuardOuterBind(t *testing.T) {
	setIPv6Enabled(false)
	t.Cleanup(func() { setIPv6Enabled(true) })
	b := newWireGuardBind()
	checkIPv4OuterBind(t, b.Open, b.ParseEndpoint, b.Send, b.Close)
}
func TestIPv4AmneziaOuterBind(t *testing.T) {
	setIPv6Enabled(false)
	t.Cleanup(func() { setIPv6Enabled(true) })
	b := newAmneziaBind()
	checkIPv4OuterBind(t, b.Open, b.ParseEndpoint, b.Send, b.Close)
}

func checkIPv4OuterBind[E interface{ DstIP() netip.Addr }, R ~func([][]byte, []int, []E) (int, error)](
	t *testing.T, open func(uint16) ([]R, uint16, error), parse func(string) (E, error), send func([][]byte, E) error, closeBind func() error,
) {
	t.Helper()
	if _, err := parse("[2001:db8::1]:51820"); err == nil {
		t.Fatal("outer IPv6 peer accepted")
	}
	receivers, port, err := open(0)
	if err != nil {
		t.Fatal(err)
	}
	defer closeBind()
	if len(receivers) != 1 {
		t.Fatalf("expected one IPv4 receive function, got %d", len(receivers))
	}
	peer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer peer.Close()
	peer.SetDeadline(time.Now().Add(3 * time.Second))
	endpoint, err := parse(peer.LocalAddr().String())
	if err != nil {
		t.Fatal(err)
	}
	if err := send([][]byte{[]byte("hello")}, endpoint); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 64)
	n, _, err := peer.ReadFromUDP(buffer)
	if err != nil || string(buffer[:n]) != "hello" {
		t.Fatalf("send: %q %v", buffer[:n], err)
	}
	// Close unblocks the receive path even on assertion failure or test timeout.
	timer := time.AfterFunc(3*time.Second, func() { closeBind() })
	defer timer.Stop()
	if _, err := peer.WriteToUDP([]byte("reply"), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(port)}); err != nil {
		t.Fatal(err)
	}
	sizes := make([]int, 1)
	endpoints := make([]E, 1)
	count, err := receivers[0]([][]byte{buffer}, sizes, endpoints)
	if err != nil || count != 1 || string(buffer[:sizes[0]]) != "reply" || !endpoints[0].DstIP().Is4() {
		t.Fatalf("receive: count=%d err=%v", count, err)
	}
	if err := closeBind(); err != nil {
		t.Fatal(err)
	}
	if _, err := receivers[0]([][]byte{buffer}, sizes, endpoints); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("closed bind: %v", err)
	}
	reopened, newPort, err := open(0)
	if err != nil || len(reopened) != 1 || newPort == 0 {
		t.Fatalf("reopen: port=%d err=%v", newPort, err)
	}
	if err := send([][]byte{[]byte("reopened")}, endpoint); err != nil {
		t.Fatal(err)
	}
	n, _, err = peer.ReadFromUDP(buffer)
	if err != nil || string(buffer[:n]) != "reopened" {
		t.Fatalf("reopened send: %q %v", buffer[:n], err)
	}
}
