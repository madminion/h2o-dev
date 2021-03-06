package water.api;

import water.init.NetworkTest;
import water.util.TwoDimTable;

public class NetworkTestV2 extends Schema<NetworkTest, NetworkTestV2> {
  @API(help="Collective broadcast/reduce times in microseconds (for each message size)", direction = API.Direction.OUTPUT)
  public double[] microseconds_collective;
  @API(help="Collective bandwidths in Bytes/sec (for each message size, for each node)", direction = API.Direction.OUTPUT)
  public double[] bandwidths_collective;
  @API(help="Round-trip times in microseconds (for each message size, for each node)", direction = API.Direction.OUTPUT)
  public double[][] microseconds;
  @API(help="Bi-directional bandwidths in Bytes/sec (for each message size, for each node)", direction = API.Direction.OUTPUT)
  public double[][] bandwidths;
  @API(help="Nodes", direction = API.Direction.OUTPUT)
  public String[] nodes;
  @API(help="NetworkTestResults", direction = API.Direction.OUTPUT)
  public TwoDimTableV1 table;
}
