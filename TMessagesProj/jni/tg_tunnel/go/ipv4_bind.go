package main

import (
	"errors"
	"net"
	"net/netip"
	"sync"

	awgconn "github.com/amnezia-vpn/amneziawg-go/conn"
	wgconn "golang.zx2c4.com/wireguard/conn"
)

// An IPv4-only outer socket also prevents authenticated endpoint roaming to
// IPv6. Dropping IPv6 DNS answers alone would not enforce the outer policy.
type ipv4Bind struct {
	mu   sync.Mutex
	conn *net.UDPConn
}

func (b *ipv4Bind) open(port uint16) (*net.UDPConn, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.conn != nil {
		return nil, 0, errors.New("bind already open")
	}
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: int(port)})
	if err != nil {
		return nil, 0, err
	}
	b.conn = conn
	return conn, uint16(conn.LocalAddr().(*net.UDPAddr).Port), nil
}
func (b *ipv4Bind) Close() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.conn == nil {
		return nil
	}
	err := b.conn.Close()
	b.conn = nil
	return err
}
func (b *ipv4Bind) send(bufs [][]byte, addr netip.AddrPort) error {
	if !addr.Addr().Is4() {
		return errIPv6Disabled
	}
	b.mu.Lock()
	conn := b.conn
	b.mu.Unlock()
	if conn == nil {
		return net.ErrClosed
	}
	for _, buf := range bufs {
		if _, err := conn.WriteToUDPAddrPort(buf, addr); err != nil {
			return err
		}
	}
	return nil
}
func (*ipv4Bind) SetMark(mark uint32) error {
	if mark != 0 {
		return errors.New("socket marks are unsupported")
	}
	return nil
}
func (*ipv4Bind) BatchSize() int { return 1 }

type ipv4WireGuardBind struct {
	ipv4Bind
	parser wgconn.Bind
}

func newWireGuardBind() wgconn.Bind {
	if ipv6Enabled() {
		return wgconn.NewDefaultBind()
	}
	return &ipv4WireGuardBind{parser: wgconn.NewDefaultBind()}
}
func (b *ipv4WireGuardBind) ParseEndpoint(value string) (wgconn.Endpoint, error) {
	addr, err := netip.ParseAddrPort(value)
	if err != nil {
		return nil, err
	}
	if !addr.Addr().Is4() {
		return nil, errIPv6Disabled
	}
	return b.parser.ParseEndpoint(value)
}
func (b *ipv4WireGuardBind) Open(port uint16) ([]wgconn.ReceiveFunc, uint16, error) {
	conn, actual, err := b.open(port)
	if err != nil {
		return nil, 0, err
	}
	receive := func(packets [][]byte, sizes []int, eps []wgconn.Endpoint) (int, error) {
		n, addr, err := conn.ReadFromUDPAddrPort(packets[0])
		if err != nil {
			return 0, err
		}
		ep, err := b.ParseEndpoint(addr.String())
		if err != nil {
			return 0, err
		}
		sizes[0] = n
		eps[0] = ep
		return 1, nil
	}
	return []wgconn.ReceiveFunc{receive}, actual, nil
}
func (b *ipv4WireGuardBind) Send(bufs [][]byte, ep wgconn.Endpoint) error {
	addr, err := netip.ParseAddrPort(ep.DstToString())
	if err != nil {
		return err
	}
	return b.send(bufs, addr)
}

type ipv4AmneziaBind struct {
	ipv4Bind
	parser awgconn.Bind
}

func newAmneziaBind() awgconn.Bind {
	if ipv6Enabled() {
		return awgconn.NewDefaultBind()
	}
	return &ipv4AmneziaBind{parser: awgconn.NewDefaultBind()}
}
func (b *ipv4AmneziaBind) ParseEndpoint(value string) (awgconn.Endpoint, error) {
	addr, err := netip.ParseAddrPort(value)
	if err != nil {
		return nil, err
	}
	if !addr.Addr().Is4() {
		return nil, errIPv6Disabled
	}
	return b.parser.ParseEndpoint(value)
}
func (b *ipv4AmneziaBind) Open(port uint16) ([]awgconn.ReceiveFunc, uint16, error) {
	conn, actual, err := b.open(port)
	if err != nil {
		return nil, 0, err
	}
	receive := func(packets [][]byte, sizes []int, eps []awgconn.Endpoint) (int, error) {
		n, addr, err := conn.ReadFromUDPAddrPort(packets[0])
		if err != nil {
			return 0, err
		}
		ep, err := b.ParseEndpoint(addr.String())
		if err != nil {
			return 0, err
		}
		sizes[0] = n
		eps[0] = ep
		return 1, nil
	}
	return []awgconn.ReceiveFunc{receive}, actual, nil
}
func (b *ipv4AmneziaBind) Send(bufs [][]byte, ep awgconn.Endpoint) error {
	addr, err := netip.ParseAddrPort(ep.DstToString())
	if err != nil {
		return err
	}
	return b.send(bufs, addr)
}
