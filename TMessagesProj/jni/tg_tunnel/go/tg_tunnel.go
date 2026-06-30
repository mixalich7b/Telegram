package main

import (
	"context"
	"errors"
	"fmt"
	"net"
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

type tunnelNetwork interface {
	tcpDialer
	ListenPacket(ctx context.Context, network string, address string) (net.PacketConn, error)
	LookupHost(host string) ([]string, error)
	LocalAddresses() []netip.Addr
}

type wireGuardDevice interface {
	BindUpdate() error
	Close()
}

type runtimeFactory func(local []netip.Addr, dns []netip.Addr, mtu int, userspaceConfig string) (wireGuardDevice, tunnelNetwork, int, error)

const endpointResolveTimeout = 10 * time.Second

type endpointResolver func(ctx context.Context, host string) ([]net.IPAddr, error)

type runtimeState struct {
	device     wireGuardDevice
	network    tunnelNetwork
	cancel     context.CancelFunc
	protocol   string
	generation uint64
}

var (
	stateMu        sync.Mutex
	state          *runtimeState
	nextGeneration uint64
)

func startWireGuardRuntime(userspaceConfig string, localAddresses []string, dnsServers []string, mtu int) int {
	return startRuntime("WireGuard", userspaceConfig, localAddresses, dnsServers, mtu, newWireGuardRuntime)
}

func startAmneziaWGRuntime(userspaceConfig string, localAddresses []string, dnsServers []string, mtu int) int {
	return startRuntime("AmneziaWG", userspaceConfig, localAddresses, dnsServers, mtu, newAmneziaWGRuntime)
}

func startRuntime(protocolName string, userspaceConfig string, localAddresses []string, dnsServers []string, mtu int, factory runtimeFactory) int {
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

	device, network, status, err := factory(local, dns, mtu, userspaceConfig)
	if err != nil {
		logError("%s start failed: %v", protocolName, err)
		return status
	}

	_, cancel := context.WithCancel(context.Background())
	nextGeneration++
	generation := nextGeneration
	if generation == 0 {
		nextGeneration++
		generation = nextGeneration
	}
	next := &runtimeState{
		device:     device,
		network:    network,
		cancel:     cancel,
		protocol:   protocolName,
		generation: generation,
	}
	state = next
	logDebug("%s started", protocolName)
	return 0
}

func newWireGuardRuntime(local []netip.Addr, dns []netip.Addr, mtu int, userspaceConfig string) (wireGuardDevice, tunnelNetwork, int, error) {
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
	return dev, wireGuardNet{net: tnet, local: append([]netip.Addr(nil), local...)}, 0, nil
}

func newAmneziaWGRuntime(local []netip.Addr, dns []netip.Addr, mtu int, userspaceConfig string) (wireGuardDevice, tunnelNetwork, int, error) {
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
	return dev, amneziaWGNet{net: tnet, local: append([]netip.Addr(nil), local...)}, 0, nil
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
	closeTunnelSocketsLocked()
	if state.cancel != nil {
		state.cancel()
	}
	if state.device != nil {
		state.device.Close()
	}
	state = nil
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
