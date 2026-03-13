package com.storage.engine.config;

import cn.edu.tsinghua.iginx.exception.SessionException;
import cn.edu.tsinghua.iginx.session.ClusterInfo;
import cn.edu.tsinghua.iginx.session.Session;
import cn.edu.tsinghua.iginx.thrift.IginxInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connection pool that manages IGinX sessions for active cluster nodes.
 *
 * Startup rule is preserved: seed node must be reachable, otherwise web server
 * startup fails.
 */
@Component
public class IGinxConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(IGinxConnectionPool.class);

    @Value("${iginx.host}")
    private String seedHost;

    @Value("${iginx.port}")
    private int seedPort;

    @Value("${iginx.username}")
    private String username;

    @Value("${iginx.password}")
    private String password;

    @Value("${iginx.pool.refresh-interval-ms:5000}")
    private long refreshIntervalMs;

    private final CopyOnWriteArrayList<NodeSession> sessions = new CopyOnWriteArrayList<NodeSession>();
    private final AtomicInteger counter = new AtomicInteger(0);
    private final ScheduledExecutorService refreshExecutor =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "iginx-pool-refresh");
                    t.setDaemon(true);
                    return t;
                }
            });

    @PostConstruct
    public void init() {
        Session seedSession;
        try {
            seedSession = new Session(seedHost, seedPort, username, password);
            seedSession.openSession();
            sessions.add(new NodeSession(seedHost, String.valueOf(seedPort), seedSession));
            log.info("[ConnectionPool] Connected to seed IGinX node: {}:{}", seedHost, seedPort);
        } catch (SessionException e) {
            throw new RuntimeException(
                    "Failed to connect to seed IGinX node " + seedHost + ":" + seedPort
                            + " - server cannot start without a live IGinX node.",
                    e);
        }

        try {
            syncFromClusterInfo(seedSession);
        } catch (Exception e) {
            log.warn("[ConnectionPool] Could not enumerate cluster nodes at startup: {}", e.getMessage());
        }

        if (refreshIntervalMs < 1000L) {
            refreshIntervalMs = 1000L;
        }
        refreshExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshFromClusterInfo();
                } catch (Exception e) {
                    log.warn("[ConnectionPool] Periodic cluster sync failed: {}", e.getMessage());
                }
            }
        }, refreshIntervalMs, refreshIntervalMs, TimeUnit.MILLISECONDS);

        log.info("[ConnectionPool] Initialisation complete. Pool size: {}", sessions.size());
    }

    @PreDestroy
    public void destroy() {
        refreshExecutor.shutdownNow();
        for (NodeSession ns : sessions) {
            try {
                ns.session.closeSession();
                log.info("[ConnectionPool] Closed session to {}:{}", ns.ip, ns.port);
            } catch (Exception e) {
                log.warn("[ConnectionPool] Error closing session to {}:{}: {}", ns.ip, ns.port, e.getMessage());
            }
        }
        sessions.clear();
        log.info("[ConnectionPool] All sessions closed.");
    }

    /**
     * Register a node and open a session.
     * If an equivalent endpoint already exists (for example 127.0.0.1 and local LAN IP), it is skipped.
     */
    public synchronized void addNode(String ip, String port) {
        if (hasEquivalentSession(ip, port)) {
            log.info("[ConnectionPool] Node {}:{} already in pool (equivalent endpoint) - skipping.", ip, port);
            return;
        }
        try {
            Session s = new Session(ip, Integer.parseInt(port), username, password);
            s.openSession();
            sessions.add(new NodeSession(ip, port, s));
            log.info("[ConnectionPool] Added IGinX node {}:{} - pool size: {}", ip, port, sessions.size());
        } catch (SessionException e) {
            log.error("[ConnectionPool] Failed to open session to {}:{}: {}", ip, port, e.getMessage());
            throw new RuntimeException("Failed to open session to IGinX node " + ip + ":" + port, e);
        }
    }

    /**
     * Remove a node session by endpoint.
     * Endpoint comparison is canonicalized, so alias addresses are treated as same node.
     */
    public synchronized void removeNode(String ip, String port) {
        for (NodeSession ns : sessions) {
            if (isSameEndpoint(ns.ip, ns.port, ip, port)) {
                try {
                    ns.session.closeSession();
                    log.info("[ConnectionPool] Closed session to {}:{}", ns.ip, ns.port);
                } catch (Exception e) {
                    log.warn("[ConnectionPool] Error closing session to {}:{}: {}", ns.ip, ns.port, e.getMessage());
                }
                sessions.remove(ns);
                log.info("[ConnectionPool] Removed node {}:{} from pool - pool size: {}", ns.ip, ns.port, sessions.size());
                return;
            }
        }
        log.warn("[ConnectionPool] removeNode: {}:{} not found in pool.", ip, port);
    }

    /**
     * Round-robin session selector with logging.
     */
    public Session getNextSession() {
        List<NodeSession> snapshot = new ArrayList<NodeSession>(sessions);
        if (snapshot.isEmpty()) {
            throw new RuntimeException("IGinX connection pool is empty - no available sessions.");
        }
        int idx = (counter.getAndIncrement() & Integer.MAX_VALUE) % snapshot.size();
        NodeSession ns = snapshot.get(idx);
        log.info("[LoadBalancer] --> IGinX node {}:{} (round-robin index={}, pool size={})",
                ns.ip, ns.port, idx, snapshot.size());
        return ns.session;
    }

    public int getPoolSize() {
        return sessions.size();
    }


    /**
     * Prefer seed endpoint for cluster-level operations.
     */
    public synchronized Session getSeedSessionOrAny() {
        for (NodeSession ns : sessions) {
            if (isSameEndpoint(ns.ip, ns.port, seedHost, String.valueOf(seedPort))) {
                return ns.session;
            }
        }
        return pickAnySession();
    }

    /**
     * Evict a broken session from pool.
     */
    public synchronized void evictSession(Session session, String reason) {
        if (session == null) {
            return;
        }
        for (NodeSession ns : sessions) {
            if (ns.session == session) {
                try {
                    ns.session.closeSession();
                } catch (Exception ignored) {
                    // Best effort close.
                }
                sessions.remove(ns);
                log.warn("[ConnectionPool] Evicted IGinX node {}:{} from pool, reason={}, pool size={}",
                        ns.ip, ns.port, reason, sessions.size());
                return;
            }
        }
    }

    /**
     * Pull latest cluster info and add any node not in pool.
     */
    public synchronized void refreshFromClusterInfo() {
        Session s = getSeedSessionOrAny();
        if (s == null) {
            throw new RuntimeException("No available IGinX session for cluster refresh.");
        }
        syncFromClusterInfo(s);
    }

    private void syncFromClusterInfo(Session session) {
        ClusterInfo clusterInfo;
        synchronized (session) {
            try {
                clusterInfo = session.getClusterInfo();
            } catch (SessionException e) {
                throw new RuntimeException("Failed to get cluster info for pool sync", e);
            }
        }
        if (clusterInfo == null || clusterInfo.getIginxInfos() == null) {
            return;
        }

        for (IginxInfo info : clusterInfo.getIginxInfos()) {
            String ip = info.getIp();
            String port = String.valueOf(info.getPort());
            if (!hasEquivalentSession(ip, port)) {
                try {
                    addNode(ip, port);
                } catch (Exception e) {
                    log.warn("[ConnectionPool] Auto-discovery add failed for {}:{}: {}", ip, port, e.getMessage());
                }
            }
        }
    }

    private Session pickAnySession() {
        List<NodeSession> snapshot = new ArrayList<NodeSession>(sessions);
        if (snapshot.isEmpty()) {
            return null;
        }
        return snapshot.get(0).session;
    }

    private boolean hasEquivalentSession(String ip, String port) {
        for (NodeSession ns : sessions) {
            if (isSameEndpoint(ns.ip, ns.port, ip, port)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameEndpoint(String ip1, String port1, String ip2, String port2) {
        if (ip1 == null || ip2 == null || port1 == null || port2 == null) {
            return false;
        }
        if (!port1.equals(port2)) {
            return false;
        }
        String c1 = canonicalHost(ip1);
        String c2 = canonicalHost(ip2);
        return c1.equals(c2);
    }

    private String canonicalHost(String host) {
        String normalized = host == null ? "" : host.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        try {
            InetAddress addr = InetAddress.getByName(normalized);
            if (isLocalAddress(addr)) {
                return "__LOCAL__";
            }
            return addr.getHostAddress();
        } catch (Exception e) {
            return normalized;
        }
    }

    private boolean isLocalAddress(InetAddress target) {
        if (target == null) {
            return false;
        }
        if (target.isLoopbackAddress() || target.isAnyLocalAddress()) {
            return true;
        }
        return getLocalAddressSet().contains(target.getHostAddress());
    }

    private Set<String> getLocalAddressSet() {
        Set<String> addresses = new HashSet<String>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return addresses;
            }
            for (NetworkInterface iface : Collections.list(interfaces)) {
                if (!iface.isUp()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    addresses.add(addr.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // Return best-effort local address set.
        }
        return addresses;
    }

    private static final class NodeSession {
        final String ip;
        final String port;
        final Session session;

        NodeSession(String ip, String port, Session session) {
            this.ip = ip;
            this.port = port;
            this.session = session;
        }
    }
}
