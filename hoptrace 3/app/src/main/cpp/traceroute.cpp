#include <jni.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/errqueue.h>
#include <netdb.h>
#include <netinet/icmp6.h>
#include <netinet/ip.h>
#include <netinet/ip_icmp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct HopResult {
    int ttl = 0;
    std::string ip;
    double rtt = -1.0;
    std::string type = "TIMEOUT";
};

std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    const char* hex = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0xf] << hex[c & 0xf];
                } else {
                    out << static_cast<char>(c);
                }
        }
    }
    return out.str();
}

std::string errorJson(const std::string& message) {
    return std::string("{\"error\":\"") + jsonEscape(message) + "\"}";
}

std::string addressToString(const sockaddr* address) {
    if (!address) return "";
    char host[NI_MAXHOST] = {0};
    socklen_t length = address->sa_family == AF_INET
            ? sizeof(sockaddr_in) : sizeof(sockaddr_in6);
    int status = getnameinfo(address, length, host, sizeof(host), nullptr, 0, NI_NUMERICHOST);
    return status == 0 ? std::string(host) : std::string();
}

bool sameAddress(const sockaddr_storage& a, const sockaddr_storage& b) {
    if (a.ss_family != b.ss_family) return false;
    if (a.ss_family == AF_INET) {
        const auto* x = reinterpret_cast<const sockaddr_in*>(&a);
        const auto* y = reinterpret_cast<const sockaddr_in*>(&b);
        return x->sin_addr.s_addr == y->sin_addr.s_addr;
    }
    if (a.ss_family == AF_INET6) {
        const auto* x = reinterpret_cast<const sockaddr_in6*>(&a);
        const auto* y = reinterpret_cast<const sockaddr_in6*>(&b);
        return std::memcmp(&x->sin6_addr, &y->sin6_addr, sizeof(in6_addr)) == 0;
    }
    return false;
}

struct ProbeReply {
    bool received = false;
    bool destination = false;
    bool unreachable = false;
    std::string address;
    double rttMs = -1.0;
};

ProbeReply receiveIpv4Error(int fd, int timeoutMs, const sockaddr_storage& target,
                            std::chrono::steady_clock::time_point sentAt) {
    ProbeReply result;
    pollfd descriptor{};
    descriptor.fd = fd;
    descriptor.events = POLLIN | POLLERR;
    int ready;
    do {
        ready = poll(&descriptor, 1, timeoutMs);
    } while (ready < 0 && errno == EINTR);
    if (ready <= 0) return result;

    if (descriptor.revents & POLLERR) {
        char payload[512]{};
        char control[512]{};
        sockaddr_storage original{};
        iovec iov{};
        iov.iov_base = payload;
        iov.iov_len = sizeof(payload);
        msghdr message{};
        message.msg_name = &original;
        message.msg_namelen = sizeof(original);
        message.msg_iov = &iov;
        message.msg_iovlen = 1;
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        ssize_t count = recvmsg(fd, &message, MSG_ERRQUEUE | MSG_DONTWAIT);
        if (count >= 0) {
            for (cmsghdr* cmsg = CMSG_FIRSTHDR(&message); cmsg; cmsg = CMSG_NXTHDR(&message, cmsg)) {
                if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_RECVERR &&
                    cmsg->cmsg_len >= CMSG_LEN(sizeof(sock_extended_err))) {
                    auto* extended = reinterpret_cast<sock_extended_err*>(CMSG_DATA(cmsg));
                    sockaddr* offender = SO_EE_OFFENDER(extended);
                    result.address = addressToString(offender);
                    result.received = !result.address.empty();
                    result.destination = extended->ee_type == ICMP_DEST_UNREACH &&
                                         result.address == addressToString(reinterpret_cast<const sockaddr*>(&target));
                    result.unreachable = extended->ee_type == ICMP_DEST_UNREACH;
                    if (extended->ee_type == ICMP_TIME_EXCEEDED) result.unreachable = false;
                    result.rttMs = std::chrono::duration<double, std::milli>(
                            std::chrono::steady_clock::now() - sentAt).count();
                    return result;
                }
            }
        }
    }

    if (descriptor.revents & POLLIN) {
        unsigned char packet[512]{};
        sockaddr_storage peer{};
        socklen_t peerLength = sizeof(peer);
        ssize_t count = recvfrom(fd, packet, sizeof(packet), MSG_DONTWAIT,
                                 reinterpret_cast<sockaddr*>(&peer), &peerLength);
        if (count >= static_cast<ssize_t>(sizeof(icmphdr))) {
            auto* header = reinterpret_cast<icmphdr*>(packet);
            if (header->type == ICMP_ECHOREPLY) {
                result.received = true;
                result.destination = true;
                result.address = addressToString(reinterpret_cast<sockaddr*>(&peer));
                result.rttMs = std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - sentAt).count();
            }
        }
    }
    return result;
}

