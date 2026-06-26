#include "TunnelPacketSocketFactory.h"

#include "api/async_dns_resolver.h"
#include "p2p/base/async_stun_tcp_socket.h"
#include "rtc_base/async_tcp_socket.h"
#include "rtc_base/async_dns_resolver.h"
#include "rtc_base/byte_order.h"
#include "rtc_base/logging.h"
#include "rtc_base/network/received_packet.h"
#include "rtc_base/network/sent_packet.h"
#include "rtc_base/socket_adapters.h"
#include "rtc_base/ssl_adapter.h"
#include "rtc_base/time_utils.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netdb.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace tgcalls {
namespace {

constexpr const char *kTag = "Telegram/TunnelVoip";
constexpr size_t kMaxPacketSize = 64 * 1024;
constexpr size_t kTcpPacketLengthSize = 2;
constexpr size_t kStunHeaderSize = 20;
constexpr size_t kTurnChannelDataHeaderSize = 4;
constexpr uint32_t kRawTcpPrologue = 0xeeeeeeeeU;

using IsRunningFunc = int (*)();
using GetLocalAddressFunc = int (*)(int, char *, int);
using LookupHostFunc = int (*)(const char *, char *, int);
using TcpConnectFunc = int64_t (*)(const char *, int);
using TcpReadFunc = int (*)(int64_t, void *, int);
using TcpWriteFunc = int (*)(int64_t, const void *, int);
using TcpCloseFunc = void (*)(int64_t);
using UdpOpenFunc = int64_t (*)(const char *, int);
using UdpSendToFunc = int (*)(int64_t, const void *, int, const char *, int);
using UdpRecvFromFunc = int (*)(int64_t, void *, int, char *, int, int *);
using UdpLocalAddressFunc = int (*)(int64_t, char *, int, int *);
using UdpCloseFunc = void (*)(int64_t);

class TunnelBridge {
public:
    static TunnelBridge &Shared() {
        static TunnelBridge bridge;
        return bridge;
    }

    bool load() {
        std::lock_guard<std::mutex> lock(_mutex);
        if (_loaded) {
            return true;
        }
        _handle = dlopen("libtg-tunnel-go.so", RTLD_NOW);
        if (_handle == nullptr) {
            const char *error = dlerror();
            __android_log_print(ANDROID_LOG_ERROR, kTag, "dlopen libtg-tunnel-go.so failed: %s", error != nullptr ? error : "unknown");
            return false;
        }
        isRunning = reinterpret_cast<IsRunningFunc>(dlsym(_handle, "tgTunnelIsRunning"));
        getLocalAddress = reinterpret_cast<GetLocalAddressFunc>(dlsym(_handle, "tgTunnelGetLocalAddress"));
        lookupHost = reinterpret_cast<LookupHostFunc>(dlsym(_handle, "tgTunnelLookupHost"));
        tcpConnect = reinterpret_cast<TcpConnectFunc>(dlsym(_handle, "tgTunnelTcpConnect"));
        tcpRead = reinterpret_cast<TcpReadFunc>(dlsym(_handle, "tgTunnelTcpRead"));
        tcpWrite = reinterpret_cast<TcpWriteFunc>(dlsym(_handle, "tgTunnelTcpWrite"));
        tcpClose = reinterpret_cast<TcpCloseFunc>(dlsym(_handle, "tgTunnelTcpClose"));
        udpOpen = reinterpret_cast<UdpOpenFunc>(dlsym(_handle, "tgTunnelUdpOpen"));
        udpSendTo = reinterpret_cast<UdpSendToFunc>(dlsym(_handle, "tgTunnelUdpSendTo"));
        udpRecvFrom = reinterpret_cast<UdpRecvFromFunc>(dlsym(_handle, "tgTunnelUdpRecvFrom"));
        udpLocalAddress = reinterpret_cast<UdpLocalAddressFunc>(dlsym(_handle, "tgTunnelUdpLocalAddress"));
        udpClose = reinterpret_cast<UdpCloseFunc>(dlsym(_handle, "tgTunnelUdpClose"));
        _loaded = isRunning && getLocalAddress && lookupHost && tcpConnect && tcpRead && tcpWrite && tcpClose && udpOpen && udpSendTo && udpRecvFrom && udpLocalAddress && udpClose;
        if (!_loaded) {
            __android_log_write(ANDROID_LOG_ERROR, kTag, "required tunnel socket symbols are missing");
            dlclose(_handle);
            _handle = nullptr;
        }
        return _loaded;
    }

    bool running() {
        return load() && isRunning() == 1;
    }

