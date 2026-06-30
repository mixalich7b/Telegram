package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestTunnelLookupHostUsesActiveTunnelNetwork(t *testing.T) {
	restore := setTestRuntimeState(&runtimeState{
		network:    fakeTunnelNetwork{lookupHosts: []string{"10.8.0.2", "fd00::2"}},
		generation: 1,
	})
	defer restore()

	addresses, err := tunnelLookupHost("telegram.org")
	if err != nil {
		t.Fatalf("lookup failed: %v", err)
	}
	if len(addresses) != 2 || addresses[0].String() != "10.8.0.2" || addresses[1].String() != "fd00::2" {
		t.Fatalf("unexpected addresses: %v", addresses)
	}
}

func TestTunnelTCPWriteWritesFullBuffer(t *testing.T) {
	restore := setTestRuntimeState(&runtimeState{
		network:    fakeTunnelNetwork{},
		generation: 7,
	})
	defer restore()

	conn := &partialWriteConn{maxWrite: 2}
	id := sockets.registerTCP(conn, 7)
	written, err := tunnelTCPWrite(id, []byte("abcdef"))
	if err != nil {
		t.Fatalf("write failed: %v", err)
	}
	if written != 6 || string(conn.writes) != "abcdef" {
		t.Fatalf("unexpected write result: written=%d data=%q", written, string(conn.writes))
	}
}

func TestTunnelSocketRejectsStaleGeneration(t *testing.T) {
	restore := setTestRuntimeState(&runtimeState{
		network:    fakeTunnelNetwork{},
		generation: 11,
	})
	defer restore()

	id := sockets.registerTCP(&partialWriteConn{maxWrite: 4}, 10)
	if _, err := tunnelTCPWrite(id, []byte("ping")); !errors.Is(err, errTunnelSocketNotFound) {
		t.Fatalf("expected stale handle error, got %v", err)
	}
}

type fakeTunnelNetwork struct {
	lookupHosts []string
}

func (n fakeTunnelNetwork) DialContext(context.Context, string, string) (net.Conn, error) {
	return nil, errors.New("not implemented")
}

func (n fakeTunnelNetwork) ListenPacket(context.Context, string, string) (net.PacketConn, error) {
	return nil, errors.New("not implemented")
}

func (n fakeTunnelNetwork) LookupHost(host string) ([]string, error) {
	if n.lookupHosts != nil {
		return n.lookupHosts, nil
	}
	return []string{host}, nil
}

func (n fakeTunnelNetwork) LocalAddresses() []netip.Addr {
	return nil
}

func setTestRuntimeState(next *runtimeState) func() {
	stateMu.Lock()
	previous := state
	state = next
	stateMu.Unlock()
	sockets.closeAll()
	return func() {
		sockets.closeAll()
		stateMu.Lock()
		state = previous
		stateMu.Unlock()
	}
}

type partialWriteConn struct {
	writes   []byte
	maxWrite int
}

func (c *partialWriteConn) Read([]byte) (int, error) {
	return 0, io.EOF
}

func (c *partialWriteConn) Write(bytes []byte) (int, error) {
	if c.maxWrite <= 0 || c.maxWrite > len(bytes) {
		c.maxWrite = len(bytes)
	}
	c.writes = append(c.writes, bytes[:c.maxWrite]...)
	return c.maxWrite, nil
}

func (c *partialWriteConn) Close() error {
	return nil
}

func (c *partialWriteConn) LocalAddr() net.Addr {
	return testNetAddr("local")
}

func (c *partialWriteConn) RemoteAddr() net.Addr {
	return testNetAddr("remote")
}

func (c *partialWriteConn) SetDeadline(time.Time) error {
	return nil
}

func (c *partialWriteConn) SetReadDeadline(time.Time) error {
	return nil
}

func (c *partialWriteConn) SetWriteDeadline(time.Time) error {
	return nil
}

type testNetAddr string

func (a testNetAddr) Network() string {
	return string(a)
}

func (a testNetAddr) String() string {
	return "127.0.0.1:0"
}
