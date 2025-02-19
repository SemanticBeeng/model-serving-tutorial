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

package com.lightbend.modelserving.client

import java.io.{File, IOException}
import java.util.Properties

import kafka.admin.{AdminUtils, RackAwareMode}
import kafka.server.{KafkaConfig, KafkaServerStartable}
import kafka.utils.ZkUtils
import org.apache.commons.io.FileUtils
import org.apache.curator.test.TestingServer
import org.slf4j.LoggerFactory
//import com.github.ghik.silencer.silent

/** A convenience utility for running a local, single instance of Kafka. */
class KafkaLocalServer private (kafkaProperties: Properties, zooKeeperServer: ZooKeeperLocalServer) {

  import KafkaLocalServer._

  private var broker = null.asInstanceOf[KafkaServerStartable]
  //@silent
  private var zkUtils : ZkUtils =
    ZkUtils.apply(s"localhost:${zooKeeperServer.getPort()}", DEFAULT_ZK_SESSION_TIMEOUT_MS, DEFAULT_ZK_CONNECTION_TIMEOUT_MS, false)

  def start(): Unit = {

    broker = KafkaServerStartable.fromProps(kafkaProperties)
    broker.startup()
  }

  def stop(): Unit = {
    if (broker != null) {
      broker.shutdown()
      zooKeeperServer.stop()
      broker = null.asInstanceOf[KafkaServerStartable]
    }
  }

  /**
    * Create a Kafka topic with 1 partition and a replication factor of 1.
    *
    * @param topic The name of the topic.
    */
  def createTopic(topic: String): Unit = {
    createTopic(topic, 1, 1, new Properties)
  }

  /**
    * Create a Kafka topic with the given parameters.
    *
    * @param topic       The name of the topic.
    * @param partitions  The number of partitions for this topic.
    * @param replication The replication factor for (the partitions of) this topic.
    */
  def createTopic(topic: String, partitions: Int, replication: Int): Unit = {
    createTopic(topic, partitions, replication, new Properties)
  }

  /**
    * Create a Kafka topic with the given parameters.
    *
    * @param topic       The name of the topic.
    * @param partitions  The number of partitions for this topic.
    * @param replication The replication factor for (partitions of) this topic.
    * @param topicConfig Additional topic-level configuration settings.
    */
  //@silent
  def createTopic(topic: String, partitions: Int, replication: Int, topicConfig: Properties): Unit = {
    AdminUtils.createTopic(zkUtils, topic, partitions, replication, topicConfig, RackAwareMode.Enforced)
  }
}

object KafkaLocalServer {
  final val DefaultPort = 9092
  final val DefaultResetOnStart = true
  private val DEFAULT_ZK_CONNECT = "localhost:2181"
  private val DEFAULT_ZK_SESSION_TIMEOUT_MS = 10 * 1000
  private val DEFAULT_ZK_CONNECTION_TIMEOUT_MS = 8 * 1000

  private final val basDir = "tmp/"

  private final val KafkaDataFolderName = "kafka_data"

  val Log = LoggerFactory.getLogger(classOf[KafkaLocalServer])

  def apply(cleanOnStart: Boolean): KafkaLocalServer = this(DefaultPort, ZooKeeperLocalServer.DefaultPort, cleanOnStart)

  def apply(kafkaPort: Int, zookeeperServerPort: Int, cleanOnStart: Boolean): KafkaLocalServer = {
    val kafkaDataDir = dataDirectory(KafkaDataFolderName)
    Log.info(s"Kafka data directory is $kafkaDataDir.")

    val kafkaProperties = createKafkaProperties(kafkaPort, zookeeperServerPort, kafkaDataDir)

    if (cleanOnStart) deleteDirectory(kafkaDataDir)
    val zk = new ZooKeeperLocalServer(zookeeperServerPort, cleanOnStart)
    zk.start()
    new KafkaLocalServer(kafkaProperties, zk)
  }

