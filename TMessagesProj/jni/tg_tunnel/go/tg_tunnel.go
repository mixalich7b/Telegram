package main

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	awgconn "github.com/amnezia-vpn/amneziawg-go/conn"
	awgdevice "github.com/amnezia-vpn/amneziawg-go/device"
	awgnetstack "github.com/amnezia-vpn/amneziawg-go/tun/netstack"
	wgconn "golang.zx2c4.com/wireguard/conn"
	wgdevice "golang.zx2c4.com/wireguard/device"
	wgnetstack "golang.zx2c4.com/wireguard/tun/netstack"
)

type tcpDialer interface {
	DialContext(ctx context.Context, network string, address string) (net.Conn, error)
}

type wireGuardDevice interface {
	BindUpdate() error
	Close()
}

type runtimeFactory func(local []netip.Addr, dns []netip.Addr, mtu int, userspaceConfig string) (wireGuardDevice, tcpDialer, int, error)

type proxyProtocol int

const (
	proxyProtocolSocks5 proxyProtocol = iota
	proxyProtocolHttpConnect
)

const endpointResolveTimeout = 10 * time.Second

type endpointResolver func(ctx context.Context, host string) ([]net.IPAddr, error)

type runtimeState struct {
	device   wireGuardDevice
	dialer   tcpDialer
	listener net.Listener
	cancel   context.CancelFunc
	username string
	password string
	protocol string
}

var (
	stateMu sync.Mutex
	state   *runtimeState
)

func startWireGuardRuntime(userspaceConfig string, localAddresses []string, dnsServers []string, mtu int, socksHost string, socksPort int, socksUsername string, socksPassword string) int {
	return startRuntime("WireGuard", userspaceConfig, localAddresses, dnsServers, mtu, socksHost, socksPort, socksUsername, socksPassword, newWireGuardRuntime)
}

func startAmneziaWGRuntime(userspaceConfig string, localAddresses []string, dnsServers []string, mtu int, socksHost string, socksPort int, socksUsername string, socksPassword string) int {
	return startRuntime("AmneziaWG", userspaceConfig, localAddresses, dnsServers, mtu, socksHost, socksPort, socksUsername, socksPassword, newAmneziaWGRuntime)
}

func startRuntime(protocolName string, userspaceConfig string, localAddresses []string, dnsServers []string, mtu int, socksHost string, socksPort int, socksUsername string, socksPassword string, factory runtimeFactory) int {
	stateMu.Lock()
	defer stateMu.Unlock()

	stopLocked()

	local, err := parseAddrs(localAddresses)
	if err != nil {
		logError("local address parse failed: %v", err)
		return -1
	}
	dns, err := parseAddrs(dnsServers)
	if err != nil {
		logError("dns address parse failed: %v", err)
		return -2
	}
	if len(local) == 0 {
		logError("at least one local %s address is required", protocolName)
		return -3
	}
	if mtu <= 0 {
		mtu = 1420
	}

	userspaceConfig, err = resolveUserspaceConfigEndpoints(userspaceConfig, net.DefaultResolver.LookupIPAddr)
	if err != nil {
		logError("endpoint resolve failed: %v", err)
		return -10
	}

	device, dialer, status, err := factory(local, dns, mtu, userspaceConfig)
	if err != nil {
		logError("%s start failed: %v", protocolName, err)
		return status
	}

	host := socksHost
	if host == "" {
		host = "127.0.0.1"
	}
	listener, err := net.Listen("tcp", net.JoinHostPort(host, strconv.Itoa(socksPort)))
	if err != nil {
		logError("SOCKS listen failed: %v", err)
		device.Close()
		return -7
	}

	ctx, cancel := context.WithCancel(context.Background())
	next := &runtimeState{
		device:   device,
		dialer:   dialer,
		listener: listener,
		cancel:   cancel,
		username: socksUsername,
		password: socksPassword,
		protocol: protocolName,
	}
	state = next
	go acceptLoop(ctx, next)

	_, portStr, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		logError("SOCKS address parse failed: %v", err)
		stopLocked()
		return -8
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		logError("SOCKS port parse failed: %v", err)
		stopLocked()
		return -9
	}
	logDebug("%s started on %s", protocolName, listener.Addr().String())
	return port
}

