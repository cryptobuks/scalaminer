/*
 * ScalaMiner
 * ----------
 * https://github.com/colinrgodsey/scalaminer
 *
 * Copyright 2014 Colin R Godsey <colingodsey.com>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.  See COPYING for more details.
 */

package com.colingodsey.scalaminer

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import java.io.{File, ByteArrayOutputStream, DataOutputStream}
import akka.actor._
import akka.pattern._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.drivers._
import akka.io.{ IO, Tcp }
import com.colingodsey.scalaminer.network.Stratum.Connection
import com.colingodsey.scalaminer.network.{StratumPool, Stratum, StratumProxy, StratumActor}
import spray.can.Http
import akka.util.{Timeout, ByteString}
import com.colingodsey.scalaminer.metrics.{MinerMetrics, MetricsActor}
import com.typesafe.config._
import java.util.concurrent._
import java.util
import com.colingodsey.scalaminer.network.Stratum.Connection
import scala.Some
import com.colingodsey.scalaminer.Work
import scala.concurrent.duration.TimeUnit
import scala.util.{Failure, Success}
import scala.concurrent.Await
import com.colingodsey.io.usb.Usb
import com.colingodsey.scalaminer.api.CGMinerAPI
import com.colingodsey.scalaminer.network.Stratum.Connection
import scala.Some
import com.colingodsey.scalaminer.Work
import com.colingodsey.scalaminer.network.Stratum.Connection
import scala.Some
import com.colingodsey.scalaminer.Work
import com.colingodsey.scalaminer.drivers.bitfury.BitFury
import com.colingodsey.scalaminer.drivers.gridseed.{DualMiner, GridSeed}

object ScalaMiner {
	type BufferType = ByteString
	val BufferType = ByteString

	//type BufferType = Stream[Byte]
	//val BufferType = Stream

  sealed trait HashType

  case object Scrypt extends HashType
  case object SHA256 extends HashType

  //val LTC = Scrypt
}

object ScalaMinerMain extends App {
	val classLoader = getClass.getClassLoader
	val config = ConfigFactory.parseFile(
		new File("./scalaminer.conf")) withFallback ConfigFactory.load(classLoader)
	val smConfig = config.getConfig("com.colingodsey.scalaminer")
	implicit val system = ActorSystem("scalaminer", config, classLoader)

	val usbDrivers: Set[USBDeviceDriver] = Set(DualMiner, BFLSC,
		GridSeed, BitMain, Icarus, BitFury)

	def intOrOne(cfg: Config, key: String) = if(cfg hasPath key) cfg getInt key
	else 1

	def readStConns(cfgVal: ConfigValue): Seq[Connection] = cfgVal match {
		case cfg0: ConfigObject =>
			val cfg = cfg0.toConfig
			Seq(Connection(cfg getString "host", cfg getInt "port",
				cfg getString "user", cfg getString "pass",
				priority = intOrOne(cfg, "priority"),
				weight = intOrOne(cfg, "weight")))
		case x: ConfigList =>
			x.toSeq flatMap readStConns
		case _ => sys.error(s"bad connection cfg (${cfgVal.origin()}}) " + cfgVal)
	}

	val metricsRef = system.actorOf(Props[MetricsActor], name = "metrics")

	val minerAPI = system.actorOf(Props(classOf[CGMinerAPI],
		metricsRef, ScalaMiner.SHA256), name = "api")

	val usbDeviceManager = if(smConfig.hasPath("devices.usb.enabled") &&
			smConfig.getBoolean("devices.usb.enabled")) {
		val enabledDriverNames = smConfig.getStringList("devices.usb.drivers").toSet

		val ref = system.actorOf(Props(classOf[UsbDeviceManager],
			smConfig getConfig "devices.usb"), name = "usb-manager")
		ref.tell(MinerMetrics.Subscribe, metricsRef)

		val enabledDrivers = usbDrivers.filter(
			x => enabledDriverNames(x.toString.toLowerCase))

		enabledDrivers.foreach(x => ref ! UsbDeviceManager.AddDriver(x))
		Some(ref)
	} else None

	if(smConfig.hasPath("stratum.scrypt")) {
		val conns = readStConns(smConfig getValue "stratum.scrypt")
		val connRef = system.actorOf(Props(classOf[StratumPool],
			ScalaMiner.Scrypt), name = "stratum-scrypt")

		conns.foreach(connRef ! StratumPool.AddConnection(_))

		if(usbDeviceManager.isDefined)
			usbDeviceManager.get ! UsbDeviceManager.AddStratumRef(ScalaMiner.Scrypt, connRef)
	}

	if(smConfig.hasPath("stratum.sha256")) {
		val conns = readStConns(smConfig getValue "stratum.sha256")
		val connRef = system.actorOf(Props(classOf[StratumPool],
			ScalaMiner.SHA256), name = "stratum-sha256")

		conns.foreach(connRef ! StratumPool.AddConnection(_))

		if(usbDeviceManager.isDefined)
			usbDeviceManager.get ! UsbDeviceManager.AddStratumRef(ScalaMiner.SHA256, connRef)

		if(smConfig.hasPath("stratum-proxy") && smConfig.getBoolean("stratum-proxy.enabled")) {
			val stratumProxy = system.actorOf(Props(classOf[StratumProxy], connRef,
				smConfig.getConfig("stratum-proxy")), name = "stratum-proxy")

			stratumProxy.tell(MinerMetrics.Subscribe, metricsRef)
		}
	}

	if(usbDeviceManager.isDefined) {
		//start after register stratum
		usbDeviceManager.get ! UsbDeviceManager.Start
	}

	sys addShutdownHook {
		println("Shuttdown down...")
		system.shutdown()
		system.awaitTermination(15.seconds)
		println("Shut down")
	}

	val fs = for(i <- 0 until 150) yield {
		val f = 225 + 25 * i
		val clamped = Icarus.getAntMinerFreq(f).toInt

		val cmdBufPre = Vector[Int](2 | 0x80, (clamped & 0xff00) >> 8,
			clamped & 0x00ff).map(_.toByte)
		//val hex = cmdBufPre.toHex
		val hex = intToBytes(clamped).toHex

		s"${f}, $hex"
	}

	//println(fs.mkString("\n"))
}

object MinerDriver {

}

//should be a case object
trait MinerDriver {
	def identities: Set[_ <: MinerIdentity]

	def submitsAtDifficulty = false
}

//should be a case object
trait MinerIdentity {
	def drv: MinerDriver
}


case class Work(hashType: ScalaMiner.HashType, data: Seq[Byte],
				midstate: Seq[Byte], target: Seq[Byte])

case class Nonce(work: Work, nonce: Seq[Byte], extraNonce: Seq[Byte])



