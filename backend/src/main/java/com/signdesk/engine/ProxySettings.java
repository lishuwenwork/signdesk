package com.signdesk.engine;

import com.signdesk.common.ApiException;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Set;

/** Per-request routing; never changes the JVM's global proxy or authenticator. */
public record ProxySettings(String mode, String host, int port) {
    public ProxySettings {
        mode = mode == null ? "system" : mode;
        host = host == null ? "" : host.trim();
    }

    public static ProxySettings system() {
        return new ProxySettings("system", "", 0);
    }

    public ProxySettings validated() {
        if (!Set.of("system", "direct", "http", "socks").contains(mode))
            throw new ApiException("请选择有效的代理模式");
        if (mode.equals("http") || mode.equals("socks")) {
            if (port < 1 || port > 65535)
                throw new ApiException("代理端口需在 1～65535 之间");
            try {
                if (host.isBlank() || host.length() > 253
                        || !host.chars().allMatch(c -> c > 32 && c < 127)
                        || new URI("http", null, host, port, null, null, null).getHost() == null)
                    throw new IllegalArgumentException();
            } catch (Exception e) {
                throw new ApiException("代理地址仅填写主机名或 IP，不包含协议、端口、路径或账号密码");
            }
        }
        return this;
    }

    public Proxy toProxy() {
        validated();
        return switch (mode) {
            case "system" -> null;
            case "direct" -> Proxy.NO_PROXY;
            default -> new Proxy(
                    mode.equals("http") ? Proxy.Type.HTTP : Proxy.Type.SOCKS,
                    new InetSocketAddress(host, port));
        };
    }
}