    IsRunningFunc isRunning = nullptr;
    GetLocalAddressFunc getLocalAddress = nullptr;
    LookupHostFunc lookupHost = nullptr;
    TcpConnectFunc tcpConnect = nullptr;
    TcpReadFunc tcpRead = nullptr;
    TcpWriteFunc tcpWrite = nullptr;
    TcpCloseFunc tcpClose = nullptr;
    UdpOpenFunc udpOpen = nullptr;
    UdpSendToFunc udpSendTo = nullptr;
    UdpRecvFromFunc udpRecvFrom = nullptr;
    UdpLocalAddressFunc udpLocalAddress = nullptr;
    UdpCloseFunc udpClose = nullptr;

private:
    std::mutex _mutex;
    void *_handle = nullptr;
    bool _loaded = false;
};

std::string SocketHost(const rtc::SocketAddress &address) {
    if (!address.hostname().empty()) {
        return address.hostname();
    }
    return address.ipaddr().ToString();
}

bool ParseIPAddress(const std::string &value, rtc::IPAddress *address) {
    in_addr ipv4;
    if (inet_pton(AF_INET, value.c_str(), &ipv4) == 1) {
        *address = rtc::IPAddress(ipv4);
        return true;
    }
    in6_addr ipv6;
    if (inet_pton(AF_INET6, value.c_str(), &ipv6) == 1) {
        *address = rtc::IPAddress(ipv6);
        return true;
    }
    return false;
}

rtc::SocketAddress SocketAddressFromHostPort(const std::string &host, int port) {
    rtc::IPAddress address;
    if (ParseIPAddress(host, &address)) {
        return rtc::SocketAddress(address, port);
    }
    return rtc::SocketAddress(host, port);
}

void AppendLE32(std::vector<uint8_t> *bytes, uint32_t value) {
    bytes->push_back(static_cast<uint8_t>(value & 0xff));
    bytes->push_back(static_cast<uint8_t>((value >> 8) & 0xff));
    bytes->push_back(static_cast<uint8_t>((value >> 16) & 0xff));
    bytes->push_back(static_cast<uint8_t>((value >> 24) & 0xff));
}

std::vector<rtc::IPAddress> LookupTunnelIPs(const std::string &host, int family, int *error) {
    *error = 0;
    rtc::IPAddress literal;
    if (ParseIPAddress(host, &literal)) {
        if (family == AF_UNSPEC || literal.family() == family) {
            return { literal };
        }
        return {};
    }
    if (!TunnelBridge::Shared().running()) {
        *error = ENETDOWN;
        return {};
    }
    char buffer[4096] = {0};
    int count = TunnelBridge::Shared().lookupHost(host.c_str(), buffer, sizeof(buffer));
    if (count < 0) {
        *error = EHOSTUNREACH;
        return {};
    }
    std::vector<rtc::IPAddress> result;
    std::stringstream stream(buffer);
    std::string item;
    while (std::getline(stream, item)) {
        rtc::IPAddress address;
        if (ParseIPAddress(item, &address) && (family == AF_UNSPEC || address.family() == family)) {
            result.push_back(address);
        }
    }
    if (result.empty()) {
        *error = EAI_NONAME;
    }
    return result;
}

bool IsStunMessage(uint16_t messageType) {
    return (messageType & 0xC000) == 0;
}

size_t ExpectedStunTcpPacketLength(const uint8_t *data, size_t size, size_t *padding) {
    *padding = 0;
    if (size < 4) {
        return 0;
    }
    const uint16_t messageType = rtc::GetBE16(data);
    const uint16_t length = rtc::GetBE16(data + 2);
    size_t expected = IsStunMessage(messageType) ? kStunHeaderSize + length : kTurnChannelDataHeaderSize + length;
    if (!IsStunMessage(messageType) && expected % 4 != 0) {
        *padding = 4 - (expected % 4);
    }
    return expected;
}

class TunnelAsyncPacketSocket : public rtc::AsyncPacketSocket {
public:
    explicit TunnelAsyncPacketSocket(rtc::Thread *thread) :
    _thread(thread),
    _alive(std::make_shared<std::atomic<bool>>(true)) {
    }

    ~TunnelAsyncPacketSocket() override {
        _alive->store(false);
    }

    rtc::SocketAddress GetLocalAddress() const override {
        return _localAddress;
    }

    rtc::SocketAddress GetRemoteAddress() const override {
        return _remoteAddress;
    }

    int GetOption(rtc::Socket::Option, int *value) override {
        if (value != nullptr) {
            *value = 0;
        }
        return 0;
    }

    int SetOption(rtc::Socket::Option, int) override {
        return 0;
    }

    int GetError() const override {
        return _error;
    }

    void SetError(int error) override {
        _error = error;
    }

protected:
    void postPacket(std::vector<uint8_t> bytes, rtc::SocketAddress remoteAddress) {
        auto alive = _alive;
        _thread->PostTask([alive, this, bytes = std::move(bytes), remoteAddress] {
            if (!alive->load()) {
                return;
            }
            NotifyPacketReceived(rtc::ReceivedPacket::CreateFromLegacy(
                reinterpret_cast<const char *>(bytes.data()),
                bytes.size(),
                rtc::TimeMicros(),
                remoteAddress));
        });
    }

    void postConnected() {
        auto alive = _alive;
        _thread->PostTask([alive, this] {
            if (!alive->load()) {
                return;
            }
            SignalConnect(this);
            SignalReadyToSend(this);
        });
    }

