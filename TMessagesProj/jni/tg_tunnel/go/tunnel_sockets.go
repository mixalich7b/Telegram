package main

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	awgnetstack "github.com/amnezia-vpn/amneziawg-go/tun/netstack"
	wgnetstack "golang.zx2c4.com/wireguard/tun/netstack"
)

const tunnelSocketConnectTimeout = 15 * time.Second

var (
	errTunnelRuntimeUnavailable = errors.New("tunnel runtime is unavailable")
	errTunnelSocketNotFound     = errors.New("tunnel socket not found")
	errTunnelNumericPort        = errors.New("port must be numeric")
	errTunnelNoSuitableAddress  = errors.New("no suitable address found")
)

type wireGuardNet struct {
	net   *wgnetstack.Net
	local []netip.Addr
}

func (n wireGuardNet) DialContext(ctx context.Context, network string, address string) (net.Conn, error) {
	return n.net.DialContext(ctx, network, address)
}

func (n wireGuardNet) ListenPacket(ctx context.Context, network string, address string) (net.PacketConn, error) {
	addr, err := resolveListenUDPAddr(network, address)
	if err != nil {
		return nil, err
	}
	return n.net.ListenUDP(addr)
}

func (n wireGuardNet) LookupHost(host string) ([]string, error) {
	return n.net.LookupHost(host)
}

func (n wireGuardNet) LocalAddresses() []netip.Addr {
	return append([]netip.Addr(nil), n.local...)
}

type amneziaWGNet struct {
	net   *awgnetstack.Net
	local []netip.Addr
}

func (n amneziaWGNet) DialContext(ctx context.Context, network string, address string) (net.Conn, error) {
	return n.net.DialContext(ctx, network, address)
}

func (n amneziaWGNet) ListenPacket(ctx context.Context, network string, address string) (net.PacketConn, error) {
	addr, err := resolveListenUDPAddr(network, address)
	if err != nil {
		return nil, err
	}
	return n.net.ListenUDP(addr)
}

func (n amneziaWGNet) LookupHost(host string) ([]string, error) {
	return n.net.LookupHost(host)
}

func (n amneziaWGNet) LocalAddresses() []netip.Addr {
	return append([]netip.Addr(nil), n.local...)
}

type tunnelSocketRegistry struct {
	mu        sync.Mutex
	nextID    int64
	tcp       map[int64]net.Conn
	udp       map[int64]net.PacketConn
	localAddr map[int64]net.Addr
}

var sockets = tunnelSocketRegistry{
	nextID:    1,
	tcp:       make(map[int64]net.Conn),
	udp:       make(map[int64]net.PacketConn),
	localAddr: make(map[int64]net.Addr),
}

func activeTunnelNetwork() (tunnelNetwork, error) {
	stateMu.Lock()
	defer stateMu.Unlock()
	if state == nil || state.network == nil {
		return nil, errTunnelRuntimeUnavailable
	}
	return state.network, nil
}

func tunnelIsRunning() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return state != nil && state.network != nil
}

func tunnelLocalAddresses() []netip.Addr {
	stateMu.Lock()
	defer stateMu.Unlock()
	if state == nil || state.network == nil {
		return nil
	}
	return state.network.LocalAddresses()
}

func openTunnelTCP(host string, port int) (int64, error) {
	network, err := activeTunnelNetwork()
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), tunnelSocketConnectTimeout)
	defer cancel()
	conn, err := network.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return 0, err
	}
	return sockets.registerTCP(conn), nil
}

func tunnelTCPRead(id int64, buffer []byte) (int, error) {
	conn, err := sockets.getTCP(id)
	if err != nil {
		return 0, err
	}
	return conn.Read(buffer)
}

func tunnelTCPWrite(id int64, buffer []byte) (int, error) {
	conn, err := sockets.getTCP(id)
	if err != nil {
		return 0, err
	}
	return conn.Write(buffer)
}

func closeTunnelTCP(id int64) {
	sockets.closeTCP(id)
}

func openTunnelUDP(host string, port int) (int64, error) {
	network, err := activeTunnelNetwork()
	if err != nil {
		return 0, err
	}
	packetConn, err := network.ListenPacket(context.Background(), udpNetworkForHost(host), net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return 0, err
	}
	return sockets.registerUDP(packetConn), nil
}

func tunnelUDPSendTo(id int64, buffer []byte, host string, port int) (int, error) {
	packetConn, err := sockets.getUDP(id)
	if err != nil {
		return 0, err
	}
	addr, err := resolveRemoteUDPAddr(host, port)
	if err != nil {
		return 0, err
	}
	return packetConn.WriteTo(buffer, addr)
}

func tunnelUDPRecvFrom(id int64, buffer []byte) (int, string, int, error) {
	packetConn, err := sockets.getUDP(id)
	if err != nil {
		return 0, "", 0, err
	}
	n, addr, err := packetConn.ReadFrom(buffer)
	if err != nil {
		return 0, "", 0, err
	}
	host, port, err := splitNetAddr(addr)
	if err != nil {
		return 0, "", 0, err
	}
	return n, host, port, nil
}

