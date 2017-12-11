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
        .asStreamOfKeyValue(ssc)
    } else {
      NatsToSparkConnector
        .receiveFromNats(classOf[Tuple2[Long,Float]], StorageLevel.MEMORY_ONLY)
        .withProperties(properties)
        .withSubjects(inputSubject)
        .withDataDecoder(dataDecoder)
        .asStreamOfKeyValue(ssc)
    }

  // smartmeter.voltage.data.3.3.2   | (2016-11-16T20:05:04,116.366646)
  /**
  line tinyint,    // 8-bit signed int
  transformer int, // 32-bit signed int
  usagePoint int,
  year smallint,    // 16-bit signed int
  month tinyint,
  day tinyint,
  hour tinyint,
  minute tinyint,
  day_of_week tinyint,
  voltage float,
  **/
  val raw_data = voltages.map(
    {case (key, (epoch, voltage)) =>
      // println(key)
      val tokens = key.split('.').reverse
      // println(tokens.mkString("<", ",", ">"))
      val line = tokens(2).toInt
      val transformer = tokens(1).toInt
      val usagePoint = tokens(0).toInt

      import java.time._
      val date = LocalDateTime.ofEpochSecond(epoch, 0, ZoneOffset.UTC)
      val year = date.getYear
      val month = date.getMonthValue
      val day = date.getDayOfYear
      val hour = date.getHour
      val minute = date.getMinute
      val day_of_week = date.getDayOfYear

      (line, transformer, usagePoint, year, month, day, hour, minute, day_of_week, voltage.toFloat)
    })
  raw_data.saveToCassandra("smartmeter", "raw_data")

//  if (logLevel.contains("MESSAGES")) {
    raw_data.print()
//  }
/*
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
  }*/

  // Start //
  ssc.start();

  ssc.awaitTermination()
}