ProbeReply receiveIpv6Error(int fd, int timeoutMs, const sockaddr_storage& target,
                            std::chrono::steady_clock::time_point sentAt) {
    ProbeReply result;
    pollfd descriptor{};
    descriptor.fd = fd;
    descriptor.events = POLLIN | POLLERR;
    int ready;
    do {
        ready = poll(&descriptor, 1, timeoutMs);
    } while (ready < 0 && errno == EINTR);
    if (ready <= 0) return result;

    if (descriptor.revents & POLLERR) {
        char payload[512]{};
        char control[512]{};
        sockaddr_storage original{};
        iovec iov{};
        iov.iov_base = payload;
        iov.iov_len = sizeof(payload);
        msghdr message{};
        message.msg_name = &original;
        message.msg_namelen = sizeof(original);
        message.msg_iov = &iov;
        message.msg_iovlen = 1;
        message.msg_control = control;
        message.msg_controllen = sizeof(control);
        ssize_t count = recvmsg(fd, &message, MSG_ERRQUEUE | MSG_DONTWAIT);
        if (count >= 0) {
            for (cmsghdr* cmsg = CMSG_FIRSTHDR(&message); cmsg; cmsg = CMSG_NXTHDR(&message, cmsg)) {
                if (cmsg->cmsg_level == IPPROTO_IPV6 && cmsg->cmsg_type == IPV6_RECVERR &&
                    cmsg->cmsg_len >= CMSG_LEN(sizeof(sock_extended_err))) {
                    auto* extended = reinterpret_cast<sock_extended_err*>(CMSG_DATA(cmsg));
                    sockaddr* offender = SO_EE_OFFENDER(extended);
                    result.address = addressToString(offender);
                    result.received = !result.address.empty();
                    result.destination = extended->ee_type == ICMP6_DST_UNREACH &&
                                         result.address == addressToString(reinterpret_cast<const sockaddr*>(&target));
                    result.unreachable = extended->ee_type == ICMP6_DST_UNREACH;
                    if (extended->ee_type == ICMP6_TIME_EXCEEDED) result.unreachable = false;
                    result.rttMs = std::chrono::duration<double, std::milli>(
                            std::chrono::steady_clock::now() - sentAt).count();
                    return result;
                }
            }
        }
    }

    if (descriptor.revents & POLLIN) {
        unsigned char packet[512]{};
        sockaddr_storage peer{};
        socklen_t peerLength = sizeof(peer);
        ssize_t count = recvfrom(fd, packet, sizeof(packet), MSG_DONTWAIT,
                                 reinterpret_cast<sockaddr*>(&peer), &peerLength);
        if (count >= static_cast<ssize_t>(sizeof(icmp6_hdr))) {
            auto* header = reinterpret_cast<icmp6_hdr*>(packet);
            if (header->icmp6_type == ICMP6_ECHO_REPLY) {
                result.received = true;
                result.destination = true;
                result.address = addressToString(reinterpret_cast<sockaddr*>(&peer));
                result.rttMs = std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - sentAt).count();
            }
        }
    }
    return result;
}