    void postClosed(int error) {
        auto alive = _alive;
        _thread->PostTask([alive, this, error] {
            if (!alive->load()) {
                return;
            }
            NotifyClosed(error);
        });
    }

    void signalSent(size_t size, const rtc::PacketOptions &options, bool connectionless) {
        rtc::SentPacket sentPacket(options.packet_id, rtc::TimeMillis(), options.info_signaled_after_sent);
        rtc::CopySocketInformationToPacketInfo(size, *this, connectionless, &sentPacket.info);
        SignalSentPacket(this, sentPacket);
    }

    rtc::Thread *_thread = nullptr;
    rtc::SocketAddress _localAddress;
    rtc::SocketAddress _remoteAddress;
    int _error = 0;
    std::shared_ptr<std::atomic<bool>> _alive;
};

class TunnelUdpPacketSocket final : public TunnelAsyncPacketSocket {
public:
    TunnelUdpPacketSocket(rtc::Thread *thread, int64_t handle, rtc::SocketAddress localAddress) :
    TunnelAsyncPacketSocket(thread),
    _handle(handle) {
        _localAddress = localAddress;
        _reader = std::thread([this] {
            readLoop();
        });
        _thread->PostTask([alive = _alive, this] {
            if (alive->load()) {
                SignalAddressReady(this, _localAddress);
                SignalReadyToSend(this);
            }
        });
    }

    ~TunnelUdpPacketSocket() override {
        Close();
    }

    int Send(const void *data, size_t size, const rtc::PacketOptions &options) override {
        if (_remoteAddress.IsNil()) {
            SetError(ENOTCONN);
            return -1;
        }
        return SendTo(data, size, _remoteAddress, options);
    }

    int SendTo(const void *data, size_t size, const rtc::SocketAddress &address, const rtc::PacketOptions &options) override {
        int64_t handle = _handle.load();
        if (handle == 0 || !TunnelBridge::Shared().load()) {
            SetError(ENETDOWN);
            return -1;
        }
        const std::string host = SocketHost(address);
        int sent = TunnelBridge::Shared().udpSendTo(handle, data, static_cast<int>(size), host.c_str(), address.port());
        if (sent < 0) {
            SetError(EIO);
            return -1;
        }
        signalSent(size, options, true);
        return sent;
    }

    int Close() override {
        int64_t handle = _handle.exchange(0);
        if (handle != 0 && TunnelBridge::Shared().load()) {
            _alive->store(false);
            TunnelBridge::Shared().udpClose(handle);
        }
        if (_reader.joinable() && _reader.get_id() != std::this_thread::get_id()) {
            _reader.join();
        }
        return 0;
    }

    State GetState() const override {
        return _handle.load() == 0 ? STATE_CLOSED : STATE_BOUND;
    }

private:
    void readLoop() {
        std::vector<uint8_t> buffer(kMaxPacketSize);
        while (_handle.load() != 0) {
            char host[128] = {0};
            int port = 0;
            int read = TunnelBridge::Shared().udpRecvFrom(_handle.load(), buffer.data(), static_cast<int>(buffer.size()), host, sizeof(host), &port);
            if (read <= 0) {
                break;
            }
            std::vector<uint8_t> packet(buffer.begin(), buffer.begin() + read);
            postPacket(std::move(packet), SocketAddressFromHostPort(host, port));
        }
        postClosed(0);
    }

    std::atomic<int64_t> _handle;
    std::thread _reader;
};

enum class TunnelTcpFraming {
    Length16,
    StunTurn,
    RawReflector,
};

class TunnelTcpAsyncSocket final : public rtc::Socket {
public:
    explicit TunnelTcpAsyncSocket(rtc::Thread *thread) :
    _thread(thread),
    _alive(std::make_shared<std::atomic<bool>>(true)) {
    }

    ~TunnelTcpAsyncSocket() override {
        _alive->store(false);
        Close();
    }

    rtc::SocketAddress GetLocalAddress() const override {
        std::lock_guard<std::mutex> lock(_mutex);
        return _localAddress;
    }

    rtc::SocketAddress GetRemoteAddress() const override {
        std::lock_guard<std::mutex> lock(_mutex);
        return _remoteAddress;
    }

    int Bind(const rtc::SocketAddress &addr) override {
        if (GetState() != rtc::Socket::CS_CLOSED) {
            SetError(EINVAL);
            return -1;
        }
        std::lock_guard<std::mutex> lock(_mutex);
        _localAddress = addr;
        return 0;
    }

    int Connect(const rtc::SocketAddress &addr) override {
        if (GetState() != rtc::Socket::CS_CLOSED) {
            SetError(EALREADY);
            return -1;
        }
        if (!TunnelBridge::Shared().running()) {
            SetError(ENETDOWN);
            return -1;
        }
        const std::string host = SocketHost(addr);
        int64_t handle = TunnelBridge::Shared().tcpConnect(host.c_str(), addr.port());
        if (handle <= 0) {
            SetError(EHOSTUNREACH);
            return -1;
        }
        {
            std::lock_guard<std::mutex> lock(_mutex);
            _remoteAddress = addr;
        }
        _handle.store(handle);
        _state.store(rtc::Socket::CS_CONNECTED);
        _reader = std::thread([this] {
            readLoop();
        });
        postConnectAndWritable();
        return 0;
    }

