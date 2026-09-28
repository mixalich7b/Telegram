/* SOCKS5 CONNECT handshake shared by WebRTC TCP and raw reflector sockets. */
#ifndef RTC_BASE_SOCKS5_HANDSHAKE_H_
#define RTC_BASE_SOCKS5_HANDSHAKE_H_

#include <algorithm>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace rtc {

// No I/O: the adapter retains incomplete responses and drains pending bytes
// across short writes. An address contains ATYP followed by the RFC 1928 bytes.
class Socks5Handshake {
 public:
  Socks5Handshake(std::vector<uint8_t> address, uint16_t port,
                  std::string user, std::string password)
      : address_(std::move(address)), port_(port), user_(std::move(user)),
        password_(std::move(password)) {
    const bool valid_address =
        (address_.size() == 5 && address_[0] == 1) ||
        (address_.size() == 17 && address_[0] == 4) ||
        (address_.size() >= 3 && address_[0] == 3 &&
         address_[1] != 0 && address_.size() == size_t(address_[1]) + 2);
    if (!valid_address || !port_ || user_.size() > 255 || password_.size() > 255) {
      Fail();
    } else {
      pending_ = user_.empty() ? std::vector<uint8_t>{5, 1, 0}
                              : std::vector<uint8_t>{5, 2, 0, 2};
    }
  }

  bool failed() const { return state_ == State::Error; }
  bool connected() const { return state_ == State::Connected; }
  const uint8_t* pending_data() const { return pending_.empty() ? nullptr : pending_.data() + sent_; }
  size_t pending_size() const { return pending_.size() - sent_; }
  void Sent(size_t count) {
    if (count > pending_size()) { Fail(); return; }
    sent_ += count;
    if (sent_ == pending_.size()) {
      std::fill(pending_.begin(), pending_.end(), 0);
      pending_.clear();
      sent_ = 0;
    }
  }

  // Returns only complete response bytes consumed; never consumes app payload.
  size_t Receive(const uint8_t* data, size_t size) {
    if (failed() || connected() || size == 0) return 0;
    if (pending_size()) { Fail(); return 0; }
    if (state_ == State::Greeting || state_ == State::Auth) {
      if (size < 2) return 0;
      if (state_ == State::Auth) {
        if (data[0] != 1 || data[1] != 0) { Fail(); return 0; }
        ConnectRequest();
      } else if (data[0] != 5) {
        Fail(); return 0;
      } else if (data[1] == 0) {
        ConnectRequest();
      } else if (data[1] == 2 && !user_.empty()) {
        pending_ = {1, static_cast<uint8_t>(user_.size())};
        pending_.insert(pending_.end(), user_.begin(), user_.end());
        pending_.push_back(static_cast<uint8_t>(password_.size()));
        pending_.insert(pending_.end(), password_.begin(), password_.end());
        state_ = State::Auth;
      } else {
        Fail(); return 0;
      }
      return 2;
    }
    if (size < 4) return 0;
    if (data[0] != 5 || data[1] != 0 || data[2] != 0) { Fail(); return 0; }
    size_t length = 0;
    if (data[3] == 1) length = 10;
    else if (data[3] == 4) length = 22;
    else if (data[3] == 3) {
      if (size < 5) return 0;
      if (data[4] == 0) { Fail(); return 0; }
      length = 7 + data[4];
    } else { Fail(); return 0; }
    if (size < length) return 0;
    state_ = State::Connected;
    return length;
  }

 private:
  enum class State { Greeting, Auth, Connect, Connected, Error };
  void Fail() {
    state_ = State::Error;
    std::fill(pending_.begin(), pending_.end(), 0);
    pending_.clear();
    sent_ = 0;
  }
  void ConnectRequest() {
    pending_ = {5, 1, 0};
    pending_.insert(pending_.end(), address_.begin(), address_.end());
    pending_.push_back(static_cast<uint8_t>(port_ >> 8));
    pending_.push_back(static_cast<uint8_t>(port_));
    state_ = State::Connect;
  }
  State state_ = State::Greeting;
  std::vector<uint8_t> address_, pending_;
  uint16_t port_;
  std::string user_, password_;
  size_t sent_ = 0;
};

}  // namespace rtc
#endif
