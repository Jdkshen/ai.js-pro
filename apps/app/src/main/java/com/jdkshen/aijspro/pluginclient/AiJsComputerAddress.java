package com.jdkshen.aijspro.pluginclient;

/** Address of the AI.js Pro VS Code extension's TCP listener. */
public final class AiJsComputerAddress {
    public static final int DEFAULT_PORT = 8327;
    public final String host;
    public final int port;

    private AiJsComputerAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String displayAddress() {
        return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    public static AiJsComputerAddress parse(String input, Integer explicitPort) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("请输入电脑 IP 地址或主机名");
        if (value.contains("://") || value.matches(".*[\\s/@?#\\\\].*")) {
            throw new IllegalArgumentException("请填写电脑地址，例如 192.168.1.100:8327");
        }
        String host = value;
        String portText = null;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 2) throw new IllegalArgumentException("IPv6 地址格式不正确");
            host = value.substring(1, end);
            String tail = value.substring(end + 1);
            if (!tail.isEmpty()) {
                if (!tail.startsWith(":")) throw new IllegalArgumentException("电脑地址格式不正确");
                portText = tail.substring(1);
            }
        } else if (value.indexOf(':') == value.lastIndexOf(':') && value.contains(":")) {
            int colon = value.indexOf(':');
            host = value.substring(0, colon);
            portText = value.substring(colon + 1);
        }
        if (host.contains(":")) {
            // Numeric IPv6 only; address resolution is deferred to the connection worker.
            if (!host.matches("[0-9A-Fa-f:.]+") || !host.contains("::") && host.split(":", -1).length != 8) {
                throw new IllegalArgumentException("IPv6 地址格式不正确");
            }
            try {
                if (!(java.net.InetAddress.getByName(host) instanceof java.net.Inet6Address)) {
                    throw new IllegalArgumentException("IPv6 地址格式不正确");
                }
            } catch (java.net.UnknownHostException error) {
                throw new IllegalArgumentException("IPv6 地址格式不正确");
            }
        } else {
            if (!host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?") || host.contains("..")) {
                throw new IllegalArgumentException("电脑地址格式不正确");
            }
            if (host.matches("[0-9.]+")) {
                String[] octets = host.split("\\.", -1);
                if (octets.length != 4) throw new IllegalArgumentException("IP 地址格式不正确");
                for (String octet : octets) {
                    if (octet.isEmpty() || octet.length() > 3 || Integer.parseInt(octet) > 255) {
                        throw new IllegalArgumentException("IP 地址格式不正确");
                    }
                }
            }
        }
        int port = DEFAULT_PORT;
        if (portText != null) {
            if (!portText.matches("[0-9]{1,5}")) throw new IllegalArgumentException("端口应在 1 到 65535 之间");
            port = Integer.parseInt(portText);
        }
        if (explicitPort != null) port = explicitPort;
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口应在 1 到 65535 之间");
        return new AiJsComputerAddress(host, port);
    }
}