    int Send(const void *data, size_t size) override {
        if (size == 0) {
            return 0;
        }
        if (size > static_cast<size_t>(INT32_MAX)) {
            SetError(EMSGSIZE);
            return -1;
        }
        int64_t handle = _handle.load();
        if (handle == 0 || GetState() != rtc::Socket::CS_CONNECTED || !TunnelBridge::Shared().load()) {
            SetError(ENOTCONN);
            return -1;
        }
        int written = TunnelBridge::Shared().tcpWrite(handle, data, static_cast<int>(size));
        if (written != static_cast<int>(size)) {
            SetError(EIO);
            closeHandle(EIO, true);
            return -1;
        }
        postWritable();
        return static_cast<int>(size);
    }

    int SendTo(const void *data, size_t size, const rtc::SocketAddress &addr) override {
        if (addr == GetRemoteAddress()) {
            return Send(data, size);
        }
        SetError(ENOTCONN);
        return -1;
    }

    int Recv(void *data, size_t size, int64_t *) override {
        if (size == 0) {
            return 0;
        }
        std::lock_guard<std::mutex> lock(_mutex);
        if (_input.empty()) {
            if (_state.load() == rtc::Socket::CS_CLOSED) {
                _error.store(ENOTCONN);
                return 0;
            }
            _error.store(EWOULDBLOCK);
            return -1;
        }
        const size_t available = std::min(size, _input.size());
        memcpy(data, _input.data(), available);
        if (available < _input.size()) {
            memmove(_input.data(), _input.data() + available, _input.size() - available);
        }
        _input.resize(_input.size() - available);
        _error.store(0);
        return static_cast<int>(available);
    }

    int RecvFrom(void *data, size_t size, rtc::SocketAddress *addr, int64_t *timestamp) override {
        int read = Recv(data, size, timestamp);
        if (read >= 0 && addr != nullptr) {
            *addr = GetRemoteAddress();
        }
        return read;
    }

    int Listen(int) override {
        SetError(EOPNOTSUPP);
        return -1;
    }

    rtc::Socket *Accept(rtc::SocketAddress *) override {
        SetError(EOPNOTSUPP);
        return nullptr;
    }

    int Close() override {
        _alive->store(false);
        closeHandle(0, false);
        if (_reader.joinable() && _reader.get_id() != std::this_thread::get_id()) {
            _reader.join();
        }
        return 0;
    }

    int GetError() const override {
        return _error.load();
    }

    void SetError(int error) override {
        _error.store(error);
    }

    rtc::Socket::ConnState GetState() const override {
        return static_cast<rtc::Socket::ConnState>(_state.load());
    }

    int GetOption(rtc::Socket::Option, int *value) override {
        if (value != nullptr) {
            *value = 0;
        }
        return 0;
    }

    int SetOption(rtc::Socket::Option, int) override {
        return 0;
    }

private:
    void readLoop() {
        std::vector<uint8_t> buffer(4096);
        while (_handle.load() != 0) {
            int read = TunnelBridge::Shared().tcpRead(_handle.load(), buffer.data(), static_cast<int>(buffer.size()));
            if (read <= 0) {
                break;
            }
            {
                std::lock_guard<std::mutex> lock(_mutex);
                _input.insert(_input.end(), buffer.begin(), buffer.begin() + read);
            }
            postRead();
        }
        closeHandle(0, true);
    }

    void closeHandle(int error, bool notify) {
        int64_t handle = _handle.exchange(0);
        _state.store(rtc::Socket::CS_CLOSED);
        if (error != 0) {
            _error.store(error);
        }
        if (handle != 0 && TunnelBridge::Shared().load()) {
            TunnelBridge::Shared().tcpClose(handle);
        }
        if (notify && !_closeNotified.exchange(true)) {
            postClose(error);
        }
    }

    void postConnectAndWritable() {
        auto alive = _alive;
        rtc::Thread *thread = _thread;
        thread->PostTask([alive, this] {
            if (!alive->load()) {
                return;
            }
            SignalConnectEvent(this);
            SignalWriteEvent(this);
        });
    }

    void postWritable() {
        auto alive = _alive;
        rtc::Thread *thread = _thread;
        thread->PostTask([alive, this] {
            if (!alive->load()) {
                return;
            }
            SignalWriteEvent(this);
        });
    }

    void postRead() {
        auto alive = _alive;
        rtc::Thread *thread = _thread;
        thread->PostTask([alive, this] {
            if (!alive->load()) {
                return;
            }
            SignalReadEvent(this);
        });
    }

    void postClose(int error) {
        auto alive = _alive;
        rtc::Thread *thread = _thread;
        thread->PostTask([alive, this, error] {
            if (!alive->load()) {
                return;
            }
            SignalCloseEvent(this, error);
        });
    }

