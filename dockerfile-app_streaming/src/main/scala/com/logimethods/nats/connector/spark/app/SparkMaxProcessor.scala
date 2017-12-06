/*******************************************************************************
 * Copyright (c) 2016 Logimethods
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the MIT License (MIT)
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/MIT
 *******************************************************************************/

package com.logimethods.nats.connector.spark.app

import java.util.Properties;
import java.io.File
import java.io.Serializable

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.storage.StorageLevel;
import org.apache.spark.streaming._
import com.datastax.spark.connector.streaming._
import com.datastax.spark.connector.SomeColumns

import io.nats.client.ConnectionFactory._
import java.nio.ByteBuffer

import org.apache.log4j.{Level, LogManager, PropertyConfigurator}

import com.logimethods.connector.nats.to_spark._
import com.logimethods.scala.connector.spark.to_nats._

import java.util.function._

import java.time.{LocalDateTime, ZoneOffset}

object SparkMaxProcessor extends App with SparkStreamingProcessor {
  val log = LogManager.getRootLogger
  log.setLevel(Level.WARN)

  val (properties, target, logLevel, sc, ssc, inputNatsStreaming, inputSubject, outputSubject, clusterId, outputNatsStreaming, natsUrl, streamingDuration) =
    setupStreaming(args)

  // MAX Voltages by Epoch //

//    val inputDataSubject = inputSubject + ".data.>"

  val voltages =
    if (inputNatsStreaming) {
      NatsToSparkConnector
        .receiveFromNatsStreaming(classOf[Tuple2[Long,Float]], StorageLevel.MEMORY_ONLY, clusterId)
        .withNatsURL(natsUrl)
        .withSubjects(inputSubject)
        .withDataDecoder(dataDecoder)
        .asStreamOf(ssc)
    } else {
      NatsToSparkConnector
        .receiveFromNats(classOf[Tuple2[Long,Float]], StorageLevel.MEMORY_ONLY)
        .withProperties(properties)
        .withSubjects(inputSubject)
        .withDataDecoder(dataDecoder)
        .asStreamOf(ssc)
    }

  voltages.saveToCassandra("smartmeter", "raw_data")

  if (logLevel.contains("MESSAGES")) {
    voltages.print()
  }

  val maxByEpoch = voltages.reduceByKey(Math.max(_,_))
  maxByEpoch.saveToCassandra("smartmeter", "max_voltage")

  if (logLevel.contains("MAX")) {
    maxByEpoch.print()
  }

  val maxReport = maxByEpoch.map({case (epoch, voltage) => (s"""{"epoch": $epoch, "voltage": $voltage}""") })
  SparkToNatsConnectorPool.newPool()
                          .withProperties(properties)
                          .withSubjects(outputSubject)
                          .publishToNats(maxReport)

  if (logLevel.contains("MAX_REPORT")) {
    maxReport.print()
  }

  // Start //
  ssc.start();

  ssc.awaitTermination()
}