package org.apache.cassandra.plugins;

import java.io.IOException;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import org.apache.cassandra.thrift.*;

import org.safehaus.uuid.UUID;
import org.safehaus.uuid.UUIDGenerator;

import com.cloudera.flume.conf.Context;
import com.cloudera.flume.conf.SinkFactory.SinkBuilder;
import com.cloudera.flume.core.Event;
import com.cloudera.flume.core.EventSink;
import com.cloudera.util.Pair;

/**
 * Allows Cassandra to be used as a sink, primarily for log messages.
 * 
 * When the Cassandra sink receives an event, it does the following:
 *
 * 1. Creates a column where the name is a type 1 UUID (timestamp based) and the
 *    value is the event body.
 * 2. Inserts it into row "YYYYMMDD" (the current date) in the given ColumnFamily.
 *
 * SimpleCassandraSink primarily targets log storage right now.
 */
public class SimpleCassandraSink extends EventSink.Base {
  
  private String dataColumnFamily;
  private String indexColumnFamily;
    
  private CassandraClient cClient;

  private static final UUIDGenerator uuidGen = UUIDGenerator.getInstance();

  public SimpleCassandraSink(String keyspace, String dataColumnFamily,
      String indexColumnFamily, String[] servers) {
    this.dataColumnFamily = dataColumnFamily;
    this.indexColumnFamily = indexColumnFamily;
    
    this.cClient = new CassandraClient(keyspace, servers);
  }

  @Override
  public void open() throws IOException {
    this.cClient.open();
  }

  /**
   * Writes the message to Cassandra.
   * The key is the current date (YYYYMMDD) and the column
   * name is a type 1 UUID, which includes a time stamp
   * component.
 * @throws InterruptedException 
   */
  @Override
  public void append(Event event) throws IOException, InterruptedException {

    long timestamp = event.getNanos();

    // Make the index column
    UUID uuid = uuidGen.generateTimeBasedUUID();
    Column indexColumn = new Column();
			indexColumn.setName(uuid.toByteArray());
			indexColumn.setValue(new byte[0]);
			indexColumn.setTimestamp(timestamp);

    // Make the data column
    Column dataColumn = new Column();
			dataColumn.setName("data".getBytes());
			dataColumn.setValue(event.getBody());
			dataColumn.setTimestamp(timestamp);
	
	Column catColumn = new Column();
	        catColumn.setName("category".getBytes());
	        catColumn.setValue(event.get("scribe.category"));

	Column hostColumn = new Column();
	        hostColumn.setName("host".getBytes());
	        hostColumn.setValue(event.getHost().getBytes());

    // Insert the index
    this.cClient.insert(this.getKey(event), this.indexColumnFamily, new Column[] {indexColumn}, ConsistencyLevel.LOCAL_QUORUM);
    // Insert the data (row key is the uuid and there is only one column)
    this.cClient.insert(uuid.toString().getBytes(), this.dataColumnFamily, new Column[] {dataColumn, catColumn, hostColumn}, ConsistencyLevel.LOCAL_QUORUM);
    super.append(event);
  }

  /**
   * Returns a String representing the current date to be used as
   * a key.  This has the format "YYYYMMDDHH".
   */
  private byte[] getKey(Event event) {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+0"));
    cal.setTimeInMillis(event.getTimestamp());

    int day = cal.get(Calendar.DAY_OF_MONTH);
    int month = cal.get(Calendar.MONTH);
    int year = cal.get(Calendar.YEAR);
    int hour = cal.get(Calendar.HOUR_OF_DAY);

    StringBuffer buff = new StringBuffer();
    buff.append(year);
    if(month < 10)
      buff.append('0');
    buff.append(month);
    if(day < 10)
      buff.append('0');
    buff.append(day);
    if(hour < 10)
      buff.append('0');
    buff.append(hour);
    return buff.toString().getBytes();
  }

  @Override
  public void close() throws IOException {
    this.cClient.close();
  }

  public static SinkBuilder builder() {
    return new SinkBuilder() {
      @Override
      public EventSink build(Context context, String ... args) {
        if (args.length < 4) {
          throw new IllegalArgumentException(
              "usage: simpleCassandraSink(\"keyspace\", \"data_column_family\", " +
              "\"index_column_family\", \"host:port\"...");
        }
        String[] servers = Arrays.copyOfRange(args, 3, args.length);
        return new SimpleCassandraSink(args[0], args[1], args[2], servers);
      }
    };
  }

  /**
   * This is a special function used by the SourceFactory to pull in this class
   * as a plugin sink.
   */
  public static List<Pair<String, SinkBuilder>> getSinkBuilders() {
    List<Pair<String, SinkBuilder>> builders =
      new ArrayList<Pair<String, SinkBuilder>>();
    builders.add(new Pair<String, SinkBuilder>("simpleCassandraSink", builder()));
    return builders;
  }
}
