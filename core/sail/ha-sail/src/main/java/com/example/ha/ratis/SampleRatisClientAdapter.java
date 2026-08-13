package com.example.ha.ratis;

/*
  This sample shows where to hook in an actual Apache Ratis client.
  It's intentionally commented-out because the exact Ratis API and
  how you build the RaftClient depends on the Ratis version and your
  project's configuration.

  Implement RaftClientAdapter.append(payload) by calling your Raft client to
  append the bytes and wait for commit.

  Example (pseudocode):

  import org.apache.ratis.client.RaftClient;
  import org.apache.ratis.protocol.Message;

  public class RatisClientAdapterImpl implements RaftClientAdapter {
      private final RaftClient client;

      public RatisClientAdapterImpl(RaftClient client) {
          this.client = client;
      }

      @Override
      public void append(byte[] payload) throws Exception {
          // convert to Ratis Message or the API your version requires
          Message message = Message.valueOf(payload);
          // client.append may be synchronous or return a future depending on API:
          client.append(message).get(); // wait for commit
      }
  }

  Add TLS, timeouts and retries as appropriate. See your Ratis version docs for exact methods.
*/
public final class SampleRatisClientAdapter implements RaftClientAdapter {
    @Override
    public void append(byte[] payload) throws Exception {
        throw new UnsupportedOperationException("Replace with a concrete Ratis client implementation.");
    }
}