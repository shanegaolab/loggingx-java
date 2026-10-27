package com.loggingx;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent API: collect configs, install scripts, and the agents' sync. The generated scripts are also written to
 * {@code target/agent-samples}, where they can be syntax-checked ({@code bash -n}) and their configs validated with
 * a real Vector.
 */
class AgentApiIntegrationTest extends ApiIntegrationTest {

    private static final String COLLECT = "{\"paths\":[\"/var/log/app/*.log\"],\"windowsPaths\":[\"C:\\\\logs\\\\*.log\"],"
            + "\"containers\":true,\"multiline\":\"stacktrace\",\"transport\":\"http\"}";

    private HttpResponse<String> request(String method, String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).method(method,
                HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            request.headers(headers);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token, String ingestToken) throws Exception {
        if (token != null) {
            return request("GET", path, "Authorization", "Bearer " + token);
        }
        return ingestToken == null ? request("GET", path) : request("GET", path, "X-Log-Token", ingestToken);
    }

    private static void save(String name, String body) throws Exception {
        Path samples = Path.of("target", "agent-samples");
        Files.createDirectories(samples);
        Files.writeString(samples.resolve(name), body, StandardCharsets.UTF_8);
    }

    private String createStore(String token, String code) throws Exception {
        String created = send("POST", "/api/logstore", token,
                "{\"code\":\"" + code + "\",\"name\":\"Agent\",\"retentionDays\":7,\"enabled\":true}").body();
        return extract(created, "\"storeId\":(\\d+)");
    }

    private String ingestToken(String token, String storeId) throws Exception {
        String list = send("GET", "/api/logstore/list", token, null).body();
        return extract(list, "\"storeId\":" + storeId + ",[^}]*?\"ingestToken\":\"([^\"]+)\"");
    }

    @Test
    void agentHostsFetchScriptsWithTheIngestToken() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String storeId = createStore(token, "it-agent");
        String ingestToken = ingestToken(token, storeId);
        assertEquals(200, send("PUT", "/api/agent/collect?storeId=" + storeId, token, COLLECT).statusCode());

        HttpResponse<String> linux = get("/api/agent/install.sh?platform=linux&server=https://logs.example.com",
                null, ingestToken);
        assertEquals(200, linux.statusCode(), linux.body());
        assertTrue(linux.body().startsWith("#!/usr/bin/env bash"));
        assertTrue(linux.body().contains("# loggingx-revision: "), "the first config carries its revision");
        assertTrue(linux.body().contains("uri: 'https://logs.example.com/api/logs/ingest'"), linux.body());
        assertTrue(linux.body().contains("X-Log-Token: '" + ingestToken + "'"));
        assertTrue(linux.body().contains("- '/var/log/app/*.log'"), "paths come from the collect config");
        assertTrue(linux.body().contains("LOGGINGX_TOKEN='" + ingestToken + "' /bin/sh"), "registers on install");
        assertTrue(linux.body().contains("OnUnitActiveSec=30s"));
        save("install-linux.sh", linux.body());

        HttpResponse<String> docker = get("/api/agent/install.sh?platform=docker", null, ingestToken);
        assertEquals(200, docker.statusCode(), docker.body());
        assertTrue(docker.body().contains("-v /:/host:ro"), docker.body());
        assertTrue(docker.body().contains("/var/run/docker.sock"), "collects container output");
        assertTrue(docker.body().contains("kill -HUP 1"), "the container reloads Vector on a new config");
        // Without a server parameter, agents use the address LoggingX was reached at.
        assertTrue(docker.body().contains("uri: 'http://localhost:"), docker.body());
        save("install-docker.sh", docker.body());

        HttpResponse<String> windows = get("/api/agent/install.ps1", null, ingestToken);
        assertEquals(200, windows.statusCode(), windows.body());
        assertTrue(windows.body().contains("service install --name $Service"));
        assertTrue(windows.body().contains("- 'C:\\logs\\*.log'"), windows.body());
        assertTrue(windows.body().contains("Register-ScheduledTask"));
        assertTrue(windows.body().startsWith("#"), "no BOM when the text is run directly");
        HttpResponse<String> saved = get("/api/agent/install.ps1?download=true", null, ingestToken);
        assertTrue(saved.body().startsWith("\uFEFF#"), "a saved file starts with a BOM for PowerShell 5.1");
        save("install-windows.ps1", windows.body());