    rtc::Thread *_thread = nullptr;
    std::shared_ptr<std::atomic<bool>> _alive;
    std::atomic<int64_t> _handle{0};
    std::atomic<int> _state{rtc::Socket::CS_CLOSED};
    std::atomic<int> _error{0};
    std::atomic<bool> _closeNotified{false};
    mutable std::mutex _mutex;
    rtc::SocketAddress _localAddress;
    rtc::SocketAddress _remoteAddress;
    std::thread _reader;
    std::vector<uint8_t> _input;
};

class TunnelTcpPacketSocket final : public TunnelAsyncPacketSocket {
public:
    TunnelTcpPacketSocket(rtc::Thread *thread, int64_t handle, rtc::SocketAddress localAddress, rtc::SocketAddress remoteAddress, TunnelTcpFraming framing) :
    TunnelAsyncPacketSocket(thread),
    _handle(handle),
    _framing(framing) {
        _localAddress = localAddress;
        _remoteAddress = remoteAddress;
        _reader = std::thread([this] {
            readLoop();
        });
        postConnected();
    }

    ~TunnelTcpPacketSocket() override {
        Close();
    }

    int Send(const void *data, size_t size, const rtc::PacketOptions &options) override {
        int64_t handle = _handle.load();
        if (handle == 0 || !TunnelBridge::Shared().load()) {
            SetError(ENETDOWN);
            return -1;
        }
        std::vector<uint8_t> frame;
        if (_framing == TunnelTcpFraming::StunTurn) {
            frame.assign(static_cast<const uint8_t *>(data), static_cast<const uint8_t *>(data) + size);
            size_t padding = 0;
            size_t expected = ExpectedStunTcpPacketLength(frame.data(), frame.size(), &padding);
            if (expected == 0 || expected != frame.size()) {
                SetError(EMSGSIZE);
                return -1;
            }
            frame.insert(frame.end(), padding, 0);
        } else if (_framing == TunnelTcpFraming::RawReflector) {
            if (size > kMaxPacketSize) {
                SetError(EMSGSIZE);
                return -1;
            }
            frame.reserve((_rawPrologueSent ? 0 : sizeof(uint32_t)) + sizeof(uint32_t) + size);
            if (!_rawPrologueSent) {
                AppendLE32(&frame, kRawTcpPrologue);
                _rawPrologueSent = true;
            }
            AppendLE32(&frame, static_cast<uint32_t>(size));
            frame.insert(frame.end(), static_cast<const uint8_t *>(data), static_cast<const uint8_t *>(data) + size);
        } else {
            if (size > UINT16_MAX) {
                SetError(EMSGSIZE);
                return -1;
            }
            frame.resize(kTcpPacketLengthSize + size);
            frame[0] = static_cast<uint8_t>((size >> 8) & 0xff);
            frame[1] = static_cast<uint8_t>(size & 0xff);
            memcpy(frame.data() + kTcpPacketLengthSize, data, size);
        }
        int written = TunnelBridge::Shared().tcpWrite(handle, frame.data(), static_cast<int>(frame.size()));
        if (written != static_cast<int>(frame.size())) {
            SetError(EIO);
            return -1;
        }
        signalSent(size, options, false);
        return static_cast<int>(size);
    }

    int SendTo(const void *data, size_t size, const rtc::SocketAddress &address, const rtc::PacketOptions &options) override {
        if (address == _remoteAddress) {
            return Send(data, size, options);
        }
        SetError(ENOTCONN);
        return -1;
    }

    int Close() override {
        int64_t handle = _handle.exchange(0);
        if (handle != 0 && TunnelBridge::Shared().load()) {
            _alive->store(false);
            TunnelBridge::Shared().tcpClose(handle);
        }
        if (_reader.joinable() && _reader.get_id() != std::this_thread::get_id()) {
            _reader.join();
        }
        return 0;
    }

    State GetState() const override {
        return _handle.load() == 0 ? STATE_CLOSED : STATE_CONNECTED;
    }

private:
    void readLoop() {
        std::vector<uint8_t> readBuffer(4096);
        while (_handle.load() != 0) {
            int read = TunnelBridge::Shared().tcpRead(_handle.load(), readBuffer.data(), static_cast<int>(readBuffer.size()));
            if (read <= 0) {
                break;
            }
            _input.insert(_input.end(), readBuffer.begin(), readBuffer.begin() + read);
            processInput();
        }
        postClosed(0);
    }