func newWireGuardRuntime(local []netip.Addr, dns []netip.Addr, mtu int, userspaceConfig string) (wireGuardDevice, tcpDialer, int, error) {
	tunDev, tnet, err := wgnetstack.CreateNetTUN(local, dns, mtu)
	if err != nil {
		return nil, nil, -4, fmt.Errorf("CreateNetTUN failed: %w", err)
	}

	logger := wgdevice.NewLogger(wgdevice.LogLevelError, "Telegram/WireGuard: ")
	dev := wgdevice.NewDevice(tunDev, wgconn.NewDefaultBind(), logger)
	if err = dev.IpcSet(userspaceConfig); err != nil {
		dev.Close()
		return nil, nil, -5, fmt.Errorf("IpcSet failed: %w", err)
	}
	if err = dev.Up(); err != nil {
		dev.Close()
		return nil, nil, -6, fmt.Errorf("device.Up failed: %w", err)
	}
	return dev, tnet, 0, nil
}

func newAmneziaWGRuntime(local []netip.Addr, dns []netip.Addr, mtu int, userspaceConfig string) (wireGuardDevice, tcpDialer, int, error) {
	tunDev, tnet, err := awgnetstack.CreateNetTUN(local, dns, mtu)
	if err != nil {
		return nil, nil, -4, fmt.Errorf("CreateNetTUN failed: %w", err)
	}

	logger := awgdevice.NewLogger(awgdevice.LogLevelError, "Telegram/AmneziaWG: ")
	dev := awgdevice.NewDevice(tunDev, awgconn.NewDefaultBind(), logger)
	if err = dev.IpcSet(userspaceConfig); err != nil {
		dev.Close()
		return nil, nil, -5, fmt.Errorf("IpcSet failed: %w", err)
	}
	if err = dev.Up(); err != nil {
		dev.Close()
		return nil, nil, -6, fmt.Errorf("device.Up failed: %w", err)
	}
	return dev, tnet, 0, nil
}

func stopRuntime() {
	stateMu.Lock()
	defer stateMu.Unlock()
	stopLocked()
}

func onNetworkChangedRuntime() int {
	stateMu.Lock()
	defer stateMu.Unlock()

	if state == nil || state.device == nil {
		logDebug("network changed without active runtime")
		return 0
	}
	if err := state.device.BindUpdate(); err != nil {
		logError("%s BindUpdate failed after network change: %v", state.protocol, err)
		return -1
	}
	logDebug("%s network bind updated", state.protocol)
	return 0
}

func stopLocked() {
	if state == nil {
		return
	}
	if state.cancel != nil {
		state.cancel()
	}
	if state.listener != nil {
		_ = state.listener.Close()
	}
	if state.device != nil {
		state.device.Close()
	}
	state = nil
}

func acceptLoop(ctx context.Context, state *runtimeState) {
	for {
		conn, err := state.listener.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
				logError("%s proxy accept failed: %v", state.protocol, err)
				continue
			}
		}
		go handleProxyConnection(ctx, state, conn)
	}
}

func handleSocks(ctx context.Context, state *runtimeState, client net.Conn) {
	handleProxyConnection(ctx, state, client)
}

func handleProxyConnection(ctx context.Context, state *runtimeState, client net.Conn) {
	defer client.Close()

	reader := bufio.NewReader(client)
	target, protocol, err := proxyHandshake(reader, client, state.username, state.password)
	if err != nil {
		logError("proxy handshake failed: %v", err)
		return
	}

	remote, err := state.dialer.DialContext(ctx, "tcp", target)
	if err != nil {
		if protocol == proxyProtocolSocks5 {
			writeSocksFailure(client)
		} else {
			writeHTTPConnectFailure(client)
		}
		logError("%s dial failed for %s: %v", state.protocol, target, err)
		return
	}
	defer remote.Close()

	if protocol == proxyProtocolSocks5 {
		if _, err = client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
			return
		}
	} else {
		if _, err = io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			return
		}
	}

	done := make(chan struct{}, 2)
	go proxyCopy(remote, reader, done)
	go proxyCopy(client, remote, done)
	<-done
}

