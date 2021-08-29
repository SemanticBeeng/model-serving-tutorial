/*
 * Copyright (C) 2017-2019  Lightbend
 *
 * This file is part of the Lightbend model-serving-tutorial (https://github.com/lightbend/model-serving-tutorial)
 *
 * The model-serving-tutorial is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License Version 2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lightbend.modelserving.flink.wine.server

import java.util.Properties

import com.lightbend.model.winerecord.WineRecord
import com.lightbend.modelserving.model.DataToServe
import com.lightbend.modelserving.configuration.ModelServingConfiguration
import com.lightbend.modelserving.flink.keyed.DataProcessorKeyed
import com.lightbend.modelserving.flink.typeschema.ByteArraySchema
import com.lightbend.modelserving.flink.wine.BadDataHandler
import com.lightbend.modelserving.model.ModelToServe
import com.lightbend.modelserving.winemodel.{DataRecord, WineFactoryResolver}
import org.apache.flink.api.common.JobID
import org.apache.flink.configuration.{Configuration, JobManagerOptions, QueryableStateOptions, TaskManagerOptions}
import org.apache.flink.runtime.minicluster.{MiniCluster, MiniClusterConfiguration, RpcServiceSharing}
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer
import org.apache.flink.streaming.api.scala._

/**
  * Queryable state implementation loosely based on this approach from data Artisans (now Ververica):
  * http://dataartisans.github.io/flink-training/exercises/eventTimeJoin.html
  * See also this README:
  *   https://github.com/dataArtisans/flink-queryable_state_demo/blob/master/README.md
  * See also this class as an example of a Flink server to enable Queryable data access:
  *   see https://github.com/dataArtisans/flink-queryable_state_demo/blob/master/src/main/java/com/dataartisans/queryablestatedemo/EventCountJob.java
  *
  * This little application is based on a RichCoProcessFunction that works on a keyed streams. It is applicable
  * when a single applications serves multiple different models for different data types. Every model is keyed with
  * the type of data that it is designed for. The same key must be present in the data, if it wants to use a specific
  * model.
  *
  * Scaling of the application is based on the data type; for every key there is a separate instance of the
  * RichCoProcessFunction dedicated to this type. All messages of the same type are processed by the same instance
  * of RichCoProcessFunction
  */
object ModelServingKeyedJob {

  import ModelServingConfiguration._

  val defaultIDFileName = "./ModelServingKeyedJob.id"
//  val outputFileName = "./output/flink-keyed.txt"

  /**
   * Entry point. It takes one optional argument, a file path to which the job ID
   * is written, so that the {@link com.lightbend.modelserving.flink.wine.query.ModelStateQueryJob}
   * can query the job. The default path is `./ModelServingKeyedJob.id`.
   * The ID is also written to stderr.
   */
  def main(args: Array[String]): Unit = {
    val idFileName = if (args.length > 0) args(0) else defaultIDFileName
    executeServer(idFileName)
  }

  /** Execute on the local Flink server - to test queryable state */
  def executeServer(idFileName: String) : Unit = {

    // We use a mini cluster here for sake of simplicity, because I don't want
    // to require a Flink installation to run this demo. Everything should be
    // contained in this JAR.

    val port = 6124
    val parallelism = 2

    val config = new Configuration()
    config.setInteger(JobManagerOptions.PORT, port)
    config.setString(JobManagerOptions.ADDRESS, "localhost")
    config.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, parallelism)

    // In a non MiniCluster setup queryable state is enabled by default.
    config.setBoolean(QueryableStateOptions.ENABLE_QUERYABLE_STATE_PROXY_SERVER, true)
    config.setString(QueryableStateOptions.PROXY_PORT_RANGE, "9069")
    config.setInteger(QueryableStateOptions.PROXY_NETWORK_THREADS, 2)
    config.setInteger(QueryableStateOptions.PROXY_ASYNC_QUERY_THREADS, 2)