    void processInput() {
        size_t processed = 0;
        while (true) {
            if (_framing == TunnelTcpFraming::StunTurn) {
                if (_input.size() - processed < 4) {
                    break;
                }
                size_t padding = 0;
                size_t packetLength = ExpectedStunTcpPacketLength(_input.data() + processed, _input.size() - processed, &padding);
                size_t actualLength = packetLength + padding;
                if (packetLength == 0 || _input.size() - processed < actualLength) {
                    break;
                }
                std::vector<uint8_t> packet(_input.begin() + processed, _input.begin() + processed + packetLength);
                postPacket(std::move(packet), _remoteAddress);
                processed += actualLength;
            } else if (_framing == TunnelTcpFraming::RawReflector) {
                if (_input.size() - processed < sizeof(uint32_t)) {
                    break;
                }
                uint32_t packetLength = rtc::GetLE32(_input.data() + processed);
                if (packetLength > kMaxPacketSize) {
                    SetError(EMSGSIZE);
                    Close();
                    break;
                }
                if (_input.size() - processed < sizeof(uint32_t) + packetLength) {
                    break;
                }
                std::vector<uint8_t> packet(_input.begin() + processed + sizeof(uint32_t), _input.begin() + processed + sizeof(uint32_t) + packetLength);
                postPacket(std::move(packet), _remoteAddress);
                processed += sizeof(uint32_t) + packetLength;
            } else {
                if (_input.size() - processed < kTcpPacketLengthSize) {
                    break;
                }
                uint16_t packetLength = rtc::GetBE16(_input.data() + processed);
                if (_input.size() - processed < kTcpPacketLengthSize + packetLength) {
                    break;
                }
                std::vector<uint8_t> packet(_input.begin() + processed + kTcpPacketLengthSize, _input.begin() + processed + kTcpPacketLengthSize + packetLength);
                postPacket(std::move(packet), _remoteAddress);
                processed += kTcpPacketLengthSize + packetLength;
            }
        }
        if (processed > 0) {
            _input.erase(_input.begin(), _input.begin() + processed);
        }
    }

    std::atomic<int64_t> _handle;
    TunnelTcpFraming _framing = TunnelTcpFraming::Length16;
    bool _rawPrologueSent = false;
    std::thread _reader;
    std::vector<uint8_t> _input;
};

class TunnelDnsResult final : public webrtc::AsyncDnsResolverResult {
public:
    bool GetResolvedAddress(int family, rtc::SocketAddress *addr) const override {
        if (_error != 0 || _addresses.empty() || addr == nullptr) {
            return false;
        }
        *addr = _addr;
        for (const auto &address : _addresses) {
            if (family == AF_UNSPEC || address.family() == family) {
                addr->SetResolvedIP(address);
                return true;
            }
        }
        return false;
    }

    int GetError() const override {
        return _error;
    }

    void setAddress(const rtc::SocketAddress &addr) {
        _addr = addr;
    }

    void setResult(std::vector<rtc::IPAddress> addresses, int error) {
        _addresses = std::move(addresses);
        _error = error;
    }

private:
    rtc::SocketAddress _addr;
    std::vector<rtc::IPAddress> _addresses;
    int _error = 0;
};

class TunnelAsyncDnsResolver final : public webrtc::AsyncDnsResolverInterface {
public:
    explicit TunnelAsyncDnsResolver(rtc::Thread *thread) :
    _thread(thread),
    _alive(std::make_shared<std::atomic<bool>>(true)) {
    }

    ~TunnelAsyncDnsResolver() override {
        _alive->store(false);
    }

    void Start(const rtc::SocketAddress &addr, absl::AnyInvocable<void()> callback) override {
        Start(addr, addr.family(), std::move(callback));
    }

    void Start(const rtc::SocketAddress &addr, int family, absl::AnyInvocable<void()> callback) override {
        _result.setAddress(addr);
        const std::string host = SocketHost(addr);
        auto alive = _alive;
        rtc::Thread *thread = _thread;
        std::thread([alive, thread, this, host, family, callback = std::move(callback)]() mutable {
            int error = 0;
            std::vector<rtc::IPAddress> addresses = LookupTunnelIPs(host, family, &error);
            thread->PostTask([alive, this, addresses = std::move(addresses), error, callback = std::move(callback)]() mutable {
                if (!alive->load()) {
                    return;
                }
                _result.setResult(std::move(addresses), error);
                callback();
            });
        }).detach();
    }

    const webrtc::AsyncDnsResolverResult &result() const override {
        return _result;
    }

private:
    rtc::Thread *_thread = nullptr;
    std::shared_ptr<std::atomic<bool>> _alive;
    TunnelDnsResult _result;
};

class TunnelAsyncDnsResolverFactory final : public webrtc::AsyncDnsResolverFactoryInterface {
public:
    explicit TunnelAsyncDnsResolverFactory(rtc::Thread *thread) :
    _thread(thread) {
    }

    std::unique_ptr<webrtc::AsyncDnsResolverInterface> CreateAndResolve(
        const rtc::SocketAddress &addr,
        absl::AnyInvocable<void()> callback) override {
        auto resolver = Create();
        resolver->Start(addr, std::move(callback));
        return resolver;
    }

    std::unique_ptr<webrtc::AsyncDnsResolverInterface> CreateAndResolve(
        const rtc::SocketAddress &addr,
        int family,
        absl::AnyInvocable<void()> callback) override {
        auto resolver = Create();
        resolver->Start(addr, family, std::move(callback));
        return resolver;
    }