func proxyCopy(dst net.Conn, src io.Reader, done chan<- struct{}) {
	_, _ = io.Copy(dst, src)
	if closer, ok := dst.(interface{ CloseWrite() error }); ok {
		_ = closer.CloseWrite()
	}
	done <- struct{}{}
}

func socksHandshake(conn net.Conn, username string, password string) (string, error) {
	return socksHandshakeFromReader(bufio.NewReader(conn), conn, username, password)
}

func proxyHandshake(reader *bufio.Reader, conn net.Conn, username string, password string) (string, proxyProtocol, error) {
	first, err := reader.Peek(1)
	if err != nil {
		return "", proxyProtocolSocks5, err
	}
	if first[0] == 0x05 {
		target, err := socksHandshakeFromReader(reader, conn, username, password)
		return target, proxyProtocolSocks5, err
	}
	target, err := httpConnectHandshake(reader, conn, username, password)
	return target, proxyProtocolHttpConnect, err
}

func socksHandshakeFromReader(reader io.Reader, writer io.Writer, username string, password string) (string, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(reader, header); err != nil {
		return "", err
	}
	if header[0] != 0x05 {
		return "", errors.New("unsupported SOCKS version")
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(reader, methods); err != nil {
		return "", err
	}

	method := byte(0x00)
	if username != "" || password != "" {
		method = 0x02
		if !containsMethod(methods, method) {
			_, _ = writer.Write([]byte{0x05, 0xff})
			return "", errors.New("username/password auth is unavailable")
		}
	} else if !containsMethod(methods, method) {
		_, _ = writer.Write([]byte{0x05, 0xff})
		return "", errors.New("no-auth method is unavailable")
	}
	if _, err := writer.Write([]byte{0x05, method}); err != nil {
		return "", err
	}
	if method == 0x02 {
		if err := socksAuthenticate(reader, writer, username, password); err != nil {
			return "", err
		}
	}

	request := make([]byte, 4)
	if _, err := io.ReadFull(reader, request); err != nil {
		return "", err
	}
	if request[0] != 0x05 || request[1] != 0x01 {
		writeSocksFailure(writer)
		return "", errors.New("only CONNECT is supported")
	}

	host, err := readSocksHost(reader, request[3])
	if err != nil {
		writeSocksFailure(writer)
		return "", err
	}
	portBytes := make([]byte, 2)
	if _, err = io.ReadFull(reader, portBytes); err != nil {
		return "", err
	}
	port := binary.BigEndian.Uint16(portBytes)
	return net.JoinHostPort(host, strconv.Itoa(int(port))), nil
}

func socksAuthenticate(reader io.Reader, writer io.Writer, username string, password string) error {
	version := make([]byte, 1)
	if _, err := io.ReadFull(reader, version); err != nil {
		return err
	}
	if version[0] != 0x01 {
		return errors.New("unsupported auth version")
	}
	userLength := make([]byte, 1)
	if _, err := io.ReadFull(reader, userLength); err != nil {
		return err
	}
	user := make([]byte, int(userLength[0]))
	if _, err := io.ReadFull(reader, user); err != nil {
		return err
	}
	passLength := make([]byte, 1)
	if _, err := io.ReadFull(reader, passLength); err != nil {
		return err
	}
	pass := make([]byte, int(passLength[0]))
	if _, err := io.ReadFull(reader, pass); err != nil {
		return err
	}
	if string(user) != username || string(pass) != password {
		_, _ = writer.Write([]byte{0x01, 0x01})
		return errors.New("invalid auth")
	}
	_, err := writer.Write([]byte{0x01, 0x00})
	return err
}

func readSocksHost(reader io.Reader, addressType byte) (string, error) {
	switch addressType {
	case 0x01:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(reader, ip); err != nil {
			return "", err
		}
		return net.IP(ip).String(), nil
	case 0x03:
		length := make([]byte, 1)
		if _, err := io.ReadFull(reader, length); err != nil {
			return "", err
		}
		host := make([]byte, int(length[0]))
		if _, err := io.ReadFull(reader, host); err != nil {
			return "", err
		}
		return string(host), nil
	case 0x04:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(reader, ip); err != nil {
			return "", err
		}
		return net.IP(ip).String(), nil
	default:
		return "", fmt.Errorf("unsupported address type %d", addressType)
	}
}

func httpConnectHandshake(reader *bufio.Reader, writer io.Writer, username string, password string) (string, error) {
	request, err := http.ReadRequest(reader)
	if err != nil {
		return "", err
	}
	if request.Body != nil {
		_ = request.Body.Close()
	}
	if request.Method != http.MethodConnect {
		_, _ = io.WriteString(writer, "HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n")
		return "", errors.New("only HTTP CONNECT is supported")
	}
	if !httpProxyAuthorized(request, username, password) {
		_, _ = io.WriteString(writer, "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"tg-wg\"\r\nContent-Length: 0\r\n\r\n")
		return "", errors.New("invalid HTTP proxy auth")
	}
	target := request.URL.Host
	if target == "" {
		target = request.Host
	}
	if _, _, err := net.SplitHostPort(target); err != nil {
		_, _ = io.WriteString(writer, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
		return "", fmt.Errorf("invalid CONNECT target %q: %w", target, err)
	}
	return target, nil
}

func httpProxyAuthorized(request *http.Request, username string, password string) bool {
	if username == "" && password == "" {
		return true
	}
	value := request.Header.Get("Proxy-Authorization")
	if len(value) < 6 || !strings.EqualFold(value[:6], "Basic ") {
		return false
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(value[6:]))
	if err != nil {
		return false
	}
	return string(decoded) == username+":"+password
}

func resolveUserspaceConfigEndpoints(config string, lookup endpointResolver) (string, error) {
	if !strings.Contains(config, "endpoint=") {
		return config, nil
	}

	lines := strings.Split(config, "\n")
	for i, line := range lines {
		key, value, found := strings.Cut(line, "=")
		if !found || key != "endpoint" {
			continue
		}

		endpoint, err := resolveUserspaceEndpoint(value, lookup)
		if err != nil {
			return "", err
		}
		lines[i] = key + "=" + endpoint
	}
	return strings.Join(lines, "\n"), nil
}

func resolveUserspaceEndpoint(endpoint string, lookup endpointResolver) (string, error) {
	endpoint = strings.TrimSpace(endpoint)
	if endpoint == "" {
		return "", errors.New("empty endpoint")
	}
	if _, err := netip.ParseAddrPort(endpoint); err == nil {
		return endpoint, nil
	}

	host, portString, err := net.SplitHostPort(endpoint)
	if err != nil {
		return "", fmt.Errorf("invalid endpoint %q: %w", endpoint, err)
	}
	port, err := strconv.ParseUint(portString, 10, 16)
	if err != nil || port == 0 {
		return "", fmt.Errorf("invalid endpoint port %q", portString)
	}

	if addr, err := netip.ParseAddr(host); err == nil {
		return netip.AddrPortFrom(addr, uint16(port)).String(), nil
	}
	if lookup == nil {
		return "", errors.New("endpoint resolver is unavailable")
	}

	ctx, cancel := context.WithTimeout(context.Background(), endpointResolveTimeout)
	defer cancel()

	addrs, err := lookup(ctx, host)
	if err != nil {
		return "", fmt.Errorf("resolve endpoint host %q: %w", host, err)
	}
	for _, ipAddr := range addrs {
		addr, ok := netip.AddrFromSlice(ipAddr.IP)
		if !ok {
			continue
		}
		return netip.AddrPortFrom(addr.Unmap(), uint16(port)).String(), nil
	}
	return "", fmt.Errorf("resolve endpoint host %q: no IP addresses", host)
}

func writeSocksFailure(writer io.Writer) {
	_, _ = writer.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
}

func writeHTTPConnectFailure(writer io.Writer) {
	_, _ = io.WriteString(writer, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
}

func containsMethod(methods []byte, method byte) bool {
	for _, candidate := range methods {
		if candidate == method {
			return true
		}
	}
	return false
}

func parseAddrs(values []string) ([]netip.Addr, error) {
	result := make([]netip.Addr, 0, len(values))
	for _, value := range values {
		if value == "" {
			continue
		}
		prefix, err := netip.ParsePrefix(value)
		if err == nil {
			result = append(result, prefix.Addr())
			continue
		}
		addr, err := netip.ParseAddr(value)
		if err != nil {
			return nil, err
		}
		result = append(result, addr)
	}
	return result, nil
}

func main() {
}
