package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestSocksHandshakeWithUsernamePasswordAndDomainTarget(t *testing.T) {
	client, done := startHandshake(t, "user", "pass")
	defer client.Close()

	writeAll(t, client, []byte{0x05, 0x01, 0x02})
	expectBytes(t, client, []byte{0x05, 0x02})
	writeAll(t, client, authRequest("user", "pass"))
	expectBytes(t, client, []byte{0x01, 0x00})
	writeAll(t, client, domainConnectRequest("telegram.org", 443))

	result := <-done
	if result.err != nil {
		t.Fatalf("handshake failed: %v", result.err)
	}
	if result.target != "telegram.org:443" {
		t.Fatalf("unexpected target: %s", result.target)
	}
}

func TestSocksHandshakeRejectsWrongCredentials(t *testing.T) {
	client, done := startHandshake(t, "user", "pass")
	defer client.Close()

	writeAll(t, client, []byte{0x05, 0x01, 0x02})
	expectBytes(t, client, []byte{0x05, 0x02})
	writeAll(t, client, authRequest("user", "wrong"))
	expectBytes(t, client, []byte{0x01, 0x01})

	result := <-done
	if result.err == nil {
		t.Fatal("expected auth error")
	}
}

func TestSocksHandshakeRejectsUnavailableAuthMethod(t *testing.T) {
	client, done := startHandshake(t, "user", "pass")
	defer client.Close()

	writeAll(t, client, []byte{0x05, 0x01, 0x00})
	expectBytes(t, client, []byte{0x05, 0xff})

	result := <-done
	if result.err == nil {
		t.Fatal("expected unavailable auth method error")
	}
}

func TestSocksHandshakeParsesIPv4AndIPv6Targets(t *testing.T) {
	client4, done4 := startHandshake(t, "", "")
	defer client4.Close()
	writeAll(t, client4, []byte{0x05, 0x01, 0x00})
	expectBytes(t, client4, []byte{0x05, 0x00})
	writeAll(t, client4, ipv4ConnectRequest(net.IPv4(149, 154, 167, 50), 443))
	result4 := <-done4
	if result4.err != nil || result4.target != "149.154.167.50:443" {
		t.Fatalf("unexpected IPv4 result: target=%s err=%v", result4.target, result4.err)
	}

	client6, done6 := startHandshake(t, "", "")
	defer client6.Close()
	writeAll(t, client6, []byte{0x05, 0x01, 0x00})
	expectBytes(t, client6, []byte{0x05, 0x00})
	writeAll(t, client6, ipv6ConnectRequest(net.ParseIP("2001:db8::1"), 443))
	result6 := <-done6
	if result6.err != nil || result6.target != "[2001:db8::1]:443" {
		t.Fatalf("unexpected IPv6 result: target=%s err=%v", result6.target, result6.err)
	}
}