    std::unique_ptr<webrtc::AsyncDnsResolverInterface> Create() override {
        return std::make_unique<TunnelAsyncDnsResolver>(_thread);
    }

private:
    rtc::Thread *_thread = nullptr;
};

class TunnelPacketSocketFactory final : public rtc::PacketSocketFactory {
public:
    explicit TunnelPacketSocketFactory(rtc::Thread *thread) :
    _thread(thread) {
    }

    rtc::AsyncPacketSocket *CreateUdpSocket(const rtc::SocketAddress &address, uint16_t, uint16_t) override {
        if (!TunnelBridge::Shared().running()) {
            RTC_LOG(LS_ERROR) << "Tunnel UDP socket requested while tunnel is not running";
            return nullptr;
        }
        const std::string host = SocketHost(address);
        int64_t handle = TunnelBridge::Shared().udpOpen(host.c_str(), address.port());
        if (handle <= 0) {
            RTC_LOG(LS_ERROR) << "Tunnel UDP open failed for " << address.ToSensitiveString();
            return nullptr;
        }
        char localHost[128] = {0};
        int localPort = 0;
        if (TunnelBridge::Shared().udpLocalAddress(handle, localHost, sizeof(localHost), &localPort) < 0) {
            TunnelBridge::Shared().udpClose(handle);
            RTC_LOG(LS_ERROR) << "Tunnel UDP local address lookup failed";
            return nullptr;
        }
        RTC_LOG(LS_INFO) << "Tunnel UDP socket opened for " << address.ToSensitiveString();
        return new TunnelUdpPacketSocket(_thread, handle, SocketAddressFromHostPort(localHost, localPort));
    }

    rtc::AsyncListenSocket *CreateServerTcpSocket(const rtc::SocketAddress &, uint16_t, uint16_t, int) override {
        return nullptr;
    }

    rtc::AsyncPacketSocket *CreateClientTcpSocket(const rtc::SocketAddress &localAddress, const rtc::SocketAddress &remoteAddress, const rtc::ProxyInfo &, const std::string &, const rtc::PacketSocketTcpOptions &tcpOptions) override {
        if (!TunnelBridge::Shared().running()) {
            RTC_LOG(LS_ERROR) << "Tunnel TCP socket requested while tunnel is not running";
            return nullptr;
        }
        const int tlsOptions = tcpOptions.opts & (rtc::PacketSocketFactory::OPT_TLS | rtc::PacketSocketFactory::OPT_TLS_FAKE | rtc::PacketSocketFactory::OPT_TLS_INSECURE);
        RTC_DCHECK((tlsOptions & (tlsOptions - 1)) == 0);
        const bool rawReflector = (tcpOptions.opts & kTunnelTcpRawReflectorOption) != 0;
        if (!rawReflector) {
            return CreateWebRtcTcpSocket(localAddress, remoteAddress, tcpOptions, tlsOptions);
        }
        if (tlsOptions != 0) {
            RTC_LOG(LS_ERROR) << "Tunnel raw reflector TCP does not support TLS wrapping; failing closed";
            return nullptr;
        }
        const std::string host = SocketHost(remoteAddress);
        int64_t handle = TunnelBridge::Shared().tcpConnect(host.c_str(), remoteAddress.port());
        if (handle <= 0) {
            RTC_LOG(LS_ERROR) << "Tunnel TCP connect failed for " << remoteAddress.ToSensitiveString();
            return nullptr;
        }
        TunnelTcpFraming framing = TunnelTcpFraming::RawReflector;
        RTC_LOG(LS_INFO) << "Tunnel TCP socket opened for " << remoteAddress.ToSensitiveString() << " framing=" << static_cast<int>(framing);
        return new TunnelTcpPacketSocket(_thread, handle, localAddress, remoteAddress, framing);
    }

