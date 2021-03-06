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

import com.colingodsey.Sha256
import javax.xml.bind.DatatypeConverter
import akka.util.ByteString
import com.typesafe.config.Config
import scala.concurrent.duration.{FiniteDuration, Duration}
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer
import scala.collection.IndexedSeqOptimized
import java.io.{ObjectOutputStream, IOException, ObjectInputStream}

/**
 * Created by crgodsey on 4/10/14.
 */
package object utils {
	def curTime = System.currentTimeMillis() / 1000

	def intToBytes(x: Int) = {
		//val bs = BigInt(x).toByteArray
		//Seq.fill[Byte](4 - bs.length)(0) ++ bs

		Vector(((x >>> 24) & 0xFF).toByte,
			((x >>> 16) & 0xFF).toByte,
			((x >>> 8 ) & 0xFF).toByte,
			((x      ) & 0xFF).toByte)
	}

	def bintToBytes(x: BigInt, bytes: Int): Seq[Byte] = {
		val bs = x.toByteArray

		Vector.fill[Byte](bytes - bs.length)(0) ++ bs
	}

	//TODO: replace with Cord from MediaMath when OSd
	/** Wrapper class to enable serialization of all seqs */
	@SerialVersionUID(150000343434000010L)
	final class SerializableByteSeq private (seq0: Seq[Byte]) extends Serializable with Equals {
		private def this() {
			this(Nil)
		}
		private var theSeq = seq0

		def seq = theSeq

		@throws[ClassNotFoundException]
		@throws[IOException]
		private def readObject(stream: ObjectInputStream) {
			val len = stream.readInt
			theSeq = Array.fill(len)(stream.readByte.toByte)
		}

		@throws[IOException]
		private def writeObject(stream: ObjectOutputStream) {
			stream.writeInt(seq.length)
			seq.foreach(b => stream.writeByte(b))
		}

		def canEqual(that: Any) = that match {
			case _: SerializableByteSeq => true
			case _: Seq[_] => true
			case _ => false
		}

		override def equals(that: Any) = that match {
			case x: SerializableByteSeq => x.seq == seq
			case x: Seq[_] => seq == x
			case _ => false
		}

		override def toString = seq.toString
	}

	object SerializableByteSeq {
		implicit def seqToSBS(seq: Seq[Byte]): SerializableByteSeq =
			new SerializableByteSeq(seq)

		implicit def toSeq(x: SerializableByteSeq): Seq[Byte] = x.seq
	}

	def reverseInts(dat: Seq[Byte]) = getInts(dat).reverse.flatMap(intToBytes)

	def doubleHash(seq: Iterable[Byte], sha: ScalaSha256 = new ScalaSha256) = {
		//seq.toArray.sha256.bytes.sha256.bytes.toSeq
		sha.update(seq)
		val seq2 = sha.digest()
		sha.update(seq2)
		sha.digestSeq()
	}

	def getInts(dat: Seq[Byte]): Stream[Int] =
		if(dat.isEmpty) Stream.empty
		else Stream(BigInt(dat.take(4).toArray).toInt) append getInts(dat.drop(4))

	def reverseEndian(dat: Seq[Byte]): ScalaMiner.BufferType =
		ScalaMiner.BufferType.empty ++ dat.take(4).reverse ++ {
			val dropped = dat drop 4
			if(dropped.isEmpty) Stream.empty
			else reverseEndian(dropped)
		}

	val hexDigits = "0123456789abcdef".toIndexedSeq

	def bytesToHex(seq: Iterable[Byte]): String =
		bytesToHex(seq.iterator)

	def bytesToHex(itr: Iterator[Byte]): String = {
		val builder = new StringBuilder

		while(itr.hasNext) {
			val byte = itr.next
			val n1 = (byte >>> 4) & 0xF
			val n2 = byte & 0xF

			builder += hexDigits(n1)
			builder += hexDigits(n2)
		}

		builder.result()
	}

	implicit class ByteArrayPimp(val seq: Array[Byte]) extends AnyVal {
		def toHex = utils.bytesToHex(seq.iterator)
	}

	implicit class ByteSeqPimp(val seq: Seq[Byte]) extends AnyVal {
		def reverseEndian = utils.reverseEndian(seq)
		def toHex = utils.bytesToHex(seq)
		def doubleHash = utils.doubleHash(seq)
		def reverseInts = utils.reverseInts(seq)
		/*def serSeq: SerializableByteSeq = seq match {
			case x: SerializableByteSeq => x
			case _ =>
		}*/
	}

	implicit class StringPimp(val str: String) extends AnyVal {
		def fromHex: Seq[Byte] = DatatypeConverter.parseHexBinary(str)
	}

	implicit class ConfigPimp(val cfg: Config) extends AnyVal {
		def getDur(path: String) = {
			val dur = Duration(cfg.getString(path))

			require(dur.isFinite())

			FiniteDuration(dur.toNanos, TimeUnit.NANOSECONDS)
		}
	}

	implicit final class ByteBufferSeq(buf: ByteBuffer) extends IndexedSeq[Byte]
			with IndexedSeqOptimized[Byte, IndexedSeq[Byte]] {
		def apply(idx : Int): Byte = buf.get(idx)
		def length = buf.remaining

		override def stringPrefix: String = "ByteBufferSeq"

		override def seq = this

		//override def slice(start: Int, end: Int): IndexedSeq[Byte] = Cord(this).slice(start, end)

	}
}
