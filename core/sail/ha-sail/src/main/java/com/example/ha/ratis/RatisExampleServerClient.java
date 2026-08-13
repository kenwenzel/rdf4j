package com.example.ha.ratis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.api.RaftClientReply;
import org.apache.ratis.protocol.Message;

import org.eclipse.rdf4j.repository.sail.SailRepository;

import com.example.ha.snapshot.NativeStoreSnapshotManager;
import com.example.ha.snapshot.NativeStoreStatementApplier;

/**
 * Example helper that constructs a single-node RaftServer and a RaftClient (local) and wires a RatisStateMachine.
 *
 * WARNING: This example is intended for local testing and illustration. For multi-node clusters, set the proper
 * peer addresses and configure RPC transport (gRPC/Netty) and data directories.
 */
public final class RatisExampleServerClient {
    private static final Logger LOG = Logger.getLogger(RatisExampleServerClient.class.getName());

    private RaftServer server;
    private RaftClient client;

    /**
     * Start a single-node Raft server and create a client connected to that server.
     *
     * @param repository the SailRepository (NativeStore) used for applying statements and snapshots
     * @param workDir base directory for Raft server storage (will create subdirs)
     * @throws Exception on startup error
     */
    public void start(SailRepository repository, Path workDir) throws Exception {
        // Ensure work dir exists
        Files.createDirectories(workDir);

        // Unique group id
        RaftGroupId groupId = RaftGroupId.valueOf(UUID.randomUUID().toString());
        // Local single peer id "node1"
        RaftPeer peer = RaftPeer.newBuilder().setId("node1").build();
        RaftGroup group = RaftGroup.valueOf(groupId, Collections.singletonList(peer));

        // Ratis properties
        RaftProperties props = new RaftProperties();
        // set server storage directory
        RaftServerConfigKeys.setStorageDir(props, Collections.singletonList(workDir.resolve("ratis_storage").toFile()));

        // Build snapshot manager & state machine components
        NativeStoreSnapshotManager snapshotManager = new NativeStoreSnapshotManager(repository);
        NativeStoreStatementApplier applier = new NativeStoreStatementApplier(repository);
        RatisStateMachineAdapter adapter = new RatisStateMachineAdapter(applier);
        RatisStateMachine stateMachine = new RatisStateMachine(adapter, snapshotManager);

        // Build and start RaftServer
        server = RaftServer.newBuilder()
                .setGroup(group)
                .setProperties(props)
                .setServerId(peer.getId())
                .setStateMachine(stateMachine)
                .build();

        server.start();

        // Build RaftClient - connect to the same group
        client = RaftClient.newBuilder()
                .setProperties(props)
                .setRaftGroup(group)
                .build();

        // Optionally wait briefly for initialization
        TimeUnit.SECONDS.sleep(1);

        LOG.info("Single-node Raft server started and client created");
    }

    public void stop() throws Exception {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    /**
     * Append application payload bytes via the client to the Raft log.
     * Blocks until commit (client.append returns a reply).
     */
    public RaftClientReply append(byte[] payload) throws Exception {
        Message msg = Message.valueOf(payload);
        return client.append(msg);
    }
}