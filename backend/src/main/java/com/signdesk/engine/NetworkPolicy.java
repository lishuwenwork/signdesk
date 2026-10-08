package com.signdesk.engine;

import com.signdesk.common.ApiException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class NetworkPolicy {
    private final Set<String> allowed;

    public NetworkPolicy(@Value("${signdesk.allowed-hosts:}") String hosts) {
        allowed =
                Arrays.stream(hosts.toLowerCase().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
    }

    public void validate(String raw) {
        URI uri = CurlParser.validateUrl(raw);
        String host = uri.getHost().toLowerCase();
        if (host.equals("169.254.169.254") || host.equals("metadata.google.internal"))
            throw new ApiException("禁止访问云元数据地址");
        if (allowed.contains(host)) return;
        try {
            for (InetAddress ip : InetAddress.getAllByName(host)) {
                byte[] b = ip.getAddress();
                boolean special =
                        ip.isAnyLocalAddress()
                                || ip.isLoopbackAddress()
                                || ip.isLinkLocalAddress()
                                || ip.isSiteLocalAddress()
                                || ip.isMulticastAddress();
                if (b.length == 16 && (b[0] & 0xfe) == 0xfc) special = true;
                if (b.length == 4
                        && ((b[0] & 255) == 0
                                || ((b[0] & 255) == 100
                                        && (b[1] & 255) >= 64
                                        && (b[1] & 255) <= 127))) special = true;
                if (special)
                    throw new ApiException("默认不访问本机或内网地址；内部平台请在 SIGNDESK_ALLOWED_HOSTS 中明确允许主机");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("目标主机无法解析");
        }
    }
}