    config.setString(QueryableStateOptions.SERVER_PORT_RANGE, "9067")
    config.setInteger(QueryableStateOptions.SERVER_NETWORK_THREADS, 2)
    config.setInteger(QueryableStateOptions.SERVER_ASYNC_QUERY_THREADS, 2)


    val flinkCluster = new MiniCluster(
      new MiniClusterConfiguration.Builder()
        .setConfiguration(config)
        .setNumTaskManagers(1)
        .setRpcServiceSharing(RpcServiceSharing.SHARED)
        .setCommonBindAddress(null)
        .build()
      //new MiniClusterConfiguration(config, 1, RpcServiceSharing.SHARED, null, MiniCluster.HaServices.WITH_LEADERSHIP_CONTROL))
    )
    try {
      // Start server and create environment
      flinkCluster.start()
      val env = StreamExecutionEnvironment.createRemoteEnvironment("localhost", port)
      // Build Graph
      buildGraph(env)
      val jobGraph = env.getStreamGraph.getJobGraph()
      // Submit to the server and wait for completion
      val submit = flinkCluster.submitJob(jobGraph).get()
      System.out.println(s"Job ID: ${submit.getJobID}")
      Thread.sleep(Long.MaxValue)
    } catch {
      case e: Exception => e.printStackTrace()
    }
  }

  /** Build the execution Graph */
  def buildGraph(env : StreamExecutionEnvironment) : Unit = {
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.enableCheckpointing(5000)

    // configure Kafka consumer
    // Data
    val dataKafkaProps = new Properties
    dataKafkaProps.setProperty("bootstrap.servers", KAFKA_BROKER)
    dataKafkaProps.setProperty("group.id", DATA_GROUP)
    // always read the Kafka topic from the current location
    dataKafkaProps.setProperty("auto.offset.reset", "earliest")

    // Model
    val modelKafkaProps = new Properties
    modelKafkaProps.setProperty("bootstrap.servers", KAFKA_BROKER)
    modelKafkaProps.setProperty("group.id", MODELS_GROUP)
    // always read the Kafka topic from the current location
    modelKafkaProps.setProperty("auto.offset.reset", "earliest")

    // create a Kafka consumers
    // Data
    val dataConsumer = new FlinkKafkaConsumer[Array[Byte]](
      DATA_TOPIC,
      new ByteArraySchema,
      dataKafkaProps
    )

    // Model
    val modelConsumer = new FlinkKafkaConsumer[Array[Byte]](
      MODELS_TOPIC,
      new ByteArraySchema,
      modelKafkaProps
    )

    // Create input data streams
    val modelsStream = env.addSource(modelConsumer)
    val dataStream = env.addSource(dataConsumer)

    // Set modelToServe
    ModelToServe.setResolver(WineFactoryResolver)

    // Read models from streams
    val models = modelsStream.map(ModelToServe.fromByteArray(_))
      .flatMap(BadDataHandler[ModelToServe])
      .keyBy(_.dataType)
    // Read data from streams
    val data = dataStream.map(DataRecord.fromByteArray(_))
      .flatMap(BadDataHandler[DataToServe[WineRecord]])
      .keyBy(_.getType)

    // Merge streams
    data
      .connect(models)
      .process(DataProcessorKeyed[WineRecord, Double]())
      .map{ result =>
        println(s"Model served in ${System.currentTimeMillis() - result.submissionTs} ms, with result ${result.result} (model ${result.name}, data type ${result.dataType})")
        result
      }
//      .writeAsText(outputFileName) // Also write the records to a file.
  }

  import java.nio.file.{Files, Paths}
  import java.nio.file.StandardOpenOption.{WRITE, TRUNCATE_EXISTING}
  import java.nio.charset.StandardCharsets.UTF_8
  import scala.collection.JavaConverters._

  protected def writeJobId(idFileName: String, jobID: JobID): Unit = {
    val msg = s"Started job with ID : ${jobID}"
    println(msg)
    Console.err.println(msg)
    Files.write(Paths.get(idFileName), asJavaIterable(Seq(jobID.toString)), UTF_8, WRITE, TRUNCATE_EXISTING);
  }
}