std::string runTrace(const std::string& host, bool ipv6, int maxHops, int timeoutMs, int probes) {
    addrinfo hints{};
    hints.ai_family = ipv6 ? AF_INET6 : AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    hints.ai_protocol = 0;
    addrinfo* resolved = nullptr;
    int gai = getaddrinfo(host.c_str(), nullptr, &hints, &resolved);
    if (gai != 0 || !resolved) {
        return errorJson(std::string("DNS resolution failed: ") + gai_strerror(gai));
    }

    sockaddr_storage target{};
    std::memcpy(&target, resolved->ai_addr, resolved->ai_addrlen);
    int family = resolved->ai_family;
    freeaddrinfo(resolved);

    int protocol = ipv6 ? static_cast<int>(IPPROTO_ICMPV6) : static_cast<int>(IPPROTO_ICMP);
    int fd = socket(family, SOCK_DGRAM | SOCK_CLOEXEC, protocol);
    if (fd < 0) {
        return errorJson(std::string("Cannot create ICMP ping socket: ") + std::strerror(errno) +
                         ". This device or network may block unprivileged ICMP sockets.");
    }

    int enabled = 1;
    int optionResult = ipv6
            ? setsockopt(fd, IPPROTO_IPV6, IPV6_RECVERR, &enabled, sizeof(enabled))
            : setsockopt(fd, IPPROTO_IP, IP_RECVERR, &enabled, sizeof(enabled));
    if (optionResult < 0) {
        std::string error = std::string("ICMP error queue is unavailable: ") + std::strerror(errno);
        close(fd);
        return errorJson(error);
    }

    const int identifier = static_cast<int>(getpid() & 0xffff);
    std::vector<HopResult> hops;
    bool reached = false;
    bool hardFailure = false;

    for (int ttl = 1; ttl <= maxHops && !reached && !hardFailure; ++ttl) {
        HopResult hop;
        hop.ttl = ttl;
        double bestRtt = 1e12;

        int hopOption = ipv6
                ? setsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &ttl, sizeof(ttl))
                : setsockopt(fd, IPPROTO_IP, IP_TTL, &ttl, sizeof(ttl));
        if (hopOption < 0) {
            close(fd);
            return errorJson(std::string("Could not set hop limit: ") + std::strerror(errno));
        }

        for (int probe = 0; probe < probes; ++probe) {
            // Drain stale asynchronous errors before sending the next probe.
            for (;;) {
                char drainPayload[256]{};
                char drainControl[256]{};
                iovec drainIov{};
                drainIov.iov_base = drainPayload;
                drainIov.iov_len = sizeof(drainPayload);
                msghdr drainMessage{};
                drainMessage.msg_iov = &drainIov;
                drainMessage.msg_iovlen = 1;
                drainMessage.msg_control = drainControl;
                drainMessage.msg_controllen = sizeof(drainControl);
                if (recvmsg(fd, &drainMessage, MSG_ERRQUEUE | MSG_DONTWAIT) < 0) break;
            }

            unsigned char packet[64]{};
            if (ipv6) {
                auto* header = reinterpret_cast<icmp6_hdr*>(packet);
                header->icmp6_type = ICMP6_ECHO_REQUEST;
                header->icmp6_code = 0;
                header->icmp6_id = htons(static_cast<uint16_t>(identifier));
                header->icmp6_seq = htons(static_cast<uint16_t>((ttl - 1) * probes + probe + 1));
                for (size_t i = sizeof(icmp6_hdr); i < sizeof(packet); ++i) packet[i] = static_cast<unsigned char>(i ^ ttl ^ probe);
            } else {
                auto* header = reinterpret_cast<icmphdr*>(packet);
                header->type = ICMP_ECHO;
                header->code = 0;
                header->un.echo.id = htons(static_cast<uint16_t>(identifier));
                header->un.echo.sequence = htons(static_cast<uint16_t>((ttl - 1) * probes + probe + 1));
                for (size_t i = sizeof(icmphdr); i < sizeof(packet); ++i) packet[i] = static_cast<unsigned char>(i ^ ttl ^ probe);
            }

            auto sentAt = std::chrono::steady_clock::now();
            socklen_t targetLength = ipv6 ? sizeof(sockaddr_in6) : sizeof(sockaddr_in);
            ssize_t sent = sendto(fd, packet, sizeof(packet), 0,
                                  reinterpret_cast<sockaddr*>(&target), targetLength);
            if (sent < 0) {
                if (errno == EINTR) { --probe; continue; }
                std::string error = std::string("ICMP send failed: ") + std::strerror(errno);
                close(fd);
                return errorJson(error);
            }

            ProbeReply reply = ipv6
                    ? receiveIpv6Error(fd, timeoutMs, target, sentAt)
                    : receiveIpv4Error(fd, timeoutMs, target, sentAt);
            if (reply.received && reply.rttMs >= 0 && reply.rttMs < bestRtt) {
                bestRtt = reply.rttMs;
                hop.ip = reply.address;
                hop.rtt = reply.rttMs;
                hop.type = reply.destination ? "DESTINATION" :
                           (reply.unreachable ? "UNREACHABLE" : "TIME_EXCEEDED");
            }
            if (reply.destination) reached = true;
            if (reply.unreachable) hardFailure = true;
        }

        hops.push_back(hop);
    }
    close(fd);

    std::ostringstream output;
    output << "{\"target\":\"" << jsonEscape(host) << "\",\"family\":\""
           << (ipv6 ? "IPv6" : "IPv4") << "\",\"hops\":[";
    for (size_t i = 0; i < hops.size(); ++i) {
        if (i) output << ',';
        const auto& hop = hops[i];
        output << "{\"ttl\":" << hop.ttl
               << ",\"ip\":\"" << jsonEscape(hop.ip)
               << "\",\"rtt_ms\":";
        if (hop.rtt < 0) output << "null";
        else output << hop.rtt;
        output << ",\"type\":\"" << hop.type << "\"}";
    }
    output << "],\"reached\":" << (reached ? "true" : "false") << '}';
    return output.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_hoptrace_app_NativeTraceroute_run(JNIEnv* env, jclass,
                                           jstring hostString, jboolean ipv6,
                                           jint maxHops, jint timeoutMs, jint probes) {
    if (hostString == nullptr) return env->NewStringUTF("{\"error\":\"Target is empty\"}");
    const char* raw = env->GetStringUTFChars(hostString, nullptr);
    if (!raw) return env->NewStringUTF("{\"error\":\"Could not read target string\"}");
    std::string host(raw);
    env->ReleaseStringUTFChars(hostString, raw);

    maxHops = std::max(1, std::min(64, static_cast<int>(maxHops)));
    timeoutMs = std::max(250, std::min(5000, static_cast<int>(timeoutMs)));
    probes = std::max(1, std::min(3, static_cast<int>(probes)));
    return env->NewStringUTF(runTrace(host, ipv6 == JNI_TRUE, maxHops, timeoutMs, probes).c_str());
}
