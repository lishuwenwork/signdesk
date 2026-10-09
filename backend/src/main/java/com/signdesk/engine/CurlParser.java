package com.signdesk.engine;

import com.signdesk.common.ApiException;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses data only. Never starts a shell, expands variables, or reads a file. */
@Component
public class CurlParser {
    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS");

    public record Preview(RequestSpec spec, List<String> warnings) {
        @Override public String toString() { return "Preview[redacted]"; }
    }

    public Preview parse(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 262144)
            throw new ApiException("cURL 不能为空，最多 256 KiB");
        List<String> tokens = tokenize(raw);
        if (tokens.isEmpty()
                || !(tokens.getFirst().equalsIgnoreCase("curl")
                        || tokens.getFirst().equalsIgnoreCase("curl.exe")))
            throw new ApiException("请粘贴以 curl 开头的完整命令");
        String url = null, method = null;
        List<RequestSpec.Header> headers = new ArrayList<>();
        List<String> data = new ArrayList<>(), warnings = new ArrayList<>();
        boolean get = false, head = false, redirect = false, hadBody = false;
        int timeout = 0;
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i), option = token, value = null;
            int equals = token.startsWith("--") ? token.indexOf('=') : -1;
            if (equals > 0) {
                option = token.substring(0, equals);
                value = token.substring(equals + 1);
            } else if (token.length() > 2
                    && !token.startsWith("--")
                    && "XHdbum".indexOf(token.charAt(1)) >= 0) {
                option = token.substring(0, 2);
                value = token.substring(2);
            }
            if (Set.of(
                            "-X",
                            "--request",
                            "-H",
                            "--header",
                            "-b",
                            "--cookie",
                            "-d",
                            "--data",
                            "--data-raw",
                            "--data-binary",
                            "--data-urlencode",
                            "--url",
                            "-u",
                            "--user",
                            "-m",
                            "--max-time",
                            "--connect-timeout",
                            "-A",
                            "--user-agent",
                            "-e",
                            "--referer")
                    .contains(option)) {
                if (value == null) {
                    if (++i >= tokens.size()) throw new ApiException("选项缺少值：" + option);
                    value = tokens.get(i);
                }
                switch (option) {
                    case "-X", "--request" -> method = value.toUpperCase(Locale.ROOT);
                    case "--url" -> {
                        if (url != null) throw new ApiException("一次只导入一个 URL");
                        url = value;
                    }
                    case "-H", "--header" -> {
                        int colon = value.indexOf(':');
                        if (colon <= 0) throw new ApiException("请求头需使用 Name: value 格式");
                        String name = value.substring(0, colon).trim(),
                                content = value.substring(colon + 1).trim();
                        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                                || content.contains("\n")
                                || content.contains("\r")) throw new ApiException("请求头名称或换行不合法");
                        if (name.equalsIgnoreCase("Content-Length"))
                            warnings.add("Content-Length 将按实际 Body 字节计算");
                        else if (Set.of(
                                        "transfer-encoding",
                                        "connection",
                                        "proxy-connection",
                                        "host")
                                .contains(name.toLowerCase(Locale.ROOT)))
                            throw new ApiException("当前 Hutool 适配不支持手工设置传输头：" + name);
                        else headers.add(new RequestSpec.Header(name, content));
                    }
                    case "-b", "--cookie" -> {
                        if (!value.contains("=") || value.startsWith("@"))
                            throw new ApiException("只支持内联 Cookie，不读取 Cookie 文件");
                        headers.add(new RequestSpec.Header("Cookie", value));
                    }
                    case "-u", "--user" ->
                            headers.add(
                                    new RequestSpec.Header(
                                            "Authorization",
                                            "Basic "
                                                    + Base64.getEncoder()
                                                            .encodeToString(
                                                                    value.getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8))));
                    case "-A", "--user-agent" ->
                            headers.add(new RequestSpec.Header("User-Agent", value));
                    case "-e", "--referer" -> headers.add(new RequestSpec.Header("Referer", value));
                    case "-m", "--max-time", "--connect-timeout" -> {
                        try {
                            double seconds = Double.parseDouble(value);
                            if (!Double.isFinite(seconds) || seconds < 1 || seconds > 120)
                                throw new NumberFormatException();
                            int ms = (int) (seconds * 1000);
                            timeout = timeout == 0 ? ms : Math.min(timeout, ms);
                        } catch (NumberFormatException e) {
                            throw new ApiException("超时需在 1～120 秒之间");
                        }
                    }
                    default -> {
                        if (value.startsWith("@") && !option.equals("--data-raw"))
                            throw new ApiException("不读取 @文件，请粘贴内联请求体");
                        hadBody = true;
                        if (option.equals("--data-urlencode")) {
                            int p = value.indexOf('=');
                            if (p < 0 && value.contains("@"))
                                throw new ApiException("不读取 --data-urlencode 文件");
                            value =
                                    p >= 0
                                            ? value.substring(0, p)
                                                    + "="
                                                    + encode(value.substring(p + 1))
                                            : encode(value);
                        } else if (option.equals("-d") || option.equals("--data"))
                            value = value.replace("\r", "").replace("\n", "");
                        data.add(value);
                    }
                }
            } else
                switch (option) {
                    case "-G", "--get" -> get = true;
                    case "-I", "--head" -> head = true;
                    case "-L", "--location" -> redirect = true;
                    case "--compressed" -> {
                        if (headers.stream()
                                .noneMatch(h -> h.name().equalsIgnoreCase("Accept-Encoding")))
                            headers.add(new RequestSpec.Header("Accept-Encoding", "gzip, deflate"));
                        warnings.add("仅支持 gzip / deflate；显式 br 或 zstd 编码会被拒绝");
                    }
                    case "-s", "--silent", "-S", "--show-error", "--globoff", "-g" -> {
                        /* output or URL glob options; URL is never glob expanded */
                    }
                    default -> {
                        if (option.startsWith("-"))
                            throw new ApiException("暂不支持此 cURL 选项：" + option.split("=", 2)[0]);
                        if (url != null) throw new ApiException("一次只支持一个 cURL 请求，不支持额外命令或多个 URL");
                        url = token;
                    }
                }
        }
        if (url == null) throw new ApiException("缺少 URL");
        if (head && hadBody && !get) throw new ApiException("HEAD 请求暂不支持 Body");
        String body = String.join("&", data);
        if (get && hadBody) {
            url += (url.contains("?") ? "&" : "?") + body;
            body = "";
            hadBody = false;
        }
        if (redirect && method != null)
            throw new ApiException("暂不支持同时使用 -L 和显式 -X；请复制最终接口请求，避免重定向改变请求语义");
        method = method != null ? method : head ? "HEAD" : get ? "GET" : hadBody ? "POST" : "GET";
        if (!METHODS.contains(method))
            throw new ApiException(
                    "当前发送层支持 GET / POST / PUT / DELETE / HEAD / OPTIONS；暂不支持 " + method);
        if ((method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) && hadBody)
            throw new ApiException("当前 Hutool 发送层不支持此方法的请求体；请使用 -G 或其他方法");
        if (hadBody && headers.stream().noneMatch(h -> h.name().equalsIgnoreCase("Content-Type")))
            headers.add(
                    new RequestSpec.Header("Content-Type", "application/x-www-form-urlencoded"));
        for (var h : headers)
            if (h.name().equalsIgnoreCase("Accept-Encoding")
                    && !h.value().matches("(?i)(gzip|deflate|identity|[ ,])*"))
                throw new ApiException(
                        "当前适配只支持 gzip / deflate / identity 压缩，请重新复制未使用 br / zstd 的请求");
        validateUrl(url);
        return new Preview(
                new RequestSpec(
                        method,
                        url,
                        headers,
                        body.getBytes(StandardCharsets.UTF_8),
                        redirect,
                        timeout),
                warnings);
    }

    public static URI validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
            if (!value.chars().allMatch(c -> c > 32 && c < 127))
                throw new IllegalArgumentException();
            return uri;
        } catch (Exception e) {
            throw new ApiException("URL 需为合法 HTTP / HTTPS 地址，不能含账号密码、片段或未编码字符");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    List<String> tokenize(String input) {
        boolean cmd =
                input.matches("(?s).*\\^([\r\n\"]).*")
                        || input.stripLeading().startsWith("curl.exe");
        if (cmd) {
            StringBuilder normalized = new StringBuilder();
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (c == '^' && i + 1 < input.length()) {
                    c = input.charAt(++i);
                    if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                    if (c == '\r' || c == '\n') continue;
                }
                normalized.append(c);
            }
            input = normalized.toString();
            if (input.matches("(?s).*%[A-Za-z_][A-Za-z0-9_]*%.*"))
                throw new ApiException("不展开 CMD 环境变量，请粘贴实际值");
        }
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false, ansi = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                    ansi = false;
                } else if (ansi && c == '\\') {
                    if (++i >= input.length()) throw new ApiException("未闭合的转义");
                    char escape = input.charAt(i);
                    word.append(
                            switch (escape) {
                                case 'n' -> '\n';
                                case 'r' -> '\r';
                                case 't' -> '\t';
                                case '\\' -> '\\';
                                case '\'' -> '\'';
                                default -> throw new ApiException("暂不支持此 ANSI 转义");
                            });
                } else word.append(c);
            } else if (quote == '"') {
                if (c == '"') quote = 0;
                else if (c == '\\'
                        && i + 1 < input.length()
                        && (input.charAt(i + 1) == '"'
                                || input.charAt(i + 1) == '\\'
                                || input.charAt(i + 1) == '$'
                                || input.charAt(i + 1) == '`'
                                || input.charAt(i + 1) == '\n')) {
                    c = input.charAt(++i);
                    if (c != '\n') word.append(c);
                } else if (!cmd && (c == '$' || c == '`'))
                    throw new ApiException("不展开变量或命令，请使用字面值");
                else word.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (!cmd && c == '$' && i + 1 < input.length() && input.charAt(i + 1) == '\'') {
                quote = '\'';
                ansi = true;
                started = true;
                i++;
            } else if (c == '\\' && !cmd) {
                if (++i >= input.length()) throw new ApiException("未闭合的转义");
                c = input.charAt(i);
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                if (c != '\r' && c != '\n') {
                    word.append(c);
                    started = true;
                }
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    out.add(word.toString());
                    word.setLength(0);
                    started = false;
                }
            } else if (";|&<>`".indexOf(c) >= 0 || (!cmd && c == '$'))
                throw new ApiException("不支持多条命令、管道、重定向或变量展开；URL / Body 请使用引号包裹");
            else {
                word.append(c);
                started = true;
            }
        }
        if (quote != 0) throw new ApiException("引号未闭合");
        if (started) out.add(word.toString());
        return out;
    }
}