  /**
    * Creates a Properties instance for Kafka customized with values passed in argument.
    */
  private def createKafkaProperties(kafkaPort: Int, zookeeperServerPort: Int, dataDir: File): Properties = {
    val kafkaProperties = new Properties
    kafkaProperties.put(KafkaConfig.ListenersProp, s"PLAINTEXT://localhost:$kafkaPort")
    kafkaProperties.put(KafkaConfig.ZkConnectProp, s"localhost:$zookeeperServerPort")
    kafkaProperties.put(KafkaConfig.ZkConnectionTimeoutMsProp, "6000")
    kafkaProperties.put(KafkaConfig.BrokerIdProp, "0")
    kafkaProperties.put(KafkaConfig.NumNetworkThreadsProp, "3")
    kafkaProperties.put(KafkaConfig.NumIoThreadsProp, "8")
    kafkaProperties.put(KafkaConfig.SocketSendBufferBytesProp, "102400")
    kafkaProperties.put(KafkaConfig.SocketReceiveBufferBytesProp, "102400")
    kafkaProperties.put(KafkaConfig.SocketRequestMaxBytesProp, "104857600")
    kafkaProperties.put(KafkaConfig.NumPartitionsProp, "1")
    kafkaProperties.put(KafkaConfig.NumRecoveryThreadsPerDataDirProp, "1")
    kafkaProperties.put(KafkaConfig.OffsetsTopicReplicationFactorProp, "1")
    kafkaProperties.put(KafkaConfig.LogRetentionTimeHoursProp, "2")
    kafkaProperties.put(KafkaConfig.LogSegmentBytesProp, "1073741824")
    kafkaProperties.put(KafkaConfig.LogCleanupIntervalMsProp, "300000")
    kafkaProperties.put(KafkaConfig.AutoCreateTopicsEnableProp, "true")
    kafkaProperties.put(KafkaConfig.ControlledShutdownEnableProp, "true")
    kafkaProperties.put(KafkaConfig.LogDirProp, dataDir.getAbsolutePath)

    kafkaProperties
  }

  def deleteDirectory(directory: File): Unit = {
    if (directory.exists() && directory.isDirectory) try {
      FileUtils.deleteDirectory(directory)
    } catch {
      case e: Exception => Log.warn(s"Failed to delete directory ${directory.getAbsolutePath}.", e)
    }
  }

  def dataDirectory(directoryName: String): File = {

    val dataDirectory = new File(basDir + directoryName)
    if (dataDirectory.exists() && !dataDirectory.isDirectory())
      throw new IllegalArgumentException(s"Cannot use $directoryName as a directory name because a file with that name already exists in $dataDirectory.")

    dataDirectory
  }
}

/** A convenience utility for running a local, single instance of ZooKeeper. */
private class ZooKeeperLocalServer(port: Int, cleanOnStart: Boolean) {

  import KafkaLocalServer._
  import ZooKeeperLocalServer._

  private var zooKeeper = null.asInstanceOf[TestingServer]

  def start(): Unit = {
    val zookeeperDataDir = dataDirectory(ZookeeperDataFolderName)
    zooKeeper = new TestingServer(port, zookeeperDataDir, false)
    Log.info(s"Zookeeper data directory is $zookeeperDataDir.")

    if (cleanOnStart) deleteDirectory(zookeeperDataDir)

    zooKeeper.start() // blocking operation
   }

  def stop(): Unit = {
    if (zooKeeper != null)
      try {
        zooKeeper.stop()
        zooKeeper = null.asInstanceOf[TestingServer]
      }
      catch {
        case _: IOException => () // nothing to do if an exception is thrown while shutting down
      }
  }

  def getPort() : Int = port
}

object ZooKeeperLocalServer {
  final val DefaultPort = 2181
  private final val ZookeeperDataFolderName = "zookeeper_data"
}