        HttpResponse<String> kubernetes = get("/api/agent/kubernetes.yaml", null, ingestToken);
        assertEquals(200, kubernetes.statusCode(), kubernetes.body());
        assertTrue(kubernetes.body().contains("kind: DaemonSet"));
        assertTrue(kubernetes.body().contains("type: kubernetes_logs"), kubernetes.body());
        save("kubernetes.yaml", kubernetes.body());

        // Managers preview the config on the agent page with their login and the store id.
        HttpResponse<String> config = get("/api/agent/config?platform=linux&storeId=" + storeId, token, null);
        assertEquals(200, config.statusCode(), config.body());
        assertTrue(config.body().contains("type: file"));
        save("config-preview.yaml", config.body());

        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }

    @Test
    void agentsRegisterSyncAndFollowTheCollectConfig() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String storeId = createStore(token, "it-agent-sync");
        String ingestToken = ingestToken(token, storeId);
        String id = UUID.randomUUID().toString();
        String secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String sync = "/api/agent/sync?platform=linux&version=0.58.0&hostname=web-01&server=http://logs.test";

        assertEquals(401, request("GET", sync).statusCode(), "an id and secret are required");
        assertEquals(401, request("GET", sync, "X-Agent-Id", id, "X-Agent-Secret", secret).statusCode(),
                "an unknown agent needs the ingest token to register");

        // The first sync registers the agent and hands it the config.
        HttpResponse<String> first = request("GET", sync, "X-Agent-Id", id, "X-Agent-Secret", secret,
                "X-Log-Token", ingestToken);
        assertEquals(200, first.statusCode(), first.body());
        String revision = first.headers().firstValue("X-Config-Revision").orElseThrow();
        assertTrue(first.body().startsWith("# loggingx-revision: " + revision + "\n"));
        assertTrue(first.body().contains("uri: 'http://logs.test/api/logs/ingest'"), first.body());

        // Later syncs authenticate with the secret alone; an unchanged config is not sent again.
        HttpResponse<String> heartbeat = request("GET", sync + "&revision=" + revision,
                "X-Agent-Id", id, "X-Agent-Secret", secret);
        assertEquals(204, heartbeat.statusCode(), heartbeat.body());
        assertEquals(401, request("GET", sync, "X-Agent-Id", id, "X-Agent-Secret", secret.replace('0', '1'))
                .statusCode(), "a wrong secret is rejected");

        String agents = send("GET", "/api/agent/agents?storeId=" + storeId, token, null).body();
        assertTrue(agents.contains("\"hostname\":\"web-01\""), agents);
        assertTrue(agents.contains("\"online\":true"), agents);
        assertTrue(agents.contains("\"syncState\":\"synced\""), agents);
        String stores = send("GET", "/api/logstore/list", token, null).body();
        assertTrue(stores.matches("(?s).*\"storeId\":" + storeId
                + ",[^}]*\"agentCount\":1,\"onlineAgentCount\":1.*"), stores);

        // A collect config change reaches the agent on its next sync.
        assertEquals(200, send("PUT", "/api/agent/collect?storeId=" + storeId, token,
                COLLECT.replace("/var/log/app/*.log", "/srv/orders/*.log")).statusCode());
        assertTrue(send("GET", "/api/agent/agents?storeId=" + storeId, token, null).body()
                .contains("\"syncState\":\"pending\""));
        HttpResponse<String> changed = request("GET", sync + "&revision=" + revision,
                "X-Agent-Id", id, "X-Agent-Secret", secret);
        assertEquals(200, changed.statusCode(), changed.body());
        assertTrue(changed.body().contains("- '/srv/orders/*.log'"), changed.body());
        revision = changed.headers().firstValue("X-Config-Revision").orElseThrow();

        // So does a new ingest token: the agent keeps syncing with its secret and receives the new token.
        String regenerated = send("POST", "/api/logstore/" + storeId + "/token", token, null).body();
        String newToken = extract(regenerated, "\"ingestToken\":\"([^\"]+)\"");
        HttpResponse<String> rotated = request("GET", sync + "&revision=" + revision,
                "X-Agent-Id", id, "X-Agent-Secret", secret, "X-Log-Token", ingestToken);
        assertEquals(200, rotated.statusCode(), "the old token is ignored once the secret matches");
        assertTrue(rotated.body().contains("X-Log-Token: '" + newToken + "'"), rotated.body());

        // The uninstaller removes the agent.
        assertEquals(204, request("DELETE", "/api/agent/sync", "X-Agent-Id", id, "X-Agent-Secret", secret)
                .statusCode());
        assertFalse(send("GET", "/api/agent/agents?storeId=" + storeId, token, null).body().contains("web-01"));

        // Deleting the store removes its agents.
        assertEquals(200, request("GET", sync, "X-Agent-Id", id, "X-Agent-Secret", secret,
                "X-Log-Token", newToken).statusCode());
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
        assertEquals(401, request("GET", sync, "X-Agent-Id", id, "X-Agent-Secret", secret).statusCode());
    }

    @Test
    void collectConfigsAreValidatedAndManagedByStoreManagers() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String storeId = createStore(token, "it-agent-collect");
        HttpResponse<String> defaults = send("GET", "/api/agent/collect?storeId=" + storeId, token, null);
        assertEquals(200, defaults.statusCode(), defaults.body());
        assertTrue(defaults.body().contains("\"updateTime\":null"), "a new store uses the defaults");

        assertEquals(400, send("PUT", "/api/agent/collect?storeId=" + storeId, token,
                COLLECT.replace("/var/log/app/*.log", "/var/log/$(reboot)")).statusCode());
        assertEquals(400, send("PUT", "/api/agent/collect?storeId=" + storeId, token,
                "{\"paths\":[],\"windowsPaths\":[],\"multiline\":\"none\",\"transport\":\"kafka\"}").statusCode());
        HttpResponse<String> linuxOnly = send("PUT", "/api/agent/collect?storeId=" + storeId, token,
                "{\"paths\":[\"/var/log/a.log\"],\"windowsPaths\":[],\"multiline\":\"none\",\"transport\":\"http\"}");
        assertEquals(200, linuxOnly.statusCode(), linuxOnly.body());
        HttpResponse<String> windows = get("/api/agent/config?platform=windows&storeId=" + storeId, token, null);
        assertEquals(400, windows.statusCode(), "no Windows paths to collect");

        String userToken = login("user", DEMO_PASSWORD);
        assertEquals(403, send("GET", "/api/agent/collect?storeId=" + storeId, userToken, null).statusCode());
        assertEquals(403, send("GET", "/api/agent/agents", userToken, null).statusCode());
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }

    @Test
    void scriptsNeedATokenOrAStoreManager() throws Exception {
        assertEquals(401, get("/api/agent/install.sh?platform=linux", null, null).statusCode());
        assertEquals(401, get("/api/agent/install.sh?platform=linux", null, "lx_wrong").statusCode());
        String userToken = login("user", DEMO_PASSWORD);
        assertEquals(401, get("/api/agent/config?storeId=1", userToken, null).statusCode());
        assertEquals(403, send("GET", "/api/agent/meta", userToken, null).statusCode());

        String superToken = login("super", DEMO_PASSWORD);
        String storeId = createStore(superToken, "it-agent-unsafe");
        HttpResponse<String> unsafe = get("/api/agent/config?storeId=" + storeId + "&server=https://x/'$(reboot)",
                superToken, null);
        assertEquals(400, unsafe.statusCode(), unsafe.body());
        HttpResponse<String> meta = send("GET", "/api/agent/meta", superToken, null);
        assertEquals(200, meta.statusCode(), meta.body());
        assertTrue(meta.body().contains("\"version\":\"0.58.0\""), meta.body());
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, superToken, null).statusCode());
    }

    @Test
    void packagesRedirectToTheUpstreamRelease() throws Exception {
        HttpResponse<String> known = get("/api/agent/download/vector-0.58.0-x86_64-unknown-linux-musl.tar.gz",
                null, null);
        assertEquals(302, known.statusCode());
        assertEquals("https://github.com/vectordotdev/vector/releases/download/v0.58.0/"
                + "vector-0.58.0-x86_64-unknown-linux-musl.tar.gz", known.headers().firstValue("Location").orElse(""));
        assertEquals(404, get("/api/agent/download/evil.sh", null, null).statusCode());
        assertFalse(get("/api/agent/download/..%2F..%2Fetc%2Fpasswd", null, null).statusCode() == 200);
    }
}