func TestSocksHandshakeRejectsUnsupportedCommand(t *testing.T) {
	client, done := startHandshake(t, "", "")
	defer client.Close()

	writeAll(t, client, []byte{0x05, 0x01, 0x00})
	expectBytes(t, client, []byte{0x05, 0x00})
	writeAll(t, client, []byte{0x05, 0x02, 0x00, 0x01})
	expectBytes(t, client, []byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	result := <-done
	if result.err == nil {
		t.Fatal("expected unsupported command error")
	}
}

func TestHandleSocksProxiesBytesThroughDialer(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	setDeadline(t, client)
	setDeadline(t, server)

	dialer := &echoDialer{}
	state := &runtimeState{
		network:  fakeTunnelNetwork{dialer: dialer},
		username: "user",
		password: "pass",
	}
	go handleSocks(context.Background(), state, server)

	writeAll(t, client, []byte{0x05, 0x01, 0x02})
	expectBytes(t, client, []byte{0x05, 0x02})
	writeAll(t, client, authRequest("user", "pass"))
	expectBytes(t, client, []byte{0x01, 0x00})
	writeAll(t, client, domainConnectRequest("telegram.org", 443))
	expectBytes(t, client, []byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	writeAll(t, client, []byte("ping"))
	expectBytes(t, client, []byte("ping"))

	if dialer.target != "telegram.org:443" {
		t.Fatalf("unexpected dial target: %s", dialer.target)
	}
}

func TestHandleSocksWritesFailureWhenDialFails(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	setDeadline(t, client)
	setDeadline(t, server)

	state := &runtimeState{
		network: fakeTunnelNetwork{dialer: &errorDialer{}},
	}
	go handleSocks(context.Background(), state, server)

	writeAll(t, client, []byte{0x05, 0x01, 0x00})
	expectBytes(t, client, []byte{0x05, 0x00})
	writeAll(t, client, domainConnectRequest("telegram.org", 443))
	expectBytes(t, client, []byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
}

func TestHttpConnectHandshakeWithProxyAuthorization(t *testing.T) {
	client, done := startProxyHandshake(t, "user", "pass")
	defer client.Close()

	writeAll(t, client, httpConnectRequest("telegram.org:443", "user", "pass"))

	result := <-done
	if result.err != nil {
		t.Fatalf("handshake failed: %v", result.err)
	}
	if result.target != "telegram.org:443" {
		t.Fatalf("unexpected target: %s", result.target)
	}
}

func TestHttpConnectHandshakeUsesConnectRequestTarget(t *testing.T) {
	client, done := startProxyHandshake(t, "", "")
	defer client.Close()

	writeAll(t, client, []byte("CONNECT telegram.org:443 HTTP/1.0\r\nHost: telegram.org\r\nContent-Length: 0\r\n\r\n"))

	result := <-done
	if result.err != nil {
		t.Fatalf("handshake failed: %v", result.err)
	}
	if result.target != "telegram.org:443" {
		t.Fatalf("unexpected target: %s", result.target)
	}
}

func TestHttpConnectHandshakeRejectsWrongCredentials(t *testing.T) {
	client, done := startProxyHandshake(t, "user", "pass")
	defer client.Close()

	writeAll(t, client, httpConnectRequest("telegram.org:443", "user", "wrong"))
	expectBytes(t, client, []byte("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"tg-wg\"\r\nContent-Length: 0\r\n\r\n"))

	result := <-done
	if result.err == nil {
		t.Fatal("expected HTTP proxy auth error")
	}
}

func TestHandleHttpConnectProxiesBytesThroughDialer(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	setDeadline(t, client)
	setDeadline(t, server)

	dialer := &echoDialer{}
	state := &runtimeState{
		network:  fakeTunnelNetwork{dialer: dialer},
		username: "user",
		password: "pass",
	}
	go handleProxyConnection(context.Background(), state, server)

	writeAll(t, client, httpConnectRequest("telegram.org:443", "user", "pass"))
	expectBytes(t, client, []byte("HTTP/1.1 200 Connection Established\r\n\r\n"))

	writeAll(t, client, []byte("ping"))
	expectBytes(t, client, []byte("ping"))

	if dialer.target != "telegram.org:443" {
		t.Fatalf("unexpected dial target: %s", dialer.target)
	}
}

func TestHandleHttpConnectWritesFailureWhenDialFails(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	setDeadline(t, client)
	setDeadline(t, server)

	state := &runtimeState{
		network: fakeTunnelNetwork{dialer: &errorDialer{}},
	}
	go handleProxyConnection(context.Background(), state, server)

	writeAll(t, client, httpConnectRequest("telegram.org:443", "", ""))
	expectBytes(t, client, []byte("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"))
}

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

type handshakeResult struct {
	target string
	err    error
}

func startHandshake(t *testing.T, username string, password string) (net.Conn, <-chan handshakeResult) {
	t.Helper()

	client, server := net.Pipe()
	setDeadline(t, client)
	setDeadline(t, server)

	done := make(chan handshakeResult, 1)
	go func() {
		target, err := socksHandshake(server, username, password)
		_ = server.Close()
		done <- handshakeResult{target: target, err: err}
	}()
	return client, done
}

func startProxyHandshake(t *testing.T, username string, password string) (net.Conn, <-chan handshakeResult) {
	t.Helper()

	client, server := net.Pipe()
	setDeadline(t, client)
	setDeadline(t, server)

	done := make(chan handshakeResult, 1)
	go func() {
		target, _, err := proxyHandshake(bufio.NewReader(server), server, username, password)
		_ = server.Close()
		done <- handshakeResult{target: target, err: err}
	}()
	return client, done
}

func authRequest(username string, password string) []byte {
	request := []byte{0x01, byte(len(username))}
	request = append(request, []byte(username)...)
	request = append(request, byte(len(password)))
	request = append(request, []byte(password)...)
	return request
}

func domainConnectRequest(host string, port uint16) []byte {
	request := []byte{0x05, 0x01, 0x00, 0x03, byte(len(host))}
	request = append(request, []byte(host)...)
	return appendPort(request, port)
}

func ipv4ConnectRequest(ip net.IP, port uint16) []byte {
	request := []byte{0x05, 0x01, 0x00, 0x01}
	request = append(request, ip.To4()...)
	return appendPort(request, port)
}

func ipv6ConnectRequest(ip net.IP, port uint16) []byte {
	request := []byte{0x05, 0x01, 0x00, 0x04}
	request = append(request, ip.To16()...)
	return appendPort(request, port)
}

func httpConnectRequest(target string, username string, password string) []byte {
	request := "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n"
	if username != "" || password != "" {
		request += "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(username+":"+password)) + "\r\n"
	}
	request += "\r\n"
	return []byte(request)
}

func appendPort(request []byte, port uint16) []byte {
	portBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(portBytes, port)
	return append(request, portBytes...)
}

func writeAll(t *testing.T, conn net.Conn, bytes []byte) {
	t.Helper()
	if _, err := conn.Write(bytes); err != nil {
		t.Fatalf("write failed: %v", err)
	}
}

func expectBytes(t *testing.T, conn net.Conn, expected []byte) {
	t.Helper()
	actual := make([]byte, len(expected))
	if _, err := io.ReadFull(conn, actual); err != nil {
		t.Fatalf("read failed: %v", err)
	}
	for i := range expected {
		if actual[i] != expected[i] {
			t.Fatalf("unexpected bytes: got %v want %v", actual, expected)
		}
	}
}

func setDeadline(t *testing.T, conn net.Conn) {
	t.Helper()
	if err := conn.SetDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("SetDeadline failed: %v", err)
	}
}

type echoDialer struct {
	target string
}

func (d *echoDialer) DialContext(ctx context.Context, network string, address string) (net.Conn, error) {
	d.target = address
	local, remote := net.Pipe()
	go func() {
		defer remote.Close()
		_, _ = io.Copy(remote, remote)
	}()
	return local, nil
}

type errorDialer struct {
}

func (d *errorDialer) DialContext(ctx context.Context, network string, address string) (net.Conn, error) {
	return nil, errors.New("dial failed")
}

type fakeTunnelNetwork struct {
	dialer      tcpDialer
	lookupHosts []string
}

func (n fakeTunnelNetwork) DialContext(ctx context.Context, network string, address string) (net.Conn, error) {
	if n.dialer == nil {
		return nil, errors.New("not implemented")
	}
	return n.dialer.DialContext(ctx, network, address)
}

func (n fakeTunnelNetwork) ListenPacket(ctx context.Context, network string, address string) (net.PacketConn, error) {
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