func tunnelUDPLocalAddr(id int64) (string, int, error) {
	addr, err := sockets.getLocalAddr(id)
	if err != nil {
		return "", 0, err
	}
	return splitNetAddr(addr)
}

func closeTunnelUDP(id int64) {
	sockets.closeUDP(id)
}

func closeTunnelSocketsLocked() {
	sockets.closeAll()
}

func (r *tunnelSocketRegistry) registerTCP(conn net.Conn) int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	id := r.nextID
	r.nextID++
	r.tcp[id] = conn
	r.localAddr[id] = conn.LocalAddr()
	return id
}

func (r *tunnelSocketRegistry) registerUDP(conn net.PacketConn) int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	id := r.nextID
	r.nextID++
	r.udp[id] = conn
	r.localAddr[id] = conn.LocalAddr()
	return id
}

func (r *tunnelSocketRegistry) getTCP(id int64) (net.Conn, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	conn := r.tcp[id]
	if conn == nil {
		return nil, errTunnelSocketNotFound
	}
	return conn, nil
}

func (r *tunnelSocketRegistry) getUDP(id int64) (net.PacketConn, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	conn := r.udp[id]
	if conn == nil {
		return nil, errTunnelSocketNotFound
	}
	return conn, nil
}

func (r *tunnelSocketRegistry) getLocalAddr(id int64) (net.Addr, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	addr := r.localAddr[id]
	if addr == nil {
		return nil, errTunnelSocketNotFound
	}
	return addr, nil
}

func (r *tunnelSocketRegistry) closeTCP(id int64) {
	r.mu.Lock()
	conn := r.tcp[id]
	delete(r.tcp, id)
	delete(r.localAddr, id)
	r.mu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
}

func (r *tunnelSocketRegistry) closeUDP(id int64) {
	r.mu.Lock()
	conn := r.udp[id]
	delete(r.udp, id)
	delete(r.localAddr, id)
	r.mu.Unlock()
	if conn != nil {
		_ = conn.Close()
	}
}

func (r *tunnelSocketRegistry) closeAll() {
	r.mu.Lock()
	tcp := r.tcp
	udp := r.udp
	r.tcp = make(map[int64]net.Conn)
	r.udp = make(map[int64]net.PacketConn)
	r.localAddr = make(map[int64]net.Addr)
	r.mu.Unlock()
	for _, conn := range tcp {
		_ = conn.Close()
	}
	for _, conn := range udp {
		_ = conn.Close()
	}
}

func resolveListenUDPAddr(network string, address string) (*net.UDPAddr, error) {
	if network == "" {
		network = "udp"
	}
	host, portString, err := net.SplitHostPort(address)
	if err != nil {
		return nil, err
	}
	port, err := strconv.Atoi(portString)
	if err != nil || port < 0 || port > 65535 {
		return nil, errTunnelNumericPort
	}
	if host == "" {
		return &net.UDPAddr{Port: port}, nil
	}
	addr, err := netip.ParseAddr(host)
	if err != nil {
		return nil, err
	}
	return &net.UDPAddr{IP: net.IP(addr.AsSlice()), Port: port}, nil
}

func resolveRemoteUDPAddr(host string, port int) (*net.UDPAddr, error) {
	if port < 0 || port > 65535 {
		return nil, errTunnelNumericPort
	}
	if addr, err := netip.ParseAddr(host); err == nil {
		return &net.UDPAddr{IP: net.IP(addr.AsSlice()), Port: port}, nil
	}
	network, err := activeTunnelNetwork()
	if err != nil {
		return nil, err
	}
	addrs, err := network.LookupHost(host)
	if err != nil {
		return nil, err
	}
	for _, value := range addrs {
		if addr, err := netip.ParseAddr(value); err == nil {
			return &net.UDPAddr{IP: net.IP(addr.AsSlice()), Port: port}, nil
		}
	}
	return nil, errTunnelNoSuitableAddress
}

func splitNetAddr(addr net.Addr) (string, int, error) {
	if udpAddr, ok := addr.(*net.UDPAddr); ok {
		return udpAddr.IP.String(), udpAddr.Port, nil
	}
	if tcpAddr, ok := addr.(*net.TCPAddr); ok {
		return tcpAddr.IP.String(), tcpAddr.Port, nil
	}
	host, portString, err := net.SplitHostPort(addr.String())
	if err != nil {
		return "", 0, err
	}
	port, err := strconv.Atoi(portString)
	if err != nil {
		return "", 0, err
	}
	return host, port, nil
}

func udpNetworkForHost(host string) string {
	addr, err := netip.ParseAddr(host)
	if err == nil {
		if addr.Is4() {
			return "udp4"
		}
		if addr.Is6() {
			return "udp6"
		}
	}
	return "udp"
}

func firstAddressForFamily(addrs []netip.Addr, family int) (netip.Addr, bool) {
	for _, addr := range addrs {
		if (family == 4 && addr.Is4()) || (family == 6 && addr.Is6()) {
			return addr, true
		}
	}
	return netip.Addr{}, false
}

func formatSocketError(op string, err error) error {
	if err == nil {
		return nil
	}
	return fmt.Errorf("%s: %w", op, err)
}
