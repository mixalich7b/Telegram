package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	awgnetstack "github.com/amnezia-vpn/amneziawg-go/tun/netstack"
	wgnetstack "golang.zx2c4.com/wireguard/tun/netstack"
)

const tunnelSocketConnectTimeout = 20 * time.Second

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
	tcp       map[int64]tunnelTCPConn
	udp       map[int64]tunnelUDPConn
	localAddr map[int64]net.Addr
}

type tunnelTCPConn struct {
	conn       net.Conn
	generation uint64
}

type tunnelUDPConn struct {
	conn       net.PacketConn
	generation uint64
}

var sockets = tunnelSocketRegistry{
	nextID:    1,
	tcp:       make(map[int64]tunnelTCPConn),
	udp:       make(map[int64]tunnelUDPConn),
	localAddr: make(map[int64]net.Addr),
}

func activeTunnelNetwork() (tunnelNetwork, uint64, error) {
	stateMu.Lock()
	defer stateMu.Unlock()
	if state == nil || state.network == nil {
		return nil, 0, errTunnelRuntimeUnavailable
	}
	return state.network, state.generation, nil
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

func tunnelGeneration() uint64 {
	stateMu.Lock()
	defer stateMu.Unlock()
	if state == nil || state.network == nil {
		return 0
	}
	return state.generation
}

func tunnelLookupHost(host string) ([]netip.Addr, error) {
	network, _, err := activeTunnelNetwork()
	if err != nil {
		return nil, err
	}
	values, err := network.LookupHost(host)
	if err != nil {
		return nil, err
	}
	result := make([]netip.Addr, 0, len(values))
	for _, value := range values {
		if addr, err := netip.ParseAddr(value); err == nil {
			result = append(result, addr)
		}
	}
	if len(result) == 0 {
		return nil, errTunnelNoSuitableAddress
	}
	return result, nil
}

func openTunnelTCP(host string, port int) (int64, error) {
	network, generation, err := activeTunnelNetwork()
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), tunnelSocketConnectTimeout)
	defer cancel()
	conn, err := network.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return 0, err
	}
	return sockets.registerTCP(conn, generation), nil
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
	written := 0
	for written < len(buffer) {
		n, err := conn.Write(buffer[written:])
		if n > 0 {
			written += n
		}
		if err != nil {
			return written, err
		}
		if n == 0 {
			return written, io.ErrShortWrite
		}
	}
	return written, nil
}

func closeTunnelTCP(id int64) {
	sockets.closeTCP(id)
}

func openTunnelUDP(host string, port int) (int64, error) {
	network, generation, err := activeTunnelNetwork()
	if err != nil {
		return 0, err
	}
	packetConn, err := network.ListenPacket(context.Background(), udpNetworkForHost(host), net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return 0, err
	}
	return sockets.registerUDP(packetConn, generation), nil
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

func (r *tunnelSocketRegistry) registerTCP(conn net.Conn, generation uint64) int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	id := r.nextID
	r.nextID++
	r.tcp[id] = tunnelTCPConn{conn: conn, generation: generation}
	r.localAddr[id] = conn.LocalAddr()
	return id
}

func (r *tunnelSocketRegistry) registerUDP(conn net.PacketConn, generation uint64) int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	id := r.nextID
	r.nextID++
	r.udp[id] = tunnelUDPConn{conn: conn, generation: generation}
	r.localAddr[id] = conn.LocalAddr()
	return id
}

func (r *tunnelSocketRegistry) getTCP(id int64) (net.Conn, error) {
	generation := tunnelGeneration()
	r.mu.Lock()
	defer r.mu.Unlock()
	entry, ok := r.tcp[id]
	if !ok || entry.conn == nil || entry.generation != generation {
		return nil, errTunnelSocketNotFound
	}
	return entry.conn, nil
}

func (r *tunnelSocketRegistry) getUDP(id int64) (net.PacketConn, error) {
	generation := tunnelGeneration()
	r.mu.Lock()
	defer r.mu.Unlock()
	entry, ok := r.udp[id]
	if !ok || entry.conn == nil || entry.generation != generation {
		return nil, errTunnelSocketNotFound
	}
	return entry.conn, nil
}

func (r *tunnelSocketRegistry) getLocalAddr(id int64) (net.Addr, error) {
	generation := tunnelGeneration()
	r.mu.Lock()
	defer r.mu.Unlock()
	if entry, ok := r.tcp[id]; ok && entry.generation != generation {
		return nil, errTunnelSocketNotFound
	}
	if entry, ok := r.udp[id]; ok && entry.generation != generation {
		return nil, errTunnelSocketNotFound
	}
	addr := r.localAddr[id]
	if addr == nil {
		return nil, errTunnelSocketNotFound
	}
	return addr, nil
}

func (r *tunnelSocketRegistry) closeTCP(id int64) {
	r.mu.Lock()
	entry := r.tcp[id]
	delete(r.tcp, id)
	delete(r.localAddr, id)
	r.mu.Unlock()
	if entry.conn != nil {
		_ = entry.conn.Close()
	}
}

func (r *tunnelSocketRegistry) closeUDP(id int64) {
	r.mu.Lock()
	entry := r.udp[id]
	delete(r.udp, id)
	delete(r.localAddr, id)
	r.mu.Unlock()
	if entry.conn != nil {
		_ = entry.conn.Close()
	}
}

func (r *tunnelSocketRegistry) closeAll() {
	r.mu.Lock()
	tcp := r.tcp
	udp := r.udp
	r.tcp = make(map[int64]tunnelTCPConn)
	r.udp = make(map[int64]tunnelUDPConn)
	r.localAddr = make(map[int64]net.Addr)
	r.mu.Unlock()
	for _, entry := range tcp {
		if entry.conn != nil {
			_ = entry.conn.Close()
		}
	}
	for _, entry := range udp {
		if entry.conn != nil {
			_ = entry.conn.Close()
		}
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
	network, _, err := activeTunnelNetwork()
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