    std::unique_ptr<webrtc::AsyncDnsResolverInterface> CreateAsyncDnsResolver() override {
        return std::make_unique<TunnelAsyncDnsResolver>(_thread);
    }

private:
    rtc::AsyncPacketSocket *CreateWebRtcTcpSocket(const rtc::SocketAddress &localAddress, const rtc::SocketAddress &remoteAddress, const rtc::PacketSocketTcpOptions &tcpOptions, int tlsOptions) {
        rtc::Socket *socket = new TunnelTcpAsyncSocket(_thread);
        if (socket->Bind(localAddress) < 0) {
            RTC_LOG(LS_ERROR) << "Tunnel TCP bind failed with error " << socket->GetError();
            delete socket;
            return nullptr;
        }
        if (socket->SetOption(rtc::Socket::OPT_NODELAY, 1) != 0) {
            RTC_LOG(LS_WARNING) << "Tunnel TCP_NODELAY option ignored with error " << socket->GetError();
        }
        if ((tlsOptions & rtc::PacketSocketFactory::OPT_TLS) != 0 || (tlsOptions & rtc::PacketSocketFactory::OPT_TLS_INSECURE) != 0) {
            rtc::SSLAdapter *sslAdapter = rtc::SSLAdapter::Create(socket);
            if (sslAdapter == nullptr) {
                RTC_LOG(LS_ERROR) << "Tunnel TCP SSLAdapter creation failed";
                delete socket;
                return nullptr;
            }
            if ((tlsOptions & rtc::PacketSocketFactory::OPT_TLS_INSECURE) != 0) {
                sslAdapter->SetIgnoreBadCert(true);
            }
            sslAdapter->SetAlpnProtocols(tcpOptions.tls_alpn_protocols);
            sslAdapter->SetEllipticCurves(tcpOptions.tls_elliptic_curves);
            sslAdapter->SetCertVerifier(tcpOptions.tls_cert_verifier);
            socket = sslAdapter;
            if (sslAdapter->StartSSL(remoteAddress.hostname().c_str()) != 0) {
                RTC_LOG(LS_ERROR) << "Tunnel TCP StartSSL failed for " << remoteAddress.ToSensitiveString();
                delete sslAdapter;
                return nullptr;
            }
        } else if ((tlsOptions & rtc::PacketSocketFactory::OPT_TLS_FAKE) != 0) {
            socket = new rtc::AsyncSSLSocket(socket);
        }
        if (socket->Connect(remoteAddress) < 0) {
            RTC_LOG(LS_ERROR) << "Tunnel TCP connect failed for " << remoteAddress.ToSensitiveString() << " error=" << socket->GetError();
            delete socket;
            return nullptr;
        }
        if ((tcpOptions.opts & rtc::PacketSocketFactory::OPT_STUN) != 0) {
            RTC_LOG(LS_INFO) << "Tunnel TCP STUN/TURN socket opened for " << remoteAddress.ToSensitiveString() << " tls=" << tlsOptions;
            return new cricket::AsyncStunTCPSocket(socket);
        }
        RTC_LOG(LS_INFO) << "Tunnel TCP packet socket opened for " << remoteAddress.ToSensitiveString() << " tls=" << tlsOptions;
        return new rtc::AsyncTCPSocket(socket);
    }

    rtc::Thread *_thread = nullptr;
};

class TunnelNetworkManager final : public rtc::NetworkManager {
public:
    TunnelNetworkManager() {
        reloadNetworks();
    }

    void StartUpdating() override {
        reloadNetworks();
        SignalNetworksChanged();
    }

    void StopUpdating() override {
    }

    std::vector<const rtc::Network *> GetNetworks() const override {
        std::vector<const rtc::Network *> result;
        for (const auto &network : _networks) {
            result.push_back(network.get());
        }
        return result;
    }

    EnumerationPermission enumeration_permission() const override {
        return ENUMERATION_ALLOWED;
    }

    std::vector<const rtc::Network *> GetAnyAddressNetworks() override {
        return GetNetworks();
    }

    bool GetDefaultLocalAddress(int family, rtc::IPAddress *ipaddr) const override {
        for (const auto &network : _networks) {
            rtc::IPAddress ip = network->GetBestIP();
            if (ip.family() == family) {
                *ipaddr = ip;
                return true;
            }
        }
        return false;
    }

    webrtc::MdnsResponderInterface *GetMdnsResponder() const override {
        return nullptr;
    }

private:
    void reloadNetworks() {
        _networks.clear();
        if (!TunnelBridge::Shared().load()) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            char host[128] = {0};
            int family = TunnelBridge::Shared().getLocalAddress(i, host, sizeof(host));
            if (family == 0) {
                break;
            }
            if (family < 0) {
                continue;
            }
            rtc::IPAddress ip;
            if (!ParseIPAddress(host, &ip)) {
                continue;
            }
            auto network = std::make_unique<rtc::Network>(
                "telegram-tunnel" + std::to_string(i),
                "Telegram tunnel",
                ip,
                family == 4 ? 32 : 128,
                rtc::ADAPTER_TYPE_VPN);
            network->AddIP(ip);
            network->set_default_local_address_provider(this);
            network->set_mdns_responder_provider(this);
            _networks.push_back(std::move(network));
        }
    }

    std::vector<std::unique_ptr<rtc::Network>> _networks;
};

} // namespace

bool IsTunnelProxy(const Proxy *proxy) {
    return proxy != nullptr && proxy->protocol == Proxy::Protocol::Tunnel;
}

bool IsTunnelProxy(const absl::optional<Proxy> &proxy) {
    return proxy.has_value() && proxy->protocol == Proxy::Protocol::Tunnel;
}

std::unique_ptr<rtc::PacketSocketFactory> CreateTunnelPacketSocketFactory(rtc::Thread *thread) {
    return std::make_unique<TunnelPacketSocketFactory>(thread);
}

std::unique_ptr<rtc::NetworkManager> CreateTunnelNetworkManager() {
    return std::make_unique<TunnelNetworkManager>();
}

std::unique_ptr<webrtc::AsyncDnsResolverFactoryInterface> CreateTunnelAsyncDnsResolverFactory(rtc::Thread *thread) {
    return std::make_unique<TunnelAsyncDnsResolverFactory>(thread);
}

} // namespace tgcalls
